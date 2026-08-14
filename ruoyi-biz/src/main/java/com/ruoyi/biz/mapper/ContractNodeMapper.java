package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.ContractNode;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 合同履约节点 Mapper 接口
 *
 * @author kys
 * @date 2026-08-14
 */
public interface ContractNodeMapper extends BaseMapper<ContractNode> {

    /**
     * 节点列表（返回 overdue 计算列：status='PENDING' AND plan_date&lt;TRUNC(SYSDATE)）
     *
     * @param contractId 合同ID
     * @return 节点列表（含 overdue 标志）
     */
    List<ContractNode> selectNodeList(@Param("contractId") Long contractId);

    /**
     * 按合同逻辑删除全部有效节点（删除合同级联）
     */
    int softDeleteByContractId(@Param("contractId") Long contractId,
                               @Param("operName") String operName);

    /**
     * 批量逻辑删除
     */
    int softDeleteByIds(@Param("nodeIds") Long[] nodeIds,
                        @Param("operName") String operName);

    /**
     * 完成动作（置 status=DONE + actual_date + voucher_url，已 DONE 节点由 Service 拦截）
     */
    int finishNode(@Param("nodeId") Long nodeId,
                   @Param("actualDate") java.util.Date actualDate,
                   @Param("voucherUrl") String voucherUrl,
                   @Param("operName") String operName);
}