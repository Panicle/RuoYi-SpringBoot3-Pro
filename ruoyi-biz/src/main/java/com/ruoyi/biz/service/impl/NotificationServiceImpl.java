package com.ruoyi.biz.service.impl;

import com.ruoyi.biz.domain.Notification;
import com.ruoyi.biz.mapper.NotificationMapper;
import com.ruoyi.biz.service.INotificationService;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 通知 Service 实现（阶段9：我的通知/已读/确认/未读计数，端点 4-7）
 *
 * <p>D10：我的通知 receiver_id=me 硬过滤（不走 @DataScope）；read/confirm 先查通知并校验
 * receiver_id=me（否则抛"无权操作"），再按 SQL WHERE receiver_id=me 兜底更新。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements INotificationService {

    private static final String STATUS_READ      = "READ";
    private static final String STATUS_CONFIRMED = "CONFIRMED";

    private final NotificationMapper notificationMapper;

    @Override
    public List<Notification> selectMyNotificationList(Notification query) {
        if (query == null) {
            query = new Notification();
        }
        // receiver_id = me 硬过滤（D10，不走 @DataScope）
        query.getParams().put("selfUserId", SecurityUtils.getUserId());
        return notificationMapper.selectMyNotificationList(query);
    }

    @Override
    public int unreadCount() {
        return notificationMapper.selectUnreadCount(SecurityUtils.getUserId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markRead(Long notifyId, String operName) {
        Notification n = checkOwned(notifyId);
        if (STATUS_READ.equals(n.getStatus()) || STATUS_CONFIRMED.equals(n.getStatus())) {
            return 1;   // 已读/已确认，幂等成功（避免前端 toAjax(0) 误报"操作失败"）
        }
        return notificationMapper.updateRead(notifyId, SecurityUtils.getUserId(), operName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markConfirm(Long notifyId, String operName) {
        Notification n = checkOwned(notifyId);
        if (STATUS_CONFIRMED.equals(n.getStatus())) {
            return 1;   // 已确认，幂等成功（避免前端 toAjax(0) 误报"操作失败"）
        }
        return notificationMapper.updateConfirm(notifyId, SecurityUtils.getUserId(), operName);
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /**
     * 校验通知存在且 receiver_id=me（D10 读权限）；不满足抛"通知不存在或无权操作"。
     */
    private Notification checkOwned(Long notifyId) {
        if (notifyId == null) {
            throw new ServiceException("notifyId 不能为空");
        }
        Notification n = notificationMapper.selectByNotifyId(notifyId);
        if (n == null) {
            throw new ServiceException("通知不存在或无权操作");
        }
        Long me = SecurityUtils.getUserId();
        if (!me.equals(n.getReceiverId())) {
            throw new ServiceException("通知不存在或无权操作");
        }
        return n;
    }
}
