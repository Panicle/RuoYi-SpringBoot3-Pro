package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.ProjectUnitBudget;

import java.util.List;

/**
 * 课题按单位经费支出预算 Mapper（project_unit_budget，V1.0.23）
 *
 * <p>沿用 budget_source 的无 XML 风格：LambdaQueryWrapper 默认方法实现（del_flag 非 @TableLogic，
 * 删除走物理 DELETE、查询显式过滤 del_flag='0'）。</p>
 *
 * @author kys
 * @date 2026-08-18
 */
public interface ProjectUnitBudgetMapper extends BaseMapper<ProjectUnitBudget> {

    /**
     * 查课题按单位预算列表（按 projectId，del_flag='0'）
     */
    default List<ProjectUnitBudget> selectByProjectId(Long projectId) {
        return selectList(new LambdaQueryWrapper<ProjectUnitBudget>()
                .eq(ProjectUnitBudget::getProjectId, projectId)
                .eq(ProjectUnitBudget::getDelFlag, "0")
                .orderByAsc(ProjectUnitBudget::getDeptId, ProjectUnitBudget::getCategory));
    }

    /**
     * 物理删除课题全部按单位预算行（删旧写新第一步）
     */
    default int deleteByProjectId(Long projectId) {
        return delete(new LambdaQueryWrapper<ProjectUnitBudget>()
                .eq(ProjectUnitBudget::getProjectId, projectId));
    }
}
