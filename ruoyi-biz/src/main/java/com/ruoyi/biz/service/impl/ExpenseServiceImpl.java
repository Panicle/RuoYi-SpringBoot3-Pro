package com.ruoyi.biz.service.impl;

import com.ruoyi.biz.domain.Alert;
import com.ruoyi.biz.domain.BudgetSplit;
import com.ruoyi.biz.domain.Expense;
import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.mapper.AlertMapper;
import com.ruoyi.biz.mapper.BudgetSplitMapper;
import com.ruoyi.biz.mapper.ExpenseMapper;
import com.ruoyi.biz.service.IBudgetService;
import com.ruoyi.biz.service.IExpenseService;
import com.ruoyi.biz.service.IProjectService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.service.ISysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 经费记账 Service 实现（记账 / 作废 / 冲销 / 经费预警，任务卡 §四.1-§四.3）
 *
 * <p>数据权限照 ProjectServiceImpl/ContractServiceImpl 双通道：列表 researcher 走"本人相关"专用 SQL，
 * 详情与全部写操作过 scoped {@code projectService.selectProjectById} 闸门（researcher 亦须本人相关，
 * 决策 D7：researcher 对本人相关课题有记账写权限，无作废权）。</p>
 *
 * <p>金额一律 BigDecimal 2 位小数 HALF_UP（决策 D9）；budget_split 核减/回冲一律走 @Version 乐观锁；
 * 预警落 alert 表（同事务幂等去重，决策 D5）；项目两列汇总由 IBudgetService.recalcProjectBudget 派生。</p>
 *
 * @author kys
 * @date 2026-08-14
 */
@Service
@RequiredArgsConstructor
public class ExpenseServiceImpl implements IExpenseService {

    /** 课题状态（与字典 project_status 一致） */
    private static final String STATUS_ARCHIVED = "ARCHIVED";

    /** 流水状态（与字典 expense_status 一致） */
    private static final String STATUS_NORMAL = "NORMAL";
    private static final String STATUS_VOID   = "VOID";

    /** 预警类型 / 状态 / 级别（与字典 alert_type / status / alert_level 一致） */
    private static final String ALERT_TYPE_BUDGET         = "BUDGET";
    private static final String ALERT_STATUS_UNREAD       = "UNREAD";
    private static final String ALERT_STATUS_READ         = "READ";
    private static final String ALERT_STATUS_HANDLED      = "HANDLED";
    private static final String ALERT_LEVEL_WARN          = "WARN";
    private static final String ALERT_LEVEL_CRITICAL      = "CRITICAL";

    /** 透支开关配置（默认 false） */
    private static final String CONFIG_ALLOW_OVERDRAFT = "biz.expense.allowOverdraft";

    private final ExpenseMapper expenseMapper;
    private final BudgetSplitMapper budgetSplitMapper;
    private final AlertMapper alertMapper;
    private final IProjectService projectService;
    private final IBudgetService budgetService;
    private final BudgetSupport budgetSupport;
    private final ISysConfigService sysConfigService;

    // ========================================================
    //  列表 / 详情（数据范围双通道）
    // ========================================================

    @Override
    public List<Expense> selectExpenseList(Expense query) {
        if (query == null) {
            query = new Expense();
        }
        // researcher (data_scope=5) 不走 @DataScope，Service 内按角色硬分支（决策 D7：可记账、只读本人相关）
        if (isResearcher()) {
            Long me = SecurityUtils.getUserId();
            query.getParams().put("selfUserId", me);
            return expenseMapper.selectExpenseListForResearcher(query);
        }
        return expenseMapper.selectExpenseList(query);
    }

    @Override
    public Expense selectExpenseById(Long expenseId) {
        if (expenseId == null) {
            throw new ServiceException("expenseId 不能为空");
        }
        Expense e = expenseMapper.selectExpenseById(expenseId);
        if (e == null) {
            // 不暴露是否存在信息
            throw new ServiceException("无权访问");
        }
        // 过闸门：所属课题不在数据范围内抛"无权访问"（researcher 亦须本人相关）
        projectService.selectProjectById(e.getProjectId());
        // 字典翻译（前端 dict-tag 兜底）
        e.setCategoryLabel(com.ruoyi.common.utils.DictUtils.getDictLabel("budget_category", e.getCategory()));
        return e;
    }

