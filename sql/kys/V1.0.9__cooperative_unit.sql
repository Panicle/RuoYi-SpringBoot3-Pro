-- ============================================================================
-- 科研管理平台 阶段6：合作单位管理数据库基线
-- 版本: V1.0.9
-- 日期: 2026-08-13
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 任务卡_阶段6_合作单位 §1（数据模型精确值）
-- 说明:
--   1. cooperative_unit ALTER 加 6 列（parent_id/ancestors/company_type/company_category/expertise/order_num）
--      幂等 ADD IF NOT EXISTS + COMMENT ON COLUMN（重跑覆盖同值零副作用）
--      + 索引 idx_cooperative_unit_parent_id（PL 块预检 USER_INDEXES，达梦 CREATE INDEX 无 IF NOT EXISTS）
--   2. 新表 unit_contact（合作单位/高校老师通用联系人）
--      IDENTITY 主键 + 审计4字段 + remark + del_flag + 索引（PL 块预检 user_tables，达梦 CREATE TABLE 无 IF NOT EXISTS）
--   3. 字典 company_type（225, 20119-20120 MICRO/GENERAL） + company_category（226, 20121-20126 SOE/PRIVATE/JV/FOREIGN/INSTITUTION/OTHER）
--   4. 菜单 2020(C 合作单位管理 parent=2010) + 2021-2027 F(query/add/edit/remove/export/contact/treeselect) + 2029 F(biz:project:unit parent=2011)
--      跳号 2028 预留
--   5. 角色挂载按任务卡 §1.4 矩阵：admin(1)/science_admin(101) 全部 9 项；leader(100)/office(102)/labor_hr(103)/researcher(105) 只读 2020+2021；
--      dept_leader(104) 2020+2021+2029。共 29 条 sys_role_menu
--   6. 复用现有字典（不重建）：unit_type(216 INTERNAL/EXTERNAL)、external_unit_type(217 COMPANY/SCHOOL/OTHER)、cooperation_type(206 LEAD/PARTICIPANT/COLLABORATE)
-- ============================================================================

-- ============================================================================
-- 一、cooperative_unit 加 6 列 + 索引（幂等）
-- 期望：列数 14 → 20；新增 idx_cooperative_unit_parent_id
-- ============================================================================

-- 1. parent_id 父单位ID（0=顶级）
ALTER TABLE cooperative_unit ADD IF NOT EXISTS parent_id BIGINT DEFAULT 0;
COMMENT ON COLUMN cooperative_unit.parent_id IS '父单位ID（0=顶级；照 sys_dept 模型，公司树≤3层/学校树≤2层）';

-- 2. ancestors 祖级链（照 sys_dept 逗号分隔）
ALTER TABLE cooperative_unit ADD IF NOT EXISTS ancestors VARCHAR(200) DEFAULT '';
COMMENT ON COLUMN cooperative_unit.ancestors IS '祖级链（逗号分隔，首尾均为空字符串；例：,3,5,）';

-- 3. company_type 公司类型（字典 company_type：公司用）
ALTER TABLE cooperative_unit ADD IF NOT EXISTS company_type VARCHAR(20) DEFAULT NULL;
COMMENT ON COLUMN cooperative_unit.company_type IS '公司类型（字典 company_type：MICRO 小微企业 / GENERAL 一般纳税人，公司用）';

-- 4. company_category 公司性质（字典 company_category：公司用）
ALTER TABLE cooperative_unit ADD IF NOT EXISTS company_category VARCHAR(20) DEFAULT NULL;
COMMENT ON COLUMN cooperative_unit.company_category IS '公司性质（字典 company_category：SOE 国有企业 / PRIVATE 私营企业 / JV 合资企业 / FOREIGN 外资企业 / INSTITUTION 事业单位 / OTHER 其他，公司用）';

-- 5. expertise 擅长领域（公司/学校通用）
ALTER TABLE cooperative_unit ADD IF NOT EXISTS expertise VARCHAR(500) DEFAULT NULL;
COMMENT ON COLUMN cooperative_unit.expertise IS '擅长领域（公司/学校通用文本）';

-- 6. order_num 树内排序
ALTER TABLE cooperative_unit ADD IF NOT EXISTS order_num INT DEFAULT 0;
COMMENT ON COLUMN cooperative_unit.order_num IS '树内排序（同 parent_id 内排序）';

-- 7. 索引 idx_cooperative_unit_parent_id（PL 块预检）
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_INDEXES WHERE INDEX_NAME = 'IDX_COOPERATIVE_UNIT_PARENT_ID';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_cooperative_unit_parent_id ON cooperative_unit (parent_id)';
    END IF;
