package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.RdLaborAllocation;
import com.ruoyi.biz.domain.bo.RdAllocCalcBo;
import com.ruoyi.biz.domain.bo.RdAllocRevokeBo;
import com.ruoyi.biz.domain.vo.RdAllocDashboardVo;

import java.util.List;
import java.util.Map;

/**
 * 研发加计扣除 — 分摊 Service 接口（任务卡 Task 3 端点 11-15）
 *
 * <p>算法核心下沉到 {@code RdAllocSupport.calcCore}（纯函数 + 单测覆盖），本 Service 只做 DB 拉取 + 落库 + 状态机。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
public interface IRdAllocService {

    /**
     * 分摊计算（端点 11）— 严格按任务卡简报伪代码 1-11 步执行：scoped 闸门 → ARCHIVED 拒 → 取预算 →
     * 已 CONFIRMED 拒 → 已 DRAFT 软删重建 → 取成员/工资 → 调算法 → 落库 → 闭合断言。
     *
     * @return 落库后的批次行 + 汇总 + allocLastNegative 提示（key: rows / summary / allocLastNegative / msg）
     */
    Map<String, Object> calc(RdAllocCalcBo body, String operName);

    /**
     * 批次确认（端点 13）— 有 DRAFT 有效行才可确认；全部行 status→CONFIRMED + confirm_by/confirm_time；
     * 已 CONFIRMED 拒重复。
     */
    int confirm(RdAllocCalcBo body, String operName);

    /**
     * 撤销确认（端点 14）— reason 必填非空；CONFIRMED 行→DRAFT，confirm_by/time 清 null，
     * reason 追加写入行 remark（格式"[撤销确认 by X at time] reason"）。
     */
    int revoke(RdAllocRevokeBo body, String operName);

    /**
     * 批次明细列表（端点 12）— JOIN sys_user 带 researcherName；researcher 对他人行的 hourlyRate/monthlySalary 置 null。
     */
    List<RdLaborAllocation> list(Long projectId, String month);

    /**
     * 批次看板（端点 15）— B/Σ三值/人数/状态/闭合标志/无预算或无工时引导提示。
     */
    RdAllocDashboardVo dashboard(Long projectId, String month);
}