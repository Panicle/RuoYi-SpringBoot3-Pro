package com.ruoyi.biz.controller;

import com.ruoyi.biz.domain.Honor;
import com.ruoyi.biz.domain.HonorRelation;
import com.ruoyi.biz.service.IHonorService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 荣誉 Controller（任务卡 Task 2：9 端点 + 数据权限三通道 + 级联删）
 *
 * <p>权限串已挂 science_admin/admin 收紧（决策 D4 简报）：写操作（add/edit/remove/relation）严格限定
 * science_admin/admin（@PreAuthorize 由 RuoYi 菜单挂载保证）；详情/关联列表过 Service scoped 闸门。
 * researcher 走"本人相关"专用 SQL，列表能看本人相关荣誉，详情/关联查不到（无关联或不在本人范围）抛"无权访问"。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@RestController
@RequestMapping("/biz/honor")
@PreAuthorize("@ss.hasPermi('biz:honor:list')")
@RequiredArgsConstructor
public class HonorController extends BaseController {

    private final IHonorService honorService;

    /**
     * 查询荣誉列表（分页；筛选 honorName(like)/honorType/awardLevel/awardDate 区间；三通道 researcher/@DataScope/全所）
     */
    @PreAuthorize("@ss.hasPermi('biz:honor:list')")
    @GetMapping("/list")
    public TableDataInfo list(Honor honor) {
        startPage();
        List<Honor> list = honorService.selectHonorList(honor);
        return getDataTable(list);
    }

    /**
     * 获取荣誉详情（scoped 闸门：researcher 走"本人相关"按 id 查，其他角色走 @DataScope 按 id 查）
     */
    @PreAuthorize("@ss.hasPermi('biz:honor:query')")
    @GetMapping("/{honorId}")
    public AjaxResult getInfo(@PathVariable("honorId") Long honorId) {
        return success(honorService.selectHonorById(honorId));
    }

    /**
     * 新增荣誉（honorName 必填）
     */
    @PreAuthorize("@ss.hasPermi('biz:honor:add')")
    @Log(title = "荣誉", businessType = BusinessType.INSERT)
    @PostMapping
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult add(@RequestBody Honor honor) {
        Honor saved = honorService.insertHonor(honor, getUsername());
        return success(saved);
    }

    /**
     * 修改荣誉（scoped 闸门 + 存在性校验）
     */
    @PreAuthorize("@ss.hasPermi('biz:honor:edit')")
    @Log(title = "荣誉", businessType = BusinessType.UPDATE)
    @PutMapping
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult edit(@RequestBody Honor honor) {
        return toAjax(honorService.updateHonor(honor, getUsername()));
    }

    /**
     * 批量删除荣誉（D5：每个 id 同事务级联软删关联 honor_relation）
     */
    @PreAuthorize("@ss.hasPermi('biz:honor:remove')")
    @Log(title = "荣誉", businessType = BusinessType.DELETE)
    @DeleteMapping("/{honorIds}")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult remove(@PathVariable Long[] honorIds) {
        return toAjax(honorService.deleteHonorByIds(honorIds, getUsername()));
    }

    /**
     * 荣誉导出（ExcelUtil + @Excel 字典注解 honor_type/honor_level；走列表同通道）
     */
    @PreAuthorize("@ss.hasPermi('biz:honor:export')")
    @Log(title = "荣誉导出", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, Honor honor) {
        List<Honor> list = honorService.exportHonor(honor);
        ExcelUtil<Honor> util = new ExcelUtil<>(Honor.class);
        util.exportExcel(response, list, "荣誉");
    }

    /**
     * 关联列表（先过荣誉 scoped 闸门；JOIN 解析 refName）
     */
    @PreAuthorize("@ss.hasPermi('biz:honor:query')")
    @GetMapping("/relation/list")
    public AjaxResult relationList(@RequestParam("honorId") Long honorId) {
        List<HonorRelation> list = honorService.selectRelationList(honorId);
        return success(list);
    }

    /**
     * 新增关联（校验：荣誉存在未删→refType∈三值→ref 对象存在未删→查重 D5）
     */
    @PreAuthorize("@ss.hasPermi('biz:honor:relation')")
    @Log(title = "荣誉关联", businessType = BusinessType.INSERT)
    @PostMapping("/relation")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult addRelation(@RequestBody HonorRelation relation) {
        HonorRelation saved = honorService.insertRelation(relation, getUsername());
        return success(saved);
    }

    /**
     * 软删单条关联（先查关联再过荣誉 scoped 闸门）
     */
    @PreAuthorize("@ss.hasPermi('biz:honor:relation')")
    @Log(title = "荣誉关联", businessType = BusinessType.DELETE)
    @DeleteMapping("/relation/{relationId}")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult removeRelation(@PathVariable("relationId") Long relationId) {
        return toAjax(honorService.deleteRelationById(relationId, getUsername()));
    }
}
