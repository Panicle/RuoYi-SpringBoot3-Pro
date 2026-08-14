-- ============================================================================
-- 科研管理平台 阶段4：经费管理数据库基线
-- 版本: V1.0.11
-- 日期: 2026-08-14
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 任务卡_阶段4_经费管理 §二（数据模型全部内容）
-- 说明:
--   1. budget_split 表加 3 列（10列→13列）：used_amount / balance / version
--      幂等 ADD IF NOT EXISTS + COMMENT ON COLUMN；存量初始化 UPDATE（幂等，仅 IS NULL 行）
--      新增唯一索引 idx_budget_split_pc_uk(project_id, category, del_flag)：
--      PL 块预检 USER_INDEXES + 建索引前先查重复行，若存在重复行则
--      RAISE_APPLICATION_ERROR 中止（devdm 现无数据，预期不触发，本次执行前已勘查确认 0 条重复）
--   2. expense 表加 4 列（13列→17列）：split_id / status / voucher_url / version
--      幂等同上；存量初始化 UPDATE（幂等）；新增普通索引 idx_expense_split_id（PL 块预检）
--   3. 字典 expense_status（dict_id=229，dict_code 20133-20134）：
--      NORMAL 正常(success) / VOID 已作废(info)
--   4. sys_config：biz.expense.allowOverdraft = false（经费记账-允许透支，config_type='Y'）
--      INSERT...WHERE NOT EXISTS 幂等；config_id 取当前 MAX(config_id)+1（动态子查询，避免硬编码冲突）
--   5. 菜单 2040-2046（经费管理，parent=2010 科研管理，order_num=4）：
--      2040 C 经费管理（path=expense, component=biz/expense/index, perms=biz:expense:list）
--      2041-2046 F：query/add/void/export/budget/alert
--   6. 角色挂载（任务卡 §2.5 矩阵，共 30 条 sys_role_menu；任务卡正文写 31，
--      按矩阵逐项实算为 7+7+6+4+2+2+2=30，矩阵优先于总数，详见报告说明）：
--      admin(1)/science_admin(101) 全部 7 项；
--      dept_leader(104) 6 项（2040-2045，无 alert）；
--      researcher(105) 4 项（2040/2041/2042/2046，可查可记账可看预警，不可作废/调预算）；
--      leader(100)/office(102)/labor_hr(103) 各 2 项（2040+2041，只读）
-- ============================================================================

-- ============================================================================
-- 一、budget_split 加 3 列 + 存量初始化 + 唯一索引（幂等）
-- 期望：列数 10 → 13；新增 idx_budget_split_pc_uk(project_id, category, del_flag) UNIQUE
-- ============================================================================

-- 1. used_amount 已用金额缓存
ALTER TABLE budget_split ADD IF NOT EXISTS used_amount DECIMAL(14,2) DEFAULT 0.00;
COMMENT ON COLUMN budget_split.used_amount IS '已用金额缓存 = Σ 有效 expense.amount（事务内重算写回）';

-- 2. balance 余额缓存
ALTER TABLE budget_split ADD IF NOT EXISTS balance DECIMAL(14,2) DEFAULT 0.00;
COMMENT ON COLUMN budget_split.balance IS '余额缓存 = budget_amount - used_amount';

-- 3. version 乐观锁
ALTER TABLE budget_split ADD IF NOT EXISTS version INT DEFAULT 0;
COMMENT ON COLUMN budget_split.version IS '乐观锁（MyBatis-Plus @Version）';

-- 4. 存量数据初始化（幂等，仅 used_amount IS NULL 的行）
UPDATE budget_split SET used_amount = 0, balance = budget_amount, version = 0 WHERE used_amount IS NULL;

-- 5. 唯一索引 idx_budget_split_pc_uk（PL 块预检 USER_INDEXES；建索引前先查重复行，
--    存在重复则 RAISE_APPLICATION_ERROR 中止，不建索引）
DECLARE
    CNT INT;
    DUP_CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_INDEXES WHERE INDEX_NAME = 'IDX_BUDGET_SPLIT_PC_UK';
    IF CNT = 0 THEN
        SELECT COUNT(*) INTO DUP_CNT FROM (
            SELECT project_id, category, del_flag FROM budget_split
            GROUP BY project_id, category, del_flag HAVING COUNT(*) > 1
        );
        IF DUP_CNT > 0 THEN
            RAISE_APPLICATION_ERROR(-20001, 'budget_split 存在 (project_id,category,del_flag) 重复行，无法建唯一索引 idx_budget_split_pc_uk，请先人工合并重复行后重跑本脚本');
        ELSE
            EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX idx_budget_split_pc_uk ON budget_split (project_id, category, del_flag)';
        END IF;
    END IF;
END;

-- ============================================================================
-- 二、expense 加 4 列 + 存量初始化 + 普通索引（幂等）
-- 期望：列数 13 → 17；新增 idx_expense_split_id
-- ============================================================================

-- 1. split_id 关联预算分劈行
ALTER TABLE expense ADD IF NOT EXISTS split_id BIGINT DEFAULT NULL;
COMMENT ON COLUMN expense.split_id IS '关联预算分劈行（budget_split.split_id；NOT NULL 语义由应用层强校验；索引 idx_expense_split_id）';

-- 2. status 流水状态
ALTER TABLE expense ADD IF NOT EXISTS status VARCHAR(20) DEFAULT 'NORMAL';
COMMENT ON COLUMN expense.status IS '流水状态（字典 expense_status：NORMAL 正常 / VOID 已作废）';

