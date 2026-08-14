-- ============================================================================
-- 科研管理平台 阶段3：合同管理数据库基线
-- 版本: V1.0.10
-- 日期: 2026-08-14
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 任务卡_阶段3_合同管理 §一（数据模型全部内容）
-- 说明:
--   1. contract 表加 5 列（14列→19列）
--      幂等 ADD IF NOT EXISTS + COMMENT ON COLUMN（重跑覆盖同值零副作用）
--   2. contract 表加 2 索引（PL 块预检 USER_INDEXES，照 V1.0.6 idx_project_no_uk 写法）：
--      idx_contract_no_uk（UNIQUE on contract_no，含软删行，照 project_no 模式）
--      idx_contract_party_unit_id（普通 on party_unit_id）
--   3. contract_node 表加 1 列（13列→14列）：voucher_url
--   4. 字典 contract_status（dict_id=227，dict_code 20127-20129 ACTIVE/EXPIRED/TERMINATED）
--      + node_status（dict_id=228，dict_code 20130-20132 PENDING/DONE/OVERDUE，
--      OVERDUE 阶段9 定时任务写入，本期预留字典项）
--   5. 菜单 2030(C 合同管理 parent=2010) + 2031-2036 F 6按钮（query/add/edit/remove/export/node）
--   6. 角色挂载（任务卡 §1.4 矩阵，共 29 条 sys_role_menu）：
--      admin(1)/science_admin(101) 全部 7 项；
--      leader(100)/office(102)/labor_hr(103) 各 2 项（2030+2031）；
--      dept_leader(104) 6 项（2030/2031/2032/2033/2035/2036，无 remove）；
--      researcher(105) 3 项（2030/2031/2035）
--   7. 复用：contract_type(203 RESEARCH/SERVICE/PROCUREMENT)、node_type(204 PAYMENT/DELIVERY/ACCEPTANCE)
--   8. 完成日期复用现有 actual_date（总纲 finish_date 语义等价，不加列不迁移——任务卡备案）
-- ============================================================================

-- ============================================================================
-- 一、contract 加 5 列 + 2 索引（幂等）
-- 期望：列数 14 → 19；新增 idx_contract_no_uk(UNIQUE) + idx_contract_party_unit_id
-- ============================================================================

-- 1. contract_no 合同编号（人工输入必填；唯一索引 idx_contract_no_uk 兜底）
ALTER TABLE contract ADD IF NOT EXISTS contract_no VARCHAR(50) DEFAULT NULL;
COMMENT ON COLUMN contract.contract_no IS '合同编号（人工输入必填；唯一索引 idx_contract_no_uk 兜底，含软删行）';

-- 2. party_unit_id 对方主体（cooperative_unit.unit_id，可空）
ALTER TABLE contract ADD IF NOT EXISTS party_unit_id BIGINT DEFAULT NULL;
COMMENT ON COLUMN contract.party_unit_id IS '对方主体（cooperative_unit.unit_id，可空）';

-- 3. party_name 对方名称（选单位时=单位名快照；未建档时手工填）
ALTER TABLE contract ADD IF NOT EXISTS party_name VARCHAR(200) DEFAULT NULL;
COMMENT ON COLUMN contract.party_name IS '对方名称（选单位时=单位名快照；未建档时手工填）';

-- 4. start_date 生效日期
ALTER TABLE contract ADD IF NOT EXISTS start_date DATE DEFAULT NULL;
COMMENT ON COLUMN contract.start_date IS '生效日期';

-- 5. file_url 合同附件（/common/upload 相对路径）
ALTER TABLE contract ADD IF NOT EXISTS file_url VARCHAR(500) DEFAULT NULL;
COMMENT ON COLUMN contract.file_url IS '合同附件（/common/upload 相对路径）';

-- 6. 索引 idx_contract_no_uk（PL 块预检，唯一索引）
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_INDEXES WHERE INDEX_NAME = 'IDX_CONTRACT_NO_UK';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX idx_contract_no_uk ON contract (contract_no)';
    END IF;
END;

-- 7. 索引 idx_contract_party_unit_id（PL 块预检，普通索引）
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_INDEXES WHERE INDEX_NAME = 'IDX_CONTRACT_PARTY_UNIT_ID';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_contract_party_unit_id ON contract (party_unit_id)';
    END IF;
END;

-- ============================================================================
-- 二、contract_node 加 1 列（13列→14列）
-- 期望：voucher_url 完成凭证（验收单/发票，/common/upload 相对路径）
-- ============================================================================

ALTER TABLE contract_node ADD IF NOT EXISTS voucher_url VARCHAR(500) DEFAULT NULL;
COMMENT ON COLUMN contract_node.voucher_url IS '完成凭证（验收单/发票，/common/upload 相对路径）';

-- ============================================================================
-- 三、新增字典 contract_status（dict_id=227，dict_code 20127-20129）
-- 字典值大写，含 list_class 样式标签
-- ============================================================================

-- 1. 字典类型
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status, create_by, create_time, remark)
SELECT 227, '合同状态', 'contract_status', '0', 'admin', SYSDATE, '合同履行状态（ACTIVE 履行中 / EXPIRED 已到期 / TERMINATED 已终止；EXPIRED 阶段9 定时任务写入）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_id = 227);

