package com.ruoyi.biz.controller;

import com.ruoyi.biz.domain.RdLaborAllocation;
import com.ruoyi.biz.domain.bo.RdAllocCalcBo;
import com.ruoyi.biz.domain.bo.RdAllocRevokeBo;
import com.ruoyi.biz.service.IRdAllocService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 研发加计扣除 — 分摊 Controller（任务卡 Task 3 端点 11-15）
 *
 * <p>权限串：biz:rd:alloc:calc（端点 11）、biz:rd:alloc:list（端点 12/15）、
 * biz:rd:alloc:confirm（端点 13）、biz:rd:alloc:revoke（端点 14）。
 * 数据权限：所有端点先过 {@code projectService.selectProjectById} scoped 闸门
 * （researcher 走本人相关，其他角色走 dept_leader 本室/全所，闸门已实现这套语义）。
 * researcher 对 /list 与 /dashboard 的他人行 hourlyRate 字段置 null（任务卡 D11）。</p>
 *
 * <p>CONFIRMED 行不可改不可删（无对应端点即天然满足，calc 步骤 3 挡重算）。
 * 算法核心下沉到 {@code RdAllocSupport.calcCore}（纯函数 + 单测覆盖 9 个场景）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@RestController
@RequestMapping("/biz/rd/alloc")
@RequiredArgsConstructor
public class RdAllocController extends BaseController {

    private final IRdAllocService rdAllocService;

    /**
     * 分摊计算（端点 11 POST /calc）— body {projectId, month}；
     * 严格按任务卡简报伪代码 1-11 步执行；DRAFT 批次软删重建；
     * CONFIRMED 批次拒（须先撤销）；缺预算/缺工资按规则报错或返回空集提示。
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:alloc:calc')")
    @Log(title = "分摊计算", businessType = BusinessType.UPDATE)
    @PostMapping("/calc")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult calc(@RequestBody RdAllocCalcBo body) {
        return success(rdAllocService.calc(body, getUsername()));
    }

    /**
     * 批次明细列表（端点 12 GET /list）— JOIN sys_user 带 researcherName；
     * researcher 对他人行 hourlyRate 置 null（任务卡 D11）。
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:alloc:list')")
    @GetMapping("/list")
    public TableDataInfo list(Long projectId, String month) {
        startPage();
        List<RdLaborAllocation> list = rdAllocService.list(projectId, month);
        return getDataTable(list);
    }

    /**
     * 批次确认（端点 13 POST /confirm）— body {projectId, month}；
     * 全部 DRAFT 行 → CONFIRMED + confirm_by/confirm_time；已 CONFIRMED 拒。
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:alloc:confirm')")
    @Log(title = "分摊确认", businessType = BusinessType.UPDATE)
    @PostMapping("/confirm")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult confirm(@RequestBody RdAllocCalcBo body) {
        return toAjax(rdAllocService.confirm(body, getUsername()));
    }

    /**
     * 撤销确认（端点 14 POST /revoke）— body {projectId, month, reason}；
     * reason 必填非空；CONFIRMED → DRAFT，confirm_by/time 清 null，reason 追加写入行 remark。
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:alloc:revoke')")
    @Log(title = "分摊撤销", businessType = BusinessType.UPDATE)
    @PostMapping("/revoke")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult revoke(@RequestBody RdAllocRevokeBo body) {
        return toAjax(rdAllocService.revoke(body, getUsername()));
    }

    /**
     * 分摊看板（端点 15 GET /dashboard）— B/Σ三值/人数/状态/闭合标志/无预算或无工时引导提示。
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:alloc:list')")
    @GetMapping("/dashboard")
    public AjaxResult dashboard(Long projectId, String month) {
        return success(rdAllocService.dashboard(projectId, month));
    }
}