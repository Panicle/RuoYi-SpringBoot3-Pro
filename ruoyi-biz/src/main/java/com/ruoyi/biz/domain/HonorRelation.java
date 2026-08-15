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
 * 荣誉关联对象 honor_relation
 *
 * <p>字段分组：V1.0.0 表列原貌（relation_id/honor_id/ref_type/ref_id/del_flag/create_by/create_time/update_by/update_time/remark）
 * + V1.0.14 新增 2 列（role_desc/contribution_desc）= 12 表列；
 * 非表字段 refName 用于关联列表 JOIN 展示（PROJECT→project_no+' '+project_name / RESEARCHER→sys_user.nick_name / UNIT→cooperative_unit.unit_name）。</p>
 *
 * <p>refType 取值固定 PROJECT/RESEARCHER/UNIT（字典 honor_ref_type），由 Service 校验合法性。
 * D2 决策：ref_type='RESEARCHER' 时 ref_id = {@code sys_user.user_id}。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("honor_relation")
public class HonorRelation extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 关联ID */
    @TableId(value = "relation_id", type = IdType.AUTO)
    private Long relationId;

    /** 荣誉ID */
    @TableField("honor_id")
    private Long honorId;

    /** 关联类型（字典 honor_ref_type：PROJECT/RESEARCHER/UNIT） */
    @TableField("ref_type")
    private String refType;

    /** 关联对象ID（PROJECT→project_id / RESEARCHER→sys_user.user_id / UNIT→cooperative_unit.unit_id） */
    @TableField("ref_id")
    private Long refId;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== V1.0.14 新增 2 列 ======

    /** 角色/名次说明 */
    @TableField("role_desc")
    private String roleDesc;

    /** 贡献说明 */
    @TableField("contribution_desc")
    private String contributionDesc;

    // ====== 视图关联字段（来自 project/sys_user/cooperative_unit JOIN，非 honor_relation 字段） ======

    /**
     * 关联对象名称（不同 refType 解析规则）：
     * PROJECT → project_no || ' ' || project_name
     * RESEARCHER → sys_user.nick_name
     * UNIT → cooperative_unit.unit_name
     */
    @TableField(exist = false)
    private String refName;
}