END;

-- ============================================================================
-- 二、新表 unit_contact（合作单位/高校老师通用联系人；PL 块预检，达梦 CREATE TABLE 无 IF NOT EXISTS）
-- ============================================================================

DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TABLES WHERE TABLE_NAME = 'UNIT_CONTACT';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE '
            CREATE TABLE unit_contact (
                contact_id    BIGINT IDENTITY(1,1) NOT NULL,
                unit_id       BIGINT       NOT NULL,
                contact_name  VARCHAR(50)  NOT NULL,
                position      VARCHAR(50)  DEFAULT NULL,
                phone         VARCHAR(20)  DEFAULT NULL,
                email         VARCHAR(100) DEFAULT NULL,
                major         VARCHAR(100) DEFAULT NULL,
                research_field VARCHAR(200) DEFAULT NULL,
                is_primary    CHAR(1)      DEFAULT ''0'',
                del_flag      CHAR(1)      DEFAULT ''0'',
                create_by     VARCHAR(64)  DEFAULT '''',
                create_time   TIMESTAMP    DEFAULT NULL,
                update_by     VARCHAR(64)  DEFAULT '''',
                update_time   TIMESTAMP    DEFAULT NULL,
                remark        VARCHAR(500) DEFAULT NULL,
                PRIMARY KEY (contact_id)
            )';
        EXECUTE IMMEDIATE 'COMMENT ON TABLE unit_contact IS ''合作单位联系人表（公司联系人/高校老师统一建模）''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN unit_contact.contact_id     IS ''联系人ID''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN unit_contact.unit_id        IS ''所属单位ID''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN unit_contact.contact_name   IS ''联系人姓名''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN unit_contact.position       IS ''职务''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN unit_contact.phone          IS ''联系电话''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN unit_contact.email         IS ''电子邮箱''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN unit_contact.major         IS ''专业（高校老师用）''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN unit_contact.research_field IS ''研究领域（高校老师用）''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN unit_contact.is_primary    IS ''是否主联系人（0否 1是）''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN unit_contact.del_flag      IS ''删除标志（0存在 2删除）''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN unit_contact.create_by     IS ''创建者''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN unit_contact.create_time   IS ''创建时间''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN unit_contact.update_by     IS ''更新者''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN unit_contact.update_time   IS ''更新时间''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN unit_contact.remark        IS ''备注''';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_unit_contact_unit_id ON unit_contact (unit_id)';
    END IF;
END;

-- ============================================================================
-- 三、新增字典 company_type（dict_id=225，公司类型 MICRO/GENERAL）
-- ============================================================================

INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status, create_by, create_time, remark)
SELECT 225, '公司类型', 'company_type', '0', 'admin', SYSDATE, '公司税务类型（MICRO 小微企业 / GENERAL 一般纳税人，公司用）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_id = 225);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20119, 1, '小微企业',     'MICRO',    'company_type', '', 'primary', 'N', '0', 'admin', SYSDATE, '小微企业'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20119);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20120, 2, '一般纳税人',   'GENERAL',  'company_type', '', 'success', 'N', '0', 'admin', SYSDATE, '一般纳税人'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20120);

-- ============================================================================
-- 四、新增字典 company_category（dict_id=226，公司性质 6 类）
-- ============================================================================

INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status, create_by, create_time, remark)
SELECT 226, '公司性质', 'company_category', '0', 'admin', SYSDATE, '公司经济性质（SOE 国有企业 / PRIVATE 私营企业 / JV 合资企业 / FOREIGN 外资企业 / INSTITUTION 事业单位 / OTHER 其他，公司用）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_id = 226);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20121, 1, '国有企业',   'SOE',         'company_category', '', 'danger',  'N', '0', 'admin', SYSDATE, '国有企业'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20121);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20122, 2, '私营企业',   'PRIVATE',     'company_category', '', 'primary', 'N', '0', 'admin', SYSDATE, '私营企业'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20122);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20123, 3, '合资企业',   'JV',          'company_category', '', 'warning', 'N', '0', 'admin', SYSDATE, '合资企业'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20123);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20124, 4, '外资企业',   'FOREIGN',     'company_category', '', 'info',    'N', '0', 'admin', SYSDATE, '外资企业'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20124);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20125, 5, '事业单位',   'INSTITUTION', 'company_category', '', 'success', 'N', '0', 'admin', SYSDATE, '事业单位'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20125);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20126, 6, '其他',       'OTHER',       'company_category', '', 'default', 'N', '0', 'admin', SYSDATE, '其他'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20126);

