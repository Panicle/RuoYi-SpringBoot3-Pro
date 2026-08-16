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
 * 预警对象 alert（阶段9 预警引擎三类：合同节点 CONTRACT / 经费超限 BUDGET / 资料逾期 DOCUMENT）
 *
 * <p>V1.0.16 起加 5 列：ref_type(关联业务对象类型)/biz_key(业务唯一键，幂等去重)/round(触发轮次)/
 * first_time(首次生成时间)/last_time(最近命中扫描时间)；status 语义 UNREAD/READ/HANDLED → OPEN/RESOLVED
 * （决策 D1）。幂等 upsert 决策 D3：同 biz_key 存在 OPEN 行只更新 last_time，不建唯一索引。</p>
 *
 * @author kys
 * @date 2026-08-15
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

    /** 关联对象ID（合同节点=contract_node.node_id；经费超限=project.project_id；资料逾期=project_document.doc_id） */
    @TableField("ref_id")
    private Long refId;

    /** 关联业务对象类型（CONTRACT/PROJECT/DOCUMENT；经费超限关联课题故为 PROJECT） */
    @TableField("ref_type")
    private String refType;

    /** 预警级别（字典 alert_level：INFO/WARN/CRITICAL） */
    @TableField("alert_level")
    private String alertLevel;

    /** 预警标题 */
    @TableField("title")
    private String title;

    /** 预警内容 */
    @TableField("content")
    private String content;

    /** 状态（OPEN 生效 / RESOLVED 已消除） */
    @TableField("status")
    private String status;

    /** 业务唯一键（决策 D4：CONTRACT:node:{node_id} / BUDGET:project:{project_id} / DOCUMENT:doc:{doc_id}，幂等去重用） */
    @TableField("biz_key")
    private String bizKey;

    /** 触发轮次（同 biz_key 每次重新触发 +1，首次 1） */
    @TableField("round")
    private Integer round;

    /** 首次生成时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("first_time")
    private Date firstTime;

    /** 最近命中扫描时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("last_time")
    private Date lastTime;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== 视图关联字段（来自 ref 对象 → project JOIN，非 alert 字段） ======

    /** 关联对象名称（refName，按 ref_type 解析：CONTRACT=节点名 / PROJECT=课题名 / DOCUMENT=资料文件名） */
    @TableField(exist = false)
    private String refName;

    /** 关联课题负责人ID（扫描通知接收人派生用） */
    @TableField(exist = false)
    private Long leaderId;

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
     *  显式声明仅为避免下游误用 status 原值（OPEN/RESOLVED）做展示——
     *  前端按业务需要取 statusLabel 或直接用 status 原始值均可 */
    @TableField(exist = false)
    private String statusLabel;
}
