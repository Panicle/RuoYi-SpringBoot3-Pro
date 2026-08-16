package com.ruoyi.biz.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.biz.domain.Expense;
import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.domain.vo.ConfirmCard;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 确认卡片服务（任务卡 D8/D9 + §三.10：create_expense 写操作先确认后执行）
 *
 * <p>流程：LLM 解析出 create_expense 意图 → {@link #createExpenseCard} 只生成确认卡片
 * （confirmId 存 Redis TTL 5 分钟，value=JSON {tool,userId,summary,params}）不落库；
 * 用户 POST /biz/chat/confirm {confirmId, approved} → {@link #execute}：
 * approved=true 才调 ExpenseService 真实记账，false 丢弃；执行后删 Redis key。</p>
 *
 * <p>金额以 String 明文存 params（避免 JSON 数值精度丢失），执行时 BigDecimal 解析。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatConfirmService {

    /** 确认卡片 Redis TTL（5 分钟，任务卡 D8） */
    private static final long CARD_TTL_MINUTES = 5;

    /** Redis key 前缀 */
    private static final String CONFIRM_KEY_PREFIX = "chat:confirm:";

    /** 工具上下文 key：ConfirmCard[] 单元素数组（ChatService 构建 toolContext 传入，ChatTools 写入） */
    public static final String TOOL_CONTEXT_HOLDER_KEY = "confirmCardHolder";

    private final RedisCache redisCache;
    private final ObjectMapper objectMapper;
    private final IProjectService projectService;
    private final IExpenseService expenseService;

    /**
     * 生成 create_expense 确认卡片（不落库）：先过 scoped 闸门校验课题可访问，
     * 再存 Redis（TTL 5 分钟）并返回卡片给前端回显。
     *
     * @param projectId 课题ID（LLM 输出解析）
     * @param amount    金额（LLM 输出解析，BigDecimal 校验 > 0）
     * @param category  预算科目（字典 budget_category，以 split 为准回填）
     * @return 确认卡片（confirmId/summary/params 回显给用户确认）
     */
    public ConfirmCard createExpenseCard(Long projectId, BigDecimal amount, String category, Long userId,
                                         String username) {
        if (projectId == null) {
            throw new ServiceException("课题不能为空");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException("金额必须大于 0");
        }
        if (StringUtils.isEmpty(category)) {
            throw new ServiceException("预算科目不能为空");
        }
        // scoped 闸门（researcher 亦须本人相关，决策 D7；无权访问抛"无权访问"）
        Project project = projectService.selectProjectById(projectId);

        String confirmId = UUID.randomUUID().toString().replace("-", "");
        BigDecimal scaled = amount.setScale(2, java.math.RoundingMode.HALF_UP);
        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("amount", scaled.toPlainString());
        params.put("category", category);

        StoredCard stored = new StoredCard();
        stored.setTool("create_expense");
        stored.setUserId(userId);
        stored.setUsername(username);
        stored.setSummary("为课题[" + safe(project.getProjectNo()) + " " + safe(project.getProjectName())
                + "]记账 " + scaled.toPlainString() + " 元（科目：" + safe(category) + "）");
        stored.setParams(params);
        storeCard(confirmId, stored);

        ConfirmCard card = new ConfirmCard();
        card.setConfirmId(confirmId);
        card.setTool(stored.getTool());
        card.setSummary(stored.getSummary());
        card.setParams(params);
        card.setExpireAt(new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(CARD_TTL_MINUTES)));
        card.setCreateBy(username);
        return card;
    }

    /**
     * 执行确认回调：approved=true 且 tool=create_expense → 真实记账；否则丢弃。
     * 校验卡片存在、未过期、归属当前用户；执行后删除 Redis key。
     *
     * @return 执行结果（executed/message/expenseId）
     */
    public Map<String, Object> execute(String confirmId, Boolean approved, Long userId, String username) {
        String key = confirmKey(confirmId);
        String json = redisCache.getCacheObject(key);
        if (StringUtils.isEmpty(json)) {
            return result(false, "确认卡片不存在或已过期", null);
        }
        StoredCard card;
        try {
            card = objectMapper.readValue(json, new TypeReference<StoredCard>() { });
        } catch (Exception e) {
            log.warn("确认卡片解析失败 confirmId={}: {}", confirmId, e.getMessage());
            redisCache.deleteObject(key);
            return result(false, "确认卡片无效", null);
        }
        if (card == null || !userId.equals(card.getUserId())) {
            redisCache.deleteObject(key);
            return result(false, "无权确认此操作", null);
        }

        try {
            if (Boolean.TRUE.equals(approved) && "create_expense".equals(card.getTool())) {
                Expense saved = executeCreateExpense(card, username);
                redisCache.deleteObject(key);
                return result(true, "记账成功", saved.getExpenseId());
            }
            redisCache.deleteObject(key);
            return result(false, "已取消该操作", null);
        } catch (ServiceException e) {
            // 记账失败（预算不足/课题状态等）：按 D8 语义失败即丢弃，避免同一卡片重复消费
            redisCache.deleteObject(key);
            return result(false, e.getMessage(), null);
        } catch (Exception e) {
            log.error("确认卡片执行异常 confirmId={}", confirmId, e);
            redisCache.deleteObject(key);
            return result(false, "操作失败，请重试", null);
        }
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /** approved=true 时的真实记账（create_expense 写操作） */
    private Expense executeCreateExpense(StoredCard card, String username) {
        Map<String, Object> params = card.getParams();
        Long projectId = params.get("projectId") == null ? null
                : Long.parseLong(params.get("projectId").toString());
        String amountStr = params.get("amount") == null ? null : params.get("amount").toString();
        String category = params.get("category") == null ? null : params.get("category").toString();
        if (projectId == null || StringUtils.isEmpty(amountStr) || StringUtils.isEmpty(category)) {
            throw new ServiceException("确认卡片参数不完整");
        }
        BigDecimal amount = new BigDecimal(amountStr);

        Expense expense = new Expense();
        expense.setProjectId(projectId);
        expense.setAmount(amount);
        expense.setCategory(category);
        expense.setExpenseDate(new Date());
        expense.setCreateBy(username);
        // 数据权限/预算校验/乐观锁核减全部走 ExpenseService 既有 9 步事务
        return expenseService.insertExpense(expense, username);
    }

    private void storeCard(String confirmId, StoredCard card) {
        try {
            redisCache.setCacheObject(confirmKey(confirmId), objectMapper.writeValueAsString(card),
                    (int) TimeUnit.MINUTES.toSeconds(CARD_TTL_MINUTES), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new ServiceException("确认卡片生成失败，请重试");
        }
    }

    private String confirmKey(String confirmId) {
        return CONFIRM_KEY_PREFIX + confirmId;
    }

    private static Map<String, Object> result(boolean executed, String message, Long expenseId) {
        Map<String, Object> map = new HashMap<>();
        map.put("executed", executed);
        map.put("message", message);
        if (expenseId != null) {
            map.put("expenseId", expenseId);
        }
        return map;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** Redis 存储结构（不暴露给前端；JSON 序列化目标） */
    @Data
    static class StoredCard {
        private String tool;
        private Long userId;
        private String username;
        private String summary;
        private Map<String, Object> params;
    }
}
