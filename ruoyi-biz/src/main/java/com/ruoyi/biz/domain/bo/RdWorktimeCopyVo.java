package com.ruoyi.biz.domain.bo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 复制上月响应体（端点 9 返回）
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RdWorktimeCopyVo {

    /** 实际复制条数 */
    private Integer copiedCount;

    /** 因目标日已存在而跳过的条数 */
    private Integer skippedCount;
}