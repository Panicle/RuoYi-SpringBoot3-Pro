-- ============================================================================
-- 科研管理平台 阶段1变更：科研档案并入用户管理
-- 版本: V1.0.5
-- 日期: 2026-08-11
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 用户裁决（2026-08-11）—— 科研人员不作为独立模块，改为用户管理补充
-- 说明:
--   1. 删除身份证号字段 id_number
--   2. 补三个字段 degree/major/bio（学位字典化，专业/简介文本）
--   3. 新增 3 个字典类型：research_area（8项）、research_direction（12项）、degree_level（3项）
--   4. 菜单调整：2000/2001 删除，2002-2007 parent 改为 用户管理 (menu_id=100)，新增 2008/2009 F按钮
--   5. 业务角色对 2000/2001 的引用替换为 2008/2009（researcher/leader/office 至少 list+query）
--   6. 视图 v_biz_user_profile 同步更新（去 id_number，加 degree/major/bio）
--   7. 幂等：使用 IF EXISTS / IF NOT EXISTS / WHERE NOT EXISTS，可重复执行
-- ============================================================================

-- ============================================================================
-- 一、biz_user_profile 表结构变更
-- ============================================================================

-- 1. 删除身份证号列
ALTER TABLE biz_user_profile DROP COLUMN IF EXISTS id_number;

-- 2. 补三个字段（学位/专业/个人简介）
ALTER TABLE biz_user_profile ADD IF NOT EXISTS degree VARCHAR(20) DEFAULT NULL;
ALTER TABLE biz_user_profile ADD IF NOT EXISTS major  VARCHAR(100) DEFAULT NULL;
ALTER TABLE biz_user_profile ADD IF NOT EXISTS bio    VARCHAR(500) DEFAULT NULL;

-- 3. 注释
COMMENT ON COLUMN biz_user_profile.degree IS '学位（对应字典 degree_level：BACHELOR/MASTER/DOCTOR）';
COMMENT ON COLUMN biz_user_profile.major IS '所学专业（自由文本）';
COMMENT ON COLUMN biz_user_profile.bio   IS '个人简介（自由文本）';

-- ============================================================================
-- 二、新增 3 个字典类型 + 数据
-- 字典类型 dict_id 从 218 开始（接 217 之后）
-- 字典数据 dict_code 从 20068 开始（接 20067 之后）
-- 值大写（dict_value）
-- ============================================================================

-- 1. 字典类型
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status, create_by, create_time, remark)
SELECT 218, '研究领域',   'research_area',      '0', 'admin', SYSDATE, '科研人员研究领域（多选用 / 分隔）' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_id = 218);
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status, create_by, create_time, remark)
SELECT 219, '研究方向',   'research_direction', '0', 'admin', SYSDATE, '科研人员研究方向（多选用 / 分隔）' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_id = 219);
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status, create_by, create_time, remark)
SELECT 220, '学位',       'degree_level',       '0', 'admin', SYSDATE, '科研人员学位' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_id = 220);

-- 2. 研究领域 research_area（8 项，20068-20075）
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20068, 1, '车务', 'CHEWU',   'research_area', '', 'primary',  'N', '0', 'admin', SYSDATE, '车务专业' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20068);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20069, 2, '机务', 'JIWU',    'research_area', '', 'success',  'N', '0', 'admin', SYSDATE, '机务专业' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20069);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20070, 3, '工务', 'GONGWU',  'research_area', '', 'info',     'N', '0', 'admin', SYSDATE, '工务专业' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20070);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20071, 4, '电务', 'DIANWU',  'research_area', '', 'warning',  'N', '0', 'admin', SYSDATE, '电务专业' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20071);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20072, 5, '车辆', 'CHELIANG','research_area', '', 'danger',   'N', '0', 'admin', SYSDATE, '车辆专业' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20072);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20073, 6, '房建', 'FANGJIAN','research_area', '', 'primary',  'N', '0', 'admin', SYSDATE, '房建专业' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20073);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20074, 7, '信息', 'XINXI',   'research_area', '', 'success',  'N', '0', 'admin', SYSDATE, '信息专业' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20074);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20075, 8, '综合', 'ZONGHE',  'research_area', '', 'info',     'N', '0', 'admin', SYSDATE, '综合专业' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20075);

