package com.ruoyi.biz.service.impl;

import com.ruoyi.biz.domain.CooperativeUnit;
import com.ruoyi.biz.domain.Honor;
import com.ruoyi.biz.domain.HonorRelation;
import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.mapper.CooperativeUnitMapper;
import com.ruoyi.biz.mapper.HonorMapper;
import com.ruoyi.biz.mapper.HonorRelationMapper;
import com.ruoyi.biz.mapper.ProjectMapper;
import com.ruoyi.biz.service.IHonorService;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 荣誉 Service 实现（荣誉 CRUD + 关联维护，任务卡 Task 2）
 *
 * <p>数据权限照 ProjectDocumentServiceImpl/ContractServiceImpl 双通道：列表 researcher 走"本人相关"专用 SQL
 * （本人作为人员 OR 所在课题 出现在有效关联中），其他角色走 @DataScope 注解；
 * 详情/全部写操作过 scoped {@code selectHonorById} 闸门（researcher 亦须本人相关，抛"无权访问"）。</p>
 *
 * <p>D2：ref_type='RESEARCHER' 时 ref_id = sys_user.user_id；RESEARCHER 不走 scoped 闸门（人员与数据范围无强关联）。
 * D4：写操作权限串已挂 science_admin/admin（@PreAuthorize 强校验），Service 仍做存在性/合法性校验。
 * D5：删荣誉同事务级联软删全部有效 honor_relation；关联查重走应用层（同 honor_id+ref_type+ref_id 有效行存在则拒）。</p>
 *
 * <p>无关联的荣誉仅全所范围角色可见（本人/本室通道查不到是预期语义）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Service
@RequiredArgsConstructor
public class HonorServiceImpl implements IHonorService {

    /** 关联类型三值（字典 honor_ref_type） */
    private static final String REF_TYPE_PROJECT    = "PROJECT";
    private static final String REF_TYPE_RESEARCHER = "RESEARCHER";
    private static final String REF_TYPE_UNIT       = "UNIT";
    private static final Set<String> ALLOWED_REF_TYPES = new HashSet<>(
            Arrays.asList(REF_TYPE_PROJECT, REF_TYPE_RESEARCHER, REF_TYPE_UNIT));

    private final HonorMapper honorMapper;
    private final HonorRelationMapper honorRelationMapper;
    private final ProjectMapper projectMapper;
    private final SysUserMapper sysUserMapper;
    private final CooperativeUnitMapper cooperativeUnitMapper;

    // ========================================================
    //  列表 / 详情（数据范围双通道）
    // ========================================================

    @Override
    public List<Honor> selectHonorList(Honor query) {
        if (query == null) {
            query = new Honor();
        }
        // researcher (data_scope=5) 不走 @DataScope，Service 内按角色硬分支：走"本人相关"专用 SQL
        if (isResearcher()) {
            Long me = SecurityUtils.getUserId();
            query.getParams().put("selfUserId", me);
            return honorMapper.selectHonorListForResearcher(query);
        }
        return honorMapper.selectHonorList(query);
    }

    @Override
    public Honor selectHonorById(Long honorId) {
        if (honorId == null) {
            throw new ServiceException("honorId 不能为空");
        }
        // researcher 走"本人相关"按 id 查；其他角色走 @DataScope 通道的 selectHonorById
        if (isResearcher()) {
            Long me = SecurityUtils.getUserId();
            Honor h = honorMapper.selectHonorByIdForResearcher(honorId, me);
            if (h == null) {
                // 不暴露是否存在信息
                throw new ServiceException("无权访问");
            }
            return h;
        }
        Honor probe = new Honor();
        probe.setHonorId(honorId);
        Honor h = honorMapper.selectHonorById(probe);
        if (h == null) {
            throw new ServiceException("无权访问");
        }
        return h;
    }

