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
 * 课题合作单位关联对象 project_unit
 *
 * @author kys
 * @date 2026-08-13
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_unit")
public class ProjectUnit extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 关联ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 课题ID */
    @TableField("project_id")
    private Long projectId;

    /** 合作单位ID */
    @TableField("unit_id")
    private Long unitId;

    /** 合作类型（字典 cooperation_type：LEAD/PARTICIPANT/COLLABORATE） */
    @TableField("cooperation_type")
    private String cooperationType;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== 视图关联字段（来自 cooperative_unit JOIN，非 project_unit 字段） ======

    /** 单位名称（来自 cooperative_unit.unit_name） */
    @TableField(exist = false)
    private String unitName;

    /** 外部单位类型（来自 cooperative_unit.external_unit_type：COMPANY/SCHOOL/OTHER） */
    @TableField(exist = false)
    private String externalUnitType;
}
