-- ============================================================================
-- 科研管理平台 基础表DDL脚本
-- 版本: V1.0.0
-- 日期: 2026-07-08
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 说明:
--   1. 包含22张业务表DDL（另含4张若依框架表结构参考），适配达梦DM8兼容语法
--   2. 自增主键使用 IDENTITY(1,1)（达梦原生自增，无需序列）
--   3. 移除外键约束（若依规范，应用层保证关联完整性）
--   4. 每张表含 del_flag CHAR(1) DEFAULT '0' 逻辑删除字段
--   5. 每张表含 create_by/create_time/update_by/update_time 审计字段 + remark 备注字段（适配 BaseEntity）
--   6. 视图保持Oracle风格语法（达梦兼容）；经费核减由 Service 层事务维护，不使用触发器（2026-08-11 依阶段0任务卡决策移除 trg_expense_after_insert）
-- ============================================================================

-- ============================================================================
-- 模块一：若依框架基础表（4张）
-- 【重要】以下4张表已通过 ruoyi-dm8.dmp 导入，正常情况下无需重复创建。
-- 如已存在请跳过此模块的 CREATE TABLE 语句，直接执行后续模块。
-- 此处DDL仅供结构参考与字段对照。
-- ============================================================================

-- ----------------------------
-- 1. 系统用户表 sys_user
-- ----------------------------
CREATE TABLE sys_user (
    user_id        BIGINT IDENTITY(1,1) NOT NULL,
    dept_id        BIGINT       DEFAULT NULL,
    user_name      VARCHAR(30)  NOT NULL,
    nick_name      VARCHAR(30)  NOT NULL,
    email          VARCHAR(50)  DEFAULT '',
    phonenumber    VARCHAR(11)  DEFAULT '',
    sex            CHAR(1)      DEFAULT '0',
    password       VARCHAR(100) DEFAULT '',
    status         CHAR(1)      DEFAULT '0',
    del_flag       CHAR(1)      DEFAULT '0',
    login_ip       VARCHAR(128) DEFAULT '',
    login_date     TIMESTAMP    DEFAULT NULL,
    create_by      VARCHAR(64)  DEFAULT '',
    create_time    TIMESTAMP    DEFAULT NULL,
    update_by      VARCHAR(64)  DEFAULT '',
    update_time    TIMESTAMP    DEFAULT NULL,
    remark         VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (user_id)
);

COMMENT ON TABLE  sys_user IS '系统用户表';
COMMENT ON COLUMN sys_user.user_id     IS '用户ID';
COMMENT ON COLUMN sys_user.dept_id     IS '部门ID';
COMMENT ON COLUMN sys_user.user_name   IS '用户名';
COMMENT ON COLUMN sys_user.nick_name   IS '昵称';
COMMENT ON COLUMN sys_user.email       IS '邮箱';
COMMENT ON COLUMN sys_user.phonenumber IS '手机号';
COMMENT ON COLUMN sys_user.sex         IS '性别（0男 1女 2未知）';
COMMENT ON COLUMN sys_user.password    IS '密码';
COMMENT ON COLUMN sys_user.status      IS '状态（0正常 1停用）';
COMMENT ON COLUMN sys_user.del_flag    IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN sys_user.login_ip    IS '最后登录IP';
COMMENT ON COLUMN sys_user.login_date  IS '最后登录时间';
COMMENT ON COLUMN sys_user.create_by   IS '创建者';
COMMENT ON COLUMN sys_user.create_time IS '创建时间';
COMMENT ON COLUMN sys_user.update_by   IS '更新者';
COMMENT ON COLUMN sys_user.update_time IS '更新时间';
COMMENT ON COLUMN sys_user.remark      IS '备注';

CREATE INDEX idx_sys_user_dept_id     ON sys_user (dept_id);
CREATE INDEX idx_sys_user_user_name   ON sys_user (user_name);
CREATE INDEX idx_sys_user_phonenumber ON sys_user (phonenumber);

-- ----------------------------
-- 2. 系统部门表 sys_dept
-- ----------------------------
CREATE TABLE sys_dept (
    dept_id       BIGINT IDENTITY(1,1) NOT NULL,
    parent_id     BIGINT       DEFAULT 0,
    ancestors     VARCHAR(50)  DEFAULT '',
    dept_name     VARCHAR(30)  DEFAULT '',
    order_num     INT          DEFAULT 0,
    leader        VARCHAR(20)  DEFAULT NULL,
    phone         VARCHAR(11)  DEFAULT NULL,
    email         VARCHAR(50)  DEFAULT NULL,
    status        CHAR(1)      DEFAULT '0',
    del_flag      CHAR(1)      DEFAULT '0',
    create_by     VARCHAR(64)  DEFAULT '',
    create_time   TIMESTAMP    DEFAULT NULL,
    update_by     VARCHAR(64)  DEFAULT '',
    update_time   TIMESTAMP    DEFAULT NULL,
    PRIMARY KEY (dept_id)
);

COMMENT ON TABLE  sys_dept IS '系统部门表';
COMMENT ON COLUMN sys_dept.dept_id     IS '部门ID';
COMMENT ON COLUMN sys_dept.parent_id   IS '父部门ID';
COMMENT ON COLUMN sys_dept.ancestors   IS '祖级列表';
COMMENT ON COLUMN sys_dept.dept_name   IS '部门名称';
COMMENT ON COLUMN sys_dept.order_num   IS '显示顺序';
COMMENT ON COLUMN sys_dept.leader      IS '负责人';
COMMENT ON COLUMN sys_dept.phone       IS '联系电话';
COMMENT ON COLUMN sys_dept.email       IS '邮箱';
COMMENT ON COLUMN sys_dept.status      IS '状态（0正常 1停用）';
COMMENT ON COLUMN sys_dept.del_flag    IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN sys_dept.create_by   IS '创建者';
COMMENT ON COLUMN sys_dept.create_time IS '创建时间';
COMMENT ON COLUMN sys_dept.update_by   IS '更新者';
COMMENT ON COLUMN sys_dept.update_time IS '更新时间';

CREATE INDEX idx_sys_dept_parent_id ON sys_dept (parent_id);

-- ----------------------------
-- 3. 系统角色表 sys_role
-- ----------------------------
CREATE TABLE sys_role (
    role_id       BIGINT IDENTITY(1,1) NOT NULL,
    role_name     VARCHAR(30)  NOT NULL,
    role_key      VARCHAR(100) NOT NULL,
    role_sort     INT          NOT NULL,
    status        CHAR(1)      DEFAULT '0',
    del_flag      CHAR(1)      DEFAULT '0',
    create_by     VARCHAR(64)  DEFAULT '',
    create_time   TIMESTAMP    DEFAULT NULL,
    update_by     VARCHAR(64)  DEFAULT '',
    update_time   TIMESTAMP    DEFAULT NULL,
    remark        VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (role_id)
);

