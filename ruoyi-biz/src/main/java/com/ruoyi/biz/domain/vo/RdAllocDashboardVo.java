package com.ruoyi.biz.domain.vo;

import com.ruoyi.biz.domain.RdLaborAllocation;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 分摊汇总卡（端点 15 /biz/rd/alloc/dashboard GET）
 *
 * <p>任务卡 Task 3：含 B/Σ三值/人数/状态/闭合标志/无预算或无工时引导提示。
 * rows 字段附带每行详情（含 researcherName + surchargeDetail JSON）便于前端看板一次性渲染。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
public class RdAllocDashboardVo {

    /** 课题ID */
    private Long projectId;

    /** 月份（YYYY-MM） */
    private String month;

    /** 课题状态（NULL=课题不存在） */
    private String projectStatus;

    /** 预算 B（NULL=无预算） */
    private BigDecimal budget;

    /** Σalloc（应 == B） */
    private BigDecimal sumAlloc;

    /** Σsurcharge */
    private BigDecimal sumSurcharge;

    /** Σgrand */
    private BigDecimal sumGrand;

    /** 人数 */
    private Integer memberCount;

    /** 批次状态（DRAFT/CONFIRMED/NONE） */
    private String status;

    /** 闭合标志：Σalloc == B 且 Σgrand == Σalloc+Σsurcharge（无 batch 时 true） */
    private Boolean closed;

    /** 引导提示（无预算/无工时/未计算 等） */
    private String hint;

    /** 每行详情（含 researcherName + surchargeDetail） */
    private List<RdLaborAllocation> rows;
}