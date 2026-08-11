package com.ruoyi.biz.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
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
 * 科研人员档案对象 biz_user_profile
 *
 * @author kys
 * @date 2026-08-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_user_profile")
public class UserProfile extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 档案ID */
    @TableId(value = "profile_id", type = IdType.AUTO)
    private Long profileId;

    /** 用户ID（关联 sys_user.user_id） */
    @TableField("user_id")
    private Long userId;

    /** 学历（字典：edu_level，大写如 BACHELOR / MASTER / DOCTOR） */
    @Excel(name = "学历", dictType = "edu_level")
    @TableField("edu_level")
    private String eduLevel;

    /** 职称等级（字典：title_level，大写如 JUNIOR / MID / SUB_SENIOR / SENIOR） */
    @Excel(name = "职称等级", dictType = "title_level")
    @TableField("title_level")
    private String titleLevel;

    /** 研究方向 */
    @Excel(name = "研究方向")
    @TableField("research_direction")
    private String researchDirection;

    /** 研究领域 */
    @Excel(name = "研究领域")
    @TableField("research_area")
    private String researchArea;

    /** 身份证号 */
    @TableField("id_number")
    private String idNumber;

    /** 入职日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "入职日期", dateFormat = "yyyy-MM-dd")
    @TableField("entry_date")
    private Date entryDate;

    /** 办公电话 */
    @TableField("office_phone")
    private String officePhone;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic
    @TableField(value = "del_flag", fill = FieldFill.INSERT)
    private String delFlag;

    // ====== 视图关联字段（来自 sys_user / sys_dept JOIN，非 biz_user_profile 字段） ======

    /** 用户昵称（来自 sys_user.nick_name，列表展示 / 模糊查询） */
    @TableField(exist = false)
    private String nickName;

    /** 部门ID（来自 sys_user.dept_id，列表查询条件 / 展示） */
    @TableField(exist = false)
    private Long deptId;

    /** 部门名称（来自 sys_dept.dept_name，列表展示） */
    @TableField(exist = false)
    private String deptName;
}