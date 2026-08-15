package com.ruoyi.biz.controller;

import com.ruoyi.biz.domain.RdWorktimeMonthly;
import com.ruoyi.biz.domain.bo.RdWorktimeSaveBo;
import com.ruoyi.biz.service.IRdWorktimeService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 研发加计扣除 — 工时 Controller（任务卡 Task 2 端点 7-10）
 *
 * <p>权限串 biz:rd:worktime:list + biz:rd:worktime:save + biz:rd:worktime:copy。
 * 数据权限：日历 / save / copy 三档由 Service 校验（researcher 仅本人相关或本课题成员）；
 * 月度汇总（端点 10）走 @DataScope 三档（researcher 本人 / dept_leader 本室 / 其余全所）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@RestController
@RequestMapping("/biz/rd/worktime")
@PreAuthorize("@ss.hasPermi('biz:rd:worktime:list')")
@RequiredArgsConstructor
public class RdWorktimeController extends BaseController {

    private final IRdWorktimeService rdWorktimeService;

    // ========================================================
    //  工时（端点 7 /calendar + 端点 8 /save + 端点 9 /copyLastMonth + 端点 10 /monthly/list）
    // ========================================================

    /**
     * 月度日历 — {days:[{workDate,rdHours,dayTotalAcrossProjects}], monthTotal}；
     * researcher 只能查 researcherId=self。
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:worktime:list')")
    @GetMapping("/calendar")
    public AjaxResult calendar(Long projectId, Long researcherId, String month) {
        return success(rdWorktimeService.calendar(projectId, researcherId, month));
    }

    /**
     * 工时保存 — D9 全套校验（0&lt;rdHours≤24、跨课题同日合计≤24、rdHours=0 视为软删）
     * + 同事务月汇总重算。
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:worktime:save')")
    @Log(title = "工时填报", businessType = BusinessType.UPDATE)
    @PutMapping("/save")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult save(@RequestBody RdWorktimeSaveBo body) {
        return toAjax(rdWorktimeService.saveWorktime(body, getUsername()));
    }

    /**
     * 复制上月 — 按日序号映射；目标日已有则跳过；返回 copiedCount / skippedCount。
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:worktime:copy')")
    @Log(title = "工时复制上月", businessType = BusinessType.INSERT)
    @PostMapping("/copyLastMonth")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult copyLastMonth(@RequestBody RdWorktimeSaveBo body) {
        return success(rdWorktimeService.copyLastMonth(body, getUsername()));
    }

    /**
     * 月度汇总分页列表（数据权限三档 — researcher 仅本人 / dept_leader 本室 / 其余全所）
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:worktime:list')")
    @GetMapping("/monthly/list")
    public TableDataInfo monthlyList(RdWorktimeMonthly monthly) {
        startPage();
        List<RdWorktimeMonthly> list = rdWorktimeService.selectMonthlyList(monthly);
        return getDataTable(list);
    }
}