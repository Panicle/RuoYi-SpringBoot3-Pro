-- ============================================================================
-- 科研管理平台 字典数据初始化脚本
-- 版本: V1.0.1
-- 日期: 2026-07-08
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 说明:
--   1. 依赖若依框架 sys_dict_type / sys_dict_data 表（已通过 ruoyi-dm8.dmp 导入）
--   2. 依赖 V1.0.0__base_tables.sql 中的 surcharge_rate 表
--   3. 字典 dict_id 从 200 开始，dict_code 从 20001 开始，避免与框架内置字典冲突
--   4. 包含18个业务字典类型 + 10项工资附加费比例数据
-- ============================================================================

-- ============================================================================
-- 一、字典类型（sys_dict_type）
-- ============================================================================

INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status, create_by, create_time, remark) VALUES
(200, '课题状态',     'project_status',      '0', 'admin', SYSDATE, '课题生命周期状态'),
(201, '课题阶段',     'project_stage',       '0', 'admin', SYSDATE, '课题资料所属阶段'),
(202, '经费类别',     'expense_category',    '0', 'admin', SYSDATE, '经费预算与记账类别'),
(203, '合同类型',     'contract_type',       '0', 'admin', SYSDATE, '合同分类'),
(204, '节点类型',     'node_type',           '0', 'admin', SYSDATE, '合同履约节点类型'),
(205, '审批状态',     'approval_status',     '0', 'admin', SYSDATE, '审批流程状态'),
(206, '合作类型',     'cooperation_type',    '0', 'admin', SYSDATE, '课题合作单位合作方式'),
(207, '荣誉级别',     'honor_level',         '0', 'admin', SYSDATE, '荣誉获奖级别'),
(208, '荣誉类型',     'honor_type',          '0', 'admin', SYSDATE, '荣誉集体/个人分类'),
(209, '预警类型',     'alert_type',          '0', 'admin', SYSDATE, '预警分类'),
(210, '预警级别',     'alert_level',         '0', 'admin', SYSDATE, '预警紧急程度'),
(211, '课题成员角色', 'member_role',         '0', 'admin', SYSDATE, '课题成员角色'),
(212, '学历',         'edu_level',           '0', 'admin', SYSDATE, '科研人员学历'),
(213, '职称',         'title_level',         '0', 'admin', SYSDATE, '科研人员职称'),
(214, '研发分摊状态', 'rd_alloc_status',     '0', 'admin', SYSDATE, '研发人工费分摊状态'),
(215, '工资附加费比例', 'rd_surcharge_rate', '0', 'admin', SYSDATE, '工资附加费计提比例'),
(216, '单位类型',     'unit_type',           '0', 'admin', SYSDATE, '合作单位内外部类型'),
(217, '外部单位类型', 'external_unit_type',  '0', 'admin', SYSDATE, '外部单位分类');

-- ============================================================================
-- 二、字典数据（sys_dict_data）
-- ============================================================================

-- ----------------------------
-- 1. 课题状态 project_status
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20001, 1, '立项', 'DRAFT',     'project_status', '', 'default',  'Y', '0', 'admin', SYSDATE, '立项阶段'),
(20002, 2, '在研', 'ACTIVE',    'project_status', '', 'primary',  'N', '0', 'admin', SYSDATE, '在研阶段'),
(20003, 3, '结题', 'COMPLETED', 'project_status', '', 'success',  'N', '0', 'admin', SYSDATE, '已结题'),
(20004, 4, '评审', 'ACCEPTED',  'project_status', '', 'info',     'N', '0', 'admin', SYSDATE, '已通过评审'),
(20005, 5, '归档', 'ARCHIVED',  'project_status', '', 'warning',  'N', '0', 'admin', SYSDATE, '已归档');

-- ----------------------------
-- 2. 课题阶段 project_stage
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20006, 1, '立项',     'INITIATION', 'project_stage', '', 'primary',  'Y', '0', 'admin', SYSDATE, '立项阶段'),
(20007, 2, '节点考核', 'MIDTERM',    'project_stage', '', 'info',     'N', '0', 'admin', SYSDATE, '节点考核阶段'),
(20008, 3, '结题',     'CLOSING',    'project_stage', '', 'warning',  'N', '0', 'admin', SYSDATE, '结题阶段'),
(20009, 4, '评审',     'REVIEW',     'project_stage', '', 'success',  'N', '0', 'admin', SYSDATE, '评审阶段');

