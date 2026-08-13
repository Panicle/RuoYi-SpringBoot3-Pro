package com.ruoyi.biz.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 合作单位联系人对象 unit_contact
 *
 * <p>公司联系人 / 高校老师统一建模。major/research_field 仅高校老师使用。</p>
 *
 * @author kys
 * @date 2026-08-13
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("unit_contact")
public class UnitContact extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 联系人ID */
    @TableId(value = "contact_id", type = IdType.AUTO)
    private Long contactId;

    /** 所属单位ID */
    @TableField("unit_id")
    private Long unitId;

    /** 联系人姓名 */
    @TableField("contact_name")
    private String contactName;

    /** 职务 */
    @TableField("position")
    private String position;

    /** 联系电话 */
    @TableField("phone")
    private String phone;

    /** 电子邮箱 */
    @TableField("email")
    private String email;

    /** 专业（高校老师用） */
    @TableField("major")
    private String major;

    /** 研究领域（高校老师用） */
    @TableField("research_field")
    private String researchField;

    /** 是否主联系人（0否 1是） */
    @TableField("is_primary")
    private String isPrimary;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;
}
