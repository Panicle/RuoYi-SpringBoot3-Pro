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
 * 每日研发工时对象 rd_worktime_daily
 *
 * <p>字段对应 V1.0.0 建表 11 列（id/project_id/researcher_id/work_date/rd_hours/del_flag/
 * create_by/create_time/update_by/update_time/remark）。</p>
 *
 * <p>业务约束（任务卡 D9）：
 * <ul>
 *   <li>(project_id, researcher_id, work_date) 唯一性由应用层保证 — DB 仅建普通索引
 *       idx_rd_worktime_daily_prd；同键有效行 UPDATE，否则 INSERT（不建唯一索引）</li>
 *   <li>单日单课题 0 &lt; rd_hours ≤ 24</li>
 *   <li>跨课题同日合计 ≤ 24 — 保存前按 researcher+date 汇总校验，含本次值</li>
 *   <li>rd_hours=0 视为软删该日（save 端点语义）</li>
 * </ul>
 * </p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("rd_worktime_daily")
public class RdWorktimeDaily extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 每日工时ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 课题ID */
    @TableField("project_id")
    private Long projectId;

    /** 研发人员ID */
    @TableField("researcher_id")
    private Long researcherId;

    /** 工作日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "工作日期", dateFormat = "yyyy-MM-dd")
    @TableField("work_date")
    private Date workDate;

    /** 当日研发工时（小时；0<rd_hours≤24；=0 视为软删） */
    @Excel(name = "研发工时")
    @TableField("rd_hours")
    private BigDecimal rdHours;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;
}