    // ========================================================
    //  新增（honorName 必填）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Honor insertHonor(Honor honor, String operName) {
        if (honor == null) {
            throw new ServiceException("参数为空");
        }
        if (StringUtils.isEmpty(honor.getHonorName())) {
            throw new ServiceException("荣誉名称不能为空");
        }
        // delFlag 显式置 "0"（三层保险之一）
        honor.setDelFlag("0");
        honor.setCreateBy(operName);
        honorMapper.insert(honor);
        return honor;
    }

    // ========================================================
    //  修改（scoped 闸门 + 存在性校验）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateHonor(Honor honor, String operName) {
        if (honor == null || honor.getHonorId() == null) {
            throw new ServiceException("honorId 不能为空");
        }
        // scoped 闸门（researcher 走本人相关；其他角色走 @DataScope 已校验的 selectHonorById）
        selectHonorById(honor.getHonorId());
        honor.setUpdateBy(operName);
        return honorMapper.updateById(honor);
    }

    // ========================================================
    //  删除（D5：每个 id 同事务级联软删关联 honor_relation）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteHonorByIds(Long[] honorIds, String operName) {
        if (honorIds == null || honorIds.length == 0) {
            return 0;
        }
        for (Long hid : honorIds) {
            // 每个 id 过 scoped 闸门（不在范围内抛"无权访问"）
            selectHonorById(hid);
        }
        // 级联软删：主表 + 全部有效关联（同事务；同事务内必走）
        for (Long hid : honorIds) {
            honorRelationMapper.softDeleteByHonorId(hid, operName);
            honorMapper.softDeleteByHonorId(hid, operName);
        }
        return honorIds.length;
    }

    // ========================================================
    //  导出
    // ========================================================

    @Override
    public List<Honor> exportHonor(Honor query) {
        return selectHonorList(query);
    }

    // ========================================================
    //  关联列表（先过荣誉 scoped 闸门；JOIN 解析 refName）
    // ========================================================

    @Override
    public List<HonorRelation> selectRelationList(Long honorId) {
        if (honorId == null) {
            throw new ServiceException("honorId 不能为空");
        }
        // 先过荣誉 scoped 闸门（researcher 走本人相关；其他角色走 @DataScope 已校验的 selectHonorById）
        selectHonorById(honorId);
        return honorRelationMapper.selectRelationListByHonorId(honorId);
    }

    // ========================================================
    //  新增关联（校验：荣誉存在未删→refType∈三值→ref 对象存在未删→查重 D5）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HonorRelation insertRelation(HonorRelation relation, String operName) {
        if (relation == null) {
            throw new ServiceException("参数为空");
        }
        if (relation.getHonorId() == null) {
            throw new ServiceException("荣誉不能为空");
        }
        if (StringUtils.isEmpty(relation.getRefType())) {
            throw new ServiceException("关联类型不能为空");
        }
        if (relation.getRefId() == null) {
            throw new ServiceException("关联对象ID不能为空");
        }
        // 1. 荣誉存在未删（scoped 闸门）
        selectHonorById(relation.getHonorId());
        // 2. refType 三值校验
        if (!ALLOWED_REF_TYPES.contains(relation.getRefType())) {
            throw new ServiceException("关联类型不合法：" + relation.getRefType());
        }
        // 3. ref 对象存在未删（按类型分表查）
        validateRefObject(relation.getRefType(), relation.getRefId());
        // 4. 查重（决策 D5：同 honor_id+ref_type+ref_id 有效行存在则拒；不依赖 DB 唯一索引）
        if (honorRelationMapper.countActiveRelation(relation.getHonorId(), relation.getRefType(), relation.getRefId()) > 0) {
            throw new ServiceException("该关联已存在");
        }
        relation.setDelFlag("0");
        relation.setCreateBy(operName);
        honorRelationMapper.insert(relation);
        return relation;
    }

    // ========================================================
    //  软删单条关联（先查关联再过荣誉 scoped 闸门）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteRelationById(Long relationId, String operName) {
        if (relationId == null) {
            throw new ServiceException("relationId 不能为空");
        }
        HonorRelation db = honorRelationMapper.selectById(relationId);
        if (db == null || !"0".equals(db.getDelFlag())) {
            throw new ServiceException("关联不存在或已删除");
        }
        // 过荣誉 scoped 闸门
        selectHonorById(db.getHonorId());
        return honorRelationMapper.softDeleteByRelationId(relationId, operName);
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /**
     * 校验 ref 对象存在且未删（按 refType 分表查）：
     * PROJECT → project 表 + leader_id NOT NULL 兜底
     * RESEARCHER → sys_user 表（直查 user_id；del_flag='0' 由 Service 补判，因 SysUserMapper.selectUserById 不带 del_flag 过滤）
     * UNIT → cooperative_unit 表
     */
    private void validateRefObject(String refType, Long refId) {
        if (REF_TYPE_PROJECT.equals(refType)) {
            Project p = projectMapper.selectById(refId);
            if (p == null || !"0".equals(p.getDelFlag())) {
                throw new ServiceException("关联课题不存在或已删除");
            }
            return;
        }
        if (REF_TYPE_RESEARCHER.equals(refType)) {
            // D2：ref_id = sys_user.user_id；直查 sys_user 存在即视为有效。
            // 禁用账号不拦——历史荣誉可关联离职/禁用人员（审查 M2 留档）。
            // selectUserById 不带 del_flag 过滤；Service 补判 del_flag='0' 拒绝已删用户。
            SysUser u = sysUserMapper.selectUserById(refId);
            if (u == null || !"0".equals(u.getDelFlag())) {
                throw new ServiceException("关联人员不存在");
            }
            return;
        }
        if (REF_TYPE_UNIT.equals(refType)) {
            CooperativeUnit unit = cooperativeUnitMapper.selectUnitById(refId);
            if (unit == null || !"0".equals(unit.getDelFlag())) {
                throw new ServiceException("关联合作单位不存在或已删除");
            }
        }
    }

    /**
     * 当前登录用户是否「精确」为 researcher（不含 admin）。
     * 照抄 ProjectServiceImpl.java:672-696 现有实现风格，单次遍历 + hasAdmin/hasResearcher 标志。
     */
    private boolean isResearcher() {
        try {
            List<SysRole> roles = SecurityUtils.getLoginUser().getUser().getRoles();
            if (roles == null || roles.isEmpty()) {
                return false;
            }
            boolean hasAdmin = false;
            boolean hasResearcher = false;
            for (SysRole r : roles) {
                if (r == null || StringUtils.isEmpty(r.getRoleKey())) {
                    continue;
                }
                if ("admin".equals(r.getRoleKey())) {
                    hasAdmin = true;
                }
                if ("researcher".equals(r.getRoleKey())) {
                    hasResearcher = true;
                }
            }
            return hasResearcher && !hasAdmin;
        } catch (Exception e) {
            return false;
        }
    }
}
