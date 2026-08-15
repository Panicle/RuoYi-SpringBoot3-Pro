package com.ruoyi.biz.controller;

import com.ruoyi.biz.domain.RdResearcherSalary;
import com.ruoyi.biz.service.IRdLaborBudgetService;
import com.ruoyi.biz.service.IRdResearcherSalaryService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 研发加计扣除 — 工资与预算 Controller（任务卡 Task 2 端点 1/2/3/4/5/6）
 *
 * <p>端点 1-2 预算走 /biz/rd/budget/*；端点 3-6 工资走 /biz/rd/salary/*。
 * 权限串：
 * <ul>
 *   <li>biz:rd:salary:budget — 预算列表/保存（端点 1-2）</li>
 *   <li>biz:rd:salary:list — 工资列表（端点 3）</li>
 *   <li>biz:rd:salary:save — 工资保存（端点 4）</li>
 *   <li>biz:rd:salary:import — 工资导入（端点 5 + importTemplate）</li>
 *   <li>biz:rd:salary:export — 工资导出（端点 6）</li>
 * </ul>
 * </p>
 *
 * <p>数据权限：列表走 @DataScope；researcher 角色 Service 兜底拒（D11）；
 * 详情/写操作过 scoped projectService.selectProjectById 闸门（预算）或 sys_userMapper 校验（工资）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@RestController
@RequestMapping("/biz/rd")
@RequiredArgsConstructor
public class RdSalaryController extends BaseController {

    private final IRdLaborBudgetService rdLaborBudgetService;
    private final IRdResearcherSalaryService rdResearcherSalaryService;

    // ========================================================
    //  预算（端点 1 /biz/rd/budget/list + 端点 2 /biz/rd/budget/save）
    // ========================================================

    /**
     * 预算列表 — 12 个月行（缺月补零值行 budgetId=null）；
     * 先过 projectService.selectProjectById scoped 闸门。
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:salary:budget')")
    @GetMapping("/budget/list")
    public AjaxResult budgetList(Long projectId, Integer year) {
        return success(rdLaborBudgetService.listYearBudget(projectId, year));
    }

    /**
     * 预算保存 — 按 (projectId, year, month) 增量 upsert；CONFIRMED 月拒改；ARCHIVED 课题拒。
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:salary:budget')")
    @Log(title = "研发预算", businessType = BusinessType.UPDATE)
    @PutMapping("/budget/save")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult saveBudget(@RequestBody com.ruoyi.biz.domain.bo.RdBudgetSaveBo body) {
        return toAjax(rdLaborBudgetService.saveBudget(body, getUsername()));
    }

    // ========================================================
    //  工资（端点 3 /biz/rd/salary/list + 端点 4 save + 端点 5 import + 端点 6 export）
    // ========================================================

    /**
     * 工资列表（分页；JOIN sys_user 带 researcherName；researcher 角色 Service 兜底拒）
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:salary:list')")
    @GetMapping("/salary/list")
    public TableDataInfo salaryList(RdResearcherSalary salary) {
        startPage();
        List<RdResearcherSalary> list = rdResearcherSalaryService.selectSalaryList(salary);
        return getDataTable(list);
    }

    /**
     * 工资保存（单条 upsert 查重）
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:salary:save')")
    @Log(title = "研发工资", businessType = BusinessType.INSERT)
    @PostMapping("/salary/save")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult saveSalary(@RequestBody RdResearcherSalary salary) {
        RdResearcherSalary saved = rdResearcherSalaryService.saveSalary(salary, getUsername());
        return success(saved);
    }

    /**
     * 工资导入模板下载（端点 5 附属，不计入端点数）
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:salary:import')")
    @GetMapping("/salary/importTemplate")
    public AjaxResult importTemplate() {
        ExcelUtil<RdResearcherSalary> util = new ExcelUtil<>(RdResearcherSalary.class);
        return util.importTemplateExcel("研发工资");
    }

    /**
     * 工资导入（ExcelUtil 行级校验汇总报错）
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:salary:import')")
    @Log(title = "研发工资导入", businessType = BusinessType.IMPORT)
    @PostMapping("/salary/importData")
    public AjaxResult importData(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            return error("导入文件不能为空");
        }
        ExcelUtil<RdResearcherSalary> util = new ExcelUtil<>(RdResearcherSalary.class);
        List<RdResearcherSalary> rows = util.importExcel(file.getInputStream());
        String msg = rdResearcherSalaryService.importSalary(rows, getUsername());
        return success(msg);
    }

    /**
     * 工资导出（ExcelUtil）
     */
    @PreAuthorize("@ss.hasPermi('biz:rd:salary:export')")
    @Log(title = "研发工资导出", businessType = BusinessType.EXPORT)
    @PostMapping("/salary/export")
    public void export(HttpServletResponse response, RdResearcherSalary salary) {
        List<RdResearcherSalary> list = rdResearcherSalaryService.exportSalary(salary);
        ExcelUtil<RdResearcherSalary> util = new ExcelUtil<>(RdResearcherSalary.class);
        util.exportExcel(response, list, "研发工资");
    }
}