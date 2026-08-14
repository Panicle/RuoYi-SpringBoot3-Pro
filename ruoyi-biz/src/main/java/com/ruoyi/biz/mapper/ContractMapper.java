package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.Contract;
import com.ruoyi.common.annotation.DataScope;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 合同 Mapper 接口
 *
 * <p>数据权限照课题模式（ProjectMapper）：列表走 @DataScope(deptAlias="d", userAlias="u") 注解，
 * SQL JOIN project p LEFT JOIN sys_dept d ON p.dept_id=d.dept_id；researcher(data_scope=5) 由 Service
 * 切换到 selectContractListForResearcher 专用分支。</p>
 *
 * @author kys
 * @date 2026-08-14
 */
public interface ContractMapper extends BaseMapper<Contract> {

    /**
     * 查询合同列表（自行 JOIN project / sys_dept / sys_user 视图，
     * researcher(data_scope=5) 不走此方法，Service 走"本人相关"专用分支）
     *
     * @param query 查询条件
     * @return 合同集合（含 projectName/projectNo/leaderName/deptName）
     */
    @DataScope(deptAlias = "d", userAlias = "u")
    List<Contract> selectContractList(Contract query);

    /**
     * 列表查询（researcher 专用）：仅返回所属课题的 leader_id = 当前用户 OR 课题成员表中含当前用户的合同
     */
    List<Contract> selectContractListForResearcher(Contract query);

    /**
     * 查询合同详情（JOIN project + cooperative_unit，携带 partyUnitName）
     */
    Contract selectContractById(@Param("contractId") Long contractId);

    /**
     * 按合同编号查重（含软删行，与 DB 唯一索引 idx_contract_no_uk 兜底一致；唯一索引下至多 1 行）
     *
     * @param contractNo 合同编号
     * @return 已占用返回对应 contract_id，否则 null
     */
    Long selectByContractNo(@Param("contractNo") String contractNo);
}