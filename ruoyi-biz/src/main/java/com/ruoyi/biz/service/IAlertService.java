package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.Alert;

import java.util.List;

/**
 * 预警 Service 接口（阶段9：通用预警列表/详情/消除，端点 1-3）
 *
 * @author kys
 * @date 2026-08-15
 */
public interface IAlertService {

    /**
     * 通用预警列表（数据范围双通道 D10：researcher 走本人相关专用 SQL，其余走 @DataScope）
     */
    List<Alert> selectAlertList(Alert query);

    /**
     * 通用预警详情（scoped 闸门：researcher 本人相关 / 其余 @DataScope；无权访问抛异常）
     */
    Alert selectAlertById(Long alertId);

    /**
     * 消除（D7）：status→RESOLVED + remark 记操作人；数据范围闸门 + 仅 OPEN 行生效
     *
     * @return 受影响行数（已消除则 0）
     */
    int resolveAlert(Long alertId, String operName);
}
