package com.ruoyi.biz.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 人工费分摊结果对象 rd_labor_allocation
 *
 * <p>字段分组：V1.0.0 建表 15 列（alloc_id/project_id/researcher_id/month/allocated_amount/
 * surcharge_total/grand_total/status/batch_no/del_flag/create_by/create_time/update_by/update_time/remark）
 * + V1.0.15 新增 5 列（monthly_hours/hourly_rate/surcharge_detail/confirm_by/confirm_time）= 20 表列；
 * 非表字段 researcherName 用于列表 JOIN sys_user 展示。</p>
 *
 * <p>本任务（Task 2）只建 Domain + Mapper 骨架（resultMap + 基础查询），Service 业务规则
 * （分摊计算 / 批次确认 / 撤销确认）归 Task 3（任务卡简报明确拆分）。</p>
 *
 * <p>amount 一律 BigDecimal 2 位小数 HALF_UP（决策 D9）；status 走字典 rd_alloc_status：DRAFT/CONFIRMED。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("rd_labor_allocation")
public class RdLaborAllocation extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 分摊ID */
    @TableId(value = "alloc_id", type = IdType.AUTO)
    private Long allocId;

    /** 课题ID */
    @Excel(name = "课题ID")
    @TableField("project_id")
    private Long projectId;

    /** 研发人员ID（关联 sys_user.user_id） */
    @Excel(name = "研发人员ID")
    @TableField("researcher_id")
    private Long researcherId;

    /** 月份（格式：YYYY-MM） */
    @Excel(name = "月份")
    @TableField("month")
    private String month;

    /** 分摊人工费 */
    @Excel(name = "分摊人工费")
    @TableField("allocated_amount")
    private BigDecimal allocatedAmount;

    /** 工资附加费合计 */
    @Excel(name = "附加费合计")
    @TableField("surcharge_total")
    private BigDecimal surchargeTotal;

    /** 总计（人工费+附加费） */
    @Excel(name = "总计")
    @TableField("grand_total")
    private BigDecimal grandTotal;

    /** 状态（字典 rd_alloc_status：DRAFT/CONFIRMED） */
    @Excel(name = "状态")
    @TableField("status")
    private String status;

    /** 批次号 */
    @TableField("batch_no")
    private String batchNo;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== V1.0.15 新增 5 列 ======

    /** 月研发工时快照 */
    @TableField("monthly_hours")
    private BigDecimal monthlyHours;

    /** 时薪快照（月薪÷174，展示口径） */
    @TableField("hourly_rate")
    private BigDecimal hourlyRate;

    /** 附加费逐项JSON（{"edu":金额,...} 10项，rate_code 为键） */
    @TableField("surcharge_detail")
    private String surchargeDetail;

    /** 确认人 */
    @TableField("confirm_by")
    private String confirmBy;

    /** 确认时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("confirm_time")
    private Date confirmTime;

    // ====== 视图关联字段（来自 sys_user JOIN，非 rd_labor_allocation 字段） ======

    /** 研发人员姓名（来自 sys_user.nick_name） */
    @Excel(name = "研发人员")
    @TableField(exist = false)
    private String researcherName;
}