    @Override
    public List<Expense> exportExpense(Expense query) {
        return selectExpenseList(query);
    }

    // ========================================================
    //  记账（§4.1 核心事务 9 步）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Expense insertExpense(Expense expense, String operName) {
        if (expense == null) {
            throw new ServiceException("参数为空");
        }
        // step 1. 参数校验
        if (expense.getProjectId() == null) {
            throw new ServiceException("课题不能为空");
        }
        if (expense.getAmount() == null) {
            throw new ServiceException("金额不能为空");
        }
        if (expense.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException("金额必须大于 0（负数/退款请走冲销端点）");
        }
        if (expense.getExpenseDate() == null) {
            throw new ServiceException("费用发生日期不能为空");
        }
        // step 2. scoped 闸门（researcher 走本人相关分支）
        Project project = projectService.selectProjectById(expense.getProjectId());
        // step 3. 课题状态：ARCHIVED 拒绝记账
        if (STATUS_ARCHIVED.equals(project.getStatus())) {
            throw new ServiceException("已归档课题不可记账");
        }
        // step 4. 定位 split：优先 splitId；仅传 category 时按 (projectId, category, del_flag='0') 查
        BudgetSplit split = locateSplit(expense.getProjectId(), expense.getSplitId(), expense.getCategory());

        // step 4.5 记账支出不统计人工费（LABOR）
        if ("LABOR".equals(split.getCategory())) {
            throw new ServiceException("人工费不计入记账支出，请选择其他科目");
        }

        // step 5. 预算不足校验：used + amount > budget 且 allowOverdraft=false → 拒绝
        BigDecimal used    = nz(split.getUsedAmount());
        BigDecimal budget  = nz(split.getBudgetAmount());
        BigDecimal amount  = scale(expense.getAmount());
        if (used.add(amount).compareTo(budget) > 0) {
            boolean allowOverdraft = parseAllowOverdraft();
            if (!allowOverdraft) {
                BigDecimal remain = scale(budget.subtract(used));
                String msg = "预算不足：科目" + split.getCategory() + "剩余可用额 " + remain.toPlainString()
                        + " 元，本次申请 " + amount.toPlainString() + " 元，超出 " + scale(used.add(amount).subtract(budget)).toPlainString() + " 元";
                throw new ServiceException(msg);
            }
        }

        // step 6. INSERT 流水（status=NORMAL、delFlag=0、category 以 split 为准回填）
        Expense entity = new Expense();
        entity.setProjectId(expense.getProjectId());
        entity.setSplitId(split.getSplitId());
        entity.setAmount(amount);
        entity.setTaxRate(expense.getTaxRate());  // taxRate 为字典值 String（V1.0.12），原样存，null 允许（税率可选）
        entity.setExpenseDate(expense.getExpenseDate());
        entity.setCategory(split.getCategory());          // 以 split 为准回填
        entity.setStatus(STATUS_NORMAL);
        entity.setVoucherUrl(expense.getVoucherUrl());
        entity.setDescription(expense.getDescription());
        entity.setDelFlag("0");
        entity.setCreateBy(operName);
        expenseMapper.insert(entity);

        // step 7. 带 version 乐观锁更新 split（核减）
        BigDecimal newUsed    = scale(used.add(amount));
        BigDecimal newBalance = scale(budget.subtract(newUsed));
        BudgetSplit update = new BudgetSplit();
        update.setSplitId(split.getSplitId());
        update.setUsedAmount(newUsed);
        update.setBalance(newBalance);
        update.setVersion(split.getVersion());             // 入参未传 version，以库中为准
        update.setUpdateBy(operName);
        if (budgetSplitMapper.updateById(update) == 0) {
            throw new ServiceException("预算行已被他人修改，请重试");
        }

        // step 8. 重算并回写 project.budget_total / budget_balance（D3）
        budgetService.recalcProjectBudget(expense.getProjectId());

