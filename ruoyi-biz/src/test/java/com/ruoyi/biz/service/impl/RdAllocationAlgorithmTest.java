package com.ruoyi.biz.service.impl;

import com.ruoyi.biz.service.impl.RdAllocSupport.CalcResult;
import com.ruoyi.biz.service.impl.RdAllocSupport.Member;
import com.ruoyi.biz.service.impl.RdAllocSupport.Rate;
import com.ruoyi.biz.service.impl.RdAllocSupport.Row;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分摊算法核心纯函数单测（任务卡 §四 Task 3 简报 7 场景）。
 *
 * <p>组件本身不依赖 Spring，直接 {@code new} + 静态方法调用即可。</p>
 *
 * <p>闭合断言必查项（每个非空场景都要验证）：
 * <ul>
 *   <li>Σalloc.compareTo(B) == 0</li>
 *   <li>每项 Σ_i s_ik == round2(B × rate_k)</li>
 *   <li>Σgrand == Σalloc + Σsurcharge</li>
 *   <li>每行 allocatedAmount = round2(B × w_i / Σw)（最后一行除外，走"兜底差"）</li>
 * </ul>
 * </p>
 *
 * @author kys
 * @date 2026-08-15
 */
class RdAllocationAlgorithmTest {

    /** V1.0.1 surcharge_rate 10 项 ACTIVE 真实值（任务卡 D9 与 sql/kys/V1.0.1__dict_data.sql:203-213 一致） */
    private static final List<Rate> RATES_V101 = orderedRates(
            new BigDecimal("0.0150"),  // edu
            new BigDecimal("0.0200"),  // union
            new BigDecimal("0.0800"),  // med
            new BigDecimal("0.0200"),  // med_sup
            new BigDecimal("0.1600"),  // pension
            new BigDecimal("0.0700"),  // annuity
            new BigDecimal("0.0070"),  // unemploy
            new BigDecimal("0.0036"),  // injury
            new BigDecimal("0.1200"),  // housing_fund
            new BigDecimal("0.0030")   // relief
    );

    /** Σ rate = 0.4986（任务卡 SQL 基线 §四 Task 3 简报场景 6 引用） */
    private static final BigDecimal SUM_RATES = new BigDecimal("0.4986");

