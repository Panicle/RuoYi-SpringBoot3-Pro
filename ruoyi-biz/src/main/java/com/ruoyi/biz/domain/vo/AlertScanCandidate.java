package com.ruoyi.biz.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 预警扫描候选对象（AlertScanService 三类扫描的候选行载体）
 *
 * <p>由 AlertMapper.xml 三个扫描 SELECT 返回（JOIN 合同/课题/资料/审批解析出的原始业务数据），
 * AlertScanService 据此组装 Alert（title/content/biz_key/round 等）并做幂等 upsert 与通知派生。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
public class AlertScanCandidate {

    /** 关联业务对象类型（CONTRACT / PROJECT / DOCUMENT） */
    private String refType;

    /** 关联对象ID（节点ID / 课题ID / 资料ID） */
    private Long refId;

    /** 所属课题ID */
    private Long projectId;

    /** 课题负责人ID（通知接收人派生） */
    private Long leaderId;

    // ====== 合同节点扫描（refType=CONTRACT） ======

    /** 节点名称 */
    private String nodeName;

    /** 合同名称 */
    private String contractName;

    /** 节点计划日期 */
    private Date planDate;

    // ====== 经费超限扫描（refType=PROJECT） ======

    /** 课题编号 */
    private String projectNo;

    /** 课题名称 */
    private String projectName;

    /** 预算总额 */
    private BigDecimal budgetTotal;

    /** 预算余额 */
    private BigDecimal budgetBalance;

    // ====== 资料逾期扫描（refType=DOCUMENT） ======

    /** 资料文件名 */
    private String fileName;

    /** 计划提交日期 */
    private Date planSubmitDate;
}
