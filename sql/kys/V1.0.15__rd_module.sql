-- ============================================================================
-- 科研管理平台 阶段8：研发加计扣除数据库基线
-- 版本: V1.0.15
-- 日期: 2026-08-15
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 任务卡_阶段8_研发加计扣除 §一~§五
-- 说明:
--   1. rd_labor_allocation 加 5 列（monthly_hours/hourly_rate/surcharge_detail/
--      confirm_by/confirm_time），每列 PL 块预检 user_tab_columns 幂等
--   2. 3 个非唯一索引（idx_rd_alloc_pm / idx_rd_worktime_daily_prd /
--      idx_rd_salary_rm），建前查 USER_INDEXES 幂等
--   3. 菜单 2070-2085 共 14 项（parent=2010 科研管理；C 菜单 3 个 + F 按钮 11 个）
--      C 菜单路径：2070=rdworktime(biz/rd/worktime, icon=time)
--                  2075=rdsalary(biz/rd/salary, icon=money)
--                  2080=rdallocation(biz/rd/allocation, icon=chart)
--      C 菜单 icon(time/money/chart)已确认存在于前端 svg 目录
--   4. 角色挂载（任务卡 §4 矩阵，共 56 条 sys_role_menu）：
--      admin(1)/science_admin(101)/leader(100) 各挂 14 项（全部 C+F）
--      dept_leader(104) 挂 3 项（2070/2080/2084 本室工时查看+分摊查看+单课题导出）
--      researcher(105) 挂 5 项（2070/2071/2072 本人填报+2080/2084 本人查看导出）
--      office(102) 挂 1 项（2080 行政查看）
--      labor_hr(103) 挂 5 项（2070/2075/2078/2080/2084 工资可见可导+分摊只读）
--      合计 14+14+14+3+5+1+5 = 56 条
--   5. 不创建新表（基线 5 张 rd_ 表 + surcharge_rate 已在 V1.0.0 建立）
--   6. 不修改 surcharge_rate 数据（status 字面量 'ACTIVE'，代码层直接使用）
-- ============================================================================

-- ============================================================================
-- 一、rd_labor_allocation 加 5 列（每列独立 PL 块预检 user_tab_columns 幂等）
--   基线 15 列（含 alloc_id/project_id/researcher_id/month/allocated_amount/
--   surcharge_total/grand_total/status/batch_no/del_flag/create_by/create_time/
--   update_by/update_time/remark），本次新增 5 列后 = 20 列
-- ============================================================================

-- 1. monthly_hours 月研发工时快照
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'RD_LABOR_ALLOCATION' AND COLUMN_NAME = 'MONTHLY_HOURS';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE rd_labor_allocation ADD monthly_hours DECIMAL(8,2)';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN rd_labor_allocation.monthly_hours IS ''月研发工时快照''';
    END IF;
END;
-- 2. hourly_rate 时薪快照（月薪÷174，展示口径）
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'RD_LABOR_ALLOCATION' AND COLUMN_NAME = 'HOURLY_RATE';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE rd_labor_allocation ADD hourly_rate DECIMAL(12,2)';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN rd_labor_allocation.hourly_rate IS ''时薪快照（月薪÷174，展示口径）''';
    END IF;
END;
-- 3. surcharge_detail 附加费逐项JSON
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'RD_LABOR_ALLOCATION' AND COLUMN_NAME = 'SURCHARGE_DETAIL';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE rd_labor_allocation ADD surcharge_detail VARCHAR(2000)';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN rd_labor_allocation.surcharge_detail IS ''附加费逐项JSON（{"edu":金额,...} 10项，rate_code 为键）''';
    END IF;
END;
-- 4. confirm_by 确认人
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'RD_LABOR_ALLOCATION' AND COLUMN_NAME = 'CONFIRM_BY';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE rd_labor_allocation ADD confirm_by VARCHAR(64)';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN rd_labor_allocation.confirm_by IS ''确认人''';
    END IF;
