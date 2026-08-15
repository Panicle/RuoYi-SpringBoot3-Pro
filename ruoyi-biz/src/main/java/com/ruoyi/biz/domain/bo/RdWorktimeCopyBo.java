package com.ruoyi.biz.domain.bo;

import lombok.Data;

/**
 * 复制上月请求体（端点 9 /biz/rd/worktime/copyLastMonth POST）
 *
 * <p>按"日序号"映射到本月同号日（上月 29/30/31 号本月不存在则丢弃）；
 * 目标日已有有效记录则**跳过不覆盖**；返回复制条数与跳过条数。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
public class RdWorktimeCopyBo {

    /** 课题ID */
    private Long projectId;

    /** 研发人员ID */
    private Long researcherId;

    /** 目标月份（YYYY-MM；将基于该月计算"上月"=该月减一个月） */
    private String month;
}