    /**
     * 场景 1：3 人 / 30000 元 — Σalloc 精确；逐项 Σs_k 精确；Σgrand 精确
     */
    @Test
    @DisplayName("场景1：3人/30000(h=[100,80,60],salary=[10000,8000,6000]) Σalloc精确 + 逐项闭合 + grand闭合")
    void test_scenario1_threePersons_30000_exactClose() {
        List<Member> members = orderedMembers(
                new Member(101L, new BigDecimal("100"), new BigDecimal("10000")),
                new Member(102L, new BigDecimal("80"),  new BigDecimal("8000")),
                new Member(103L, new BigDecimal("60"),  new BigDecimal("6000"))
        );
        // w_i = h_i × s_i = 100×10000 / 80×8000 / 60×6000 = 1000000 / 640000 / 360000
        // Σw = 2000000
        // alloc_1 = 30000×1000000/2000000 = 15000.00
        // alloc_2 = 30000× 640000/2000000 =  9600.00
        // alloc_3 = 30000 - 15000 - 9600   =  5400.00
        BigDecimal budget = new BigDecimal("30000");

        CalcResult r = RdAllocSupport.calcCore(1L, "2026-08", budget, members, RATES_V101);

        assertEquals(0, r.getBudget().compareTo(budget), "B 透传");
        assertNotNull(r.getRows());
        assertEquals(3, r.getRows().size(), "3 行");
        assertEquals(0, r.getSumAlloc().compareTo(budget), "Σalloc == B");
        // 行校验
        Row r1 = r.getRows().get(0);
        Row r2 = r.getRows().get(1);
        Row r3 = r.getRows().get(2);
        assertEquals(0, r1.getAllocatedAmount().compareTo(new BigDecimal("15000.00")), "前 1 行 alloc");
        assertEquals(0, r2.getAllocatedAmount().compareTo(new BigDecimal("9600.00")),  "前 2 行 alloc");
        assertEquals(0, r3.getAllocatedAmount().compareTo(new BigDecimal("5400.00")),  "末行 alloc（B-Σ前）");
        // 校验 6：行内 grand = alloc + surcharge_total
        for (Row row : r.getRows()) {
            assertEquals(0, row.getGrandTotal().compareTo(
                    row.getAllocatedAmount().add(row.getSurchargeTotal())), "行 grand = alloc + surcharge");
        }
        // 校验 Σgrand = Σalloc + Σsurcharge
        assertEquals(0, r.getSumGrand().compareTo(r.getSumAlloc().add(r.getSumSurcharge())), "Σgrand 闭合");
        // 逐项附加费闭合
        for (Rate rate : RATES_V101) {
            BigDecimal sumK = BigDecimal.ZERO;
            for (Row row : r.getRows()) {
                sumK = sumK.add(row.getSurchargeTotal());  // 各行 surcharge_total 之和 == 每项 row 的对应值之和，
                                                          // 这里改用更精确的逐项校验
            }
        }
        // 严格逐项校验：解析每行 surchargeDetailJson 后按 rate_code 求和
        for (Rate rate : RATES_V101) {
            BigDecimal sumK = BigDecimal.ZERO;
            for (Row row : r.getRows()) {
                BigDecimal v = readSurchargeDetail(row.getSurchargeDetailJson(), rate.getRateCode());
                sumK = sumK.add(v);
            }
            BigDecimal expect = RdAllocSupport.scale(budget.multiply(rate.getRateValue()));
            assertEquals(0, sumK.compareTo(expect), "项 " + rate.getRateCode() + " Σ精确闭合");
        }
    }

    /**
     * 场景 2：尾差兜底 — 3 人等权 B=100 → 33.33 / 33.33 / 33.34
     */
    @Test
    @DisplayName("场景2：3人等权 B=100 尾差兜底 [33.33/33.33/33.34]")
    void test_scenario2_equalWeight_roundingTail() {
        List<Member> members = orderedMembers(
                new Member(101L, new BigDecimal("1"), new BigDecimal("1")),
                new Member(102L, new BigDecimal("1"), new BigDecimal("1")),
                new Member(103L, new BigDecimal("1"), new BigDecimal("1"))
        );
        BigDecimal budget = new BigDecimal("100");
        // B/3 = 33.3333... → round2 = 33.33
        // 末行 = 100 - 33.33 - 33.33 = 33.34

        CalcResult r = RdAllocSupport.calcCore(1L, "2026-08", budget, members, RATES_V101);

        assertEquals(0, r.getSumAlloc().compareTo(budget), "Σalloc == B（尾差兜底）");
        assertEquals(3, r.getRows().size(), "3 行");
        assertEquals(0, r.getRows().get(0).getAllocatedAmount().compareTo(new BigDecimal("33.33")), "行1 alloc=33.33");
        assertEquals(0, r.getRows().get(1).getAllocatedAmount().compareTo(new BigDecimal("33.33")), "行2 alloc=33.33");
        assertEquals(0, r.getRows().get(2).getAllocatedAmount().compareTo(new BigDecimal("33.34")), "行3 alloc=33.34");
        // 闭合：每项 Σs_k == round2(100 × rate_k)
        for (Rate rate : RATES_V101) {
            BigDecimal sumK = BigDecimal.ZERO;
            for (Row row : r.getRows()) {
                sumK = sumK.add(readSurchargeDetail(row.getSurchargeDetailJson(), rate.getRateCode()));
            }
            BigDecimal expect = RdAllocSupport.scale(budget.multiply(rate.getRateValue()));
            assertEquals(0, sumK.compareTo(expect), "项 " + rate.getRateCode() + " Σ精确闭合");
        }
    }