-- 2. 字典数据 3 项
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20127, 1, '履行中', 'ACTIVE',     'contract_status', '', 'primary', 'N', '0', 'admin', SYSDATE, '合同履行中'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20127);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20128, 2, '已到期', 'EXPIRED',    'contract_status', '', 'warning', 'N', '0', 'admin', SYSDATE, '合同已到期'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20128);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20129, 3, '已终止', 'TERMINATED', 'contract_status', '', 'danger',  'N', '0', 'admin', SYSDATE, '合同已终止'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20129);

-- ============================================================================
-- 四、新增字典 node_status（dict_id=228，dict_code 20130-20132）
-- OVERDUE 阶段9 定时任务写入，本期预留字典项
-- ============================================================================

-- 1. 字典类型
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status, create_by, create_time, remark)
SELECT 228, '节点状态', 'node_status', '0', 'admin', SYSDATE, '履约节点状态（PENDING 待执行 / DONE 已完成 / OVERDUE 已逾期；OVERDUE 阶段9 定时任务写入，本期预留字典项）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_id = 228);

-- 2. 字典数据 3 项
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20130, 1, '待执行', 'PENDING', 'node_status', '', 'info',    'N', '0', 'admin', SYSDATE, '节点待执行'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20130);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20131, 2, '已完成', 'DONE',    'node_status', '', 'success', 'N', '0', 'admin', SYSDATE, '节点已完成'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20131);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20132, 3, '已逾期', 'OVERDUE','node_status', '', 'danger',  'N', '0', 'admin', SYSDATE, '节点已逾期（阶段9 定时任务写入）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20132);

-- ============================================================================
-- 五、菜单 2030-2036（合同管理 + 6 F 按钮，parent=2010 科研管理）
--   2030 C  合同管理（path=contract, component=biz/contract/index, perms=biz:contract:list）
--   2031 F  查询
--   2032 F  新增
--   2033 F  修改
--   2034 F  删除
--   2035 F  导出
--   2036 F  节点维护（节点 CRUD + 完成动作）
-- ============================================================================

-- 1. 2030 合同管理（C）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2030, '合同管理', 2010, 3, 'contract', 'biz/contract/index', NULL, '',
       1, 0, 'C', '0', '0', 'biz:contract:list', 'documentation',
       'admin', SYSDATE, '', NULL, '合同管理菜单（CRUD+节点维护+完成动作；阶段3）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2030);

-- 2. 2031 查询
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2031, '查询', 2030, 1, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:contract:query', '#',
       'admin', SYSDATE, '', NULL, '合同-查询'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2031);

-- 3. 2032 新增
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2032, '新增', 2030, 2, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:contract:add', '#',
       'admin', SYSDATE, '', NULL, '合同-新增（contract_no 必填查重；party 二选一校验）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2032);

-- 4. 2033 修改
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2033, '修改', 2030, 3, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:contract:edit', '#',
       'admin', SYSDATE, '', NULL, '合同-修改（contract_no 不可改以库为准；status 可改三值）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2033);

-- 5. 2034 删除
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2034, '删除', 2030, 4, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:contract:remove', '#',
       'admin', SYSDATE, '', NULL, '合同-删除（逻辑删；级联逻辑删全部有效节点，同事务）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2034);

-- 6. 2035 导出
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2035, '导出', 2030, 5, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:contract:export', '#',
       'admin', SYSDATE, '', NULL, '合同-导出'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2035);

-- 7. 2036 节点维护
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2036, '节点维护', 2030, 6, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:contract:node', '#',
       'admin', SYSDATE, '', NULL, '合同-节点维护（节点 CRUD + 完成动作，含 voucher_url）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2036);

-- ============================================================================
-- 六、角色挂载（按任务卡 §1.4 矩阵）
--   admin(1) / science_admin(101)：全部 7 项（2030-2036）
--   leader(100) / office(102) / labor_hr(103)：只读 2 项（2030+2031）
--   dept_leader(104)：6 项（2030/2031/2032/2033/2035/2036，无 remove）
--   researcher(105)：3 项（2030/2031/2035）
--   共 29 条 sys_role_menu
-- ============================================================================

-- admin 1：全部 7 项
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2030 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2030);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2031 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2031);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2032 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2032);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2033 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2033);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2034 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2034);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2035 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2035);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2036 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2036);

-- science_admin 101：全部 7 项
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2030 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2030);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2031 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2031);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2032 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2032);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2033 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2033);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2034 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2034);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2035 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2035);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2036 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2036);

-- leader 100：2030+2031
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2030 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2030);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2031 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2031);

-- office 102：2030+2031
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2030 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2030);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2031 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2031);

-- labor_hr 103：2030+2031
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2030 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2030);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2031 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2031);

-- dept_leader 104：2030/2031/2032/2033/2035/2036（无 remove=2034）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2030 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2030);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2031 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2031);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2032 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2032);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2033 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2033);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2035 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2035);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2036 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2036);

-- researcher 105：2030+2031+2035
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2030 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2030);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2031 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2031);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2035 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2035);

-- ============================================================================
-- 完
-- ============================================================================
