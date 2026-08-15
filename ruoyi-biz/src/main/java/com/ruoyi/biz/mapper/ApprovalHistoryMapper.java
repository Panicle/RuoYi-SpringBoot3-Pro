package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.ApprovalHistory;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 审批历史 Mapper 接口
 *
 * <p>每次状态流转同事务写一条历史（决策 D3）；历史按 round + operate_time 有序返回（决策 D4 时间线）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
public interface ApprovalHistoryMapper extends BaseMapper<ApprovalHistory> {

    /**
     * 按审批ID查询历史（JOIN sys_user 带 operatorName；按 round + operate_time + history_id 排序）
     *
     * @param approvalId 审批ID
     * @return 历史集合（含 operatorName）
     */
    List<ApprovalHistory> selectByApprovalId(@Param("approvalId") Long approvalId);

    /**
     * 逻辑删除指定审批的全部有效历史（删资料级联调用；del_flag='2'）
     *
     * @param approvalId 审批ID
     * @param updateBy   操作人
     * @return 影响行数
     */
    int softDeleteByApprovalId(@Param("approvalId") Long approvalId, @Param("updateBy") String updateBy);
}
