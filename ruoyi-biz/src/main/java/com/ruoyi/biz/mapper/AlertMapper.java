package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.Alert;
import com.ruoyi.common.annotation.DataScope;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 预警 Mapper 接口（本期仅经费预警 alert_type='BUDGET'，ref_id = budget_split.split_id）
 *
 * <p>数据权限：预警本身没有部门/负责人列，权限沿 ref_id → budget_split → project 关联链判定。
 * 列表走 @DataScope(deptAlias="d", userAlias="u")，researcher 走本人相关专用分支——
 * 与 expense/contract 双通道完全一致，避免"能看到别人课题预警"的越权。</p>
 *
 * @author kys
 * @date 2026-08-14
 */
public interface AlertMapper extends BaseMapper<Alert> {

    /**
     * 经费预警列表（alert_type='BUDGET'；projectId 非空时限定该课题）。
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
}
