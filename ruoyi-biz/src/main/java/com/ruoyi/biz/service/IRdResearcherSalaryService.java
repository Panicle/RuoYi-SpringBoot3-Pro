package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.RdResearcherSalary;

import java.util.List;

/**
 * 研发加计扣除 — 工资 Service 接口（任务卡 Task 2 端点 3-6）
 *
 * @author kys
 * @date 2026-08-15
 */
public interface IRdResearcherSalaryService {

    /**
     * 工资分页列表（researcher 角色 Service 兜底拒 — D11）。
     */
    List<RdResearcherSalary> selectSalaryList(RdResearcherSalary query);

    /**
     * 单条 upsert（按 researcherId+salaryMonth 查重）。monthlySalary 必须 &gt; 0。
     */
    RdResearcherSalary saveSalary(RdResearcherSalary salary, String operName);

    /**
     * 导出工资（复用列表同通道）。
     */
    List<RdResearcherSalary> exportSalary(RdResearcherSalary query);

    /**
     * 导入工资（ExcelUtil 行级校验，row 级错误汇总报错）。
     */
    String importSalary(List<RdResearcherSalary> rows, String operName);
}