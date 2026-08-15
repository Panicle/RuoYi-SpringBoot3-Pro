package com.ruoyi.biz.service.impl;

import com.ruoyi.biz.domain.Approval;
import com.ruoyi.biz.domain.ApprovalHistory;
import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.domain.ProjectDocument;
import com.ruoyi.biz.mapper.ApprovalHistoryMapper;
import com.ruoyi.biz.mapper.ApprovalMapper;
import com.ruoyi.biz.mapper.ProjectDocumentMapper;
import com.ruoyi.biz.service.IProjectDocumentService;
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
 * 课题资料 Service 实现（资料 CRUD + submit/resubmit 审批状态机，任务卡 §三.1 + §四.1/§四.3）
 *
 * <p>数据权限照 ProjectServiceImpl/ContractServiceImpl 双通道：列表 researcher 走"本人相关"专用 SQL，
 * 详情与全部写操作过 scoped {@code projectService.selectProjectById} 闸门（researcher 亦须本人相关）。
 * 审批状态唯一事实来源是 approval.status（决策 D1）；每次状态流转同事务双写 approval + history（决策 D3）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Service
@RequiredArgsConstructor
public class ProjectDocumentServiceImpl implements IProjectDocumentService {

    /** 课题状态（与字典 project_status 一致） */
    private static final String STATUS_ARCHIVED = "ARCHIVED";

    /** 审批状态（与字典 approval_status 一致） */
    private static final String STATUS_PENDING  = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";

    /** 审批历史动作（与 approval_history.action 一致） */
    private static final String ACTION_SUBMIT   = "SUBMIT";
    private static final String ACTION_RESUBMIT = "RESUBMIT";

    private final ProjectDocumentMapper projectDocumentMapper;
    private final ApprovalMapper approvalMapper;
    private final ApprovalHistoryMapper approvalHistoryMapper;
    private final IProjectService projectService;

    // ========================================================
    //  列表 / 详情（数据范围双通道）
    // ========================================================

    @Override
    public List<ProjectDocument> selectDocumentList(ProjectDocument query) {
        if (query == null) {
            query = new ProjectDocument();
        }
        // researcher (data_scope=5) 不走 @DataScope，Service 内按角色硬分支：走"本人相关"专用 SQL
        if (isResearcher()) {
            Long me = SecurityUtils.getUserId();
            query.getParams().put("selfUserId", me);
            return fillDocumentLabels(projectDocumentMapper.selectDocumentListForResearcher(query));
        }
        return fillDocumentLabels(projectDocumentMapper.selectDocumentList(query));
    }

    @Override
    public ProjectDocument selectDocumentById(Long docId) {
        if (docId == null) {
            throw new ServiceException("docId 不能为空");
        }
        ProjectDocument doc = projectDocumentMapper.selectDocumentById(docId);
        if (doc == null) {
            // 不暴露是否存在信息
            throw new ServiceException("无权访问");
        }
        // scoped 闸门：所属课题不在数据范围内抛"无权访问"（researcher 亦须本人相关）
        projectService.selectProjectById(doc.getProjectId());
        // 携带当前审批 + 完整历史（D1/D3）
        Approval approval = approvalMapper.selectByDocId(docId);
        if (approval != null) {
            doc.setApproval(approval);
            doc.setApprovalStatus(approval.getStatus());
            doc.setApprovalRound(approval.getRound());
            doc.setRejectReason(approval.getRejectReason());
            doc.setHistoryList(approvalHistoryMapper.selectByApprovalId(approval.getApprovalId()));
        }
        doc.setStageLabel(DictUtils.getDictLabel("project_stage", doc.getStage()));
        return doc;
    }

