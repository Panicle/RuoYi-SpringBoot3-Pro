package com.ruoyi.biz.service.impl;

import com.ruoyi.biz.domain.RdResearcherSalary;
import com.ruoyi.biz.mapper.RdResearcherSalaryMapper;
import com.ruoyi.biz.service.IRdResearcherSalaryService;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 研发加计扣除 — 工资 Service 实现（任务卡 Task 2 端点 3-6）
 *
 * <p>数据权限照 ExpenseServiceImpl/HonorServiceImpl 双通道：列表走 @DataScope 注解，
 * researcher 走"本人相关"专用 SQL；导入/导出复用列表同通道。save 按
 * (researcherId, salaryMonth) 应用层查重 upsert（DB 唯一索引 idx_rd_researcher_salary_uk 兜底）。</p>
 *
 * <p>任务卡 D11：researcher 角色访问 /salary/list 直接拒（Service 兜底）—— 即使菜单
 * 串已挂 admin/science_admin/leader/labor_hr/researcher（researcher 应无 list 串，
 * 本层兜底防漏挂）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Service
@RequiredArgsConstructor
public class RdResearcherSalaryServiceImpl implements IRdResearcherSalaryService {

    /** 角色 key（与 V1.0.4 sys_role.role_key 一致） */
    private static final String ROLE_RESEARCHER = "researcher";

    /** 工资月份格式校验：YYYY-MM */
    private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private final RdResearcherSalaryMapper rdResearcherSalaryMapper;
    private final SysUserMapper sysUserMapper;

    // ========================================================
    //  列表（端点 3）
    // ========================================================

    @Override
    public List<RdResearcherSalary> selectSalaryList(RdResearcherSalary query) {
        if (query == null) {
            query = new RdResearcherSalary();
        }
        // D11：researcher 角色直接拒（即使权限串漏挂也兜底）
        if (isResearcher()) {
            throw new ServiceException("无权查看工资数据");
        }
        return rdResearcherSalaryMapper.selectRdResearcherSalaryList(query);
    }

    // ========================================================
    //  保存（端点 4）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RdResearcherSalary saveSalary(RdResearcherSalary salary, String operName) {
        if (salary == null) {
            throw new ServiceException("参数不能为空");
        }
        if (salary.getResearcherId() == null) {
            throw new ServiceException("研发人员不能为空");
        }
        if (StringUtils.isEmpty(salary.getSalaryMonth())) {
            throw new ServiceException("工资月份不能为空");
        }
        if (!MONTH_PATTERN.matcher(salary.getSalaryMonth()).matches()) {
            throw new ServiceException("工资月份格式必须为 YYYY-MM");
        }
        BigDecimal amount = salary.getMonthlySalary() == null ? BigDecimal.ZERO : scale(salary.getMonthlySalary());
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException("月度工资必须大于 0");
        }
        // 用户存在性校验（D2：researcher_id = sys_user.user_id）
        SysUser u = sysUserMapper.selectUserById(salary.getResearcherId());
        if (u == null || !"0".equals(u.getDelFlag())) {
            throw new ServiceException("研发人员不存在");
        }

        // 应用层查重 upsert（DB 唯一索引 idx_rd_researcher_salary_uk 兜底）
        RdResearcherSalary db = rdResearcherSalaryMapper.selectByResearcherAndMonth(
                salary.getResearcherId(), salary.getSalaryMonth());
        salary.setMonthlySalary(amount);
        if (db == null) {
            salary.setDelFlag("0");
            salary.setCreateBy(operName);
            try {
                rdResearcherSalaryMapper.insert(salary);
            } catch (DuplicateKeyException e) {
                throw new ServiceException("该研发人员该月工资已存在");
            }
        } else {
            db.setMonthlySalary(amount);
            db.setUpdateBy(operName);
            rdResearcherSalaryMapper.updateById(db);
            salary.setSalaryId(db.getSalaryId());
        }
        // 回填 researcherName（按列表 vo 同口径）
        salary.setResearcherName(u.getNickName());
        return salary;
    }

    // ========================================================
    //  导出（端点 6）
    // ========================================================

    @Override
    public List<RdResearcherSalary> exportSalary(RdResearcherSalary query) {
        if (query == null) {
            query = new RdResearcherSalary();
        }
        if (isResearcher()) {
            throw new ServiceException("无权导出工资数据");
        }
        return rdResearcherSalaryMapper.selectRdResearcherSalaryList(query);
    }

    // ========================================================
    //  导入（端点 5）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String importSalary(List<RdResearcherSalary> rows, String operName) {
        if (rows == null || rows.isEmpty()) {
            throw new ServiceException("导入数据不能为空");
        }
        int success = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            RdResearcherSalary row = rows.get(i);
            int lineNo = i + 1;  // Excel 行号（数据行从 1 起）
            try {
                if (row == null) {
                    throw new ServiceException("空行");
                }
                // 行级校验复用 save 校验（不含用户查重兜底由 save 内部处理）
                if (row.getResearcherId() == null) {
                    throw new ServiceException("研发人员ID不能为空");
                }
                if (StringUtils.isEmpty(row.getSalaryMonth())) {
                    throw new ServiceException("工资月份不能为空");
                }
                if (!MONTH_PATTERN.matcher(row.getSalaryMonth()).matches()) {
                    throw new ServiceException("工资月份格式必须为 YYYY-MM");
                }
                BigDecimal amount = row.getMonthlySalary() == null ? BigDecimal.ZERO : scale(row.getMonthlySalary());
                if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new ServiceException("月度工资必须大于 0");
                }
                row.setMonthlySalary(amount);
                row.setDelFlag("0");
                // 用户存在性校验（直接走 mapper 查 sys_user）
                SysUser u = sysUserMapper.selectUserById(row.getResearcherId());
                if (u == null || !"0".equals(u.getDelFlag())) {
                    throw new ServiceException("研发人员不存在");
                }
                // 复用单条 upsert 通道（一致性最高）
                saveSalary(row, operName);
                success++;
            } catch (Exception e) {
                errors.add("第 " + lineNo + " 行：" + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            // 行级错误汇总抛出（任一行失败整体回滚？— RuoYi ExcelUtil 惯例是汇总报，由 Controller 决定是否 rollback；
            // 本任务简报说"行级校验汇总报错" — 故即便部分成功也报汇总，事务整体回滚）
            throw new ServiceException("导入失败：" + System.lineSeparator() + String.join(System.lineSeparator(), errors));
        }
        return "导入成功 " + success + " 条";
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /** 金额 2 位小数 HALF_UP（决策 D9） */
    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 当前登录用户是否「精确」为 researcher（不含 admin）。
     * 照 ProjectServiceImpl.java:672-696 实现风格，单次遍历 + hasAdmin/hasResearcher 标志。
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