    /**
     * 场景 3：Σw = 0 → 空结果（"无有效工时"）
     */
    @Test
    @DisplayName("场景3：Σw=0 返回空结果（非异常）")
    void test_scenario3_sigmaW_zero_emptyResult() {
        // 工时=0 但成员行存在（→ Σw=0 走步骤 6 短路返回）
        List<Member> members = orderedMembers(
                new Member(101L, BigDecimal.ZERO, new BigDecimal("10000")),
                new Member(102L, BigDecimal.ZERO, new BigDecimal("8000"))
        );
        BigDecimal budget = new BigDecimal("10000");

        CalcResult r = RdAllocSupport.calcCore(1L, "2026-08", budget, members, RATES_V101);

        assertTrue(r.isEmpty(), "Σw=0 → 返回空 rows");
        assertEquals(0, r.getSumAlloc().compareTo(BigDecimal.ZERO), "Σalloc=0");
        assertEquals(0, r.getSumSurcharge().compareTo(BigDecimal.ZERO), "Σsurcharge=0");
        assertEquals(0, r.getSumGrand().compareTo(BigDecimal.ZERO), "Σgrand=0");
        assertFalse(r.isAllocLastNegative(), "无负尾差");
    }

    /**
     * 场景 4：单人 → 全额 + 全部附加费精确闭合
     */
    @Test
    @DisplayName("场景4：单人 B=10000 全额 + 全部附加费精确闭合")
    void test_scenario4_singlePerson_fullBudget() {
        List<Member> members = orderedMembers(
                new Member(101L, new BigDecimal("100"), new BigDecimal("10000"))
        );
        BigDecimal budget = new BigDecimal("10000");

        CalcResult r = RdAllocSupport.calcCore(1L, "2026-08", budget, members, RATES_V101);

        assertEquals(1, r.getRows().size(), "1 行");
        Row row = r.getRows().get(0);
        assertEquals(0, row.getAllocatedAmount().compareTo(budget), "单人 alloc=B");
        // Σ附加费 == round2(B × Σrate) = round2(10000 × 0.4986) = 4986.00
        BigDecimal expectSumSurcharge = RdAllocSupport.scale(budget.multiply(SUM_RATES));
        assertEquals(0, r.getSumSurcharge().compareTo(expectSumSurcharge), "Σsurcharge = round2(B×0.4986)");
        assertEquals(0, r.getSumAlloc().compareTo(budget), "Σalloc=B");
        assertEquals(0, r.getSumGrand().compareTo(budget.add(expectSumSurcharge)), "Σgrand = B + Σsurcharge");
        // 逐项校验
        for (Rate rate : RATES_V101) {
            BigDecimal sumK = BigDecimal.ZERO;
            for (Row ro : r.getRows()) {
                sumK = sumK.add(readSurchargeDetail(ro.getSurchargeDetailJson(), rate.getRateCode()));
            }
            BigDecimal expect = RdAllocSupport.scale(budget.multiply(rate.getRateValue()));
            assertEquals(0, sumK.compareTo(expect), "项 " + rate.getRateCode() + " Σ精确闭合");
        }
    }

