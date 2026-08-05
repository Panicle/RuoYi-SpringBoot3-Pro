-- ============================================================================
-- 科研管理平台 菜单权限初始化脚本
-- 版本: V1.0.2
-- 日期: 2026-08-05
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 说明:
--   1. 依赖若依框架 sys_menu / sys_role_menu 表（已通过 ruoyi-dm8.dmp 导入）
--   2. 创建科研管理业务目录与阶段1科研人员管理菜单权限
--   3. 接口前缀: /biz/，权限标识: biz:userProfile:{action}
--   4. 管理员角色 role_id=1 挂载全部菜单
--   5. 幂等: 使用 INSERT ... SELECT ... WHERE NOT EXISTS 防重复
-- ============================================================================

-- ============================================================================
-- 一、菜单（sys_menu）
-- 说明: menu_id 从 2000 开始，避免与框架内置菜单（≤1060）冲突
--       目录 path=biz（对应接口前缀 /biz/），子菜单 path=userProfile
--       按若依约定: C 菜单承载 list 权限，F 按钮承载 action 权限
-- ============================================================================

-- 1. 科研管理目录（M）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2000, '科研管理', 0, 1, 'biz', NULL, NULL, '',
       1, 0, 'M', '0', '0', NULL, 'form',
       'admin', SYSDATE, '', NULL, '科研管理目录'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2000);

-- 2. 科研人员管理菜单（C）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2001, '科研人员管理', 2000, 1, 'userProfile', 'biz/userProfile/index', NULL, '',
       1, 0, 'C', '0', '0', 'biz:userProfile:list', 'user',
       'admin', SYSDATE, '', NULL, '科研人员管理菜单'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2001);

-- 3. 查询按钮（F）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2002, '查询', 2001, 1, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:userProfile:query', '#',
       'admin', SYSDATE, '', NULL, ''
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2002);

-- 4. 新增按钮（F）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2003, '新增', 2001, 2, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:userProfile:add', '#',
       'admin', SYSDATE, '', NULL, ''
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2003);

-- 5. 修改按钮（F）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2004, '修改', 2001, 3, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:userProfile:edit', '#',
       'admin', SYSDATE, '', NULL, ''
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2004);

-- 6. 删除按钮（F）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2005, '删除', 2001, 4, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:userProfile:remove', '#',
       'admin', SYSDATE, '', NULL, ''
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2005);

-- 7. 导出按钮（F）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2006, '导出', 2001, 5, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:userProfile:export', '#',
       'admin', SYSDATE, '', NULL, ''
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2006);

-- 8. 导入按钮（F）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2007, '导入', 2001, 6, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:userProfile:import', '#',
       'admin', SYSDATE, '', NULL, ''
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2007);

-- ============================================================================
-- 二、角色菜单关联（sys_role_menu）
-- 说明: 管理员角色 role_id=1 挂载上述全部菜单
-- ============================================================================

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, 2000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2000);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, 2001 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2001);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, 2002 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2002);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, 2003 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2003);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, 2004 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2004);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, 2005 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2005);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, 2006 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2006);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, 2007 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2007);

-- ============================================================================
-- 完
-- ============================================================================
