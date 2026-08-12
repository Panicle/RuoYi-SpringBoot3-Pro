package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.domain.ProjectMember;

import java.util.List;

/**
 * 课题 Service 接口
 *
 * @author kys
 * @date 2026-08-12
 */
public interface IProjectService {

    /**
     * 查询课题列表（已注入数据范围；researcher 走"本人相关"专用分支）
     */
    List<Project> selectProjectList(Project query);

    /**
     * 详情查询（强制数据范围校验；不在范围内抛 ServiceException）
     */
    Project selectProjectById(Long projectId);

    /**
     * 新增课题（含生成 project_no + 写入 HOST 成员行；唯一索引冲突重试 1 次）
     */
    Project insertProject(Project project, String operName);

    /**
     * 修改课题（不允许修改 projectNo/leaderId/status；ARCHIVED 状态拒）
     */
    int updateProject(Project project, String operName);

    /**
     * 逻辑删除（有有效成员时拒绝）
     */
    int deleteProjectByIds(Long[] projectIds, String operName);

    /**
     * 状态机迁移（按 §3.5 相邻单向规则校验；非法抛业务错误）
     */
    int changeStatus(Long projectId, String targetStatus, String operName);

    /**
     * 归档（仅 COMPLETED/ACCEPTED → ARCHIVED）
     */
    int archive(Long projectId, String operName);

    /**
     * 成员列表（按 projectId 过滤；走数据范围校验）
     */
    List<ProjectMember> selectMemberList(Long projectId);

    /**
     * 新增成员（拒绝 HOST；批量有序；任一失败整体回滚）
     */
    int addMembers(Long projectId, List<ProjectMember> members, String operName);

    /**
     * 批量删除成员（不能删唯一 HOST；每个成员所属课题需通过数据范围）
     */
    int removeMembers(Long[] memberIds, String operName);

    /**
     * 换主持人（事务内完成：HOST→PARTICIPANT、新成员→HOST、同步 leader_id）
     */
    int changeHost(Long projectId, Long newLeaderUserId, String operName);

    /**
     * Excel 导出（复用列表查询条件 + 数据范围）
     */
    List<Project> exportProject(Project query);
}