    /**
     * 场景 5：负尾差 — 最后一人权重极小，alloc_last < 0 且 Σalloc 仍闭合
     */
    @Test
    @DisplayName("场景5：负尾差 alloc_last<0 但 Σalloc 仍精确闭合")
    void test_scenario5_negativeTail_remainderAlloc() {
        // h=[1000, 1] → w=[10000, 10] → Σw=10010
        // alloc_1 = round2(B × 10000/10010) — 取 B=100 时 alloc_1 = round2(99.90) = 99.90
        // alloc_2 = 100 - 99.90 = 0.10（正）
        // 取更极端 B=100 时让最后权重极小：构造 w=[9999, 1], Σw=10000, B=10000
        // alloc_1 = round2(10000 × 9999/10000) = round2(9999.0000... 略小于 9999 → setScale 10 后 9999.0000) = 9999.00
        // alloc_2 = 10000 - 9999.00 = 1.00
        // 想要负尾差：alloc_1 < B 仍 round2 向上，alloc_2 = B - alloc_1 仍 ≥ 0
        // 真正能触发 alloc_2 < 0 的场景是 alloc_1 已经被 round2 顶到 > B（如 B=100, w=[99.99, 0.01]）：
        //   alloc_1 = round2(100 × 99.99/100) = round2(99.99) = 99.99；alloc_2 = 100 - 99.99 = 0.01（仍 ≥0）
        //   再极端 w=[99, 1]，Σw=100，B=100，alloc_1 = round2(99) = 99.00，alloc_2 = 1.00
        //   让 alloc_1 因为 HALF_UP 上抬到超过 B：构造 w=[0.005, 0.005], Σw=0.01, B=100
        //   alloc_1 = round2(100 × 0.005/0.01) = round2(50) = 50.00；alloc_2 = 100 - 50.00 = 50.00
        // 触发负尾差最直接：B=100, w=[0.001, 0.001], Σw=0.002
        //   alloc_1 = round2(100 × 0.001/0.002) = round2(50) = 50.00；alloc_2 = 50.00（仍非负）
        // 真正触发：让 alloc_1 = round2(B × w_1 / Σw) 在舍入后超过 B（首人 w 略大于 Σw/2 时不会发生）
        // 改方案：构造 w=[1.0001, 0.0001], Σw=1.0002, B=100
        //   alloc_1 = round2(100 × 1.0001/1.0002) = round2(99.9900...) = 99.99
        //   alloc_2 = 100 - 99.99 = 0.01（仍非负）
        // 改方案：w_1 极接近 Σw（如 w_1 = Σw - ε），B × w_1/Σw 极接近 B，round2 后可能等于 B，alloc_2=0
        // 负尾差必须 alloc_1 > B：round2 后顶到 > B 才可能。
        // round2 HALF_UP 后最大溢出 0.005；要让 alloc_1 > B 需要 B × w_1/Σw ∈ (B, B+0.005]。
        // 构造：w=[100.01, 0.001], Σw=100.011, B=100
        //   alloc_1 = round2(100 × 100.01/100.011) = round2(99.9900...）= 99.99
        //   alloc_2 = 100 - 99.99 = 0.01（仍非负）
        // 再次换思路：要让 alloc_2 < 0 必须 alloc_1 > B。考虑 w_1 权重极大（>Σw/2），round2 后 alloc_1 = B - ε 仍 < B；
        // HALF_UP 舍入最多 +0.005，所以 alloc_1 > B 仅当 B × w_1/Σw ∈ (B, B+0.005]。
        // 设 w_1/Σw = 1 + δ（δ 极小），B × (1+δ) = B + Bδ。Bδ ≤ 0.005 → δ ≤ 0.005/B。
        // B=100：δ ≤ 0.00005；w_1 = Σw × (1+0.00005)，w_2 = Σw × (1 - (1+0.00005)) 需 ≥ 0 → Σw ≥ w_1/(1+0.00005) ≈ w_1。
        // 构造：Σw = 10000, w_1 = 10000.0001, w_2 = -0.0001（负值非法）
        // 算法要求 w_i > 0（h_i > 0 && salary > 0）。负尾差实际由极端权重（如 B=100, w=[99.99, 0.01]）
        // 在 round2(99.99) = 99.99 后 alloc_2 = 0.01（≥0）；而 B×99.99/100 = 99.99，round2=99.99，不上抬。
        // 唯一可能触发：multi-person 场景下前 n-1 人累计 round2 顶过 B。
        // 构造：4 人等权 B=10 → w_i/Σw=0.25，alloc_i = round2(10×0.25) = round2(2.5) = 2.50；
        // Σ前 3 = 7.50，alloc_4 = 10-7.50 = 2.50（无负尾差）。
        // 构造：6 人等权 B=10 → alloc_i = round2(10/6) = round2(1.6666) = 1.67；
        // Σ前 5 = 8.35，alloc_6 = 10-8.35 = 1.65（无负）。
        // 构造：10 人等权 B=10 → alloc_i = round2(1) = 1.00，Σ前 9 = 9.00，alloc_10 = 1.00。
        // 构造：11 人等权 B=10 → alloc_i = round2(10/11) = round2(0.909090...) = 0.91；
        // Σ前 10 = 9.10，alloc_11 = 10-9.10 = 0.90（仍 ≥0）。
        // 真实触发：B=10, 11 人等权，但若首行 round2=0.91 后累加，前 9 = 8.19（实际是 10 人 0.91），
        // 计算精确：10 × 10/11 = 9.0909...；round2(0.90909...) = 0.91；前 10 = 10×0.91 = 9.10；末 = 0.90。
        // 9.0909/11 = 0.8264 → round2(0.8264...) = 0.83 → 前 10 = 8.30 → 末 = 1.70；
        // 以上均无法触发 alloc_2 < 0，因为 round2 HALF_UP 至多 +0.005/人，11 人累计 +0.055 也只是抵消。
        // 要让末行负，必须前 n-1 行 round2 后累计 > B。构造：B=100, 101 人等权 w_i=1。
        // alloc_i = round2(100/101) = round2(0.99009900...) = 0.99；前 100 = 99.00；末 = 1.00（仍非负）。
        // 改：B=100, 1000 人等权 w_i=1 → alloc_i = round2(0.1) = 0.10；前 999 = 99.90；末 = 0.10。
        // 改：B=100, 10000 人等权 → alloc_i = round2(0.01) = 0.01；前 9999 = 99.99；末 = 0.01。
        // 改：B=100, 100001 人等权 → alloc_i = round2(100/100001) = round2(0.000999...) = 0.00（截断到 2 位=0.00）；
        // 前 100000 = 0.00；末 = 100.00（但每人都发了 0）。
        // 真正能触发 alloc_2<0：B=100, 21 人等权 → alloc_i = round2(100/21) = round2(4.7619...) = 4.76；
        // 前 20 = 95.20；末 = 4.80（非负）。
        // 最后策略：B=100, w=[50, 1, 1] → Σw=52；
        //   alloc_1 = round2(100×50/52) = round2(96.1538...) = 96.15；
        //   alloc_2 = round2(100×1/52)   = round2(1.9230...)  = 1.92；
        //   alloc_3 = 100 - 96.15 - 1.92 = 1.93（非负）。
        // 改：B=100, w=[100, 1, 1] → Σw=102；
        //   alloc_1 = round2(100×100/102) = round2(98.0392...) = 98.04；
        //   alloc_2 = round2(100×1/102)   = round2(0.9803...)  = 0.98；
        //   alloc_3 = 100 - 98.04 - 0.98 = 0.98（非负）。
        // 改：B=100, w=[1000, 1] → Σw=1001；
        //   alloc_1 = round2(100×1000/1001) = round2(99.9001...) = 99.90；
        //   alloc_2 = 100 - 99.90 = 0.10（非负）。
        // 改：B=100, w=[100000, 1] → Σw=100001；
        //   alloc_1 = round2(100×100000/100001) = round2(99.9990...) = 100.00（HALF_UP 上抬）；
        //   alloc_2 = 100 - 100.00 = 0.00（非负但首行 alloc=B）。
        // 改：B=100, w=[200000, 1] → Σw=200001；
        //   alloc_1 = round2(100×200000/200001) = round2(99.9995...) = 100.00（HALF_UP 上抬，因 .9995 进位）；
        //   alloc_2 = 100 - 100.00 = 0.00。
        // 改：B=100, w=[300000, 1] → Σw=300001；
        //   alloc_1 = round2(100×300000/300001) = round2(99.99966...) = 100.00（HALF_UP 上抬）；
        //   alloc_2 = 100 - 100.00 = 0.00。
        // 不可能 alloc_1 > B？因为 round2(99.999...) 在 setScale(2, HALF_UP) 下，99.999 → 100.00。
        // 而 alloc_1 上限 = round2(B × w_1/Σw) ≤ B 仅当 w_1 ≤ Σw；w_1 接近 Σw 时
        // B×w_1/Σw 接近 B，但 B 整数倍时 round2(B × 0.9999...) < B 仍是；只有当 B×w_1/Σw > B 才上抬超过 B。
        // 例：B=100, Σw=2, w_1=1.00001 → B×w_1/Σw = 50.00025 → round2 = 50.00 < B。
        // 唯一触发：首行 w_1 > Σw/2 且 B×w_1/Σw 进位后 = B+0.00X；
        //   例：B=100, w_1=1000, w_2=1 → Σw=1001, B×1000/1001 = 99.9000...，round2 = 99.90 < 100；
        //   若 w_1=1010, w_2=1 → Σw=1011, B×1010/1011 = 99.90108..., round2 = 99.90；
        //   w_1=10000, w_2=1 → Σw=10001, B×10000/10001 = 99.9900..., round2 = 99.99 < 100。
        // 实际上 round2 后 alloc_1 ≤ B 永真（w_1 ≤ Σw 时 B×w_1/Σw ≤ B，round2 不外溢到 > B）。
        // 所以单次 round2 上抬至 B+0.005 仅当 B×w_1/Σw ∈ (B, B+0.005]，但 w_1 ≤ Σw 时该项 ≤ B。
        // 因此单 round2 不会让 alloc_1 > B。
        // 累计效应：前 n-1 人各自 round2 上抬 0.005 累计 (n-1)×0.005，若 (n-1)×0.005 > 0.005（即 n≥2）就可能顶过 B。
        // 构造：B=100, 3 人等权 w_i=1 → alloc_i = round2(33.333...) = 33.33；前 2 = 66.66；末 = 33.34（非负）。
        // 构造：B=100, 201 人等权 → alloc_i = round2(100/201) = round2(0.4975...) = 0.50；
        //   前 200 = 200×0.50 = 100.00；末 = 0.00（非负但等于0）。
        // 构造：B=100, 202 人等权 → alloc_i = round2(100/202) = round2(0.4950495...) = 0.50；
        //   前 201 = 201×0.50 = 100.50；末 = -0.50（负尾差！）
        // 构造：B=100, w_i = 1 (i=1..202), Σw=202, alloc_i = round2(100/202) = round2(0.4950495) = 0.50
        List<Member> members = new ArrayList<>(202);
        for (long i = 1; i <= 202; i++) {
            members.add(new Member(i, BigDecimal.ONE, BigDecimal.ONE));
        }
        // 按 researcherId 升序（已按 i 升序构造）
        BigDecimal budget = new BigDecimal("100");

        CalcResult r = RdAllocSupport.calcCore(1L, "2026-08", budget, members, RATES_V101);

        assertEquals(0, r.getSumAlloc().compareTo(budget), "Σalloc == B（负尾差仍闭合）");
        assertTrue(r.isAllocLastNegative(), "末行 alloc < 0");
        assertTrue(r.getRows().get(201).getAllocatedAmount().compareTo(BigDecimal.ZERO) < 0, "末行 alloc 实际为负");
        // 闭合：每项 Σs_k == round2(B × rate_k)
        for (Rate rate : RATES_V101) {
            BigDecimal sumK = BigDecimal.ZERO;
            for (Row row : r.getRows()) {
                sumK = sumK.add(readSurchargeDetail(row.getSurchargeDetailJson(), rate.getRateCode()));
            }
            BigDecimal expect = RdAllocSupport.scale(budget.multiply(rate.getRateValue()));
            assertEquals(0, sumK.compareTo(expect), "项 " + rate.getRateCode() + " Σ精确闭合（负尾差）");
        }
    }

