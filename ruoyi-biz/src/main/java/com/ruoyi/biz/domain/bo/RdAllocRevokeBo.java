package com.ruoyi.biz.domain.bo;

import lombok.Data;

/**
 * 撤销确认请求体（端点 14 /biz/rd/alloc/revoke POST）
 *
 * <p>任务卡 Task 3：body {projectId, month, reason}；reason 必填非空，
 * reason 追加写入行 remark（格式"[撤销确认 by X at time] reason"）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
public class RdAllocRevokeBo {

    /** 课题ID */
    private Long projectId;

    /** 月份（YYYY-MM） */
    private String month;

    /** 撤销原因（必填非空） */
    private String reason;
}