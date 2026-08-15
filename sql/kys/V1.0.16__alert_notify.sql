-- ============================================================================
-- 科研管理平台 阶段9：预警引擎与对话精灵-预警通知数据库基线
-- 版本: V1.0.16
-- 日期: 2026-08-15
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 任务卡_阶段9_预警引擎与对话精灵 §二~§六
-- 说明:
--   1. alert 加 5 列（ref_type/biz_key/round/first_time/last_time），每列
--      PL 块预检 user_tab_columns 幂等；round 带 DEFAULT 1
--   2. alert.status 默认值 'UNREAD'->'OPEN'（PL 块预检 USER_TAB_COLUMNS.DATA_DEFAULT
--      后 ALTER COLUMN SET DEFAULT 幂等）+ 注释改为 'OPEN生效/RESOLVED已消除'
--      （COMMENT 覆盖式天然幂等）
--   3. 非唯一索引 idx_alert_biz_key(biz_key)，建前查 USER_INDEXES 幂等
--   4. notification 加 2 列（status/confirm_time），PL 块预检幂等；
--      status 带 DEFAULT 'UNREAD'；is_read 注释追加"（兼容保留，新代码用 status）"
--   5. 字典：sys_dict_type 232='预警状态'/alert_status、233='通知状态'/notify_status；
--      sys_dict_data 20142-20146（alert_status 2 项 + notify_status 3 项），
--      INSERT 前查 dict_code 存在性幂等
--   6. 菜单 2090-2095 共 6 项（parent=2010 科研管理；C 菜单 2 个 + F 按钮 4 个）
--      C 菜单路径：2090=alert(biz/alert/index, icon=monitor)
--                  2093=notify(biz/alert/notify, icon=message)
--      C 菜单 icon：2090 需求 bell 不存在于前端 svg 目录，语义替换为 monitor
--      （监控，最接近"预警"语义）；2093 message 已确认存在
--   7. 角色挂载（任务卡 §5 矩阵，共 34 条 sys_role_menu）：
--      admin(1)/science_admin(101)/leader(100) 各挂 6 项（2090-2095 全量 C+F）
--      dept_leader(104)/researcher(105)/office(102)/labor_hr(103) 各挂 4 项
--      （2090/2091/2093/2094 只读）
--      合计 6x3 + 4x4 = 34 条
--   8. 不创建新表（alert/notification 已在 V1.0.0 建立）；全程幂等无 DROP/DELETE
-- ============================================================================

-- ============================================================================
-- 一、alert 加 5 列（每列独立 PL 块预检 user_tab_columns 幂等）
--   基线 13 列（含 remark），本次新增 5 列后 = 18 列
-- ============================================================================

-- 1. ref_type 关联业务对象类型
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'ALERT' AND COLUMN_NAME = 'REF_TYPE';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE alert ADD ref_type VARCHAR(20)';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN alert.ref_type IS ''关联业务对象类型（PROJECT/CONTRACT/BUDGET/DOCUMENT）''';
    END IF;
END;
-- 2. biz_key 业务唯一键（幂等去重用）
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'ALERT' AND COLUMN_NAME = 'BIZ_KEY';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE alert ADD biz_key VARCHAR(200)';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN alert.biz_key IS ''业务唯一键（alert_type:ref_type:ref_id:周期标识，幂等去重用）''';
    END IF;
END;
-- 3. round 触发轮次
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'ALERT' AND COLUMN_NAME = 'ROUND';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE alert ADD round INT DEFAULT 1';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN alert.round IS ''触发轮次（同 biz_key 每次重新触发 +1，首次 1）''';
    END IF;
END;
-- 4. first_time 首次生成时间
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'ALERT' AND COLUMN_NAME = 'FIRST_TIME';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE alert ADD first_time TIMESTAMP';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN alert.first_time IS ''首次生成时间''';
    END IF;
END;
-- 5. last_time 最近命中扫描时间
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'ALERT' AND COLUMN_NAME = 'LAST_TIME';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE alert ADD last_time TIMESTAMP';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN alert.last_time IS ''最近命中扫描时间''';
    END IF;
