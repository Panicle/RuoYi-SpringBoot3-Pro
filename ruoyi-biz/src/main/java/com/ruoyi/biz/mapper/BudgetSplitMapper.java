package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.BudgetSplit;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 课题预算细分 Mapper 接口
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
     * 批量插入预算细分（新增/编辑全量替换时调用）
     *
     * @param list 预算细分集合
     * @return 影响行数
     */
    int batchInsert(@Param("list") List<BudgetSplit> list);

    /**
     * 按课题逻辑删除预算细分（del_flag='2'；编辑全量替换前调用）
     *
     * @param projectId 课题ID
     * @return 影响行数
     */
    int deleteByProjectId(@Param("projectId") Long projectId);
}
