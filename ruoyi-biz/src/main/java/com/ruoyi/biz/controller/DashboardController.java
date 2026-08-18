package com.ruoyi.biz.controller;

import com.ruoyi.biz.service.DashboardService;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页工作台 Controller
 *
 * <p>登录即可访问（不挂菜单权限串 — 首页对全部角色开放）；
 * 数据范围由各业务 Service 的 scoped/角色通道保证。</p>
 *
 * @author kys
 * @date 2026-08-17
 */
@RestController
@RequestMapping("/biz/dashboard")
@RequiredArgsConstructor
public class DashboardController extends BaseController {

    private final DashboardService dashboardService;

    /**
     * 工作台汇总：统计卡 + 待办 + 未读预警 + 课题预算执行 TOP5
     */
    @GetMapping("/summary")
    public AjaxResult summary() {
        return success(dashboardService.summary());
    }
}
