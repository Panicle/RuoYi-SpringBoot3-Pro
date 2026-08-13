package com.ruoyi.biz.controller;

import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.domain.ProjectMember;
import com.ruoyi.biz.service.IProjectService;
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

import java.util.List;
import java.util.Map;

/**
 * 课题 Controller
 *
 * @author kys
 * @date 2026-08-12
 */
@RestController
@RequestMapping("/biz/project")
@PreAuthorize("@ss.hasPermi('biz:project:list')")
@RequiredArgsConstructor
public class ProjectController extends BaseController {

    private final IProjectService projectService;

    // ========================================================
    //  课题主数据端点
    // ========================================================

    /**
     * 查询课题列表
     */
    @PreAuthorize("@ss.hasPermi('biz:project:list')")
    @GetMapping("/list")
    public TableDataInfo list(Project project) {
        startPage();
        List<Project> list = projectService.selectProjectList(project);
        return getDataTable(list);
    }

    /**
     * 获取课题详情（强制数据范围）
     */
    @PreAuthorize("@ss.hasPermi('biz:project:query')")
    @GetMapping("/{projectId}")
    public AjaxResult getInfo(@PathVariable("projectId") Long projectId) {
        return success(projectService.selectProjectById(projectId));
    }

    /**
     * 新增课题（projectNo 必填 + 唯一；请求体可带 budgetSplitList 预算细分，预算总额 = Σ）
     */
    @PreAuthorize("@ss.hasPermi('biz:project:add')")
    @Log(title = "课题管理", businessType = BusinessType.INSERT)
    @PostMapping
    @RepeatSubmit(interval = 2000)
    public AjaxResult add(@RequestBody Project project) {
        Project saved = projectService.insertProject(project, getUsername());
        return success(saved);
    }

    /**
     * 修改课题（禁改 leaderId/projectNo/status；请求体带 budgetSplitList 时全量替换预算细分，预算总额 = Σ；ARCHIVED 拒）
     */
    @PreAuthorize("@ss.hasPermi('biz:project:edit')")
    @Log(title = "课题管理", businessType = BusinessType.UPDATE)
    @PutMapping
    @RepeatSubmit(interval = 2000)
    public AjaxResult edit(@RequestBody Project project) {
        project.setUpdateBy(getUsername());
        return toAjax(projectService.updateProject(project, getUsername()));
    }

    /**
     * 删除课题（逻辑删除；存在有效成员时拒）
     */
    @PreAuthorize("@ss.hasPermi('biz:project:remove')")
    @Log(title = "课题管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{projectIds}")
    @RepeatSubmit(interval = 2000)
    public AjaxResult remove(@PathVariable Long[] projectIds) {
        return toAjax(projectService.deleteProjectByIds(projectIds, getUsername()));
    }

    /**
     * 导出课题列表
     */
    @PreAuthorize("@ss.hasPermi('biz:project:export')")
    @Log(title = "课题管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, Project project) {
        List<Project> list = projectService.exportProject(project);
        ExcelUtil<Project> util = new ExcelUtil<>(Project.class);
        util.exportExcel(response, list, "课题档案");
    }

    /**
     * 状态机迁移（相邻单向）
     */
    @PreAuthorize("@ss.hasPermi('biz:project:edit')")
    @Log(title = "课题管理", businessType = BusinessType.UPDATE)
    @PostMapping("/changeStatus")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult changeStatus(@RequestBody Map<String, Object> body) {
        Object pidObj   = body.get("projectId");
        Object statusObj = body.get("targetStatus");
        if (pidObj == null || statusObj == null) {
            return error("参数不完整");
        }
        Long projectId = (pidObj instanceof Number) ? ((Number) pidObj).longValue() : Long.parseLong(pidObj.toString());
        String targetStatus = statusObj.toString();
        return toAjax(projectService.changeStatus(projectId, targetStatus, getUsername()));
    }

