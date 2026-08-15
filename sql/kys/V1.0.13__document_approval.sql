-- ============================================================================
-- 科研管理平台 阶段5：资料与审批数据库基线
-- 版本: V1.0.13
-- 日期: 2026-08-15
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 任务卡_阶段5_资料与审批 §二（数据模型全部内容）
-- 说明:
--   1. project_document 表加 2 列（submitter_id / plan_submit_date）+ 普通索引
--      idx_project_document_submitter（PL 块预检 USER_INDEXES 幂等创建）
--   2. approval 表加 2 列（round / reject_reason）+ 唯一索引 idx_approval_doc_id_uk（doc_id）：
--      PL 块预检 USER_INDEXES + 建索引前先查重复 doc_id（del_flag='0' GROUP BY HAVING COUNT(*)>1），
--      有重复则 RAISE_APPLICATION_ERROR 中止（devdm 现无数据，预期不触发）
--   3. approval_history 表加 1 列（round）
--   4. 菜单 2050-2056 共 7 项（2050 C 课题资料 parent=2010 科研管理，order_num=5；
--      2051-2054 F 查询/上传/删除/发起审批；2055 F 审批操作；2056 F 审批历史）
--   5. 角色挂载（任务卡 §2.4 矩阵，共 31 条 sys_role_menu）：
--      admin(1)/science_admin(101) 全部 7 项；
--      dept_leader(104) 6 项（2050-2056 除 2053 remove）；
--      researcher(105) 5 项（2050/2051/2052/2054/2056）；
--      leader(100)/office(102)/labor_hr(103) 各 2 项（2050/2051 只读）
-- ============================================================================

-- ============================================================================
-- 一、project_document 加 2 列 + 索引（幂等）
-- ============================================================================

-- 1. submitter_id 提交人
ALTER TABLE project_document ADD IF NOT EXISTS submitter_id BIGINT;
COMMENT ON COLUMN project_document.submitter_id IS '提交人 user_id（发起审批时回填；upload_by 保留作冗余）';

-- 2. plan_submit_date 计划提交日期
ALTER TABLE project_document ADD IF NOT EXISTS plan_submit_date DATE;
COMMENT ON COLUMN project_document.plan_submit_date IS '计划提交日期（预警引擎用，科管/室主任手动维护）';

-- 3. 索引 idx_project_document_submitter（PL 块预检 USER_INDEXES，普通索引）
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_INDEXES WHERE INDEX_NAME = 'IDX_PROJECT_DOCUMENT_SUBMITTER';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_project_document_submitter ON project_document (submitter_id)';
    END IF;
END;

-- ============================================================================
-- 二、approval 加 2 列 + 唯一索引（幂等）
-- ============================================================================

-- 1. round 审批轮次
ALTER TABLE approval ADD IF NOT EXISTS round INT DEFAULT 1;
COMMENT ON COLUMN approval.round IS '审批轮次（驳回重报 +1；发起时=1，一份资料一条当前审批）';

-- 2. reject_reason 最近驳回原因
ALTER TABLE approval ADD IF NOT EXISTS reject_reason VARCHAR(500);
COMMENT ON COLUMN approval.reject_reason IS '最近一次驳回原因（REJECT 时回填；重报时清空）';

-- 3. 唯一索引 idx_approval_doc_id_uk（PL 块预检 USER_INDEXES；建索引前先查重复 doc_id，
--    存在重复则 RAISE_APPLICATION_ERROR 中止，不建索引）
DECLARE
    CNT INT;
    DUP_CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_INDEXES WHERE INDEX_NAME = 'IDX_APPROVAL_DOC_ID_UK';
    IF CNT = 0 THEN
        SELECT COUNT(*) INTO DUP_CNT FROM (
            SELECT doc_id FROM approval WHERE del_flag = '0'
            GROUP BY doc_id HAVING COUNT(*) > 1
        );
        IF DUP_CNT > 0 THEN
            RAISE_APPLICATION_ERROR(-20001, 'approval 存在重复 doc_id（一份资料多条有效审批），无法建唯一索引 idx_approval_doc_id_uk，请先人工合并重复行后重跑本脚本');
        ELSE
            EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX idx_approval_doc_id_uk ON approval (doc_id)';
        END IF;
    END IF;
END;

-- ============================================================================
-- 三、approval_history 加 1 列（幂等）
-- ============================================================================

