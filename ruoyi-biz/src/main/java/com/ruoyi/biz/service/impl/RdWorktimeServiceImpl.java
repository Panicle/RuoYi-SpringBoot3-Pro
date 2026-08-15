package com.ruoyi.biz.service.impl;

import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.domain.ProjectMember;
import com.ruoyi.biz.domain.RdWorktimeDaily;
import com.ruoyi.biz.domain.RdWorktimeMonthly;
import com.ruoyi.biz.domain.bo.RdWorktimeCalendarVo;
import com.ruoyi.biz.domain.bo.RdWorktimeCopyVo;
import com.ruoyi.biz.domain.bo.RdWorktimeSaveBo;
import com.ruoyi.biz.mapper.ProjectMemberMapper;
import com.ruoyi.biz.mapper.ProjectMapper;
import com.ruoyi.biz.mapper.RdWorktimeDailyMapper;
import com.ruoyi.biz.mapper.RdWorktimeMonthlyMapper;
import com.ruoyi.biz.service.IRdWorktimeService;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 研发加计扣除 — 工时 Service 实现（任务卡 Task 2 端点 7-10）
 *
 * <p>D9 工时全套校验：scoped 闸门 → ARCHIVED 拒 → researcher 本人校验 → 单日单课题 0&lt;rdHours≤24
 * → 跨课题同日合计≤24 → 同事务重算 rd_worktime_monthly.total_rd_hours / cumulative_hours。
 * rdHours=0 视为软删该日（save 端点语义）。</p>
 *
 * <p>复制上月：按"日序号"映射到本月同号日（上月 29/30/31 号本月不存在则丢弃）；
 * 目标日已有有效记录则跳过不覆盖；返回 copiedCount / skippedCount。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Service
@RequiredArgsConstructor
public class RdWorktimeServiceImpl implements IRdWorktimeService {

    /** 角色 key（与 V1.0.4 sys_role.role_key 一致） */
    private static final String ROLE_RESEARCHER = "researcher";

    /** 课题状态 */
    private static final String STATUS_ARCHIVED = "ARCHIVED";

    /** 成员角色 */
    private static final String MEMBER_HOST = "HOST";

    /** 单日工时上限（含全部课题合计） */
    private static final BigDecimal DAILY_MAX_HOURS = new BigDecimal("24");

    private final RdWorktimeDailyMapper rdWorktimeDailyMapper;
    private final RdWorktimeMonthlyMapper rdWorktimeMonthlyMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final SysUserMapper sysUserMapper;

    // ========================================================
    //  日历（端点 7）
    // ========================================================

    @Override
    @Transactional(readOnly = true)
    public RdWorktimeCalendarVo calendar(Long projectId, Long researcherId, String month) {
        if (projectId == null || researcherId == null || StringUtils.isEmpty(month)) {
            throw new ServiceException("projectId / researcherId / month 必填");
        }
        if (!MONTH_PATTERN.matcher(month).matches()) {
            throw new ServiceException("month 格式必须为 YYYY-MM");
        }
        // researcher 只能查自己（任务卡 D10）
        if (isResearcher()) {
            Long me = userId();
            if (!me.equals(researcherId)) {
                throw new ServiceException("无权查看他人工时");
            }
        }
        // 项目存在性校验（轻量；不强制 scoped — researcher 已在 selfUserId 校验过；
        //   其他角色：调用方已限定到本人相关/本室/全所语义）
        Project p = projectMapper.selectProjectById(projectId);
        if (p == null || !"0".equals(p.getDelFlag())) {
            throw new ServiceException("课题不存在");
        }
        // 用户存在性
        if (sysUserMapper.selectUserById(researcherId) == null) {
            throw new ServiceException("研发人员不存在");
        }

        // 拉取该 (project, researcher, month) 全部有效日行
        List<RdWorktimeDaily> dayList = rdWorktimeDailyMapper.selectByProjectResearcherMonth(projectId, researcherId, month);

        // 同时拉跨课题同日合计（按 (researcher, date) 全表聚合）— 在内存中汇总（数据量单月 ~ 30 行）
        Map<String, BigDecimal> crossDayTotal = new LinkedHashMap<>();
        for (RdWorktimeDaily d : dayList) {
            String key = fmtDate(d.getWorkDate());
            if (key == null) {
                continue;
            }
            List<RdWorktimeDaily> sameDay = rdWorktimeDailyMapper.selectByResearcherAndDate(researcherId, d.getWorkDate());
            BigDecimal sum = BigDecimal.ZERO;
            for (RdWorktimeDaily s : sameDay) {
                sum = sum.add(s.getRdHours() == null ? BigDecimal.ZERO : s.getRdHours());
            }
            crossDayTotal.put(key, scale(sum));
        }

        // 排序 + 汇总
        dayList.sort(Comparator.comparing(RdWorktimeDaily::getWorkDate));
        BigDecimal monthTotal = BigDecimal.ZERO;
        List<RdWorktimeCalendarVo.DayEntry> entries = new ArrayList<>();
        for (RdWorktimeDaily d : dayList) {
            String dateKey = fmtDate(d.getWorkDate());
            RdWorktimeCalendarVo.DayEntry e = new RdWorktimeCalendarVo.DayEntry();
            e.setWorkDate(dateKey);
            e.setRdHours(d.getRdHours());
            e.setDayTotalAcrossProjects(crossDayTotal.get(dateKey));
            entries.add(e);
            monthTotal = monthTotal.add(d.getRdHours() == null ? BigDecimal.ZERO : d.getRdHours());
        }
        RdWorktimeCalendarVo vo = new RdWorktimeCalendarVo();
        vo.setMonth(month);
        vo.setDays(entries);
        vo.setMonthTotal(scale(monthTotal));
        return vo;
    }

