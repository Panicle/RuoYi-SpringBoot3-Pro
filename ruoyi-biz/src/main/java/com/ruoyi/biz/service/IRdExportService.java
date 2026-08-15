package com.ruoyi.biz.service;

import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

/**
 * 研发加计扣除 — Excel 导出 Service 接口（任务卡 Task 4 端点 16-18）
 *
 * <p>三个端点统一走 POI 直写（XSSFWorkbook）到 HttpServletResponse，避免 ExcelUtil
 * 对动态列 / 自定义合计行的限制。三端点都先过 {@code projectService.selectProjectById}
 * scoped 闸门，权限串已挡非管理组。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
public interface IRdExportService {

    /**
     * 端点 16 — 某课题某月工时统计表（researcherName/月工时/累计工时 三列平表）。
     *
     * @param response  HTTP 响应流
     * @param projectId 课题 ID
     * @param month     'YYYY-MM'
     */
    void exportWorktime(HttpServletResponse response, Long projectId, String month);

    /**
     * 端点 17 — 某课题某月工资及附加费统计表（researcherName + monthlyHours + hourlyRate
     * + allocatedAmount + 10 项附加费按 rate_id 升序列 + surchargeTotal + grandTotal + 合计行）。
     *
     * @param response  HTTP 响应流
     * @param projectId 课题 ID
     * @param month     'YYYY-MM'
     */
    void exportAllocation(HttpServletResponse response, Long projectId, String month);

    /**
     * 端点 18 — 多课题某年汇总（课题 × 月聚合 Σalloc/Σsurcharge/Σgrand + 总计行）。
     *
     * @param response   HTTP 响应流
     * @param year       4 位年份
     * @param projectIds 课题 ID 列表（null/空=全部可见课题）
     */
    void exportSummary(HttpServletResponse response, Integer year, List<Long> projectIds);
}