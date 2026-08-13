-- ============================================================================
-- 科研管理平台 阶段2变更2：项目类别/专业分类字典 + 主持人改组长 数据库基线
-- 版本: V1.0.8
-- 日期: 2026-08-13
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 任务卡_阶段2_课题管理_变更2 §1.1 / §1.3 / §2.1
-- 说明:
--   1. 新增字典 project_category（dict_id=223，dict_code 20107-20109，3值）
--      项目类别=经费来源：A全额资助课题 / B定额补助课题 / C经费全部自筹课题
--   2. 新增字典 specialty（dict_id=224，dict_code 20110-20118，9值）
--      专业分类=铁路专业：Y运输/J机务/GD供电/C车辆/G工务工程/D电务/X信息技术/Z综合/F软科学
--   3. project 加列 project_category VARCHAR(20) + specialty VARCHAR(20)
--      （可空——存量数据无值，必填约束在应用层，幂等 ADD IF NOT EXISTS，同 V1.0.6）
--   4. UPDATE sys_dict_data：member_role HOST 标签 主持人→组长
--      （幂等：追加 AND dict_label='主持人'，二次重跑 WHERE 不命中，零副作用）
--   5. 幂等：字典走 INSERT ... SELECT ... WHERE NOT EXISTS；
--      COMMENT ON COLUMN 直接执行（达梦重跑覆盖为同值，零副作用，同 V1.0.6/V1.0.7 已验）
-- ============================================================================

-- ============================================================================
-- 一、新增字典类型 project_category（dict_id=223，项目类别=经费来源）
-- ============================================================================

INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status, create_by, create_time, remark)
SELECT 223, '项目类别', 'project_category', '0', 'admin', SYSDATE, '课题经费来源类别（A全额资助/B定额补助/C经费全部自筹）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_id = 223);

-- ============================================================================
-- 二、新增字典数据 project_category（3 项 20107-20109，dict_sort 1-3）
-- ============================================================================

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20107, 1, '全额资助课题',   'A', 'project_category', '', 'primary', 'N', '0', 'admin', SYSDATE, '全额资助课题'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20107);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20108, 2, '定额补助课题',   'B', 'project_category', '', 'warning', 'N', '0', 'admin', SYSDATE, '定额补助课题'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20108);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20109, 3, '经费全部自筹课题', 'C', 'project_category', '', 'default', 'N', '0', 'admin', SYSDATE, '经费全部自筹课题'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20109);

-- ============================================================================
-- 三、新增字典类型 specialty（dict_id=224，专业分类=铁路专业）
-- ============================================================================

INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status, create_by, create_time, remark)
SELECT 224, '专业分类', 'specialty', '0', 'admin', SYSDATE, '铁路专业分类（运输/机务/供电/车辆/工务工程/电务/信息技术/综合/软科学）'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_id = 224);

-- ============================================================================
-- 四、新增字典数据 specialty（9 项 20110-20118，dict_sort 1-9）
-- ============================================================================

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20110, 1, '运输',     'Y',  'specialty', '', 'primary', 'N', '0', 'admin', SYSDATE, '运输专业'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20110);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20111, 2, '机务',     'J',  'specialty', '', 'success', 'N', '0', 'admin', SYSDATE, '机务专业'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20111);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20112, 3, '供电',     'GD', 'specialty', '', 'info',    'N', '0', 'admin', SYSDATE, '供电专业'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20112);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20113, 4, '车辆',     'C',  'specialty', '', 'warning', 'N', '0', 'admin', SYSDATE, '车辆专业'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20113);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20114, 5, '工务工程', 'G',  'specialty', '', 'default', 'N', '0', 'admin', SYSDATE, '工务工程专业'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20114);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20115, 6, '电务',     'D',  'specialty', '', 'danger',  'N', '0', 'admin', SYSDATE, '电务专业'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20115);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20116, 7, '信息技术', 'X',  'specialty', '', 'primary', 'N', '0', 'admin', SYSDATE, '信息技术专业'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20116);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20117, 8, '综合',     'Z',  'specialty', '', 'success', 'N', '0', 'admin', SYSDATE, '综合专业'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20117);

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
SELECT 20118, 9, '软科学',   'F',  'specialty', '', 'info',    'N', '0', 'admin', SYSDATE, '软科学专业'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_code = 20118);

-- ============================================================================
-- 五、project 加列 project_category + specialty（可空，必填约束在应用层）
-- ============================================================================

ALTER TABLE project ADD IF NOT EXISTS project_category VARCHAR(20) DEFAULT NULL;
COMMENT ON COLUMN project.project_category IS '项目类别（字典 project_category：A全额资助课题/B定额补助课题/C经费全部自筹课题）';

ALTER TABLE project ADD IF NOT EXISTS specialty VARCHAR(20) DEFAULT NULL;
COMMENT ON COLUMN project.specialty IS '专业分类（字典 specialty：Y运输/J机务/GD供电/C车辆/G工务工程/D电务/X信息技术/Z综合/F软科学）';

-- ============================================================================
-- 六、member_role HOST 标签 主持人→组长（幂等：仅当现值仍为主持人时更新）
-- ============================================================================

UPDATE sys_dict_data
   SET dict_label = '组长', update_by = 'admin', update_time = SYSDATE
 WHERE dict_type = 'member_role' AND dict_value = 'HOST' AND dict_label = '主持人';

-- ============================================================================
-- 完
-- ============================================================================