-- ----------------------------
-- 3. 经费类别 expense_category
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20010, 1, '设备费',             'EQUIPMENT',   'expense_category', '', 'primary',  'N', '0', 'admin', SYSDATE, '设备购置费'),
(20011, 2, '材料费',             'MATERIAL',    'expense_category', '', 'info',     'N', '0', 'admin', SYSDATE, '材料消耗费'),
(20012, 3, '差旅费',             'TRAVEL',      'expense_category', '', 'success',  'N', '0', 'admin', SYSDATE, '差旅费用'),
(20013, 4, '劳务费',             'LABOR',       'expense_category', '', 'warning',  'N', '0', 'admin', SYSDATE, '劳务费用'),
(20014, 5, '测试化验加工费',     'TESTING',     'expense_category', '', 'default',  'N', '0', 'admin', SYSDATE, '测试化验加工费用'),
(20015, 6, '出版/文献/信息传播', 'PUBLICATION', 'expense_category', '', 'default',  'N', '0', 'admin', SYSDATE, '出版、文献、信息传播费'),
(20016, 7, '其他',               'OTHER',       'expense_category', '', 'info',     'N', '0', 'admin', SYSDATE, '其他费用');

-- ----------------------------
-- 4. 合同类型 contract_type
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20017, 1, '研究', 'RESEARCH',    'contract_type', '', 'primary',  'Y', '0', 'admin', SYSDATE, '研究类合同'),
(20018, 2, '服务', 'SERVICE',     'contract_type', '', 'success',  'N', '0', 'admin', SYSDATE, '服务类合同'),
(20019, 3, '采购', 'PROCUREMENT', 'contract_type', '', 'warning',  'N', '0', 'admin', SYSDATE, '采购类合同');

-- ----------------------------
-- 5. 节点类型 node_type
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20020, 1, '付款', 'PAYMENT',     'node_type', '', 'warning',  'N', '0', 'admin', SYSDATE, '付款节点'),
(20021, 2, '交付', 'DELIVERY',    'node_type', '', 'primary',  'N', '0', 'admin', SYSDATE, '交付节点'),
(20022, 3, '验收', 'ACCEPTANCE',  'node_type', '', 'success',  'N', '0', 'admin', SYSDATE, '验收节点');

-- ----------------------------
-- 6. 审批状态 approval_status
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20023, 1, '审核中', 'PENDING',  'approval_status', '', 'warning',  'Y', '0', 'admin', SYSDATE, '审核中'),
(20024, 2, '已通过', 'APPROVED', 'approval_status', '', 'success',  'N', '0', 'admin', SYSDATE, '已通过'),
(20025, 3, '驳回',   'REJECTED', 'approval_status', '', 'danger',   'N', '0', 'admin', SYSDATE, '已驳回');

-- ----------------------------
-- 7. 合作类型 cooperation_type
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20026, 1, '牵头', 'LEAD',        'cooperation_type', '', 'primary',  'N', '0', 'admin', SYSDATE, '牵头单位'),
(20027, 2, '参与', 'PARTICIPANT', 'cooperation_type', '', 'success',  'N', '0', 'admin', SYSDATE, '参与单位'),
(20028, 3, '协作', 'COLLABORATE', 'cooperation_type', '', 'info',     'N', '0', 'admin', SYSDATE, '协作单位');

-- ----------------------------
-- 8. 荣誉级别 honor_level
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20029, 1, '国家级',     'NATIONAL',   'honor_level', '', 'danger',   'N', '0', 'admin', SYSDATE, '国家级荣誉'),
(20030, 2, '省部级',     'PROVINCIAL', 'honor_level', '', 'warning',  'N', '0', 'admin', SYSDATE, '省部级荣誉'),
(20031, 3, '国铁集团级', 'GROUP',      'honor_level', '', 'primary',  'N', '0', 'admin', SYSDATE, '国铁集团级荣誉'),
(20032, 4, '集团公司级', 'COMPANY',    'honor_level', '', 'success',  'N', '0', 'admin', SYSDATE, '集团公司级荣誉'),
(20033, 5, '所级',       'INSTITUTE',  'honor_level', '', 'info',     'N', '0', 'admin', SYSDATE, '所级荣誉');

