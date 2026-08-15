package com.ruoyi.biz.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.biz.domain.Expense;
import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.domain.vo.ConfirmCard;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确认卡片 create_expense 写路径单测（任务简报 F2：真实写路径自动化覆盖）
 *
 * <p>不依赖 LLM、不拉起 Spring 上下文（沿用仓内 RdAllocationAlgorithmTest/ChatServiceDegradeTest 纯函数惯例）：
 * 以内存 FakeRedisCache 代替 Redis、以 JDK 动态 Proxy 充当 {@link IProjectService}/{@link IExpenseService} 替身，
 * 用真实 {@link ObjectMapper} 走"卡片生成 → Redis 序列化/反序列化 → 确认执行 → 记账回调 → 消费"完整链路，
 * 断言 insertExpense 以正确课题/科目/金额被真实调用（返回成功契约 = 带 expenseId 的流水）。</p>
 *
 * <p>覆盖（对应简报 5 项 + 卡片归属闸门 1 项）：
 * <ol>
 *   <li>createExpenseCard 生成卡片（confirmId 非空、Redis 存 {tool:'create_expense', params}）</li>
 *   <li>execute(approved=true) → 调 insertExpense 记账（测试课题/科目），返回成功 + expenseId</li>
 *   <li>execute(approved=false) → 不记账、删卡</li>
 *   <li>双确认（同 confirmId 第二次 execute）→ 第二次失败（卡片已消费防双记）</li>
 *   <li>非本人课题 createExpenseCard → 权限拒绝（scoped 闸门抛"无权访问"）</li>
 *   <li>execute 归属校验：非持卡用户确认 → 拒绝 + 删卡</li>
 * </ol>
 * </p>
 *
 * @author kys
 * @date 2026-08-16
 */
class ChatConfirmServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 测试课题 / 科目 / 金额（与真实记账参数逐字对应） */
    private static final Long PROJECT_ID = 1L;
    private static final BigDecimal AMOUNT = new BigDecimal("100");
    private static final String CATEGORY = "LABOR";
    /** 持卡用户（createExpenseCard 与 execute 的归属锚点） */
    private static final Long USER_ID = 100L;
    private static final String USERNAME = "tester";
    /** Redis key 前缀（与 ChatConfirmService.CONFIRM_KEY_PREFIX 逐字一致） */
    private static final String CONFIRM_KEY_PREFIX = "chat:confirm:";

    // ========================================================
    //  用例 1：createExpenseCard 生成卡片 + Redis 落存
    // ========================================================

    @Test
    void createExpenseCard_generatesCardAndStoresRedis() throws Exception {
        FakeRedisCache redis = new FakeRedisCache();
        ChatConfirmService svc = new ChatConfirmService(redis, OBJECT_MAPPER,
                projectService(project(), null), expenseService(new ArrayList<>()));

        ConfirmCard card = svc.createExpenseCard(PROJECT_ID, AMOUNT, CATEGORY, USER_ID, USERNAME);

        assertNotNull(card, "卡片非空");
        assertNotNull(card.getConfirmId());
        assertFalse(card.getConfirmId().isEmpty(), "confirmId 非空");
        assertEquals("create_expense", card.getTool());
        assertEquals("100.00", card.getParams().get("amount"), "金额以 String 明文存 params（防 JSON 精度丢失）");
        assertEquals(CATEGORY, card.getParams().get("category"));

        // Redis 已存 {tool:'create_expense', userId, username, summary, params}
        String json = redis.getCacheObject(CONFIRM_KEY_PREFIX + card.getConfirmId());
        assertNotNull(json, "Redis 已存确认卡片");
        Map<String, Object> stored = OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        assertEquals("create_expense", stored.get("tool"), "Redis 卡片 tool=create_expense");
        assertEquals(USER_ID.longValue(), ((Number) stored.get("userId")).longValue(), "卡片归属 userId");
        Map<?, ?> params = (Map<?, ?>) stored.get("params");
        assertEquals(PROJECT_ID.longValue(), ((Number) params.get("projectId")).longValue());
        assertEquals("100.00", params.get("amount"));
        assertEquals(CATEGORY, params.get("category"));
    }

    // ========================================================
    //  用例 2：execute(approved=true) → 真实调 insertExpense 记账（返回成功契约）
    // ========================================================

    @Test
    void execute_approvedTrue_callsInsertExpense_realAccountingWiring() {
        FakeRedisCache redis = new FakeRedisCache();
        List<Expense> inserted = new ArrayList<>();
        ChatConfirmService svc = new ChatConfirmService(redis, OBJECT_MAPPER,
                projectService(project(), null), expenseService(inserted));

        ConfirmCard card = svc.createExpenseCard(PROJECT_ID, AMOUNT, CATEGORY, USER_ID, USERNAME);

        Map<String, Object> result = svc.execute(card.getConfirmId(), Boolean.TRUE, USER_ID, USERNAME);

        assertEquals(Boolean.TRUE, result.get("executed"), "approved=true 执行成功");
        assertEquals("记账成功", result.get("message"));
        assertNotNull(result.get("expenseId"), "返回落库 expenseId");
        assertEquals(1, inserted.size(), "insertExpense 被真实调用恰一次");
        Expense saved = inserted.get(0);
        assertEquals(PROJECT_ID, saved.getProjectId(), "记账课题=卡片参数");
        assertEquals(0, new BigDecimal("100.00").compareTo(saved.getAmount()), "记账金额=卡片参数(100.00)");
        assertEquals(CATEGORY, saved.getCategory(), "记账科目=卡片参数");
        assertFalse(redis.hasKey(CONFIRM_KEY_PREFIX + card.getConfirmId()), "执行成功后删卡（卡片已消费）");
    }

    // ========================================================
    //  用例 3：execute(approved=false) → 不记账、删卡
    // ========================================================

    @Test
    void execute_approvedFalse_noAccountingAndDeletesCard() {
        FakeRedisCache redis = new FakeRedisCache();
        List<Expense> inserted = new ArrayList<>();
        ChatConfirmService svc = new ChatConfirmService(redis, OBJECT_MAPPER,
                projectService(project(), null), expenseService(inserted));

        ConfirmCard card = svc.createExpenseCard(PROJECT_ID, AMOUNT, CATEGORY, USER_ID, USERNAME);

        Map<String, Object> result = svc.execute(card.getConfirmId(), Boolean.FALSE, USER_ID, USERNAME);

        assertEquals(Boolean.FALSE, result.get("executed"), "approved=false 取消");
        assertEquals("已取消该操作", result.get("message"));
        assertTrue(inserted.isEmpty(), "取消不触发记账");
        assertFalse(redis.hasKey(CONFIRM_KEY_PREFIX + card.getConfirmId()), "取消后删卡");
    }

    // ========================================================
    //  用例 4：双确认 → 第二次失败，不双记
    // ========================================================

    @Test
    void execute_doubleConfirm_secondFails_noDoubleAccounting() {
        FakeRedisCache redis = new FakeRedisCache();
        List<Expense> inserted = new ArrayList<>();
        ChatConfirmService svc = new ChatConfirmService(redis, OBJECT_MAPPER,
                projectService(project(), null), expenseService(inserted));

        ConfirmCard card = svc.createExpenseCard(PROJECT_ID, AMOUNT, CATEGORY, USER_ID, USERNAME);

        Map<String, Object> first = svc.execute(card.getConfirmId(), Boolean.TRUE, USER_ID, USERNAME);
        assertEquals(Boolean.TRUE, first.get("executed"), "首次确认成功");

        Map<String, Object> second = svc.execute(card.getConfirmId(), Boolean.TRUE, USER_ID, USERNAME);
        assertEquals(Boolean.FALSE, second.get("executed"), "二次确认失败（卡片已消费，乐观防双记）");
        assertEquals("确认卡片不存在或已过期", second.get("message"));
        assertEquals(1, inserted.size(), "不双记：insertExpense 全程只调一次");
    }

    // ========================================================
    //  用例 5：非本人课题 createExpenseCard → 权限拒绝
    // ========================================================

    @Test
    void createExpenseCard_nonOwnedProject_throwsPermissionDenied() {
        FakeRedisCache redis = new FakeRedisCache();
        ChatConfirmService svc = new ChatConfirmService(redis, OBJECT_MAPPER,
                projectService(null, new ServiceException("无权访问")), expenseService(new ArrayList<>()));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.createExpenseCard(PROJECT_ID, AMOUNT, CATEGORY, USER_ID, USERNAME));
        assertTrue(ex.getMessage().contains("无权访问"), "scoped 闸门权限拒绝");
        assertTrue(redis.isEmpty(), "权限拒绝不生成卡片");
    }

    // ========================================================
    //  用例 6（补充）：execute 卡片归属校验 — 非持卡用户确认被拒
    // ========================================================

    @Test
    void execute_otherUser_rejectsAndDeletesCard() {
        FakeRedisCache redis = new FakeRedisCache();
        ChatConfirmService svc = new ChatConfirmService(redis, OBJECT_MAPPER,
                projectService(project(), null), expenseService(new ArrayList<>()));

        ConfirmCard card = svc.createExpenseCard(PROJECT_ID, AMOUNT, CATEGORY, USER_ID, USERNAME);

        Map<String, Object> result = svc.execute(card.getConfirmId(), Boolean.TRUE, 999L, "other");

        assertEquals(Boolean.FALSE, result.get("executed"), "非持卡用户被拒");
        assertEquals("无权确认此操作", result.get("message"));
        assertFalse(redis.hasKey(CONFIRM_KEY_PREFIX + card.getConfirmId()), "无权确认后删卡");
    }

    // ========================================================
    //  测试替身
    // ========================================================

    /** 可访问的测试课题（getProjectNo/getProjectName 供 summary 拼接） */
    private static Project project() {
        Project p = new Project();
        p.setProjectId(PROJECT_ID);
        p.setProjectNo("P-TEST-001");
        p.setProjectName("单测课题");
        return p;
    }

    /**
     * IProjectService 替身（JDK 动态 Proxy）：仅 selectProjectById 有真实行为 —— 可访问返回 project，
     * 无权限抛 denied（模拟真实 ProjectServiceImpl 的 scoped 闸门"无权访问"）；其余方法返回默认值。
     */
    private static IProjectService projectService(final Project project, final RuntimeException denied) {
        return (IProjectService) Proxy.newProxyInstance(
                IProjectService.class.getClassLoader(),
                new Class<?>[] { IProjectService.class },
                (proxy, method, args) -> {
                    if ("selectProjectById".equals(method.getName())) {
                        if (denied != null) {
                            throw denied;
                        }
                        return project;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    /**
     * IExpenseService 替身（JDK 动态 Proxy）：insertExpense 记录入参（断言真实记账的课题/科目/金额）
     * 并返回带 expenseId 的成功流水（对应真实 ExpenseServiceImpl 落库后回填主键的返回契约）；
     * 其余方法返回默认值。
     */
    private static IExpenseService expenseService(final List<Expense> inserted) {
        return (IExpenseService) Proxy.newProxyInstance(
                IExpenseService.class.getClassLoader(),
                new Class<?>[] { IExpenseService.class },
                (proxy, method, args) -> {
                    if ("insertExpense".equals(method.getName())) {
                        Expense incoming = (Expense) args[0];
                        inserted.add(incoming);
                        Expense saved = new Expense();
                        saved.setExpenseId(1001L);
                        saved.setProjectId(incoming.getProjectId());
                        saved.setAmount(incoming.getAmount());
                        saved.setCategory(incoming.getCategory());
                        saved.setCreateBy((String) args[1]);
                        return saved;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    /** 基本类型返回 0/false/0L 等默认值，其余返回 null（替身未被调用的方法不关心返回值） */
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    /** 内存版 RedisCache 替身：以 LinkedHashMap 模拟 Redis 键值存储（set/get/delete/hasKey） */
    static class FakeRedisCache extends RedisCache {

        final Map<String, Object> store = new HashMap<>();

        @Override
        public <T> void setCacheObject(String key, T value, Integer timeout, TimeUnit timeUnit) {
            store.put(key, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getCacheObject(String key) {
            return (T) store.get(key);
        }

        @Override
        public boolean deleteObject(String key) {
            return store.remove(key) != null;
        }

        @Override
        public Boolean hasKey(String key) {
            return store.containsKey(key);
        }

        boolean isEmpty() {
            return store.isEmpty();
        }
    }
}
