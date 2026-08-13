package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.ProjectUnit;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 课题合作单位关联 Mapper 接口
 *
 * <p>自定义 XML 不走 @TableLogic 自动过滤，del_flag='0' 显式声明。</p>
 *
 * @author kys
 * @date 2026-08-13
 */
public interface ProjectUnitMapper extends BaseMapper<ProjectUnit> {

    /**
     * 查询课题关联单位列表（JOIN cooperative_unit 返回 unitName/externalUnitType；del_flag='0'）
     */
    List<ProjectUnit> selectProjectUnitList(@Param("projectId") Long projectId);

    /**
     * 查询课题下某单位当前有效关联行（重复关联校验）
     */
    ProjectUnit selectByProjectAndUnit(@Param("projectId") Long projectId, @Param("unitId") Long unitId);

    /**
     * 统计某单位被有效关联数（删除保护：被引用拒）
     */
    int countByUnitId(@Param("unitId") Long unitId);

    /**
     * 通过关联 id 反查所属 project_id（删除前数据权限校验）
     */
    Long selectProjectIdById(@Param("id") Long id);

    /**
     * 批量逻辑删除（updateBy / del_flag='2'）
     */
    int softDeleteByIds(@Param("ids") Long[] ids, @Param("updateBy") String updateBy);
}