    /**
     * 场景 6：逐项闭合优先于总率 — Σsurcharge 与 B×0.4986 允许分位差，逐项必须精确
     */
    @Test
    @DisplayName("场景6：Σsurcharge 与 B×0.4986 分位差可接受，逐项必须精确")
    void test_scenario6_perItemClosureOverTotalRate() {
        // B=30000, 3 人等权 w=[1,1,1], h=s=1 → Σw=3
        // alloc_i = round2(30000/3) = round2(10000) = 10000；前 2 = 20000；末 = 10000
        // 每项 Σ_i s_ik = round2(B × rate_k) 精确
        // Σsurcharge = Σ_k round2(B × rate_k) — 10 项各自 round2 后求和，与 B × Σrate = B × 0.4986 可能有 ±0.01 量级分位差
        List<Member> members = orderedMembers(
                new Member(101L, new BigDecimal("1"), new BigDecimal("1")),
                new Member(102L, new BigDecimal("1"), new BigDecimal("1")),
                new Member(103L, new BigDecimal("1"), new BigDecimal("1"))
        );
        BigDecimal budget = new BigDecimal("30000");

        CalcResult r = RdAllocSupport.calcCore(1L, "2026-08", budget, members, RATES_V101);

        // 逐项精确闭合
        for (Rate rate : RATES_V101) {
            BigDecimal sumK = BigDecimal.ZERO;
            for (Row row : r.getRows()) {
                sumK = sumK.add(readSurchargeDetail(row.getSurchargeDetailJson(), rate.getRateCode()));
            }
            BigDecimal expect = RdAllocSupport.scale(budget.multiply(rate.getRateValue()));
            assertEquals(0, sumK.compareTo(expect), "项 " + rate.getRateCode() + " 精确闭合");
        }
        // Σsurcharge 与 B×Σrate 的差一定 ≤ 0.10（10 项各 ±0.005）
        BigDecimal directSum = r.getSumSurcharge();
        BigDecimal refSum = budget.multiply(SUM_RATES);
        BigDecimal diff = directSum.subtract(refSum).abs();
        assertTrue(diff.compareTo(new BigDecimal("0.10")) <= 0,
                "分位差 ≤ 0.10（实际：" + diff.toPlainString() + "），逐项闭合优先于总率");
    }

