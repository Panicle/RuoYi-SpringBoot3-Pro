-- ============================================================================
-- 科研管理平台 课题变更3：课题联络人 + 本单位主持标识 + 外部人员通道
-- 版本: V1.0.20
-- 日期: 2026-08-17
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 用户需求"科研所人员参与外单位课题承担录入的称为课题联络人；课题需标识
--   是否本单位主持；外单位人员由联络人录入维护"（产品裁决：外部人员进 sys_user 禁登录；
--   联络人可自建课题 → researcher 放开 新增/修改/成员 三个按钮权限）
-- 说明:
--   1. project 加 2 列（PL 块预检幂等）：
--      self_hosted CHAR(1) DEFAULT '1'  -- 1=本单位（科研所）主持 / 0=外单位主持
--      host_unit_id BIGINT              -- 外单位主持时 → cooperative_unit.unit_id
--      存量行 self_hosted 统一置 '1'（历史课题均为本所主持）
--   2. 字典 member_role 加 LIAISON 联络人（dict_code 20147；现有 HOST 20042/PARTICIPANT 20043）
--   3. sys_dept 预建"外部人员"虚拟部门（挂根部门下）：外单位人员账号统一挂此部门，
--      status='1'（停用=禁止登录），不挂任何角色
--   4. researcher(105) 补挂课题 新增(2013)/修改(2014)/成员(2018) —— 联络人自建课题所需
--   5. 幂等：全部 INSERT ... WHERE NOT EXISTS / PL 块预检，可重复执行
-- ============================================================================

-- ============================================================================
-- 一、project 加 2 列
-- ============================================================================

-- 1. self_hosted 本单位主持标识
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'PROJECT' AND COLUMN_NAME = 'SELF_HOSTED';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE project ADD self_hosted CHAR(1) DEFAULT ''1''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN project.self_hosted IS ''是否本单位主持（1 本单位/0 外单位主持）''';
    END IF;
END;

-- 2. host_unit_id 主持单位（外单位主持时关联 cooperative_unit）
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'PROJECT' AND COLUMN_NAME = 'HOST_UNIT_ID';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE project ADD host_unit_id BIGINT';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN project.host_unit_id IS ''主持单位ID（外单位主持时关联 cooperative_unit.unit_id；本单位主持为空）''';
    END IF;
END;

-- 3. 存量课题统一置本单位主持
UPDATE project SET self_hosted = '1' WHERE self_hosted IS NULL;

-- ============================================================================
-- 二、字典 member_role 加 LIAISON 联络人
-- ============================================================================
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20147, 3, '联络人', 'LIAISON', 'member_role', '', 'warning', 'N', '0', 'admin', SYSDATE, '课题联络人（本所人员参与外单位课题、承担系统录入）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20147);

-- ============================================================================
-- 三、"外部人员"虚拟部门（挂根部门下；外单位人员账号统一归属）
-- ============================================================================
INSERT INTO sys_dept (parent_id, ancestors, dept_name, order_num, status, del_flag, create_by, create_time)
SELECT r.dept_id, r.ancestors || ',' || r.dept_id, '外部人员', 99, '0', '0', 'admin', SYSDATE
FROM (SELECT dept_id, NVL(ancestors, '0') AS ancestors FROM sys_dept WHERE parent_id = 0 AND del_flag = '0') r
WHERE NOT EXISTS (SELECT 1 FROM sys_dept WHERE dept_name = '外部人员' AND del_flag = '0');

-- ============================================================================
-- 四、researcher(105) 补挂课题 新增/修改/成员 按钮（联络人自建课题）
-- ============================================================================
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2013 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2013);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2014 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2014);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2018 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2018);

COMMIT;

-- ============================================================================
-- 完
-- ============================================================================
