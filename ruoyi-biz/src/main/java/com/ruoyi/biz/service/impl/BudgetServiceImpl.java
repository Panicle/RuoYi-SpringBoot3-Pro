package com.ruoyi.biz.service.impl;

import com.ruoyi.biz.domain.BudgetSplit;
import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.service.IBudgetService;
import com.ruoyi.biz.service.IProjectService;
import com.ruoyi.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 课题预算 Service 实现
 *
 * <p>数据权限照 ProjectServiceImpl：所有端点先过 scoped {@code projectService.selectProjectById} 闸门
 * （researcher 亦须本人相关），故本类不再自带 @DataScope SQL。</p>
 *
 * <p>预算细分的写入语义（D1 增量更新、监管上限、乐观锁）集中在 {@link BudgetSupport}，
 * 与课题保存路径（ProjectServiceImpl）共用同一实现。</p>
 *
 * @author kys
 * @date 2026-08-14
 */
@Service
@RequiredArgsConstructor
public class BudgetServiceImpl implements IBudgetService {

    /** 课题状态（与字典 project_status 一致） */
    private static final String STATUS_ARCHIVED = "ARCHIVED";

    /** 预算分组（展示用小计口径：直接费 / 间接费 / 委外支出费 / 税金） */
    private static final String GROUP_DIRECT      = "DIRECT";
    private static final String GROUP_INDIRECT    = "INDIRECT";
    private static final String GROUP_OUTSOURCING = "OUTSOURCING";
    private static final String GROUP_TAX         = "TAX";

    private final IProjectService projectService;
    private final BudgetSupport budgetSupport;

    // ========================================================
    //  查询
    // ========================================================

    @Override
    @Transactional(readOnly = true)
    public List<BudgetSplit> selectBudgetList(Long projectId) {
        if (projectId == null) {
            throw new ServiceException("projectId 不能为空");
        }
        // scoped 闸门（researcher 亦须本人相关；无权访问抛"无权访问"）
        projectService.selectProjectById(projectId);
        return budgetSupport.selectFullSplits(projectId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> selectBudgetSummary(Long projectId) {
        if (projectId == null) {
            throw new ServiceException("projectId 不能为空");
        }
        // scoped 闸门
        Project project = projectService.selectProjectById(projectId);
        List<BudgetSplit> splits = budgetSupport.selectFullSplits(projectId);

        BigDecimal budgetTotal  = BigDecimal.ZERO;
        BigDecimal usedTotal    = BigDecimal.ZERO;
        BigDecimal balanceTotal = BigDecimal.ZERO;
        Map<String, BigDecimal[]> groupSums = new LinkedHashMap<>();
        for (String group : Arrays.asList(GROUP_DIRECT, GROUP_INDIRECT, GROUP_OUTSOURCING, GROUP_TAX)) {
            groupSums.put(group, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        }
        int alertCount = 0;
        for (BudgetSplit split : splits) {
            // 逐行预警标志（§4.3），前端直接按 alertFlag/alertLevel 渲染
            budgetSupport.evaluateAlert(split);
            if (Boolean.TRUE.equals(split.getAlertFlag())) {
                alertCount++;
            }
            budgetTotal  = budgetTotal.add(split.getBudgetAmount());
            usedTotal    = usedTotal.add(split.getUsedAmount());
            balanceTotal = balanceTotal.add(split.getBalance());
            BigDecimal[] sums = groupSums.get(groupOf(split.getCategory()));
            if (sums != null) {
                sums[0] = sums[0].add(split.getBudgetAmount());
                sums[1] = sums[1].add(split.getUsedAmount());
                sums[2] = sums[2].add(split.getBalance());
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("projectId", projectId);
        summary.put("projectNo", project.getProjectNo());
        summary.put("projectName", project.getProjectName());
        summary.put("budgetTotal", BudgetSupport.scale(budgetTotal));
        summary.put("usedTotal", BudgetSupport.scale(usedTotal));
        summary.put("balanceTotal", BudgetSupport.scale(balanceTotal));
        summary.put("alertCount", alertCount);
        summary.put("splits", splits);
        summary.put("groups", buildGroupRows(groupSums));
        return summary;
    }

    // ========================================================
    //  预算调整（§3.1 /adjust + §4.4 + §4.5 增量更新 + D9 乐观锁）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int adjustBudget(Long projectId, List<BudgetSplit> splits, String operName) {
        if (projectId == null) {
            throw new ServiceException("projectId 不能为空");
        }
        if (splits == null || splits.isEmpty()) {
            throw new ServiceException("预算科目不能为空");
        }
        // 1. scoped 闸门（researcher 亦须本人相关）
        Project project = projectService.selectProjectById(projectId);
        if (STATUS_ARCHIVED.equals(project.getStatus())) {
            throw new ServiceException("已归档课题不可调整预算");
        }
        // 2. 按 category 增量更新（含白名单/去重校验、监管上限校验、version 乐观锁）
        //    补丁语义：未传科目保持库中原值
        budgetSupport.applySplits(projectId, splits, false, operName);
        // 3. 同事务重算 project.budget_total / budget_balance（D3）
        budgetSupport.recalcProjectBudget(projectId);
        return splits.size();
    }

    // ========================================================
    //  规则复用（Task 3 记账事务调用）
    // ========================================================

    @Override
    public void validateRegulatoryLimits(List<BudgetSplit> effectiveSplits) {
        budgetSupport.validateRegulatoryLimits(effectiveSplits);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recalcProjectBudget(Long projectId) {
        budgetSupport.recalcProjectBudget(projectId);
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /**
     * 科目 → 展示分组（直接费 7 项 / 间接费 / 委外支出费 / 税金）
     */
    private String groupOf(String category) {
        if (BudgetSupport.DIRECT_CATEGORIES.contains(category)) {
            return GROUP_DIRECT;
        }
        if (GROUP_INDIRECT.equals(category) || GROUP_OUTSOURCING.equals(category) || GROUP_TAX.equals(category)) {
            return category;
        }
        return null;  // 白名单外的历史科目不计入分组小计（仍计入总额）
    }

    private List<Map<String, Object>> buildGroupRows(Map<String, BigDecimal[]> groupSums) {
        // 分组名为展示口径（"直接费"非 budget_category 字典值，四组统一硬编码，与任务卡 §五 一致）
        Map<String, String> names = new LinkedHashMap<>();
        names.put(GROUP_DIRECT, "直接费");
        names.put(GROUP_INDIRECT, "间接费");
        names.put(GROUP_OUTSOURCING, "委外支出费");
        names.put(GROUP_TAX, "税金");
        List<Map<String, Object>> rows = new ArrayList<>(groupSums.size());
        for (Map.Entry<String, BigDecimal[]> entry : groupSums.entrySet()) {
            BigDecimal[] sums = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("groupCode", entry.getKey());
            row.put("groupName", names.get(entry.getKey()));
            row.put("budgetAmount", BudgetSupport.scale(sums[0]));
            row.put("usedAmount", BudgetSupport.scale(sums[1]));
            row.put("balance", BudgetSupport.scale(sums[2]));
            rows.add(row);
        }
        return rows;
    }
}
