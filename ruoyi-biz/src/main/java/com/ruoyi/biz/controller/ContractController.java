package com.ruoyi.biz.controller;

import com.ruoyi.biz.domain.Contract;
import com.ruoyi.biz.domain.ContractNode;
import com.ruoyi.biz.service.IContractService;
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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 合同 Controller（/biz/contract 全部端点 + /biz/contract/node 子资源）
 *
 * <p>数据权限照课题模式：列表走 @DataScope（Service 内硬分支 researcher 走专用 SQL），
 * 详情/写操作过 scoped projectService.selectProjectById 闸门（抛"无权访问"）。</p>
 *
 * <p>节点列表返回 AjaxResult.success(list)（不是 TableDataInfo）——前端 nodeDialog 取 response.data，
 * 与 unit/contactDialog 模式一致。</p>
 *
 * @author kys
 * @date 2026-08-14
 */
@RestController
@RequestMapping("/biz/contract")
@PreAuthorize("@ss.hasPermi('biz:contract:list')")
@RequiredArgsConstructor
public class ContractController extends BaseController {

    private final IContractService contractService;

    // ========================================================
    //  合同主数据端点
    // ========================================================

    /**
     * 查询合同列表
     */
    @PreAuthorize("@ss.hasPermi('biz:contract:list')")
    @GetMapping("/list")
    public TableDataInfo list(Contract contract) {
        startPage();
        List<Contract> list = contractService.selectContractList(contract);
        return getDataTable(list);
    }

    /**
     * 获取合同详情（强制数据范围；含节点列表）
     */
    @PreAuthorize("@ss.hasPermi('biz:contract:query')")
    @GetMapping("/{contractId}")
    public AjaxResult getInfo(@PathVariable("contractId") Long contractId) {
        return success(contractService.selectContractById(contractId));
    }

    /**
     * 新增合同（contractNo 必填 + 唯一；projectId 必填且过闸门；party 二选一校验）
     */
    @PreAuthorize("@ss.hasPermi('biz:contract:add')")
    @Log(title = "合同管理", businessType = BusinessType.INSERT)
    @PostMapping
    @RepeatSubmit(interval = 2000)
    public AjaxResult add(@RequestBody Contract contract) {
        Contract saved = contractService.insertContract(contract, getUsername());
        return success(saved);
    }

    /**
     * 修改合同（contractNo 以库为准不可改；party 二选一重校验；status 只允许 ACTIVE/EXPIRED/TERMINATED）
     */
    @PreAuthorize("@ss.hasPermi('biz:contract:edit')")
    @Log(title = "合同管理", businessType = BusinessType.UPDATE)
    @PutMapping
    @RepeatSubmit(interval = 2000)
    public AjaxResult edit(@RequestBody Contract contract) {
        return toAjax(contractService.updateContract(contract, getUsername()));
    }

    /**
     * 删除合同（逻辑删除；级联逻辑删除全部有效节点，同事务）
     */
    @PreAuthorize("@ss.hasPermi('biz:contract:remove')")
    @Log(title = "合同管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{contractIds}")
    @RepeatSubmit(interval = 2000)
    public AjaxResult remove(@PathVariable Long[] contractIds) {
        return toAjax(contractService.deleteContractByIds(contractIds, getUsername()));
    }

    /**
     * 导出合同列表
     */
    @PreAuthorize("@ss.hasPermi('biz:contract:export')")
    @Log(title = "合同管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, Contract contract) {
        List<Contract> list = contractService.exportContract(contract);
        ExcelUtil<Contract> util = new ExcelUtil<>(Contract.class);
        util.exportExcel(response, list, "合同档案");
    }

    // ========================================================
    //  履约节点子资源（/biz/contract/node/**）
    // ========================================================

    /**
     * 查询合同节点列表（?contractId=x；返回 overdue 计算标志）
     */
    @PreAuthorize("@ss.hasPermi('biz:contract:query')")
    @GetMapping("/node/list")
    public AjaxResult nodeList(Long contractId) {
        List<ContractNode> list = contractService.selectNodeList(contractId);
        return success(list);
    }

    /**
     * 新增节点（node_name/node_type/plan_date 必填，status 强制 PENDING）
     */
    @PreAuthorize("@ss.hasPermi('biz:contract:node')")
    @Log(title = "履约节点", businessType = BusinessType.INSERT)
    @PostMapping("/node")
    @RepeatSubmit(interval = 2000)
    public AjaxResult addNode(@RequestBody ContractNode node) {
        node.setCreateBy(getUsername());
        return toAjax(contractService.insertNode(node, getUsername()));
    }

    /**
     * 修改节点（DONE 节点仅允许改 remark/voucherUrl）
     */
    @PreAuthorize("@ss.hasPermi('biz:contract:node')")
    @Log(title = "履约节点", businessType = BusinessType.UPDATE)
    @PutMapping("/node")
    @RepeatSubmit(interval = 2000)
    public AjaxResult editNode(@RequestBody ContractNode node) {
        return toAjax(contractService.updateNode(node, getUsername()));
    }

    /**
     * 完成动作（body: nodeId/actualDate/voucherUrl；已 DONE 节点拒）
     */
    @PreAuthorize("@ss.hasPermi('biz:contract:node')")
    @Log(title = "履约节点", businessType = BusinessType.UPDATE)
    @PutMapping("/node/finish")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult finishNode(@RequestBody Map<String, Object> body) {
        Object nidObj   = body.get("nodeId");
        Object dateObj  = body.get("actualDate");
        Object urlObj   = body.get("voucherUrl");
        if (nidObj == null || dateObj == null) {
            return error("参数不完整");
        }
        Long nodeId;
        try {
            nodeId = (nidObj instanceof Number) ? ((Number) nidObj).longValue() : Long.parseLong(nidObj.toString());
        } catch (NumberFormatException e) {
            return error("nodeId 格式错误");
        }
        Date actualDate = parseDate(dateObj);
        if (actualDate == null) {
            return error("实际日期格式错误");
        }
        String voucherUrl = urlObj == null ? null : urlObj.toString();
        return toAjax(contractService.finishNode(nodeId, actualDate, voucherUrl, getUsername()));
    }

    /**
     * 批量删除节点（逻辑删除）
     */
    @PreAuthorize("@ss.hasPermi('biz:contract:node')")
    @Log(title = "履约节点", businessType = BusinessType.DELETE)
    @DeleteMapping("/node/{nodeIds}")
    @RepeatSubmit(interval = 2000)
    public AjaxResult removeNode(@PathVariable Long[] nodeIds) {
        return toAjax(contractService.deleteNodeByIds(nodeIds, getUsername()));
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /**
     * 入参 actualDate 兼容 ISO 字符串 / 时间戳数字 / Date 序列（前端日期选择器返回 ISO 字符串）
     */
    private Date parseDate(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Date) {
            return (Date) o;
        }
        if (o instanceof Number) {
            return new Date(((Number) o).longValue());
        }
        String s = o.toString();
        // 兼容 "yyyy-MM-dd" 与 "yyyy-MM-dd HH:mm:ss"
        String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"};
        for (String p : patterns) {
            try {
                return new SimpleDateFormat(p).parse(s);
            } catch (Exception ignore) {
                // 尝试下一种格式
            }
        }
        return null;
    }
}