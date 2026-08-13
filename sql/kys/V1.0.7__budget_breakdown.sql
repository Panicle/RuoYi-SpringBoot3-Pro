-- ============================================================================
-- 科研管理平台 阶段2变更1：课题预算细分数据库基线
-- 版本: V1.0.7
-- 日期: 2026-08-13
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 任务卡_阶段2_课题管理_变更1 §1.2 / §2.1
-- 说明:
--   1. 新增字典 budget_category（dict_id=222，dict_code 20097-20106，10值大写）
--      对应预算总额拆分的 10 个叶子科目（直接费/间接费/委外支出费/税金）
--   2. budget_split.category 列注释更新为「预算科目（对应字典 budget_category）」
--      （表已存在 V1.0.0，无 DDL 变更；category 语义由 expense_category 改为 budget_category）
--   3. 幂等：字典走 INSERT ... SELECT ... WHERE NOT EXISTS；
--      COMMENT ON COLUMN 直接执行（达梦重跑覆盖为同值，零副作用，同 V1.0.6 已验）
-- ============================================================================

-- ============================================================================
-- 一、新增字典类型 budget_category（dict_id=222）
-- ============================================================================

INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status, create_by, create_time, remark)
SELECT 222, '预算科目', 'budget_category', '0', 'admin', SYSDATE, '课题预算细分叶子科目（直接费/间接费/委外支出费/税金）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_id = 222);

-- ============================================================================
-- 二、新增字典数据（10 项 20097-20106，dict_sort 1-10，值大写，list_class 样式标签）
-- ============================================================================

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20097, 1,  '人工费',       'LABOR',       'budget_category', '', 'primary',  'N', '0', 'admin', SYSDATE, '人工费（直接费）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20097);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20098, 2,  '设备费',       'EQUIPMENT',   'budget_category', '', 'success',  'N', '0', 'admin', SYSDATE, '设备费（直接费）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20098);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20099, 3,  '材料费',       'MATERIAL',    'budget_category', '', 'info',     'N', '0', 'admin', SYSDATE, '材料费（直接费/业务费）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20099);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20100, 4,  '测试化验加工费', 'TESTING',   'budget_category', '', 'warning',  'N', '0', 'admin', SYSDATE, '测试化验加工费（直接费/业务费）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20100);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20101, 5,  '燃料动力费',   'FUEL',        'budget_category', '', 'default',  'N', '0', 'admin', SYSDATE, '燃料动力费（直接费/业务费）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20101);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20102, 6,  '差旅费会议费国际合作交流费', 'TRAVEL', 'budget_category', '', 'primary', 'N', '0', 'admin', SYSDATE, '差旅费/会议费/国际合作交流费（直接费/业务费）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20102);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20103, 7,  '出版文献信息传播知识产权事务费', 'PUBLICATION', 'budget_category', '', 'info', 'N', '0', 'admin', SYSDATE, '出版/文献/信息传播/知识产权事务费（直接费/业务费）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20103);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20104, 8,  '间接费',       'INDIRECT',    'budget_category', '', 'warning',  'N', '0', 'admin', SYSDATE, '间接费（顶级科目）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20104);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20105, 9,  '委外支出费',   'OUTSOURCING', 'budget_category', '', 'primary',  'N', '0', 'admin', SYSDATE, '委外支出费（顶级科目）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20105);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20106, 10, '税金',         'TAX',         'budget_category', '', 'danger',   'N', '0', 'admin', SYSDATE, '税金（顶级科目）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20106);

-- ============================================================================
-- 三、budget_split.category 列注释更新
--    category 语义由 expense_category（经费类别）改为 budget_category（预算科目）
-- ============================================================================

COMMENT ON COLUMN budget_split.category IS '预算科目（对应字典 budget_category）';

-- ============================================================================
-- 完
-- ============================================================================
