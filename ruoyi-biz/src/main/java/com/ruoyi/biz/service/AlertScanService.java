package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.Alert;
import com.ruoyi.biz.domain.Notification;
import com.ruoyi.biz.domain.vo.AlertScanCandidate;
import com.ruoyi.biz.mapper.AlertMapper;
import com.ruoyi.biz.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 预警扫描服务（阶段9：三类扫描 + biz_key 幂等 upsert + 通知派生）
 *
 * <p>决策对齐：D3 biz_key 幂等 upsert（同 biz_key 存在 OPEN 行只更新 last_time；否则历史最大 round+1 新建，
 * first_time=last_time=now，不建唯一索引）；D4 biz_key 定义逐字；D5 三类扫描规则逐字；D6 通知接收人 =
 * {project.leader_id} ∪ {全部 science_admin(role_key) + admin(user_id=1)}，按 (alert_id, receiver_id)
 * 应用层查重；同事务（alert 生成 + notification 生成）。</p>
 *
 * <p>Quartz 任务（Task 3）调用 {@link #scanAll()} 触发；单方法含三小扫描，测试可直接调各 scan* 方法。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Service
@RequiredArgsConstructor
public class AlertScanService {

    private static final Logger log = LoggerFactory.getLogger(AlertScanService.class);

    // ====== 状态 / 类型（字典对齐） ======

    private static final String ALERT_STATUS_OPEN     = "OPEN";
    private static final String ALERT_STATUS_RESOLVED = "RESOLVED";
    private static final String NOTIFY_STATUS_UNREAD  = "UNREAD";

    private static final String ALERT_TYPE_CONTRACT = "CONTRACT";
    private static final String ALERT_TYPE_BUDGET   = "BUDGET";
    private static final String ALERT_TYPE_DOCUMENT = "DOCUMENT";

    private static final String REF_TYPE_CONTRACT = "CONTRACT";
    private static final String REF_TYPE_PROJECT  = "PROJECT";
    private static final String REF_TYPE_DOCUMENT = "DOCUMENT";

    // ====== 扫描参数（与任务卡 D5 一致） ======

    /** 合同节点临近窗口：plan_date ∈ [today, today+15] */
    private static final int NODE_NEAR_DAYS = 15;
    /** 经费超限阈值：余额 ≤ 1000 元 或 余额率 ≤ 5% */
    private static final BigDecimal BUDGET_ABS_THRESHOLD = new BigDecimal("1000");
    private static final BigDecimal BUDGET_RATIO_THRESHOLD = new BigDecimal("0.05");

    /** 扫描操作人（Quartz 无登录态，固定 system） */
    private static final String SCAN_OPERATOR = "system";

    private final AlertMapper alertMapper;
    private final NotificationMapper notificationMapper;

    /**
     * 全量扫描（D5）：合同节点 / 经费超限 / 资料逾期 三小方法，同事务。
     *
     * @return 本次扫描新建的 alert 数（幂等命中仅刷新 last_time 的不计入）
     */
    @Transactional(rollbackFor = Exception.class)
    public int scanAll() {
        int created = 0;
        created += scanContractNodes();
        created += scanBudgetOverrun();
        created += scanOverdueDocuments();
        return created;
    }

    // ========================================================
    //  三类扫描（D5）
    // ========================================================

    /**
     * 扫描① 合同节点临近：node.status='PENDING' AND plan_date ∈ [today, today+15]
     * biz_key = CONTRACT:node:{node_id}，ref_type='CONTRACT'，title"合同节点临近"，
     * 通知课题 leader（contract→project→leader_id）。
     *
     * @return 本次新建的 alert 数
     */
    @Transactional(rollbackFor = Exception.class)
    public int scanContractNodes() {
        int created = 0;
        List<AlertScanCandidate> cands = alertMapper.selectContractNodeCandidates();
        for (AlertScanCandidate cand : cands) {
            if (cand == null || cand.getRefId() == null) {
                continue;
            }
            String bizKey = "CONTRACT:node:" + cand.getRefId();
            String title = "合同节点临近";
            String content = "合同[" + nz(cand.getContractName()) + "]节点[" + nz(cand.getNodeName())
                    + "]计划日期" + fmtDate(cand.getPlanDate()) + "临近，请及时办理";
            if (upsertAlertAndNotify(cand, ALERT_TYPE_CONTRACT, REF_TYPE_CONTRACT, bizKey, title, content)) {
                created++;
            }
        }
        log.info("合同节点扫描完成：{} 条候选，{} 条新建", cands.size(), created);
        return created;
    }

    /**
     * 扫描② 经费超限：project.del_flag='0' AND (budget_balance ≤ 1000 OR (budget_total>0 AND balance/total ≤ 0.05))
     * biz_key = BUDGET:project:{project_id}，ref_type='PROJECT'，通知 project.leader_id。
     *
     * @return 本次新建的 alert 数
     */
    @Transactional(rollbackFor = Exception.class)
    public int scanBudgetOverrun() {
        int created = 0;
        List<AlertScanCandidate> cands = alertMapper.selectBudgetOverrunCandidates();
        for (AlertScanCandidate cand : cands) {
            if (cand == null || cand.getRefId() == null) {
                continue;
            }
            String bizKey = "BUDGET:project:" + cand.getRefId();
            String title = "经费超限";
            String content = "课题[" + nz(cand.getProjectNo()) + "/" + nz(cand.getProjectName())
                    + "]余额 " + scale(nz(cand.getBudgetBalance())).toPlainString()
                    + " 元 / 预算 " + scale(nz(cand.getBudgetTotal())).toPlainString()
                    + " 元（余额过低，请及时处理）";
            if (upsertAlertAndNotify(cand, ALERT_TYPE_BUDGET, REF_TYPE_PROJECT, bizKey, title, content)) {
                created++;
            }
        }
        log.info("经费超限扫描完成：{} 条候选，{} 条新建", cands.size(), created);
        return created;
    }

    /**
     * 扫描③ 资料逾期：project_document.del_flag='0' AND plan_submit_date &lt; today
     * AND (无 approval 或 approval.status='PENDING' 且 del_flag='0')
     * biz_key = DOCUMENT:doc:{doc_id}，ref_type='DOCUMENT'，通知 project.leader_id。
     *
     * @return 本次新建的 alert 数
     */
    @Transactional(rollbackFor = Exception.class)
    public int scanOverdueDocuments() {
        int created = 0;
        List<AlertScanCandidate> cands = alertMapper.selectOverdueDocumentCandidates();
        for (AlertScanCandidate cand : cands) {
            if (cand == null || cand.getRefId() == null) {
                continue;
            }
            String bizKey = "DOCUMENT:doc:" + cand.getRefId();
            String title = "资料逾期";
            String content = "课题[" + nz(cand.getProjectNo()) + "/" + nz(cand.getProjectName())
                    + "]资料[" + nz(cand.getFileName()) + "]计划提交日期 "
                    + fmtDate(cand.getPlanSubmitDate()) + " 已逾期，请及时提交审批";
            if (upsertAlertAndNotify(cand, ALERT_TYPE_DOCUMENT, REF_TYPE_DOCUMENT, bizKey, title, content)) {
                created++;
            }
        }
        log.info("资料逾期扫描完成：{} 条候选，{} 条新建", cands.size(), created);
        return created;
    }

    // ========================================================
    //  D3 幂等 upsert + D6 通知派生（同事务）
    // ========================================================

    /**
     * biz_key 幂等 upsert（D3）：同 biz_key 存在 OPEN 行 → 只更新 last_time（不新增、不重复通知）；
     * 否则查历史最大 round+1 INSERT 新 OPEN 行（first_time=last_time=now），随后派生通知。
     *
     * @return true=本次新建 alert（含通知派生）；false=幂等命中仅刷新 last_time
     */
    private boolean upsertAlertAndNotify(AlertScanCandidate cand, String alertType, String refType,
                                         String bizKey, String title, String content) {
        Alert existing = alertMapper.selectOpenByBizKey(bizKey);
        if (existing != null) {
            // D3：命中未消除行，仅刷新 last_time
            alertMapper.updateLastTime(existing.getAlertId(), new Date(), SCAN_OPERATOR);
            return false;
        }
        Integer maxRound = alertMapper.selectMaxRoundByBizKey(bizKey);
        int round = (maxRound == null ? 0 : maxRound) + 1;

        Date now = new Date();
        Alert alert = new Alert();
        alert.setAlertType(alertType);
        alert.setRefId(cand.getRefId());
        alert.setRefType(refType);
        alert.setAlertLevel(resolveLevel(alertType));
        alert.setTitle(title);
        alert.setContent(content);
        alert.setStatus(ALERT_STATUS_OPEN);
        alert.setBizKey(bizKey);
        alert.setRound(round);
        alert.setFirstTime(now);
        alert.setLastTime(now);
        alert.setDelFlag("0");
        alert.setCreateBy(SCAN_OPERATOR);
        alertMapper.insert(alert);

        // D6 通知派生（复用扫描候选中的 project.leader_id）
        deriveNotifications(alert.getAlertId(), cand.getLeaderId());
        return true;
    }

    /**
     * 通知接收人派生（D6）：{project.leader_id} ∪ {全部 science_admin(role_key) + admin(user_id=1)}，
     * 按 (alert_id, receiver_id) 应用层查重（已存在则不重复 INSERT），status='UNREAD'。
     */
    private void deriveNotifications(Long alertId, Long projectLeaderId) {
        Set<Long> receivers = new LinkedHashSet<>();
        if (projectLeaderId != null) {
            receivers.add(projectLeaderId);
        }
        receivers.addAll(notificationMapper.selectAdminReceiverUserIds());
        for (Long receiverId : receivers) {
            if (receiverId == null) {
                continue;
            }
            if (notificationMapper.countByAlertAndReceiver(alertId, receiverId) > 0) {
                continue;   // 应用层查重
            }
            Notification n = new Notification();
            n.setAlertId(alertId);
            n.setReceiverId(receiverId);
            n.setStatus(NOTIFY_STATUS_UNREAD);
            n.setIsRead(0);
            n.setDelFlag("0");
            n.setCreateBy(SCAN_OPERATOR);
            notificationMapper.insert(n);
        }
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /** 预警级别：合同节点/资料逾期 WARN，经费超限 CRITICAL（资金风险最紧迫） */
    private String resolveLevel(String alertType) {
        return ALERT_TYPE_BUDGET.equals(alertType) ? "CRITICAL" : "WARN";
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    /** 日期 → yyyy-MM-dd（null 返回"-"） */
    private static String fmtDate(Date d) {
        if (d == null) {
            return "-";
        }
        return new java.text.SimpleDateFormat("yyyy-MM-dd").format(d);
    }
}
