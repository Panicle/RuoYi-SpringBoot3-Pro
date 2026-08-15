package com.ruoyi.biz.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 审批历史对象 approval_history
 *
 * <p>字段分组：12 表列原貌（history_id/approval_id/action/operator_id/comment_text/operate_time/del_flag/create_by/create_time/update_by/update_time/remark）
 * + V1.0.13 新增 1 列（round）= 13 表列；
 * 非表字段 operatorName/actionLabel 用于历史时间线展示（operatorName 来自 sys_user JOIN，actionLabel 由 Service 手工映射）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("approval_history")
public class ApprovalHistory extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 历史ID */
    @TableId(value = "history_id", type = IdType.AUTO)
    private Long historyId;

    /** 审批ID */
    @TableField("approval_id")
    private Long approvalId;

    /** 操作类型（SUBMIT提交/APPROVE通过/REJECT驳回/RESUBMIT重报/TRANSFER转交） */
    @TableField("action")
    private String action;

    /** 操作人ID */
    @TableField("operator_id")
    private Long operatorId;

    /** 审批意见（comment 为达梦保留字，故用 comment_text；意见/驳回原因复用，决策 D5） */
    @TableField("comment_text")
    private String commentText;

    /** 操作时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("operate_time")
    private Date operateTime;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== V1.0.13 新增 1 列 ======

    /** 该动作发生时的审批轮次（SUBMIT=1 / REJECT=1 / RESUBMIT=2 / APPROVE=2 等） */
    @TableField("round")
    private Integer round;

    // ====== 视图关联字段（来自 sys_user JOIN，非 approval_history 字段） ======

    /** 操作人姓名（来自 sys_user.nick_name） */
    @TableField(exist = false)
    private String operatorName;

    /** 操作类型字典文本（SUBMIT/APPROVE/REJECT/RESUBMIT → 提交/通过/驳回/重报，Service 手工映射） */
    @TableField(exist = false)
    private String actionLabel;
}
