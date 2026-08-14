package com.ruoyi.biz.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 合同履约节点对象 contract_node
 *
 * <p>字段分组：13 表列原貌（node_id/contract_id/node_name/node_type/plan_date/actual_date/status/del_flag/create_by/create_time/update_by/update_time/remark）
 * + V1.0.10 新增 1 列（voucher_url）= 14 表列；
 * 非表字段 overdue 由 XML 计算列返回（status='PENDING' AND plan_date &lt; TRUNC(SYSDATE)）。</p>
 *
 * <p>完成日期复用 existing actual_date（总纲写 finish_date，语义等价；V1.0.10 备案不迁移）。</p>
 *
 * @author kys
 * @date 2026-08-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("contract_node")
public class ContractNode extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 节点ID */
    @TableId(value = "node_id", type = IdType.AUTO)
    private Long nodeId;

    /** 合同ID */
    @TableField("contract_id")
    private Long contractId;

    /** 节点名称 */
    @Excel(name = "节点名称")
    @TableField("node_name")
    private String nodeName;

    /** 节点类型（字典 node_type：PAYMENT/DELIVERY/ACCEPTANCE） */
    @Excel(name = "节点类型", dictType = "node_type")
    @TableField("node_type")
    private String nodeType;

    /** 计划日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "计划日期", dateFormat = "yyyy-MM-dd")
    @TableField("plan_date")
    private Date planDate;

    /** 实际日期（finish 时写入；总纲 finish_date 语义等价，复用现有列） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "实际日期", dateFormat = "yyyy-MM-dd")
    @TableField("actual_date")
    private Date actualDate;

    /** 状态（字典 node_status：PENDING/DONE/OVERDUE，OVERDUE 阶段9 定时任务写入） */
    @Excel(name = "状态", dictType = "node_status", readConverterExp = "PENDING=待执行,DONE=已完成,OVERDUE=已逾期")
    @TableField("status")
    private String status;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== V1.0.10 新增 1 列 ======

    /** 完成凭证（验收单/发票，/common/upload 相对路径） */
    @TableField("voucher_url")
    private String voucherUrl;

    // ====== 视图关联字段（来自 XML 计算列，非 contract_node 字段） ======

    /** 是否逾期（status='PENDING' AND plan_date&lt;TRUNC(SYSDATE)），前端行标红用 */
    @TableField(exist = false)
    private Boolean overdue;
}