-- ============================================================================
-- 五、菜单 2020-2029（合作单位管理 + 课题关联）
--   2020 C  合作单位管理（parent=2010 科研管理）
--   2021 F  查询
--   2022 F  新增
--   2023 F  修改
--   2024 F  删除
--   2025 F  导出
--   2026 F  联系人维护
--   2027 F  树选择器（不需单独权限：admin/science_admin 挂载用于前端可见性）
--   2028    跳过（预留）
--   2029 F  课题关联合作单位（parent=2011 课题管理）
-- ============================================================================

-- 1. 2020 合作单位管理（C）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2020, '合作单位管理', 2010, 2, 'unit', 'biz/unit/index', NULL, '',
       1, 0, 'C', '0', '0', 'biz:unit:list', '#',
       'admin', SYSDATE, '', NULL, '合作单位管理（树表 + 联系人 + 公司/学校统一建模）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2020);

-- 2. 2021 查询
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2021, '查询', 2020, 1, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:unit:query', '#',
       'admin', SYSDATE, '', NULL, '合作单位-查询'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2021);

-- 3. 2022 新增
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2022, '新增', 2020, 2, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:unit:add', '#',
       'admin', SYSDATE, '', NULL, '合作单位-新增（顶级须选 external_unit_type；公司≤3层/学校≤2层校验）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2022);

-- 4. 2023 修改
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2023, '修改', 2020, 3, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:unit:edit', '#',
       'admin', SYSDATE, '', NULL, '合作单位-修改（换父级 updateChildren 同步 ancestors）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2023);

-- 5. 2024 删除
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2024, '删除', 2020, 4, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:unit:remove', '#',
       'admin', SYSDATE, '', NULL, '合作单位-删除（有子节点/被 project_unit 引用拒）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2024);

-- 6. 2025 导出
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2025, '导出', 2020, 5, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:unit:export', '#',
       'admin', SYSDATE, '', NULL, '合作单位-导出'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2025);

-- 7. 2026 联系人维护
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2026, '联系人维护', 2020, 6, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:unit:contact', '#',
       'admin', SYSDATE, '', NULL, '合作单位-联系人（公司联系人/高校老师统一，unit_contact 表）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2026);

-- 8. 2027 树选择器（不需单独权限：admin/science_admin 挂载用于前端可见性）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2027, '树选择器', 2020, 7, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:unit:treeselect', '#',
       'admin', SYSDATE, '', NULL, '合作单位-树选择器（不需单独权限；接口 /treeselect 仅需登录）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2027);

-- 9. 2029 课题关联合作单位（parent=2011 课题管理；课题详情页「合作单位」tab 用）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2029, '关联单位', 2011, 9, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:project:unit', '#',
       'admin', SYSDATE, '', NULL, '课题-关联合作单位（详情页 tab：LEAD/PARTICIPANT/COLLABORATE）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2029);

-- ============================================================================
-- 六、角色挂载（按任务卡 §1.4 矩阵）
--   admin(1) / science_admin(101)：全部 9 项（2020-2027 + 2029，跳过 2028）
--   leader(100) / office(102) / labor_hr(103) / researcher(105)：只读 2020+2021
--   dept_leader(104)：2020+2021+2029
--   2027 treeselect 仅 admin/science_admin 挂载（任务卡 §1.4 「不需单独权限」= 无需矩阵差异化）
-- 共 29 条 sys_role_menu
-- ============================================================================

-- admin 1：全部 9 项
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2020 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2020);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2021 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2021);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2022 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2022);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2023 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2023);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2024 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2024);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2025 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2025);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2026 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2026);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2027 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2027);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2029 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2029);

-- science_admin 101：全部 9 项
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2020 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2020);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2021 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2021);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2022 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2022);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2023 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2023);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2024 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2024);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2025 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2025);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2026 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2026);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2027 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2027);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2029 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2029);

-- leader 100：2020+2021
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2020 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2020);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2021 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2021);

-- office 102：2020+2021
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2020 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2020);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2021 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2021);

-- labor_hr 103：2020+2021
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2020 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2020);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2021 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2021);

-- dept_leader 104：2020+2021+2029
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2020 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2020);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2021 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2021);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2029 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2029);

-- researcher 105：2020+2021
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2020 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2020);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2021 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2021);

-- ============================================================================
-- 完
-- ============================================================================