COMMENT ON TABLE  sys_role IS '系统角色表';
COMMENT ON COLUMN sys_role.role_id     IS '角色ID';
COMMENT ON COLUMN sys_role.role_name   IS '角色名称';
COMMENT ON COLUMN sys_role.role_key    IS '角色权限字符串';
COMMENT ON COLUMN sys_role.role_sort   IS '显示顺序';
COMMENT ON COLUMN sys_role.status      IS '状态（0正常 1停用）';
COMMENT ON COLUMN sys_role.del_flag    IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN sys_role.create_by   IS '创建者';
COMMENT ON COLUMN sys_role.create_time IS '创建时间';
COMMENT ON COLUMN sys_role.update_by   IS '更新者';
COMMENT ON COLUMN sys_role.update_time IS '更新时间';
COMMENT ON COLUMN sys_role.remark      IS '备注';

-- ----------------------------
-- 4. 系统用户角色关联表 sys_user_role
-- ----------------------------
CREATE TABLE sys_user_role (
    user_id     BIGINT      NOT NULL,
    role_id     BIGINT      NOT NULL,
    del_flag    CHAR(1)     DEFAULT '0',
    create_by   VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP   DEFAULT NULL,
    update_by   VARCHAR(64) DEFAULT '',
    update_time TIMESTAMP   DEFAULT NULL,
    PRIMARY KEY (user_id, role_id)
);

COMMENT ON TABLE  sys_user_role IS '系统用户角色关联表';
COMMENT ON COLUMN sys_user_role.user_id     IS '用户ID';
COMMENT ON COLUMN sys_user_role.role_id     IS '角色ID';
COMMENT ON COLUMN sys_user_role.del_flag    IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN sys_user_role.create_by   IS '创建者';
COMMENT ON COLUMN sys_user_role.create_time IS '创建时间';
COMMENT ON COLUMN sys_user_role.update_by   IS '更新者';
COMMENT ON COLUMN sys_user_role.update_time IS '更新时间';

CREATE INDEX idx_sys_user_role_role_id ON sys_user_role (role_id);

-- ============================================================================
-- 模块二：科研人员管理（1张）
-- ============================================================================

-- ----------------------------
-- 5. 科研人员扩展表 biz_user_profile
-- ----------------------------
CREATE TABLE biz_user_profile (
    profile_id         BIGINT IDENTITY(1,1) NOT NULL,
    user_id            BIGINT       NOT NULL,
    edu_level          VARCHAR(20)  DEFAULT NULL,
    title_level        VARCHAR(20)  DEFAULT NULL,
    research_direction VARCHAR(200) DEFAULT NULL,
    research_area      VARCHAR(200) DEFAULT NULL,
    id_number          VARCHAR(18)  DEFAULT NULL,
    entry_date         DATE         DEFAULT NULL,
    office_phone       VARCHAR(20)  DEFAULT NULL,
    del_flag           CHAR(1)      DEFAULT '0',
    create_by          VARCHAR(64)  DEFAULT '',
    create_time        TIMESTAMP    DEFAULT NULL,
    update_by          VARCHAR(64)  DEFAULT '',
    update_time        TIMESTAMP    DEFAULT NULL,
    remark             VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (profile_id)
);

COMMENT ON TABLE  biz_user_profile IS '科研人员扩展表';
COMMENT ON COLUMN biz_user_profile.profile_id         IS '档案ID';
COMMENT ON COLUMN biz_user_profile.user_id            IS '关联系统用户ID';
COMMENT ON COLUMN biz_user_profile.edu_level          IS '学历（对应字典 edu_level）';
COMMENT ON COLUMN biz_user_profile.title_level        IS '职称等级（对应字典 title_level）';
COMMENT ON COLUMN biz_user_profile.research_direction IS '研究方向';
COMMENT ON COLUMN biz_user_profile.research_area      IS '研究领域';
COMMENT ON COLUMN biz_user_profile.id_number          IS '身份证号';
COMMENT ON COLUMN biz_user_profile.entry_date         IS '入职日期';
COMMENT ON COLUMN biz_user_profile.office_phone       IS '办公电话';
COMMENT ON COLUMN biz_user_profile.del_flag           IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN biz_user_profile.create_by          IS '创建者';
COMMENT ON COLUMN biz_user_profile.create_time        IS '创建时间';
COMMENT ON COLUMN biz_user_profile.update_by          IS '更新者';
COMMENT ON COLUMN biz_user_profile.update_time        IS '更新时间';

CREATE UNIQUE INDEX idx_biz_user_profile_user_id ON biz_user_profile (user_id);

-- ============================================================================
-- 模块三：课题管理（3张）
-- ============================================================================

-- ----------------------------
-- 6. 课题表 project
-- ----------------------------
CREATE TABLE project (
    project_id      BIGINT IDENTITY(1,1) NOT NULL,
    project_name    VARCHAR(200)   NOT NULL,
    leader_id       BIGINT         NOT NULL,
    budget_total    DECIMAL(14,2)  DEFAULT 0.00,
    budget_balance  DECIMAL(14,2)  DEFAULT 0.00,
    status          VARCHAR(20)    DEFAULT 'DRAFT',
    start_date      DATE           DEFAULT NULL,
    end_date        DATE           DEFAULT NULL,
    dept_id         BIGINT         DEFAULT NULL,
    del_flag        CHAR(1)        DEFAULT '0',
    create_by       VARCHAR(64)    DEFAULT '',
    create_time     TIMESTAMP      DEFAULT NULL,
    update_by       VARCHAR(64)    DEFAULT '',
    update_time     TIMESTAMP      DEFAULT NULL,
    remark          VARCHAR(500)   DEFAULT NULL,
    PRIMARY KEY (project_id)
);

COMMENT ON TABLE  project IS '课题表';
COMMENT ON COLUMN project.project_id     IS '课题ID';
COMMENT ON COLUMN project.project_name   IS '课题名称';
COMMENT ON COLUMN project.leader_id      IS '课题负责人ID（关联sys_user.user_id）';
COMMENT ON COLUMN project.budget_total   IS '预算总额';
COMMENT ON COLUMN project.budget_balance IS '预算余额（由Service层事务维护）';
COMMENT ON COLUMN project.status         IS '状态（对应字典 project_status：DRAFT/ACTIVE/COMPLETED/ACCEPTED/ARCHIVED）';
COMMENT ON COLUMN project.start_date     IS '开始日期';
COMMENT ON COLUMN project.end_date       IS '结束日期';
COMMENT ON COLUMN project.dept_id        IS '所属部门ID';
COMMENT ON COLUMN project.del_flag       IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN project.create_by      IS '创建者';
COMMENT ON COLUMN project.create_time    IS '创建时间';
COMMENT ON COLUMN project.update_by      IS '更新者';
COMMENT ON COLUMN project.update_time    IS '更新时间';

CREATE INDEX idx_project_leader_id ON project (leader_id);
CREATE INDEX idx_project_status    ON project (status);
CREATE INDEX idx_project_dept_id   ON project (dept_id);

-- ----------------------------
-- 7. 课题成员表 project_member
-- ----------------------------
CREATE TABLE project_member (
    member_id   BIGINT      IDENTITY(1,1) NOT NULL,
    project_id  BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    role        VARCHAR(20) DEFAULT 'PARTICIPANT',
    del_flag    CHAR(1)     DEFAULT '0',
    create_by   VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP   DEFAULT NULL,
    update_by   VARCHAR(64) DEFAULT '',
    update_time TIMESTAMP   DEFAULT NULL,
    remark      VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (member_id)
);

