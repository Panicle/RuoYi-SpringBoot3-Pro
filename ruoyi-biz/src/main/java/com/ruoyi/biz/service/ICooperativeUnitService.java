package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.CooperativeUnit;
import com.ruoyi.biz.domain.UnitContact;
import com.ruoyi.common.core.domain.TreeSelect;

import java.util.List;

/**
 * 合作单位 Service 接口
 *
 * <p>不用 @DataScope（全所共享主数据）。</p>
 *
 * @author kys
 * @date 2026-08-13
 */
public interface ICooperativeUnitService {

    /**
     * 查询合作单位平铺列表（树表用，前端 handleTree 组树；支持 unitName/unitType/externalUnitType 筛选）
     */
    List<CooperativeUnit> selectUnitList(CooperativeUnit query);

    /**
     * 按 unitId 查询单位详情
     */
    CooperativeUnit selectUnitById(Long unitId);

    /**
     * 构建前端所需树选择器（TreeSelect 结构；仅需登录）
     */
    List<TreeSelect> selectUnitTreeSelect(CooperativeUnit query);

    /**
     * 排除自身及后代的平铺列表（换父级用，照 sys_dept）
     */
    List<CooperativeUnit> excludeUnitById(Long unitId);

    /**
     * 新增单位（ancestors 自动计算；公司≤3层/学校≤2层校验；顶级须选 external_unit_type；子单位继承父级类型）
     */
    int insertUnit(CooperativeUnit unit, String operName);

    /**
     * 修改单位（换父级时 updateChildren 同步后代 ancestors；不能选自身/后代为父；子单位类型继承父级不可改）
     */
    int updateUnit(CooperativeUnit unit, String operName);

    /**
     * 批量删除单位（有未删子节点拒；被 project_unit 有效行引用拒；逻辑删除）
     */
    int deleteUnitByIds(Long[] unitIds, String operName);

    /**
     * Excel 导出（复用列表查询条件）
     */
    List<CooperativeUnit> exportUnit(CooperativeUnit query);

    /**
     * 查询单位联系人列表
     */
    List<UnitContact> selectContactList(Long unitId);

    /**
     * 新增联系人
     */
    int insertContact(UnitContact contact, String operName);

    /**
     * 修改联系人
     */
    int updateContact(UnitContact contact, String operName);

    /**
     * 批量删除联系人（逻辑删除）
     */
    int deleteContactByIds(Long[] contactIds, String operName);
}