END;
-- 5. confirm_time 确认时间
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'RD_LABOR_ALLOCATION' AND COLUMN_NAME = 'CONFIRM_TIME';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE rd_labor_allocation ADD confirm_time TIMESTAMP';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN rd_labor_allocation.confirm_time IS ''确认时间''';
    END IF;
END;
-- ============================================================================
-- 二、索引（建前查 USER_INDEXES 幂等；均非唯一）
-- ============================================================================

-- 1. idx_rd_alloc_pm
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_INDEXES
     WHERE INDEX_NAME = 'IDX_RD_ALLOC_PM' AND TABLE_NAME = 'RD_LABOR_ALLOCATION';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_rd_alloc_pm ON rd_labor_allocation(project_id, month)';
    END IF;
END;
-- 2. idx_rd_worktime_daily_prd
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_INDEXES
     WHERE INDEX_NAME = 'IDX_RD_WORKTIME_DAILY_PRD' AND TABLE_NAME = 'RD_WORKTIME_DAILY';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_rd_worktime_daily_prd ON rd_worktime_daily(project_id, researcher_id, work_date)';
    END IF;
END;
-- 3. idx_rd_salary_rm
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_INDEXES
     WHERE INDEX_NAME = 'IDX_RD_SALARY_RM' AND TABLE_NAME = 'RD_RESEARCHER_SALARY';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_rd_salary_rm ON rd_researcher_salary(researcher_id, salary_month)';
    END IF;
END;
-- ============================================================================
-- 三、菜单 2070-2085（工时/工资/分摊三组，parent=2010 科研管理）
--   2070 C  工时填报（path=rdworktime, component=biz/rd/worktime, icon=time, order_num=7）
--   2071 F  工时保存
--   2072 F  复制上月
--   2075 C  工资与预算（path=rdsalary, component=biz/rd/salary, icon=money, order_num=8）
--   2076 F  工资维护
--   2077 F  工资导入
--   2078 F  工资导出
--   2079 F  预算维护
--   2080 C  分摊管理（path=rdallocation, component=biz/rd/allocation, icon=chart, order_num=9）
--   2081 F  分摊计算
--   2082 F  批次确认
--   2083 F  撤销确认
--   2084 F  单课题导出
--   2085 F  多课题汇总导出
-- ============================================================================

-- 1. 2070 工时填报（C）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2070, '工时填报', 2010, 7, 'rdworktime', 'biz/rd/worktime', NULL, '',
       1, 0, 'C', '0', '0', 'biz:rd:worktime:list', 'time',
       'admin', SYSDATE, '', NULL, '研发加计扣除-工时填报（阶段8；月研发工时/时薪快照/确认）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2070);

-- 2. 2071 工时保存
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2071, '工时保存', 2070, 1, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:rd:worktime:save', '#',
       'admin', SYSDATE, '', NULL, '研发加计扣除-工时保存'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2071);

-- 3. 2072 复制上月
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2072, '复制上月', 2070, 2, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:rd:worktime:copy', '#',
       'admin', SYSDATE, '', NULL, '研发加计扣除-工时复制上月'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2072);

-- 4. 2075 工资与预算（C）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2075, '工资与预算', 2010, 8, 'rdsalary', 'biz/rd/salary', NULL, '',
       1, 0, 'C', '0', '0', 'biz:rd:salary:list', 'money',
       'admin', SYSDATE, '', NULL, '研发加计扣除-工资与预算（阶段8；工资维护/导入/导出/预算维护）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2075);

-- 5. 2076 工资维护
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2076, '工资维护', 2075, 1, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:rd:salary:save', '#',
       'admin', SYSDATE, '', NULL, '研发加计扣除-工资维护'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2076);

-- 6. 2077 工资导入
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2077, '工资导入', 2075, 2, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:rd:salary:import', '#',
       'admin', SYSDATE, '', NULL, '研发加计扣除-工资导入'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2077);

-- 7. 2078 工资导出
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2078, '工资导出', 2075, 3, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:rd:salary:export', '#',
       'admin', SYSDATE, '', NULL, '研发加计扣除-工资导出'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2078);

-- 8. 2079 预算维护
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2079, '预算维护', 2075, 4, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:rd:salary:budget', '#',
       'admin', SYSDATE, '', NULL, '研发加计扣除-预算维护（按 projectId+year+month 增量 upsert）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2079);

