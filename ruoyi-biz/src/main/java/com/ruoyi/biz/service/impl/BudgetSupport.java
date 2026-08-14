package com.ruoyi.biz.service.impl;

import com.ruoyi.biz.domain.BudgetSplit;
import com.ruoyi.biz.mapper.BudgetSplitMapper;
import com.ruoyi.biz.mapper.ProjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 预算规则支撑（预算细分增量更新 / 监管上限校验 / 汇总派生 / 双阈值判定）
 *
 * <p>为什么单独一个组件：预算细分的写入语义被两条路径共用——课题保存（ProjectServiceImpl）与
 * 预算调整（BudgetServiceImpl）。而 BudgetServiceImpl 需要注入 IProjectService 过数据范围闸门，
 * 若把规则挂在 IBudgetService 上再让 ProjectServiceImpl 注入，构造器注入会形成循环依赖启动失败。
 * 本组件只依赖两个 Mapper，两边都能安全注入，且保证 D1 语义只有一处实现。</p>
 *
 * <p>金额一律 BigDecimal，2 位小数 HALF_UP（决策 D9）。</p>
 *
 * @author kys
 * @date 2026-08-14
 */
@Component
@RequiredArgsConstructor
public class BudgetSupport {

    /** 预算科目白名单（字典 budget_category 十科目，顺序与 dict_sort 一致，对外展示按此序） */
    public static final List<String> CATEGORIES = Collections.unmodifiableList(Arrays.asList(
            "LABOR", "EQUIPMENT", "MATERIAL", "TESTING", "FUEL", "TRAVEL", "PUBLICATION",
            "INDIRECT", "OUTSOURCING", "TAX"));

    /** 直接费科目（§4.4：直接费 = LABOR+EQUIPMENT+MATERIAL+TESTING+FUEL+TRAVEL+PUBLICATION） */
    public static final List<String> DIRECT_CATEGORIES = Collections.unmodifiableList(Arrays.asList(
            "LABOR", "EQUIPMENT", "MATERIAL", "TESTING", "FUEL", "TRAVEL", "PUBLICATION"));

    private static final String CATEGORY_EQUIPMENT   = "EQUIPMENT";
    private static final String CATEGORY_INDIRECT    = "INDIRECT";
    private static final String CATEGORY_OUTSOURCING = "OUTSOURCING";

    /** 间接费分段阈值（元）：B ≤ 500万 → 30%；500万 < B ≤ 1000万 → 25%；B > 1000万 → 20% */
    private static final BigDecimal TIER_1 = new BigDecimal("5000000");
    private static final BigDecimal TIER_2 = new BigDecimal("10000000");
    private static final BigDecimal RATE_30 = new BigDecimal("0.30");
    private static final BigDecimal RATE_25 = new BigDecimal("0.25");
    private static final BigDecimal RATE_20 = new BigDecimal("0.20");

    /** 双阈值预警（§4.3）：绝对值阈值 1000 元、比例阈值 5% */
    private static final BigDecimal ALERT_ABSOLUTE = new BigDecimal("1000");
    private static final BigDecimal ALERT_RATIO    = new BigDecimal("0.05");

    /** 预警级别（字典 alert_level） */
    private static final String ALERT_LEVEL_CRITICAL = "CRITICAL";
    private static final String ALERT_LEVEL_WARN     = "WARN";

    private final BudgetSplitMapper budgetSplitMapper;
    private final ProjectMapper projectMapper;

    // ========================================================
    //  校验
    // ========================================================

