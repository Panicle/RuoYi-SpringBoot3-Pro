package com.ruoyi.biz.service.impl;

import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.domain.RdLaborBudget;
import com.ruoyi.biz.domain.bo.RdBudgetSaveBo;
import com.ruoyi.biz.mapper.RdLaborAllocationMapper;
import com.ruoyi.biz.mapper.RdLaborBudgetMapper;
import com.ruoyi.biz.service.IRdLaborBudgetService;
import com.ruoyi.biz.service.IProjectService;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 研发加计扣除 — 预算 Service 实现（任务卡 Task 2 端点 1-2）
 *
 * <p>数据权限：所有端点先过 scoped {@code projectService.selectProjectById} 闸门
 * （researcher 亦须本人相关，抛"无权访问"），故本类不再自带 @DataScope SQL。</p>
 *
 * <p>金额一律 BigDecimal，2 位小数 HALF_UP（决策 D9）。
 * 增量 upsert 语义（任务卡 D1/D9）：
 * <ul>
 *   <li>未传月不动（库中已有行 UPDATE；无月预算不传则保留原值）</li>
 *   <li>CONFIRMED 月（rd_labor_allocation status='CONFIRMED' 命中）→ 拒改该月</li>
 *   <li>ARCHIVED 课题 → 拒保存</li>
 * </ul>
 * </p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Service
@RequiredArgsConstructor
public class RdLaborBudgetServiceImpl implements IRdLaborBudgetService {

    /** 课题状态（与字典 project_status 一致） */
    private static final String STATUS_ARCHIVED = "ARCHIVED";

    private final RdLaborBudgetMapper rdLaborBudgetMapper;
    private final RdLaborAllocationMapper rdLaborAllocationMapper;
    private final IProjectService projectService;

    // ========================================================
    //  查询（端点 1 /biz/rd/budget/list GET）
    // ========================================================

    @Override
    @Transactional(readOnly = true)
    public List<RdLaborBudget> listYearBudget(Long projectId, Integer year) {
        if (projectId == null) {
            throw new ServiceException("projectId 不能为空");
        }
        if (year == null) {
            throw new ServiceException("year 不能为空");
        }
        // scoped 闸门（researcher 走本人相关）
        projectService.selectProjectById(projectId);

        List<RdLaborBudget> dbRows = rdLaborBudgetMapper.selectByProjectAndYear(projectId, year);
        // 12 个月缺月补零值行 budgetId=null（端点 1 约定）
        java.util.Map<Integer, RdLaborBudget> byMonth = new java.util.LinkedHashMap<>();
        for (RdLaborBudget b : dbRows) {
            if (b != null && b.getMonth() != null && b.getMonth() >= 1 && b.getMonth() <= 12) {
                byMonth.put(b.getMonth(), b);
            }
        }
        List<RdLaborBudget> result = new ArrayList<>(12);
        for (int m = 1; m <= 12; m++) {
            RdLaborBudget row = byMonth.get(m);
            if (row == null) {
                row = new RdLaborBudget();
                row.setProjectId(projectId);
                row.setBudgetYear(year);
                row.setMonth(m);
                row.setTotalAmount(BigDecimal.ZERO);
                row.setStatus("DRAFT");
            }
            result.add(row);
        }
        return result;
    }

    // ========================================================
    //  保存（端点 2 /biz/rd/budget/save PUT）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int saveBudget(RdBudgetSaveBo body, String operName) {
        if (body == null) {
            throw new ServiceException("参数不能为空");
        }
        Long projectId = body.getProjectId();
        Integer year = body.getYear();
        if (projectId == null) {
            throw new ServiceException("projectId 不能为空");
        }
        if (year == null) {
            throw new ServiceException("year 不能为空");
        }
        // scoped 闸门 + ARCHIVED 拒
        Project project = projectService.selectProjectById(projectId);
        if (STATUS_ARCHIVED.equals(project.getStatus())) {
            throw new ServiceException("已归档课题不可调整预算");
        }

        List<RdLaborBudget> requests = body.toBudgetList();
        if (requests.isEmpty()) {
            // 未传月：未传月不动（任务卡 D9 增量语义）
            return 0;
        }

        int n = 0;
        for (RdLaborBudget req : requests) {
            Integer month = req.getMonth();
            if (month == null || month < 1 || month > 12) {
                throw new ServiceException("月份必须为 1-12 整数");
            }
            BigDecimal amount = req.getTotalAmount() == null ? BigDecimal.ZERO : scale(req.getTotalAmount());
            if (amount.compareTo(BigDecimal.ZERO) < 0) {
                throw new ServiceException("预算金额不能为负数");
            }

            // CONFIRMED 分摊批次锁定 → 拒改该月（month 列是 VARCHAR 'YYYY-MM'，拼字符串等值查询，达梦兼容）
            String monthStr = String.format("%d-%02d", year, month);
            if (rdLaborAllocationMapper.countConfirmedByProjectAndMonthInt(projectId, monthStr) > 0) {
                throw new ServiceException("[" + monthStr + "] 已确认分摊，预算不可修改");
            }

            RdLaborBudget db = rdLaborBudgetMapper.selectByProjectYearMonth(projectId, year, month);
            if (db == null) {
                // 无行 → INSERT
                RdLaborBudget ins = new RdLaborBudget();
                ins.setProjectId(projectId);
                ins.setBudgetYear(year);
                ins.setMonth(month);
                ins.setTotalAmount(amount);
                ins.setStatus("DRAFT");
                ins.setDelFlag("0");
                ins.setCreateBy(operName);
                try {
                    rdLaborBudgetMapper.insert(ins);
                } catch (DuplicateKeyException e) {
                    // 极端：物理冲突（DB 无唯一索引，应用层查重）
                    throw new ServiceException("该月预算已存在");
                }
            } else {
                // 有行 → UPDATE（amount 不变也走 UPDATE，DB 零成本；保留原 status 不动）
                db.setTotalAmount(amount);
                db.setUpdateBy(operName);
                rdLaborBudgetMapper.updateById(db);
            }
            n++;
        }
        return n;
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /** 金额 2 位小数 HALF_UP（决策 D9） */
    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }
}