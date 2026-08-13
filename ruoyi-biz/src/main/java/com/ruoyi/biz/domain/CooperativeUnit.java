package com.ruoyi.biz.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 合作单位对象 cooperative_unit
 *
 * <p>树形结构照 sys_dept：parent_id/ancestors 逗号分隔（例：,3,5,）。
 * 公司树 ≤3 层、学校树 ≤2 层，层级约束在应用层校验。</p>
 *
 * @author kys
 * @date 2026-08-13
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cooperative_unit")
public class CooperativeUnit extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 单位ID */
    @TableId(value = "unit_id", type = IdType.AUTO)
    private Long unitId;

    /** 单位名称 */
    @Excel(name = "单位名称")
    @TableField("unit_name")
    private String unitName;

    /** 单位类型（字典 unit_type：INTERNAL/EXTERNAL；本模块强制 EXTERNAL，不再导出该列） */
    @TableField("unit_type")
    private String unitType;

    /** 外部单位类型（字典 external_unit_type：COMPANY/SCHOOL/OTHER；子单位继承父级，不可自行修改） */
    @Excel(name = "单位类别", dictType = "external_unit_type")
    @TableField("external_unit_type")
    private String externalUnitType;

    /** 统一社会信用代码 */
    @Excel(name = "统一社会信用代码")
    @TableField("credit_code")
    private String creditCode;

    /** 联系人 */
    @Excel(name = "联系人")
    @TableField("contact_person")
    private String contactPerson;

    /** 联系电话 */
    @Excel(name = "联系电话")
    @TableField("contact_phone")
    private String contactPhone;

    /** 单位地址 */
    @Excel(name = "单位地址")
    @TableField("address")
    private String address;

    /** 父单位ID（0=顶级） */
    @TableField("parent_id")
    private Long parentId;

    /** 祖级链（逗号分隔，首尾均为空字符串；例：,3,5,） */
    @TableField("ancestors")
    private String ancestors;

    /** 公司类型（字典 company_type：MICRO 小微企业 / GENERAL 一般纳税人，公司用） */
    @Excel(name = "公司类型", dictType = "company_type")
    @TableField("company_type")
    private String companyType;

    /** 公司性质（字典 company_category：SOE 国有企业 / PRIVATE 私营企业 / JV 合资企业 / FOREIGN 外资企业 / INSTITUTION 事业单位 / OTHER 其他，公司用） */
    @Excel(name = "公司性质", dictType = "company_category")
    @TableField("company_category")
    private String companyCategory;

    /** 擅长领域（公司/学校通用） */
    @Excel(name = "擅长领域")
    @TableField("expertise")
    private String expertise;

    /** 树内排序（同 parent_id 内排序） */
    @Excel(name = "显示顺序")
    @TableField("order_num")
    private Integer orderNum;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== 视图/树结构字段（非 cooperative_unit 字段） ======

    /** 父单位名称（来自 cooperative_unit 自身，详情回显用） */
    @TableField(exist = false)
    private String parentName;

    /** 子单位（树结构，仅内部 buildUnitTree 时填充） */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @TableField(exist = false)
    private List<CooperativeUnit> children = new ArrayList<>();
}
