package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.RdLaborBudget;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 课题研发人工费预算 Mapper 接口
 *
 * <p>预算端点（Task 2）走应用层查询 — 所有写操作先过 projectService.selectProjectById scoped 闸门，
 * 因此本 Mapper 仅提供基础按 (projectId, year) 与 (projectId, year, month) 查询，
 * 不再额外加 @DataScope 注解。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
public interface RdLaborBudgetMapper extends BaseMapper<RdLaborBudget> {

    /**
     * 按 (projectId, budgetYear) 查有效预算行（含每月）。
     */
    List<RdLaborBudget> selectByProjectAndYear(@Param("projectId") Long projectId,
                                              @Param("budgetYear") Integer budgetYear);

    /**
     * 按 (projectId, budgetYear, month) 查有效单行（唯一索引 idx_rd_labor_budget_year_month 兜底）。
     * 增量 upsert 用：Service 先查后写。
     */
    RdLaborBudget selectByProjectYearMonth(@Param("projectId") Long projectId,
                                           @Param("budgetYear") Integer budgetYear,
                                           @Param("month") Integer month);

    /**
     * 按 (projectId, budgetYear, month) 计数 CONFIRMED 分摊批次是否存在
     * （预算 save 时判定该月是否已锁定，任务卡 D9）。
     */
    int countConfirmedAllocation(@Param("projectId") Long projectId,
                                 @Param("month") Integer month);
}