-- ============================================================================
-- 科研管理平台 阶段2变更：课题管理模块数据库基线
-- 版本: V1.0.6
-- 日期: 2026-08-12
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 任务卡_阶段2_课题管理 §3.7
-- 说明:
--   1. project 表新增 project_no（唯一） + project_type 两列
--   2. 新增字典 project_type（dict_id=221，dict_code 20091-20096，6值大写）
--   3. 菜单 2010-2019：科研管理目录(M 2010) + 课题管理(C 2011) + 8 个 F 按钮(2012-2019)
--   4. 6 业务角色 + admin 对菜单 2010-2019 的差异化挂载（按 §3.7.4 矩阵）
--   5. 幂等：所有语句走 INSERT/UPDATE/DELETE 配套的 WHERE NOT EXISTS / IF NOT EXISTS / IF EXISTS
--      重跑全绿（V1.0.6 二次重跑必须零副作用）
--   6. 已落库约束：项目编号唯一索引 idx_project_no_uk（UNIQUE on project_no）
--      存在同名索引时不再创建，避免 ORA-01408
-- ============================================================================

-- ============================================================================
-- 一、project 表结构变更（加 2 列 + 1 唯一索引）
-- 期望：列数 15 → 17，新增 idx_project_no_uk
-- ============================================================================

-- 1. 新增 project_no 课题编号列（IF NOT EXISTS 保证二次重跑零副作用）
ALTER TABLE project ADD IF NOT EXISTS project_no VARCHAR(50) DEFAULT NULL;
COMMENT ON COLUMN project.project_no IS '课题编号（格式KY-{yyyy}-{3位流水}，唯一）';

-- 2. 新增 project_type 课题级别列
ALTER TABLE project ADD IF NOT EXISTS project_type VARCHAR(20) DEFAULT NULL;
COMMENT ON COLUMN project.project_type IS '课题级别（字典 project_type：NATIONAL/PROVINCIAL/CR_GROUP/COMPANY/INSTITUTE/LATERAL）';

-- 3. 唯一索引（项目编号唯一）
--    通过 PL 匿名块预检 USER_INDEXES 实现幂等创建（达梦 CREATE INDEX 无 IF NOT EXISTS）
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_INDEXES WHERE INDEX_NAME = 'IDX_PROJECT_NO_UK';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX idx_project_no_uk ON project (project_no)';
    END IF;
END;

-- ============================================================================
-- 二、新增字典 project_type（dict_id=221，dict_code 20091-20096）
-- 字典值大写（与 V1.0.5 一致），含 list_class 样式标签
-- ============================================================================

-- 1. 字典类型
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status, create_by, create_time, remark)
SELECT 221, '课题级别', 'project_type', '0', 'admin', SYSDATE, '课题立项级别（与 honor_level 同档分级）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_id = 221);

-- 2. 字典数据（6 项 20091-20096）
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20091, 1, '国家级',     'NATIONAL',   'project_type', '', 'danger',  'N', '0', 'admin', SYSDATE, '国家级课题'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20091);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20092, 2, '省部级',     'PROVINCIAL', 'project_type', '', 'warning', 'N', '0', 'admin', SYSDATE, '省部级课题'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20092);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20093, 3, '国铁集团级', 'CR_GROUP',   'project_type', '', 'primary', 'N', '0', 'admin', SYSDATE, '国铁集团级课题'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20093);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20094, 4, '集团公司级', 'COMPANY',    'project_type', '', 'success', 'N', '0', 'admin', SYSDATE, '集团公司级课题'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20094);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20095, 5, '所级',       'INSTITUTE',  'project_type', '', 'info',    'N', '0', 'admin', SYSDATE, '所级课题'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20095);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20096, 6, '横向委托',   'LATERAL',    'project_type', '', 'default', 'N', '0', 'admin', SYSDATE, '横向委托课题'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20096);

-- ============================================================================
-- 三、菜单（2010-2019，共 10 项）
--   2010 M  科研管理目录
--   2011 C  课题管理（path=project, component=biz/project/index）
--   2012-2019 F  8 个按钮（query/add/edit/remove/export/archive/member/detail）
-- ============================================================================

