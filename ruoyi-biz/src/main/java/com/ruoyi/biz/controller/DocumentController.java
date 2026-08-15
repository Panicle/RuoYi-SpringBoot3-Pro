package com.ruoyi.biz.controller;

import com.ruoyi.biz.domain.ProjectDocument;
import com.ruoyi.biz.service.IProjectDocumentService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课题资料 Controller（任务卡 §三.1 六端点；Service 先过 scoped 闸门）
 *
 * @author kys
 * @date 2026-08-15
 */
@RestController
@RequestMapping("/biz/document")
@PreAuthorize("@ss.hasPermi('biz:document:list')")
@RequiredArgsConstructor
public class DocumentController extends BaseController {

    private final IProjectDocumentService projectDocumentService;

    /**
     * 查询资料列表（分页；筛选 projectId/stage/approvalStatus；数据权限照 Project 双通道）
     */
    @PreAuthorize("@ss.hasPermi('biz:document:list')")
    @GetMapping("/list")
    public TableDataInfo list(ProjectDocument doc) {
        startPage();
        List<ProjectDocument> list = projectDocumentService.selectDocumentList(doc);
        return getDataTable(list);
    }

    /**
     * 获取资料详情（含当前审批 approval + 完整历史 historyList）
     */
    @PreAuthorize("@ss.hasPermi('biz:document:query')")
    @GetMapping("/{docId}")
    public AjaxResult getInfo(@PathVariable("docId") Long docId) {
        return success(projectDocumentService.selectDocumentById(docId));
    }

    /**
     * 上传资料（projectId/stage/fileName/fileUrl/planSubmitDate；ARCHIVED 课题拒传）
     */
    @PreAuthorize("@ss.hasPermi('biz:document:add')")
    @Log(title = "课题资料", businessType = BusinessType.INSERT)
    @PostMapping
    @RepeatSubmit(interval = 2000)
    public AjaxResult add(@RequestBody ProjectDocument doc) {
        return success(projectDocumentService.insertDocument(doc, getUsername()));
    }

    /**
     * 删除资料（D7 规则：PENDING/APPROVED 审批拒删；REJECTED/无审批可删，级联逻辑删 approval+history）
     */
    @PreAuthorize("@ss.hasPermi('biz:document:remove')")
    @Log(title = "课题资料", businessType = BusinessType.DELETE)
    @DeleteMapping("/{docIds}")
    @RepeatSubmit(interval = 2000)
    public AjaxResult remove(@PathVariable Long[] docIds) {
        return toAjax(projectDocumentService.deleteDocumentByIds(docIds, getUsername()));
    }

    /**
     * 发起审批（round=1 PENDING；写 approval + history SUBMIT）
     */
    @PreAuthorize("@ss.hasPermi('biz:document:submit')")
    @Log(title = "课题资料", businessType = BusinessType.UPDATE)
    @PostMapping("/submit/{docId}")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult submit(@PathVariable("docId") Long docId) {
        projectDocumentService.submitDocument(docId, getUsername());
        return success();
    }

    /**
     * 驳回重报（仅 REJECTED 可重报；round+1 PENDING；写 history RESUBMIT）
     */
    @PreAuthorize("@ss.hasPermi('biz:document:submit')")
    @Log(title = "课题资料", businessType = BusinessType.UPDATE)
    @PostMapping("/resubmit/{docId}")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult resubmit(@PathVariable("docId") Long docId) {
        projectDocumentService.resubmitDocument(docId, getUsername());
        return success();
    }
}
