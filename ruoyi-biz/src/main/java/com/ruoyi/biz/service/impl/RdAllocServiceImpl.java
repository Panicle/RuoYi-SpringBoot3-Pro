package com.ruoyi.biz.service.impl;

import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.domain.RdLaborAllocation;
import com.ruoyi.biz.domain.RdLaborBudget;
import com.ruoyi.biz.domain.RdResearcherSalary;
import com.ruoyi.biz.domain.RdWorktimeMonthly;
import com.ruoyi.biz.domain.SurchargeRate;
import com.ruoyi.biz.domain.bo.RdAllocCalcBo;
import com.ruoyi.biz.domain.bo.RdAllocRevokeBo;
import com.ruoyi.biz.domain.vo.RdAllocDashboardVo;
import com.ruoyi.biz.mapper.RdLaborAllocationMapper;
import com.ruoyi.biz.mapper.RdLaborBudgetMapper;
import com.ruoyi.biz.mapper.RdResearcherSalaryMapper;
import com.ruoyi.biz.mapper.RdWorktimeMonthlyMapper;
import com.ruoyi.biz.mapper.SurchargeRateMapper;
import com.ruoyi.biz.service.IProjectService;
import com.ruoyi.biz.service.IRdAllocService;
import com.ruoyi.biz.service.impl.RdAllocSupport.CalcResult;
import com.ruoyi.biz.service.impl.RdAllocSupport.Member;
import com.ruoyi.biz.service.impl.RdAllocSupport.Rate;
import com.ruoyi.biz.service.impl.RdAllocSupport.Row;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 研发加计扣除 — 分摊 Service 实现（任务卡 Task 3 端点 11-15）
 *
 * <p>数据权限：所有写端点（calc/confirm/revoke）先过 scoped {@code projectService.selectProjectById} 闸门，
 * 与 Task 2 预算/工时 Service 同口径。researcher 对 /list 与 /dashboard 的他人行 hourlyRate 字段置 null
 * （任务卡 D11 — 工资敏感），其他字段（researcherName/alloc/surcharge/grand）照常返回。</p>
 *
 * <p>算法核心下沉到 {@link RdAllocSupport#calcCore}（纯函数，JUnit 覆盖 9 个场景）；
 * 本类只负责 DB 拉取、状态机、落库、脱敏等副作用。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Service
@RequiredArgsConstructor
public class RdAllocServiceImpl implements IRdAllocService {

    /** 角色 key（与 V1.0.4 sys_role.role_key 一致） */
    private static final String ROLE_RESEARCHER = "researcher";

    /** 课题状态 */
    private static final String STATUS_ARCHIVED = "ARCHIVED";

    /** 批次状态 */
    private static final String STATUS_DRAFT_STR = "DRAFT";
    private static final String STATUS_CONFIRMED_STR = "CONFIRMED";

    /** 月份格式校验：YYYY-MM */
    private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private final RdLaborAllocationMapper rdLaborAllocationMapper;
    private final RdLaborBudgetMapper rdLaborBudgetMapper;
    private final RdResearcherSalaryMapper rdResearcherSalaryMapper;
    private final RdWorktimeMonthlyMapper rdWorktimeMonthlyMapper;
    private final SurchargeRateMapper surchargeRateMapper;
    private final IProjectService projectService;

    // ========================================================
    //  calc（端点 11 /biz/rd/alloc/calc POST）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> calc(RdAllocCalcBo body, String operName) {
        if (body == null) {
            throw new ServiceException("参数不能为空");
        }
        Long projectId = body.getProjectId();
        String month = body.getMonth();
        if (projectId == null || StringUtils.isEmpty(month)) {
            throw new ServiceException("projectId / month 必填");
        }
        if (!MONTH_PATTERN.matcher(month).matches()) {
            throw new ServiceException("month 格式必须为 YYYY-MM");
        }

        // 步骤 1：scoped 闸门 + ARCHIVED 课题拒
        Project project = projectService.selectProjectById(projectId);
        if (STATUS_ARCHIVED.equals(project.getStatus())) {
            throw new ServiceException("已归档课题不可计算分摊");
        }

        // 步骤 2：取预算 B
        Integer year = Integer.parseInt(month.substring(0, 4));
        Integer mInt = Integer.parseInt(month.substring(5, 7));
        RdLaborBudget budgetRow = rdLaborBudgetMapper.selectByProjectYearMonth(projectId, year, mInt);
        if (budgetRow == null || budgetRow.getTotalAmount() == null
                || budgetRow.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException("该课题该月未编制人工费预算");
        }
        BigDecimal budget = scale(budgetRow.getTotalAmount());

        // 步骤 3：检查已存在批次
        List<RdLaborAllocation> existing = rdLaborAllocationMapper.selectByProjectAndMonth(projectId, month);
        boolean hasConfirmed = false;
        List<RdLaborAllocation> drafts = new ArrayList<>();
        for (RdLaborAllocation a : existing) {
            if (STATUS_CONFIRMED_STR.equals(a.getStatus())) {
                hasConfirmed = true;
            } else if (STATUS_DRAFT_STR.equals(a.getStatus())) {
                drafts.add(a);
            }
        }
        if (hasConfirmed) {
            throw new ServiceException("批次已确认，请先撤销确认");
        }
        // 已有 DRAFT → 全部软删（重算重建）
        if (!drafts.isEmpty()) {
            for (RdLaborAllocation d : drafts) {
                rdLaborAllocationMapper.deleteById(d);
            }
        }

        // 步骤 4：取成员（按 researcher_id 升序稳定序）
        List<RdWorktimeMonthly> worktimeRows = rdWorktimeMonthlyMapper.selectMembersByProjectMonth(projectId, month);
        if (worktimeRows.isEmpty()) {
            // 空集 → 返回"无有效工时"，不产生行（任务卡 D9：AjaxResult 正常返回带标志，非异常）
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rows", Collections.emptyList());
            result.put("summary", null);
            result.put("allocLastNegative", false);
            result.put("msg", "无有效工时");
            return result;
        }

        // 步骤 5：逐人取工资；任一人缺 → 拒，列出全部缺薪资人员姓名
        List<Member> members = new ArrayList<>(worktimeRows.size());
        List<String> missingSalary = new ArrayList<>();
        for (RdWorktimeMonthly w : worktimeRows) {
            BigDecimal hours = w.getTotalRdHours();
            if (hours == null || hours.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Long researcherId = w.getResearcherId();
            RdResearcherSalary salaryRow = rdResearcherSalaryMapper.selectByResearcherAndMonth(researcherId, month);
            if (salaryRow == null || salaryRow.getMonthlySalary() == null
                    || salaryRow.getMonthlySalary().compareTo(BigDecimal.ZERO) <= 0) {
                missingSalary.add(String.valueOf(researcherId));
                continue;
            }
            members.add(new Member(researcherId, hours, scale(salaryRow.getMonthlySalary())));
        }
        if (!missingSalary.isEmpty()) {
            throw new ServiceException("以下人员该月未维护工资标准：" + String.join(",", missingSalary));
        }
        if (members.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rows", Collections.emptyList());
            result.put("summary", null);
            result.put("allocLastNegative", false);
            result.put("msg", "无有效工时");
            return result;
        }

        // 取附加费比例（按 rate_id 升序）
        List<SurchargeRate> rateRows = surchargeRateMapper.selectActiveRates();
        if (rateRows.isEmpty()) {
            throw new ServiceException("附加费比例未配置");
        }
        List<Rate> rates = new ArrayList<>(rateRows.size());
        for (SurchargeRate sr : rateRows) {
            rates.add(new Rate(sr.getRateCode(), sr.getRateValue()));
        }

        // 步骤 6-10：调算法核心
        CalcResult calc = RdAllocSupport.calcCore(projectId, month, budget, members, rates);

        // 步骤 9：落库
        String batchNo = "RD" + projectId + "-" + month;
        Date now = new Date();
        List<RdLaborAllocation> persisted = new ArrayList<>(calc.getRows().size());
        for (Row row : calc.getRows()) {
            RdLaborAllocation a = new RdLaborAllocation();
            a.setProjectId(projectId);
            a.setResearcherId(row.getResearcherId());
            a.setMonth(month);
            a.setAllocatedAmount(row.getAllocatedAmount());
            a.setSurchargeTotal(row.getSurchargeTotal());
            a.setGrandTotal(row.getGrandTotal());
            a.setStatus(STATUS_DRAFT_STR);
            a.setBatchNo(batchNo);
            a.setMonthlyHours(row.getMonthlyHours());
            a.setHourlyRate(row.getHourlyRate());
            a.setSurchargeDetail(row.getSurchargeDetailJson());
            a.setDelFlag("0");
            a.setCreateBy(operName);
            a.setCreateTime(now);
            rdLaborAllocationMapper.insert(a);
            persisted.add(a);
        }

        // 步骤 11：返回
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("budget", calc.getBudget());
        summary.put("sumAlloc", calc.getSumAlloc());
        summary.put("sumSurcharge", calc.getSumSurcharge());
        summary.put("sumGrand", calc.getSumGrand());
        summary.put("memberCount", calc.getRows().size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", persisted);
        result.put("summary", summary);
        result.put("allocLastNegative", calc.isAllocLastNegative());
        result.put("msg", "计算完成");
        return result;
    }

    // ========================================================
    //  confirm（端点 13 /biz/rd/alloc/confirm POST）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int confirm(RdAllocCalcBo body, String operName) {
        if (body == null) {
            throw new ServiceException("参数不能为空");
        }
        Long projectId = body.getProjectId();
        String month = body.getMonth();
        if (projectId == null || StringUtils.isEmpty(month)) {
            throw new ServiceException("projectId / month 必填");
        }
        if (!MONTH_PATTERN.matcher(month).matches()) {
            throw new ServiceException("month 格式必须为 YYYY-MM");
        }
        // scoped 闸门
        projectService.selectProjectById(projectId);

        List<RdLaborAllocation> existing = rdLaborAllocationMapper.selectByProjectAndMonth(projectId, month);
        if (existing == null || existing.isEmpty()) {
            throw new ServiceException("该月无分摊批次，请先计算");
        }
        boolean hasDraft = false;
        boolean hasConfirmed = false;
        for (RdLaborAllocation a : existing) {
            if (STATUS_CONFIRMED_STR.equals(a.getStatus())) {
                hasConfirmed = true;
            } else if (STATUS_DRAFT_STR.equals(a.getStatus())) {
                hasDraft = true;
            }
        }
        if (hasConfirmed && !hasDraft) {
            throw new ServiceException("批次已确认，无需重复确认");
        }
        if (!hasDraft) {
            throw new ServiceException("该批次无可确认的草稿行");
        }

        Date now = new Date();
        int n = 0;
        for (RdLaborAllocation a : existing) {
            if (!STATUS_DRAFT_STR.equals(a.getStatus())) {
                continue;
            }
            a.setStatus(STATUS_CONFIRMED_STR);
            a.setConfirmBy(operName);
            a.setConfirmTime(now);
            a.setUpdateBy(operName);
            a.setUpdateTime(now);
            rdLaborAllocationMapper.updateById(a);
            n++;
        }
        return n;
    }

    // ========================================================
    //  revoke（端点 14 /biz/rd/alloc/revoke POST）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int revoke(RdAllocRevokeBo body, String operName) {
        if (body == null) {
            throw new ServiceException("参数不能为空");
        }
        Long projectId = body.getProjectId();
        String month = body.getMonth();
        String reason = body.getReason();
        if (projectId == null || StringUtils.isEmpty(month) || StringUtils.isEmpty(reason)) {
            throw new ServiceException("projectId / month / reason 必填");
        }
        if (!MONTH_PATTERN.matcher(month).matches()) {
            throw new ServiceException("month 格式必须为 YYYY-MM");
        }
        // scoped 闸门
        projectService.selectProjectById(projectId);

        List<RdLaborAllocation> existing = rdLaborAllocationMapper.selectByProjectAndMonth(projectId, month);
        if (existing == null || existing.isEmpty()) {
            throw new ServiceException("该月无分摊批次");
        }
        boolean hasConfirmed = false;
        for (RdLaborAllocation a : existing) {
            if (STATUS_CONFIRMED_STR.equals(a.getStatus())) {
                hasConfirmed = true;
                break;
            }
        }
        if (!hasConfirmed) {
            throw new ServiceException("该批次未确认，无需撤销");
        }

        Date now = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String prefix = "[撤销确认 by " + operName + " at " + sdf.format(now) + "] ";
        int n = 0;
        for (RdLaborAllocation a : existing) {
            if (!STATUS_CONFIRMED_STR.equals(a.getStatus())) {
                continue;
            }
            a.setStatus(STATUS_DRAFT_STR);
            a.setConfirmBy(null);
            a.setConfirmTime(null);
            String oldRemark = a.getRemark() == null ? "" : a.getRemark();
            a.setRemark(prefix + reason + (oldRemark.isEmpty() ? "" : System.lineSeparator() + oldRemark));
            a.setUpdateBy(operName);
            a.setUpdateTime(now);
            rdLaborAllocationMapper.updateById(a);
            n++;
        }
        return n;
    }

    // ========================================================
    //  list（端点 12 /biz/rd/alloc/list GET）
    // ========================================================

    @Override
    public List<RdLaborAllocation> list(Long projectId, String month) {
        if (projectId == null || StringUtils.isEmpty(month)) {
            throw new ServiceException("projectId / month 必填");
        }
        if (!MONTH_PATTERN.matcher(month).matches()) {
            throw new ServiceException("month 格式必须为 YYYY-MM");
        }
        // scoped 闸门
        projectService.selectProjectById(projectId);

        List<RdLaborAllocation> rows = rdLaborAllocationMapper.selectByProjectAndMonth(projectId, month);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        // researcher 对他人行 hourlyRate 置 null（任务卡 D11 — 工资敏感；monthlySalary 不在 allocation 行内无需处理）
        if (isResearcher()) {
            Long me = SecurityUtils.getUserId();
            for (RdLaborAllocation row : rows) {
                if (row.getResearcherId() != null && !row.getResearcherId().equals(me)) {
                    row.setHourlyRate(null);
                }
            }
        }
        return rows;
    }

    // ========================================================
    //  dashboard（端点 15 /biz/rd/alloc/dashboard GET）
    // ========================================================

    @Override
    public RdAllocDashboardVo dashboard(Long projectId, String month) {
        if (projectId == null || StringUtils.isEmpty(month)) {
            throw new ServiceException("projectId / month 必填");
        }
        if (!MONTH_PATTERN.matcher(month).matches()) {
            throw new ServiceException("month 格式必须为 YYYY-MM");
        }
        // scoped 闸门
        projectService.selectProjectById(projectId);

        RdAllocDashboardVo vo = new RdAllocDashboardVo();
        vo.setProjectId(projectId);
        vo.setMonth(month);

        // 预算 B
        Integer year = Integer.parseInt(month.substring(0, 4));
        Integer mInt = Integer.parseInt(month.substring(5, 7));
        RdLaborBudget budgetRow = rdLaborBudgetMapper.selectByProjectYearMonth(projectId, year, mInt);
        BigDecimal budget = (budgetRow == null || budgetRow.getTotalAmount() == null)
                ? null : scale(budgetRow.getTotalAmount());
        vo.setBudget(budget);

        // 批次
        List<RdLaborAllocation> rows = rdLaborAllocationMapper.selectByProjectAndMonth(projectId, month);
        // 脱敏（与 list 一致）
        if (isResearcher() && rows != null && !rows.isEmpty()) {
            Long me = SecurityUtils.getUserId();
            for (RdLaborAllocation row : rows) {
                if (row.getResearcherId() != null && !row.getResearcherId().equals(me)) {
                    row.setHourlyRate(null);
                }
            }
        }

        BigDecimal sumAlloc = BigDecimal.ZERO;
        BigDecimal sumSurcharge = BigDecimal.ZERO;
        BigDecimal sumGrand = BigDecimal.ZERO;
        String status = "NONE";
        if (rows != null && !rows.isEmpty()) {
            for (RdLaborAllocation a : rows) {
                sumAlloc = sumAlloc.add(a.getAllocatedAmount() == null ? BigDecimal.ZERO : a.getAllocatedAmount());
                sumSurcharge = sumSurcharge.add(a.getSurchargeTotal() == null ? BigDecimal.ZERO : a.getSurchargeTotal());
                sumGrand = sumGrand.add(a.getGrandTotal() == null ? BigDecimal.ZERO : a.getGrandTotal());
                if (STATUS_CONFIRMED_STR.equals(a.getStatus())) {
                    status = STATUS_CONFIRMED_STR;
                } else if (STATUS_DRAFT_STR.equals(a.getStatus()) && !"CONFIRMED".equals(status)) {
                    status = STATUS_DRAFT_STR;
                }
            }
        }
        vo.setSumAlloc(scale(sumAlloc));
        vo.setSumSurcharge(scale(sumSurcharge));
        vo.setSumGrand(scale(sumGrand));
        vo.setMemberCount(rows == null ? 0 : rows.size());
        vo.setStatus(status);

        // 闭合判定：Σalloc == B && Σgrand == Σalloc + Σsurcharge
        boolean closed = budget != null
                && vo.getSumAlloc().compareTo(budget) == 0
                && vo.getSumGrand().compareTo(vo.getSumAlloc().add(vo.getSumSurcharge())) == 0;
        vo.setClosed(closed);

        // 引导提示
        StringBuilder hint = new StringBuilder();
        if (budget == null) {
            hint.append("该课题该月未编制人工费预算；");
        }
        if (rows == null || rows.isEmpty()) {
            if (budget != null) {
                hint.append("尚未计算分摊（确认/撤销前请先调用 calc 端点）；");
            }
        }
        if (hint.length() > 0 && hint.charAt(hint.length() - 1) == ';') {
            hint.setLength(hint.length() - 1);
        }
        vo.setHint(hint.length() == 0 ? null : hint.toString());

        vo.setRows(rows);
        return vo;
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /** BigDecimal 2 位 HALF_UP */
    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 当前登录用户是否「精确」为 researcher（不含 admin）— 复用 Task 2 已有写法。
     */
    private boolean isResearcher() {
        try {
            List<SysRole> roles = SecurityUtils.getLoginUser().getUser().getRoles();
            if (roles == null || roles.isEmpty()) {
                return false;
            }
            boolean hasAdmin = false;
            boolean hasResearcher = false;
            for (SysRole r : roles) {
                if (r == null || StringUtils.isEmpty(r.getRoleKey())) {
                    continue;
                }
                if ("admin".equals(r.getRoleKey())) {
                    hasAdmin = true;
                }
                if (ROLE_RESEARCHER.equals(r.getRoleKey())) {
                    hasResearcher = true;
                }
            }
            return hasResearcher && !hasAdmin;
        } catch (Exception e) {
            return false;
        }
    }
}