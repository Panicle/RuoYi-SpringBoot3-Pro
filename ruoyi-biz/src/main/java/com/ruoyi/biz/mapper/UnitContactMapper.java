package com.ruoyi.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.biz.domain.UnitContact;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 合作单位联系人 Mapper 接口
 *
 * <p>自定义 XML 不走 @TableLogic 自动过滤，del_flag='0' 显式声明。</p>
 *
 * @author kys
 * @date 2026-08-13
 */
public interface UnitContactMapper extends BaseMapper<UnitContact> {

    /**
     * 查询单位联系人列表（del_flag='0'；主联系人排前再按 contact_id）
     */
    List<UnitContact> selectContactList(@Param("unitId") Long unitId);

    /**
     * 批量逻辑删除（updateBy / del_flag='2'）
     */
    int softDeleteByIds(@Param("contactIds") Long[] contactIds, @Param("updateBy") String updateBy);
}