COMMENT ON TABLE  project_member IS '课题成员表';
COMMENT ON COLUMN project_member.member_id   IS '成员ID';
COMMENT ON COLUMN project_member.project_id  IS '课题ID';
COMMENT ON COLUMN project_member.user_id     IS '用户ID';
COMMENT ON COLUMN project_member.role        IS '成员角色（对应字典 member_role：HOST/PARTICIPANT）';
COMMENT ON COLUMN project_member.del_flag    IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN project_member.create_by   IS '创建者';
COMMENT ON COLUMN project_member.create_time IS '创建时间';
COMMENT ON COLUMN project_member.update_by   IS '更新者';
COMMENT ON COLUMN project_member.update_time IS '更新时间';

CREATE INDEX idx_project_member_project_id ON project_member (project_id);
CREATE INDEX idx_project_member_user_id    ON project_member (user_id);
CREATE UNIQUE INDEX idx_project_member_uk  ON project_member (project_id, user_id);

-- ----------------------------
-- 8. 课题资料表 project_document
-- ----------------------------
CREATE TABLE project_document (
    doc_id      BIGINT IDENTITY(1,1) NOT NULL,
    project_id  BIGINT       NOT NULL,
    stage       VARCHAR(20)  DEFAULT NULL,
    file_name   VARCHAR(255) NOT NULL,
    file_url    VARCHAR(500) NOT NULL,
    upload_by   VARCHAR(64)  DEFAULT '',
    upload_time TIMESTAMP    DEFAULT NULL,
    del_flag    CHAR(1)      DEFAULT '0',
    create_by   VARCHAR(64)  DEFAULT '',
    create_time TIMESTAMP    DEFAULT NULL,
    update_by   VARCHAR(64)  DEFAULT '',
    update_time TIMESTAMP    DEFAULT NULL,
    remark      VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (doc_id)
);

COMMENT ON TABLE  project_document IS '课题资料表';
COMMENT ON COLUMN project_document.doc_id      IS '资料ID';
COMMENT ON COLUMN project_document.project_id  IS '课题ID';
COMMENT ON COLUMN project_document.stage       IS '课题阶段（对应字典 project_stage：INITIATION/MIDTERM/CLOSING/REVIEW）';
COMMENT ON COLUMN project_document.file_name   IS '文件名称';
COMMENT ON COLUMN project_document.file_url    IS '文件路径';
COMMENT ON COLUMN project_document.upload_by   IS '上传人';
COMMENT ON COLUMN project_document.upload_time IS '上传时间';
COMMENT ON COLUMN project_document.del_flag    IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN project_document.create_by   IS '创建者';
COMMENT ON COLUMN project_document.create_time IS '创建时间';
COMMENT ON COLUMN project_document.update_by   IS '更新者';
COMMENT ON COLUMN project_document.update_time IS '更新时间';

CREATE INDEX idx_project_document_project_id ON project_document (project_id);
CREATE INDEX idx_project_document_stage      ON project_document (stage);

-- ============================================================================
-- 模块四：合同管理（2张）
-- ============================================================================

-- ----------------------------
-- 9. 合同表 contract
-- ----------------------------
CREATE TABLE contract (
    contract_id   BIGINT IDENTITY(1,1) NOT NULL,
    project_id    BIGINT        NOT NULL,
    contract_name VARCHAR(200)  NOT NULL,
    contract_type VARCHAR(20)   DEFAULT NULL,
    amount        DECIMAL(14,2) DEFAULT 0.00,
    sign_date     DATE          DEFAULT NULL,
    expire_date   DATE          DEFAULT NULL,
    status        VARCHAR(20)   DEFAULT 'ACTIVE',
    del_flag      CHAR(1)       DEFAULT '0',
    create_by     VARCHAR(64)   DEFAULT '',
    create_time   TIMESTAMP     DEFAULT NULL,
    update_by     VARCHAR(64)   DEFAULT '',
    update_time   TIMESTAMP     DEFAULT NULL,
    remark        VARCHAR(500)  DEFAULT NULL,
    PRIMARY KEY (contract_id)
);

COMMENT ON TABLE  contract IS '合同表';
COMMENT ON COLUMN contract.contract_id   IS '合同ID';
COMMENT ON COLUMN contract.project_id    IS '课题ID';
COMMENT ON COLUMN contract.contract_name IS '合同名称';
COMMENT ON COLUMN contract.contract_type IS '合同类型（对应字典 contract_type：RESEARCH/SERVICE/PROCUREMENT）';
COMMENT ON COLUMN contract.amount        IS '合同金额';
COMMENT ON COLUMN contract.sign_date     IS '签订日期';
COMMENT ON COLUMN contract.expire_date   IS '到期日期';
COMMENT ON COLUMN contract.status        IS '状态（ACTIVE有效/EXPIRED过期/TERMINATED终止）';
COMMENT ON COLUMN contract.del_flag      IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN contract.create_by     IS '创建者';
COMMENT ON COLUMN contract.create_time   IS '创建时间';
COMMENT ON COLUMN contract.update_by     IS '更新者';
COMMENT ON COLUMN contract.update_time   IS '更新时间';

CREATE INDEX idx_contract_project_id ON contract (project_id);
CREATE INDEX idx_contract_status     ON contract (status);
CREATE INDEX idx_contract_type       ON contract (contract_type);

-- ----------------------------
-- 10. 合同履约节点表 contract_node
-- ----------------------------
CREATE TABLE contract_node (
    node_id     BIGINT IDENTITY(1,1) NOT NULL,
    contract_id BIGINT       NOT NULL,
    node_name   VARCHAR(200) NOT NULL,
    node_type   VARCHAR(20)  DEFAULT NULL,
    plan_date   DATE         DEFAULT NULL,
    actual_date DATE         DEFAULT NULL,
    status      VARCHAR(20)  DEFAULT 'PENDING',
    del_flag    CHAR(1)      DEFAULT '0',
    create_by   VARCHAR(64)  DEFAULT '',
    create_time TIMESTAMP    DEFAULT NULL,
    update_by   VARCHAR(64)  DEFAULT '',
    update_time TIMESTAMP    DEFAULT NULL,
    remark      VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (node_id)
);

COMMENT ON TABLE  contract_node IS '合同履约节点表';
COMMENT ON COLUMN contract_node.node_id     IS '节点ID';
COMMENT ON COLUMN contract_node.contract_id IS '合同ID';
COMMENT ON COLUMN contract_node.node_name   IS '节点名称';
COMMENT ON COLUMN contract_node.node_type   IS '节点类型（对应字典 node_type：PAYMENT/DELIVERY/ACCEPTANCE）';
COMMENT ON COLUMN contract_node.plan_date   IS '计划日期';
COMMENT ON COLUMN contract_node.actual_date IS '实际日期';
COMMENT ON COLUMN contract_node.status      IS '状态（PENDING待执行/DONE已完成/OVERDUE逾期）';
COMMENT ON COLUMN contract_node.del_flag    IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN contract_node.create_by   IS '创建者';
COMMENT ON COLUMN contract_node.create_time IS '创建时间';
COMMENT ON COLUMN contract_node.update_by   IS '更新者';
COMMENT ON COLUMN contract_node.update_time IS '更新时间';

