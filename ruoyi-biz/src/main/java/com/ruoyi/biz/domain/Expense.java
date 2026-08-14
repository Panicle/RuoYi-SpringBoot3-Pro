package com.ruoyi.biz.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 经费流水对象 expense
 *
 * <p>字段分组：13 表列原貌（expense_id/project_id/amount/tax_rate/expense_date/category/description/del_flag
 * + BaseEntity 的 create_by/create_time/update_by/update_time/remark）
 * + V1.0.11 新增 4 列（split_id/status/voucher_url/version）= 17 表列；
 * 非表字段 projectNo/projectName/categoryLabel 用于列表 JOIN 展示。</p>
 *
 * <p>金额一律 BigDecimal，2 位小数 HALF_UP（决策 D9）；status 走字典 expense_status（NORMAL/VOID），
 * 修改支出 = 作废原单 + 新增新单，退款/冲销 = 追加负数流水，历史金额不就地改（决策 D8）。
 * version 走 MyBatis-Plus {@code @Version} 乐观锁，{@code updateById} 自动附加条件并自增。</p>
 *
 * @author kys
 * @date 2026-08-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("expense")
public class Expense extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 记账ID */
    @TableId(value = "expense_id", type = IdType.AUTO)
    private Long expenseId;

    /** 课题ID */
    @TableField("project_id")
    private Long projectId;

    /** 关联预算分劈行（budget_split.split_id；应用层强校验非空） */
    @TableField("split_id")
    private Long splitId;

    /** 金额（含税；冲销流水为负数） */
    @Excel(name = "金额")
    @TableField("amount")
    private BigDecimal amount;

    /** 税率 */
    @TableField("tax_rate")
    private BigDecimal taxRate;

    /** 费用发生日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "费用日期", dateFormat = "yyyy-MM-dd")
    @TableField("expense_date")
    private Date expenseDate;

    /** 经费类别（字典 budget_category，与 budget_split.category 同源；以 split 为准回填） */
    @Excel(name = "预算科目", dictType = "budget_category")
    @TableField("category")
    private String category;

    /** 流水状态（字典 expense_status：NORMAL 正常 / VOID 已作废） */
    @Excel(name = "状态", dictType = "expense_status")
    @TableField("status")
    private String status;

    /** 凭证（/common/upload 相对路径） */
    @TableField("voucher_url")
    private String voucherUrl;

    /** 费用说明 */
    @Excel(name = "费用说明")
    @TableField("description")
    private String description;

    /** 乐观锁版本号（MyBatis-Plus @Version，updateById 自动附加条件并自增） */
    @Version
    @TableField("version")
    private Integer version;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== 视图关联字段（来自 project JOIN，非 expense 字段） ======

    /** 所属课题编号（来自 project.project_no） */
    @Excel(name = "所属课题编号")
    @TableField(exist = false)
    private String projectNo;

    /** 所属课题名称（来自 project.project_name） */
    @Excel(name = "所属课题")
    @TableField(exist = false)
    private String projectName;

    /** 预算科目名称（字典 budget_category 翻译，详情展示用） */
    @TableField(exist = false)
    private String categoryLabel;
}
