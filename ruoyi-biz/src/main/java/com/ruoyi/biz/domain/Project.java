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
 * 课题对象 project
 *
 * @author kys
 * @date 2026-08-12
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project")
public class Project extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 课题ID */
    @TableId(value = "project_id", type = IdType.AUTO)
    private Long projectId;

    /** 课题编号（格式 KY-{yyyy}-{3位流水}，V1.0.6 新增，唯一） */
    @Excel(name = "课题编号")
    @TableField("project_no")
    private String projectNo;

    /** 课题级别（字典 project_type：NATIONAL/PROVINCIAL/CR_GROUP/COMPANY/INSTITUTE/LATERAL，V1.0.6 新增） */
    @Excel(name = "课题级别", dictType = "project_type")
    @TableField("project_type")
    private String projectType;

    /** 课题名称 */
    @Excel(name = "课题名称")
    @TableField("project_name")
    private String projectName;

    /** 课题负责人ID（关联 sys_user.user_id） */
    @Excel(name = "主持人ID")
    @TableField("leader_id")
    private Long leaderId;

    /** 预算总额 */
    @Excel(name = "预算总额")
    @TableField("budget_total")
    private BigDecimal budgetTotal;

    /** 预算余额（阶段4 维护核减） */
    @TableField("budget_balance")
    private BigDecimal budgetBalance;

    /** 状态（字典 project_status：DRAFT/ACTIVE/COMPLETED/ACCEPTED/ARCHIVED） */
    @Excel(name = "状态", dictType = "project_status", readConverterExp = "DRAFT=立项,ACTIVE=在研,COMPLETED=结题,ACCEPTED=评审,ARCHIVED=归档")
    @TableField("status")
    private String status;

    /** 开始日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "开始日期", dateFormat = "yyyy-MM-dd")
    @TableField("start_date")
    private Date startDate;

    /** 结束日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "结束日期", dateFormat = "yyyy-MM-dd")
    @TableField("end_date")
    private Date endDate;

    /** 所属部门ID */
    @TableField("dept_id")
    private Long deptId;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== 视图关联字段（来自 sys_user / sys_dept JOIN，非 project 字段） ======

    /** 主持人姓名（来自 sys_user.nick_name，列表/详情展示） */
    @TableField(exist = false)
    private String leaderName;

    /** 所属部门名称（来自 sys_dept.dept_name） */
    @Excel(name = "所属部门")
    @TableField(exist = false)
    private String deptName;

    /** 课题级别字典翻译（详情页用） */
    @TableField(exist = false)
    private String projectTypeLabel;

    /** 状态字典翻译（详情页用） */
    @TableField(exist = false)
    private String statusLabel;
}