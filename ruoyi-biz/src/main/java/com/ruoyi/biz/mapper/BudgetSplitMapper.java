package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.BudgetSplit;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 课题预算细分 Mapper 接口
 *
 * <p>V1.0.11 起预算行按 (project_id, category) 增量更新（决策 D1，唯一索引 idx_budget_split_pc_uk 兜底），
 * 行金额更新一律走 {@code updateById(entity)} 的 @Version 乐观锁通道，不再提供全量替换用的批量插入。</p>
 *
 * @author kys
 * @date 2026-08-13
 */
public interface BudgetSplitMapper extends BaseMapper<BudgetSplit> {

    /**
     * 查询课题预算细分列表（按 projectId，del_flag='0'）
     *
     * @param projectId 课题ID
     * @return 预算细分集合（按科目排序）
     */
    List<BudgetSplit> selectByProjectId(@Param("projectId") Long projectId);

    /**
     * 按 (课题, 科目) 查有效预算行（唯一索引 idx_budget_split_pc_uk 保证至多 1 行）。
     * D1 增量更新与记账定位 split 共用。
     *
     * @param projectId 课题ID
     * @param category  预算科目（字典 budget_category）
     * @return 预算行；不存在返回 null
     */
    BudgetSplit selectByProjectAndCategory(@Param("projectId") Long projectId,
                                           @Param("category") String category);

    /**
     * 按课题逻辑删除全部有效预算行（删课题时级联调用，任务卡 §九）
     *
     * @param projectId 课题ID
     * @param updateBy  操作人
     * @return 影响行数
     */
    int softDeleteByProjectId(@Param("projectId") Long projectId,
                              @Param("updateBy") String updateBy);
}