    /**
     * 入参行校验：category 必须属于 budget_category 十科目白名单、同一请求内不得重复、金额非负
     * （消化阶段2挂账 M-1；DB 唯一索引 idx_budget_split_pc_uk 兜底，此处给友好业务错误）。
     * 同时把 null 金额归整为 0。
     */
    public void validateCategories(List<BudgetSplit> splits) {
        if (splits == null || splits.isEmpty()) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (BudgetSplit split : splits) {
            if (split == null) {
                continue;
            }
            String category = split.getCategory();
            if (StringUtils.isEmpty(category)) {
                throw new ServiceException("预算科目不能为空");
            }
            if (!CATEGORIES.contains(category)) {
                throw new ServiceException("预算科目不合法：" + category);
            }
            if (!seen.add(category)) {
                throw new ServiceException("预算科目重复：" + category);
            }
            if (split.getBudgetAmount() == null) {
                split.setBudgetAmount(BigDecimal.ZERO);
            }
            if (split.getBudgetAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new ServiceException("预算科目金额不能为负数");
            }
        }
    }

    /**
     * 监管上限校验（§4.4，预算调整与课题预算保存共用）。
     * 入参必须是本次保存后的**完整终态**（库中未改动的科目也要带上），否则基数 B 会被算小。
     *
     * <p>直接费 = LABOR+EQUIPMENT+MATERIAL+TESTING+FUEL+TRAVEL+PUBLICATION，基数 B = 直接费 - EQUIPMENT；
     * B = 0 时跳过两项校验。</p>
     */
    public void validateRegulatoryLimits(List<BudgetSplit> effectiveSplits) {
        Map<String, BigDecimal> amounts = toAmountMap(effectiveSplits);
        BigDecimal base = BigDecimal.ZERO;
        for (String category : DIRECT_CATEGORIES) {
            base = base.add(nz(amounts.get(category)));
        }
        base = scale(base.subtract(nz(amounts.get(CATEGORY_EQUIPMENT))));
        if (base.compareTo(BigDecimal.ZERO) <= 0) {
            // B = 0（无直接费基数）跳过校验
            return;
        }
        // 1. 间接费分段上限
        BigDecimal indirectRate  = indirectRate(base);
        BigDecimal indirectLimit = scale(base.multiply(indirectRate));
        BigDecimal indirect      = nz(amounts.get(CATEGORY_INDIRECT));
        if (indirect.compareTo(indirectLimit) > 0) {
            throw new ServiceException(limitMessage("间接费", base, indirectRate, indirectLimit, indirect));
        }
        // 2. 委外支出费上限（固定 30%）
        BigDecimal outsourcingLimit = scale(base.multiply(RATE_30));
        BigDecimal outsourcing      = nz(amounts.get(CATEGORY_OUTSOURCING));
        if (outsourcing.compareTo(outsourcingLimit) > 0) {
            throw new ServiceException(limitMessage("委外支出费", base, RATE_30, outsourcingLimit, outsourcing));
        }
    }

    // ========================================================
    //  预算行增量更新（D1 / §4.5 唯一实现）
    // ========================================================

