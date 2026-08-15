package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.SurchargeRate;

import java.util.List;

/**
 * 工资附加费比例 Mapper（V1.0.0 基线表 surcharge_rate，Task 3 算法只读）
 *
 * <p>Task 3 算法按 rate_id 升序加载全部 ACTIVE 项（共 10 项），不改动数据；
 * 表管理端点不在本任务范围内。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
public interface SurchargeRateMapper extends BaseMapper<SurchargeRate> {

    /**
     * 加载全部 ACTIVE 项（按 rate_id 升序 — 算法侧最后一人由升序末位确定）。
     */
    List<SurchargeRate> selectActiveRates();
}