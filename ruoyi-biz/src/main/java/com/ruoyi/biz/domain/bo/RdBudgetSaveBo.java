package com.ruoyi.biz.domain.bo;

import com.ruoyi.biz.domain.RdLaborBudget;
import lombok.Data;

import java.util.List;

/**
 * 预算保存请求体（端点 2 /biz/rd/budget/save PUT）
 *
 * <p>按 (projectId, budgetYear, month) 增量 upsert（任务卡 D9）：
 * <ul>
 *   <li>未传 month 的预算行原样保留（D9：未传月不动）</li>
 *   <li>有行 UPDATE total_amount，无则 INSERT</li>
 *   <li>该月存在 CONFIRMED 分摊批次 → 拒改该月</li>
 *   <li>ARCHIVED 课题 → 拒保存</li>
 * </ul>
 * </p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
public class RdBudgetSaveBo {

    /** 课题ID */
    private Long projectId;

    /** 预算年度 */
    private Integer year;

    /** 月度预算行（month=1-12；totalAmount 可为 0 表示清零） */
    private List<MonthAmount> months;

    /**
     * 单月预算条目
     */
    @Data
    public static class MonthAmount {

        /** 月份（1-12） */
        private Integer month;

        /** 该月预算总额 */
        private java.math.BigDecimal totalAmount;
    }

    /**
     * 转换为 RdLaborBudget 列表（用于 Service 内迭代 upsert）。
     */
    public List<RdLaborBudget> toBudgetList() {
        if (months == null) {
            return java.util.Collections.emptyList();
        }
        List<RdLaborBudget> list = new java.util.ArrayList<>(months.size());
        for (MonthAmount m : months) {
            if (m == null || m.getMonth() == null) {
                continue;
            }
            RdLaborBudget b = new RdLaborBudget();
            b.setProjectId(projectId);
            b.setBudgetYear(year);
            b.setMonth(m.getMonth());
            b.setTotalAmount(m.getTotalAmount() == null ? java.math.BigDecimal.ZERO : m.getTotalAmount());
            b.setStatus("DRAFT");
            list.add(b);
        }
        return list;
    }
}