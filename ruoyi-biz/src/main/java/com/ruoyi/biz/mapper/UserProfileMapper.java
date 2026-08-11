package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.UserProfile;
import com.ruoyi.common.annotation.DataScope;

import java.util.List;

/**
 * 科研人员档案 Mapper 接口
 *
 * @author kys
 * @date 2026-08-11
 */
public interface UserProfileMapper extends BaseMapper<UserProfile> {

    /**
     * 查询科研人员档案列表（自行 JOIN sys_user / sys_dept，避免视图多角色重复行）
     *
     * @param researcher 查询条件（userId、nickName、eduLevel、titleLevel、researchDirection、deptId、params.beginCreateTime/endCreateTime）
     * @return 科研人员档案集合（含 nickName / deptId / deptName）
     */
    @DataScope(deptAlias = "d", userAlias = "u")
    public List<UserProfile> selectUserProfileViewList(UserProfile researcher);

    /**
     * 通过档案ID查询单条科研人员档案（自行 JOIN 含 dept 信息）
     *
     * @param profileId 档案ID
     * @return 科研人员档案
     */
    public UserProfile selectUserProfileViewById(Long profileId);
}