-- 9. 2080 分摊管理（C）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2080, '分摊管理', 2010, 9, 'rdallocation', 'biz/rd/allocation', NULL, '',
       1, 0, 'C', '0', '0', 'biz:rd:alloc:list', 'chart',
       'admin', SYSDATE, '', NULL, '研发加计扣除-分摊管理（阶段8；分摊计算/批次确认/导出）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2080);

-- 10. 2081 分摊计算
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2081, '分摊计算', 2080, 1, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:rd:alloc:calc', '#',
       'admin', SYSDATE, '', NULL, '研发加计扣除-分摊计算'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2081);

-- 11. 2082 批次确认
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2082, '批次确认', 2080, 2, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:rd:alloc:confirm', '#',
       'admin', SYSDATE, '', NULL, '研发加计扣除-分摊批次确认'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2082);

-- 12. 2083 撤销确认
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2083, '撤销确认', 2080, 3, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:rd:alloc:revoke', '#',
       'admin', SYSDATE, '', NULL, '研发加计扣除-分摊批次撤销确认'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2083);

-- 13. 2084 单课题导出
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2084, '单课题导出', 2080, 4, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:rd:alloc:export', '#',
       'admin', SYSDATE, '', NULL, '研发加计扣除-单课题分摊导出'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2084);

-- 14. 2085 多课题汇总导出
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2085, '多课题汇总导出', 2080, 5, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:rd:alloc:summary', '#',
       'admin', SYSDATE, '', NULL, '研发加计扣除-多课题分摊汇总导出'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2085);

-- ============================================================================
-- 四、角色挂载（按任务卡 §4 矩阵，共 56 条 sys_role_menu）
--   admin(1) / science_admin(101) / leader(100)：14 项（全部 C+F）
--   dept_leader(104)：3 项（2070/2080/2084 本室工时查看+分摊查看+单课题导出）
--   researcher(105)：5 项（2070/2071/2072 本人填报 + 2080/2084 本人查看导出）
--   office(102)：1 项（2080 行政查看）
--   labor_hr(103)：5 项（2070/2075/2078/2080/2084 工资可见可导+分摊只读）
--   合计 14+14+14+3+5+1+5 = 56 条
-- ============================================================================

-- admin 1：14 项（2070-2072, 2075-2079, 2080-2085）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2070 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2070);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2071 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2071);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2072 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2072);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2075 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2075);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2076 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2076);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2077 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2077);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2078 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2078);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2079 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2079);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2080 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2080);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2081 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2081);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2082 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2082);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2083 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2083);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2084 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2084);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2085 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2085);

-- science_admin 101：14 项
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2070 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2070);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2071 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2071);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2072 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2072);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2075 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2075);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2076 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2076);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2077 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2077);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2078 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2078);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2079 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2079);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2080 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2080);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2081 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2081);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2082 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2082);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2083 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2083);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2084 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2084);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2085 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2085);

-- leader 100：14 项（所领导有预算/工资/计算确认/汇总导出权）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2070 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2070);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2071 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2071);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2072 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2072);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2075 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2075);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2076 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2076);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2077 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2077);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2078 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2078);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2079 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2079);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2080 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2080);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2081 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2081);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2082 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2082);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2083 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2083);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2084 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2084);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2085 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2085);

-- dept_leader 104：3 项（2070 本室工时查看 / 2080 分摊查看 / 2084 单课题导出）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2070 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2070);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2080 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2080);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2084 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2084);

-- researcher 105：5 项（2070/2071/2072 本人填报 + 2080/2084 本人查看+导出）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2070 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2070);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2071 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2071);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2072 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2072);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2080 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2080);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2084 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2084);

-- office 102：1 项（2080 行政查看）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2080 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2080);

-- labor_hr 103：5 项（2070/2075/2078/2080/2084 工资可见可导+分摊只读）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2070 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2070);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2075 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2075);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2078 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2078);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2080 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2080);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2084 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2084);

-- ============================================================================
-- 完
-- ============================================================================