    // ========================================================
    //  保存（端点 8）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int saveWorktime(RdWorktimeSaveBo body, String operName) {
        if (body == null) {
            throw new ServiceException("参数不能为空");
        }
        Long projectId = body.getProjectId();
        Long researcherId = body.getResearcherId();
        String month = body.getMonth();
        if (projectId == null || researcherId == null || StringUtils.isEmpty(month)) {
            throw new ServiceException("projectId / researcherId / month 必填");
        }
        if (!MONTH_PATTERN.matcher(month).matches()) {
            throw new ServiceException("month 格式必须为 YYYY-MM");
        }
        // 1. scoped 闸门 + ARCHIVED 拒
        Project project = projectMapper.selectProjectById(projectId);
        if (project == null || !"0".equals(project.getDelFlag())) {
            throw new ServiceException("课题不存在");
        }
        if (STATUS_ARCHIVED.equals(project.getStatus())) {
            throw new ServiceException("已归档课题不可填报工时");
        }
        // 2. researcher 仅本人相关（任务卡 D9）
        if (isResearcher()) {
            Long me = userId();
            if (!me.equals(researcherId)) {
                throw new ServiceException("无权代他人填报工时");
            }
            // 必须是该课题 leader 或有效 project_member
            if (!isProjectMemberForResearcher(projectId, me)) {
                throw new ServiceException("您不是该课题的参与人，无权填报工时");
            }
        } else {
            // 管理组可代填任意人（任务卡 D9）— 仍校验 sys_user 存在
            if (sysUserMapper.selectUserById(researcherId) == null) {
                throw new ServiceException("研发人员不存在");
            }
        }

        // 3. 逐日校验 + upsert
        int upsertCount = 0;
        if (body.getDays() != null) {
            for (RdWorktimeSaveBo.DayHours dh : body.getDays()) {
                if (dh == null) {
                    continue;
                }
                Date workDate = parseDate(dh.getWorkDate());
                if (workDate == null) {
                    throw new ServiceException("工作日期格式错误：" + dh.getWorkDate());
                }
                // 日期必须在指定 month 内
                String dateMonth = new SimpleDateFormat("yyyy-MM").format(workDate);
                if (!dateMonth.equals(month)) {
                    throw new ServiceException("工作日期[" + dh.getWorkDate() + "]不在月份[" + month + "]内");
                }
                BigDecimal hours = dh.getRdHours() == null ? BigDecimal.ZERO : scale(dh.getRdHours());
                if (hours.compareTo(BigDecimal.ZERO) == 0) {
                    // 0 → 视为软删该日
                    RdWorktimeDaily existing = rdWorktimeDailyMapper.selectByProjectResearcherDate(
                            projectId, researcherId, workDate);
                    if (existing != null) {
                        rdWorktimeDailyMapper.deleteById(existing);
                    }
                    continue;
                }
                if (hours.compareTo(DAILY_MAX_HOURS) > 0) {
                    throw new ServiceException("单日单课题工时不能超过 24 小时：" + dh.getWorkDate());
                }
                // 跨课题同日合计 ≤ 24（含本次值）：先拉 (researcher, date) 全部有效行
                List<RdWorktimeDaily> sameDay = rdWorktimeDailyMapper.selectByResearcherAndDate(researcherId, workDate);
                BigDecimal total = BigDecimal.ZERO;
                boolean selfRowFound = false;
                for (RdWorktimeDaily s : sameDay) {
                    if (s.getProjectId().equals(projectId)) {
                        // 本次将更新为 hours，故不累加 s.rd_hours
                        selfRowFound = true;
                    } else {
                        total = total.add(s.getRdHours() == null ? BigDecimal.ZERO : s.getRdHours());
                    }
                }
                total = total.add(hours);
                if (total.compareTo(DAILY_MAX_HOURS) > 0) {
                    throw new ServiceException("跨课题同日合计不能超过 24 小时：" + dh.getWorkDate());
                }

                RdWorktimeDaily existing = rdWorktimeDailyMapper.selectByProjectResearcherDate(
                        projectId, researcherId, workDate);
                if (existing == null) {
                    RdWorktimeDaily ins = new RdWorktimeDaily();
                    ins.setProjectId(projectId);
                    ins.setResearcherId(researcherId);
                    ins.setWorkDate(workDate);
                    ins.setRdHours(hours);
                    ins.setDelFlag("0");
                    ins.setCreateBy(operName);
                    rdWorktimeDailyMapper.insert(ins);
                } else {
                    existing.setRdHours(hours);
                    existing.setUpdateBy(operName);
                    rdWorktimeDailyMapper.updateById(existing);
                }
                upsertCount++;
            }
        }

