package com.ruoyi.biz.controller;

import com.ruoyi.biz.domain.Alert;
import com.ruoyi.biz.domain.Expense;
import com.ruoyi.biz.service.IExpenseService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 经费记账 Controller（/biz/expense 记账六端点 + /biz/expense/alert 预警两端点，任务卡 §3.2 + §3.3）
 *
 * <p>类级 @PreAuthorize('biz:expense:list') 照 ContractController 写法。researcher 权限：
 * list / query / add / alert（决策 D7 + §2.5 矩阵），**无 void**——作废端点 @PreAuthorize 强制要求
 * biz:expense:void 权限串，researcher 不可访问。</p>
 *
 * @author kys
 * @date 2026-08-14
 */
@RestController
@RequestMapping("/biz/expense")
@PreAuthorize("@ss.hasPermi('biz:expense:list')")
@RequiredArgsConstructor
public class ExpenseController extends BaseController {

    private final IExpenseService expenseService;

    // ========================================================
    //  记账六端点（§3.2）
    // ========================================================

    /**
     * 经费流水分页列表（数据权限双通道：researcher 走本人相关专用 SQL）
     */
    @PreAuthorize("@ss.hasPermi('biz:expense:list')")
    @GetMapping("/list")
    public TableDataInfo list(Expense expense) {
        startPage();
        List<Expense> list = expenseService.selectExpenseList(expense);
        return getDataTable(list);
    }

    /**
     * 经费流水详情（过 scoped 闸门）
     */
    @PreAuthorize("@ss.hasPermi('biz:expense:query')")
    @GetMapping("/{expenseId}")
    public AjaxResult getInfo(@PathVariable("expenseId") Long expenseId) {
        return success(expenseService.selectExpenseById(expenseId));
    }

    /**
     * 记账（核心事务：参数校验→scoped 闸门→定位 split→预算校验→乐观锁核减→重算汇总→双阈值预警）
     */
    @PreAuthorize("@ss.hasPermi('biz:expense:add')")
    @Log(title = "经费记账", businessType = BusinessType.INSERT)
    @PostMapping
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult add(@RequestBody Expense expense) {
        Expense saved = expenseService.insertExpense(expense, getUsername());
        return success(saved);
    }

    /**
     * 编辑记账：非金额字段就地更新；金额/科目变更按“作废原单+新增新单”处理
     * （复用 add 权限，避免额外配置菜单权限串）。
     */
    @PreAuthorize("@ss.hasPermi('biz:expense:add')")
    @Log(title = "经费记账编辑", businessType = BusinessType.UPDATE)
    @PutMapping
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult edit(@RequestBody Expense expense) {
        Expense saved = expenseService.updateExpense(expense, getUsername());
        return success(saved);
    }

    /**
     * 作废（status→VOID，事务内回冲 used_amount/balance，带 @Version 乐观锁）
     * researcher 无此权限——权限串由 @PreAuthorize 强校验，403 拒绝
     */
    @PreAuthorize("@ss.hasPermi('biz:expense:void')")
    @Log(title = "经费作废", businessType = BusinessType.UPDATE)
    @PutMapping("/void/{expenseId}")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult voidExpense(@PathVariable("expenseId") Long expenseId,
                                 @RequestBody(required = false) Map<String, Object> body) {
        Integer version = parseVersion(body == null ? null : body.get("version"));
        String remark = body == null ? null : (body.get("remark") == null ? null : body.get("remark").toString());
        return toAjax(expenseService.voidExpense(expenseId, version, remark, getUsername()));
    }

    /**
     * 冲销（追加 amount = -|入参| 的 NORMAL 流水；走记账事务的 6-9 步，不做预算不足校验）
     * 同样走 add 权限串（§3.2 明确归在 /refund POST 下，复用 add 权限）
     */
    @PreAuthorize("@ss.hasPermi('biz:expense:add')")
    @Log(title = "经费冲销", businessType = BusinessType.INSERT)
    @PostMapping("/refund")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult refund(@RequestBody Map<String, Object> body) {
        if (body == null) {
            return error("参数不能为空");
        }
        Object originObj = body.get("originExpenseId");
        Object amountObj = body.get("amount");
        if (originObj == null || amountObj == null) {
            return error("originExpenseId 与 amount 必填");
        }
        Long originExpenseId;
        BigDecimal amount;
        try {
            originExpenseId = (originObj instanceof Number) ? ((Number) originObj).longValue() : Long.parseLong(originObj.toString());
            amount = new BigDecimal(amountObj.toString());
        } catch (NumberFormatException e) {
            return error("参数格式错误");
        }
        Object dateObj   = body.get("expenseDate");
        Object descObj   = body.get("description");
        Date expenseDate = null;
        if (dateObj != null) {
            expenseDate = parseDate(dateObj);
        }
        String description = descObj == null ? null : descObj.toString();
        Expense saved = expenseService.refundExpense(originExpenseId, amount, expenseDate, description, getUsername());
        return success(saved);
    }

    /**
     * 经费流水导出（复用列表的数据权限双通道）
     */
    @PreAuthorize("@ss.hasPermi('biz:expense:export')")
    @Log(title = "经费流水导出", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, Expense expense) {
        List<Expense> list = expenseService.exportExpense(expense);
        ExcelUtil<Expense> util = new ExcelUtil<>(Expense.class);
        util.exportExcel(response, list, "经费流水");
    }

    // ========================================================
    //  经费预警两端点（§3.3）
    // ========================================================

    /**
     * 经费预警列表（alert_type='BUDGET'）；
     * projectId 非空时限定该课题并过 scoped 闸门；为空时按数据范围全量
     * （researcher 走"本人相关"专用 SQL，权限不下放到他人课题预警）
     */
    @PreAuthorize("@ss.hasPermi('biz:expense:alert')")
    @GetMapping("/alert/list")
    public AjaxResult alertList(Long projectId) {
        List<Alert> list = expenseService.selectAlertList(projectId);
        return success(list);
    }

    /**
     * 标记预警已处理（status→HANDLED，先过预警所属课题的 scoped 闸门）
     */
    @PreAuthorize("@ss.hasPermi('biz:expense:alert')")
    @Log(title = "经费预警处理", businessType = BusinessType.UPDATE)
    @PutMapping("/alert/handle/{alertId}")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult handleAlert(@PathVariable("alertId") Long alertId) {
        return toAjax(expenseService.handleAlert(alertId, getUsername()));
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /** 入参 version 兼容 Number / 字符串 / null（前端可能不传 version） */
    private Integer parseVersion(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 入参日期兼容 Date / 数字 / 字符串（与 ContractController 一致） */
    private Date parseDate(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Date) {
            return (Date) o;
        }
        if (o instanceof Number) {
            return new Date(((Number) o).longValue());
        }
        String s = o.toString();
        String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"};
        for (String p : patterns) {
            try {
                return new java.text.SimpleDateFormat(p).parse(s);
            } catch (Exception ignore) {
                // 尝试下一种格式
            }
        }
        return null;
    }
}