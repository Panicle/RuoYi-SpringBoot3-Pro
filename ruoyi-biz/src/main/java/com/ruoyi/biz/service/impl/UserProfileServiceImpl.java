package com.ruoyi.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ruoyi.biz.domain.UserProfile;
import com.ruoyi.biz.mapper.UserProfileMapper;
import com.ruoyi.biz.service.IUserProfileService;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.bean.BeanValidators;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * 科研人员档案 Service 实现
 *
 * @author kys
 * @date 2026-08-11
 */
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl extends ServiceImpl<UserProfileMapper, UserProfile>
        implements IUserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileServiceImpl.class);

    private final UserProfileMapper userProfileMapper;
    protected final Validator validator;

    @Override
    public List<UserProfile> selectUserProfileList(UserProfile researcher) {
        return userProfileMapper.selectUserProfileViewList(researcher);
    }

    @Override
    public UserProfile selectUserProfileById(Long profileId) {
        return userProfileMapper.selectUserProfileViewById(profileId);
    }

    /**
     * 新增：校验 userId 非空、且不存在有效档案后插入；delFlag 由本方法显式置 "0"（双保险，不依赖 DB 默认值）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertUserProfile(UserProfile researcher) {
        if (researcher == null || researcher.getUserId() == null) {
            throw new ServiceException("userId 不能为空");
        }
        UserProfile existing = userProfileMapper.selectOne(
                new LambdaQueryWrapper<UserProfile>().eq(UserProfile::getUserId, researcher.getUserId()));
        if (existing != null) {
            throw new ServiceException("userId=" + researcher.getUserId() + " 的档案已存在");
        }
        // delFlag 双保险：显式置 "0"，避免无 MetaObjectHandler 时依赖 DB 默认值
        if (StringUtils.isEmpty(researcher.getDelFlag())) {
            researcher.setDelFlag("0");
        }
        return userProfileMapper.insert(researcher);
    }

    /**
     * 修改：以库中原 userId 为准，覆盖入参中的 userId，防止误改关联用户
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateUserProfile(UserProfile researcher) {
        if (researcher == null || researcher.getProfileId() == null) {
            throw new ServiceException("profileId 不能为空");
        }
        UserProfile dbRecord = userProfileMapper.selectById(researcher.getProfileId());
        if (dbRecord == null) {
            throw new ServiceException("档案不存在");
        }
        // userId 以库中原值为准
        researcher.setUserId(dbRecord.getUserId());
        return userProfileMapper.updateById(researcher);
    }

    @Override
    public int deleteUserProfileByIds(Long[] profileIds) {
        if (profileIds == null || profileIds.length == 0) {
            return 0;
        }
        // @TableLogic 标识 del_flag；BaseMapper.deleteByIds 会自动改写为 UPDATE ... SET del_flag='2'
        return userProfileMapper.deleteByIds(Arrays.asList(profileIds));
    }

    @Override
    public String importUserProfile(List<UserProfile> list, int titleNum,
                                    Boolean isUpdateSupport, String operName) {
        if (StringUtils.isNull(list) || list.isEmpty()) {
            throw new ServiceException("导入数据不能为空！");
        }
        int successNum = 0;
        int failureNum = 0;
        StringBuilder successMsg = new StringBuilder();
        StringBuilder failureMsg = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            UserProfile researcher = list.get(i);
            try {
                BeanValidators.validateWithException(validator, researcher);
                UserProfile existing = userProfileMapper.selectOne(
                        new LambdaQueryWrapper<UserProfile>().eq(UserProfile::getUserId, researcher.getUserId()));
                if (existing == null) {
                    researcher.setCreateBy(operName);
                    userProfileMapper.insert(researcher);
                    successNum++;
                    successMsg.append("<br/>").append(successNum)
                            .append("、记录").append(i + titleNum + 2)
                            .append("：").append(researcher.getUserId()).append(" 导入成功");
                } else if (isUpdateSupport) {
                    researcher.setProfileId(existing.getProfileId());
                    researcher.setUpdateBy(operName);
                    // userId 不变
                    researcher.setUserId(existing.getUserId());
                    userProfileMapper.updateById(researcher);
                    successNum++;
                    successMsg.append("<br/>").append(successNum)
                            .append("、记录").append(i + titleNum + 2)
                            .append("：").append(researcher.getUserId()).append(" 更新成功");
                } else {
                    failureNum++;
                    failureMsg.append("<br/>").append(failureNum)
                            .append("、记录").append(i + titleNum + 2)
                            .append("：").append(researcher.getUserId()).append(" 已存在");
                }
            } catch (Exception e) {
                failureNum++;
                String msg = "<br/>" + failureNum + "、记录" + (i + titleNum + 2)
                        + "：" + researcher.getUserId() + " 导入失败：";
                failureMsg.append(msg).append(e.getMessage());
                log.error(msg, e);
            }
        }
        if (failureNum > 0) {
            failureMsg.insert(0, "很抱歉，导入失败！共 " + failureNum + " 条数据格式不正确，错误如下：");
            throw new ServiceException(failureMsg.toString());
        } else {
            successMsg.insert(0, "恭喜您，数据已全部导入成功！共 " + successNum + " 条，数据如下：");
        }
        return successMsg.toString();
    }
}