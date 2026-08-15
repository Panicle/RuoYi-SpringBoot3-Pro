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
 * 审批对象 approval
 *
 * <p>字段分组：13 表列原貌（approval_id/doc_id/applicant_id/approver_id/status/comment_text/del_flag/create_by/create_time/update_by/update_time/finish_time/remark）
 * + V1.0.13 新增 2 列（round/reject_reason）= 15 表列；
 * 非表字段 docFileName/projectName/statusLabel 用于列表 JOIN 展示与字典翻译。</p>
 *
 * <p>审批状态唯一事实来源（决策 D1）；单级审批（决策 D4）；comment_text 复用为审批意见（决策 D5）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("approval")
public class Approval extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 审批ID */
    @TableId(value = "approval_id", type = IdType.AUTO)
    private Long approvalId;

    /** 关联资料ID */
    @TableField("doc_id")
    private Long docId;

    /** 申请人ID */
    @TableField("applicant_id")
    private Long applicantId;

    /** 审批人ID */
    @TableField("approver_id")
    private Long approverId;

    /** 审批状态（字典 approval_status：PENDING/APPROVED/REJECTED） */
    @TableField("status")
    private String status;

    /** 审批意见（comment 为达梦保留字，故用 comment_text） */
    @TableField("comment_text")
    private String commentText;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    /** 完成时间（APPROVE/REJECT 时回填，复用作审批时间） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("finish_time")
    private Date finishTime;

    // ====== V1.0.13 新增 2 列 ======

    /** 审批轮次（驳回重报 +1；发起时=1） */
    @TableField("round")
    private Integer round;

    /** 最近驳回原因（REJECT 时回填；重报时清空） */
    @TableField("reject_reason")
    private String rejectReason;

    // ====== 视图关联字段（来自 project_document / project JOIN，非 approval 字段） ======

    /** 关联资料文件名（来自 project_document.file_name） */
    @TableField(exist = false)
    private String docFileName;

    /** 所属课题名称（来自 project.project_name） */
    @TableField(exist = false)
    private String projectName;

    /** 审批状态字典文本（来自字典 approval_status，Service 翻译） */
    @TableField(exist = false)
    private String statusLabel;
}