-- 3. 研究方向 research_direction（12 项，20076-20087）
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20076, 1,  '北斗定位',     'BEIDONG',  'research_direction', '', 'primary',  'N', '0', 'admin', SYSDATE, '北斗定位方向' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20076);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20077, 2,  '人工智能',     'AI',       'research_direction', '', 'success',  'N', '0', 'admin', SYSDATE, '人工智能方向' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20077);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20078, 3,  '物联网',       'IOT',      'research_direction', '', 'info',     'N', '0', 'admin', SYSDATE, '物联网方向' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20078);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20079, 4,  '通信',         'TONGXIN',  'research_direction', '', 'warning',  'N', '0', 'admin', SYSDATE, '通信方向' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20079);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20080, 5,  '大数据',       'BIGDATA',  'research_direction', '', 'danger',   'N', '0', 'admin', SYSDATE, '大数据方向' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20080);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20081, 6,  '云计算',       'CLOUD',    'research_direction', '', 'primary',  'N', '0', 'admin', SYSDATE, '云计算方向' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20081);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20082, 7,  '智能检测监测', 'JIANCE',   'research_direction', '', 'success',  'N', '0', 'admin', SYSDATE, '智能检测监测方向' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20082);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20083, 8,  '信号控制',     'XINHAO',   'research_direction', '', 'info',     'N', '0', 'admin', SYSDATE, '信号控制方向' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20083);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20084, 9,  '牵引供电',     'GONGDIAN', 'research_direction', '', 'warning',  'N', '0', 'admin', SYSDATE, '牵引供电方向' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20084);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20085, 10, '工程材料',     'CAILIAO',  'research_direction', '', 'danger',   'N', '0', 'admin', SYSDATE, '工程材料方向' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20085);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20086, 11, '安全应急',     'ANQUAN',   'research_direction', '', 'primary',  'N', '0', 'admin', SYSDATE, '安全应急方向' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20086);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20087, 12, '节能环保',     'HUANBAO',  'research_direction', '', 'success',  'N', '0', 'admin', SYSDATE, '节能环保方向' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20087);

-- 4. 学位 degree_level（3 项，20088-20090）
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20088, 1, '学士', 'BACHELOR', 'degree_level', '', 'info',     'N', '0', 'admin', SYSDATE, '学士学位' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20088);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20089, 2, '硕士', 'MASTER',   'degree_level', '', 'primary',  'N', '0', 'admin', SYSDATE, '硕士学位' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20089);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20090, 3, '博士', 'DOCTOR',   'degree_level', '', 'success',  'N', '0', 'admin', SYSDATE, '博士学位' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20090);

-- ============================================================================
-- 三、菜单调整（科研档案并入用户管理）
-- 步骤：
--   a. 2002-2007 的 parent_id 改为 用户管理 (menu_id=100)
--   b. 新增 2008（科研档案 list F 按钮）、2009（科研档案 query F 按钮）
--   c. 清空 role_menu 中 2000/2001 的引用
--   d. 把角色对新 F 按钮的引用补上（researcher/leader/office 至少有 list+query）
--   e. 删除 sys_menu 2000/2001
-- ============================================================================

-- 用户管理菜单 menu_id 实查为 100（RuoYi 标准），脚本以 100 为硬编码值

-- a. 移动 2002-2007 到用户管理下（parent 100）
UPDATE sys_menu SET parent_id = 100 WHERE menu_id BETWEEN 2002 AND 2007;

-- b. 新增 F 按钮 2008（科研档案 list）、2009（科研档案 query）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2008, '科研档案', 100, 1, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:userProfile:list', '#',
       'admin', SYSDATE, '', NULL, '科研档案-列表权限（原挂在 2001）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2008);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2009, '科研档案查询', 100, 2, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:userProfile:query', '#',
       'admin', SYSDATE, '', NULL, '科研档案-查询权限'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2009);

-- c. 清空 role_menu 中 2000/2001 的引用（先删，避免菜单删除后悬空）
DELETE FROM sys_role_menu WHERE menu_id IN (2000, 2001);

-- d. 把角色对新 F 按钮的引用补上
--    admin (1) 与所有业务角色（100-105）均挂 2008(list) + 2009(query)
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1,   2008 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1   AND menu_id = 2008);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1,   2009 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1   AND menu_id = 2009);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2008 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2008);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2009 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2009);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2008 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2008);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2009 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2009);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2008 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2008);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2009 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2009);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2008 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2008);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2009 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2009);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2008 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2008);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2009 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2009);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2008 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2008);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2009 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2009);

-- e. 删除菜单 2000（科研管理目录）与 2001（科研人员管理页面）
DELETE FROM sys_menu WHERE menu_id IN (2000, 2001);

-- ============================================================================
-- 四、视图 v_biz_user_profile 同步更新
-- 去 id_number，加 degree/major/bio
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
    rp.degree,
    rp.major,
    rp.bio,
    rp.research_direction,
    rp.research_area,
    rp.entry_date,
    rp.office_phone
FROM sys_user u
LEFT JOIN sys_dept d          ON u.dept_id = d.dept_id
LEFT JOIN sys_user_role ur    ON u.user_id = ur.user_id
LEFT JOIN sys_role r          ON ur.role_id = r.role_id
LEFT JOIN biz_user_profile rp ON u.user_id = rp.user_id
WHERE u.del_flag = '0';

-- 注：达梦不支持对视图执行 COMMENT ON TABLE，视图用途见文件头注释

-- ============================================================================
-- 完
-- ============================================================================