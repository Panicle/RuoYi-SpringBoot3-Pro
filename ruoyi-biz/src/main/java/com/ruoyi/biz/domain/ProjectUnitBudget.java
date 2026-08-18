package com.ruoyi.biz.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 课题按单位经费支出预算（project_unit_budget，V1.0.23）
 *
 * <p>dept_id 覆盖主持+参与单位（集团二级公司 sys_dept）；category 取 budget_category 十科目，
 * 每单位一套 10 科目明细（非总金额）。同一 (project_id, dept_id, category) 至多一行
 * （唯一索引 idx_pub_pdc 兜底），保存时删旧写新。</p>
 *
 * <p>del_flag 为普通列（不加 @TableLogic）：本表走「物理删旧 + 重插」语义（同 budget_source），
 * 若走逻辑删，物理行仍占用唯一索引会导致重插冲突。</p>
 *
 * @author kys
 * @date 2026-08-18
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_unit_budget")
public class ProjectUnitBudget extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 课题ID */
    @TableField("project_id")
    private Long projectId;

    /** 二级公司 dept_id（sys_dept，覆盖主持+参与单位） */
    @TableField("dept_id")
    private Long deptId;

    /** 预算科目（budget_category 字典值，每单位 10 科目） */
    @TableField("category")
    private String category;

    /** 该单位该科目预算金额（元） */
    @TableField("budget_amount")
    private BigDecimal budgetAmount;

    /** 删除标志（0代表存在 2代表删除） */
    @TableField("del_flag")
    private String delFlag;
}
