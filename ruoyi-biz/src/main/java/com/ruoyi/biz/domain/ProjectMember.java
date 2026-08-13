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

/**
 * 课题成员对象 project_member
 *
 * @author kys
 * @date 2026-08-12
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_member")
public class ProjectMember extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 成员ID */
    @TableId(value = "member_id", type = IdType.AUTO)
    private Long memberId;

    /** 课题ID */
    @TableField("project_id")
    private Long projectId;

    /** 用户ID（关联 sys_user.user_id） */
    @TableField("user_id")
    private Long userId;

    /** 课题内角色（字典 member_role：HOST/PARTICIPANT） */
    @Excel(name = "角色", dictType = "member_role", readConverterExp = "HOST=组长,PARTICIPANT=参与人")
    @TableField("role")
    private String role;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== 视图关联字段（来自 sys_user / sys_dept JOIN，非 project_member 字段） ======

    /** 用户账号（来自 sys_user.user_name） */
    @Excel(name = "账号")
    @TableField(exist = false)
    private String userName;

    /** 用户昵称（来自 sys_user.nick_name） */
    @Excel(name = "姓名")
    @TableField(exist = false)
    private String nickName;

    /** 所属部门名称（来自 sys_dept.dept_name） */
    @Excel(name = "所属部门")
    @TableField(exist = false)
    private String deptName;
}