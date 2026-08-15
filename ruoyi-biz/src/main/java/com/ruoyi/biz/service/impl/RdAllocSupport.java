package com.ruoyi.biz.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分摊算法支撑（算法核心下沉纯函数组件，照 BudgetSupport 先例）
 *
 * <p>本组件只依赖 Jackson，不碰 DB。calc 端点的所有计算口径（成员排序 / 权重聚合 / 前 n-1 四舍五入 /
 * 最后一人闭合 / 逐项附加费闭合 / JSON 序列化 / 闭合断言）都在本组件内实现，Service 端只负责：
 * <ol>
 *   <li>拉预算、工资、工时、附加费比例（DB）</li>
 *   <li>调用 {@link #calcCore} 算出每行</li>
 *   <li>逐行 INSERT（DB）</li>
 * </ol>
 * </p>
 *
 * <p>算法严格按任务卡 §四 Task 3 简报伪代码实现：</p>
 * <ul>
 *   <li>步骤 6：w_i = h_i × monthly_salary_i（BigDecimal 原值乘，不先除 174）</li>
 *   <li>步骤 7：前 n-1 人 round2(B × w_i / Σw)；最后一人 alloc_last = B - Σ(前 n-1)</li>
 *   <li>步骤 8：每项 k 前 n-1 人 round2(alloc_i × rate_k)；最后一人 s_last_k = round2(B×rate_k) - Σ(前 n-1 s_ik)</li>
 *   <li>步骤 9：surcharge_detail 序列化为 JSON 字符串（toPlainString 避免科学计数）</li>
 *   <li>步骤 10：闭合断言（Σalloc==B、Σ_i s_ik==round2(B×rate_k)、Σgrand==Σalloc+Σsurcharge）</li>
 * </ul>
 *
 * @author kys
 * @date 2026-08-15
 */
public class RdAllocSupport {

    /** 时薪换算基数（任务卡 D9：月薪÷174） */
    public static final BigDecimal HOUR_DIVISOR = new BigDecimal("174");

    /** 单行 round2（HALF_UP） */
    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** 状态字面量（与字典 rd_alloc_status 一致） */
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_CONFIRMED = "CONFIRMED";

    /** 复用 Jackson 单例（线程安全） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ========================================================
    //  纯函数入口
    // ========================================================

    /**
     * 计算一人一行（不含 researcherName/DbId，DB 持久化由 Service 负责）。
     *
     * @param projectId 课题ID（仅用于 batchNo 拼接）
     * @param month     'YYYY-MM'（仅用于 batchNo 拼接）
     * @param budget    课题该月人工费预算总额 B（已 scale 2）
     * @param members   成员列表（researcherId / hours / monthlySalary）；调用前必须已按 researcherId 升序稳定排序
     * @param rates     附加费比例列表（rateCode / rateValue）；调用前必须已按 rateId 升序稳定排序
     * @return CalcResult（rows + summary + allocLastNegative 提示）
     */
    public static CalcResult calcCore(Long projectId, String month,
                                      BigDecimal budget, List<Member> members, List<Rate> rates) {
        if (budget == null || budget.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException("该课题该月未编制人工费预算");
        }
        if (members == null || members.isEmpty()) {
            // 空集 → 返回"无有效工时"，不产生行（Service 端用此标记返回前端带标志的 AjaxResult，非异常）
            return new CalcResult(Collections.emptyList(), scale(budget),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
        if (rates == null || rates.isEmpty()) {
            throw new ServiceException("附加费比例未配置");
        }

        // 步骤 6：w_i = h_i × monthly_salary_i；Σw
        BigDecimal sigmaW = BigDecimal.ZERO;
        for (Member m : members) {
            if (m == null || m.getHours() == null || m.getMonthlySalary() == null) {
                continue;
            }
            sigmaW = sigmaW.add(m.getHours().multiply(m.getMonthlySalary()));
        }
        if (sigmaW.compareTo(BigDecimal.ZERO) == 0) {
            return new CalcResult(Collections.emptyList(), scale(budget),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
        }

        int n = members.size();
        BigDecimal[] allocArr = new BigDecimal[n];
        BigDecimal sumAllocPrev = BigDecimal.ZERO;
        // 步骤 7：前 n-1 人 alloc_i = round2(B × w_i / Σw)
        for (int i = 0; i < n - 1; i++) {
            Member m = members.get(i);
            BigDecimal w = m.getHours().multiply(m.getMonthlySalary());
            BigDecimal alloc = scale(budget.multiply(w).divide(sigmaW, 10, ROUNDING));
            allocArr[i] = alloc;
            sumAllocPrev = sumAllocPrev.add(alloc);
        }
        // 步骤 7：最后一人 alloc_last = B - Σ(前 n-1)
        BigDecimal allocLast = scale(budget.subtract(sumAllocPrev));
        allocArr[n - 1] = allocLast;

        // 步骤 8：逐项附加费；二维数组 s[k][i]
        int kCount = rates.size();
        BigDecimal[][] surchargeArr = new BigDecimal[kCount][n];
        BigDecimal[] sumSurchargePrev = new BigDecimal[kCount];
        for (int k = 0; k < kCount; k++) {
            BigDecimal rateK = rates.get(k).getRateValue();
            BigDecimal totalK = scale(budget.multiply(rateK));
            BigDecimal sumKPrev = BigDecimal.ZERO;
            for (int i = 0; i < n - 1; i++) {
                BigDecimal sIK = scale(allocArr[i].multiply(rateK));
                surchargeArr[k][i] = sIK;
                sumKPrev = sumKPrev.add(sIK);
            }
            surchargeArr[k][n - 1] = scale(totalK.subtract(sumKPrev));
            sumSurchargePrev[k] = sumKPrev.add(surchargeArr[k][n - 1]);
        }

        // 步骤 9：组装每人一行
        List<Row> rows = new ArrayList<>(n);
        BigDecimal sumAlloc = BigDecimal.ZERO;
        BigDecimal sumSurcharge = BigDecimal.ZERO;
        BigDecimal sumGrand = BigDecimal.ZERO;
        boolean allocLastNegative = false;
        for (int i = 0; i < n; i++) {
            Member m = members.get(i);
            Row row = new Row();
            row.setResearcherId(m.getResearcherId());
            row.setMonthlyHours(scale(m.getHours()));
            row.setHourlyRate(scale(m.getMonthlySalary().divide(HOUR_DIVISOR, 10, ROUNDING)));
            row.setMonthlySalary(m.getMonthlySalary());
            row.setAllocatedAmount(allocArr[i]);

            // 附加费明细 JSON（rate_code 为键，金额 toPlainString）
            Map<String, String> detailMap = new LinkedHashMap<>(kCount);
            BigDecimal surchargeRow = BigDecimal.ZERO;
            for (int k = 0; k < kCount; k++) {
                Rate rk = rates.get(k);
                BigDecimal amount = surchargeArr[k][i];
                detailMap.put(rk.getRateCode(), amount.toPlainString());
                surchargeRow = surchargeRow.add(amount);
            }
            row.setSurchargeTotal(surchargeRow);
            row.setSurchargeDetailJson(serializeDetail(detailMap));
            row.setGrandTotal(scale(allocArr[i].add(surchargeRow)));

            sumAlloc = sumAlloc.add(allocArr[i]);
            sumSurcharge = sumSurcharge.add(surchargeRow);
            sumGrand = sumGrand.add(row.getGrandTotal());
            if (i == n - 1 && allocArr[i].compareTo(BigDecimal.ZERO) < 0) {
                allocLastNegative = true;
            }
            rows.add(row);
        }

        // 步骤 10：闭合断言
        if (sumAlloc.compareTo(scale(budget)) != 0) {
            throw new ServiceException("分摊闭合失败：Σalloc(" + sumAlloc.toPlainString()
                    + ") ≠ B(" + scale(budget).toPlainString() + ")");
        }
        for (int k = 0; k < kCount; k++) {
            BigDecimal totalK = scale(budget.multiply(rates.get(k).getRateValue()));
            if (sumSurchargePrev[k].compareTo(totalK) != 0) {
                throw new ServiceException("附加费闭合失败：项 " + rates.get(k).getRateCode()
                        + " Σ(" + sumSurchargePrev[k].toPlainString()
                        + ") ≠ round2(B×rate)(" + totalK.toPlainString() + ")");
            }
        }
        if (sumGrand.compareTo(sumAlloc.add(sumSurcharge)) != 0) {
            throw new ServiceException("总计闭合失败：Σgrand(" + sumGrand.toPlainString()
                    + ") ≠ Σalloc+Σsurcharge(" + sumAlloc.add(sumSurcharge).toPlainString() + ")");
        }

        return new CalcResult(rows, scale(budget), scale(sumAlloc), scale(sumSurcharge),
                scale(sumGrand), allocLastNegative);
    }

    // ========================================================
    //  辅助：单金额 scale、JSON 序列化
    // ========================================================

    /** BigDecimal 2 位 HALF_UP（null→ZERO） */
    public static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(SCALE, ROUNDING);
    }

    /** 序列化附加费明细 JSON；序列化为 LinkedHashMap 保证 10 项有序与 rateId 升序一致 */
    public static String serializeDetail(Map<String, String> detail) {
        try {
            return MAPPER.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new ServiceException("附加费明细 JSON 序列化失败：" + e.getMessage());
        }
    }

    // ========================================================
    //  入参/出参 DTO
    // ========================================================

    /**
     * 单个成员入参（researcherId / hours / monthlySalary）。
     * 调用方须按 researcherId 升序稳定排序后再传入。
     */
    public static class Member {
        private final Long researcherId;
        private final BigDecimal hours;
        private final BigDecimal monthlySalary;

        public Member(Long researcherId, BigDecimal hours, BigDecimal monthlySalary) {
            this.researcherId = researcherId;
            this.hours = hours;
            this.monthlySalary = monthlySalary;
        }

        public Long getResearcherId() { return researcherId; }
        public BigDecimal getHours() { return hours; }
        public BigDecimal getMonthlySalary() { return monthlySalary; }
    }

    /**
     * 单项附加费比例入参（rateCode / rateValue）。
     * 调用方须按 rateId 升序稳定排序后再传入。
     */
    public static class Rate {
        private final String rateCode;
        private final BigDecimal rateValue;

        public Rate(String rateCode, BigDecimal rateValue) {
            this.rateCode = rateCode;
            this.rateValue = rateValue;
        }

        public String getRateCode() { return rateCode; }
        public BigDecimal getRateValue() { return rateValue; }
    }

    /**
     * 单行计算结果（不含 researcherName/DbId；Service 端负责补齐 + 落库）。
     */
    public static class Row {
        private Long researcherId;
        private BigDecimal monthlyHours;
        private BigDecimal hourlyRate;
        private BigDecimal monthlySalary;
        private BigDecimal allocatedAmount;
        private BigDecimal surchargeTotal;
        private BigDecimal grandTotal;
        private String surchargeDetailJson;

        public Long getResearcherId() { return researcherId; }
        public void setResearcherId(Long researcherId) { this.researcherId = researcherId; }
        public BigDecimal getMonthlyHours() { return monthlyHours; }
        public void setMonthlyHours(BigDecimal monthlyHours) { this.monthlyHours = monthlyHours; }
        public BigDecimal getHourlyRate() { return hourlyRate; }
        public void setHourlyRate(BigDecimal hourlyRate) { this.hourlyRate = hourlyRate; }
        public BigDecimal getMonthlySalary() { return monthlySalary; }
        public void setMonthlySalary(BigDecimal monthlySalary) { this.monthlySalary = monthlySalary; }
        public BigDecimal getAllocatedAmount() { return allocatedAmount; }
        public void setAllocatedAmount(BigDecimal allocatedAmount) { this.allocatedAmount = allocatedAmount; }
        public BigDecimal getSurchargeTotal() { return surchargeTotal; }
        public void setSurchargeTotal(BigDecimal surchargeTotal) { this.surchargeTotal = surchargeTotal; }
        public BigDecimal getGrandTotal() { return grandTotal; }
        public void setGrandTotal(BigDecimal grandTotal) { this.grandTotal = grandTotal; }
        public String getSurchargeDetailJson() { return surchargeDetailJson; }
        public void setSurchargeDetailJson(String surchargeDetailJson) { this.surchargeDetailJson = surchargeDetailJson; }
    }

    /**
     * 计算结果（含每行 + 汇总 + alloc_last 负尾差提示）。
     */
    public static class CalcResult {
        /** 每人一行（顺序：researcherId 升序） */
        private final List<Row> rows;
        /** 预算 B */
        private final BigDecimal budget;
        /** Σalloc（应 == B） */
        private final BigDecimal sumAlloc;
        /** Σsurcharge */
        private final BigDecimal sumSurcharge;
        /** Σgrand */
        private final BigDecimal sumGrand;
        /** alloc_last < 0 提示（极小权重场景；不阻断计算） */
        private final boolean allocLastNegative;

        public CalcResult(List<Row> rows, BigDecimal budget, BigDecimal sumAlloc,
                          BigDecimal sumSurcharge, BigDecimal sumGrand, boolean allocLastNegative) {
            this.rows = rows;
            this.budget = budget;
            this.sumAlloc = sumAlloc;
            this.sumSurcharge = sumSurcharge;
            this.sumGrand = sumGrand;
            this.allocLastNegative = allocLastNegative;
        }

        public List<Row> getRows() { return rows; }
        public BigDecimal getBudget() { return budget; }
        public BigDecimal getSumAlloc() { return sumAlloc; }
        public BigDecimal getSumSurcharge() { return sumSurcharge; }
        public BigDecimal getSumGrand() { return sumGrand; }
        public boolean isAllocLastNegative() { return allocLastNegative; }
        public boolean isEmpty() { return rows == null || rows.isEmpty(); }
    }
}