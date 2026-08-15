package com.ruoyi.biz.service.impl;

import com.ruoyi.biz.domain.Approval;
import com.ruoyi.biz.domain.ApprovalHistory;
import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.domain.ProjectDocument;
import com.ruoyi.biz.mapper.ApprovalHistoryMapper;
import com.ruoyi.biz.mapper.ApprovalMapper;
import com.ruoyi.biz.mapper.ProjectDocumentMapper;
import com.ruoyi.biz.service.IApprovalService;
import com.ruoyi.biz.service.IProjectService;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DictUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 审批 Service 实现（列表 / 历史 / 审批，任务卡 §三.2 + §四.2）
 *
 * <p>数据权限照课题/合同模式双通道：列表 researcher 走"仅本人申请"专用 SQL，
 * 历史与 audit 过 scoped {@code projectService.selectProjectById} 闸门——
 * 该闸门天然判定"本室 dept_leader"（data_scope=3 @DataScope 限定本人部门课题），
 * science_admin/admin（data_scope=1）全量通过，approver_id 回填当前用户。</p>
 *
 * <p>每次状态流转同事务双写 approval + history（决策 D3）；approval.comment_text 复用为审批意见（决策 D5）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Service
@RequiredArgsConstructor
public class ApprovalServiceImpl implements IApprovalService {

    /** 审批状态（与字典 approval_status 一致） */
    private static final String STATUS_PENDING  = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";

    /** 课题状态（与字典 project_status 一致；决策 D8：归档课题不可审批） */
    private static final String STATUS_ARCHIVED = "ARCHIVED";

    /** 审批动作（与 approval_history.action 一致） */
    private static final String ACTION_APPROVE = "APPROVE";
    private static final String ACTION_REJECT  = "REJECT";

    private final ApprovalMapper approvalMapper;
    private final ApprovalHistoryMapper approvalHistoryMapper;
    private final ProjectDocumentMapper projectDocumentMapper;
    private final IProjectService projectService;

    // ========================================================
    //  列表（数据范围双通道）
    // ========================================================

    @Override
    public List<Approval> selectApprovalList(Approval query) {
        if (query == null) {
            query = new Approval();
        }
        // researcher (data_scope=5) 不走 @DataScope，Service 内按角色硬分支：走"仅本人申请"专用 SQL
        if (isResearcher()) {
            Long me = SecurityUtils.getUserId();
            query.getParams().put("selfUserId", me);
            return fillApprovalLabels(approvalMapper.selectApprovalListForResearcher(query));
        }
        return fillApprovalLabels(approvalMapper.selectApprovalList(query));
    }

    // ========================================================
    //  审批历史（scoped 闸门）
    // ========================================================

    @Override
    public List<ApprovalHistory> selectApprovalHistory(Long approvalId) {
        if (approvalId == null) {
            throw new ServiceException("approvalId 不能为空");
        }
        Approval approval = approvalMapper.selectById(approvalId);
        if (approval == null) {
            // 不暴露是否存在信息
            throw new ServiceException("无权访问");
        }
        ProjectDocument doc = projectDocumentMapper.selectDocumentById(approval.getDocId());
        if (doc == null) {
            throw new ServiceException("无权访问");
        }
        // scoped 闸门：所属课题不在数据范围内抛"无权访问"（researcher 亦须本人相关）
        projectService.selectProjectById(doc.getProjectId());
        return fillHistoryLabels(approvalHistoryMapper.selectByApprovalId(approvalId));
    }

