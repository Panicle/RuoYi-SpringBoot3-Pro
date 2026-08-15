-- ============================================================================
-- 科研管理平台 阶段7：荣誉管理数据库基线
-- 版本: V1.0.14
-- 日期: 2026-08-15
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 任务卡_阶段7_荣誉管理 §一（数据模型扩展）
-- 说明:
--   1. honor 表加 2 列（certificate_no / certificate_url）
--   2. honor_relation 表加 2 列（role_desc / contribution_desc）+ 更新 ref_type 列注释
--      （追加 UNIT 合作单位说明）
--   3. 字典 honor_ref_type（dict_id=231，dict_code 20139-20141 共 3 项）
--      复用 honor_level(207)/honor_type(208)，不新建不修改
--   4. 菜单 2060-2066 共 7 项（2060 C 荣誉管理 parent=2010 科研管理，order_num=6；
--      2061-2066 F 按钮 查询/新增/修改/删除/导出/关联维护）
--   5. 角色挂载（任务卡 §1 矩阵，共 29 条 sys_role_menu）：
--      admin(1)/science_admin(101) 全部 7 项；
--      leader(100)/office(102)/labor_hr(103)/dept_leader(104)/researcher(105)
--      各挂 2060/2061/2065 共 3 项（只读）
--      合计 7+7+3+3+3+3+3 = 29 条
-- ============================================================================

-- ============================================================================
-- 一、honor 加 2 列（每列独立 PL 块预检 user_tab_columns 幂等）
-- ============================================================================

-- 1. certificate_no 证书编号
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'HONOR' AND COLUMN_NAME = 'CERTIFICATE_NO';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE honor ADD certificate_no VARCHAR(100)';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN honor.certificate_no IS ''证书编号''';
    END IF;
END;

-- 2. certificate_url 证书附件路径
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'HONOR' AND COLUMN_NAME = 'CERTIFICATE_URL';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE honor ADD certificate_url VARCHAR(500)';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN honor.certificate_url IS ''证书附件路径''';
    END IF;
END;

-- ============================================================================
-- 二、honor_relation 加 2 列 + 更新 ref_type 注释（幂等）
-- ============================================================================

-- 1. role_desc 角色/名次说明
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'HONOR_RELATION' AND COLUMN_NAME = 'ROLE_DESC';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE honor_relation ADD role_desc VARCHAR(200)';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN honor_relation.role_desc IS ''角色/名次说明''';
    END IF;
END;

-- 2. contribution_desc 贡献说明
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'HONOR_RELATION' AND COLUMN_NAME = 'CONTRIBUTION_DESC';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE honor_relation ADD contribution_desc VARCHAR(500)';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN honor_relation.contribution_desc IS ''贡献说明''';
    END IF;
END;

-- 3. ref_type 列注释更新（追加 UNIT 合作单位说明；COMMENT 天然幂等覆盖）
COMMENT ON COLUMN honor_relation.ref_type IS '关联类型（PROJECT课题/RESEARCHER科研人员(sys_user.user_id)/UNIT合作单位）';

-- ============================================================================
-- 三、字典 honor_ref_type（dict_id=231，dict_code 20139-20141 共 3 项）
--   20139 / 1 / 课题 / PROJECT / primary
--   20140 / 2 / 人员 / RESEARCHER / success
--   20141 / 3 / 合作单位 / UNIT / info
-- ============================================================================

-- 1. sys_dict_type honor_ref_type（dict_id=231）
INSERT INTO sys_dict_type
    (dict_id, dict_name, dict_type, status, create_by, create_time, update_by, update_time, remark)
SELECT 231, '荣誉关联类型', 'honor_ref_type', '0', 'admin', SYSDATE, '', NULL, '荣誉模块-关联类型（PROJECT课题/RESEARCHER科研人员/UNIT合作单位）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_id = 231);

-- 2. sys_dict_data 三条（按 dict_code 查存在性幂等）
INSERT INTO sys_dict_data
    (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, update_by, update_time, remark)
SELECT 20139, 1, '课题', 'PROJECT', 'honor_ref_type', '', 'primary', 'N', '0', 'admin', SYSDATE, '', NULL, '荣誉关联-课题'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20139);

