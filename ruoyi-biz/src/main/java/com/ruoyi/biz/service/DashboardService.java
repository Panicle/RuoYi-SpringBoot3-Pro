package com.ruoyi.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.biz.domain.Approval;
import com.ruoyi.biz.domain.Contract;
import com.ruoyi.biz.domain.Honor;
import com.ruoyi.biz.domain.Notification;
import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.domain.ProjectField;
import com.ruoyi.biz.domain.RdWorktimeMonthly;
import com.ruoyi.biz.mapper.ProjectFieldMapper;
import com.ruoyi.common.utils.DictUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页工作台聚合（/biz/dashboard/summary）
 *
 * <p>全部统计走各业务 Service 既有的 scoped/角色双通道 —— researcher 的数字天然只剩
 * 本人相关范围，dept_leader 本部门，管理角色全所；本 Service 不重复做权限判断，
 * 也不裸查 Mapper。角色差异化展示由前端按 roles 控制卡片显隐。</p>
 *
 * @author kys
 * @date 2026-08-17
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** 待办/预警列表最多返回条数 */
    private static final int MAX_LIST = 5;

    private final IProjectService projectService;
    private final IContractService contractService;
    private final IApprovalService approvalService;
    private final INotificationService notificationService;
    private final IHonorService honorService;
    private final IRdWorktimeService rdWorktimeService;
    private final ProjectFieldMapper projectFieldMapper;

    /**
     * 汇总统计 + 待办 + 预警 + 课题预算执行 TOP5（图表数据）。
     */
    public Map<String, Object> summary() {
        Map<String, Object> data = new LinkedHashMap<>();

        // ---- 课题 / 经费（同一份列表汇总，避免多次扫描） ----
        List<Project> projects = projectService.selectProjectList(new Project());
        int projectActive = 0;
        BigDecimal budgetTotal = BigDecimal.ZERO;
        BigDecimal budgetBalance = BigDecimal.ZERO;
        for (Project p : projects) {
            if ("ACTIVE".equals(p.getStatus())) {
                projectActive++;
            }
            budgetTotal = budgetTotal.add(nz(p.getBudgetTotal()));
            budgetBalance = budgetBalance.add(nz(p.getBudgetBalance()));
        }
        data.put("projectTotal", projects.size());
        data.put("projectActive", projectActive);
        data.put("budgetTotal", budgetTotal);
        data.put("budgetUsed", budgetTotal.subtract(budgetBalance));
        data.put("budgetBalance", budgetBalance);

        // 课题预算执行 TOP5（按预算总额降序；图表：预算 vs 已用）
        List<Map<String, Object>> budgetTop = new ArrayList<>();
        projects.stream()
                .filter(p -> nz(p.getBudgetTotal()).compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(p -> nz(((Project) p).getBudgetTotal())).reversed())
                .limit(5)
                .forEach(p -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", p.getProjectName());
                    row.put("total", nz(p.getBudgetTotal()));
                    row.put("used", nz(p.getBudgetTotal()).subtract(nz(p.getBudgetBalance())));
                    budgetTop.add(row);
                });
        data.put("budgetTop", budgetTop);

        // ---- 专业分类分布（specialty 单值，内存分组，V1.0.21） ----
        Map<String, Integer> specialtyCnt = new HashMap<>();
        for (Project p : projects) {
            if (p.getSpecialty() != null && !p.getSpecialty().isEmpty()) {
                specialtyCnt.merge(p.getSpecialty(), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> specialtyStats = new ArrayList<>();
        for (Map.Entry<String, Integer> e : specialtyCnt.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", e.getKey());
            row.put("label", DictUtils.getDictLabel("specialty", e.getKey()));
            row.put("count", e.getValue());
            specialtyStats.add(row);
        }
        specialtyStats.sort((a, b) -> ((Integer) b.get("count")).compareTo((Integer) a.get("count")));
        data.put("specialtyStats", specialtyStats);

        // ---- 研究领域分布（project_field 多对多，按可见课题聚合，V1.0.21） ----
        List<Long> visibleIds = new ArrayList<>();
        for (Project p : projects) {
            if (p.getProjectId() != null) {
                visibleIds.add(p.getProjectId());
            }
        }
        Map<String, Integer> fieldCnt = new HashMap<>();
        if (!visibleIds.isEmpty()) {
            List<ProjectField> fields = projectFieldMapper.selectList(
                    new LambdaQueryWrapper<ProjectField>().in(ProjectField::getProjectId, visibleIds));
            for (ProjectField f : fields) {
                if (f != null && f.getFieldCode() != null) {
                    fieldCnt.merge(f.getFieldCode(), 1, Integer::sum);
                }
            }
        }
        List<Map<String, Object>> fieldStats = new ArrayList<>();
        for (Map.Entry<String, Integer> e : fieldCnt.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", e.getKey());
            row.put("label", DictUtils.getDictLabel("research_direction", e.getKey()));
            row.put("count", e.getValue());
            fieldStats.add(row);
        }
        fieldStats.sort((a, b) -> ((Integer) b.get("count")).compareTo((Integer) a.get("count")));
        data.put("fieldStats", fieldStats);

        // ---- 在研课题进度列表（ACTIVE + 预算执行率，V1.0.21） ----
        List<Map<String, Object>> activeProjects = new ArrayList<>();
        for (Project p : projects) {
            if (!"ACTIVE".equals(p.getStatus())) {
                continue;
            }
            BigDecimal total = nz(p.getBudgetTotal());
            BigDecimal used = total.subtract(nz(p.getBudgetBalance()));
            BigDecimal progress = total.compareTo(BigDecimal.ZERO) > 0
                    ? used.multiply(new BigDecimal("100")).divide(total, 1, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("projectId", p.getProjectId());
            row.put("projectNo", p.getProjectNo());
            row.put("projectName", p.getProjectName());
            row.put("leaderName", p.getLeaderName());
            row.put("status", p.getStatus());
            row.put("budgetTotal", total);
            row.put("budgetUsed", used);
            row.put("progress", progress);
            activeProjects.add(row);
        }
        activeProjects.sort((a, b) -> ((BigDecimal) b.get("progress")).compareTo((BigDecimal) a.get("progress")));
        data.put("activeProjects", activeProjects.size() > 8 ? activeProjects.subList(0, 8) : activeProjects);

        // ---- 合同 ----
        List<Contract> contracts = contractService.selectContractList(new Contract());
        long contractActive = contracts.stream().filter(c -> "ACTIVE".equals(c.getStatus())).count();
        data.put("contractTotal", contracts.size());
        data.put("contractActive", contractActive);

        // ---- 审批（PENDING=待处理；REJECTED=被驳回，researcher 视角是待重报） ----
        Approval pendingQuery = new Approval();
        pendingQuery.setStatus("PENDING");
        List<Approval> pendings = approvalService.selectApprovalList(pendingQuery);
        Approval rejectedQuery = new Approval();
        rejectedQuery.setStatus("REJECTED");
        List<Approval> rejecteds = approvalService.selectApprovalList(rejectedQuery);
        data.put("approvalPending", pendings == null ? 0 : pendings.size());
        data.put("approvalRejected", rejecteds == null ? 0 : rejecteds.size());

        // ---- 预警（本人未读） ----
        Notification unreadQuery = new Notification();
        unreadQuery.setStatus("UNREAD");
        List<Notification> unreads = notificationService.selectMyNotificationList(unreadQuery);
        data.put("alertUnread", unreads == null ? 0 : unreads.size());
        List<Map<String, Object>> alertList = new ArrayList<>();
        if (unreads != null) {
            for (Notification no : unreads) {
                if (alertList.size() >= MAX_LIST) {
                    break;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("notifyId", no.getNotifyId());
                row.put("title", no.getAlertTitle());
                row.put("alertType", no.getAlertType());
                row.put("alertLevel", no.getAlertLevel());
                alertList.add(row);
            }
        }
        data.put("alerts", alertList);

        // ---- 荣誉 ----
        List<Honor> honors = honorService.selectHonorList(new Honor());
        data.put("honorTotal", honors == null ? 0 : honors.size());

        // ---- 本月研发工时（researcher 通道自动只剩本人行） ----
        String month = new SimpleDateFormat("yyyy-MM").format(new Date());
        RdWorktimeMonthly wtQuery = new RdWorktimeMonthly();
        wtQuery.setMonth(month);
        List<RdWorktimeMonthly> wtRows = rdWorktimeService.selectMonthlyList(wtQuery);
        BigDecimal monthHours = BigDecimal.ZERO;
        if (wtRows != null) {
            for (RdWorktimeMonthly w : wtRows) {
                monthHours = monthHours.add(nz(w.getTotalRdHours()));
            }
        }
        data.put("worktimeMonth", month);
        data.put("worktimeMonthHours", monthHours);

        // ---- 待办事项（按数字组装文案，前端点击跳对应页面） ----
        List<Map<String, Object>> todos = new ArrayList<>();
        if (pendings != null && !pendings.isEmpty()) {
            todos.add(todo("APPROVAL_PENDING", "待处理审批 " + pendings.size() + " 份", "/biz/document"));
        }
        if (rejecteds != null && !rejecteds.isEmpty()) {
            todos.add(todo("APPROVAL_REJECTED", "被驳回资料 " + rejecteds.size() + " 份（可修改后重新提交）", "/biz/document"));
        }
        if (unreads != null && !unreads.isEmpty()) {
            todos.add(todo("ALERT_UNREAD", "未读预警通知 " + unreads.size() + " 条", "/biz/alert/notify"));
        }
        if (monthHours.compareTo(BigDecimal.ZERO) == 0) {
            todos.add(todo("WORKTIME_EMPTY", "本月（" + month + "）研发工时尚未填报", "/biz/rd/worktime"));
        }
        data.put("todos", todos);

        return data;
    }

    private static Map<String, Object> todo(String type, String title, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("path", path);
        return m;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
