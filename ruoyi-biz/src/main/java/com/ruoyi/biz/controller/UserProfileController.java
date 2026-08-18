package com.ruoyi.biz.controller;

import com.ruoyi.biz.domain.UserProfile;
import com.ruoyi.biz.service.IUserProfileService;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 科研人员档案 Controller
 *
 * @author kys
 * @date 2026-08-11
 */
@RestController
@RequestMapping("/biz/userProfile")
@RequiredArgsConstructor
public class UserProfileController extends BaseController {

    private final IUserProfileService userProfileService;

    /**
     * 查询科研人员档案列表
     */
    @PreAuthorize("@ss.hasPermi('biz:userProfile:list')")
    @GetMapping("/list")
    public TableDataInfo list(UserProfile researcher) {
        startPage();
        List<UserProfile> list = userProfileService.selectUserProfileList(researcher);
        return getDataTable(list);
    }

    /**
     * 可选人员选项（课题组长/成员选择器用）：全所 sys_user 列表，无数据范围过滤。
     * researcher 需选择组长/成员，此处返回全所人员（不做 @DataScope 过滤）。
     */
    @PreAuthorize("@ss.hasPermi('biz:userProfile:list')")
    @GetMapping("/options")
    public AjaxResult options() {
        return success(userProfileService.selectUserOptions());
    }

    /**
     * 获取科研人员档案详细信息
     */
    @PreAuthorize("@ss.hasPermi('biz:userProfile:query')")
    @GetMapping("/{profileId}")
    public AjaxResult getInfo(@PathVariable("profileId") Long profileId) {
        return success(userProfileService.selectUserProfileById(profileId));
    }

    /**
     * 新增科研人员档案
     */
    @PreAuthorize("@ss.hasPermi('biz:userProfile:add')")
    @Log(title = "科研人员档案", businessType = BusinessType.INSERT)
    @PostMapping
    @RepeatSubmit
    public AjaxResult add(@RequestBody UserProfile researcher) {
        researcher.setCreateBy(getUsername());
        return toAjax(userProfileService.insertUserProfile(researcher));
    }

    /**
     * 修改科研人员档案
     */
    @PreAuthorize("@ss.hasPermi('biz:userProfile:edit')")
    @Log(title = "科研人员档案", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody UserProfile researcher) {
        researcher.setUpdateBy(getUsername());
        return toAjax(userProfileService.updateUserProfile(researcher));
    }

    /**
     * 删除科研人员档案（逻辑删除）
     */
    @PreAuthorize("@ss.hasPermi('biz:userProfile:remove')")
    @Log(title = "科研人员档案", businessType = BusinessType.DELETE)
    @DeleteMapping("/{profileIds}")
    public AjaxResult remove(@PathVariable Long[] profileIds) {
        return toAjax(userProfileService.deleteUserProfileByIds(profileIds));
    }

    /**
     * 导出科研人员档案列表
     */
    @PreAuthorize("@ss.hasPermi('biz:userProfile:export')")
    @Log(title = "科研人员档案", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, UserProfile researcher) {
        List<UserProfile> list = userProfileService.selectUserProfileList(researcher);
        ExcelUtil<UserProfile> util = new ExcelUtil<>(UserProfile.class);
        util.exportExcel(response, list, "科研人员档案");
    }

    /**
     * 导入科研人员档案数据
     */
    @PreAuthorize("@ss.hasPermi('biz:userProfile:import')")
    @Log(title = "科研人员档案", businessType = BusinessType.IMPORT)
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception {
        int titleNum = 0;
        ExcelUtil<UserProfile> util = new ExcelUtil<>(UserProfile.class);
        List<UserProfile> list = util.importExcel(file.getInputStream(), titleNum);
        String operName = getUsername();
        String message = userProfileService.importUserProfile(list, titleNum, updateSupport, operName);
        return success(message);
    }

    /**
     * 下载导入模板（登录即可）
     */
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil<UserProfile> util = new ExcelUtil<>(UserProfile.class);
        util.importTemplateExcel(response, "科研人员档案数据");
    }
}