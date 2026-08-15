package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.Alert;
import com.ruoyi.biz.domain.vo.AlertScanCandidate;
import com.ruoyi.common.annotation.DataScope;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 预警 Mapper 接口（阶段9 预警引擎三类：合同节点 CONTRACT / 经费超限 BUDGET / 资料逾期 DOCUMENT）
 *
 * <p>数据权限 D10：预警本身没有部门/负责人列，权限沿 ref 对象 → project 关联链判定。
 * 通用列表走 @DataScope(deptAlias="d", userAlias="u")，researcher 走本人相关专用分支——
 * 与 expense/contract/honor 双通道完全一致，避免越权。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
public interface AlertMapper extends BaseMapper<Alert> {

    /**
     * 经费预警列表（旧流程遗留，alert_type='BUDGET'；projectId 非空时限定该课题）。
     * researcher(data_scope=5) 不走此方法，Service 走"本人相关"专用分支。
     */
    @DataScope(deptAlias = "d", userAlias = "u")
    List<Alert> selectBudgetAlertList(Alert query);

    /**
     * 经费预警列表（researcher 专用）：仅返回 ref_id 所属课题的 leader_id = 当前用户
     * OR 课题成员表中含当前用户的预警
     */
    List<Alert> selectBudgetAlertListForResearcher(Alert query);

    /**
     * 幂等去重计数（§4.3）：同 (alert_type='BUDGET', ref_id) 且 status ∈ (UNREAD, READ) 的未处理预警条数
     *
     * @param refId budget_split.split_id
     * @return 未处理预警条数，> 0 表示不应重复写入
     */
    int countPendingBudgetAlert(@Param("refId") Long refId);

    // ========================================================
    //  阶段9 通用预警列表 / 详情（D10 双通道）
    // ========================================================

    /**
     * 通用预警列表（全部 alert_type；筛选 alertType/alertLevel/status/refType）。
     * 权限沿 ref 对象（合同节点/课题/资料）→ project 关联链走 @DataScope，researcher 不走此方法。
     */
    @DataScope(deptAlias = "d", userAlias = "u")
    List<Alert> selectAlertList(Alert query);

    /**
     * 通用预警列表（researcher 专用）：仅返回 ref 对象所属课题 leader_id = 当前用户
     * OR 课题成员表中含当前用户的预警
     */
    List<Alert> selectAlertListForResearcher(Alert query);

    /**
     * 通用预警详情（按 alert_id；带 refName 解析）。走 @DataScope 通道（其他角色），researcher 走 ForResearcher 分支。
     *
     * @param probe 承载 alertId + @DataScope 注入的 params.dataScope
     */
    @DataScope(deptAlias = "d", userAlias = "u")
    Alert selectAlertById(Alert probe);

    /**
     * 通用预警详情（researcher 专用）：ref 对象所属课题 leader_id = me OR 成员含 me
     */
    Alert selectAlertByIdForResearcher(@Param("alertId") Long alertId, @Param("selfUserId") Long selfUserId);

    // ========================================================
    //  D3 biz_key 幂等 upsert 辅助
    // ========================================================

    /**
     * 查询同 biz_key 且 status='OPEN'（未消除）的现有预警行（幂等 upsert 判定用）
     *
     * @param bizKey 业务唯一键（CONTRACT:node:{id}/BUDGET:project:{id}/DOCUMENT:doc:{id}）
     * @return 现有 OPEN 行；不存在返回 null
     */
    Alert selectOpenByBizKey(@Param("bizKey") String bizKey);

    /**
     * 查询同 biz_key 的历史最大 round（含 RESOLVED 保留行；不存在返回 0）
     *
     * @param bizKey 业务唯一键
     * @return 历史最大轮次，无记录为 0
     */
    Integer selectMaxRoundByBizKey(@Param("bizKey") String bizKey);

    /**
     * 幂等命中后仅更新 last_time（D3：不新增、不重复通知）
     */
    int updateLastTime(@Param("alertId") Long alertId, @Param("lastTime") Date lastTime, @Param("updateBy") String updateBy);

    /**
     * 消除（D7）：status→RESOLVED + remark 记操作人；仅对 OPEN 行生效（已消除则 0 行）
     */
    int resolveAlert(@Param("alertId") Long alertId, @Param("remark") String remark, @Param("updateBy") String updateBy);

    // ========================================================
    //  D5 三类扫描候选查询（返回组装 Alert 所需的原始业务数据）
    // ========================================================

    /**
     * 扫描① 合同节点临近：contract_node.status='PENDING' AND del_flag='0' AND plan_date ∈ [today, today+15]
     */
    List<AlertScanCandidate> selectContractNodeCandidates();

    /**
     * 扫描② 经费超限：project.del_flag='0' AND (budget_balance ≤ 1000 OR (budget_total>0 AND budget_balance/budget_total ≤ 0.05))
     */
    List<AlertScanCandidate> selectBudgetOverrunCandidates();

    /**
     * 扫描③ 资料逾期：project_document.del_flag='0' AND plan_submit_date &lt; today
     * AND (无 approval 或 approval.status='PENDING' 且 del_flag='0')
     */
    List<AlertScanCandidate> selectOverdueDocumentCandidates();
}