CREATE INDEX idx_contract_node_contract_id ON contract_node (contract_id);
CREATE INDEX idx_contract_node_node_type   ON contract_node (node_type);

-- ============================================================================
-- 模块五：经费管理（2张）
-- ============================================================================

-- ----------------------------
-- 11. 经费预算分劈表 budget_split
-- ----------------------------
CREATE TABLE budget_split (
    split_id      BIGINT IDENTITY(1,1) NOT NULL,
    project_id    BIGINT        NOT NULL,
    category      VARCHAR(20)   NOT NULL,
    budget_amount DECIMAL(14,2) DEFAULT 0.00,
    del_flag      CHAR(1)       DEFAULT '0',
    create_by     VARCHAR(64)   DEFAULT '',
    create_time   TIMESTAMP     DEFAULT NULL,
    update_by     VARCHAR(64)   DEFAULT '',
    update_time   TIMESTAMP     DEFAULT NULL,
    remark        VARCHAR(500)  DEFAULT NULL,
    PRIMARY KEY (split_id)
);

COMMENT ON TABLE  budget_split IS '经费预算分劈表';
COMMENT ON COLUMN budget_split.split_id      IS '分劈ID';
COMMENT ON COLUMN budget_split.project_id    IS '课题ID';
COMMENT ON COLUMN budget_split.category      IS '经费类别（对应字典 expense_category）';
COMMENT ON COLUMN budget_split.budget_amount IS '预算金额';
COMMENT ON COLUMN budget_split.del_flag      IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN budget_split.create_by     IS '创建者';
COMMENT ON COLUMN budget_split.create_time   IS '创建时间';
COMMENT ON COLUMN budget_split.update_by     IS '更新者';
COMMENT ON COLUMN budget_split.update_time   IS '更新时间';

CREATE INDEX idx_budget_split_project_id ON budget_split (project_id);
CREATE INDEX idx_budget_split_category   ON budget_split (category);

-- ----------------------------
-- 12. 经费记账表 expense
-- ----------------------------
CREATE TABLE expense (
    expense_id   BIGINT IDENTITY(1,1) NOT NULL,
    project_id   BIGINT        NOT NULL,
    amount       DECIMAL(14,2) NOT NULL,
    tax_rate     DECIMAL(5,4)  DEFAULT 0.0000,
    expense_date DATE          DEFAULT NULL,
    category     VARCHAR(20)   DEFAULT NULL,
    description  VARCHAR(500)  DEFAULT NULL,
    del_flag     CHAR(1)       DEFAULT '0',
    create_by    VARCHAR(64)   DEFAULT '',
    create_time  TIMESTAMP     DEFAULT NULL,
    update_by    VARCHAR(64)   DEFAULT '',
    update_time  TIMESTAMP     DEFAULT NULL,
    remark       VARCHAR(500)  DEFAULT NULL,
    PRIMARY KEY (expense_id)
);

COMMENT ON TABLE  expense IS '经费记账表';
COMMENT ON COLUMN expense.expense_id   IS '记账ID';
COMMENT ON COLUMN expense.project_id   IS '课题ID';
COMMENT ON COLUMN expense.amount       IS '金额（含税）';
COMMENT ON COLUMN expense.tax_rate     IS '税率';
COMMENT ON COLUMN expense.expense_date IS '费用发生日期';
COMMENT ON COLUMN expense.category     IS '经费类别（对应字典 expense_category）';
COMMENT ON COLUMN expense.description  IS '费用说明';
COMMENT ON COLUMN expense.del_flag     IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN expense.create_by    IS '创建人';
COMMENT ON COLUMN expense.create_time  IS '创建时间';
COMMENT ON COLUMN expense.update_by    IS '更新者';
COMMENT ON COLUMN expense.update_time  IS '更新时间';

CREATE INDEX idx_expense_project_id   ON expense (project_id);
CREATE INDEX idx_expense_category     ON expense (category);
CREATE INDEX idx_expense_expense_date ON expense (expense_date);

-- ============================================================================
-- 模块六：审批管理（2张）
-- ============================================================================

-- ----------------------------
-- 13. 审批表 approval
-- ----------------------------
CREATE TABLE approval (
    approval_id  BIGINT IDENTITY(1,1) NOT NULL,
    doc_id       BIGINT       DEFAULT NULL,
    applicant_id BIGINT       NOT NULL,
    approver_id  BIGINT       DEFAULT NULL,
    status       VARCHAR(20)  DEFAULT 'PENDING',
    comment_text VARCHAR(500) DEFAULT NULL,
    del_flag     CHAR(1)      DEFAULT '0',
    create_by    VARCHAR(64)  DEFAULT '',
    create_time  TIMESTAMP    DEFAULT NULL,
    update_by    VARCHAR(64)  DEFAULT '',
    update_time  TIMESTAMP    DEFAULT NULL,
    finish_time  TIMESTAMP    DEFAULT NULL,
    remark       VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (approval_id)
);

COMMENT ON TABLE  approval IS '审批表';
COMMENT ON COLUMN approval.approval_id  IS '审批ID';
COMMENT ON COLUMN approval.doc_id       IS '关联资料ID';
COMMENT ON COLUMN approval.applicant_id IS '申请人ID';
COMMENT ON COLUMN approval.approver_id  IS '审批人ID';
COMMENT ON COLUMN approval.status       IS '审批状态（对应字典 approval_status：PENDING/APPROVED/REJECTED）';
COMMENT ON COLUMN approval.comment_text IS '审批意见（comment为达梦保留字，故用comment_text）';
COMMENT ON COLUMN approval.del_flag     IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN approval.create_by    IS '创建者';
COMMENT ON COLUMN approval.create_time  IS '创建时间';
COMMENT ON COLUMN approval.update_by    IS '更新者';
COMMENT ON COLUMN approval.update_time  IS '更新时间';
COMMENT ON COLUMN approval.finish_time  IS '完成时间';

CREATE INDEX idx_approval_doc_id       ON approval (doc_id);
CREATE INDEX idx_approval_applicant_id ON approval (applicant_id);
CREATE INDEX idx_approval_approver_id  ON approval (approver_id);
CREATE INDEX idx_approval_status       ON approval (status);

-- ----------------------------
-- 14. 审批历史表 approval_history
-- ----------------------------
CREATE TABLE approval_history (
    history_id   BIGINT IDENTITY(1,1) NOT NULL,
    approval_id  BIGINT       NOT NULL,
    action       VARCHAR(20)  NOT NULL,
    operator_id  BIGINT       NOT NULL,
    comment_text VARCHAR(500) DEFAULT NULL,
    operate_time TIMESTAMP    DEFAULT NULL,
    del_flag     CHAR(1)      DEFAULT '0',
    create_by    VARCHAR(64)  DEFAULT '',
    create_time  TIMESTAMP    DEFAULT NULL,
    update_by    VARCHAR(64)  DEFAULT '',
    update_time  TIMESTAMP    DEFAULT NULL,
    remark       VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (history_id)
);

