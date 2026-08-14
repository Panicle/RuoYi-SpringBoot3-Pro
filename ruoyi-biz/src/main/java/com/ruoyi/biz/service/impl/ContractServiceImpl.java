package com.ruoyi.biz.service.impl;

import com.ruoyi.biz.domain.CooperativeUnit;
import com.ruoyi.biz.domain.Contract;
import com.ruoyi.biz.domain.ContractNode;
import com.ruoyi.biz.mapper.CooperativeUnitMapper;
import com.ruoyi.biz.mapper.ContractMapper;
import com.ruoyi.biz.mapper.ContractNodeMapper;
import com.ruoyi.biz.service.IContractService;
import com.ruoyi.biz.service.IProjectService;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * 合同 Service 实现
 *
 * <p>数据权限照 ProjectServiceImpl：列表 researcher 走专用 SQL（p.leader_id=me OR member 含 me），
 * 其他角色走 @DataScope 注解；详情/写操作过 scoped projectService.selectProjectById 闸门（抛"无权访问"）；
 * 节点操作先查合同再过闸门。</p>
 *
 * <p>party 二选一校验：partyUnitId 非空→查单位存在且 del_flag='0'，强制 partyName=单位名快照；
 * 否则 partyName 必填（前后端契约修正 Task 3 审查新增）。</p>
 *
 * <p>party/附件显式清空：updateContract 解析入参为确定终值（party 二选一校验后）+ 写入 partyUnitId/partyName/fileUrl；
 * Domain 加 @TableField(updateStrategy=FieldStrategy.ALWAYS) 让 MyBatis-Plus 不忽略 null。</p>
 *
 * @author kys
 * @date 2026-08-14
 */
@Service
@RequiredArgsConstructor
public class ContractServiceImpl implements IContractService {

    private static final Logger log = LoggerFactory.getLogger(ContractServiceImpl.class);

    /** 合同状态（与字典 contract_status 一致） */
    private static final String STATUS_ACTIVE     = "ACTIVE";
    private static final String STATUS_EXPIRED    = "EXPIRED";
    private static final String STATUS_TERMINATED = "TERMINATED";
    private static final Set<String> ALLOWED_STATUSES = new HashSet<>(Arrays.asList(STATUS_ACTIVE, STATUS_EXPIRED, STATUS_TERMINATED));

    /** 节点状态（与字典 node_status 一致） */
    private static final String NODE_STATUS_PENDING = "PENDING";
    private static final String NODE_STATUS_DONE    = "DONE";

    private final ContractMapper contractMapper;
    private final ContractNodeMapper contractNodeMapper;
    private final CooperativeUnitMapper cooperativeUnitMapper;
    private final IProjectService projectService;

    // ========================================================
    //  列表 / 详情（数据范围）
    // ========================================================

    @Override
    public List<Contract> selectContractList(Contract query) {
        if (query == null) {
            query = new Contract();
        }
        // researcher (data_scope=5) 不走 @DataScope，Service 内按角色硬分支：走"本人相关"专用 SQL
        if (isResearcher()) {
            Long me = SecurityUtils.getUserId();
            query.getParams().put("selfUserId", me);
            return contractMapper.selectContractListForResearcher(query);
        }
        return contractMapper.selectContractList(query);
    }

    @Override
    public Contract selectContractById(Long contractId) {
        if (contractId == null) {
            throw new ServiceException("contractId 不能为空");
        }
        Contract c = contractMapper.selectContractById(contractId);
        if (c == null) {
            // 不暴露是否存在信息，统一友好提示（与 ProjectServiceImpl 一致）
            throw new ServiceException("无权访问");
        }
        // 过闸门：所属课题不在数据范围内抛"无权访问"
        projectService.selectProjectById(c.getProjectId());
        // 携带节点列表（详情用）
        c.setNodeList(contractNodeMapper.selectNodeList(contractId));
        return c;
    }

    // ========================================================
    //  新增（contract_no 必填查重 + party 二选一校验）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Contract insertContract(Contract contract, String operName) {
        if (contract == null) {
            throw new ServiceException("参数为空");
        }
        if (contract.getProjectId() == null) {
            throw new ServiceException("所属课题不能为空");
        }
        if (StringUtils.isEmpty(contract.getContractName())) {
            throw new ServiceException("合同名称不能为空");
        }
        if (StringUtils.isEmpty(contract.getContractNo())) {
            throw new ServiceException("合同编号不能为空");
        }
        // 1. 过闸门：所属课题必须存在且在数据范围内（researcher 走本人相关分支）
        projectService.selectProjectById(contract.getProjectId());

        // 2. contract_no 查重（含软删行，与 DB 唯一索引 idx_contract_no_uk 兜底一致）
        if (contractMapper.selectByContractNo(contract.getContractNo()) != null) {
            throw new ServiceException("合同编号已存在");
        }

