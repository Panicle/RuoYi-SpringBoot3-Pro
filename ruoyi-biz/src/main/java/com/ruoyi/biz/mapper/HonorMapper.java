package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.Honor;
import com.ruoyi.common.annotation.DataScope;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 荣誉 Mapper 接口
 *
 * <p>数据权限照 ProjectDocumentMapper/ContractMapper 双通道：列表走 {@code @DataScope(deptAlias="d", userAlias="u")} 注解，
 * selectHonorVo 必须 LEFT JOIN {@code honor_relation hr} + LEFT JOIN {@code project p} + LEFT JOIN {@code sys_user su}
 * + LEFT JOIN {@code sys_dept d} + LEFT JOIN {@code sys_user u}，别名 d/u 专供数据权限使用，不得被业务表占用；
 * researcher(data_scope=5) 由 Service 切换到 selectHonorListForResearcher 专用分支
 * （EXISTS 本人相关：hr.ref_type='RESEARCHER' AND ref_id=me  OR  hr.ref_type='PROJECT' AND 所属课题 leader_id/member 含 me）。</p>
 *
 * <p>无关联的荣誉仅全所范围角色可见（本人/本室通道查不到是预期语义）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
public interface HonorMapper extends BaseMapper<Honor> {

    /**
     * 查询荣誉列表（@DataScope 通道；researcher 不调用此方法，Service 走"本人相关"专用分支）
     *
     * @param query 查询条件（honorName(like)/honorType/awardLevel/awardDate 区间；@DataScope 注入 params.dataScope）
     * @return 荣誉集合
     */
    @DataScope(deptAlias = "d", userAlias = "u")
    List<Honor> selectHonorList(Honor query);

    /**
     * 列表查询（researcher 专用）：仅返回本人相关的荣誉（本人作为人员出现在关联中 或 所在课题出现在关联中）
     */
    List<Honor> selectHonorListForResearcher(Honor query);

    /**
     * 查询荣誉详情（@DataScope 通道：其他角色走数据范围校验；
     * researcher 走{@link #selectHonorByIdForResearcher}专用分支；带数据范围后即作为"详情 scoped 闸门"）
     *
     * @param query 查询条件（honorId 必填；@DataScope 注入 params.dataScope）
     * @return 荣誉详情，不存在/不在数据范围返回 null
     */
    @DataScope(deptAlias = "d", userAlias = "u")
    Honor selectHonorById(Honor query);

    /**
     * researcher 详情专用：按 id 查本人相关或本人录入（create_by=当前登录用户名；无关联/不在本人范围/非本人录入则返回 null，Service 抛"无权访问"）
     */
    Honor selectHonorByIdForResearcher(@Param("honorId") Long honorId, @Param("selfUserId") Long selfUserId,
                                       @Param("selfUsername") String selfUsername);

    /**
     * 逻辑删除单条荣誉（del_flag='2'；关联 honor_relation 级联软删由 Service 同事务调用 HonorRelationMapper.softDeleteByHonorId）
     *
     * @param honorId  荣誉ID
     * @param updateBy 操作人
     * @return 影响行数
     */
    int softDeleteByHonorId(@Param("honorId") Long honorId, @Param("updateBy") String updateBy);
}