COMMENT ON TABLE  approval_history IS '审批历史表';
COMMENT ON COLUMN approval_history.history_id   IS '历史ID';
COMMENT ON COLUMN approval_history.approval_id  IS '审批ID';
COMMENT ON COLUMN approval_history.action       IS '操作类型（SUBMIT提交/APPROVE通过/REJECT驳回/TRANSFER转交）';
COMMENT ON COLUMN approval_history.operator_id  IS '操作人ID';
COMMENT ON COLUMN approval_history.comment_text IS '审批意见（comment为达梦保留字，故用comment_text）';
COMMENT ON COLUMN approval_history.operate_time IS '操作时间';
COMMENT ON COLUMN approval_history.del_flag     IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN approval_history.create_by    IS '创建者';
COMMENT ON COLUMN approval_history.create_time  IS '创建时间';
COMMENT ON COLUMN approval_history.update_by    IS '更新者';
COMMENT ON COLUMN approval_history.update_time  IS '更新时间';

CREATE INDEX idx_approval_history_approval_id ON approval_history (approval_id);

-- ============================================================================
-- 模块七：合作单位管理（2张）
-- ============================================================================

-- ----------------------------
-- 15. 合作单位表 cooperative_unit
-- ----------------------------
CREATE TABLE cooperative_unit (
    unit_id           BIGINT IDENTITY(1,1) NOT NULL,
    unit_name         VARCHAR(200) NOT NULL,
    unit_type         VARCHAR(20)  DEFAULT 'EXTERNAL',
    external_unit_type VARCHAR(20) DEFAULT NULL,
    credit_code       VARCHAR(50)  DEFAULT NULL,
    contact_person    VARCHAR(50)  DEFAULT NULL,
    contact_phone     VARCHAR(20)  DEFAULT NULL,
    address           VARCHAR(500) DEFAULT NULL,
    del_flag          CHAR(1)      DEFAULT '0',
    create_by         VARCHAR(64)  DEFAULT '',
    create_time       TIMESTAMP    DEFAULT NULL,
    update_by         VARCHAR(64)  DEFAULT '',
    update_time       TIMESTAMP    DEFAULT NULL,
    remark            VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (unit_id)
);

COMMENT ON TABLE  cooperative_unit IS '合作单位表';
COMMENT ON COLUMN cooperative_unit.unit_id            IS '单位ID';
COMMENT ON COLUMN cooperative_unit.unit_name          IS '单位名称';
COMMENT ON COLUMN cooperative_unit.unit_type          IS '单位类型（对应字典 unit_type：INTERNAL/EXTERNAL）';
COMMENT ON COLUMN cooperative_unit.external_unit_type IS '外部单位类型（对应字典 external_unit_type：COMPANY/SCHOOL/OTHER）';
COMMENT ON COLUMN cooperative_unit.credit_code        IS '统一社会信用代码';
COMMENT ON COLUMN cooperative_unit.contact_person     IS '联系人';
COMMENT ON COLUMN cooperative_unit.contact_phone      IS '联系电话';
COMMENT ON COLUMN cooperative_unit.address            IS '单位地址';
COMMENT ON COLUMN cooperative_unit.del_flag           IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN cooperative_unit.create_by          IS '创建者';
COMMENT ON COLUMN cooperative_unit.create_time        IS '创建时间';
COMMENT ON COLUMN cooperative_unit.update_by          IS '更新者';
COMMENT ON COLUMN cooperative_unit.update_time        IS '更新时间';

CREATE INDEX idx_cooperative_unit_credit_code ON cooperative_unit (credit_code);
CREATE INDEX idx_cooperative_unit_unit_type   ON cooperative_unit (unit_type);

-- ----------------------------
-- 16. 课题合作单位关联表 project_unit
-- ----------------------------
CREATE TABLE project_unit (
    id               BIGINT IDENTITY(1,1) NOT NULL,
    project_id       BIGINT      NOT NULL,
    unit_id          BIGINT      NOT NULL,
    cooperation_type VARCHAR(50) DEFAULT NULL,
    del_flag         CHAR(1)     DEFAULT '0',
    create_by        VARCHAR(64) DEFAULT '',
    create_time      TIMESTAMP   DEFAULT NULL,
    update_by        VARCHAR(64) DEFAULT '',
    update_time      TIMESTAMP   DEFAULT NULL,
    remark           VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (id)
);

COMMENT ON TABLE  project_unit IS '课题合作单位关联表';
COMMENT ON COLUMN project_unit.id               IS '关联ID';
COMMENT ON COLUMN project_unit.project_id       IS '课题ID';
COMMENT ON COLUMN project_unit.unit_id          IS '合作单位ID';
COMMENT ON COLUMN project_unit.cooperation_type IS '合作类型（对应字典 cooperation_type：LEAD/PARTICIPANT/COLLABORATE）';
COMMENT ON COLUMN project_unit.del_flag         IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN project_unit.create_by        IS '创建者';
COMMENT ON COLUMN project_unit.create_time      IS '创建时间';
COMMENT ON COLUMN project_unit.update_by        IS '更新者';
COMMENT ON COLUMN project_unit.update_time      IS '更新时间';

CREATE INDEX idx_project_unit_project_id ON project_unit (project_id);
CREATE INDEX idx_project_unit_unit_id    ON project_unit (unit_id);

-- ============================================================================
-- 模块八：荣誉管理（2张）
-- ============================================================================

-- ----------------------------
-- 17. 荣誉表 honor
-- ----------------------------
CREATE TABLE honor (
    honor_id   BIGINT IDENTITY(1,1) NOT NULL,
    honor_name VARCHAR(200) NOT NULL,
    honor_type VARCHAR(20)  DEFAULT NULL,
    award_date DATE         DEFAULT NULL,
    award_level VARCHAR(50) DEFAULT NULL,
    award_org  VARCHAR(200) DEFAULT NULL,
    description VARCHAR(500) DEFAULT NULL,
    del_flag    CHAR(1)      DEFAULT '0',
    create_by   VARCHAR(64)  DEFAULT '',
    create_time TIMESTAMP    DEFAULT NULL,
    update_by   VARCHAR(64)  DEFAULT '',
    update_time TIMESTAMP    DEFAULT NULL,
    remark      VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (honor_id)
);

COMMENT ON TABLE  honor IS '荣誉表';
COMMENT ON COLUMN honor.honor_id    IS '荣誉ID';
COMMENT ON COLUMN honor.honor_name  IS '荣誉名称';
COMMENT ON COLUMN honor.honor_type  IS '荣誉类型（对应字典 honor_type：COLLECTIVE/INDIVIDUAL）';
COMMENT ON COLUMN honor.award_date  IS '获奖日期';
COMMENT ON COLUMN honor.award_level IS '获奖级别（对应字典 honor_level：NATIONAL/PROVINCIAL/GROUP/COMPANY/INSTITUTE）';
COMMENT ON COLUMN honor.award_org   IS '颁奖机构';
COMMENT ON COLUMN honor.description IS '荣誉描述';
COMMENT ON COLUMN honor.del_flag    IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN honor.create_by   IS '创建者';
COMMENT ON COLUMN honor.create_time IS '创建时间';
COMMENT ON COLUMN honor.update_by   IS '更新者';
COMMENT ON COLUMN honor.update_time IS '更新时间';