    /**
     * 归档（COMPLETED/ACCEPTED → ARCHIVED）
     */
    @PreAuthorize("@ss.hasPermi('biz:project:archive')")
    @Log(title = "课题管理", businessType = BusinessType.UPDATE)
    @PostMapping("/archive")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult archive(@RequestBody Map<String, Object> body) {
        Object pidObj = body.get("projectId");
        if (pidObj == null) {
            return error("projectId 不能为空");
        }
        Long projectId = (pidObj instanceof Number) ? ((Number) pidObj).longValue() : Long.parseLong(pidObj.toString());
        return toAjax(projectService.archive(projectId, getUsername()));
    }

    // ========================================================
    //  成员端点
    // ========================================================

    /**
     * 查询课题成员列表（§5.10：query 形式 ?projectId=1001&pageNum=1&pageSize=10；GET 禁止 body）
     */
    @PreAuthorize("@ss.hasPermi('biz:project:member')")
    @GetMapping("/member/list")
    public TableDataInfo memberList(Long projectId) {
        startPage();
        List<ProjectMember> list = projectService.selectMemberList(projectId);
        return getDataTable(list);
    }

    /**
     * 新增成员（单/批量；拒绝 HOST）
     * 请求体：{ "projectId": 1001, "members": [ { "userId": 200, "role": "PARTICIPANT" } ] }
     */
    @PreAuthorize("@ss.hasPermi('biz:project:member')")
    @Log(title = "课题成员", businessType = BusinessType.INSERT)
    @PostMapping("/member")
    @RepeatSubmit(interval = 2000)
    public AjaxResult addMember(@RequestBody Map<String, Object> body) {
        Object pidObj = body.get("projectId");
        Object membersObj = body.get("members");
        if (pidObj == null || !(membersObj instanceof List)) {
            return error("参数不完整");
        }
        Long projectId = (pidObj instanceof Number) ? ((Number) pidObj).longValue() : Long.parseLong(pidObj.toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawList = (List<Map<String, Object>>) membersObj;
        List<ProjectMember> members = new java.util.ArrayList<>();
        for (Map<String, Object> item : rawList) {
            ProjectMember m = new ProjectMember();
            Object userIdObj = item.get("userId");
            if (userIdObj == null) continue;
            m.setUserId((userIdObj instanceof Number) ? ((Number) userIdObj).longValue() : Long.parseLong(userIdObj.toString()));
            Object roleObj = item.get("role");
            if (roleObj != null) m.setRole(roleObj.toString());
            members.add(m);
        }
        return toAjax(projectService.addMembers(projectId, members, getUsername()));
    }

    /**
     * 批量删除成员（不能删唯一 HOST）
     */
    @PreAuthorize("@ss.hasPermi('biz:project:member')")
    @Log(title = "课题成员", businessType = BusinessType.DELETE)
    @DeleteMapping("/member/{memberIds}")
    @RepeatSubmit(interval = 2000)
    public AjaxResult removeMember(@PathVariable Long[] memberIds) {
        return toAjax(projectService.removeMembers(memberIds, getUsername()));
    }

    /**
     * 换主持人（事务内）
     */
    @PreAuthorize("@ss.hasPermi('biz:project:member')")
    @Log(title = "课题成员", businessType = BusinessType.UPDATE)
    @PutMapping("/member/changeHost")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult changeHost(@RequestBody Map<String, Object> body) {
        Object pidObj   = body.get("projectId");
        Object newObj   = body.get("newLeaderUserId");
        if (pidObj == null || newObj == null) {
            return error("参数不完整");
        }
        Long projectId = (pidObj instanceof Number) ? ((Number) pidObj).longValue() : Long.parseLong(pidObj.toString());
        Long newLeaderUserId = (newObj instanceof Number) ? ((Number) newObj).longValue() : Long.parseLong(newObj.toString());
        return toAjax(projectService.changeHost(projectId, newLeaderUserId, getUsername()));
    }
}