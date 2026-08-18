-- ============================================================================
-- 科研管理平台 经费变更1：经费来源维度 + 参与单位经费划分
-- 版本: V1.0.22
-- 日期: 2026-08-18
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 用户需求"经费录入改为 来源预算 + 支出预算 两栏表格；参与单位经费划分；
--   自筹经费投入和使用"（产品裁决：新增来源维度；保留 10 支出科目；project_unit 加金额）
-- 说明:
--   1. 字典 budget_source（dict_id=234，7 类）：甲方拨款/国家其他拨款/地方政府拨款/
--      上级单位拨款/自筹款/银行贷款/其他来源
--   2. 新表 budget_source：课题经费来源（project_id + source_code + budget_amount）
--   3. project_unit 加 allocated_amount（该参与单位划分的经费金额）
--   4. 幂等：PL 块预检 + INSERT ... WHERE NOT EXISTS，可重复执行
-- ============================================================================

-- ============================================================================
-- 一、字典 budget_source（7 类来源）
-- ============================================================================
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status, create_by, create_time, remark)
SELECT 234, '经费来源', 'budget_source', '0', 'admin', SYSDATE, '课题经费来源（甲方拨款/国家其他拨款/地方政府拨款/上级单位拨款/自筹款/银行贷款/其他来源）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_id = 234);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20148, 1, '甲方拨款', 'PARTY', 'budget_source', '', 'primary', 'N', '0', 'admin', SYSDATE, '甲方拨款（含税）' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20148);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20149, 2, '国家其他拨款', 'NATIONAL', 'budget_source', '', 'success', 'N', '0', 'admin', SYSDATE, '国家其他拨款' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20149);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20150, 3, '地方政府拨款', 'LOCAL', 'budget_source', '', 'info', 'N', '0', 'admin', SYSDATE, '地方政府拨款' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20150);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20151, 4, '上级单位拨款', 'SUPERIOR', 'budget_source', '', 'warning', 'N', '0', 'admin', SYSDATE, '上级单位拨款' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20151);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20152, 5, '自筹款', 'SELF', 'budget_source', '', 'danger', 'N', '0', 'admin', SYSDATE, '自筹经费投入' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20152);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20153, 6, '银行贷款', 'BANK', 'budget_source', '', 'primary', 'N', '0', 'admin', SYSDATE, '银行贷款' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20153);
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20154, 7, '其他来源', 'OTHER', 'budget_source', '', 'info', 'N', '0', 'admin', SYSDATE, '其他来源' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20154);

-- ============================================================================
-- 二、新表 budget_source（课题经费来源）
-- ============================================================================
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TABLES WHERE TABLE_NAME = 'BUDGET_SOURCE';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE budget_source (
            source_id     BIGINT        IDENTITY,
            project_id    BIGINT        NOT NULL,
            source_code   VARCHAR(20)   NOT NULL,
            budget_amount DECIMAL(14,2) DEFAULT 0,
            del_flag      CHAR(1)       DEFAULT ''0'',
            create_by     VARCHAR(64),
            create_time   TIMESTAMP,
            update_by     VARCHAR(64),
            update_time   TIMESTAMP,
            remark        VARCHAR(500)
        )';
        EXECUTE IMMEDIATE 'COMMENT ON TABLE budget_source IS ''课题经费来源（source_code 取 budget_source 字典值）''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN budget_source.source_code IS ''经费来源编码（budget_source 字典值）''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN budget_source.budget_amount IS ''来源预算金额（元）''';
    END IF;
END;

DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_INDEXES WHERE INDEX_NAME = 'IDX_BUDGET_SOURCE_PF';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX idx_budget_source_pf ON budget_source (project_id, source_code)';
    END IF;
END;

-- ============================================================================
-- 三、project_unit 加 allocated_amount（参与单位经费划分）
-- ============================================================================
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'PROJECT_UNIT' AND COLUMN_NAME = 'ALLOCATED_AMOUNT';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE project_unit ADD allocated_amount DECIMAL(14,2) DEFAULT 0';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN project_unit.allocated_amount IS ''该参与单位划分的经费金额（元）''';
    END IF;
END;

COMMIT;

-- ============================================================================
-- 完
-- ============================================================================
