package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.BudgetSplit;

import java.util.List;
import java.util.Map;

/**
 * 课题预算 Service 接口（预算细分为预算与余额的事实来源，决策 D1/D3）
 *
 * <p>数据权限：全部端点先过 scoped {@code projectService.selectProjectById(projectId)} 闸门
 * （researcher 亦须本人相关课题），不在范围内抛"无权访问"。</p>
 *
 * @author kys
 * @date 2026-08-14
 */
public interface IBudgetService {

    /**
     * 查询课题的十科目预算行（budget/used/balance/version）。
     * 库中缺失的科目补零值占位行（splitId/version 为 null），便于前端直接渲染与回填。
     */
    List<BudgetSplit> selectBudgetList(Long projectId);

    /**
     * 预算调整（§3.1 /adjust）：按 category 增量更新保 split_id、带 version 乐观锁、
     * 监管上限校验、同事务重算 balance 与 project.budget_total/budget_balance。
     *
     * <p>补丁语义：只改传入的科目，未传科目保持库中原值（与课题保存的全量终态语义不同）。</p>
     *
     * @return 本次提交的科目行数
     */
    int adjustBudget(Long projectId, List<BudgetSplit> splits, String operName);

    /**
     * 课题预算汇总：总额/已用/余额 + 各分组小计 + 逐行预警标志（alertFlag/alertLevel，§4.3）
     */
    Map<String, Object> selectBudgetSummary(Long projectId);

    /**
     * 监管上限校验（§4.4）。入参须为保存后的完整终态科目清单。
     * 课题保存路径直接复用 BudgetSupport 同名方法（避免 Service 间循环依赖）。
     */
    void validateRegulatoryLimits(List<BudgetSplit> effectiveSplits);

    /**
     * 重算并回写 project.budget_total = Σ budget_amount、budget_balance = Σ balance（D3）。
     * 记账/作废/冲销事务同样调用。
     */
    void recalcProjectBudget(Long projectId);
}
