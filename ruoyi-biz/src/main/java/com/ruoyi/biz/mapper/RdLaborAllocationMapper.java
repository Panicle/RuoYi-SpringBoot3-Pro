package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.RdLaborAllocation;
import com.ruoyi.biz.domain.vo.RdAllocSummaryRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 人工费分摊 Mapper 接口（骨架）
 *
 * <p>task brief：RdLaborAllocation 本任务（Task 2）只建 Domain + Mapper 骨架（resultMap + 基础查询），
 * Service 业务规则（分摊计算 / 批次确认 / 撤销确认）归 Task 3。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
public interface RdLaborAllocationMapper extends BaseMapper<RdLaborAllocation> {

    /**
     * 按 (projectId, month) 查有效分摊行（task brief：预算 save 校验 CONFIRMED 月拒改）。
     */
    List<RdLaborAllocation> selectByProjectAndMonth(@Param("projectId") Long projectId,
                                                    @Param("month") String month);

    /**
     * 按 (projectId, month) 计数 CONFIRMED 分摊批次（任务卡 D9：预算锁定判定）。
     */
    int countConfirmedByProjectAndMonth(@Param("projectId") Long projectId,
                                        @Param("month") String month);

    /**
     * 按 (projectId, monthStr) 计数 CONFIRMED 分摊批次（budget save 的 Integer month 由调用处拼成
     * 'YYYY-MM' 字符串等值传入；month 列是 VARCHAR，达梦下 to_char/to_number 运行时报字符串转换错）。
     */
    int countConfirmedByProjectAndMonthInt(@Param("projectId") Long projectId,
                                           @Param("monthStr") String monthStr);

    /**
     * 多课题×某年 12 月聚合 Σalloc/Σsurcharge/Σgrand（端点 18 多课题汇总用）。
     * <p><b>汇总口径 = 已确认批次（status='CONFIRMED'）</b>，与批次看板 SUM 口径一致；
     * DRAFT 批次不计入（任务卡裁决采纳 fix I3）。</p>
     * 按 (project_id, month) 升序稳定排序，month=YYYY-MM；调用方负责 projectIds 范围（scoped 闸门已通过）。
     */
    List<RdAllocSummaryRow> aggregateByProjectsYear(@Param("projectIds") List<Long> projectIds,
                                                    @Param("year") Integer year);
}