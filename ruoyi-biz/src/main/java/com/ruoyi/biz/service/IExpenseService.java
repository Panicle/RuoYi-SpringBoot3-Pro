package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.Alert;
import com.ruoyi.biz.domain.Expense;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 经费记账 Service 接口（记账 / 作废 / 冲销 / 经费预警，任务卡 §3.2 + §3.3）
 *
 * <p>数据权限双通道：列表 researcher 走"本人相关"专用 SQL、其余角色走 @DataScope 注解；
 * 详情与全部写操作过 scoped {@code projectService.selectProjectById} 闸门（researcher 亦须本人相关，决策 D7）。</p>
 *
 * <p>金额一律 BigDecimal 2 位小数 HALF_UP；budget_split 的核减/回冲一律走 @Version 乐观锁（决策 D9）；
 * 支出不就地改历史金额——改 = 作废 + 新增，退款 = 追加负数流水（决策 D8）。</p>
 *
 * @author kys
 * @date 2026-08-14
 */
public interface IExpenseService {

    /**
     * 查询经费流水列表（数据权限双通道）
     */
    List<Expense> selectExpenseList(Expense query);

    /**
     * 查询流水详情（过 scoped 闸门）
     */
    Expense selectExpenseById(Long expenseId);

    /**
     * 记账（§4.1 核心事务）：参数校验 → scoped 闸门 → 课题状态 → 定位 split → 预算不足校验
     * → INSERT 流水 → 乐观锁核减 split → 重算课题汇总 → 双阈值预警幂等落库
     *
     * @return 落库后的流水（含自增主键与回填的 category）
     */
    Expense insertExpense(Expense expense, String operName);

    /**
     * 作废（§4.2）：原单 status 必须为 NORMAL；status→VOID；同事务回冲 used_amount/balance（乐观锁）、
     * 重算课题汇总、重跑阈值检查（余额回升不删除已有 alert，留阶段9 收敛）
     *
     * @param version 前端回传的流水版本号（为空时以库中版本为准）
     * @return 影响行数
     */
    int voidExpense(Long expenseId, Integer version, String remark, String operName);

    /**
     * 冲销（§4.2）：追加一笔 amount = -|入参| 的 NORMAL 流水，remark 记"冲销 expense_id=x"；
     * 走记账事务的 6-9 步，不做预算不足校验
     *
     * @return 落库后的负数流水
     */
    Expense refundExpense(Long originExpenseId, BigDecimal amount, Date expenseDate,
                          String description, String operName);

    /**
     * 导出（复用列表的数据权限双通道）
     */
    List<Expense> exportExpense(Expense query);

    /**
     * 经费预警列表（§3.3）：alert 表 alert_type='BUDGET'；projectId 非空时限定该课题并过 scoped 闸门，
     * 为空时按调用者数据范围全量（researcher 只看本人相关课题的预警）
     */
    List<Alert> selectAlertList(Long projectId);

    /**
     * 标记预警已处理（§3.3）：status→HANDLED，先过预警所属课题的 scoped 闸门
     *
     * @return 影响行数
     */
    int handleAlert(Long alertId, String operName);
}