    /**
     * 按 (projectId, category) 增量写入预算行——绝不删旧插新，split_id 全程保持稳定（决策 D1）。
     *
     * <ul>
     *   <li>库中已有该科目 → UPDATE budget_amount，并重算 balance = budget_amount - used_amount（带 version 乐观锁）</li>
     *   <li>库中没有且金额 &gt; 0 → INSERT（used_amount=0、balance=budget_amount、version=0）</li>
     *   <li>库中没有且金额 = 0 → 跳过（不造无意义空行）</li>
     *   <li>库中有但本次未传 → {@code zeroMissing=true} 时金额置 0（保留行与 split_id，不逻辑删）；
     *       {@code zeroMissing=false} 时原样保留</li>
     * </ul>
     *
     * <p>{@code zeroMissing} 语义区分：课题保存（§4.5）是**全量终态**语义，未传即置 0；
     * 预算调整端点是**按科目补丁**语义，未传即不动（否则调一个科目会把其余九项清零）。</p>
     *
     * @return 写入后的合计（budget_total = Σ budget_amount、budget_balance = Σ balance）
     */
    public Totals applySplits(Long projectId, List<BudgetSplit> requested, boolean zeroMissing, String operName) {
        if (projectId == null) {
            throw new ServiceException("projectId 不能为空");
        }
        validateCategories(requested);

        Map<String, BudgetSplit> dbMap = new LinkedHashMap<>();
        for (BudgetSplit db : budgetSplitMapper.selectByProjectId(projectId)) {
            dbMap.put(db.getCategory(), db);
        }
        Map<String, BudgetSplit> reqMap = new LinkedHashMap<>();
        if (requested != null) {
            for (BudgetSplit req : requested) {
                if (req != null) {
                    reqMap.put(req.getCategory(), req);
                }
            }
        }
        // 监管上限校验：以「本次保存后的终态」为准（补丁语义下需带上库中未改动的科目）
        validateRegulatoryLimits(buildEffective(dbMap, reqMap, zeroMissing));

        BigDecimal totalBudget  = BigDecimal.ZERO;
        BigDecimal totalBalance = BigDecimal.ZERO;
        Date now = new Date();

        // 遍历「十科目 ∪ 库中已有科目」：库中若残留白名单外的历史科目行，也必须计入合计，
        // 否则本方法算出的 Σ 会与 recalcProjectBudget（全表求和）对不上
        Set<String> categories = new LinkedHashSet<>(CATEGORIES);
        categories.addAll(dbMap.keySet());
        for (String category : categories) {
            BudgetSplit db  = dbMap.get(category);
            BudgetSplit req = reqMap.get(category);
            if (req == null && (db == null || !zeroMissing)) {
                // 未传且不需置 0：库中有则原样计入合计，库中无则整行不存在
                if (db != null) {
                    totalBudget  = totalBudget.add(nz(db.getBudgetAmount()));
                    totalBalance = totalBalance.add(nz(db.getBalance()));
                }
                continue;
            }
            BigDecimal amount = req == null ? BigDecimal.ZERO : scale(nz(req.getBudgetAmount()));
            if (db == null) {
                if (amount.compareTo(BigDecimal.ZERO) == 0) {
                    continue;  // 库中无行且金额为 0：不造空行
                }
                insertSplit(projectId, category, amount, operName, now);
                totalBudget  = totalBudget.add(amount);
                totalBalance = totalBalance.add(amount);
            } else {
                BigDecimal used    = scale(nz(db.getUsedAmount()));
                BigDecimal balance = scale(amount.subtract(used));
                updateSplit(db, req, amount, balance, operName, now);
                totalBudget  = totalBudget.add(amount);
                totalBalance = totalBalance.add(balance);
            }
        }
        return new Totals(scale(totalBudget), scale(totalBalance));
    }

    /**
     * 重算并回写 project 的预算汇总两列（D3）。记账/作废/冲销事务同样调用本方法。
     */
    public void recalcProjectBudget(Long projectId) {
        if (projectId == null) {
            throw new ServiceException("projectId 不能为空");
        }
        Totals totals = sumTotals(budgetSplitMapper.selectByProjectId(projectId));
        projectMapper.updateBudgetSummary(projectId, totals.getBudgetTotal(), totals.getBalanceTotal());
    }

    // ========================================================
    //  查询辅助
    // ========================================================

    /**
     * 取课题的十科目预算行（DB 有则用 DB 行，无则补零值占位行，splitId/version 为 null）。
     * 占位行仅用于前端渲染与回填，回传时按 category 匹配，金额为 0 且库中无行者不会落库。
     */
    public List<BudgetSplit> selectFullSplits(Long projectId) {
        Map<String, BudgetSplit> dbMap = new LinkedHashMap<>();
        for (BudgetSplit db : budgetSplitMapper.selectByProjectId(projectId)) {
            dbMap.put(db.getCategory(), db);
        }
        // 十科目 ∪ 库中已有科目（库中残留白名单外的历史科目行也带出来，保证汇总与 project 两列一致）
        Set<String> categories = new LinkedHashSet<>(CATEGORIES);
        categories.addAll(dbMap.keySet());
        List<BudgetSplit> result = new ArrayList<>(categories.size());
        for (String category : categories) {
            BudgetSplit split = dbMap.get(category);
            if (split == null) {
                split = new BudgetSplit();
                split.setProjectId(projectId);
                split.setCategory(category);
                split.setBudgetAmount(BigDecimal.ZERO);
                split.setUsedAmount(BigDecimal.ZERO);
                split.setBalance(BigDecimal.ZERO);
                split.setDelFlag("0");
            } else {
                split.setBudgetAmount(scale(nz(split.getBudgetAmount())));
                split.setUsedAmount(scale(nz(split.getUsedAmount())));
                split.setBalance(scale(nz(split.getBalance())));
            }
            result.add(split);
        }
        return result;
    }

