package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.Approval;
import com.ruoyi.biz.domain.ApprovalHistory;

import java.util.List;

/**
 * 审批 Service 接口（列表 / 历史 / 审批，任务卡 §三.2 + §四.2）
 *
 * @author kys
 * @date 2026-08-15
 */
public interface IApprovalService {

    /**
     * 查询审批列表（已注入数据范围；dept_leader 本室（data_scope=3 @DataScope），
     * science_admin/admin 全量；researcher 仅本人申请的）
     */
    List<Approval> selectApprovalList(Approval query);

    /**
     * 审批历史（scoped 闸门校验；按 round + operate_time 有序）
     */
    List<ApprovalHistory> selectApprovalHistory(Long approvalId);

    /**
     * 审批（§四.2 完整事务：仅 PENDING 可审；审批人=本室 dept_leader 或 science_admin/admin
     * 通过 scoped 闸门判定，approver_id 回填当前用户；APPROVE/REJECT 双写 approval + history）
     *
     * @param action       APPROVE / REJECT
     * @param comment      审批意见（comment_text）
     * @param rejectReason 驳回原因（仅 REJECT 必填，回填 reject_reason）
     */
    void auditApproval(Long approvalId, String action, String comment, String rejectReason, String operName);
}