INSERT INTO sys_dict_data
    (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, update_by, update_time, remark)
SELECT 20140, 2, '人员', 'RESEARCHER', 'honor_ref_type', '', 'success', 'N', '0', 'admin', SYSDATE, '', NULL, '荣誉关联-科研人员'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20140);

INSERT INTO sys_dict_data
    (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, update_by, update_time, remark)
SELECT 20141, 3, '合作单位', 'UNIT', 'honor_ref_type', '', 'info', 'N', '0', 'admin', SYSDATE, '', NULL, '荣誉关联-合作单位'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20141);

-- ============================================================================
-- 四、菜单 2060-2066（荣誉管理 + 6 F 按钮，parent=2010 科研管理，order_num=6）
--   2060 C  荣誉管理（path=honor, component=biz/honor/index, perms=biz:honor:list, icon=star）
--   2061 F  查询（biz:honor:query）
--   2062 F  新增（biz:honor:add）
--   2063 F  修改（biz:honor:edit）
--   2064 F  删除（biz:honor:remove）
--   2065 F  导出（biz:honor:export）
--   2066 F  关联维护（biz:honor:relation）
-- ============================================================================

-- 1. 2060 荣誉管理（C）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2060, '荣誉管理', 2010, 6, 'honor', 'biz/honor/index', NULL, '',
       1, 0, 'C', '0', '0', 'biz:honor:list', 'star',
       'admin', SYSDATE, '', NULL, '荣誉管理菜单（阶段7；证书附件/关联维护）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2060);

-- 2. 2061 查询
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2061, '查询', 2060, 1, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:honor:query', '#',
       'admin', SYSDATE, '', NULL, '荣誉-查询'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2061);

-- 3. 2062 新增
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2062, '新增', 2060, 2, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:honor:add', '#',
       'admin', SYSDATE, '', NULL, '荣誉-新增'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2062);

-- 4. 2063 修改
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2063, '修改', 2060, 3, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:honor:edit', '#',
       'admin', SYSDATE, '', NULL, '荣誉-修改'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2063);

-- 5. 2064 删除
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2064, '删除', 2060, 4, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:honor:remove', '#',
       'admin', SYSDATE, '', NULL, '荣誉-删除'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2064);

-- 6. 2065 导出
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2065, '导出', 2060, 5, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:honor:export', '#',
       'admin', SYSDATE, '', NULL, '荣誉-导出'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2065);

-- 7. 2066 关联维护
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2066, '关联维护', 2060, 6, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:honor:relation', '#',
       'admin', SYSDATE, '', NULL, '荣誉-关联维护（按 ref_type=PROJECT/RESEARCHER/UNIT 维护 honor_relation 子资源）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2066);

-- ============================================================================
-- 五、角色挂载（按任务卡 §1 矩阵，共 29 条 sys_role_menu）
--   admin(1) / science_admin(101)：全部 7 项（2060-2066）
--   leader(100) / office(102) / labor_hr(103) / dept_leader(104) / researcher(105)：
--     只读 3 项（2060 + 2061 + 2065）
--   合计 7+7+3+3+3+3+3 = 29 条
-- ============================================================================

-- admin 1：全部 7 项
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2060 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2060);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2061 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2061);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2062 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2062);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2063 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2063);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2064 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2064);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2065 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2065);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2066 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2066);

-- science_admin 101：全部 7 项
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2060 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2060);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2061 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2061);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2062 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2062);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2063 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2063);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2064 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2064);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2065 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2065);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2066 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2066);

-- leader 100：2060 + 2061 + 2065（只读）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2060 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2060);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2061 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2061);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2065 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2065);

-- office 102：2060 + 2061 + 2065（只读）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2060 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2060);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2061 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2061);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2065 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2065);

-- labor_hr 103：2060 + 2061 + 2065（只读）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2060 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2060);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2061 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2061);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2065 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2065);

-- dept_leader 104：2060 + 2061 + 2065（只读）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2060 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2060);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2061 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2061);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2065 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2065);

-- researcher 105：2060 + 2061 + 2065（只读）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2060 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2060);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2061 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2061);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2065 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2065);

-- ============================================================================
-- 完
-- ============================================================================