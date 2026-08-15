package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.RdResearcherSalary;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 研发人员工资标准 Mapper 接口
 *
 * <p>列表走 @DataScope(deptAlias="d", userAlias="u") 通道（researcher 不调用此方法），
 * SELECT vo 必须 LEFT JOIN sys_user u2 带 researcherName + LEFT JOIN sys_dept d + LEFT JOIN sys_user u
 * （u 取 sys_user.dept_id 链路）。
 * researcher(data_scope=5) 走"本人相关"专用分支 selectRdResearcherSalaryListForResearcher（仅 researcherId=我）。</p>
 *
 * <p>salaryMonth 格式 'YYYY-MM'，save 端点应用层查重（task brief D9）。
 * researcher 角色直接拒 /salary/list（任务卡 D11），Service 兜底。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
public interface RdResearcherSalaryMapper extends BaseMapper<RdResearcherSalary> {

    /**
     * 工资列表（@DataScope 通道：salaryMonth/researcherId 过滤；researcher 不调用此方法）
     *
     * @param query 查询条件（salaryMonth/researcherId；@DataScope 注入 params.dataScope）
     * @return 工资集合（含 researcherName）
     */
    @com.ruoyi.common.annotation.DataScope(deptAlias = "d", userAlias = "u")
    List<RdResearcherSalary> selectRdResearcherSalaryList(RdResearcherSalary query);

    /**
     * 工资列表（researcher 专用）：仅返回本人行（WHERE researcher_id = selfUserId）。
     * 任务卡 D11：researcher 角色直接拒，本 SQL 仅作工程兜底，Service 仍按 isResearcher() 判拒。
     */
    List<RdResearcherSalary> selectRdResearcherSalaryListForResearcher(RdResearcherSalary query);

    /**
     * 按 (researcherId, salaryMonth) 查有效单行
     * （DB 唯一索引 idx_rd_researcher_salary_uk 兜底，应用层查重）。
     */
    RdResearcherSalary selectByResearcherAndMonth(@Param("researcherId") Long researcherId,
                                                 @Param("salaryMonth") String salaryMonth);
}