    /**
     * 合计一组预算行（budget_total = Σ budget_amount、budget_balance = Σ balance）
     */
    public Totals sumTotals(List<BudgetSplit> splits) {
        BigDecimal totalBudget  = BigDecimal.ZERO;
        BigDecimal totalBalance = BigDecimal.ZERO;
        if (splits != null) {
            for (BudgetSplit split : splits) {
                if (split == null) {
                    continue;
                }
                totalBudget  = totalBudget.add(nz(split.getBudgetAmount()));
                totalBalance = totalBalance.add(nz(split.getBalance()));
            }
        }
        return new Totals(scale(totalBudget), scale(totalBalance));
    }

    /**
     * 双阈值预警判定（§4.3）并回填 alertFlag / alertLevel。
     * 触发条件（OR）：balance ≤ 1000 或 (budget_amount &gt; 0 且 balance / budget_amount ≤ 5%)；
     * budget_amount = 0 时比例条件不参与判断。级别：balance ≤ 0 → CRITICAL，否则 WARN。
     *
     * <p>附加前置：预算与已用**双双为 0** 的科目（未编制预算的占位行 / 被调整清零且无流水的行）不判预警——
     * 否则一个尚未编制预算的课题十科目会齐刷刷报 CRITICAL。§4.3 的书写场景是记账后的预算行，
     * 记账要求该科目已编制预算（§4.1 第 4 步），故此前置不改变记账链路的判定结果。</p>
     */
    public void evaluateAlert(BudgetSplit split) {
        if (split == null) {
            return;
        }
        BigDecimal balance = nz(split.getBalance());
        BigDecimal budget  = nz(split.getBudgetAmount());
        if (budget.compareTo(BigDecimal.ZERO) == 0 && nz(split.getUsedAmount()).compareTo(BigDecimal.ZERO) == 0) {
            split.setAlertFlag(false);
            split.setAlertLevel(null);
            return;
        }
        boolean hit = balance.compareTo(ALERT_ABSOLUTE) <= 0;
        if (!hit && budget.compareTo(BigDecimal.ZERO) > 0) {
            hit = balance.divide(budget, 6, RoundingMode.HALF_UP).compareTo(ALERT_RATIO) <= 0;
        }
        split.setAlertFlag(hit);
        split.setAlertLevel(!hit ? null
                : (balance.compareTo(BigDecimal.ZERO) <= 0 ? ALERT_LEVEL_CRITICAL : ALERT_LEVEL_WARN));
    }