END;
-- ============================================================================
-- 二、alert.status 默认值 'UNREAD'->'OPEN' + 注释改（幂等）
--   默认值：预检 USER_TAB_COLUMNS.DATA_DEFAULT != 'OPEN'（含引号文本）才执行
--   ALTER COLUMN SET DEFAULT；注释用 COMMENT 覆盖式（天然幂等）
-- ============================================================================
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'ALERT' AND COLUMN_NAME = 'STATUS'
       AND NVL(TRIM(DATA_DEFAULT), 'NULL') <> '''OPEN''';
    IF CNT > 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE alert ALTER COLUMN status SET DEFAULT ''OPEN''';
    END IF;
END;
COMMENT ON COLUMN alert.status IS 'OPEN生效/RESOLVED已消除';
-- ============================================================================
-- 三、索引 idx_alert_biz_key（非唯一，建前查 USER_INDEXES 幂等）
-- ============================================================================
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_INDEXES
     WHERE INDEX_NAME = 'IDX_ALERT_BIZ_KEY' AND TABLE_NAME = 'ALERT';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_alert_biz_key ON alert(biz_key)';
    END IF;
END;
-- ============================================================================
-- 四、notification 加 2 列（每列独立 PL 块预检 user_tab_columns 幂等）
--   实际基线 11 列（含 READ_TIME），本次新增 2 列后 = 13 列；增量恒 5+2
-- ============================================================================

-- 1. status 通知状态
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'NOTIFICATION' AND COLUMN_NAME = 'STATUS';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE notification ADD status VARCHAR(20) DEFAULT ''UNREAD''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN notification.status IS ''通知状态（UNREAD未读/READ已读/CONFIRMED已确认）''';
    END IF;
END;
-- 2. confirm_time 确认时间
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'NOTIFICATION' AND COLUMN_NAME = 'CONFIRM_TIME';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE notification ADD confirm_time TIMESTAMP';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN notification.confirm_time IS ''确认时间''';
    END IF;
END;
-- is_read 注释追加（COMMENT 覆盖式幂等，直接写最终完整值）
COMMENT ON COLUMN notification.is_read IS '是否已读（0未读 1已读）（兼容保留，新代码用 status）';
-- ============================================================================
-- 五、字典（INSERT 前查存在性幂等）
--   sys_dict_type 232/233；sys_dict_data 20142-20146
-- ============================================================================

-- 1. sys_dict_type 232 预警状态
INSERT INTO sys_dict_type
    (dict_id, dict_name, dict_type, status, create_by, create_time, update_by, update_time, remark)
SELECT 232, '预警状态', 'alert_status', '0', 'admin', SYSDATE, '', NULL, '预警引擎-预警状态'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_id = 232);
-- 2. sys_dict_type 233 通知状态
INSERT INTO sys_dict_type
    (dict_id, dict_name, dict_type, status, create_by, create_time, update_by, update_time, remark)
SELECT 233, '通知状态', 'notify_status', '0', 'admin', SYSDATE, '', NULL, '预警引擎-通知状态'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_id = 233);
-- 3. sys_dict_data 20142 alert_status 生效
INSERT INTO sys_dict_data
    (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, update_by, update_time, remark)
SELECT 20142, 1, '生效', 'OPEN', 'alert_status', '', 'success', 'N', '0', 'admin', SYSDATE, '', NULL, '预警状态-生效'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20142);
-- 4. sys_dict_data 20143 alert_status 已消除
INSERT INTO sys_dict_data
    (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, update_by, update_time, remark)
SELECT 20143, 2, '已消除', 'RESOLVED', 'alert_status', '', 'info', 'N', '0', 'admin', SYSDATE, '', NULL, '预警状态-已消除'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20143);
-- 5. sys_dict_data 20144 notify_status 未读
INSERT INTO sys_dict_data
    (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, update_by, update_time, remark)
SELECT 20144, 1, '未读', 'UNREAD', 'notify_status', '', 'warning', 'N', '0', 'admin', SYSDATE, '', NULL, '通知状态-未读'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20144);
-- 6. sys_dict_data 20145 notify_status 已读
INSERT INTO sys_dict_data
    (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, update_by, update_time, remark)