CREATE INDEX idx_honor_award_date  ON honor (award_date);
CREATE INDEX idx_honor_award_level ON honor (award_level);
CREATE INDEX idx_honor_honor_type  ON honor (honor_type);

-- ----------------------------
-- 18. 荣誉关联表 honor_relation
-- ----------------------------
CREATE TABLE honor_relation (
    relation_id BIGINT IDENTITY(1,1) NOT NULL,
    honor_id    BIGINT      NOT NULL,
    ref_type    VARCHAR(20) NOT NULL,
    ref_id      BIGINT      NOT NULL,
    del_flag    CHAR(1)     DEFAULT '0',
    create_by   VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP   DEFAULT NULL,
    update_by   VARCHAR(64) DEFAULT '',
    update_time TIMESTAMP   DEFAULT NULL,
    remark      VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (relation_id)
);

COMMENT ON TABLE  honor_relation IS '荣誉关联表';
COMMENT ON COLUMN honor_relation.relation_id IS '关联ID';
COMMENT ON COLUMN honor_relation.honor_id    IS '荣誉ID';
COMMENT ON COLUMN honor_relation.ref_type    IS '关联类型（PROJECT课题/RESEARCHER科研人员）';
COMMENT ON COLUMN honor_relation.ref_id      IS '关联对象ID';
COMMENT ON COLUMN honor_relation.del_flag    IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN honor_relation.create_by   IS '创建者';
COMMENT ON COLUMN honor_relation.create_time IS '创建时间';
COMMENT ON COLUMN honor_relation.update_by   IS '更新者';
COMMENT ON COLUMN honor_relation.update_time IS '更新时间';

CREATE INDEX idx_honor_relation_honor_id ON honor_relation (honor_id);
CREATE INDEX idx_honor_relation_ref      ON honor_relation (ref_type, ref_id);

-- ============================================================================
-- 模块九：预警与通知（2张）
-- ============================================================================

-- ----------------------------
-- 19. 预警表 alert
-- ----------------------------
CREATE TABLE alert (
    alert_id    BIGINT IDENTITY(1,1) NOT NULL,
    alert_type  VARCHAR(20)   NOT NULL,
    ref_id      BIGINT        DEFAULT NULL,
    alert_level VARCHAR(20)   DEFAULT 'INFO',
    title       VARCHAR(200)  NOT NULL,
    content     VARCHAR(2000) DEFAULT NULL,
    status      VARCHAR(20)   DEFAULT 'UNREAD',
    del_flag    CHAR(1)       DEFAULT '0',
    create_by   VARCHAR(64)   DEFAULT '',
    create_time TIMESTAMP     DEFAULT NULL,
    update_by   VARCHAR(64)   DEFAULT '',
    update_time TIMESTAMP     DEFAULT NULL,
    remark      VARCHAR(500)  DEFAULT NULL,
    PRIMARY KEY (alert_id)
);

COMMENT ON TABLE  alert IS '预警表';
COMMENT ON COLUMN alert.alert_id    IS '预警ID';
COMMENT ON COLUMN alert.alert_type  IS '预警类型（对应字典 alert_type：CONTRACT/BUDGET/DOCUMENT）';
COMMENT ON COLUMN alert.ref_id      IS '关联对象ID';
COMMENT ON COLUMN alert.alert_level IS '预警级别（对应字典 alert_level：INFO/WARN/CRITICAL）';
COMMENT ON COLUMN alert.title       IS '预警标题';
COMMENT ON COLUMN alert.content     IS '预警内容';
COMMENT ON COLUMN alert.status      IS '状态（UNREAD未读/READ已读/HANDLED已处理）';
COMMENT ON COLUMN alert.del_flag    IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN alert.create_by   IS '创建者';
COMMENT ON COLUMN alert.create_time IS '创建时间';
COMMENT ON COLUMN alert.update_by   IS '更新者';
COMMENT ON COLUMN alert.update_time IS '更新时间';

CREATE INDEX idx_alert_alert_type ON alert (alert_type);
CREATE INDEX idx_alert_status     ON alert (status);
CREATE INDEX idx_alert_ref_id     ON alert (ref_id);

-- ----------------------------
-- 20. 通知表 notification
-- ----------------------------
CREATE TABLE notification (
    notify_id   BIGINT IDENTITY(1,1) NOT NULL,
    alert_id    BIGINT      DEFAULT NULL,
    receiver_id BIGINT      NOT NULL,
    is_read     INT         DEFAULT 0,
    read_time   TIMESTAMP   DEFAULT NULL,
    del_flag    CHAR(1)     DEFAULT '0',
    create_by   VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP   DEFAULT NULL,
    update_by   VARCHAR(64) DEFAULT '',
    update_time TIMESTAMP   DEFAULT NULL,
    remark      VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (notify_id)
);

COMMENT ON TABLE  notification IS '通知表';
COMMENT ON COLUMN notification.notify_id   IS '通知ID';
COMMENT ON COLUMN notification.alert_id    IS '预警ID';
COMMENT ON COLUMN notification.receiver_id IS '接收人ID';
COMMENT ON COLUMN notification.is_read     IS '是否已读（0未读 1已读）';
COMMENT ON COLUMN notification.read_time   IS '阅读时间';
COMMENT ON COLUMN notification.del_flag    IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN notification.create_by   IS '创建者';
COMMENT ON COLUMN notification.create_time IS '创建时间';
COMMENT ON COLUMN notification.update_by   IS '更新者';
COMMENT ON COLUMN notification.update_time IS '更新时间';

CREATE INDEX idx_notification_alert_id    ON notification (alert_id);
CREATE INDEX idx_notification_receiver_id ON notification (receiver_id);
CREATE INDEX idx_notification_is_read     ON notification (is_read);

-- ============================================================================
-- 模块十：研发人工费管理（5张）
-- ============================================================================

-- ----------------------------
-- 21. 课题研发人工费预算表 rd_labor_budget
-- ----------------------------
CREATE TABLE rd_labor_budget (
    budget_id    BIGINT IDENTITY(1,1) NOT NULL,
    project_id   BIGINT        NOT NULL,
    budget_year  INT           NOT NULL,
    month        INT           NOT NULL,
    total_amount DECIMAL(14,2) DEFAULT 0.00,
    status       VARCHAR(20)   DEFAULT 'DRAFT',
    del_flag     CHAR(1)       DEFAULT '0',
    create_by    VARCHAR(64)   DEFAULT '',
    create_time  TIMESTAMP     DEFAULT NULL,
    update_by    VARCHAR(64)   DEFAULT '',
    update_time  TIMESTAMP     DEFAULT NULL,
    remark       VARCHAR(500)  DEFAULT NULL,
    PRIMARY KEY (budget_id)
);