-- 3. voucher_url 凭证
ALTER TABLE expense ADD IF NOT EXISTS voucher_url VARCHAR(500) DEFAULT NULL;
COMMENT ON COLUMN expense.voucher_url IS '凭证（/common/upload 相对路径）';

-- 4. version 乐观锁
ALTER TABLE expense ADD IF NOT EXISTS version INT DEFAULT 0;
COMMENT ON COLUMN expense.version IS '乐观锁（MyBatis-Plus @Version）';

-- 5. 存量数据初始化（幂等，仅 status IS NULL 的行）
UPDATE expense SET status = 'NORMAL', version = 0 WHERE status IS NULL;

-- 6. 索引 idx_expense_split_id（PL 块预检，普通索引）
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_INDEXES WHERE INDEX_NAME = 'IDX_EXPENSE_SPLIT_ID';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_expense_split_id ON expense (split_id)';
    END IF;
END;

-- ============================================================================
-- 三、新增字典 expense_status（dict_id=229，dict_code 20133-20134）
-- 字典值大写，含 list_class 样式标签
-- ============================================================================

-- 1. 字典类型
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status, create_by, create_time, remark)
SELECT 229, '经费记账状态', 'expense_status', '0', 'admin', SYSDATE, '经费流水状态（NORMAL 正常 / VOID 已作废）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_id = 229);

-- 2. 字典数据 2 项
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20133, 1, '正常', 'NORMAL', 'expense_status', '', 'success', 'N', '0', 'admin', SYSDATE, '经费流水正常'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20133);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20134, 2, '已作废', 'VOID', 'expense_status', '', 'info', 'N', '0', 'admin', SYSDATE, '经费流水已作废'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20134);

-- ============================================================================
-- 四、sys_config 配置项（幂等；config_id 取当前 MAX(config_id)+1，避免硬编码冲突）
-- ============================================================================

INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT (SELECT MAX(config_id) + 1 FROM sys_config), '经费记账-允许透支', 'biz.expense.allowOverdraft', 'false', 'Y', 'admin', SYSDATE, '经费记账是否允许透支预算（true 允许/false 默认拒绝，默认拒绝）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'biz.expense.allowOverdraft');

-- ============================================================================
-- 五、菜单 2040-2046（经费管理 + 6 F 按钮，parent=2010 科研管理，order_num=4）
--   2040 C  经费管理（path=expense, component=biz/expense/index, perms=biz:expense:list）
--   2041 F  查询
--   2042 F  记账
--   2043 F  作废
--   2044 F  导出
--   2045 F  预算调整
--   2046 F  预警查看
-- ============================================================================

-- 1. 2040 经费管理（C）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2040, '经费管理', 2010, 4, 'expense', 'biz/expense/index', NULL, '',
       1, 0, 'C', '0', '0', 'biz:expense:list', 'money',
       'admin', SYSDATE, '', NULL, '经费管理菜单（预算概览+记账流水+预警；阶段4）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2040);

-- 2. 2041 查询
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2041, '查询', 2040, 1, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:expense:query', '#',
       'admin', SYSDATE, '', NULL, '经费-查询'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2041);

-- 3. 2042 记账
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2042, '记账', 2040, 2, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:expense:add', '#',
       'admin', SYSDATE, '', NULL, '经费-记账（核心事务：预算校验+余额核减+双阈值预警）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2042);

-- 4. 2043 作废
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2043, '作废', 2040, 3, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:expense:void', '#',
       'admin', SYSDATE, '', NULL, '经费-作废（status→VOID，事务内回冲 used_amount/balance）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2043);

-- 5. 2044 导出
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2044, '导出', 2040, 4, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:expense:export', '#',
       'admin', SYSDATE, '', NULL, '经费-导出'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2044);

-- 6. 2045 预算调整
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2045, '预算调整', 2040, 5, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:expense:budget', '#',
       'admin', SYSDATE, '', NULL, '经费-预算调整（按 category 增量更新，保 split_id；监管上限校验）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2045);

-- 7. 2046 预警查看
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2046, '预警查看', 2040, 6, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:expense:alert', '#',
       'admin', SYSDATE, '', NULL, '经费-预警查看（alert 表 alert_type=BUDGET）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2046);

-- ============================================================================
-- 六、角色挂载（按任务卡 §2.5 矩阵）
--   admin(1) / science_admin(101)：全部 7 项（2040-2046）
--   leader(100) / office(102) / labor_hr(103)：只读 2 项（2040+2041）
--   dept_leader(104)：6 项（2040/2041/2042/2043/2044/2045，无 alert）
--   researcher(105)：4 项（2040/2041/2042/2046）
--   共 30 条 sys_role_menu（7+7+6+4+2+2+2=30；任务卡正文写 31，矩阵实算为准，详见报告）
-- ============================================================================

-- admin 1：全部 7 项
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2040 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2040);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2041 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2041);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2042 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2042);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2043 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2043);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2044 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2044);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2045 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2045);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2046 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2046);

-- science_admin 101：全部 7 项
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2040 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2040);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2041 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2041);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2042 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2042);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2043 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2043);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2044 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2044);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2045 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2045);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2046 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2046);

-- leader 100：2040+2041
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2040 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2040);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2041 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2041);

-- office 102：2040+2041
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2040 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2040);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2041 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2041);

-- labor_hr 103：2040+2041
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2040 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2040);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2041 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2041);

-- dept_leader 104：2040/2041/2042/2043/2044/2045（无 2046 alert）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2040 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2040);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2041 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2041);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2042 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2042);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2043 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2043);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2044 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2044);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2045 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2045);

-- researcher 105：2040/2041/2042/2046
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2040 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2040);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2041 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2041);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2042 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2042);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2046 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2046);

-- ============================================================================
-- 完
-- ============================================================================
