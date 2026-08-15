package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.ProjectDocument;
import com.ruoyi.common.annotation.DataScope;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 课题资料 Mapper 接口
 *
 * <p>数据权限照课题/合同模式（ProjectMapper/ContractMapper）：列表走 @DataScope(deptAlias="d", userAlias="u") 注解，
 * selectDocumentVo 必须 JOIN project p LEFT JOIN sys_dept d ON p.dept_id=d.dept_id + LEFT JOIN sys_user u ON p.leader_id=u.user_id，
 * 别名 d/u 专供数据权限使用，不得被业务表占用（阶段3 终审教训）；
 * researcher(data_scope=5) 由 Service 切换到 selectDocumentListForResearcher 专用分支（EXISTS 本人相关课题）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
public interface ProjectDocumentMapper extends BaseMapper<ProjectDocument> {

    /**
     * 查询资料列表（JOIN project / approval / sys_dept / sys_user 视图，
     * researcher(data_scope=5) 不走此方法，Service 走"本人相关"专用分支）
     *
     * @param query 查询条件（projectId/stage/approvalStatus 筛选；@DataScope 注入 params.dataScope）
     * @return 资料集合（含 projectNo/projectName/approvalStatus/approvalRound/rejectReason）
     */
    @DataScope(deptAlias = "d", userAlias = "u")
    List<ProjectDocument> selectDocumentList(ProjectDocument query);

    /**
     * 列表查询（researcher 专用）：仅返回所属课题的 leader_id = 当前用户 OR 课题成员表中含当前用户的资料
     */
    List<ProjectDocument> selectDocumentListForResearcher(ProjectDocument query);

    /**
     * 查询资料详情（JOIN project + approval 视图；不带数据范围，Service 走 scoped 闸门）
     *
     * @param docId 资料ID
     * @return 资料（含 projectNo/projectName/approvalStatus/approvalRound/rejectReason），不存在返回 null
     */
    ProjectDocument selectDocumentById(@Param("docId") Long docId);

    /**
     * 逻辑删除单条资料（del_flag='2'；审批/历史级联由 Service 同事务调用各自 Mapper）
     *
     * @param docId    资料ID
     * @param updateBy 操作人
     * @return 影响行数
     */
    int softDeleteByDocId(@Param("docId") Long docId, @Param("updateBy") String updateBy);

    /**
     * 回填提交人 submitter_id（发起审批时；update_time=sysdate）
     *
     * @param docId       资料ID
     * @param submitterId 提交人 user_id
     * @param updateBy    操作人
     * @return 影响行数
     */
    int updateSubmitter(@Param("docId") Long docId, @Param("submitterId") Long submitterId,
                        @Param("updateBy") String updateBy);
}