COMMENT ON TABLE  rd_labor_budget IS '课题研发人工费预算表';
COMMENT ON COLUMN rd_labor_budget.budget_id    IS '预算ID';
COMMENT ON COLUMN rd_labor_budget.project_id   IS '课题ID';
COMMENT ON COLUMN rd_labor_budget.budget_year  IS '预算年度';
COMMENT ON COLUMN rd_labor_budget.month        IS '预算月份（1-12）';
COMMENT ON COLUMN rd_labor_budget.total_amount IS '预算总额';
COMMENT ON COLUMN rd_labor_budget.status       IS '状态（DRAFT草稿/CONFIRMED已确认）';
COMMENT ON COLUMN rd_labor_budget.del_flag     IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN rd_labor_budget.create_by    IS '创建者';
COMMENT ON COLUMN rd_labor_budget.create_time  IS '创建时间';
COMMENT ON COLUMN rd_labor_budget.update_by    IS '更新者';
COMMENT ON COLUMN rd_labor_budget.update_time  IS '更新时间';

CREATE INDEX idx_rd_labor_budget_project_id ON rd_labor_budget (project_id);
CREATE INDEX idx_rd_labor_budget_year_month ON rd_labor_budget (budget_year, month);

-- ----------------------------
-- 22. 研发人员工资标准表 rd_researcher_salary
-- ----------------------------
CREATE TABLE rd_researcher_salary (
    salary_id      BIGINT IDENTITY(1,1) NOT NULL,
    researcher_id  BIGINT        NOT NULL,
    salary_month   VARCHAR(7)    NOT NULL,
    monthly_salary DECIMAL(12,2) NOT NULL,
    del_flag       CHAR(1)       DEFAULT '0',
    create_by      VARCHAR(64)   DEFAULT '',
    create_time    TIMESTAMP     DEFAULT NULL,
    update_by      VARCHAR(64)   DEFAULT '',
    update_time    TIMESTAMP     DEFAULT NULL,
    remark         VARCHAR(500)  DEFAULT NULL,
    PRIMARY KEY (salary_id)
);

COMMENT ON TABLE  rd_researcher_salary IS '研发人员工资标准表';
COMMENT ON COLUMN rd_researcher_salary.salary_id      IS '工资标准ID';
COMMENT ON COLUMN rd_researcher_salary.researcher_id  IS '研发人员ID（关联sys_user.user_id）';
COMMENT ON COLUMN rd_researcher_salary.salary_month   IS '工资月份（格式：YYYY-MM）';
COMMENT ON COLUMN rd_researcher_salary.monthly_salary IS '月度工资';
COMMENT ON COLUMN rd_researcher_salary.del_flag       IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN rd_researcher_salary.create_by      IS '创建者';
COMMENT ON COLUMN rd_researcher_salary.create_time    IS '创建时间';
COMMENT ON COLUMN rd_researcher_salary.update_by      IS '更新者';
COMMENT ON COLUMN rd_researcher_salary.update_time    IS '更新时间';

CREATE INDEX idx_rd_researcher_salary_researcher_id ON rd_researcher_salary (researcher_id);
CREATE INDEX idx_rd_researcher_salary_month         ON rd_researcher_salary (salary_month);
CREATE UNIQUE INDEX idx_rd_researcher_salary_uk     ON rd_researcher_salary (researcher_id, salary_month);

-- ----------------------------
-- 23. 每日研发工时表 rd_worktime_daily
-- ----------------------------
CREATE TABLE rd_worktime_daily (
    id            BIGINT IDENTITY(1,1) NOT NULL,
    project_id    BIGINT       NOT NULL,
    researcher_id BIGINT       NOT NULL,
    work_date     DATE         NOT NULL,
    rd_hours      DECIMAL(5,2) DEFAULT 0.00,
    del_flag      CHAR(1)      DEFAULT '0',
    create_by     VARCHAR(64)  DEFAULT '',
    create_time   TIMESTAMP    DEFAULT NULL,
    update_by     VARCHAR(64)  DEFAULT '',
    update_time   TIMESTAMP    DEFAULT NULL,
    remark        VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (id)
);

COMMENT ON TABLE  rd_worktime_daily IS '每日研发工时表';
COMMENT ON COLUMN rd_worktime_daily.id            IS '每日工时ID';
COMMENT ON COLUMN rd_worktime_daily.project_id    IS '课题ID';
COMMENT ON COLUMN rd_worktime_daily.researcher_id IS '研发人员ID';
COMMENT ON COLUMN rd_worktime_daily.work_date     IS '工作日期';
COMMENT ON COLUMN rd_worktime_daily.rd_hours      IS '当日研发工时（小时）';
COMMENT ON COLUMN rd_worktime_daily.del_flag      IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN rd_worktime_daily.create_by     IS '创建者';
COMMENT ON COLUMN rd_worktime_daily.create_time   IS '创建时间';
COMMENT ON COLUMN rd_worktime_daily.update_by     IS '更新者';
COMMENT ON COLUMN rd_worktime_daily.update_time   IS '更新时间';

CREATE INDEX idx_rd_worktime_daily_project_id    ON rd_worktime_daily (project_id);
CREATE INDEX idx_rd_worktime_daily_researcher_id ON rd_worktime_daily (researcher_id);
CREATE INDEX idx_rd_worktime_daily_work_date     ON rd_worktime_daily (work_date);
CREATE UNIQUE INDEX idx_rd_worktime_daily_uk     ON rd_worktime_daily (project_id, researcher_id, work_date);

-- ----------------------------
-- 24. 月度研发工时汇总表 rd_worktime_monthly
-- ----------------------------
CREATE TABLE rd_worktime_monthly (
    id                BIGINT IDENTITY(1,1) NOT NULL,
    project_id        BIGINT        NOT NULL,
    researcher_id     BIGINT        NOT NULL,
    month             VARCHAR(7)    NOT NULL,
    total_rd_hours    DECIMAL(8,2)  DEFAULT 0.00,
    cumulative_hours  DECIMAL(10,2) DEFAULT 0.00,
    del_flag          CHAR(1)       DEFAULT '0',
    create_by         VARCHAR(64)   DEFAULT '',
    create_time       TIMESTAMP     DEFAULT NULL,
    update_by         VARCHAR(64)   DEFAULT '',
    update_time       TIMESTAMP     DEFAULT NULL,
    remark            VARCHAR(500)  DEFAULT NULL,
    PRIMARY KEY (id)
);

COMMENT ON TABLE  rd_worktime_monthly IS '月度研发工时汇总表';
COMMENT ON COLUMN rd_worktime_monthly.id               IS '月度汇总ID';
COMMENT ON COLUMN rd_worktime_monthly.project_id       IS '课题ID';
COMMENT ON COLUMN rd_worktime_monthly.researcher_id    IS '研发人员ID';
COMMENT ON COLUMN rd_worktime_monthly.month            IS '月份（格式：YYYY-MM）';
COMMENT ON COLUMN rd_worktime_monthly.total_rd_hours   IS '当月研发工时合计（小时）';
COMMENT ON COLUMN rd_worktime_monthly.cumulative_hours IS '累计研发工时（小时）';
COMMENT ON COLUMN rd_worktime_monthly.del_flag         IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN rd_worktime_monthly.create_by        IS '创建者';
COMMENT ON COLUMN rd_worktime_monthly.create_time      IS '创建时间';
COMMENT ON COLUMN rd_worktime_monthly.update_by        IS '更新者';
COMMENT ON COLUMN rd_worktime_monthly.update_time      IS '更新时间';

