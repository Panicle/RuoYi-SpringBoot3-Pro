-- ============================================================================
-- 科研管理平台 阶段4 收尾修复
-- 版本: V1.0.12
-- 日期: 2026-08-15
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 用户反馈 4 项中的 2 项数据侧修复
--   1. 菜单图标：2011 课题管理 / 2020 合作单位 icon 原为无效值 '#'
--      （V1.0.6 / V1.0.9 建菜单时的笔误），导致菜单栏无图标
--   2. 税率改字典：tax_rate 原 DECIMAL(5,4) 自由输入，改为字典 tax_rate
--      （1%/3%/6%/13%）；字段类型 DECIMAL → VARCHAR(20) 存字典值，
--      与 category/status 等字典字段存法一致（13 作为整数百分比 DECIMAL(5,4) 装不下）
-- ============================================================================

-- ============================================================================
-- 一、菜单图标修复（幂等：仅当 icon 仍为无效值 '#' 时更新）
-- ============================================================================
-- 课题管理：education（科研/学术）
UPDATE sys_menu SET icon = 'education' WHERE menu_id = 2011 AND icon = '#';
-- 合作单位：peoples（组织/单位）
UPDATE sys_menu SET icon = 'peoples'   WHERE menu_id = 2020 AND icon = '#';

-- ============================================================================
-- 二、税率字段改字典（幂等）
-- ============================================================================

-- 1. tax_rate 字段类型 DECIMAL(5,4) → VARCHAR(20)（PL 块预检列类型，幂等）
DECLARE
    v_type VARCHAR(100);
BEGIN
    SELECT DATA_TYPE INTO v_type FROM USER_TAB_COLUMNS WHERE TABLE_NAME = 'EXPENSE' AND COLUMN_NAME = 'TAX_RATE';
    IF v_type <> 'VARCHAR' THEN
        EXECUTE IMMEDIATE 'ALTER TABLE expense MODIFY tax_rate VARCHAR(20)';
    END IF;
END;
COMMENT ON COLUMN expense.tax_rate IS '税率（百分比，字典 tax_rate：1/3/6/13；VARCHAR 存字典值）';

-- 2. 字典 tax_rate（dict_id=230，dict_code 20135-20138，dict_value 大写数字字符串）
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status, create_by, create_time, remark)
SELECT 230, '税率', 'tax_rate', '0', 'admin', SYSDATE, '记账税率（百分比：1%/3%/6%/13%）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_id = 230);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20135, 1, '1%',  '1',  'tax_rate', '', 'primary', 'N', '0', 'admin', SYSDATE, '税率 1%'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20135);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20136, 2, '3%',  '3',  'tax_rate', '', 'info',    'N', '0', 'admin', SYSDATE, '税率 3%'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20136);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20137, 3, '6%',  '6',  'tax_rate', '', 'success', 'N', '0', 'admin', SYSDATE, '税率 6%'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20137);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20138, 4, '13%', '13', 'tax_rate', '', 'warning', 'N', '0', 'admin', SYSDATE, '税率 13%'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20138);

-- ============================================================================
-- 完
-- ============================================================================