-- 1. 2010 科研管理目录（M）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2010, '科研管理', 0, 5, 'biz', NULL, NULL, '',
       1, 0, 'M', '0', '0', '', 'form',
       'admin', SYSDATE, '', NULL, '科研管理顶级目录（阶段2：课题管理；后续阶段5/6扩展）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2010);

-- 2. 2011 课题管理（C）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2011, '课题管理', 2010, 1, 'project', 'biz/project/index', NULL, '',
       1, 0, 'C', '0', '0', 'biz:project:list', '#',
       'admin', SYSDATE, '', NULL, '课题管理菜单（CRUD+成员+状态机）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2011);

-- 3. 2012-2019 F 按钮（query/add/edit/remove/export/archive/member/detail）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2012, '查询', 2011, 1, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:project:query', '#',
       'admin', SYSDATE, '', NULL, '课题-查询'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2012);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2013, '新增', 2011, 2, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:project:add', '#',
       'admin', SYSDATE, '', NULL, '课题-新增'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2013);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2014, '修改', 2011, 3, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:project:edit', '#',
       'admin', SYSDATE, '', NULL, '课题-修改（与状态机变更共用）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2014);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2015, '删除', 2011, 4, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:project:remove', '#',
       'admin', SYSDATE, '', NULL, '课题-删除'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2015);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2016, '导出', 2011, 5, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:project:export', '#',
       'admin', SYSDATE, '', NULL, '课题-导出'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2016);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2017, '归档', 2011, 6, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:project:archive', '#',
       'admin', SYSDATE, '', NULL, '课题-归档（阶段2独有）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2017);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2018, '成员', 2011, 7, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:project:member', '#',
       'admin', SYSDATE, '', NULL, '课题-成员（列表/新增/删除/换主持人共用）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2018);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2019, '详情', 2011, 8, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:project:detail', '#',
       'admin', SYSDATE, '', NULL, '课题-详情（详情页按钮控制）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2019);

-- ============================================================================
-- 四、角色挂载（按 §3.7.4 矩阵）
--   admin(1)/science_admin(101): 全部 10 个 (2010-2019)
--   leader(100):                  2010, 2011, 2012            全所只读
--   office(102):                  2010, 2011, 2012            全所只读
--   labor_hr(103):                2010, 2011, 2012, 2018     成员审核
--   dept_leader(104):             2010, 2011, 2012, 2013, 2014, 2016, 2018, 2019  本部门
--   researcher(105):              2010, 2011, 2012, 2016, 2019  本人相关（数据范围data_scope=5；2016=export 修复轮2补充）
-- ============================================================================

-- admin 1：全部 10 个
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2010 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2010);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2011 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2011);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2012 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2012);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2013 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2013);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2014 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2014);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2015 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2015);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2016 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2016);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2017 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2017);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2018 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2018);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2019 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2019);

-- science_admin 101：全部 10 个
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2010 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2010);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2011 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2011);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2012 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2012);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2013 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2013);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2014 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2014);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2015 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2015);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2016 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2016);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2017 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2017);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2018 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2018);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2019 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2019);

-- leader 100：2010+2011+2012 只读全所
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2010 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2010);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2011 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2011);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2012 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2012);

-- office 102：2010+2011+2012 只读全所
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2010 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2010);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2011 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2011);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2012 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2012);

-- labor_hr 103：2010+2011+2012+2018 成员审核
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2010 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2010);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2011 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2011);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2012 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2012);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2018 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2018);

-- dept_leader 104：2010+2011+2012+2013+2014+2016+2018+2019（本部门，无 remove/archive）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2010 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2010);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2011 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2011);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2012 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2012);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2013 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2013);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2014 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2014);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2016 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2016);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2018 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2018);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2019 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2019);

-- researcher 105：2010+2011+2012+2016+2019 本人相关
--   （2016=export 按任务卡 §9.3 researcher 可导出其本人相关数据；修复轮2 补充，幂等无副作用）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2010 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2010);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2011 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2011);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2012 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2012);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2016 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2016);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2019 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2019);

-- ============================================================================
-- 完
-- ============================================================================