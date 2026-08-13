package com.ruoyi.biz.service.impl;

import com.ruoyi.biz.domain.CooperativeUnit;
import com.ruoyi.biz.domain.UnitContact;
import com.ruoyi.biz.mapper.CooperativeUnitMapper;
import com.ruoyi.biz.mapper.ProjectUnitMapper;
import com.ruoyi.biz.mapper.UnitContactMapper;
import com.ruoyi.biz.service.ICooperativeUnitService;
import com.ruoyi.common.core.domain.TreeSelect;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 合作单位 Service 实现
 *
 * <p>树逻辑照抄 SysDeptServiceImpl：buildUnitTree / insert ancestors / updateUnitChildren / find_in_set（达梦兼容）。
 * 层级深度 = ancestors 拆逗号计数（非空段数，不含自身）；公司 ≤3 层 / 学校 ≤2 层，超限抛业务错误。
 * 子单位类型继承父级，insert 时从父带下，不接受 body 传入的不同值。
 * 不用 @DataScope（全所共享主数据）。</p>
 *
 * @author kys
 * @date 2026-08-13
 */
@Service
@RequiredArgsConstructor
public class CooperativeUnitServiceImpl implements ICooperativeUnitService {

    private final CooperativeUnitMapper cooperativeUnitMapper;
    private final UnitContactMapper unitContactMapper;
    private final ProjectUnitMapper projectUnitMapper;

    /** 外部单位类型（与字典 external_unit_type 一致） */
    private static final String TYPE_COMPANY = "COMPANY";
    private static final String TYPE_SCHOOL  = "SCHOOL";

    // ========================================================
    //  单位树 / 列表 / 详情
    // ========================================================

    @Override
    public List<CooperativeUnit> selectUnitList(CooperativeUnit query) {
        return cooperativeUnitMapper.selectUnitList(query);
    }

    @Override
    public CooperativeUnit selectUnitById(Long unitId) {
        return cooperativeUnitMapper.selectUnitById(unitId);
    }

    @Override
    public List<TreeSelect> selectUnitTreeSelect(CooperativeUnit query) {
        List<CooperativeUnit> units = selectUnitList(query);
        return buildUnitTreeSelect(units);
    }

    @Override
    public List<CooperativeUnit> excludeUnitById(Long unitId) {
        List<CooperativeUnit> units = selectUnitList(new CooperativeUnit());
        String id = String.valueOf(unitId);
        // 移除自身 + 所有后代（后代 ancestors 包含本节点）
        units.removeIf(u -> u.getUnitId().equals(unitId)
                || (StringUtils.isNotEmpty(u.getAncestors())
                    && ArrayUtils.contains(StringUtils.split(u.getAncestors(), ","), id)));
        return units;
    }

    @Override
    public List<CooperativeUnit> exportUnit(CooperativeUnit query) {
        return selectUnitList(query);
    }

