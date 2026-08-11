-- ============================================================================
-- 科研管理平台 业务角色初始化脚本
-- 版本: V1.0.4
-- 日期: 2026-08-11
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 说明:
--   1. 依赖若依框架 sys_role / sys_role_menu 表（已通过 ruoyi-dm8.dmp 导入）
--   2. 依赖 V1.0.2__menu_permissions.sql（menu_id 2000-2007）
--   3. 创建 6 个业务角色（admin 为框架内置，共成 7 角色体系）
--   4. role_id 从 100 开始，避免与框架内置角色冲突
--   5. data_scope: 1=全部数据 2=自定义 3=本部门 4=本部门及以下 5=仅本人
--   6. 幂等: INSERT ... SELECT ... WHERE NOT EXISTS 防重复
--   7. 菜单挂载为阶段1基线（科研人员管理），按钮级权限后续按模块细调
-- ============================================================================

-- ============================================================================
-- 一、业务角色（sys_role）
-- ============================================================================

-- 1. 所领导（全所只读）
INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_by, create_time, remark)
SELECT 100, '所领导', 'leader', 2, '1', 1, 1, '0', '0', 'admin', SYSDATE, '全所数据只读，宏观掌握科研进展'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_id = 100);

-- 2. 科管人员（全所管理）
INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_by, create_time, remark)
SELECT 101, '科管人员', 'science_admin', 3, '1', 1, 1, '0', '0', 'admin', SYSDATE, '全所科研业务管理、课题审批、加计扣除导出'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_id = 101);

-- 3. 办公室人员（全所只读）
INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_by, create_time, remark)
SELECT 102, '办公室人员', 'office', 4, '1', 1, 1, '0', '0', 'admin', SYSDATE, '企法/财务监督，全所数据只读'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_id = 102);

-- 4. 劳人科（全所审核）
INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_by, create_time, remark)
SELECT 103, '劳人科', 'labor_hr', 5, '1', 1, 1, '0', '0', 'admin', SYSDATE, '人员编制、课题组成员调整审批、荣誉审核'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_id = 103);

-- 5. 室主任（本部门）
INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_by, create_time, remark)
SELECT 104, '室主任', 'dept_leader', 6, '3', 1, 1, '0', '0', 'admin', SYSDATE, '本部门成员课题管理及审批'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_id = 104);

-- 6. 科研人员（仅本人）
INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_by, create_time, remark)
SELECT 105, '科研人员', 'researcher', 7, '5', 1, 1, '0', '0', 'admin', SYSDATE, '本人主持/参与课题维护、工时填报'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_id = 105);

-- ============================================================================
-- 二、角色菜单挂载（sys_role_menu，阶段1基线）
-- 科管/室主任: 全部按钮（2000-2007）
-- 劳人科: 目录+菜单+查询+修改（2000,2001,2002,2004）
-- 所领导/办公室/科研人员: 目录+菜单+查询（2000,2001,2002）只读
-- ============================================================================

-- 科管人员 101: 全部
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2000);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2001 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2001);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2002 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2002);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2003 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2003);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2004 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2004);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2005 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2005);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2006 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2006);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2007 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2007);

-- 室主任 104: 全部
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2000);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2001 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2001);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2002 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2002);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2003 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2003);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2004 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2004);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2005 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2005);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2006 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2006);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2007 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2007);

-- 劳人科 103: 目录+菜单+查询+修改
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2000);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2001 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2001);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2002 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2002);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2004 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2004);

-- 所领导 100: 只读
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2000);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2001 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2001);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2002 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2002);

-- 办公室人员 102: 只读
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2000);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2001 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2001);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2002 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2002);

-- 科研人员 105: 只读（数据范围仅本人）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2000);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2001 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2001);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2002 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2002);

-- ============================================================================
-- 完
-- ============================================================================