        // step 9. 双阈值检查 + 幂等落 alert
        BudgetSplit refreshed = budgetSplitMapper.selectByProjectAndCategory(expense.getProjectId(), split.getCategory());
        if (refreshed != null) {
            budgetSupport.evaluateAlert(refreshed);
            if (Boolean.TRUE.equals(refreshed.getAlertFlag())) {
                writeBudgetAlertIfAbsent(refreshed, project, operName);
            }
        }
        return entity;
    }

    // ========================================================
    //  编辑（记账条目编辑：非金额字段就地更新；金额/科目变更走“作废原单+新增新单”）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Expense updateExpense(Expense expense, String operName) {
        if (expense == null || expense.getExpenseId() == null) {
            throw new ServiceException("expenseId 不能为空");
        }
        if (expense.getAmount() == null || expense.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException("金额必须大于 0");
        }
        if (expense.getExpenseDate() == null) {
            throw new ServiceException("费用发生日期不能为空");
        }

        Expense db = expenseMapper.selectExpenseById(expense.getExpenseId());
        if (db == null) {
            throw new ServiceException("经费流水不存在");
        }
        // scoped 闸门
        Project project = projectService.selectProjectById(db.getProjectId());
        if (STATUS_ARCHIVED.equals(project.getStatus())) {
            throw new ServiceException("已归档课题不可编辑记账");
        }
        if (STATUS_VOID.equals(db.getStatus())) {
            throw new ServiceException("已作废的流水不可编辑");
        }
        if ("LABOR".equals(db.getCategory())) {
            throw new ServiceException("人工费不计入记账支出，不可编辑");
        }

        String newCategory = StringUtils.isEmpty(expense.getCategory()) ? db.getCategory() : expense.getCategory();
        if ("LABOR".equals(newCategory)) {
            throw new ServiceException("人工费不计入记账支出，请选择其他科目");
        }
        BigDecimal oldAmount = scale(db.getAmount());
        BigDecimal newAmount = scale(expense.getAmount());
        boolean amountChanged = oldAmount.compareTo(newAmount) != 0;
        boolean categoryChanged = !newCategory.equals(db.getCategory());

        // 仅改日期/税率/凭证/说明：就地更新，不动历史金额与预算
        if (!amountChanged && !categoryChanged) {
            return updateExpenseMeta(db, expense, operName);
        }

        BudgetSplit oldSplit = budgetSplitMapper.selectByProjectAndCategory(db.getProjectId(), db.getCategory());
        if (oldSplit == null) {
            throw new ServiceException("原科目预算行不存在，无法编辑");
        }
        BudgetSplit newSplit = categoryChanged
                ? budgetSplitMapper.selectByProjectAndCategory(db.getProjectId(), newCategory)
                : oldSplit;
        if (newSplit == null) {
            throw new ServiceException("该课题未编制此科目预算");
        }

        // 编辑后可用额度：同科目 = 当前余额 + 原单金额；跨科目 = 新科目当前余额
        BigDecimal available = categoryChanged
                ? nz(newSplit.getBalance())
                : scale(nz(oldSplit.getBalance()).add(oldAmount));
        if (newAmount.compareTo(available) > 0 && !parseAllowOverdraft()) {
            BigDecimal remain = scale(available);
            throw new ServiceException("预算不足：科目" + newCategory + "剩余可用额 " + remain.toPlainString()
                    + " 元，本次申请 " + newAmount.toPlainString() + " 元，超出 "
                    + scale(newAmount.subtract(available)).toPlainString() + " 元");
        }

        // step A. 原单作废（status → VOID，带 @Version 乐观锁）
        Expense voidUpdate = new Expense();
        voidUpdate.setExpenseId(db.getExpenseId());
        voidUpdate.setStatus(STATUS_VOID);
        voidUpdate.setRemark("编辑作废：金额/科目变更，原单作废并生成新单");
        voidUpdate.setUpdateBy(operName);
        voidUpdate.setVersion(db.getVersion());
        if (expenseMapper.updateById(voidUpdate) == 0) {
            throw new ServiceException("经费流水已被他人修改，请重试");
        }

        // step B. 原科目回冲；同科目时直接合并为新金额的净变化
        BigDecimal oldNewUsed = scale(nz(oldSplit.getUsedAmount()).subtract(oldAmount).max(BigDecimal.ZERO));
        if (!categoryChanged) {
            oldNewUsed = scale(oldNewUsed.add(newAmount));
        }
        BudgetSplit oldUpd = new BudgetSplit();
        oldUpd.setSplitId(oldSplit.getSplitId());
        oldUpd.setUsedAmount(oldNewUsed);
        oldUpd.setBalance(scale(nz(oldSplit.getBudgetAmount()).subtract(oldNewUsed)));
        oldUpd.setVersion(oldSplit.getVersion());
        oldUpd.setUpdateBy(operName);
        if (budgetSplitMapper.updateById(oldUpd) == 0) {
            throw new ServiceException("预算行已被他人修改，请重试");
        }

        // step C. 跨科目时新科目核减新金额
        if (categoryChanged) {
            BigDecimal newUsed = scale(nz(newSplit.getUsedAmount()).add(newAmount));
            BudgetSplit newUpd = new BudgetSplit();
            newUpd.setSplitId(newSplit.getSplitId());
            newUpd.setUsedAmount(newUsed);
            newUpd.setBalance(scale(nz(newSplit.getBudgetAmount()).subtract(newUsed)));
            newUpd.setVersion(newSplit.getVersion());
            newUpd.setUpdateBy(operName);
            if (budgetSplitMapper.updateById(newUpd) == 0) {
                throw new ServiceException("预算行已被他人修改，请重试");
            }
        }

        // step D. 新增一笔 NORMAL 流水（历史不就地改，决策 D8）
        Expense entity = new Expense();
        entity.setProjectId(db.getProjectId());
        entity.setSplitId(newSplit.getSplitId());
        entity.setAmount(newAmount);
        entity.setTaxRate(expense.getTaxRate());
        entity.setExpenseDate(expense.getExpenseDate());
        entity.setCategory(newCategory);
        entity.setStatus(STATUS_NORMAL);
        entity.setVoucherUrl(expense.getVoucherUrl());
        entity.setDescription(expense.getDescription());
        entity.setDelFlag("0");
        entity.setCreateBy(operName);
        expenseMapper.insert(entity);

        // step E. 重算课题汇总 + 双阈值预警
        budgetService.recalcProjectBudget(db.getProjectId());
        refreshAlert(db.getProjectId(), oldSplit.getCategory(), project, operName);
        if (categoryChanged) {
            refreshAlert(db.getProjectId(), newSplit.getCategory(), project, operName);
        }
        return entity;
    }

    private Expense updateExpenseMeta(Expense db, Expense req, String operName) {
        LambdaUpdateWrapper<Expense> wrapper = new LambdaUpdateWrapper<Expense>()
                .eq(Expense::getExpenseId, db.getExpenseId())
                .eq(Expense::getVersion, db.getVersion())
                .set(Expense::getExpenseDate, req.getExpenseDate())
                .set(Expense::getTaxRate, req.getTaxRate())
                .set(Expense::getVoucherUrl, req.getVoucherUrl())
                .set(Expense::getDescription, req.getDescription())
                .set(Expense::getUpdateBy, operName)
                .set(Expense::getVersion, (db.getVersion() == null ? 0 : db.getVersion()) + 1);
        if (expenseMapper.update(null, wrapper) == 0) {
            throw new ServiceException("经费流水已被他人修改，请重试");
        }
        db.setExpenseDate(req.getExpenseDate());
        db.setTaxRate(req.getTaxRate());
        db.setVoucherUrl(req.getVoucherUrl());
        db.setDescription(req.getDescription());
        db.setUpdateBy(operName);
        return db;
    }

    /** 重跑单个科目的双阈值检查并幂等落预警（不删除已有预警） */
    private void refreshAlert(Long projectId, String category, Project project, String operName) {
        BudgetSplit refreshed = budgetSplitMapper.selectByProjectAndCategory(projectId, category);
        if (refreshed == null) {
            return;
        }
        budgetSupport.evaluateAlert(refreshed);
        if (Boolean.TRUE.equals(refreshed.getAlertFlag())) {
            writeBudgetAlertIfAbsent(refreshed, project, operName);
        }
    }

    // ========================================================
    //  作废（§4.2）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int voidExpense(Long expenseId, Integer version, String remark, String operName) {
        if (expenseId == null) {
            throw new ServiceException("expenseId 不能为空");
        }
        Expense db = expenseMapper.selectExpenseById(expenseId);
        if (db == null) {
            throw new ServiceException("经费流水不存在");
        }
        // scoped 闸门（与详情同源，无权访问抛"无权访问"）
        Project project = projectService.selectProjectById(db.getProjectId());
        // status 必须 NORMAL（已 VOID 再作废拒绝）
        if (STATUS_VOID.equals(db.getStatus())) {
            throw new ServiceException("该流水已作废，不可重复作废");
        }
        // 定位 split（按 (projectId, category) 反查；splitId 是历史绑定，split 必然存在）
        BudgetSplit split = budgetSplitMapper.selectByProjectAndCategory(db.getProjectId(), db.getCategory());
        if (split == null) {
            throw new ServiceException("该科目预算行不存在，无法作废回冲");
        }
        BigDecimal used    = nz(split.getUsedAmount());
        BigDecimal budget  = nz(split.getBudgetAmount());
        BigDecimal amount  = nz(db.getAmount());
        // 新 used = max(used - amount, 0)，不可能为负
        BigDecimal newUsed    = scale(used.subtract(amount).max(BigDecimal.ZERO));
        BigDecimal newBalance = scale(budget.subtract(newUsed));

        // 流水 status → VOID（带 @Version 乐观锁）
        Expense update = new Expense();
        update.setExpenseId(expenseId);
        update.setStatus(STATUS_VOID);
        update.setRemark(remark);
        update.setUpdateBy(operName);
        // version 优先入参，缺省取库中值
        update.setVersion(version != null ? version : db.getVersion());
        int n = expenseMapper.updateById(update);
        if (n == 0) {
            throw new ServiceException("经费流水已被他人修改，请重试");
        }
        // split 回冲（带 @Version）
        BudgetSplit bsUpdate = new BudgetSplit();
        bsUpdate.setSplitId(split.getSplitId());
        bsUpdate.setUsedAmount(newUsed);
        bsUpdate.setBalance(newBalance);
        bsUpdate.setVersion(split.getVersion());
        bsUpdate.setUpdateBy(operName);
        if (budgetSplitMapper.updateById(bsUpdate) == 0) {
            throw new ServiceException("预算行已被他人修改，请重试");
        }
        // 重算 project 汇总
        budgetService.recalcProjectBudget(db.getProjectId());
        // 重跑双阈值检查（余额回升时**不删除已有 alert**，留阶段9 收敛）
        BudgetSplit refreshed = budgetSplitMapper.selectByProjectAndCategory(db.getProjectId(), db.getCategory());
        if (refreshed != null) {
            budgetSupport.evaluateAlert(refreshed);
            if (Boolean.TRUE.equals(refreshed.getAlertFlag())) {
                writeBudgetAlertIfAbsent(refreshed, project, operName);
            }
        }
        return n;
    }

    // ========================================================
    //  冲销（§4.2）—— 追加 amount = -|入参| 的 NORMAL 流水，走 6-9 步，不做预算不足校验
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Expense refundExpense(Long originExpenseId, BigDecimal amount, Date expenseDate,
                                 String description, String operName) {
        if (originExpenseId == null) {
            throw new ServiceException("originExpenseId 不能为空");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException("冲销金额必须大于 0");
        }
        Expense origin = expenseMapper.selectExpenseById(originExpenseId);
        if (origin == null) {
            throw new ServiceException("原单不存在");
        }
        // scoped 闸门
        Project project = projectService.selectProjectById(origin.getProjectId());
        // 已作废的原单不能再冲销
        if (STATUS_VOID.equals(origin.getStatus())) {
            throw new ServiceException("已作废的流水不可冲销");
        }
        // 定位 split（用原单的 category）
        BudgetSplit split = budgetSplitMapper.selectByProjectAndCategory(origin.getProjectId(), origin.getCategory());
        if (split == null) {
            throw new ServiceException("该科目预算行不存在，无法冲销");
        }
        BigDecimal used    = nz(split.getUsedAmount());
        BigDecimal budget  = nz(split.getBudgetAmount());
        BigDecimal negAmt  = scale(amount).negate();
        Date date = expenseDate != null ? expenseDate : origin.getExpenseDate();

        // INSERT 负数 NORMAL 流水（remark 记"冲销 expense_id=x"）
        Expense entity = new Expense();
        entity.setProjectId(origin.getProjectId());
        entity.setSplitId(split.getSplitId());
        entity.setAmount(negAmt);
        entity.setExpenseDate(date);
        entity.setCategory(origin.getCategory());
        entity.setStatus(STATUS_NORMAL);
        entity.setDescription(description);
        entity.setRemark("冲销 expense_id=" + originExpenseId);
        entity.setDelFlag("0");
        entity.setCreateBy(operName);
        expenseMapper.insert(entity);

        // 冲销回冲 split（负数相加 = 减；不校验预算不足——冲销天然可能使余额回升）
        BigDecimal newUsed    = scale(used.add(negAmt).max(BigDecimal.ZERO));
        BigDecimal newBalance = scale(budget.subtract(newUsed));
        BudgetSplit bsUpdate = new BudgetSplit();
        bsUpdate.setSplitId(split.getSplitId());
        bsUpdate.setUsedAmount(newUsed);
        bsUpdate.setBalance(newBalance);
        bsUpdate.setVersion(split.getVersion());
        bsUpdate.setUpdateBy(operName);
        if (budgetSplitMapper.updateById(bsUpdate) == 0) {
            throw new ServiceException("预算行已被他人修改，请重试");
        }
        // 重算 project 汇总
        budgetService.recalcProjectBudget(origin.getProjectId());
        // 重跑双阈值（余额回升同样不删已有 alert）
        BudgetSplit refreshed = budgetSplitMapper.selectByProjectAndCategory(origin.getProjectId(), origin.getCategory());
        if (refreshed != null) {
            budgetSupport.evaluateAlert(refreshed);
            if (Boolean.TRUE.equals(refreshed.getAlertFlag())) {
                writeBudgetAlertIfAbsent(refreshed, project, operName);
            }
        }
        return entity;
    }

    // ========================================================
    //  经费预警（§3.3）
    // ========================================================

    @Override
    public List<Alert> selectAlertList(Long projectId) {
        Alert query = new Alert();
        query.setProjectId(projectId);
        // researcher 走"本人相关"专用分支（含 projectId 时也是本人相关课题的预警，决策 D7）
        if (isResearcher()) {
            Long me = SecurityUtils.getUserId();
            query.getParams().put("selfUserId", me);
            List<Alert> list = alertMapper.selectBudgetAlertListForResearcher(query);
            return fillAlertLabels(list);
        }
        // 非 researcher：projectId 非空时过闸门，空时按数据范围全量
        if (projectId != null) {
            projectService.selectProjectById(projectId);
        }
        List<Alert> list = alertMapper.selectBudgetAlertList(query);
        return fillAlertLabels(list);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int handleAlert(Long alertId, String operName) {
        if (alertId == null) {
            throw new ServiceException("alertId 不能为空");
        }
        Alert db = alertMapper.selectById(alertId);
        if (db == null) {
            throw new ServiceException("预警不存在或已删除");
        }
        if (!ALERT_TYPE_BUDGET.equals(db.getAlertType())) {
            throw new ServiceException("非经费预警，不可在此处理");
        }
        if (ALERT_STATUS_HANDLED.equals(db.getStatus())) {
            return 0;
        }
        // 通过 budget_split.projectId 过闸门（不在数据范围内的课题预警不可处理）
        BudgetSplit split = budgetSplitMapper.selectById(db.getRefId());
        if (split == null) {
            throw new ServiceException("关联预算行不存在");
        }
        projectService.selectProjectById(split.getProjectId());
        Alert update = new Alert();
        update.setAlertId(alertId);
        update.setStatus(ALERT_STATUS_HANDLED);
        update.setUpdateBy(operName);
        return alertMapper.updateById(update);
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /**
     * 定位 split：优先 splitId；仅传 category 时按 (projectId, category, del_flag='0') 查；
     * 两者都空 / 都传但 splitId 找不到 / category 找不到 → 报"该课题未编制此科目预算"。
     */
    private BudgetSplit locateSplit(Long projectId, Long splitId, String category) {
        if (splitId != null) {
            BudgetSplit split = budgetSplitMapper.selectById(splitId);
            if (split == null || !"0".equals(split.getDelFlag())) {
                throw new ServiceException("该课题未编制此科目预算");
            }
            if (!split.getProjectId().equals(projectId)) {
                throw new ServiceException("预算行与课题不匹配");
            }
            return split;
        }
        if (StringUtils.isNotEmpty(category)) {
            BudgetSplit split = budgetSplitMapper.selectByProjectAndCategory(projectId, category);
            if (split == null) {
                throw new ServiceException("该课题未编制此科目预算");
            }
            return split;
        }
        throw new ServiceException("splitId 与 category 至少传一个");
    }

    /** 幂等写经费预警的串行化锁对象（私有 final，非 this，避免外部持锁） */
    private final Object alertLock = new Object();

    /**
     * 幂等写经费预警（§4.3）：同 (alert_type='BUDGET', ref_id) 且 status ∈ (UNREAD, READ)
     * 已存在则不重复写。
     *
     * <p><b>单机部署下</b>，本方法整体加 synchronized 防止并发双写——
     * check-then-insert 之间的窗口不靠 DB 唯一约束保护（alert 表当前无该函数索引）。
     * <b>集群化部署时</b>需改为 alert 表函数唯一索引：
     * {@code CREATE UNIQUE INDEX ... ON alert (CASE WHEN status IN ('UNREAD','READ') THEN alert_type || '_' || ref_id END)}，
     * 并把 catch DuplicateKeyException 转为"幂等成功"静默返回。</p>
     */
    private void writeBudgetAlertIfAbsent(BudgetSplit split, Project project, String operName) {
        synchronized (alertLock) {
            if (alertMapper.countPendingBudgetAlert(split.getSplitId()) > 0) {
                return;     // 已有未处理预警，跳过
            }
            Alert alert = new Alert();
            alert.setAlertType(ALERT_TYPE_BUDGET);
            alert.setRefId(split.getSplitId());
            alert.setAlertLevel(ALERT_LEVEL_CRITICAL.equals(split.getAlertLevel()) ? ALERT_LEVEL_CRITICAL : ALERT_LEVEL_WARN);
            String title = "预算预警：" + project.getProjectNo() + " / " + split.getCategory();
            alert.setTitle(title);
            BigDecimal balance = nz(split.getBalance());
            BigDecimal budget  = nz(split.getBudgetAmount());
            String content = title + " 余额 " + scale(balance).toPlainString()
                    + " 元 / 预算 " + scale(budget).toPlainString() + " 元";
            if (ALERT_LEVEL_CRITICAL.equals(alert.getAlertLevel())) {
                content += "（已透支，请立即处理）";
            }
            alert.setContent(content);
            alert.setStatus(ALERT_STATUS_UNREAD);
            alert.setDelFlag("0");
            alert.setCreateBy(operName);
            alertMapper.insert(alert);
        }
    }

    /** 预警列表补字典翻译：仅翻译 alertLevel（alert_status 字典不存在，
     *  DictUtils 返回空串会导致 API 里 status 恒为空，前端却要靠 status 原始值
     *  UNREAD/READ/HANDLED 隐藏"标记已处理"按钮——故 status 原样返回）。 */
    private List<Alert> fillAlertLabels(List<Alert> list) {
        if (list == null || list.isEmpty()) {
            return list;
        }
        for (Alert a : list) {
            if (a == null) {
                continue;
            }
            a.setAlertLevel(com.ruoyi.common.utils.DictUtils.getDictLabel("alert_level", a.getAlertLevel()));
        }
        return list;
    }

    /** 解析透支开关（默认 false） */
    private boolean parseAllowOverdraft() {
        try {
            return Boolean.parseBoolean(sysConfigService.selectConfigByKey(CONFIG_ALLOW_OVERDRAFT));
        } catch (Exception e) {
            return false;
        }
    }

    /** 当前登录用户是否「精确」为 researcher（不含 admin）。
     *  照抄 ContractServiceImpl.java:363-387 现有实现风格，单次遍历 + hasAdmin/hasResearcher 标志。 */
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

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 金额 2 位小数 HALF_UP（决策 D9） */
    private static BigDecimal scale(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }

    // 保留常量区（编译期检查使用，避免 unused import）
    @SuppressWarnings("unused")
    private static final Set<String> ALERT_PENDING_STATUSES = new HashSet<>(Arrays.asList(ALERT_STATUS_UNREAD, ALERT_STATUS_READ));
}