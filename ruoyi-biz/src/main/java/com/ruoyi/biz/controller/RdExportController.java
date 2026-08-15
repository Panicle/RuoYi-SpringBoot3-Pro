package com.ruoyi.biz.controller;

import com.ruoyi.biz.service.IRdExportService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.enums.BusinessType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 研发加计扣除 — Excel 导出 Controller（任务卡 Task 4 端点 16-18）
 *
 * <p>三个端点都走 POI 直写到 {@link HttpServletResponse}（void 返回值 + 流式下载，
 * 与前端 {@code proxy.download} 契约一致）。数据可见性全部委托给
 * {@link IRdExportService} 内部 scoped 闸门。</p>
 *
 * <ul>
 *   <li>端点 16 POST /worktime — 权限串 biz:rd:alloc:export — 工时统计表（researcherName/月工时/累计工时）</li>
 *   <li>端点 17 POST /allocation — 权限串 biz:rd:alloc:export — 工资及附加费统计表（10 项附加费 + 合计行）</li>
 *   <li>端点 18 POST /summary — 权限串 biz:rd:alloc:summary — 多课题某年汇总 + 总计行</li>
 * </ul>
 *
 * @author kys
 * @date 2026-08-15
 */
@RestController
@RequestMapping("/biz/rd/export")
@RequiredArgsConstructor
public class RdExportController extends BaseController {

    private final IRdExportService rdExportService;

    /**
     * 端点 16 — 某课题某月工时统计表导出。
     * body: {projectId, month}
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:alloc:export')")
    @Log(title = "工时统计导出", businessType = BusinessType.EXPORT)
    @PostMapping("/worktime")
    public void exportWorktime(HttpServletResponse response, @RequestBody WorktimeExportBody body) {
        rdExportService.exportWorktime(response, body.getProjectId(), body.getMonth());
    }

    /**
     * 端点 17 — 某课题某月工资及附加费统计表导出（10 项附加费逐列 + 合计行）。
     * body: {projectId, month}
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:alloc:export')")
    @Log(title = "工资及附加费统计导出", businessType = BusinessType.EXPORT)
    @PostMapping("/allocation")
    public void exportAllocation(HttpServletResponse response, @RequestBody WorktimeExportBody body) {
        rdExportService.exportAllocation(response, body.getProjectId(), body.getMonth());
    }

    /**
     * 端点 18 — 多课题某年汇总导出（课题×月聚合 + 总计行）。
     * body: {year, projectIds 可空=全部可见课题}
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:alloc:summary')")
    @Log(title = "多课题分摊汇总导出", businessType = BusinessType.EXPORT)
    @PostMapping("/summary")
    public void exportSummary(HttpServletResponse response, @RequestBody SummaryExportBody body) {
        rdExportService.exportSummary(response, body.getYear(), body.getProjectIds());
    }

    /** 端点 16/17 请求体 */
    public static class WorktimeExportBody {
        private Long projectId;
        private String month;

        public Long getProjectId() { return projectId; }
        public void setProjectId(Long projectId) { this.projectId = projectId; }
        public String getMonth() { return month; }
        public void setMonth(String month) { this.month = month; }
    }

    /** 端点 18 请求体 */
    public static class SummaryExportBody {
        private Integer year;
        private List<Long> projectIds;

        public Integer getYear() { return year; }
        public void setYear(Integer year) { this.year = year; }
        public List<Long> getProjectIds() { return projectIds; }
        public void setProjectIds(List<Long> projectIds) { this.projectIds = projectIds; }
    }
}