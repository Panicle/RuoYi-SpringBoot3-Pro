package com.ruoyi.biz.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 多课题×月聚合行（端点 18 多课题汇总导出中间结构）
 *
 * <p>字段取自 {@code rd_labor_allocation} 按 (project_id, month) 分组的 SUM 结果。
 * month 保留 V1.0.0 原始 VARCHAR(7) 格式 'YYYY-MM'（导出时直接展示）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
public class RdAllocSummaryRow {

    /** 课题ID */
    private Long projectId;

    /** 月份 'YYYY-MM' */
    private String month;

    /** Σalloc（当月该课题全体研究员人工费合计） */
    private BigDecimal sumAlloc;

    /** Σsurcharge（当月该课题全体附加费合计） */
    private BigDecimal sumSurcharge;

    /** Σgrand（Σalloc + Σsurcharge） */
    private BigDecimal sumGrand;
}