-- 1. round 动作发生轮次
ALTER TABLE approval_history ADD IF NOT EXISTS round INT;
COMMENT ON COLUMN approval_history.round IS '该动作发生时的审批轮次（SUBMIT=1 / REJECT=1 / RESUBMIT=2 / APPROVE=2 等）';

-- ============================================================================
-- 四、菜单 2050-2056（课题资料 + 6 F 按钮，parent=2010 科研管理，order_num=5）
--   2050 C  课题资料（path=document, component=biz/document/index, perms=biz:document:list, icon=documentation）
--   2051 F  查询（biz:document:query）
--   2052 F  上传（biz:document:add）
--   2053 F  删除（biz:document:remove）
--   2054 F  发起审批（biz:document:submit，发起+重报复用）
--   2055 F  审批操作（biz:approval:audit）
--   2056 F  审批历史（biz:approval:history）
-- ============================================================================

-- 1. 2050 课题资料（C）
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2050, '课题资料', 2010, 5, 'document', 'biz/document/index', NULL, '',
       1, 0, 'C', '0', '0', 'biz:document:list', 'documentation',
       'admin', SYSDATE, '', NULL, '课题资料菜单（阶段5；上传/发起审批/审批闭环）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2050);

-- 2. 2051 查询
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2051, '查询', 2050, 1, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:document:query', '#',
       'admin', SYSDATE, '', NULL, '资料-查询'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2051);

-- 3. 2052 上传
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2052, '上传', 2050, 2, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:document:add', '#',
       'admin', SYSDATE, '', NULL, '资料-上传（ARCHIVED 课题拒传）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2052);

-- 4. 2053 删除
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2053, '删除', 2050, 3, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:document:remove', '#',
       'admin', SYSDATE, '', NULL, '资料-删除（PENDING/APPROVED 审批的资料拒删；REJECTED 或无审批可删，级联逻辑删 approval+history）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2053);

-- 5. 2054 发起审批
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2054, '发起审批', 2050, 4, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:document:submit', '#',
       'admin', SYSDATE, '', NULL, '资料-发起审批（round=1 PENDING，写 approval+history SUBMIT；驳回重报复用，round+1）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2054);

-- 6. 2055 审批操作
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2055, '审批操作', 2050, 5, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:approval:audit', '#',
       'admin', SYSDATE, '', NULL, '审批-操作（室主任 dept_leader 本室审批；APPROVE/REJECT 写 approval+history）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2055);

-- 7. 2056 审批历史
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 2056, '审批历史', 2050, 6, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'biz:approval:history', '#',
       'admin', SYSDATE, '', NULL, '审批-历史（approval_history 按 round + operate_time 时间线）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2056);

-- ============================================================================
-- 五、角色挂载（按任务卡 §2.4 矩阵，共 31 条 sys_role_menu）
--   admin(1) / science_admin(101)：全部 7 项（2050-2056）
--   leader(100) / office(102) / labor_hr(103)：只读 2 项（2050+2051）
--   dept_leader(104)：6 项（2050/2051/2052/2054/2055/2056，无 remove）
--   researcher(105)：5 项（2050/2051/2052/2054/2056，无 remove/audit）
--   合计 7+7+2+2+2+6+5 = 31 条
-- ============================================================================

-- admin 1：全部 7 项
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2050 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2050);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2051 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2051);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2052 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2052);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2053 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2053);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2054 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2054);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2055 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2055);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, 2056 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2056);

-- science_admin 101：全部 7 项
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2050 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2050);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2051 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2051);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2052 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2052);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2053 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2053);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2054 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2054);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2055 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2055);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 101, 2056 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2056);

-- leader 100：2050+2051（只读）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2050 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2050);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 100, 2051 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2051);

-- office 102：2050+2051（只读）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2050 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2050);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 102, 2051 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2051);

-- labor_hr 103：2050+2051（只读）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2050 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2050);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 103, 2051 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2051);

-- dept_leader 104：2050/2051/2052/2054/2055/2056（无 2053 remove，本室审批）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2050 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2050);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2051 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2051);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2052 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2052);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2054 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2054);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2055 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2055);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 104, 2056 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 104 AND menu_id = 2056);

-- researcher 105：2050/2051/2052/2054/2056（本人相关；无 remove/audit）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2050 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2050);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2051 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2051);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2052 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2052);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2054 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2054);
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 105, 2056 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 105 AND menu_id = 2056);

-- ============================================================================
-- 完
-- ============================================================================
