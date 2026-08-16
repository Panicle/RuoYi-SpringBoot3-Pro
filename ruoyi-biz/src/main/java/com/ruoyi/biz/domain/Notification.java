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
 * 通知对象 notification（阶段9 预警引擎：一条预警对应多名接收人各一条通知）
 *
 * <p>V1.0.16 起加 2 列：status(UNREAD/READ/CONFIRMED) + confirm_time；
 * is_read 保留兼容（V1.0.0 列），新代码用 status 判定读态（决策 D2）。
 * 通知按 (alert_id, receiver_id) 应用层查重（决策 D6），无唯一索引。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notification")
public class Notification extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 通知ID */
    @TableId(value = "notify_id", type = IdType.AUTO)
    private Long notifyId;

    /** 预警ID */
    @TableField("alert_id")
    private Long alertId;

    /** 接收人ID（关联 sys_user.user_id） */
    @TableField("receiver_id")
    private Long receiverId;

    /** 通知状态（UNREAD 未读 / READ 已读 / CONFIRMED 已确认） */
    @TableField("status")
    private String status;

    /** 是否已读（0未读 1已读）（兼容保留，新代码用 status） */
    @TableField("is_read")
    private Integer isRead;

    /** 阅读时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("read_time")
    private Date readTime;

    /** 确认时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("confirm_time")
    private Date confirmTime;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== 视图关联字段（来自 sys_user / alert JOIN，非 notification 字段） ======

    /** 接收人姓名（来自 sys_user.nick_name） */
    @TableField(exist = false)
    private String receiverName;

    /** 预警标题（来自 alert.title） */
    @TableField(exist = false)
    private String alertTitle;

    /** 预警类型（来自 alert.alert_type，前端角标/分类用） */
    @TableField(exist = false)
    private String alertType;

    /** 预警级别（来自 alert.alert_level，前端角标/分类用） */
    @TableField(exist = false)
    private String alertLevel;
}
