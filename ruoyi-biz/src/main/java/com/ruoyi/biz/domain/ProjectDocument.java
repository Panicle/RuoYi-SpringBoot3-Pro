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
import java.util.List;

/**
 * 课题资料对象 project_document
 *
 * <p>字段分组：13 表列原貌（doc_id/project_id/stage/file_name/file_url/upload_by/upload_time/del_flag/create_by/create_time/update_by/update_time/remark）
 * + V1.0.13 新增 2 列（submitter_id/plan_submit_date）= 15 表列；
 * 非表字段 projectNo/projectName/stageLabel/approvalStatus/approvalRound/rejectReason 用于列表 JOIN 展示与 status 筛选，
 * approval/historyList 用于详情携带当前审批与完整历史（审批状态唯一事实来源是 approval.status，决策 D1）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_document")
public class ProjectDocument extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 资料ID */
    @TableId(value = "doc_id", type = IdType.AUTO)
    private Long docId;

    /** 课题ID */
    @TableField("project_id")
    private Long projectId;

    /** 课题阶段（字典 project_stage：INITIATION/MIDTERM/CLOSING/REVIEW） */
    @TableField("stage")
    private String stage;

    /** 文件名称 */
    @TableField("file_name")
    private String fileName;

    /** 文件路径（/common/upload 相对路径） */
    @TableField("file_url")
    private String fileUrl;

    /** 上传人（upload_by 保留作冗余） */
    @TableField("upload_by")
    private String uploadBy;

    /** 上传时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("upload_time")
    private Date uploadTime;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== V1.0.13 新增 2 列 ======

    /** 提交人 user_id（发起审批时回填；upload_by 保留作冗余） */
    @TableField("submitter_id")
    private Long submitterId;

    /** 计划提交日期（预警引擎用，科管/室主任手动维护） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @TableField("plan_submit_date")
    private Date planSubmitDate;

    // ====== 视图关联字段（来自 project / approval JOIN，非 project_document 字段） ======

    /** 所属课题编号（来自 project.project_no，列表/详情展示） */
    @TableField(exist = false)
    private String projectNo;

    /** 所属课题名称（来自 project.project_name） */
    @TableField(exist = false)
    private String projectName;

    /** 课题阶段字典文本（来自字典 project_stage，Service 翻译） */
    @TableField(exist = false)
    private String stageLabel;

    /** 审批状态（来自 approval.status，列表展示/筛选；D1 唯一事实来源） */
    @TableField(exist = false)
    private String approvalStatus;

    /** 审批轮次（来自 approval.round） */
    @TableField(exist = false)
    private Integer approvalRound;

    /** 最近驳回原因（来自 approval.reject_reason） */
    @TableField(exist = false)
    private String rejectReason;

    /** 审批主键（来自 approval.approval_id；前端审批/历史按钮直接取用，省一次详情请求） */
    @TableField(exist = false)
    private Long approvalId;

    /** 提交人姓名（来自 sys_user.nick_name，JOIN submitter_id） */
    @TableField(exist = false)
    private String submitterName;

    // ====== 子资源（详情接口携带，非 project_document 字段） ======

    /** 当前审批（详情携带；一份资料至多一条有效审批，唯一索引 idx_approval_doc_id_uk 兜底） */
    @TableField(exist = false)
    private Approval approval;

    /** 审批历史列表（详情携带；按 round + operate_time 有序） */
    @TableField(exist = false)
    private List<ApprovalHistory> historyList;
}
