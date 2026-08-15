package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.RdWorktimeDaily;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 每日研发工时 Mapper 接口
 *
 * <p>(project_id, researcher_id, work_date) 唯一性由应用层保证 — DB 仅建普通索引
 * idx_rd_worktime_daily_prd；同键有效行 UPDATE，否则 INSERT（任务卡 D9）。</p>
 *
 * <p>save/copy 同事务后还需重算 rd_worktime_monthly.total_rd_hours 与 cumulative_hours，
 * 重算 SQL 由 Service 用 SUM/聚合直接执行，不在本 Mapper 内。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
public interface RdWorktimeDailyMapper extends BaseMapper<RdWorktimeDaily> {

    /**
     * 按 (projectId, researcherId, workDate) 查有效单行（同键 upsert 用）。
     */
    RdWorktimeDaily selectByProjectResearcherDate(@Param("projectId") Long projectId,
                                                  @Param("researcherId") Long researcherId,
                                                  @Param("workDate") Date workDate);

    /**
     * 按 (researcherId, workDate) 查全部有效行 — 用于「跨课题同日合计 ≤ 24」校验。
     * 不限定 del_flag 之外的过滤（校验含本次值即可，DB 已通过 del_flag='0' 索引扫）。
     */
    List<RdWorktimeDaily> selectByResearcherAndDate(@Param("researcherId") Long researcherId,
                                                    @Param("workDate") Date workDate);

    /**
     * 按 (projectId, researcherId, 月份起止) 查有效日行 — 用于复制上月与月汇总重算。
     *
     * @param projectId    课题ID
     * @param researcherId 研发人员ID
     * @param monthPrefix  月份前缀 'YYYY-MM'（按 to_char 截断比较）
     */
    List<RdWorktimeDaily> selectByProjectResearcherMonth(@Param("projectId") Long projectId,
                                                         @Param("researcherId") Long researcherId,
                                                         @Param("monthPrefix") String monthPrefix);

    /**
     * 按 (projectId, researcherId, 月份起止) 求和 total_rd_hours
     * （用于月末重算 total_rd_hours）。
     */
    java.math.BigDecimal sumRdHoursByProjectResearcherMonth(@Param("projectId") Long projectId,
                                                            @Param("researcherId") Long researcherId,
                                                            @Param("monthPrefix") String monthPrefix);

    /**
     * 按 (projectId, researcherId, month <= ? ) 求和累计工时
     * （用于月末重算 cumulative_hours）。
     */
    java.math.BigDecimal sumCumulativeHours(@Param("projectId") Long projectId,
                                            @Param("researcherId") Long researcherId,
                                            @Param("monthEndInclusive") String monthEndInclusive);
}