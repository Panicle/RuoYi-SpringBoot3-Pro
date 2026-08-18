package com.ruoyi.biz.domain.bo;

import lombok.Data;

/**
 * 外单位人员录入请求体（V1.0.20：课题联络人维护外单位课题的外部人员）
 *
 * <p>后端自动生成登录账号（EXT+时间戳）、挂"外部人员"虚拟部门、status='1' 禁登录；
 * 姓名（nickName）必填，其余档案字段可选。返回新建 userId 供后续 addMembers 关联。</p>
 *
 * @author kys
 * @date 2026-08-17
 */
@Data
public class ExternalMemberBo {

    /** 姓名（必填，写入 sys_user.nick_name） */
    private String nickName;

    /** 手机号 */
    private String phonenumber;

    /** 所属外部单位名称（写入 sys_user.remark） */
    private String unitName;

    /** 学历（edu_level 字典） */
    private String eduLevel;

    /** 职称（title_level 字典） */
    private String titleLevel;

    /** 学位 */
    private String degree;

    /** 专业 */
    private String major;

    /** 研究方向 */
    private String researchDirection;
}
