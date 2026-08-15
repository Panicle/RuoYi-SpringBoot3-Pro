package com.ruoyi.biz.service.impl;

import com.ruoyi.biz.domain.Alert;
import com.ruoyi.biz.mapper.AlertMapper;
import com.ruoyi.biz.service.IAlertService;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 预警 Service 实现（阶段9：通用预警列表/详情/消除，端点 1-3）
 *
 * <p>数据权限照 Honor/Contract 双通道：列表 researcher 走"本人相关"专用 SQL，其余角色走 @DataScope；
 * 详情/消除过 scoped {@code selectAlertById} 闸门（researcher 亦须本人相关，抛"无权访问"）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Service
@RequiredArgsConstructor
public class AlertServiceImpl implements IAlertService {

    /** 预警状态（字典 alert_status：OPEN 生效 / RESOLVED 已消除） */
    private static final String STATUS_OPEN     = "OPEN";
    private static final String STATUS_RESOLVED = "RESOLVED";

    private final AlertMapper alertMapper;

    // ========================================================
    //  列表 / 详情（数据范围双通道 D10）
    // ========================================================

    @Override
    public List<Alert> selectAlertList(Alert query) {
        if (query == null) {
            query = new Alert();
        }
        // researcher (data_scope=5) 不走 @DataScope，Service 内按角色硬分支：走"本人相关"专用 SQL
        if (isResearcher()) {
            Long me = SecurityUtils.getUserId();
            query.getParams().put("selfUserId", me);
            return alertMapper.selectAlertListForResearcher(query);
        }
        return alertMapper.selectAlertList(query);
    }

    @Override
    public Alert selectAlertById(Long alertId) {
        if (alertId == null) {
            throw new ServiceException("alertId 不能为空");
        }
        if (isResearcher()) {
            Long me = SecurityUtils.getUserId();
            Alert a = alertMapper.selectAlertByIdForResearcher(alertId, me);
            if (a == null) {
                // 不暴露是否存在信息，统一友好提示
                throw new ServiceException("无权访问");
            }
            return a;
        }
        Alert probe = new Alert();
        probe.setAlertId(alertId);
        Alert a = alertMapper.selectAlertById(probe);
        if (a == null) {
            throw new ServiceException("无权访问");
        }
        return a;
    }

    // ========================================================
    //  消除（D7：status→RESOLVED + remark 记操作人）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int resolveAlert(Long alertId, String operName) {
        if (alertId == null) {
            throw new ServiceException("alertId 不能为空");
        }
        // 数据权限闸门（无权访问抛"无权访问"）；顺带确认存在
        Alert db = selectAlertById(alertId);
        if (STATUS_RESOLVED.equals(db.getStatus())) {
            return 0;   // 已消除，幂等
        }
        String remark = "由 " + operName + " 消除";
        return alertMapper.resolveAlert(alertId, remark, operName);
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /**
     * 当前登录用户是否「精确」为 researcher（不含 admin）。
     * 照 ProjectServiceImpl/HonorServiceImpl 现有实现，单次遍历 + hasAdmin/hasResearcher 标志。
     */
    private boolean isResearcher() {
        try {
            List<SysRole> roles = SecurityUtils.getLoginUser().getUser().getRoles();
            if (roles == null || roles.isEmpty()) {
                return false;
            }
            boolean hasAdmin = false;
            boolean hasResearcher = false;
            for (SysRole r : roles) {
                if (r == null || StringUtils.isEmpty(r.getRoleKey())) {
                    continue;
                }
                if ("admin".equals(r.getRoleKey())) {
                    hasAdmin = true;
                }
                if ("researcher".equals(r.getRoleKey())) {
                    hasResearcher = true;
                }
            }
            return hasResearcher && !hasAdmin;
        } catch (Exception e) {
            return false;
        }
    }
}
