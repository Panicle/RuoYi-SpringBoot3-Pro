package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.ProjectMember;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 课题成员 Mapper 接口
 *
 * @author kys
 * @date 2026-08-12
 */
public interface ProjectMemberMapper extends BaseMapper<ProjectMember> {

    /**
     * 查询课题成员列表（自行 JOIN sys_user / sys_dept，含 userName/nickName/deptName）。
     * 不加 @DataScope：项目级数据范围已由 Service 层 scoped selectProjectById 校验，
     * 成员行不应再按成员所属部门二次过滤（避免 data_scope=3 时跨部门成员被隐藏）。
     */
    List<ProjectMember> selectMemberList(ProjectMember query);

    /**
     * 查询课题当前有效 HOST（del_flag='0'）。无 HOST 返回 null。
     */
    ProjectMember selectHostMember(@Param("projectId") Long projectId);

    /**
     * 查询课题中某个用户当前有效成员行（不限 role）。
     */
    ProjectMember selectMemberByUser(@Param("projectId") Long projectId,
                                     @Param("userId") Long userId);

    /**
     * 修改成员角色（换主持人、HOST 转 PARTICIPANT 等）
     */
    int updateRole(@Param("memberId") Long memberId,
                   @Param("role") String role,
                   @Param("updateBy") String updateBy);

    /**
     * 批量逻辑删除（updateBy / del_flag='2'）
     */
    int softDeleteByIds(@Param("memberIds") Long[] memberIds,
                        @Param("updateBy") String updateBy);

    /**
     * 查询某 member_id 对应的课题ID（删除前校验数据权限）
     */
    Long selectProjectIdByMemberId(@Param("memberId") Long memberId);
}