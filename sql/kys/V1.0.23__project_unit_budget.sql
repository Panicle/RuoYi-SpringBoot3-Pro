-- ============================================================================
-- 科研管理平台 课题变更3：按单位经费支出预算
-- 版本: V1.0.23
-- 日期: 2026-08-18
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 用户需求"经费来源预算去掉；经费支出预算按单位录（主持+参与单位各一套 10 科目
--   budget_category）"（用户裁决 2026-08-18：主持单位+参与单位 = 集团二级公司 sys_dept，
--   外单位主持 = 选集团二级公司，不再选 cooperative_unit）
-- 说明:
--   1. project_unit 加 dept_id（参与单位对应的二级公司 dept_id，sys_dept）
--   2. 新表 project_unit_budget：课题按单位经费支出预算（project_id + dept_id + category，
--      dept_id 覆盖主持+参与单位）
--   3. 唯一索引 idx_pub_pdc（project_id, dept_id, category）
--   4. 不加新字典（复用 budget_category / cooperation_type / sys_dept）
--   5. 幂等：PL 块预检 USER_TAB_COLUMNS / USER_TABLES / USER_INDEXES，可重复执行
-- ============================================================================

-- ============================================================================
-- 一、project_unit 加 dept_id（参与单位对应的二级公司）
-- ============================================================================
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'PROJECT_UNIT' AND COLUMN_NAME = 'DEPT_ID';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE project_unit ADD dept_id BIGINT NULL';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN project_unit.dept_id IS ''参与单位对应的二级公司 dept_id（sys_dept）''';
    END IF;
END;

-- ============================================================================
-- 二、新表 project_unit_budget（课题按单位经费支出预算）
-- ============================================================================
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TABLES WHERE TABLE_NAME = 'PROJECT_UNIT_BUDGET';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE project_unit_budget (
            id            BIGINT        IDENTITY,
            project_id    BIGINT        NOT NULL,
            dept_id       BIGINT        NOT NULL,
            category      VARCHAR(20)   NOT NULL,
            budget_amount DECIMAL(14,2) DEFAULT 0,
            del_flag      CHAR(1)       DEFAULT ''0'',
            create_by     VARCHAR(64),
            create_time   TIMESTAMP,
            update_by     VARCHAR(64),
            update_time   TIMESTAMP,
            remark        VARCHAR(500)
        )';
        EXECUTE IMMEDIATE 'COMMENT ON TABLE project_unit_budget IS ''课题按单位经费支出预算（dept_id 覆盖主持+参与单位，category 取 budget_category 字典值）''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN project_unit_budget.project_id IS ''课题ID''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN project_unit_budget.dept_id IS ''二级公司 dept_id（sys_dept，覆盖主持+参与单位）''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN project_unit_budget.category IS ''预算科目（budget_category 字典值，每单位 10 科目）''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN project_unit_budget.budget_amount IS ''该单位该科目预算金额（元）''';
    END IF;
END;

-- ============================================================================
-- 三、唯一索引 idx_pub_pdc（project_id, dept_id, category）幂等
-- ============================================================================
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_INDEXES WHERE INDEX_NAME = 'IDX_PUB_PDC';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX idx_pub_pdc ON project_unit_budget (project_id, dept_id, category)';
    END IF;
END;

COMMIT;

-- ============================================================================
-- 完
-- ============================================================================
