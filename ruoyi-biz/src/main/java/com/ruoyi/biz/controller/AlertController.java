package com.ruoyi.biz.controller;

import com.ruoyi.biz.domain.Alert;
import com.ruoyi.biz.domain.Notification;
import com.ruoyi.biz.service.IAlertService;
import com.ruoyi.biz.service.INotificationService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 预警/通知 Controller（阶段9，端点 1-7）
 *
 * <p>权限串与 V1.0.16 菜单挂载对齐：/list(1)/{id}(2)/resolve(3) 走预警中心（2090-2092），
 * /my/list(4)/read(5)/confirm(6)/unread/count(7) 走我的通知（2093-2095）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@RestController
@RequestMapping("/biz/alert")
@RequiredArgsConstructor
public class AlertController extends BaseController {

    private final IAlertService alertService;
    private final INotificationService notificationService;

    /**
     * 1. 预警列表（分页；筛选 alertType/alertLevel/status/refType；@DataScope 双通道）
     */
    @PreAuthorize("@ss.hasPermi('biz:alert:list')")
    @GetMapping("/list")
    public TableDataInfo list(Alert alert) {
        startPage();
        List<Alert> list = alertService.selectAlertList(alert);
        return getDataTable(list);
    }

    /**
     * 2. 预警详情（scoped 闸门 + refName）
     */
    @PreAuthorize("@ss.hasPermi('biz:alert:query')")
    @GetMapping("/{alertId}")
    public AjaxResult getInfo(@PathVariable("alertId") Long alertId) {
        return success(alertService.selectAlertById(alertId));
    }

    /**
     * 3. 消除（D7：status→RESOLVED + remark 记操作人）
     */
    @PreAuthorize("@ss.hasPermi('biz:alert:resolve')")
    @Log(title = "预警消除", businessType = BusinessType.UPDATE)
    @PostMapping("/resolve/{alertId}")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult resolve(@PathVariable("alertId") Long alertId) {
        return toAjax(alertService.resolveAlert(alertId, getUsername()));
    }

    /**
     * 4. 我的通知列表（receiver_id=me 硬过滤；status 筛选）
     */
    @PreAuthorize("@ss.hasPermi('biz:alert:notify')")
    @GetMapping("/my/list")
    public TableDataInfo myList(Notification query) {
        startPage();
        List<Notification> list = notificationService.selectMyNotificationList(query);
        return getDataTable(list);
    }

    /**
     * 5. 标记已读（D7：status→READ + read_time；校验 receiver_id=me）
     */
    @PreAuthorize("@ss.hasPermi('biz:alert:read')")
    @Log(title = "通知已读", businessType = BusinessType.UPDATE)
    @PostMapping("/notify/{notifyId}/read")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult read(@PathVariable("notifyId") Long notifyId) {
        return toAjax(notificationService.markRead(notifyId, getUsername()));
    }

    /**
     * 6. 确认（D7：status→CONFIRMED + confirm_time；校验 receiver_id=me）
     */
    @PreAuthorize("@ss.hasPermi('biz:alert:confirm')")
    @Log(title = "通知确认", businessType = BusinessType.UPDATE)
    @PostMapping("/notify/{notifyId}/confirm")
    @RepeatSubmit(interval = 2000, message = "请勿重复提交")
    public AjaxResult confirm(@PathVariable("notifyId") Long notifyId) {
        return toAjax(notificationService.markConfirm(notifyId, getUsername()));
    }

    /**
     * 7. 未读计数（receiver_id=me AND status='UNREAD'，对话精灵气泡/角标用）
     */
    @PreAuthorize("@ss.hasPermi('biz:alert:notify')")
    @GetMapping("/notify/unread/count")
    public AjaxResult unreadCount() {
        return success(notificationService.unreadCount());
    }
}
