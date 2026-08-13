package com.ruoyi.biz.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 课题预算细分对象 budget_split
 *
 * @author kys
 * @date 2026-08-13
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("budget_split")
public class BudgetSplit extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 分劈ID */
    @TableId(value = "split_id", type = IdType.AUTO)
    private Long splitId;

    /** 课题ID */
    @TableField("project_id")
    private Long projectId;

    /** 预算科目（字典 budget_category：LABOR/EQUIPMENT/MATERIAL/TESTING/FUEL/TRAVEL/PUBLICATION/INDIRECT/OUTSOURCING/TAX） */
    @TableField("category")
    private String category;

    /** 预算金额 */
    @TableField("budget_amount")
    private BigDecimal budgetAmount;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;
}
