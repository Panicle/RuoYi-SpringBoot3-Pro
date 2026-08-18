package com.ruoyi.biz.domain.bo;

import lombok.Data;

import java.util.List;

/**
 * 工时日历响应体（端点 7 /biz/rd/worktime/calendar GET）
 *
 * <p>返回 {days:[{workDate,rdHours,dayTotalAcrossProjects}], monthTotal}。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
public class RdWorktimeCalendarVo {

    /** 月份（YYYY-MM） */
    private String month;

    /** 当月按 (projectId, researcherId) 维度的逐日条目 */
    private List<DayEntry> days;

    /** 当月合计（仅当前 project+researcher 范围） */
    private java.math.BigDecimal monthTotal;

    /** 当月休息日（yyyy-MM-dd；周末+法定节假日，剔除调休上班日。前端置灰禁输，V1.0.19） */
    private List<String> restDays;

    /**
     * 单日条目
     */
    @Data
    public static class DayEntry {

        /** 工作日期（yyyy-MM-dd） */
        private String workDate;

        /** 当日本课题工时（小时） */
        private java.math.BigDecimal rdHours;

        /** 当日该 researcher 跨全部课题合计（小时，前端展示用） */
        private java.math.BigDecimal dayTotalAcrossProjects;
    }
}