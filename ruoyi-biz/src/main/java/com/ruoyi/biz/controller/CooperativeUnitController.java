package com.ruoyi.biz.controller;

import com.ruoyi.biz.domain.CooperativeUnit;
import com.ruoyi.biz.domain.UnitContact;
import com.ruoyi.biz.service.ICooperativeUnitService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.TreeSelect;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 合作单位 Controller（/biz/unit 全部端点 + /biz/unit/contact 子资源）
 *
 * <p>不用 @DataScope（全所共享主数据）。treeselect 仅需登录，不设单独权限。</p>
 *
 * @author kys
 * @date 2026-08-13
 */
@RestController
@RequestMapping("/biz/unit")
@RequiredArgsConstructor
public class CooperativeUnitController extends BaseController {

    private final ICooperativeUnitService cooperativeUnitService;

    // ========================================================
    //  单位树主数据端点
    // ========================================================

    /**
     * 查询合作单位平铺列表（树表用，前端 handleTree 组树；支持 unitName/unitType/externalUnitType 筛选）
     */
    @PreAuthorize("@ss.hasPermi('biz:unit:list')")
    @GetMapping("/list")
    public AjaxResult list(CooperativeUnit unit) {
        List<CooperativeUnit> list = cooperativeUnitService.selectUnitList(unit);
        return success(list);
    }

    /**
     * 树选择器数据（TreeSelect 结构；仅需登录）
     */
    @GetMapping("/treeselect")
    public AjaxResult treeselect(CooperativeUnit unit) {
        List<TreeSelect> list = cooperativeUnitService.selectUnitTreeSelect(unit);
        return success(list);
    }

    /**
     * 查询单位详情
     */
    @PreAuthorize("@ss.hasPermi('biz:unit:query')")
    @GetMapping("/{unitId}")
    public AjaxResult getInfo(@PathVariable("unitId") Long unitId) {
        return success(cooperativeUnitService.selectUnitById(unitId));
    }

    /**
     * 排除自身及后代的平铺列表（换父级用，照 sys_dept）
     */
    @PreAuthorize("@ss.hasPermi('biz:unit:query')")
    @GetMapping("/exclude/{unitId}")
    public AjaxResult excludeChild(@PathVariable("unitId") Long unitId) {
        List<CooperativeUnit> list = cooperativeUnitService.excludeUnitById(unitId);
        return success(list);
    }

    /**
     * 新增单位（ancestors 自动计算；公司≤3层/学校≤2层校验；顶级须选 external_unit_type；子单位类型继承父级）
     */
    @PreAuthorize("@ss.hasPermi('biz:unit:add')")
    @Log(title = "合作单位", businessType = BusinessType.INSERT)
    @PostMapping
    @RepeatSubmit(interval = 2000)
    public AjaxResult add(@RequestBody CooperativeUnit unit) {
        unit.setCreateBy(getUsername());
        return toAjax(cooperativeUnitService.insertUnit(unit, getUsername()));
    }

    /**
     * 修改单位（换父级时 updateChildren 同步后代 ancestors；不能选自身/后代为父）
     */
    @PreAuthorize("@ss.hasPermi('biz:unit:edit')")
    @Log(title = "合作单位", businessType = BusinessType.UPDATE)
    @PutMapping
    @RepeatSubmit(interval = 2000)
    public AjaxResult edit(@RequestBody CooperativeUnit unit) {
        unit.setUpdateBy(getUsername());
        return toAjax(cooperativeUnitService.updateUnit(unit, getUsername()));
    }

    /**
     * 删除单位（有未删子节点拒；被 project_unit 有效行引用拒；逻辑删除）
     */
    @PreAuthorize("@ss.hasPermi('biz:unit:remove')")
    @Log(title = "合作单位", businessType = BusinessType.DELETE)
    @DeleteMapping("/{unitIds}")
    @RepeatSubmit(interval = 2000)
    public AjaxResult remove(@PathVariable Long[] unitIds) {
        return toAjax(cooperativeUnitService.deleteUnitByIds(unitIds, getUsername()));
    }

    /**
     * 导出合作单位列表
     */
    @PreAuthorize("@ss.hasPermi('biz:unit:export')")
    @Log(title = "合作单位", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CooperativeUnit unit) {
        List<CooperativeUnit> list = cooperativeUnitService.exportUnit(unit);
        ExcelUtil<CooperativeUnit> util = new ExcelUtil<>(CooperativeUnit.class);
        util.exportExcel(response, list, "合作单位");
    }

    // ========================================================
    //  联系人子资源（/biz/unit/contact）
    // ========================================================

    /**
     * 查询单位联系人列表（?unitId=x）
     */
    @PreAuthorize("@ss.hasPermi('biz:unit:query')")
    @GetMapping("/contact/list")
    public AjaxResult contactList(Long unitId) {
        List<UnitContact> list = cooperativeUnitService.selectContactList(unitId);
        return success(list);
    }

    /**
     * 新增联系人
     */
    @PreAuthorize("@ss.hasPermi('biz:unit:contact')")
    @Log(title = "联系人", businessType = BusinessType.INSERT)
    @PostMapping("/contact")
    @RepeatSubmit(interval = 2000)
    public AjaxResult addContact(@RequestBody UnitContact contact) {
        contact.setCreateBy(getUsername());
        return toAjax(cooperativeUnitService.insertContact(contact, getUsername()));
    }

    /**
     * 修改联系人
     */
    @PreAuthorize("@ss.hasPermi('biz:unit:contact')")
    @Log(title = "联系人", businessType = BusinessType.UPDATE)
    @PutMapping("/contact")
    @RepeatSubmit(interval = 2000)
    public AjaxResult editContact(@RequestBody UnitContact contact) {
        contact.setUpdateBy(getUsername());
        return toAjax(cooperativeUnitService.updateContact(contact, getUsername()));
    }

    /**
     * 批量删除联系人（逻辑删除）
     */
    @PreAuthorize("@ss.hasPermi('biz:unit:contact')")
    @Log(title = "联系人", businessType = BusinessType.DELETE)
    @DeleteMapping("/contact/{contactIds}")
    @RepeatSubmit(interval = 2000)
    public AjaxResult removeContact(@PathVariable Long[] contactIds) {
        return toAjax(cooperativeUnitService.deleteContactByIds(contactIds, getUsername()));
    }
}
