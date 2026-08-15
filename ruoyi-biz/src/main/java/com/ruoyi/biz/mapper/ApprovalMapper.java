package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.Approval;
import com.ruoyi.common.annotation.DataScope;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 审批 Mapper 接口
 *
 * <p>数据权限照课题模式：selectApprovalList 走 @DataScope(deptAlias="d", userAlias="u") 注解，
 * selectApprovalVo 经 approval → project_document → project → sys_dept/sys_user 联接，
 * 别名 d/u 专供数据权限使用，不得被业务表占用；
 * researcher(data_scope=5) 由 Service 切换到 selectApprovalListForResearcher 专用分支（仅本人申请的）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
public interface ApprovalMapper extends BaseMapper<Approval> {

    /**
     * 查询审批列表（JOIN project_document / project / sys_dept / sys_user 视图；
     * dept_leader 本室（data_scope=3 @DataScope d.dept_id），science_admin/admin 全量；
     * researcher(data_scope=5) 不走此方法，Service 走"仅本人申请"专用分支）
     *
     * @param query 查询条件（status/docId 筛选；@DataScope 注入 params.dataScope）
     * @return 审批集合（含 docFileName/projectName）
     */
    @DataScope(deptAlias = "d", userAlias = "u")
    List<Approval> selectApprovalList(Approval query);

    /**
     * 列表查询（researcher 专用）：仅返回 applicant_id = 当前用户 的审批（本人申请的）
     */
    List<Approval> selectApprovalListForResearcher(Approval query);

    /**
     * 按资料ID查询当前审批（一份资料至多一条有效审批，唯一索引 idx_approval_doc_id_uk 兜底）
     *
     * @param docId 资料ID
     * @return 审批（含 docFileName/projectName），无审批返回 null
     */
    Approval selectByDocId(@Param("docId") Long docId);

    /**
     * 状态机流转 UPDATE（submit/resubmit/audit 共用）：status/round/reject_reason 必传，
     * reject_reason 传 null 表示清空（REJECT 回填 / APPROVE、RESUBMIT 清空）；
     * commentText/applicantId/approverId/finishTime 按需传（null 不更新）。
     *
     * @param approval 承载要更新的字段（approvalId 必填；updateBy 必填）
     * @return 影响行数
     */
    int updateStatus(Approval approval);

    /**
     * 按资料逻辑删除全部有效审批（删资料级联调用；del_flag='2'）
     *
     * @param docId    资料ID
     * @param updateBy 操作人
     * @return 影响行数
     */
    int softDeleteByDocId(@Param("docId") Long docId, @Param("updateBy") String updateBy);
}
