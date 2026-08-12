package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.Project;
import com.ruoyi.common.annotation.DataScope;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * 课题 Mapper 接口
 *
 * @author kys
 * @date 2026-08-12
 */
public interface ProjectMapper extends BaseMapper<Project> {

    /**
     * 查询课题列表（自行 JOIN sys_user / sys_dept，避免视图多角色重复行；
     * researcher(data_scope=5) 不走此方法，Service 走"本人相关"专用分支）
     *
     * @param query 查询条件
     * @return 课题集合（含 leaderName/deptName）
     */
    @DataScope(deptAlias = "d", userAlias = "u")
    List<Project> selectProjectList(Project query);

    /**
     * 列表查询（researcher 专用）：仅返回 leader_id = 当前用户 OR 在成员表中含当前用户 的课题
     */
    List<Project> selectProjectListForResearcher(Project query);

    /**
     * 通过课题ID查询单条（不带数据范围，管理员/科管内部使用）
     *
     * @param projectId 课题ID
     * @return 课题主表数据
     */
    Project selectProjectById(@Param("projectId") Long projectId);

    /**
     * 详情查询（携带数据范围校验）。
     * researcher(data_scope=5) 时，EXISTS 子查询要求当前用户为主持人或成员。
     *
     * @param projectId     课题ID
     * @param currentUserId 当前登录用户ID
     * @return 课题（含 leaderName/deptName），不在数据范围内返回 null
     */
    @DataScope(deptAlias = "d", userAlias = "u")
    Project selectProjectScopedById(@Param("projectId") Long projectId,
                                    @Param("currentUserId") Long currentUserId);

    /**
     * 详情查询（researcher 专用）：仅当 leader_id=我 OR 成员表含我 时返回
     */
    Project selectProjectScopedByIdForResearcher(@Param("projectId") Long projectId,
                                                 @Param("currentUserId") Long currentUserId);

    /**
     * 更新状态（状态机迁移 / 归档复用）
     */
    int updateStatus(@Param("projectId") Long projectId,
                     @Param("status") String status,
                     @Param("updateBy") String updateBy);

    /**
     * 更新主持人（换主持人事务调用）
     */
    int updateLeader(@Param("projectId") Long projectId,
                     @Param("newLeaderId") Long newLeaderId,
                     @Param("updateBy") String updateBy);

    /**
     * 更新预算总额（修改接口使用）
     */
    int updateBudget(@Param("projectId") Long projectId,
                     @Param("budgetTotal") BigDecimal budgetTotal);

    /**
     * 查询指定年份的课题编号最大流水号（用于生成 KY-{yyyy}-{3位流水}）。
     * 返回 3 位数字的最大值；若无返回 null。
     */
    Long selectMaxSeqByYear(@Param("year") String year);
}