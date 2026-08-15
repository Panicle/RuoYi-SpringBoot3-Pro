package com.ruoyi.biz.domain.bo;

import lombok.Data;

import java.util.List;

/**
 * 工时保存请求体（端点 8 /biz/rd/worktime/save PUT）
 *
 * <p>D9 整套校验：
 * <ul>
 *   <li>(projectId, researcherId, month) 上下文校验（scoped 闸门 + ARCHIVED 拒 + researcher 仅本人）</li>
 *   <li>days[*].rdHours：0 视为软删该日；0 &lt; rdHours ≤ 24</li>
 *   <li>跨课题同日合计 ≤ 24（按 researcher+date 汇总校验，含本次值）</li>
 *   <li>同事务重算 (project, researcher, month) 的 rd_worktime_monthly</li>
 * </ul>
 * </p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
public class RdWorktimeSaveBo {

    /** 课题ID */
    private Long projectId;

    /** 研发人员ID */
    private Long researcherId;

    /** 月份（YYYY-MM） */
    private String month;

    /** 当月工时条目 */
    private List<DayHours> days;

    /**
     * 单日工时条目
     */
    @Data
    public static class DayHours {

        /** 工作日期（yyyy-MM-dd） */
        private String workDate;

        /** 当日研发工时（小时；=0 视为软删；0 &lt; rdHours ≤ 24） */
        private java.math.BigDecimal rdHours;
    }
}