    // ========================================================
    //  审批（§四.2 完整事务）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void auditApproval(Long approvalId, String action, String comment, String rejectReason, String operName) {
        if (approvalId == null) {
            throw new ServiceException("approvalId 不能为空");
        }
        if (!ACTION_APPROVE.equals(action) && !ACTION_REJECT.equals(action)) {
            throw new ServiceException("action 仅支持 APPROVE/REJECT");
        }
        Approval approval = approvalMapper.selectById(approvalId);
        if (approval == null) {
            throw new ServiceException("审批不存在");
        }
        // 1. 仅 PENDING 可审
        if (!STATUS_PENDING.equals(approval.getStatus())) {
            throw new ServiceException("当前状态不可审批");
        }
        // 2. 审批权限：scoped 闸门判定"本室 dept_leader"（或 science_admin/admin）——不在数据范围内抛"无权访问"
        ProjectDocument doc = projectDocumentMapper.selectDocumentById(approval.getDocId());
        if (doc == null) {
            throw new ServiceException("资料不存在");
        }
        Project project = projectService.selectProjectById(doc.getProjectId());
        // 2'. 决策 D8：归档课题不可审批（PENDING 期间被归档后，dept_leader 仍不得 APPROVE/REJECT；
        //     对齐 ExpenseServiceImpl 里 ARCHIVED 检查的写法与常量）
        if (STATUS_ARCHIVED.equals(project.getStatus())) {
            throw new ServiceException("已归档课题不可审批");
        }
        // 3. REJECT 必填驳回原因
        if (ACTION_REJECT.equals(action) && StringUtils.isEmpty(rejectReason)) {
            throw new ServiceException("驳回原因不能为空");
        }
        Long me = SecurityUtils.getUserId();
        Date now = new Date();
        if (ACTION_APPROVE.equals(action)) {
            // 4. APPROVE → status=APPROVED、finish_time=now、approver_id=当前用户、comment_text=意见
            Approval upd = new Approval();
            upd.setApprovalId(approvalId);
            upd.setStatus(STATUS_APPROVED);
            upd.setRound(approval.getRound());
            upd.setRejectReason(null);
            upd.setCommentText(comment);
            upd.setApproverId(me);
            upd.setFinishTime(now);
            upd.setUpdateBy(operName);
            approvalMapper.updateStatus(upd);
            // 5. history APPROVE（round=本次轮次）
            insertHistory(approvalId, ACTION_APPROVE, me, comment, approval.getRound(), operName);
        } else {
            // 4'. REJECT → status=REJECTED、reject_reason、finish_time=now（复用）、approver_id=当前用户
            Approval upd = new Approval();
            upd.setApprovalId(approvalId);
            upd.setStatus(STATUS_REJECTED);
            upd.setRound(approval.getRound());
            upd.setRejectReason(rejectReason);
            upd.setCommentText(comment);
            upd.setApproverId(me);
            upd.setFinishTime(now);
            upd.setUpdateBy(operName);
            approvalMapper.updateStatus(upd);
            // 5'. history REJECT（round=本次轮次）
            insertHistory(approvalId, ACTION_REJECT, me, comment, approval.getRound(), operName);
        }
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /** 同事务写一条审批历史（决策 D3） */
    private void insertHistory(Long approvalId, String action, Long operatorId,
                               String commentText, Integer round, String operName) {
        ApprovalHistory h = new ApprovalHistory();
        h.setApprovalId(approvalId);
        h.setAction(action);
        h.setOperatorId(operatorId);
        h.setCommentText(commentText);
        h.setRound(round);
        h.setOperateTime(new Date());
        h.setDelFlag("0");
        h.setCreateBy(operName);
        approvalHistoryMapper.insert(h);
    }

    /** 审批列表补字典翻译：statusLabel（approval_status 字典） */
    private List<Approval> fillApprovalLabels(List<Approval> list) {
        if (list == null || list.isEmpty()) {
            return list;
        }
        for (Approval a : list) {
            if (a == null) {
                continue;
            }
            a.setStatusLabel(DictUtils.getDictLabel("approval_status", a.getStatus()));
        }
        return list;
    }

    /** 历史补 actionLabel（无 approval_action 字典，手工映射 SUBMIT/APPROVE/REJECT/RESUBMIT） */
    private List<ApprovalHistory> fillHistoryLabels(List<ApprovalHistory> list) {
        if (list == null || list.isEmpty()) {
            return list;
        }
        for (ApprovalHistory h : list) {
            if (h == null) {
                continue;
            }
            h.setActionLabel(actionLabel(h.getAction()));
        }
        return list;
    }

    private String actionLabel(String action) {
        if (StringUtils.isEmpty(action)) {
            return "";
        }
        switch (action) {
            case "SUBMIT":
                return "提交";
            case "APPROVE":
                return "通过";
            case "REJECT":
                return "驳回";
            case "RESUBMIT":
                return "重报";
            case "TRANSFER":
                return "转交";
            default:
                return action;
        }
    }

    /**
     * 当前登录用户是否「精确」为 researcher（不含 admin）。
     * 照抄 ProjectServiceImpl.java:665-689 现有实现风格，单次遍历 + hasAdmin/hasResearcher 标志，
     * 不要重新设计（RuoYi 的 SUPER_ADMIN 捷径会让含 admin 角色恒 true）。
     */
    private boolean isResearcher() {
        try {
            List<SysRole> roles = SecurityUtils.getLoginUser().getUser().getRoles();
            if (roles == null || roles.isEmpty()) {
                return false;
            }
            boolean hasAdmin = false;
            boolean hasResearcher = false;
            for (SysRole r : roles) {
                if (r == null || StringUtils.isEmpty(r.getRoleKey())) {
                    continue;
                }
                if ("admin".equals(r.getRoleKey())) {
                    hasAdmin = true;
                }
                if ("researcher".equals(r.getRoleKey())) {
                    hasResearcher = true;
                }
            }
            return hasResearcher && !hasAdmin;
        } catch (Exception e) {
            return false;
        }
    }
}