SELECT 20145, 2, '已读', 'READ', 'notify_status', '', 'primary', 'N', '0', 'admin', SYSDATE, '', NULL, '通知状态-已读'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20145);
-- 7. sys_dict_data 20146 notify_status 已确认
INSERT INTO sys_dict_data
    (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, update_by, update_time, remark)
SELECT 20146, 3, '已确认', 'CONFIRMED', 'notify_status', '', 'success', 'N', '0', 'admin', SYSDATE, '', NULL, '通知状态-已确认'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20146);
-- ============================================================================
-- 六、菜单 2090-2095（预警中心 + 我的通知，parent=2010 科研管理）
--   2090 C  预警中心（path=alert, component=biz/alert/index, icon=monitor, order_num=10）
--   2091 F  预警详情（biz:alert:query）
--   2092 F  预警消除（biz:alert:resolve）
--   2093 C  我的通知（path=notify, component=biz/alert/notify, icon=message, order_num=11）
--   2094 F  标记已读（biz:alert:read）
--   2095 F  确认（biz:alert:confirm）
-- ============================================================================

-- 1. 2090 预警中心（C）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2090, '预警中心', 2010, 10, 'alert', 'biz/alert/index', NULL, '',
       1, 0, 'C', '0', '0', 'biz:alert:list', 'monitor',
       'admin', SYSDATE, '', NULL, '预警引擎-预警中心（阶段9；需求 icon=bell 前端缺失，语义替换 monitor）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2090);

-- 2. 2091 预警详情
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2091, '预警详情', 2090, 1, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:alert:query', '#',
       'admin', SYSDATE, '', NULL, '预警引擎-预警详情'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2091);

-- 3. 2092 预警消除
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2092, '预警消除', 2090, 2, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:alert:resolve', '#',
       'admin', SYSDATE, '', NULL, '预警引擎-预警消除'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2092);

-- 4. 2093 我的通知（C）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2093, '我的通知', 2010, 11, 'notify', 'biz/alert/notify', NULL, '',
       1, 0, 'C', '0', '0', 'biz:alert:notify', 'message',
       'admin', SYSDATE, '', NULL, '预警引擎-我的通知（阶段9；需求 icon=message 已确认存在）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2093);

-- 5. 2094 标记已读
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2094, '标记已读', 2093, 1, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:alert:read', '#',
       'admin', SYSDATE, '', NULL, '预警引擎-标记已读'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2094);

-- 6. 2095 确认
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2095, '确认', 2093, 2, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:alert:confirm', '#',
       'admin', SYSDATE, '', NULL, '预警引擎-通知确认'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2095);

-- ============================================================================
-- 七、角色挂载（任务卡 §5 矩阵，共 34 条 sys_role_menu）
--   admin(1)/science_admin(101)/leader(100)：各 6 项（2090-2095 全量 C+F）
--   dept_leader(104)/researcher(105)/office(102)/labor_hr(103)：各 4 项（只读）
--   合计 6x3 + 4x4 = 34 条
-- ============================================================================

-- admin 1：6 项（2090-2095 全量）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2090 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2090);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2091 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2091);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2092 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2092);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2093 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2093);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2094 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2094);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2095 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2095);

-- science_admin 101：6 项
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2090 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2090);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2091 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2091);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2092 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2092);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2093 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2093);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2094 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2094);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2095 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2095);

-- leader 100：6 项（所领导有消除/确认权）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2090 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2090);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2091 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2091);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2092 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2092);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2093 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2093);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2094 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2094);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2095 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2095);

-- dept_leader 104：4 项（2090/2091/2093/2094 只读）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2090 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2090);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2091 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2091);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2093 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2093);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2094 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2094);

-- researcher 105：4 项（只读）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2090 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2090);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2091 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2091);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2093 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2093);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2094 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2094);

-- office 102：4 项（只读）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2090 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2090);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2091 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2091);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2093 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2093);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2094 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2094);

-- labor_hr 103：4 项（只读）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2090 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2090);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2091 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2091);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2093 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2093);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2094 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2094);

-- ============================================================================
-- 完
-- ============================================================================
