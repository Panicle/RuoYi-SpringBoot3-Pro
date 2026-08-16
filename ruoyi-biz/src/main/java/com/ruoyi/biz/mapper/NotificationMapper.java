package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.Notification;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 通知 Mapper 接口（阶段9 预警引擎：一条预警对应多名接收人各一条通知）
 *
 * <p>D10：/my/list 我的通知 receiver_id=me 硬过滤（不走 @DataScope），
 * read/confirm 均校验 receiver_id=me（SQL WHERE 兜底）。D6 通知派生查重按 (alert_id, receiver_id)。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
public interface NotificationMapper extends BaseMapper<Notification> {

    /**
     * 我的通知列表（receiver_id = #{params.selfUserId} 硬过滤；status 可选筛选；JOIN 解析 alertTitle/receiverName）
     */
    List<Notification> selectMyNotificationList(Notification query);

    /**
     * 未读计数（receiver_id=me AND status='UNREAD'，端角标/对话精灵气泡用）
     */
    int selectUnreadCount(@Param("receiverId") Long receiverId);

    /**
     * 按 notifyId 查通知（read/confirm 前置校验：存在 + receiver_id=me）
     */
    Notification selectByNotifyId(@Param("notifyId") Long notifyId);

    /**
     * 标记已读（D7）：status→READ + read_time；WHERE receiver_id=me 兜底
     */
    int updateRead(@Param("notifyId") Long notifyId, @Param("receiverId") Long receiverId, @Param("operName") String operName);

    /**
     * 确认（D7）：status→CONFIRMED + confirm_time；WHERE receiver_id=me 兜底
     */
    int updateConfirm(@Param("notifyId") Long notifyId, @Param("receiverId") Long receiverId, @Param("operName") String operName);

    /**
     * 通知派生查重（D6）：同 (alert_id, receiver_id) 已存在则不重复 INSERT
     */
    int countByAlertAndReceiver(@Param("alertId") Long alertId, @Param("receiverId") Long receiverId);

    /**
     * 通知接收人派生（D6）：全部 science_admin(role_key) + admin(user_id=1)
     * （课题 leader 由扫描侧按 project.leader_id 并入集合，二者并集去重）
     */
    List<Long> selectAdminReceiverUserIds();
}