    /**
     * 场景 7：V1.0.1 真实 10 项比例下的标准场景 — 4 人
     */
    @Test
    @DisplayName("场景7：V1.0.1 真实 10 项比例下 4 人标准分摊")
    void test_scenario7_v101_realRates_4persons() {
        // B=20000, 4 人 w=[100×5000=500000, 80×6000=480000, 60×8000=480000, 40×10000=400000]
        // Σw = 500000+480000+480000+400000 = 1860000
        // alloc_1 = round2(20000 × 500000/1860000) = round2(5376.344...) = 5376.34
        // alloc_2 = round2(20000 × 480000/1860000) = round2(5161.290...) = 5161.29
        // alloc_3 = round2(20000 × 480000/1860000) = 5161.29（同上）
        // alloc_4 = 20000 - 5376.34 - 5161.29 - 5161.29 = 4301.08
        List<Member> members = orderedMembers(
                new Member(101L, new BigDecimal("100"), new BigDecimal("5000")),
                new Member(102L, new BigDecimal("80"),  new BigDecimal("6000")),
                new Member(103L, new BigDecimal("60"),  new BigDecimal("8000")),
                new Member(104L, new BigDecimal("40"),  new BigDecimal("10000"))
        );
        BigDecimal budget = new BigDecimal("20000");

        CalcResult r = RdAllocSupport.calcCore(1L, "2026-08", budget, members, RATES_V101);

        assertEquals(0, r.getSumAlloc().compareTo(budget), "Σalloc == B");
        assertEquals(4, r.getRows().size(), "4 行");
        assertEquals(0, r.getRows().get(3).getAllocatedAmount().compareTo(
                new BigDecimal("4301.08")), "末行 alloc = B - Σ前");
        // Σgrand 闭合
        assertEquals(0, r.getSumGrand().compareTo(r.getSumAlloc().add(r.getSumSurcharge())), "Σgrand 闭合");
        // 逐项精确
        for (Rate rate : RATES_V101) {
            BigDecimal sumK = BigDecimal.ZERO;
            for (Row row : r.getRows()) {
                sumK = sumK.add(readSurchargeDetail(row.getSurchargeDetailJson(), rate.getRateCode()));
            }
            BigDecimal expect = RdAllocSupport.scale(budget.multiply(rate.getRateValue()));
            assertEquals(0, sumK.compareTo(expect), "项 " + rate.getRateCode() + " 精确闭合");
        }
        // 行内 grand = alloc + surcharge
        for (Row row : r.getRows()) {
            assertEquals(0, row.getGrandTotal().compareTo(
                    row.getAllocatedAmount().add(row.getSurchargeTotal())), "行 grand = alloc + surcharge");
        }
    }

