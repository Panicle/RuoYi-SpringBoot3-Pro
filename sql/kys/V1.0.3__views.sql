-- ============================================================================
-- 科研管理平台 业务视图脚本
-- 版本: V1.0.3
-- 数据库: 达梦DM8
-- 说明: 所有视图使用 CREATE OR REPLACE，可重复执行
-- ============================================================================

CREATE OR REPLACE VIEW v_biz_user_profile AS
SELECT
    u.user_id,
    u.user_name,
    u.nick_name,
    u.email,
    u.phonenumber,
    u.status       AS user_status,
    d.dept_id,
    d.dept_name,
    r.role_id,
    r.role_name,
    r.role_key,
    rp.profile_id,
    rp.edu_level,
    rp.title_level,
    rp.research_direction,
    rp.research_area,
    rp.id_number,
    rp.entry_date,
    rp.office_phone
FROM sys_user u
LEFT JOIN sys_dept d          ON u.dept_id = d.dept_id
LEFT JOIN sys_user_role ur    ON u.user_id = ur.user_id
LEFT JOIN sys_role r          ON ur.role_id = r.role_id
LEFT JOIN biz_user_profile rp ON u.user_id = rp.user_id
WHERE u.del_flag = '0';

-- 注：达梦不支持对视图执行 COMMENT ON TABLE，视图用途见头部注释
