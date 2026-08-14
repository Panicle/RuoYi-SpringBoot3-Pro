package com.ruoyi.biz.controller;

import com.ruoyi.biz.domain.BudgetSplit;
import com.ruoyi.biz.service.IBudgetService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课题预算 Controller（/biz/budget 三端点，任务卡 §3.1）
 *
 * <p>预算细分是预算与余额的事实来源，调整走独立端点（不再随课题保存全量替换）。
 * 数据权限由 Service 内 scoped {@code projectService.selectProjectById} 闸门统一把关。</p>
 *
 * <p>列表/汇总返回 AjaxResult.success(...)（不是 TableDataInfo）——固定十科目行，不分页，
 * 与 contract 的 nodeList 模式一致。</p>
 *
 * @author kys
 * @date 2026-08-14
 */
@RestController
@RequestMapping("/biz/budget")
@PreAuthorize("@ss.hasPermi('biz:expense:list')")
@RequiredArgsConstructor
public class BudgetController extends BaseController {

    private final IBudgetService budgetService;

    /**
     * 查询某课题的十科目预算行（budget/used/balance/version）
     */
    @PreAuthorize("@ss.hasPermi('biz:expense:query')")
    @GetMapping("/list")
    public AjaxResult list(Long projectId) {
        return success(budgetService.selectBudgetList(projectId));
    }

    /**
     * 预算调整（body: projectId + splits[{category,budgetAmount,version}]）。
     * 按 category 增量更新保 split_id、带 version 乐观锁、监管上限校验、同事务重算课题汇总。
     */
    @PreAuthorize("@ss.hasPermi('biz:expense:budget')")
    @Log(title = "预算调整", businessType = BusinessType.UPDATE)
    @PutMapping("/adjust")
    @RepeatSubmit(interval = 2000)
    public AjaxResult adjust(@RequestBody BudgetAdjustBody body) {
        if (body == null) {
            return error("参数不完整");
        }
        return toAjax(budgetService.adjustBudget(body.getProjectId(), body.getSplits(), getUsername()));
    }

    /**
     * 课题预算汇总（总额/已用/余额 + 各分组小计 + 逐行预警标志）
     */
    @PreAuthorize("@ss.hasPermi('biz:expense:query')")
    @GetMapping("/summary")
    public AjaxResult summary(Long projectId) {
        return success(budgetService.selectBudgetSummary(projectId));
    }

    /**
     * 预算调整请求体（§3.1 约定的 projectId + splits 结构）
     */
    @Data
    public static class BudgetAdjustBody {

        /** 课题ID */
        private Long projectId;

        /** 调整的预算科目行（category / budgetAmount / version） */
        private List<BudgetSplit> splits;
    }
}
