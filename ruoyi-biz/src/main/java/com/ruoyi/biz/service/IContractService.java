package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.Contract;
import com.ruoyi.biz.domain.ContractNode;

import java.util.Date;
import java.util.List;

/**
 * 合同 Service 接口
 *
 * <p>数据权限照课题模式：列表 researcher 走专用 SQL，其他角色走 @DataScope 注解；
 * 详情/写操作先过 scoped projectService.selectProjectById(contract.projectId) 闸门（抛"无权访问"）。</p>
 *
 * @author kys
 * @date 2026-08-14
 */
public interface IContractService {

    /**
     * 查询合同列表（已注入数据范围；researcher 走"本人相关"专用分支）
     */
    List<Contract> selectContractList(Contract query);

    /**
     * 详情查询（强制数据范围校验；不在范围内抛 ServiceException）
     * 携带 nodeList 履约节点列表。
     */
    Contract selectContractById(Long contractId);

    /**
     * 新增合同（contractNo 必填查重；project 存在且过闸门；party 二选一校验）
     */
    Contract insertContract(Contract contract, String operName);

    /**
     * 修改合同（contractNo 以库为准不可改；party 二选一重校验；status 只允许 ACTIVE/EXPIRED/TERMINATED）
     */
    int updateContract(Contract contract, String operName);

    /**
     * 逻辑删除（级联逻辑删全部有效节点，同事务）
     */
    int deleteContractByIds(Long[] contractIds, String operName);

    /**
     * Excel 导出（复用列表查询条件 + 数据范围）
     */
    List<Contract> exportContract(Contract query);

    /**
     * 节点列表（先过合同→课题闸门）
     */
    List<ContractNode> selectNodeList(Long contractId);

    /**
     * 新增节点（node_name/node_type/plan_date 必填，status 强制 PENDING）
     */
    int insertNode(ContractNode node, String operName);

    /**
     * 修改节点（DONE 节点仅允许改 remark/voucherUrl，其余字段以库为准）
     */
    int updateNode(ContractNode node, String operName);

    /**
     * 完成动作（已 DONE 节点拒）
     */
    int finishNode(Long nodeId, Date actualDate, String voucherUrl, String operName);

    /**
     * 批量逻辑删除节点
     */
    int deleteNodeByIds(Long[] nodeIds, String operName);
}