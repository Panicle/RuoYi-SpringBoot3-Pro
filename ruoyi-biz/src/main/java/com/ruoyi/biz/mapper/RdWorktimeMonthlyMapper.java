package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.RdWorktimeMonthly;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 月度研发工时汇总 Mapper 接口
 *
 * <p>task brief D9：保存/复制工时同事务重算 (project, researcher, month) 的
 * total_rd_hours / cumulative_hours；端点 10（分页月度汇总）走 @DataScope 三档
 * （researcher 本人 / dept_leader 本室 / 其他全所）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
public interface RdWorktimeMonthlyMapper extends BaseMapper<RdWorktimeMonthly> {

    /**
     * 按 (projectId, researcherId, month) 查有效汇总行（DB 无唯一索引，应用层查重）。
     */
    RdWorktimeMonthly selectByProjectResearcherMonth(@Param("projectId") Long projectId,
                                                     @Param("researcherId") Long researcherId,
                                                     @Param("month") String month);

    /**
     * 按 (projectId, month) 查全部有效工时行（total_rd_hours > 0），按 researcher_id 升序 —
     * 算法侧用于分摊计算（任务卡 Task 3 步骤 4："最后一人"由稳定升序末位确定）。
     */
    List<RdWorktimeMonthly> selectMembersByProjectMonth(@Param("projectId") Long projectId,
                                                        @Param("month") String month);

    /**
     * 端点 16 工时统计表导出专用：LEFT JOIN sys_user 取 researcherName（researcherName 为 exist=false 字段，
     * 必须显式 JOIN 才能填充）；selfUserId 非空时叠加 {@code researcher_id = selfUserId} 行级过滤
     * （researcher 角色仅本人行，与 /biz/rd/worktime/monthly/list researcher 通道口径一致）。
     */
    List<RdWorktimeMonthly> selectMembersByProjectMonthForExport(@Param("projectId") Long projectId,
                                                                 @Param("month") String month,
                                                                 @Param("selfUserId") Long selfUserId);

    /**
     * 月度汇总分页列表（@DataScope 通道：deptAlias=d / userAlias=u，三档过滤；
     * researcher 不调用此方法，Service 走"本人相关"专用分支）。
     * SELECT vo 必须 LEFT JOIN project p + sys_dept d + sys_user u + sys_user u2（u2 取 researcherName）；
     * 别名 d/u 专供数据权限使用（与 ExpenseMapper 惯例一致）。
     */
    @com.ruoyi.common.annotation.DataScope(deptAlias = "d", userAlias = "u")
    List<RdWorktimeMonthly> selectRdWorktimeMonthlyList(RdWorktimeMonthly query);

    /**
     * 月度汇总列表（researcher 专用）：仅本人行 WHERE researcher_id = selfUserId
     * （task brief D9：数据权限三档首档）。
     */
    List<RdWorktimeMonthly> selectRdWorktimeMonthlyListForResearcher(RdWorktimeMonthly query);
}