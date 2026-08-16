-- ============================================================================
-- 科研管理平台 阶段7收尾：放开 researcher 荣誉录入权限
-- 版本: V1.0.18
-- 日期: 2026-08-16
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 用户反馈"荣誉管理普通用户不能录入吗？"（fix-honor-perm-brief.md 产品裁决）
-- 说明:
--   1. researcher(105) 补挂写操作菜单 3 项（V1.0.14 只挂了只读 2060/2061/2065）：
--      2062 新增（biz:honor:add）
--      2063 修改（biz:honor:edit）
--      2066 关联维护（biz:honor:relation）
--   2. 删除（2064 biz:honor:remove）不放开：仍仅 admin/science_admin（产品裁决 1）
--   3. 幂等：INSERT ... SELECT ... WHERE NOT EXISTS（role_id + menu_id 存在性），可重复执行
-- ============================================================================

-- researcher 105：补挂 2062 新增（biz:honor:add）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2062 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2062);

-- researcher 105：补挂 2063 修改（biz:honor:edit）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2063 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2063);

-- researcher 105：补挂 2066 关联维护（biz:honor:relation）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2066 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2066);

-- ============================================================================
-- 完
-- ============================================================================