    // ========================================================
    //  新增（ancestors 自动计算 + 层级深度校验 + 子单位类型继承）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertUnit(CooperativeUnit unit, String operName) {
        if (unit == null || StringUtils.isEmpty(unit.getUnitName())) {
            throw new ServiceException("单位名称不能为空");
        }
        Long parentId = unit.getParentId();
        if (parentId == null || parentId == 0L) {
            // 顶级：parent_id=0、ancestors=''，必须选择 external_unit_type
            unit.setParentId(0L);
            unit.setAncestors("");
            if (StringUtils.isEmpty(unit.getExternalUnitType())) {
                throw new ServiceException("顶级单位必须选择单位类别");
            }
            if (StringUtils.isEmpty(unit.getUnitType())) {
                // V1.0.0 表默认 EXTERNAL
                unit.setUnitType("EXTERNAL");
            }
        } else {
            CooperativeUnit parent = cooperativeUnitMapper.selectUnitById(parentId);
            if (parent == null) {
                throw new ServiceException("父单位不存在");
            }
            if (!"0".equals(parent.getDelFlag())) {
                throw new ServiceException("父单位已删除，不允许新增下级");
            }
            // 子单位类型继承父级，不接受 body 传入的不同值
            unit.setUnitType(parent.getUnitType());
            unit.setExternalUnitType(parent.getExternalUnitType());
            unit.setAncestors(parent.getAncestors() + "," + parentId);
        }
        // 层级深度校验（ancestors 拆逗号计数；COMPANY≤3层 / SCHOOL≤2层）
        validateUnitDepth(unit);
        unit.setDelFlag("0");  // 三层保险之一：Service 显式置
        unit.setCreateBy(operName);
        return cooperativeUnitMapper.insert(unit);
    }

    // ========================================================
    //  修改（换父级 updateChildren 同步后代；不能选自身/后代为父）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateUnit(CooperativeUnit unit, String operName) {
        if (unit == null || unit.getUnitId() == null) {
            throw new ServiceException("unitId 不能为空");
        }
        CooperativeUnit oldUnit = cooperativeUnitMapper.selectUnitById(unit.getUnitId());
        if (oldUnit == null) {
            throw new ServiceException("单位不存在");
        }
        if (!"0".equals(oldUnit.getDelFlag())) {
            throw new ServiceException("单位已删除，不允许修改");
        }
        Long parentId = unit.getParentId();
        if (parentId == null) {
            parentId = oldUnit.getParentId();
        }
        unit.setParentId(parentId);
        if (parentId.equals(unit.getUnitId())) {
            throw new ServiceException("上级单位不能是自己");
        }
        unit.setUpdateBy(operName);
        if (parentId == 0L) {
            // 移到顶级：ancestors 置空，并同步全部后代 ancestors
            String newAncestors = "";
            String oldAncestors = oldUnit.getAncestors();
            // ancestors 无条件重算（防止父级未变时 body 直写绕过）
            unit.setAncestors("");
            if (!StringUtils.equals(newAncestors, oldAncestors)) {
                updateUnitChildren(unit.getUnitId(), newAncestors, oldAncestors);
            }
        } else {
            CooperativeUnit newParent = cooperativeUnitMapper.selectUnitById(parentId);
            if (newParent == null) {
                throw new ServiceException("父单位不存在");
            }
            if (!"0".equals(newParent.getDelFlag())) {
                throw new ServiceException("父单位已删除，不允许挂载");
            }
            // 不能选自身/后代为父（后代 ancestors 包含本节点）
            if (isDescendant(unit.getUnitId(), newParent)) {
                throw new ServiceException("不能选择自身或后代作为上级单位");
            }
            String newAncestors = newParent.getAncestors() + "," + parentId;
            String oldAncestors = oldUnit.getAncestors();
            // 换父前先算子树最深相对层数，防止移动后后代超限（UI 可达）
            int maxRel = 0;
            for (CooperativeUnit c : cooperativeUnitMapper.selectChildrenUnitById(unit.getUnitId())) {
                maxRel = Math.max(maxRel, countAncestors(c.getAncestors()) - countAncestors(oldAncestors));
            }
            CooperativeUnit probe = new CooperativeUnit();
            probe.setExternalUnitType(newParent.getExternalUnitType());
            probe.setAncestors(newAncestors + ",0".repeat(maxRel));
            validateUnitDepth(probe);
            // ancestors 无条件重算（防止父级未变时 body 直写绕过）
            unit.setAncestors(newAncestors);
            if (!StringUtils.equals(newAncestors, oldAncestors)) {
                updateUnitChildren(unit.getUnitId(), newAncestors, oldAncestors);
            }
            // 子单位类型继承父级不可改（含换父级时改为新父类型）
            unit.setUnitType(newParent.getUnitType());
            unit.setExternalUnitType(newParent.getExternalUnitType());
        }
        return cooperativeUnitMapper.updateById(unit);
    }

    // ========================================================
    //  删除（有未删子节点拒；被 project_unit 有效行引用拒）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteUnitByIds(Long[] unitIds, String operName) {
        if (unitIds == null || unitIds.length == 0) {
            return 0;
        }
        for (Long unitId : unitIds) {
            CooperativeUnit unit = cooperativeUnitMapper.selectUnitById(unitId);
            if (unit == null) {
                throw new ServiceException("单位[" + unitId + "]不存在或已删除");
            }
            if (cooperativeUnitMapper.hasChildByUnitId(unitId) > 0) {
                throw new ServiceException("存在下级单位，不允许删除");
            }
            if (projectUnitMapper.countByUnitId(unitId) > 0) {
                throw new ServiceException("该单位已被课题关联，不允许删除");
            }
            cooperativeUnitMapper.deleteUnitById(unitId, operName);
        }
        return unitIds.length;
    }

    // ========================================================
    //  联系人（unit_contact 子资源）
    // ========================================================

    @Override
    @Transactional(readOnly = true)
    public List<UnitContact> selectContactList(Long unitId) {
        if (unitId == null) {
            throw new ServiceException("unitId 不能为空");
        }
        return unitContactMapper.selectContactList(unitId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertContact(UnitContact contact, String operName) {
        if (contact == null || contact.getUnitId() == null) {
            throw new ServiceException("unitId 不能为空");
        }
        if (StringUtils.isEmpty(contact.getContactName())) {
            throw new ServiceException("联系人姓名不能为空");
        }
        CooperativeUnit unit = cooperativeUnitMapper.selectUnitById(contact.getUnitId());
        if (unit == null || !"0".equals(unit.getDelFlag())) {
            throw new ServiceException("所属单位不存在或已删除");
        }
        contact.setDelFlag("0");  // 三层保险之一：Service 显式置
        contact.setCreateBy(operName);
        return unitContactMapper.insert(contact);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateContact(UnitContact contact, String operName) {
        if (contact == null || contact.getContactId() == null) {
            throw new ServiceException("contactId 不能为空");
        }
        UnitContact db = unitContactMapper.selectById(contact.getContactId());
        if (db == null) {
            throw new ServiceException("联系人不存在");
        }
        // unitId 以库中原值为准，不允许改挂其他单位
        contact.setUnitId(db.getUnitId());
        contact.setUpdateBy(operName);
        return unitContactMapper.updateById(contact);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteContactByIds(Long[] contactIds, String operName) {
        if (contactIds == null || contactIds.length == 0) {
            return 0;
        }
        return unitContactMapper.softDeleteByIds(contactIds, operName);
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /**
     * 层级深度校验：深度 = ancestors 拆逗号非空段数（即祖先数，不含自身）。
     * 公司 ≤3 层（深度≥3 拒）；学校 ≤2 层（深度≥2 拒）。
     */
    private void validateUnitDepth(CooperativeUnit unit) {
        int depth = countAncestors(unit.getAncestors());
        if (TYPE_COMPANY.equals(unit.getExternalUnitType()) && depth >= 3) {
            throw new ServiceException("公司层级最多三层");
        }
        if (TYPE_SCHOOL.equals(unit.getExternalUnitType()) && depth >= 2) {
            throw new ServiceException("学校层级最多两层");
        }
    }

    /**
     * ancestors 拆逗号计数（首尾空串忽略，返回祖级个数）
     */
    private int countAncestors(String ancestors) {
        if (StringUtils.isEmpty(ancestors)) {
            return 0;
        }
        int n = 0;
        for (String s : ancestors.split(",")) {
            if (StringUtils.isNotEmpty(s)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 待选父级是否为当前节点自身/后代（父级 ancestors 中包含当前节点 unitId）
     */
    private boolean isDescendant(Long unitId, CooperativeUnit parent) {
        if (parent == null || StringUtils.isEmpty(parent.getAncestors())) {
            return false;
        }
        String id = String.valueOf(unitId);
        for (String s : parent.getAncestors().split(",")) {
            if (id.equals(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 换父级后同步全部后代 ancestors（照 SysDeptServiceImpl.updateDeptChildren）
     */
    private void updateUnitChildren(Long unitId, String newAncestors, String oldAncestors) {
        List<CooperativeUnit> children = cooperativeUnitMapper.selectChildrenUnitById(unitId);
        for (CooperativeUnit child : children) {
            child.setAncestors(newAncestors + child.getAncestors().substring(oldAncestors.length()));
        }
        if (children.size() > 0) {
            cooperativeUnitMapper.updateUnitChildren(children);
        }
    }

    /**
     * 构建前端所需树结构（照 SysDeptServiceImpl.buildDeptTree）
     */
    private List<CooperativeUnit> buildUnitTree(List<CooperativeUnit> units) {
        List<CooperativeUnit> returnList = new ArrayList<>();
        List<Long> tempList = units.stream().map(CooperativeUnit::getUnitId).collect(Collectors.toList());
        for (CooperativeUnit unit : units) {
            // 顶级节点（父不在列表中）作为树根
            if (!tempList.contains(unit.getParentId())) {
                recursionFn(units, unit);
                returnList.add(unit);
            }
        }
        if (returnList.isEmpty()) {
            returnList = units;
        }
        return returnList;
    }

    /**
     * 构建前端所需下拉树结构（照 SysDeptServiceImpl.buildDeptTreeSelect）
     */
    private List<TreeSelect> buildUnitTreeSelect(List<CooperativeUnit> units) {
        List<CooperativeUnit> unitTrees = buildUnitTree(units);
        List<TreeSelect> result = new ArrayList<>();
        for (CooperativeUnit unit : unitTrees) {
            result.add(toTreeSelect(unit));
        }
        return result;
    }

    private TreeSelect toTreeSelect(CooperativeUnit unit) {
        TreeSelect tree = new TreeSelect();
        tree.setId(unit.getUnitId());
        tree.setLabel(unit.getUnitName());
        if (unit.getChildren() != null && !unit.getChildren().isEmpty()) {
            List<TreeSelect> children = new ArrayList<>();
            for (CooperativeUnit child : unit.getChildren()) {
                children.add(toTreeSelect(child));
            }
            tree.setChildren(children);
        }
        return tree;
    }

    private void recursionFn(List<CooperativeUnit> list, CooperativeUnit t) {
        List<CooperativeUnit> childList = getChildList(list, t);
        t.setChildren(childList);
        for (CooperativeUnit tChild : childList) {
            if (hasChild(list, tChild)) {
                recursionFn(list, tChild);
            }
        }
    }

    private List<CooperativeUnit> getChildList(List<CooperativeUnit> list, CooperativeUnit t) {
        List<CooperativeUnit> tlist = new ArrayList<>();
        for (CooperativeUnit n : list) {
            if (n.getParentId() != null && n.getParentId().longValue() == t.getUnitId().longValue()) {
                tlist.add(n);
            }
        }
        return tlist;
    }

    private boolean hasChild(List<CooperativeUnit> list, CooperativeUnit t) {
        return getChildList(list, t).size() > 0;
    }
}
