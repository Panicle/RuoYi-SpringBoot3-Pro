package com.ruoyi.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ruoyi.biz.domain.UserProfile;

import java.util.List;

/**
 * 科研人员档案 Service 接口
 *
 * @author kys
 * @date 2026-08-11
 */
public interface IUserProfileService extends IService<UserProfile> {

    /**
     * 查询科研人员档案列表（含 sys_user / sys_dept 关联信息）
     *
     * @param researcher 查询条件
     * @return 科研人员档案集合
     */
    public List<UserProfile> selectUserProfileList(UserProfile researcher);

    /**
     * 对话精灵人员查询（sys_user 主表 + 档案 LEFT JOIN；@DataScope 三档：
     * data_scope=1 全部 / 3 本部门 / 5 仅本人）
     *
     * @param query 查询条件（nickName 模糊匹配昵称或登录名，可空）
     * @return 人员列表（含未建档账号，档案字段可能为 null）
     */
    public List<UserProfile> selectChatUserList(UserProfile query);

    /**
     * 查询单条科研人员档案（含关联信息）
     *
     * @param profileId 档案ID
     * @return 科研人员档案
     */
    public UserProfile selectUserProfileById(Long profileId);

    /**
     * 新增科研人员档案（校验 userId 非空且未存在有效档案）
     *
     * @param researcher 科研人员档案
     * @return 结果
     */
    public int insertUserProfile(UserProfile researcher);

    /**
     * 修改科研人员档案（userId 以库中原值为准，不允许变更）
     *
     * @param researcher 科研人员档案
     * @return 结果
     */
    public int updateUserProfile(UserProfile researcher);

    /**
     * 批量逻辑删除科研人员档案
     *
     * @param profileIds 需要删除的档案ID集合
     * @return 结果
     */
    public int deleteUserProfileByIds(Long[] profileIds);

    /**
     * Excel 批量导入科研人员档案（按 userId 判存，支持 updateSupport）
     *
     * @param list            数据列表
     * @param titleNum        标题占用行数
     * @param isUpdateSupport 是否更新支持
     * @param operName        操作用户
     * @return 结果消息
     */
    public String importUserProfile(List<UserProfile> list, int titleNum,
                                    Boolean isUpdateSupport, String operName);
}