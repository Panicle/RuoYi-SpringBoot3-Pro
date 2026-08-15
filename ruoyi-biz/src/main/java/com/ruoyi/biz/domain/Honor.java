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

import java.util.Date;

/**
 * 荣誉对象 honor
 *
 * <p>字段分组：V1.0.0 表列原貌（honor_id/honor_name/honor_type/award_date/award_level/award_org/description/del_flag/create_by/create_time/update_by/update_time/remark）
 * + V1.0.14 新增 2 列（certificate_no/certificate_url）= 15 表列；
 * 无视图/聚合非表字段（决策 D2 简报：本表无聚合子对象，列表/详情 JOIN 仅用于数据权限派生，不由 Honor 自身携带）。</p>
 *
 * <p>honorType/awardLevel 走字典 honor_type/honor_level；awardDate 走 Date（不 LocalDate，避免序列化漂移）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("honor")
public class Honor extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 荣誉ID */
    @TableId(value = "honor_id", type = IdType.AUTO)
    private Long honorId;

    /** 荣誉名称 */
    @Excel(name = "荣誉名称")
    @TableField("honor_name")
    private String honorName;

    /** 荣誉类型（字典 honor_type：COLLECTIVE/INDIVIDUAL） */
    @Excel(name = "荣誉类型", dictType = "honor_type")
    @TableField("honor_type")
    private String honorType;

    /** 获奖日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "获奖日期", dateFormat = "yyyy-MM-dd")
    @TableField("award_date")
    private Date awardDate;

    /** 获奖级别（字典 honor_level：NATIONAL/PROVINCIAL/GROUP/COMPANY/INSTITUTE） */
    @Excel(name = "获奖级别", dictType = "honor_level")
    @TableField("award_level")
    private String awardLevel;

    /** 颁奖机构 */
    @Excel(name = "颁奖机构")
    @TableField("award_org")
    private String awardOrg;

    /** 荣誉描述 */
    @Excel(name = "荣誉描述")
    @TableField("description")
    private String description;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== V1.0.14 新增 2 列 ======

    /** 证书编号 */
    @Excel(name = "证书编号")
    @TableField("certificate_no")
    private String certificateNo;

    /** 证书附件路径（/common/upload 相对路径） */
    @TableField("certificate_url")
    private String certificateUrl;
}
