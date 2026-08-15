package com.ruoyi.biz.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 工资附加费比例对象 surcharge_rate（V1.0.0 基线表，Task 3 直接读）
 *
 * <p>SQL 基线：sql/kys/V1.0.0__base_tables.sql:970（rate_id/rate_name/rate_code/rate_value/status/del_flag 等 12 列）。
 * 唯一索引 idx_surcharge_rate_code（rate_code）兜底。本表 10 项 ACTIVE 真实值在
 * sql/kys/V1.0.1__dict_data.sql:203-213（Σrate_value=0.4986）。</p>
 *
 * <p>status 字面量 'ACTIVE' / 'INACTIVE'（与字典不同，非 RuoYi 通用 '0'/'1'），
 * 任务卡 D9 与 V1.0.15 changelog 已确认代码层直接用字面量过滤，不走字典映射。</p>
 *
 * <p>本 Domain 不需要 BaseEntity 全字段（createBy/updateBy/remark 不参与业务），
 * 但保留以便通用基类惯例（Task 4 报表导出可能需要 createBy）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("surcharge_rate")
public class SurchargeRate extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 比例ID */
    @TableId(value = "rate_id", type = IdType.AUTO)
    private Long rateId;

    /** 附加费名称 */
    @Excel(name = "附加费名称")
    @TableField("rate_name")
    private String rateName;

    /** 附加费编码 */
    @Excel(name = "附加费编码")
    @TableField("rate_code")
    private String rateCode;

    /** 比例值（如0.0150表示1.5%） */
    @Excel(name = "比例值")
    @TableField("rate_value")
    private BigDecimal rateValue;

    /** 状态（ACTIVE有效/INACTIVE无效） */
    @Excel(name = "状态")
    @TableField("status")
    private String status;
}