-- ----------------------------
-- 9. 荣誉类型 honor_type
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20034, 1, '集体', 'COLLECTIVE', 'honor_type', '', 'primary',  'N', '0', 'admin', SYSDATE, '集体荣誉'),
(20035, 2, '个人', 'INDIVIDUAL', 'honor_type', '', 'success',  'N', '0', 'admin', SYSDATE, '个人荣誉');

-- ----------------------------
-- 10. 预警类型 alert_type
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20036, 1, '合同节点', 'CONTRACT', 'alert_type', '', 'warning',  'N', '0', 'admin', SYSDATE, '合同到期/履约节点预警'),
(20037, 2, '经费超限', 'BUDGET',   'alert_type', '', 'danger',   'N', '0', 'admin', SYSDATE, '经费超支/不足预警'),
(20038, 3, '资料逾期', 'DOCUMENT', 'alert_type', '', 'info',     'N', '0', 'admin', SYSDATE, '资料缺失/逾期预警');

-- ----------------------------
-- 11. 预警级别 alert_level
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20039, 1, '普通', 'INFO',     'alert_level', '', 'info',     'N', '0', 'admin', SYSDATE, '一般提示'),
(20040, 2, '重要', 'WARN',     'alert_level', '', 'warning',  'N', '0', 'admin', SYSDATE, '重要级别'),
(20041, 3, '紧急', 'CRITICAL', 'alert_level', '', 'danger',   'N', '0', 'admin', SYSDATE, '紧急级别');

-- ----------------------------
-- 12. 课题成员角色 member_role
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20042, 1, '主持人', 'HOST',        'member_role', '', 'primary',  'N', '0', 'admin', SYSDATE, '课题主持人'),
(20043, 2, '参与人', 'PARTICIPANT', 'member_role', '', 'success',  'N', '0', 'admin', SYSDATE, '课题参与人');

-- ----------------------------
-- 13. 学历 edu_level
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20044, 1, '本科', 'BACHELOR', 'edu_level', '', 'info',     'N', '0', 'admin', SYSDATE, '本科学历'),
(20045, 2, '硕士', 'MASTER',   'edu_level', '', 'primary',  'N', '0', 'admin', SYSDATE, '硕士学历'),
(20046, 3, '博士', 'DOCTOR',   'edu_level', '', 'success',  'N', '0', 'admin', SYSDATE, '博士学历');

-- ----------------------------
-- 14. 职称 title_level
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20047, 1, '初级', 'JUNIOR',     'title_level', '', 'info',     'N', '0', 'admin', SYSDATE, '初级职称'),
(20048, 2, '中级', 'MID',        'title_level', '', 'primary',  'N', '0', 'admin', SYSDATE, '中级职称'),
(20049, 3, '副高', 'SUB_SENIOR', 'title_level', '', 'warning',  'N', '0', 'admin', SYSDATE, '副高级职称'),
(20050, 4, '正高', 'SENIOR',     'title_level', '', 'danger',   'N', '0', 'admin', SYSDATE, '正高级职称');

-- ----------------------------
-- 15. 研发分摊状态 rd_alloc_status
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20051, 1, '草稿',   'DRAFT',     'rd_alloc_status', '', 'warning',  'Y', '0', 'admin', SYSDATE, '草稿状态'),
(20052, 2, '已确认', 'CONFIRMED', 'rd_alloc_status', '', 'success',  'N', '0', 'admin', SYSDATE, '已确认状态');