    /** 金额统一 2 位小数 HALF_UP（决策 D9） */
    public static BigDecimal scale(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP);
    }

    // ========================================================
    //  私有工具
    // ========================================================

    private void insertSplit(Long projectId, String category, BigDecimal amount, String operName, Date now) {
        BudgetSplit entity = new BudgetSplit();
        entity.setProjectId(projectId);
        entity.setCategory(category);
        entity.setBudgetAmount(amount);
        entity.setUsedAmount(BigDecimal.ZERO);
        entity.setBalance(amount);
        entity.setVersion(0);
        entity.setDelFlag("0");
        entity.setCreateBy(operName);
        entity.setCreateTime(now);
        budgetSplitMapper.insert(entity);
    }

    /**
     * 带 @Version 乐观锁更新一行；金额与余额均未变化时跳过（避免课题保存时无谓自增 version，
     * 把别人打开着的预算调整弹窗顶掉）。影响行数 0 即并发冲突。
     */
    private void updateSplit(BudgetSplit db, BudgetSplit req, BigDecimal amount, BigDecimal balance,
                             String operName, Date now) {
        // amount 与 balance 均未变化 = 无写意图，跳过 UPDATE 是安全的（不读不写不会丢更新，
        // 反而避免课题保存时把 version 自增、顶掉别人正开着的预算调整弹窗）
        if (amount.compareTo(scale(nz(db.getBudgetAmount()))) == 0
                && balance.compareTo(scale(nz(db.getBalance()))) == 0) {
            return;
        }
        BudgetSplit entity = new BudgetSplit();
        entity.setSplitId(db.getSplitId());
        entity.setBudgetAmount(amount);
        entity.setBalance(balance);
        // version 优先取入参（预算调整端点由前端回传），缺省退回库中版本
        Integer version = (req == null || req.getVersion() == null) ? db.getVersion() : req.getVersion();
        entity.setVersion(version == null ? 0 : version);
        entity.setUpdateBy(operName);
        entity.setUpdateTime(now);
        if (budgetSplitMapper.updateById(entity) == 0) {
            throw new ServiceException("预算行已被他人修改，请重试");
        }
    }

    /**
     * 构造「本次保存后的终态」科目金额清单，供监管上限校验使用。
     */
    private List<BudgetSplit> buildEffective(Map<String, BudgetSplit> dbMap, Map<String, BudgetSplit> reqMap,
                                             boolean zeroMissing) {
        List<BudgetSplit> effective = new ArrayList<>(CATEGORIES.size());
        for (String category : CATEGORIES) {
            BudgetSplit req = reqMap.get(category);
            BudgetSplit db  = dbMap.get(category);
            BigDecimal amount;
            if (req != null) {
                amount = nz(req.getBudgetAmount());
            } else if (db != null && !zeroMissing) {
                amount = nz(db.getBudgetAmount());
            } else {
                amount = BigDecimal.ZERO;
            }
            BudgetSplit row = new BudgetSplit();
            row.setCategory(category);
            row.setBudgetAmount(amount);
            effective.add(row);
        }
        return effective;
    }

    private Map<String, BigDecimal> toAmountMap(List<BudgetSplit> splits) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        if (splits == null) {
            return map;
        }
        for (BudgetSplit split : splits) {
            if (split == null || StringUtils.isEmpty(split.getCategory())) {
                continue;
            }
            map.put(split.getCategory(), nz(split.getBudgetAmount()));
        }
        return map;
    }

    private BigDecimal indirectRate(BigDecimal base) {
        if (base.compareTo(TIER_1) <= 0) {
            return RATE_30;
        }
        if (base.compareTo(TIER_2) <= 0) {
            return RATE_25;
        }
        return RATE_20;
    }

    private String limitMessage(String label, BigDecimal base, BigDecimal rate,
                                BigDecimal limit, BigDecimal actual) {
        String percent = rate.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString();
        return label + "超出监管上限：适用比例 " + percent + "%（计费基数 " + scale(base).toPlainString()
                + " 元），上限金额 " + limit.toPlainString() + " 元，实际金额 " + scale(actual).toPlainString() + " 元";
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 预算汇总合计（budget_total / budget_balance）
     */
    public static class Totals {

        private final BigDecimal budgetTotal;
        private final BigDecimal balanceTotal;

        public Totals(BigDecimal budgetTotal, BigDecimal balanceTotal) {
            this.budgetTotal  = budgetTotal;
            this.balanceTotal = balanceTotal;
        }

        public BigDecimal getBudgetTotal() {
            return budgetTotal;
        }

        public BigDecimal getBalanceTotal() {
            return balanceTotal;
        }
    }
}