    /**
     * 边界：B<=0 抛 ServiceException
     */
    @Test
    @DisplayName("边界：预算 B<=0 抛 ServiceException")
    void test_boundary_zeroBudget_throws() {
        List<Member> members = Collections.singletonList(new Member(101L, BigDecimal.ONE, BigDecimal.ONE));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> RdAllocSupport.calcCore(1L, "2026-08", BigDecimal.ZERO, members, RATES_V101));
        assertTrue(ex.getMessage().contains("未编制人工费预算"), "错误信息");
    }

    /**
     * 边界：空成员列表返回空结果（非异常）
     */
    @Test
    @DisplayName("边界：空成员列表返回空结果")
    void test_boundary_emptyMembers_returnsEmpty() {
        CalcResult r = RdAllocSupport.calcCore(1L, "2026-08", new BigDecimal("10000"),
                Collections.emptyList(), RATES_V101);
        assertTrue(r.isEmpty(), "空成员 → 空结果");
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /** 构造 rateCode 升序的 Rate 列表（rateId 升序：edu/union/med/.../relief） */
    private static List<Rate> orderedRates(BigDecimal... values) {
        List<Rate> list = new ArrayList<>(values.length);
        String[] codes = {"edu", "union", "med", "med_sup", "pension",
                          "annuity", "unemploy", "injury", "housing_fund", "relief"};
        for (int i = 0; i < values.length && i < codes.length; i++) {
            list.add(new Rate(codes[i], values[i]));
        }
        return list;
    }

    /** 构造 researcherId 升序的 Member 列表（稳定序，"最后一人"由此确定） */
    private static List<Member> orderedMembers(Member... ms) {
        List<Member> list = new ArrayList<>(Arrays.asList(ms));
        list.sort((a, b) -> Long.compare(a.getResearcherId(), b.getResearcherId()));
        return list;
    }

    /** 从 surchargeDetail JSON 字符串读取指定 rate_code 的金额（纯字符串解析，无需 Jackson） */
    private static BigDecimal readSurchargeDetail(String json, String rateCode) {
        if (json == null) {
            return BigDecimal.ZERO;
        }
        // 简单字符串查找（rateCode 是 ASCII 字符串，无转义）；JSON 格式 {"edu":"540.00",...}
        String key = "\"" + rateCode + "\":\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return BigDecimal.ZERO;
        }
        int start = idx + key.length();  // 跳过 "\"edu\":\""
        int end = json.indexOf('"', start);  // 找到字符串值结尾的引号
        if (end < 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(json.substring(start, end));
    }
}