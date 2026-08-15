package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.RdLaborBudget;
import com.ruoyi.biz.domain.bo.RdBudgetSaveBo;

import java.util.List;

/**
 * 研发加计扣除 — 预算 Service 接口（任务卡 Task 2 端点 1-2）
 *
 * @author kys
 * @date 2026-08-15
 */
public interface IRdLaborBudgetService {

    /**
     * 查询某课题某年度 12 个月预算行（缺月补零值行 budgetId=null）。
     */
    List<RdLaborBudget> listYearBudget(Long projectId, Integer year);

    /**
     * 增量保存预算（按 projectId+year+month 应用层 upsert；未传月不动；
     * ARCHIVED 课题拒；CONFIRMED 月拒改）。
     */
    int saveBudget(RdBudgetSaveBo body, String operName);
}