package com.ruoyi.biz.controller;

import com.ruoyi.biz.domain.Approval;
import com.ruoyi.biz.domain.ApprovalHistory;
import com.ruoyi.biz.service.IApprovalService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 审批 Controller（任务卡 §三.2 三端点；Service 先过 scoped 闸门）
 *
 * <p>类级权限用 biz:approval:history（所有具备审批访问权的角色均含：admin/science_admin/dept_leader/researcher），
 * 方法级以 @PreAuthorize 覆盖精化：/list、/audit 仅 biz:approval:audit（dept_leader/science_admin/admin），
 * /history 仅 biz:approval:history（researcher 亦可见本人审批历史）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@RestController
@RequestMapping("/biz/approval")
@PreAuthorize("@ss.hasPermi('biz:approval:history')")
@RequiredArgsConstructor
public class ApprovalController extends BaseController {

    private final IApprovalService approvalService;

    /**
     * 查询审批列表（分页；dept_leader 本室待审/已审；science_admin 全所；researcher 仅本人申请）
     */
    @PreAuthorize("@ss.hasPermi('biz:approval:audit')")
    @GetMapping("/list")
    public TableDataInfo list(Approval query) {
        startPage();
        List<Approval> list = approvalService.selectApprovalList(query);
        return getDataTable(list);
    }

    /**
     * 查询审批历史（按 round + operate_time 时间线）
     */
    @PreAuthorize("@ss.hasPermi('biz:approval:history')")
    @GetMapping("/history/{approvalId}")
    public AjaxResult history(@PathVariable("approvalId") Long approvalId) {
        List<ApprovalHistory> list = approvalService.selectApprovalHistory(approvalId);
        return success(list);
    }

    /**
     * 审批（body: action=APPROVE/REJECT、comment 审批意见、rejectReason 驳回原因；
     * 仅 PENDING 且审批人=本室 dept_leader（或 science_admin/admin）可审；双写 approval + history）
     */
    @PreAuthorize("@ss.hasPermi('biz:approval:audit')")
    @Log(title = "课题审批", businessType = BusinessType.UPDATE)
    @PutMapping("/audit/{approvalId}")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult audit(@PathVariable("approvalId") Long approvalId, @RequestBody Map<String, Object> body) {
        Object actionObj = body.get("action");
        if (actionObj == null) {
            return error("action 不能为空");
        }
        String action = actionObj.toString();
        Object commentObj = body.get("comment");
        String comment = commentObj == null ? null : commentObj.toString();
        Object reasonObj = body.get("rejectReason");
        String rejectReason = reasonObj == null ? null : reasonObj.toString();
        approvalService.auditApproval(approvalId, action, comment, rejectReason, getUsername());
        return success();
    }
}
