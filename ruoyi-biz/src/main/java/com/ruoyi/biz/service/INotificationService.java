package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.Notification;

import java.util.List;

/**
 * 通知 Service 接口（阶段9：我的通知/已读/确认/未读计数，端点 4-7）
 *
 * @author kys
 * @date 2026-08-15
 */
public interface INotificationService {

    /**
     * 我的通知列表（receiver_id=me 硬过滤 D10；status 可选筛选）
     */
    List<Notification> selectMyNotificationList(Notification query);

    /**
     * 未读计数（receiver_id=me AND status='UNREAD'，对话精灵气泡/角标用）
     */
    int unreadCount();

    /**
     * 标记已读（D7）：status→READ + read_time；校验通知存在且 receiver_id=me
     *
     * @return 受影响行数（已读则 0）
     */
    int markRead(Long notifyId, String operName);

    /**
     * 确认（D7）：status→CONFIRMED + confirm_time；校验通知存在且 receiver_id=me
     *
     * @return 受影响行数（已确认则 0）
     */
    int markConfirm(Long notifyId, String operName);
}
