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
 * 研发人员工资标准对象 rd_researcher_salary
 *
 * <p>字段对应 V1.0.0 建表 10 列（salary_id/researcher_id/salary_month/monthly_salary/del_flag/
 * create_by/create_time/update_by/update_time/remark）。salaryMonth 格式 'YYYY-MM'（VARCHAR(7)）；
 * 非表字段 researcherName 用于列表 JOIN sys_user 展示。</p>
 *
 * <p>DB 无唯一索引，同键唯一由应用层查重保证（save 端点）；
 * save 端点应用层查重（任务卡 D9），命中则 UPDATE，否则 INSERT。</p>
 *
 * <p>researcher_id = sys_user.user_id（任务卡 D2）；金额一律 BigDecimal，2 位小数 HALF_UP（决策 D9）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("rd_researcher_salary")
public class RdResearcherSalary extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 工资标准ID */
    @TableId(value = "salary_id", type = IdType.AUTO)
    private Long salaryId;

    /** 研发人员ID（关联 sys_user.user_id） */
    @Excel(name = "研发人员ID")
    @TableField("researcher_id")
    private Long researcherId;

    /** 工资月份（格式：YYYY-MM） */
    @Excel(name = "工资月份")
    @TableField("salary_month")
    private String salaryMonth;

    /** 月度工资 */
    @Excel(name = "月度工资")
    @TableField("monthly_salary")
    private BigDecimal monthlySalary;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== 视图关联字段（来自 sys_user JOIN，非 rd_researcher_salary 字段） ======

    /** 研发人员姓名（来自 sys_user.nick_name） */
    @Excel(name = "研发人员")
    @TableField(exist = false)
    private String researcherName;
}