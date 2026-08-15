package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.RdWorktimeMonthly;
import com.ruoyi.biz.domain.bo.RdWorktimeCalendarVo;
import com.ruoyi.biz.domain.bo.RdWorktimeCopyVo;
import com.ruoyi.biz.domain.bo.RdWorktimeSaveBo;

import java.util.List;

/**
 * 研发加计扣除 — 工时 Service 接口（任务卡 Task 2 端点 7-10）
 *
 * @author kys
 * @date 2026-08-15
 */
public interface IRdWorktimeService {

    /**
     * 月度日历（按 researcher+project+month 维度，含跨课题同日合计 dayTotalAcrossProjects）。
     * researcher 只能查 researcherId=self（Service 校验 — D9/D10）。
     */
    RdWorktimeCalendarVo calendar(Long projectId, Long researcherId, String month);

    /**
     * 保存工时（D9 全套校验：scoped 闸门 + ARCHIVED 拒 + researcher 校验 +
     * 0&lt;rdHours≤24 + 跨课题同日合计≤24 + 同事务月汇总重算 + rdHours=0 视为软删）。
     */
    int saveWorktime(RdWorktimeSaveBo body, String operName);

    /**
     * 复制上月（按日序号映射；目标日已有则跳过不覆盖；返回复制/跳过条数）。
     */
    RdWorktimeCopyVo copyLastMonth(RdWorktimeSaveBo body, String operName);

    /**
     * 月度汇总分页列表（数据权限三档 — D10）。
     */
    List<RdWorktimeMonthly> selectMonthlyList(RdWorktimeMonthly query);
}