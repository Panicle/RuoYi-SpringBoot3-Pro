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
 * 月度研发工时汇总对象 rd_worktime_monthly
 *
 * <p>字段对应 V1.0.0 建表 11 列（id/project_id/researcher_id/month/total_rd_hours/
 * cumulative_hours/del_flag/create_by/create_time/update_by/update_time/remark）。
 * total_rd_hours = 该 project+researcher+month 当月每日有效工时合计；cumulative_hours =
 * 该 project+researcher 自最早记录至当月的累计（任务卡 D9 月汇总重算）。</p>
 *
 * <p>唯一索引 idx_rd_worktime_monthly_uk(project_id, researcher_id, month) 保证一对一，
 * 保存/复制工时同日同事务 UPSERT 该行（任务卡 D9）。</p>
 *
 * <p>非表字段 researcherName/projectNo/projectName 用于列表 JOIN 展示。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("rd_worktime_monthly")
public class RdWorktimeMonthly extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 月度汇总ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 课题ID */
    @TableField("project_id")
    private Long projectId;

    /** 研发人员ID */
    @TableField("researcher_id")
    private Long researcherId;

    /** 月份（格式：YYYY-MM） */
    @Excel(name = "月份")
    @TableField("month")
    private String month;

    /** 当月研发工时合计（小时） */
    @Excel(name = "当月工时")
    @TableField("total_rd_hours")
    private BigDecimal totalRdHours;

    /** 累计研发工时（小时） */
    @TableField("cumulative_hours")
    private BigDecimal cumulativeHours;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== 视图关联字段（来自 sys_user / project JOIN，非 rd_worktime_monthly 字段） ======

    /** 研发人员姓名（来自 sys_user.nick_name） */
    @Excel(name = "研发人员")
    @TableField(exist = false)
    private String researcherName;

    /** 所属课题编号（来自 project.project_no） */
    @Excel(name = "所属课题编号")
    @TableField(exist = false)
    private String projectNo;

    /** 所属课题名称（来自 project.project_name） */
    @Excel(name = "所属课题")
    @TableField(exist = false)
    private String projectName;
}