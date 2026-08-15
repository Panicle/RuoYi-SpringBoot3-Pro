package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.ProjectDocument;

import java.util.List;

/**
 * 课题资料 Service 接口（资料 CRUD + submit/resubmit 审批状态机，任务卡 §三.1 + §四.1/§四.3）
 *
 * @author kys
 * @date 2026-08-15
 */
public interface IProjectDocumentService {

    /**
     * 查询资料列表（已注入数据范围；researcher 走"本人相关"专用分支；
     * 筛选 projectId/stage/approvalStatus）
     */
    List<ProjectDocument> selectDocumentList(ProjectDocument query);

    /**
     * 详情查询（scoped 闸门校验；返回含当前审批 approval + 完整历史 historyList）
     */
    ProjectDocument selectDocumentById(Long docId);

    /**
     * 新增资料（scoped 闸门；ARCHIVED 课题拒传，决策 D8）
     *
     * @return 入库后资料（含回填 docId）
     */
    ProjectDocument insertDocument(ProjectDocument doc, String operName);

    /**
     * 批量删除资料（scoped 闸门；D7 规则：PENDING/APPROVED 审批的资料拒删；
     * REJECTED 或无审批可删，级联逻辑删 approval + history）
     */
    int deleteDocumentByIds(Long[] docIds, String operName);

    /**
     * 发起审批（§四.1 完整事务：无 approval 行 → 新建 round=1 PENDING + history SUBMIT +
     * 回填 submitter_id；已有 REJECTED → 转 resubmit；PENDING/APPROVED → 拒"已发起审批"）
     */
    void submitDocument(Long docId, String operName);

    /**
     * 驳回重报（§四.3：仅 REJECTED 可重报；round+1、status=PENDING、reject_reason 清空、
     * applicant_id=当前用户 + history RESUBMIT）
     */
    void resubmitDocument(Long docId, String operName);
}
