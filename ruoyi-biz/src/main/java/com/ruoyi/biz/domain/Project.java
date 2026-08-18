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
import java.util.List;

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

    /** 课题编号（人工输入业务编号，唯一） */
    @Excel(name = "课题编号")
    @TableField("project_no")
    private String projectNo;

    /** 课题级别（字典 project_type：NATIONAL/PROVINCIAL/CR_GROUP/COMPANY/INSTITUTE/LATERAL，V1.0.6 新增） */
    @Excel(name = "课题级别", dictType = "project_type")
    @TableField("project_type")
    private String projectType;

    /** 项目类别（字典 project_category：A全额资助课题/B定额补助课题/C经费全部自筹课题，V1.0.8 新增，必填） */
    @Excel(name = "项目类别", dictType = "project_category")
    @TableField("project_category")
    private String projectCategory;

    /** 专业分类（字典 specialty：Y运输/J机务/GD供电/C车辆/G工务工程/D电务/X信息技术/Z综合/F软科学，V1.0.8 新增，必填） */
    @Excel(name = "专业分类", dictType = "specialty")
    @TableField("specialty")
    private String specialty;

    /** 课题名称 */
    @Excel(name = "课题名称")
    @TableField("project_name")
    private String projectName;

    /** 课题负责人ID（关联 sys_user.user_id） */
    @Excel(name = "组长ID")
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

    /** 是否本单位主持（1 本单位（科研所）/0 外单位主持，V1.0.20） */
    @TableField("self_hosted")
    private String selfHosted;

    /** 主持单位ID（外单位主持时关联 cooperative_unit.unit_id；本单位主持为空） */
    @TableField("host_unit_id")
    private Long hostUnitId;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== 视图关联字段（来自 sys_user / sys_dept JOIN，非 project 字段） ======

    /** 组长姓名（来自 sys_user.nick_name，列表/详情展示） */
    @TableField(exist = false)
    private String leaderName;

    /** 主持单位名称（来自 sys_dept.dept_name，集团二级公司，V1.0.23 起不再关联 cooperative_unit） */
    @TableField(exist = false)
    private String hostUnitName;

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

    // ====== 预算细分（来自 budget_split，非 project 字段） ======

    /** 预算细分列表（详情/编辑回显；新增/修改请求体携带，预算总额 = Σ 各科目金额） */
    @TableField(exist = false)
    private List<BudgetSplit> budgetSplitList;

    /** 研究领域编码列表（多选，research_direction 字典值；V1.0.21 存 project_field） */
    @TableField(exist = false)
    private List<String> fieldList;

    /** 按单位预算列表（V1.0.23 存 project_unit_budget；主持+参与单位各一套 10 科目，聚合后写 budget_split） */
    @TableField(exist = false)
    private List<ProjectUnitBudget> unitBudgetList;

    /** 参与/协作单位列表（V1.0.24 随课题新增/编辑全量保存到 project_unit，非 project 字段） */
    @TableField(exist = false)
    private List<ProjectUnit> unitList;
}