    // ========================================================
    //  新增（scoped 闸门 + ARCHIVED 拒传，决策 D8）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectDocument insertDocument(ProjectDocument doc, String operName) {
        if (doc == null) {
            throw new ServiceException("参数为空");
        }
        if (doc.getProjectId() == null) {
            throw new ServiceException("课题不能为空");
        }
        if (StringUtils.isEmpty(doc.getFileName())) {
            throw new ServiceException("文件名称不能为空");
        }
        if (StringUtils.isEmpty(doc.getFileUrl())) {
            throw new ServiceException("文件路径不能为空");
        }
        // scoped 闸门（researcher 走本人相关分支）
        Project project = projectService.selectProjectById(doc.getProjectId());
        // 决策 D8：归档课题不可上传资料
        if (STATUS_ARCHIVED.equals(project.getStatus())) {
            throw new ServiceException("已归档课题不可上传资料");
        }
        doc.setUploadBy(operName);
        doc.setUploadTime(new Date());
        doc.setDelFlag("0");  // 三层保险之一：Service 显式置
        doc.setCreateBy(operName);
        projectDocumentMapper.insert(doc);
        return doc;
    }

    // ========================================================
    //  删除（D7 规则：PENDING/APPROVED 审批拒删；REJECTED/无审批可删，级联逻辑删 approval + history）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteDocumentByIds(Long[] docIds, String operName) {
        if (docIds == null || docIds.length == 0) {
            return 0;
        }
        // 先全部校验：存在且通过数据权限 + D7 规则
        for (Long docId : docIds) {
            ProjectDocument doc = projectDocumentMapper.selectDocumentById(docId);
            if (doc == null) {
                throw new ServiceException("资料[" + docId + "]不存在或已删除");
            }
            projectService.selectProjectById(doc.getProjectId());
            Approval approval = approvalMapper.selectByDocId(docId);
            if (approval != null
                    && (STATUS_PENDING.equals(approval.getStatus()) || STATUS_APPROVED.equals(approval.getStatus()))) {
                throw new ServiceException("资料[" + docId + "]存在审批中或已通过的审批，不可删除");
            }
        }
        // 级联逻辑删：资料 + 审批 + 历史（同事务；审批 history 按 approval_id 删）
        for (Long docId : docIds) {
            Approval approval = approvalMapper.selectByDocId(docId);
            Long approvalId = approval == null ? null : approval.getApprovalId();
            projectDocumentMapper.softDeleteByDocId(docId, operName);
            approvalMapper.softDeleteByDocId(docId, operName);
            if (approvalId != null) {
                approvalHistoryMapper.softDeleteByApprovalId(approvalId, operName);
            }
        }
        return docIds.length;
    }

    // ========================================================
    //  发起审批（§四.1 完整事务）/ 驳回重报（§四.3）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitDocument(Long docId, String operName) {
        if (docId == null) {
            throw new ServiceException("docId 不能为空");
        }
        ProjectDocument doc = projectDocumentMapper.selectDocumentById(docId);
        if (doc == null) {
            throw new ServiceException("资料不存在");
        }
        // 1. scoped 闸门（researcher 须本人相关）
        Project project = projectService.selectProjectById(doc.getProjectId());
        // 2. ARCHIVED 拒
        if (STATUS_ARCHIVED.equals(project.getStatus())) {
            throw new ServiceException("已归档课题不可发起审批");
        }
        Long me = SecurityUtils.getUserId();
        Approval existing = approvalMapper.selectByDocId(docId);
        if (existing == null) {
            // 3a. 无 approval 行 → 新建 round=1 PENDING，applicantId=当前用户
            Approval approval = new Approval();
            approval.setDocId(docId);
            approval.setApplicantId(me);
            approval.setStatus(STATUS_PENDING);
            approval.setRound(1);
            approval.setDelFlag("0");
            approval.setCreateBy(operName);
            approvalMapper.insert(approval);
            // 4. 回填 project_document.submitter_id
            projectDocumentMapper.updateSubmitter(docId, me, operName);
            // 5. history SUBMIT（round=1）
            insertHistory(approval.getApprovalId(), ACTION_SUBMIT, me, null, 1, operName);
            return;
        }
        // 3b. 已有 REJECTED → 走 resubmit
        if (STATUS_REJECTED.equals(existing.getStatus())) {
            doResubmit(existing, me, operName);
            return;
        }
        // 3c. 已有 PENDING/APPROVED → 拒
        throw new ServiceException("已发起审批");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resubmitDocument(Long docId, String operName) {
        if (docId == null) {
            throw new ServiceException("docId 不能为空");
        }
        ProjectDocument doc = projectDocumentMapper.selectDocumentById(docId);
        if (doc == null) {
            throw new ServiceException("资料不存在");
        }
        // scoped 闸门 + ARCHIVED 拒（决策 D8 含重报）
        Project project = projectService.selectProjectById(doc.getProjectId());
        if (STATUS_ARCHIVED.equals(project.getStatus())) {
            throw new ServiceException("已归档课题不可重报");
        }
        Approval existing = approvalMapper.selectByDocId(docId);
        if (existing == null) {
            throw new ServiceException("该资料尚未发起审批");
        }
        // §四.3：仅 REJECTED 可重报
        if (!STATUS_REJECTED.equals(existing.getStatus())) {
            throw new ServiceException("仅驳回的资料可重报");
        }
        Long me = SecurityUtils.getUserId();
        doResubmit(existing, me, operName);
    }

    /**
     * 驳回重报核心（§四.3）：round+1、status=PENDING、reject_reason 清空、applicant_id=当前用户 + history RESUBMIT
     */
    private void doResubmit(Approval existing, Long me, String operName) {
        int newRound = (existing.getRound() == null ? 1 : existing.getRound()) + 1;
        Approval upd = new Approval();
        upd.setApprovalId(existing.getApprovalId());
        upd.setStatus(STATUS_PENDING);
        upd.setRound(newRound);
        upd.setRejectReason(null);   // 清空最近驳回原因
        upd.setApplicantId(me);
        upd.setUpdateBy(operName);
        approvalMapper.updateStatus(upd);
        // history RESUBMIT（round=新轮次）
        insertHistory(existing.getApprovalId(), ACTION_RESUBMIT, me, null, newRound, operName);
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /** 同事务写一条审批历史（决策 D3：每次状态流转一条历史） */
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

    /** 资料列表补字典翻译：仅翻译 stage（approvalStatus 前端 dict-tag 兜底，无需冗余翻译） */
    private List<ProjectDocument> fillDocumentLabels(List<ProjectDocument> list) {
        if (list == null || list.isEmpty()) {
            return list;
        }
        for (ProjectDocument d : list) {
            if (d == null) {
                continue;
            }
            d.setStageLabel(DictUtils.getDictLabel("project_stage", d.getStage()));
        }
        return list;
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
