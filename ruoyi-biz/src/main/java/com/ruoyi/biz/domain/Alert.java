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
 * 预警对象 alert（本期仅经费预警 alert_type='BUDGET'，ref_id = budget_split.split_id）
 *
 * <p>决策 D5：预警落 alert 表（记账事务内幂等写入），通知推送/定时扫描留阶段9。
 * 去重键 = alert_type='BUDGET' + ref_id=split_id + status ∈ (UNREAD, READ)。</p>
 *
 * @author kys
 * @date 2026-08-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("alert")
public class Alert extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 预警ID */
    @TableId(value = "alert_id", type = IdType.AUTO)
    private Long alertId;

    /** 预警类型（字典 alert_type：CONTRACT/BUDGET/DOCUMENT） */
    @TableField("alert_type")
    private String alertType;

    /** 关联对象ID（BUDGET 预警为 budget_split.split_id） */
    @TableField("ref_id")
    private Long refId;

    /** 预警级别（字典 alert_level：INFO/WARN/CRITICAL） */
    @TableField("alert_level")
    private String alertLevel;

    /** 预警标题 */
    @TableField("title")
    private String title;

    /** 预警内容 */
    @TableField("content")
    private String content;

    /** 状态（UNREAD 未读 / READ 已读 / HANDLED 已处理） */
    @TableField("status")
    private String status;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== 视图关联字段（来自 budget_split / project JOIN，非 alert 字段） ======

    /** 所属课题ID（来自 budget_split.project_id；同时用作列表查询条件） */
    @TableField(exist = false)
    private Long projectId;

    /** 所属课题编号（来自 project.project_no） */
    @TableField(exist = false)
    private String projectNo;

    /** 所属课题名称（来自 project.project_name） */
    @TableField(exist = false)
    private String projectName;

    /** 预算科目（来自 budget_split.category，字典 budget_category） */
    @TableField(exist = false)
    private String category;

    /** 状态标签（后端不填，前端字典 useDict('alert_status') 自行渲染）；
     *  显式声明仅为避免下游误用 status 原值（UNREAD/READ/HANDLED）做展示——
     *  前端按业务需要取 statusLabel 或直接用 status 原始值均可 */
    @TableField(exist = false)
    private String statusLabel;
}
