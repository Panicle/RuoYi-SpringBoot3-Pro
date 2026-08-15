package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.HonorRelation;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 荣誉关联 Mapper 接口
 *
 * <p>三表 LEFT JOIN 解析 refName：
 * PROJECT → project_no || ' ' || project_name
 * RESEARCHER → sys_user.nick_name
 * UNIT → cooperative_unit.unit_name
 * 自定义 XML 显式声明 del_flag='0' 过滤，三层保险之一。</p>
 *
 * <p>应用层查重（决策 D5）：同 honor_id + ref_type + ref_id 有效行存在则拒；不依赖 DB 唯一索引。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
public interface HonorRelationMapper extends BaseMapper<HonorRelation> {

    /**
     * 按 honorId 查询关联列表（JOIN 解析 refName，仅有效行 del_flag='0'）
     */
    List<HonorRelation> selectRelationListByHonorId(@Param("honorId") Long honorId);

    /**
     * 查重：同 honor_id + ref_type + ref_id 有效行计数（>0 即视为已存在）
     */
    int countActiveRelation(@Param("honorId") Long honorId,
                            @Param("refType") String refType,
                            @Param("refId") Long refId);

    /**
     * 逻辑删除单条关联（XML 显式 del_flag='0' 条件 + sysdate，三层保险之一）
     */
    int softDeleteByRelationId(@Param("relationId") Long relationId, @Param("updateBy") String updateBy);

    /**
     * 级联软删：按 honorId 删除该荣誉全部有效关联（删荣誉时同事务调用）
     */
    int softDeleteByHonorId(@Param("honorId") Long honorId, @Param("updateBy") String updateBy);
}
