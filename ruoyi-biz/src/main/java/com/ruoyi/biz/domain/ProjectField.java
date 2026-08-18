package com.ruoyi.biz.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;

/**
 * 课题研究领域关联（project_field，V1.0.21）
 *
 * <p>课题↔研究领域 多对多；field_code 取 research_direction 字典值。</p>
 *
 * @author kys
 * @date 2026-08-17
 */
@Data
@TableName("project_field")
public class ProjectField extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 关联ID */
    @TableId(value = "field_id", type = IdType.AUTO)
    private Long fieldId;

    /** 课题ID */
    private Long projectId;

    /** 研究领域编码（research_direction 字典值） */
    private String fieldCode;
}
