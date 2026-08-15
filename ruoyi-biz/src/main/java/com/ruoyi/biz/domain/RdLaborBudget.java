package com.ruoyi.biz.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 课题研发人工费预算对象 rd_labor_budget
 *
 * <p>字段对应 V1.0.0 建表 12 列（budget_id/project_id/budget_year/month/total_amount/status/del_flag/
 * create_by/create_time/update_by/update_time/remark）；month 取值 1-12（任务卡 D9 端点约束）。
 * 增量 upsert 语义（任务卡 D1）：按 (project_id, budget_year, month) 应用层查重。</p>
 *
 * <p>金额一律 BigDecimal，2 位小数 HALF_UP（决策 D9）；status 走字典 rd_budget_status：DRAFT/CONFIRMED。
 * 当 status='CONFIRMED' 时该月被 rd_labor_allocation 引用后不可改（任务卡简报：CONFIRMED 月拒改）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("rd_labor_budget")
public class RdLaborBudget extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 预算ID */
    @TableId(value = "budget_id", type = IdType.AUTO)
    private Long budgetId;

    /** 课题ID */
    @Excel(name = "课题ID")
    @TableField("project_id")
    private Long projectId;

    /** 预算年度 */
    @Excel(name = "预算年度")
    @TableField("budget_year")
    private Integer budgetYear;

    /** 预算月份（1-12） */
    @Excel(name = "月份")
    @TableField("month")
    private Integer month;

    /** 预算总额 */
    @Excel(name = "预算总额")
    @TableField("total_amount")
    private BigDecimal totalAmount;

    /** 状态（字典 rd_budget_status：DRAFT/CONFIRMED） */
    @Excel(name = "状态")
    @TableField("status")
    private String status;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;
}