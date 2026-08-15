package com.ruoyi.biz.domain.bo;

import lombok.Data;

/**
 * 分摊计算请求体（端点 11 /biz/rd/alloc/calc POST）
 *
 * <p>任务卡 Task 3：body {projectId, month}，触发 calc 端点的伪代码 1-11 步。
 * CONFIRMED 批次存在时拒绝（任务卡简报：步骤 3 "批次已确认，请先撤销确认"）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
public class RdAllocCalcBo {

    /** 课题ID */
    private Long projectId;

    /** 月份（YYYY-MM） */
    private String month;
}