        // 4. 同事务重算 (project, researcher, month) 月汇总
        recalcMonthly(projectId, researcherId, month, operName);
        return upsertCount;
    }

    // ========================================================
    //  复制上月（端点 9）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RdWorktimeCopyVo copyLastMonth(RdWorktimeSaveBo body, String operName) {
        if (body == null) {
            throw new ServiceException("参数不能为空");
        }
        Long projectId = body.getProjectId();
        Long researcherId = body.getResearcherId();
        String month = body.getMonth();
        if (projectId == null || researcherId == null || StringUtils.isEmpty(month)) {
            throw new ServiceException("projectId / researcherId / month 必填");
        }
        if (!MONTH_PATTERN.matcher(month).matches()) {
            throw new ServiceException("month 格式必须为 YYYY-MM");
        }
        // scoped 闸门 + ARCHIVED 拒 + researcher 本人校验（复用 save 同套）
        Project project = projectMapper.selectProjectById(projectId);
        if (project == null || !"0".equals(project.getDelFlag())) {
            throw new ServiceException("课题不存在");
        }
        if (STATUS_ARCHIVED.equals(project.getStatus())) {
            throw new ServiceException("已归档课题不可复制工时");
        }
        if (isResearcher()) {
            Long me = userId();
            if (!me.equals(researcherId)) {
                throw new ServiceException("无权代他人复制工时");
            }
            if (!isProjectMemberForResearcher(projectId, me)) {
                throw new ServiceException("您不是该课题的参与人，无权复制工时");
            }
        }

        String prevMonth = previousMonth(month);
        List<RdWorktimeDaily> prevDays = rdWorktimeDailyMapper.selectByProjectResearcherMonth(projectId, researcherId, prevMonth);
        if (prevDays == null || prevDays.isEmpty()) {
            return new RdWorktimeCopyVo(0, 0);
        }
        // 目标月最大日数
        int targetYear  = Integer.parseInt(month.substring(0, 4));
        int targetMonth = Integer.parseInt(month.substring(5, 7));
        Calendar cal = Calendar.getInstance();
        cal.set(targetYear, targetMonth - 1, 1);  // targetMonth-1 = 当月首日
        int targetMaxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        int copied = 0, skipped = 0;
        for (RdWorktimeDaily src : prevDays) {
            Calendar srcCal = Calendar.getInstance();
            srcCal.setTime(src.getWorkDate());
            int day = srcCal.get(Calendar.DAY_OF_MONTH);
            if (day > targetMaxDay) {
                // 本月不存在该日 → 丢弃
                continue;
            }
            Calendar targetCal = Calendar.getInstance();
            targetCal.set(targetYear, targetMonth - 1, day, 0, 0, 0);
            targetCal.set(Calendar.MILLISECOND, 0);
            Date targetDate = targetCal.getTime();
            // 跨课题同日合计校验（含复制值）— 复用 save 同口径
            List<RdWorktimeDaily> sameDay = rdWorktimeDailyMapper.selectByResearcherAndDate(researcherId, targetDate);
            BigDecimal total = BigDecimal.ZERO;
            for (RdWorktimeDaily s : sameDay) {
                total = total.add(s.getRdHours() == null ? BigDecimal.ZERO : s.getRdHours());
            }
            total = total.add(src.getRdHours() == null ? BigDecimal.ZERO : src.getRdHours());
            if (total.compareTo(DAILY_MAX_HOURS) > 0) {
                // 跨课题同日合计超限 → 跳过该日（不让复制失败影响整体）
                skipped++;
                continue;
            }
            // 目标日已存在则跳过不覆盖（任务卡 D9）
            RdWorktimeDaily existing = rdWorktimeDailyMapper.selectByProjectResearcherDate(
                    projectId, researcherId, targetDate);
            if (existing != null) {
                skipped++;
                continue;
            }
            RdWorktimeDaily ins = new RdWorktimeDaily();
            ins.setProjectId(projectId);
            ins.setResearcherId(researcherId);
            ins.setWorkDate(targetDate);
            ins.setRdHours(scale(src.getRdHours()));
            ins.setDelFlag("0");
            ins.setCreateBy(operName);
            rdWorktimeDailyMapper.insert(ins);
            copied++;
        }
        // 重算目标月汇总
        recalcMonthly(projectId, researcherId, month, operName);
        return new RdWorktimeCopyVo(copied, skipped);
    }

    // ========================================================
    //  月度汇总分页（端点 10）
    // ========================================================

    @Override
    public List<RdWorktimeMonthly> selectMonthlyList(RdWorktimeMonthly query) {
        if (query == null) {
            query = new RdWorktimeMonthly();
        }
        // researcher：仅本人行（任务卡 D10 数据权限首档）
        if (isResearcher()) {
            Long me = userId();
            query.getParams().put("selfUserId", me);
            return rdWorktimeMonthlyMapper.selectRdWorktimeMonthlyListForResearcher(query);
        }
        // dept_leader / science_admin / leader / admin / labor_hr / office 走 @DataScope 三档
        return rdWorktimeMonthlyMapper.selectRdWorktimeMonthlyList(query);
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /**
     * 同事务重算 (project, researcher, month) 月汇总（任务卡 D9）。
     * 计算 total_rd_hours = Σ 当月有效日行；cumulative_hours = Σ 至 month 止的全部有效日行。
     */
    private void recalcMonthly(Long projectId, Long researcherId, String month, String operName) {
        BigDecimal total = rdWorktimeDailyMapper.sumRdHoursByProjectResearcherMonth(projectId, researcherId, month);
        BigDecimal cumulative = rdWorktimeDailyMapper.sumCumulativeHours(projectId, researcherId, month);
        RdWorktimeMonthly db = rdWorktimeMonthlyMapper.selectByProjectResearcherMonth(projectId, researcherId, month);
        if (db == null) {
            // 无汇总行 → 新建（即使 total=0 也要保留行：用户全部 0 提交后仍可见）
            RdWorktimeMonthly ins = new RdWorktimeMonthly();
            ins.setProjectId(projectId);
            ins.setResearcherId(researcherId);
            ins.setMonth(month);
            ins.setTotalRdHours(scale(total));
            ins.setCumulativeHours(scale(cumulative));
            ins.setDelFlag("0");
            ins.setCreateBy(operName);
            rdWorktimeMonthlyMapper.insert(ins);
        } else {
            db.setTotalRdHours(scale(total));
            db.setCumulativeHours(scale(cumulative));
            db.setUpdateBy(operName);
            rdWorktimeMonthlyMapper.updateById(db);
        }
    }

    /** 当前登录用户 ID（非空；登录上下文必有） */
    private static Long userId() {
        return SecurityUtils.getUserId();
    }

    /**
     * researcher 在该 (projectId, userId) 是否为 leader 或有效 member（任务卡 D9）。
     */
    private boolean isProjectMemberForResearcher(Long projectId, Long userId) {
        Project p = projectMapper.selectProjectById(projectId);
        if (p == null) {
            return false;
        }
        if (userId.equals(p.getLeaderId())) {
            return true;
        }
        ProjectMember m = projectMemberMapper.selectMemberByUser(projectId, userId);
        return m != null && MEMBER_HOST.equalsIgnoreCase(m.getRole());
    }

    /** 角色判定（照 ProjectServiceImpl.java:672-696 风格） */
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

    /** 月份格式校验：YYYY-MM */
    private static final java.util.regex.Pattern MONTH_PATTERN = java.util.regex.Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    /** BigDecimal 2 位 HALF_UP */
    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    /** Date → yyyy-MM-dd 字符串 */
    private static String fmtDate(Date d) {
        if (d == null) {
            return null;
        }
        return new SimpleDateFormat("yyyy-MM-dd").format(d);
    }

    /** yyyy-MM-dd 字符串 → Date（00:00:00） */
    private static Date parseDate(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            sdf.setLenient(false);
            return sdf.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 上一个月（YYYY-MM）；不变原 month */
    private static String previousMonth(String month) {
        int y = Integer.parseInt(month.substring(0, 4));
        int m = Integer.parseInt(month.substring(5, 7));
        Calendar c = Calendar.getInstance();
        c.set(y, m - 2, 1);
        return new SimpleDateFormat("yyyy-MM").format(c.getTime());
    }
}