        // 3. party 二选一：选单位→partyUnitId+快照名；手填→partyUnitId=null+partyName=入参
        resolveParty(contract, true);

        // 4. status 默认 ACTIVE；delFlag 显式置 "0"（三层保险之一）
        if (StringUtils.isEmpty(contract.getStatus())) {
            contract.setStatus(STATUS_ACTIVE);
        } else if (!ALLOWED_STATUSES.contains(contract.getStatus())) {
            throw new ServiceException("合同状态不合法：" + contract.getStatus());
        }
        contract.setDelFlag("0");
        contract.setCreateBy(operName);

        // 5. INSERT 主表（contract_no 唯一性由 DB 唯一索引兜底）
        try {
            contractMapper.insert(contract);
        } catch (DuplicateKeyException e) {
            // 极端：DB 唯一索引兜底（软删行已被 Service 拦下，但理论上仍有极小并发窗口）
            throw new ServiceException("合同编号已存在");
        }
        return contract;
    }

    // ========================================================
    //  修改（contract_no 以库为准不可改；party 二选一重校验；status 三值）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateContract(Contract contract, String operName) {
        if (contract == null || contract.getContractId() == null) {
            throw new ServiceException("contractId 不能为空");
        }
        // 过闸门
        selectContractById(contract.getContractId());
        Contract db = contractMapper.selectContractById(contract.getContractId());
        if (db == null) {
            throw new ServiceException("合同不存在");
        }
        // 强制以库原值为准：contractNo / projectId 不可改
        contract.setContractNo(db.getContractNo());
        contract.setProjectId(db.getProjectId());
        // status 三值校验
        if (StringUtils.isNotEmpty(contract.getStatus()) && !ALLOWED_STATUSES.contains(contract.getStatus())) {
            throw new ServiceException("合同状态不合法：" + contract.getStatus());
        }
        // party 二选一重校验（与 insert 同源逻辑；resolveParty 内部对单位存在性、del_flag 校验）
        resolveParty(contract, false);
        // 前后端契约：partyUnitId / partyName / fileUrl 三列在 Domain 已 IGNORED，
        // 必须保证入参被解析为确定终值后再写。resolveParty 已处理 partyUnitId/partyName；
        // fileUrl 走 Domain 的 updateStrategy=IGNORED：null 写入即清空，非 null 即覆盖。
        contract.setUpdateBy(operName);
        return contractMapper.updateById(contract);
    }

    // ========================================================
    //  删除（逻辑删；级联逻辑删全部有效节点，同事务）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteContractByIds(Long[] contractIds, String operName) {
        if (contractIds == null || contractIds.length == 0) {
            return 0;
        }
        for (Long cid : contractIds) {
            // 每个合同过闸门
            selectContractById(cid);
            // 级联逻辑删除全部有效节点（同事务；同事务内必走）
            contractNodeMapper.softDeleteByContractId(cid, operName);
        }
        // 主表走 BaseMapper.deleteByIds（@TableLogic 自动改写 del_flag='2'）
        return contractMapper.deleteByIds(Arrays.asList(contractIds));
    }

    // ========================================================
    //  导出
    // ========================================================

    @Override
    public List<Contract> exportContract(Contract query) {
        return selectContractList(query);
    }

    // ========================================================
    //  履约节点（先查合同过闸门，再操作节点）
    // ========================================================

    @Override
    @Transactional(readOnly = true)
    public List<ContractNode> selectNodeList(Long contractId) {
        if (contractId == null) {
            throw new ServiceException("contractId 不能为空");
        }
        // 先查合同过闸门（无权访问抛"无权访问"）
        selectContractById(contractId);
        return contractNodeMapper.selectNodeList(contractId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertNode(ContractNode node, String operName) {
        if (node == null || node.getContractId() == null) {
            throw new ServiceException("contractId 不能为空");
        }
        // 先查合同过闸门
        selectContractById(node.getContractId());
        if (StringUtils.isEmpty(node.getNodeName())) {
            throw new ServiceException("节点名称不能为空");
        }
        if (StringUtils.isEmpty(node.getNodeType())) {
            throw new ServiceException("节点类型不能为空");
        }
        if (node.getPlanDate() == null) {
            throw new ServiceException("计划日期不能为空");
        }
        // status 强制 PENDING（新增即待执行）
        node.setStatus(NODE_STATUS_PENDING);
        node.setDelFlag("0");
        node.setCreateBy(operName);
        return contractNodeMapper.insert(node);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateNode(ContractNode node, String operName) {
        if (node == null || node.getNodeId() == null) {
            throw new ServiceException("nodeId 不能为空");
        }
        ContractNode db = contractNodeMapper.selectById(node.getNodeId());
        if (db == null) {
            throw new ServiceException("节点不存在或已删除");
        }
        // 先查合同过闸门（无权访问抛"无权访问"）
        selectContractById(db.getContractId());
        // DONE 节点仅允许改 remark/voucherUrl，其余字段以库为准
        if (NODE_STATUS_DONE.equals(db.getStatus())) {
            node.setNodeName(db.getNodeName());
            node.setNodeType(db.getNodeType());
            node.setPlanDate(db.getPlanDate());
            node.setActualDate(db.getActualDate());
            node.setStatus(db.getStatus());
            node.setContractId(db.getContractId());
        } else {
            // 非 DONE：contractId 以库原值为准（不允许改挂其他合同）
            node.setContractId(db.getContractId());
            node.setActualDate(db.getActualDate());
            node.setStatus(db.getStatus());
        }
        node.setUpdateBy(operName);
        return contractNodeMapper.updateById(node);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int finishNode(Long nodeId, Date actualDate, String voucherUrl, String operName) {
        if (nodeId == null) {
            throw new ServiceException("nodeId 不能为空");
        }
        if (actualDate == null) {
            throw new ServiceException("实际日期不能为空");
        }
        ContractNode db = contractNodeMapper.selectById(nodeId);
        if (db == null) {
            throw new ServiceException("节点不存在或已删除");
        }
        // 先查合同过闸门
        selectContractById(db.getContractId());
        // 已 DONE 拒
        if (NODE_STATUS_DONE.equals(db.getStatus())) {
            throw new ServiceException("节点已完成，不可重复完成");
        }
        // finishNode SQL 自带 status != 'DONE' + del_flag='0' 兜底；affects==0 表示被并发改 DONE
        int n = contractNodeMapper.finishNode(nodeId, actualDate, voucherUrl, operName);
        if (n == 0) {
            throw new ServiceException("节点状态已变更，请刷新后重试");
        }
        return n;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteNodeByIds(Long[] nodeIds, String operName) {
        if (nodeIds == null || nodeIds.length == 0) {
            return 0;
        }
        // 每个节点所属合同需过闸门
        java.util.Set<Long> checkedContracts = new java.util.HashSet<>();
        for (Long nid : nodeIds) {
            ContractNode db = contractNodeMapper.selectById(nid);
            if (db == null) {
                throw new ServiceException("节点[" + nid + "]不存在或已删除");
            }
            if (checkedContracts.add(db.getContractId())) {
                selectContractById(db.getContractId());
            }
        }
        return contractNodeMapper.softDeleteByIds(nodeIds, operName);
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /**
     * party 二选一校验：partyUnitId 非空→查单位存在且 del_flag='0'，强制 partyName=单位名快照；
     * 否则 partyName 必填。insert 时 partyUnitId 入参为 null 也走"手填"分支（视作未选单位）。
     */
    private void resolveParty(Contract contract, boolean isInsert) {
        if (contract == null) {
            return;
        }
        Long partyUnitId = contract.getPartyUnitId();
        if (partyUnitId != null) {
            // 选单位：校验存在且未删，强制 partyName=单位名快照（忽略 body 传值）
            CooperativeUnit unit = cooperativeUnitMapper.selectUnitById(partyUnitId);
            if (unit == null || !"0".equals(unit.getDelFlag())) {
                throw new ServiceException("对方单位不存在或已删除");
            }
            contract.setPartyName(unit.getUnitName());
            contract.setPartyUnitId(partyUnitId);
        } else {
            // 手填：partyUnitId=null + partyName=入参（null 即清空单位关联）
            if (StringUtils.isEmpty(contract.getPartyName())) {
                throw new ServiceException("未选择对方单位时，对方名称不能为空");
            }
            contract.setPartyUnitId(null);
            // partyName 保持入参原值
        }
    }

    /**
     * 当前登录用户是否「精确」为 researcher（数据范围 data_scope=5）。
     * 不能用 SecurityUtils.hasRole("researcher")：RuoYi 的 SUPER_ADMIN 捷径会让含 admin
     * 角色 key 的用户恒 true，导致 admin 被误路由到 researcher「本人相关」分支。
     * 精确策略：先判 admin 短路，再遍历角色列表逐条比对 role_key。
     * 照抄 ProjectServiceImpl.java:672-696 现有实现风格。
     */
    private boolean isResearcher() {
        try {
            List<com.ruoyi.common.core.domain.entity.SysRole> roles = SecurityUtils.getLoginUser().getUser().getRoles();
            if (roles == null || roles.isEmpty()) {
                return false;
            }
            boolean hasAdmin = false;
            boolean hasResearcher = false;
            for (com.ruoyi.common.core.domain.entity.SysRole r : roles) {
                if (r == null || StringUtils.isEmpty(r.getRoleKey())) {
                    continue;
                }
                if ("admin".equals(r.getRoleKey())) {
                    hasAdmin = true;
                }
                if ("researcher".equals(r.getRoleKey())) {
                    hasResearcher = true;
                }
            }
            // 含 admin 一律走全量分支；仅含 researcher 才走本人相关
            return hasResearcher && !hasAdmin;
        } catch (Exception e) {
            return false;
        }
    }
}