CREATE INDEX idx_rd_worktime_monthly_project_id    ON rd_worktime_monthly (project_id);
CREATE INDEX idx_rd_worktime_monthly_researcher_id ON rd_worktime_monthly (researcher_id);
CREATE INDEX idx_rd_worktime_monthly_month         ON rd_worktime_monthly (month);
CREATE UNIQUE INDEX idx_rd_worktime_monthly_uk     ON rd_worktime_monthly (project_id, researcher_id, month);

-- ----------------------------
-- 25. 人工费分摊结果表 rd_labor_allocation
-- ----------------------------
CREATE TABLE rd_labor_allocation (
    alloc_id         BIGINT IDENTITY(1,1) NOT NULL,
    project_id       BIGINT        NOT NULL,
    researcher_id    BIGINT        NOT NULL,
    month            VARCHAR(7)    NOT NULL,
    allocated_amount DECIMAL(14,2) DEFAULT 0.00,
    surcharge_total  DECIMAL(14,2) DEFAULT 0.00,
    grand_total      DECIMAL(14,2) DEFAULT 0.00,
    status           VARCHAR(20)   DEFAULT 'DRAFT',
    batch_no         VARCHAR(50)   DEFAULT NULL,
    del_flag         CHAR(1)       DEFAULT '0',
    create_by        VARCHAR(64)   DEFAULT '',
    create_time      TIMESTAMP     DEFAULT NULL,
    update_by        VARCHAR(64)   DEFAULT '',
    update_time      TIMESTAMP     DEFAULT NULL,
    remark           VARCHAR(500)  DEFAULT NULL,
    PRIMARY KEY (alloc_id)
);

COMMENT ON TABLE  rd_labor_allocation IS '人工费分摊结果表';
COMMENT ON COLUMN rd_labor_allocation.alloc_id         IS '分摊ID';
COMMENT ON COLUMN rd_labor_allocation.project_id       IS '课题ID';
COMMENT ON COLUMN rd_labor_allocation.researcher_id    IS '研发人员ID';
COMMENT ON COLUMN rd_labor_allocation.month            IS '月份（格式：YYYY-MM）';
COMMENT ON COLUMN rd_labor_allocation.allocated_amount IS '分摊人工费';
COMMENT ON COLUMN rd_labor_allocation.surcharge_total  IS '工资附加费合计';
COMMENT ON COLUMN rd_labor_allocation.grand_total      IS '总计（人工费+附加费）';
COMMENT ON COLUMN rd_labor_allocation.status           IS '状态（对应字典 rd_alloc_status：DRAFT/CONFIRMED）';
COMMENT ON COLUMN rd_labor_allocation.batch_no         IS '批次号';
COMMENT ON COLUMN rd_labor_allocation.del_flag         IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN rd_labor_allocation.create_by        IS '创建者';
COMMENT ON COLUMN rd_labor_allocation.create_time      IS '创建时间';
COMMENT ON COLUMN rd_labor_allocation.update_by        IS '更新者';
COMMENT ON COLUMN rd_labor_allocation.update_time      IS '更新时间';

CREATE INDEX idx_rd_labor_allocation_project_id    ON rd_labor_allocation (project_id);
CREATE INDEX idx_rd_labor_allocation_researcher_id ON rd_labor_allocation (researcher_id);
CREATE INDEX idx_rd_labor_allocation_month         ON rd_labor_allocation (month);
CREATE INDEX idx_rd_labor_allocation_batch_no      ON rd_labor_allocation (batch_no);

-- ============================================================================
-- 附加：工资附加费比例配置表
-- ============================================================================

-- ----------------------------
-- 26. 工资附加费比例配置表 surcharge_rate
-- ----------------------------
CREATE TABLE surcharge_rate (
    rate_id     BIGINT IDENTITY(1,1) NOT NULL,
    rate_name   VARCHAR(100) NOT NULL,
    rate_code   VARCHAR(50)  NOT NULL,
    rate_value  DECIMAL(8,4) NOT NULL,
    status      VARCHAR(20)  DEFAULT 'ACTIVE',
    del_flag    CHAR(1)      DEFAULT '0',
    create_by   VARCHAR(64)  DEFAULT '',
    create_time TIMESTAMP    DEFAULT NULL,
    update_by   VARCHAR(64)  DEFAULT '',
    update_time TIMESTAMP    DEFAULT NULL,
    remark      VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (rate_id)
);

COMMENT ON TABLE  surcharge_rate IS '工资附加费比例配置表';
COMMENT ON COLUMN surcharge_rate.rate_id     IS '比例ID';
COMMENT ON COLUMN surcharge_rate.rate_name   IS '附加费名称';
COMMENT ON COLUMN surcharge_rate.rate_code   IS '附加费编码';
COMMENT ON COLUMN surcharge_rate.rate_value  IS '比例值（如0.015表示1.5%）';
COMMENT ON COLUMN surcharge_rate.status      IS '状态（ACTIVE有效/INACTIVE无效）';
COMMENT ON COLUMN surcharge_rate.del_flag    IS '删除标志（0存在 2删除）';
COMMENT ON COLUMN surcharge_rate.create_by   IS '创建者';
COMMENT ON COLUMN surcharge_rate.create_time IS '创建时间';
COMMENT ON COLUMN surcharge_rate.update_by   IS '更新者';
COMMENT ON COLUMN surcharge_rate.update_time IS '更新时间';

CREATE UNIQUE INDEX idx_surcharge_rate_code ON surcharge_rate (rate_code);

-- ============================================================================
-- 触发器（已移除）
-- 原 trg_expense_after_insert（经费插入自动扣减 project.budget_balance）
-- 依阶段0任务卡决策：经费核减由 Service 层事务（@Transactional）维护，避免双重扣减
-- ============================================================================

-- ============================================================================
-- 视图
-- ============================================================================

-- ----------------------------
-- 视图：v_biz_user_profile
-- 功能：科研人员扩展信息联合查询（用户表 + 部门表 + 角色表 + 科研人员扩展表）
-- ----------------------------
CREATE OR REPLACE VIEW v_biz_user_profile AS
SELECT
    u.user_id,
    u.user_name,
    u.nick_name,
    u.email,
    u.phonenumber,
    u.status       AS user_status,
    d.dept_id,
    d.dept_name,
    r.role_id,
    r.role_name,
    r.role_key,
    rp.profile_id,
    rp.edu_level,
    rp.title_level,
    rp.research_direction,
    rp.research_area,
    rp.id_number,
    rp.entry_date,
    rp.office_phone
FROM sys_user u
LEFT JOIN sys_dept d          ON u.dept_id = d.dept_id
LEFT JOIN sys_user_role ur    ON u.user_id = ur.user_id
LEFT JOIN sys_role r          ON ur.role_id = r.role_id
LEFT JOIN biz_user_profile rp ON u.user_id = rp.user_id
WHERE u.del_flag = '0';

-- 注：达梦不支持对视图执行 COMMENT ON TABLE，视图用途见上方注释

-- ============================================================================
-- 完
-- ============================================================================
