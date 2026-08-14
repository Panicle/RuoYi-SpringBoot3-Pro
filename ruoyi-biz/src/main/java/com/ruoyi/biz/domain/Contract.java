package com.ruoyi.biz.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
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

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 合同对象 contract
 *
 * <p>字段分组：14 表列原貌（contract_id/project_id/contract_name/contract_type/amount/sign_date/expire_date/status/del_flag/create_by/create_time/update_by/update_time/remark）
 * + V1.0.10 新增 5 列（contract_no/party_unit_id/party_name/start_date/file_url）= 19 表列；
 * 非表字段 projectName/projectNo/partyUnitName/nodeList 用于列表 JOIN 展示与详情携带节点。</p>
 *
 * <p>partyUnitId / partyName / fileUrl 三列加 updateStrategy=ALWAYS（前后端契约修正 Task 3 审查新增）：
 * MyBatis-Plus 默认忽略 null 字段，但前端会传显式 null 清空这三列（例如取消选择合作单位、清空附件），
 * 故强制 update 总是写入，由 Service 解析为确定终值后再写。其余字段维持默认 null 忽略策略。</p>
 *
 * @author kys
 * @date 2026-08-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("contract")
public class Contract extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 合同ID */
    @TableId(value = "contract_id", type = IdType.AUTO)
    private Long contractId;

    /** 课题ID */
    @TableField("project_id")
    private Long projectId;

    /** 合同名称 */
    @Excel(name = "合同名称")
    @TableField("contract_name")
    private String contractName;

    /** 合同类型（字典 contract_type：RESEARCH/SERVICE/PROCUREMENT） */
    @Excel(name = "合同类型", dictType = "contract_type")
    @TableField("contract_type")
    private String contractType;

    /** 合同金额 */
    @Excel(name = "合同金额")
    @TableField("amount")
    private BigDecimal amount;

    /** 签订日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "签订日期", dateFormat = "yyyy-MM-dd")
    @TableField("sign_date")
    private Date signDate;

    /** 到期日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "到期日期", dateFormat = "yyyy-MM-dd")
    @TableField("expire_date")
    private Date expireDate;

    /** 状态（字典 contract_status：ACTIVE/EXPIRED/TERMINATED） */
    @Excel(name = "状态", dictType = "contract_status", readConverterExp = "ACTIVE=履行中,EXPIRED=已到期,TERMINATED=已终止")
    @TableField("status")
    private String status;

    /** 删除标志（0代表存在 2代表删除） */
    @TableLogic(value = "0", delval = "2")
    @TableField("del_flag")
    private String delFlag;

    // ====== V1.0.10 新增 5 列 ======

    /** 合同编号（人工输入必填；唯一索引 idx_contract_no_uk 兜底，含软删行） */
    @Excel(name = "合同编号")
    @TableField("contract_no")
    private String contractNo;

    /** 对方主体（cooperative_unit.unit_id，可空） */
    @TableField(value = "party_unit_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long partyUnitId;

    /** 对方名称（选单位时=单位名快照；未建档时手工填） */
    @Excel(name = "对方主体")
    @TableField(value = "party_name", updateStrategy = FieldStrategy.ALWAYS)
    private String partyName;

    /** 生效日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "生效日期", dateFormat = "yyyy-MM-dd")
    @TableField("start_date")
    private Date startDate;

    /** 合同附件（/common/upload 相对路径） */
    @TableField(value = "file_url", updateStrategy = FieldStrategy.ALWAYS)
    private String fileUrl;

    // ====== 视图关联字段（来自 project / cooperative_unit JOIN，非 contract 字段） ======

    /** 所属课题编号（来自 project.project_no，列表/详情展示） */
    @Excel(name = "所属课题编号")
    @TableField(exist = false)
    private String projectNo;

    /** 所属课题名称（来自 project.project_name） */
    @Excel(name = "所属课题")
    @TableField(exist = false)
    private String projectName;

    /** 对方单位名称（来自 cooperative_unit.unit_name，详情/编辑回显） */
    @TableField(exist = false)
    private String partyUnitName;

    // ====== 子资源（来自 contract_node，非 contract 字段） ======

    /** 履约节点列表（详情接口携带；CRUD 走 /biz/contract/node 子资源） */
    @TableField(exist = false)
    private List<ContractNode> nodeList;
}