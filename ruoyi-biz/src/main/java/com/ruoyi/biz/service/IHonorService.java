package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.Honor;
import com.ruoyi.biz.domain.HonorRelation;

import java.util.List;

/**
 * 荣誉 Service 接口（荣誉 CRUD + 关联维护，任务卡 Task 2）
 *
 * @author kys
 * @date 2026-08-15
 */
public interface IHonorService {

    /**
     * 查询荣誉列表（已注入数据范围；researcher 走"本人相关"专用分支；
     * 筛选 honorName(like)/honorType/awardLevel/awardDate 区间）
     */
    List<Honor> selectHonorList(Honor query);

    /**
     * 详情查询（scoped 闸门校验；researcher 走"本人相关"按 id 查；其他角色走 @DataScope 按 id 查）
     */
    Honor selectHonorById(Long honorId);

    /**
     * 新增荣誉（honorName 必填）
     *
     * @return 入库后荣誉（含回填 honorId）
     */
    Honor insertHonor(Honor honor, String operName);

    /**
     * 修改荣誉（存在性校验；scoped 闸门）
     */
    int updateHonor(Honor honor, String operName);

    /**
     * 批量删除荣誉（D5：每个 id 同事务级联软删关联 honor_relation）
     */
    int deleteHonorByIds(Long[] honorIds, String operName);

    /**
     * 导出（复用列表查询条件 + 数据权限双通道）
     */
    List<Honor> exportHonor(Honor query);

    /**
     * 关联列表（先过荣誉 scoped 闸门；JOIN 解析 refName）
     */
    List<HonorRelation> selectRelationList(Long honorId);

    /**
     * 新增关联（校验：荣誉存在未删→refType∈三值→ref 对象存在未删（按类型分表查）→查重 D5）
     */
    HonorRelation insertRelation(HonorRelation relation, String operName);

    /**
     * 软删单条关联（先查关联再过荣誉 scoped 闸门）
     */
    int deleteRelationById(Long relationId, String operName);
}
