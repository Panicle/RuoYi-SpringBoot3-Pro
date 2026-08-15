package com.ruoyi.biz.task;

import com.ruoyi.biz.service.AlertScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 预警扫描 Quartz 定时任务（阶段9 Task 3）。
 *
 * <p>由 sys_job 配置 invoke_target='alertScanTask.scanAll()' 反射调用；
 * Quartz 调度框架（JobInvokeUtil）忽略返回值，scanAll 返回新建 alert 数仅供手动触发端点复用语义。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Component("alertScanTask")
@RequiredArgsConstructor
public class AlertScanTask {

    private final AlertScanService alertScanService;

    /**
     * 触发全量预警扫描（合同节点/经费超限/资料逾期 三小扫描，同事务）。
     *
     * @return 本次新建的 alert 数（Quartz 忽略；供冒烟/手动触发确认扫描有产出）
     */
    public int scanAll() {
        return alertScanService.scanAll();
    }
}