-- ----------------------------
-- 16. 工资附加费比例 rd_surcharge_rate
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20053, 1,  '职工教育经费(1.5%)',     'edu',          'rd_surcharge_rate', '', 'default', 'N', '0', 'admin', SYSDATE, '职工教育经费计提比例1.5%'),
(20054, 2,  '工会经费(2%)',           'union',        'rd_surcharge_rate', '', 'default', 'N', '0', 'admin', SYSDATE, '工会经费计提比例2%'),
(20055, 3,  '基本医疗保险费(8%)',     'med',          'rd_surcharge_rate', '', 'default', 'N', '0', 'admin', SYSDATE, '基本医疗保险费计提比例8%'),
(20056, 4,  '补充医疗保险费(2%)',     'med_sup',      'rd_surcharge_rate', '', 'default', 'N', '0', 'admin', SYSDATE, '补充医疗保险费计提比例2%'),
(20057, 5,  '基本养老保险费(16%)',    'pension',      'rd_surcharge_rate', '', 'default', 'N', '0', 'admin', SYSDATE, '基本养老保险费计提比例16%'),
(20058, 6,  '企业年金(7%)',           'annuity',      'rd_surcharge_rate', '', 'default', 'N', '0', 'admin', SYSDATE, '企业年金计提比例7%'),
(20059, 7,  '失业保险费(0.7%)',       'unemploy',     'rd_surcharge_rate', '', 'default', 'N', '0', 'admin', SYSDATE, '失业保险费计提比例0.7%'),
(20060, 8,  '工伤保险费(0.36%)',      'injury',       'rd_surcharge_rate', '', 'default', 'N', '0', 'admin', SYSDATE, '工伤保险费计提比例0.36%'),
(20061, 9,  '住房公积金(12%)',        'housing_fund', 'rd_surcharge_rate', '', 'default', 'N', '0', 'admin', SYSDATE, '住房公积金计提比例12%'),
(20062, 10, '帮扶救助(0.3%)',         'relief',       'rd_surcharge_rate', '', 'default', 'N', '0', 'admin', SYSDATE, '帮扶救助计提比例0.3%');

-- ----------------------------
-- 17. 单位类型 unit_type
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20063, 1, '内部单位', 'INTERNAL', 'unit_type', '', 'primary',  'N', '0', 'admin', SYSDATE, '内部单位'),
(20064, 2, '外部单位', 'EXTERNAL', 'unit_type', '', 'success',  'N', '0', 'admin', SYSDATE, '外部单位');

-- ----------------------------
-- 18. 外部单位类型 external_unit_type
-- ----------------------------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) VALUES
(20065, 1, '公司', 'COMPANY', 'external_unit_type', '', 'primary',  'N', '0', 'admin', SYSDATE, '公司'),
(20066, 2, '学校', 'SCHOOL',  'external_unit_type', '', 'success',  'N', '0', 'admin', SYSDATE, '学校'),
(20067, 3, '其他', 'OTHER',   'external_unit_type', '', 'info',     'N', '0', 'admin', SYSDATE, '其他类型单位');

-- ============================================================================
-- 三、工资附加费比例数据（surcharge_rate 表）
-- 说明：rate_id 使用 IDENTITY 自增，INSERT 不指定 rate_id
-- ============================================================================

INSERT INTO surcharge_rate (rate_name, rate_code, rate_value, status, create_by, create_time) VALUES
('职工教育经费',   'edu',          0.0150, 'ACTIVE', 'admin', SYSDATE),
('工会经费',       'union',        0.0200, 'ACTIVE', 'admin', SYSDATE),
('基本医疗保险费', 'med',          0.0800, 'ACTIVE', 'admin', SYSDATE),
('补充医疗保险费', 'med_sup',      0.0200, 'ACTIVE', 'admin', SYSDATE),
('基本养老保险费', 'pension',      0.1600, 'ACTIVE', 'admin', SYSDATE),
('企业年金',       'annuity',      0.0700, 'ACTIVE', 'admin', SYSDATE),
('失业保险费',     'unemploy',     0.0070, 'ACTIVE', 'admin', SYSDATE),
('工伤保险费',     'injury',       0.0036, 'ACTIVE', 'admin', SYSDATE),
('住房公积金',     'housing_fund', 0.1200, 'ACTIVE', 'admin', SYSDATE),
('帮扶救助',       'relief',       0.0030, 'ACTIVE', 'admin', SYSDATE);

-- ============================================================================
-- 完
-- ============================================================================
