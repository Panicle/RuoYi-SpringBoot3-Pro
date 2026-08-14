package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.Expense;
import com.ruoyi.common.annotation.DataScope;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 经费流水 Mapper 接口
 *
 * <p>数据权限照课题/合同模式：列表走 @DataScope(deptAlias="d", userAlias="u") 注解，
 * SQL JOIN project p LEFT JOIN sys_dept d ON p.dept_id=d.dept_id LEFT JOIN sys_user u ON p.leader_id=u.user_id；
 * researcher(data_scope=5) 由 Service 切换到 selectExpenseListForResearcher 专用分支（决策 D7：
 * researcher 对本人相关课题有记账写权限）。</p>
 *
 * @author kys
 * @date 2026-08-14
 */
public interface ExpenseMapper extends BaseMapper<Expense> {

    /**
     * 查询经费流水列表（自行 JOIN project / sys_dept / sys_user 视图，
     * researcher(data_scope=5) 不走此方法，Service 走"本人相关"专用分支）
     *
     * @param query 查询条件（projectId/category/status/日期区间）
     * @return 流水集合（含 projectNo/projectName）
     */
    @DataScope(deptAlias = "d", userAlias = "u")
    List<Expense> selectExpenseList(Expense query);

    /**
     * 列表查询（researcher 专用）：仅返回所属课题的 leader_id = 当前用户 OR 课题成员表中含当前用户的流水
     */
    List<Expense> selectExpenseListForResearcher(Expense query);

    /**
     * 查询流水详情（JOIN project 带 projectNo/projectName；不带数据范围，Service 走闸门）
     */
    Expense selectExpenseById(@Param("expenseId") Long expenseId);

    /**
     * 按课题逻辑删除全部有效流水（删课题时级联调用，任务卡 §九）
     *
     * @param projectId 课题ID
     * @param updateBy  操作人
     * @return 影响行数
     */
    int softDeleteByProjectId(@Param("projectId") Long projectId,
                              @Param("updateBy") String updateBy);
}
