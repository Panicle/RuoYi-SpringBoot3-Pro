package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.CooperativeUnit;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 合作单位 Mapper 接口
 *
 * <p>树逻辑照 sys_dept：insert 时计算 ancestors、updateChildren 同步后代、find_in_set（达梦兼容）查询子孙。
 * 自定义 XML 不走 @TableLogic 自动过滤，del_flag='0' 显式声明。</p>
 *
 * @author kys
 * @date 2026-08-13
 */
public interface CooperativeUnitMapper extends BaseMapper<CooperativeUnit> {

    /**
     * 查询合作单位平铺列表（树表用，前端 handleTree 组树；del_flag='0'）
     */
    List<CooperativeUnit> selectUnitList(CooperativeUnit query);

    /**
     * 按 unitId 查询单位（含 parent_name 回显；不限定 del_flag，Service 侧校验）
     */
    CooperativeUnit selectUnitById(Long unitId);

    /**
     * 是否存在未删后代节点（find_in_set ancestors，跨任意层级）
     */
    int hasChildByUnitId(Long unitId);

    /**
     * 按 unitId 查询全部后代（find_in_set ancestors，updateChildren 用）
     */
    List<CooperativeUnit> selectChildrenUnitById(Long unitId);

    /**
     * 批量同步后代 ancestors（case unit_id when ... then ...）
     */
    int updateUnitChildren(@Param("units") List<CooperativeUnit> units);

    /**
     * 逻辑删除单位（显式置 del_flag='2'，三层保险之一）
     */
    int deleteUnitById(@Param("unitId") Long unitId, @Param("updateBy") String updateBy);
}
