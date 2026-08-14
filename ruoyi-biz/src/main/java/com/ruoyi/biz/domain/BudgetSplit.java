package com.ruoyi.biz.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 课题预算细分对象 budget_split
 *
 * <p>V1.0.11 加 3 列（used_amount / balance / version）：预算细分升级为「预算与余额的事实来源」，
 * project.budget_total = Σ budget_amount、project.budget_balance = Σ balance 均由本表事务内派生（决策 D3）。
 * version 走 MyBatis-Plus `@Version` 乐观锁：调用 {@code updateById(entity)} 时自动附加
 * `AND version = #{version}` 条件并自增，实体的 version 必须非空，影响行数 0 即为并发冲突（决策 D9）。</p>
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

    /** 已用金额缓存 = Σ 有效 expense.amount（事务内重算写回） */
    @TableField("used_amount")
    private BigDecimal usedAmount;

    /** 余额缓存 = budget_amount - used_amount */
    @TableField("balance")
    private BigDecimal balance;

    /** 乐观锁版本号（MyBatis-Plus @Version，updateById 自动附加条件并自增） */
    @Version
    @TableField("version")
    private Integer version;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== 非表字段（预算汇总/概览用，由 Service 计算填充） ======

    /** 是否命中预警（§4.3 双阈值：balance ≤ 1000 或 balance/budget_amount ≤ 5%） */
    @TableField(exist = false)
    private Boolean alertFlag;

    /** 预警级别（字典 alert_level：CRITICAL 余额≤0 / WARN 其余命中；未命中为 null） */
    @TableField(exist = false)
    private String alertLevel;
}
