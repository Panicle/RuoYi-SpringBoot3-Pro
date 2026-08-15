# 科研管理平台 SQL 脚本变更日志

> 数据库：达梦 DM8（Oracle 方言兼容）
> 脚本目录：`sql/kys/`

---

## 执行记录（各环境实际执行登记，每次执行后必须更新）

| 序号 | 脚本 | 执行环境 | 执行人 | 执行时间 | 结果 |
|---|---|---|---|---|---|
| 1 | V1.0.0__base_tables.sql | devdm (F:\dmdbms\data\RUOYI) | Claude Code (dmPython) | 2026-08-11 | ✅ 22张业务表 + v_biz_user_profile 视图创建成功 |
| 2 | V1.0.1__dict_data.sql | devdm | Claude Code | 2026-08-11 | ✅ 18字典类型 + 67字典数据 + 10项附加费比例 |
| 3 | V1.0.2__menu_permissions.sql | devdm | Claude Code | 2026-08-11 | ✅ 8个菜单（2000-2007），挂载 admin |
| 4 | V1.0.3__views.sql | devdm | Claude Code | 2026-08-11 | ✅ 视图重建（幂等） |
| 5 | V1.0.4__roles.sql | devdm | Claude Code | 2026-08-11 | ✅ 6业务角色（100-105）+ 29条角色菜单挂载 |
| 6 | V1.0.5__profile_into_user.sql | devdm (F:\dmdbms\data\RUOYI) | Claude Code (dmPython) | 2026-08-11 | ✅ 53/53语句成功（首次+幂等复查各一次）：表结构3列变更+3字典（23项）+ 菜单迁移+ 视图重建 |
| 7 | V1.0.6__project_module.sql | devdm (F:\dmdbms\data\RUOYI) | Claude Code (dmPython) | 2026-08-12 | ✅ 64/64语句成功（首次+幂等复查各一次）：表结构2列+唯一索引+字典1类型6项+菜单10项+角色菜单挂载39条；DB复查（project 17列/唯一索引/dict_id=221/菜单2010-2019/7角色挂载）全部 PASS |
| 8 | V1.0.7__budget_breakdown.sql | devdm (F:\dmdbms\data\RUOYI) | Claude Code (dmPython) | 2026-08-13 | ✅ 12/12语句成功（首次+幂等复查各一次）：字典 budget_category（dict_id=222 + dict_data 20097-20106 共10项）+ budget_split.category 注释更新；DB复查（dict_type=222/dict_data 10项顺序与值/注释）全部 PASS |
| 9 | V1.0.8__project_category_specialty.sql | devdm (F:\dmdbms\data\RUOYI) | Claude Code (dmPython) | 2026-08-13 | ✅ 19/19语句成功（首次+幂等复查各一次）：字典 project_category（dict_id=223 + dict_data 20107-20109 共3项）+ specialty（dict_id=224 + dict_data 20110-20118 共9项）+ project 加列 project_category/specialty（VARCHAR(20) 可空）+ member_role HOST 标签 主持人→组长；DB复查（两字典/两列/注释/HOST label）全部 PASS |
| 10 | V1.0.9__cooperative_unit.sql | devdm (F:\dmdbms\data\RUOYI) | Claude Code (dmPython) | 2026-08-13 | ✅ 62/62语句成功（首次+幂等复查各一次）：cooperative_unit 加 6 树形列（parent_id/ancestors/company_type/company_category/expertise/order_num，14列→20列）+ 索引 idx_cooperative_unit_parent_id + 新表 unit_contact（15列，IDENTITY主键/审计/逻辑删）+ 索引 idx_unit_contact_unit_id + 字典 company_type（dict_id=225，20119-20120）+ company_category（dict_id=226，20121-20126）+ 菜单 2020-2027/2029 共9项 + 角色挂载 29 条（§1.4 矩阵）；DB复查（6列/索引/unit_contact结构/两字典8条/菜单/角色挂载）全部 PASS |
| 11 | V1.0.10__contract_module.sql | devdm (F:\dmdbms\data\RUOYI) | Claude Code (dmPython) | 2026-08-14 | ✅ 58/58语句成功（首次+幂等复查各一次）：contract 加 5 列（contract_no/party_unit_id/party_name/start_date/file_url，14列→19列）+ 索引 idx_contract_no_uk（UNIQUE on contract_no，照 project_no 模式含软删行）+ 索引 idx_contract_party_unit_id + contract_node 加 1 列 voucher_url（13列→14列）+ 字典 contract_status（dict_id=227，20127-20129 ACTIVE/EXPIRED/TERMINATED）+ node_status（dict_id=228，20130-20132 PENDING/DONE/OVERDUE）+ 菜单 2030-2036 共 7 项（2030 C 合同管理 parent=2010 + 6 F 按钮）+ 角色挂载 29 条（§1.4 矩阵）；DB复查（contract 19列/5列注释/UNIQUE索引/普通索引/contract_node voucher_url/两字典6条/菜单7项属性/7角色挂载29条）全部 PASS |
| 12 | V1.0.11__expense_module.sql | devdm (F:\dmdbms\data\RUOYI) | Claude Code (dmPython) | 2026-08-14 | ✅ 59/59语句成功（首次+幂等复查各一次）：budget_split 加 3 列（used_amount/balance/version，10列→13列）+ 存量初始化 UPDATE + 唯一索引 idx_budget_split_pc_uk（project_id,category,del_flag，PL块预检重复行→本次0条重复正常建索引）+ expense 加 4 列（split_id/status/voucher_url/version，13列→17列）+ 存量初始化 UPDATE + 索引 idx_expense_split_id + 字典 expense_status（dict_id=229，20133-20134 NORMAL/VOID）+ sys_config biz.expense.allowOverdraft=false（config_id 动态 MAX+1=7）+ 菜单 2040-2046 共 7 项（2040 C 经费管理 parent=2010 + 6 F 按钮）+ 角色挂载 30 条（任务卡正文写 31，按§2.5矩阵逐项实算为 30，矩阵优先于总数）；DB复查（budget_split 13列/唯一索引UNIQUE/expense 17列/普通索引/两存量初始化无NULL/字典2条/sys_config值false/菜单7项属性/7角色挂载30条）全部 PASS |
| 13 | V1.0.12__fix_menu_icon_tax_rate.sql | devdm (F:\dmdbms\data\RUOYI) | Claude Code (dmPython) | 2026-08-15 | ✅ 9/9语句成功（首次+幂等复查各一次）：菜单图标修复（2011 课题管理 '#'→education、2020 合作单位 '#'→peoples，用户反馈菜单栏无图标）+ expense.tax_rate 字段 DECIMAL(5,4)→VARCHAR(20)（PL块预检列类型幂等，税率改字典）+ 字典 tax_rate（dict_id=230，20135-20138 1%/3%/6%/13%）；DB复查（两菜单icon正确/列类型VARCHAR/字典4项值序）全部 PASS |
| 14 | V1.0.13__document_approval.sql | devdm (F:\dmdbms\data\RUOYI) | Claude Code (dmPython) | 2026-08-15 | ✅ 50/50语句成功（首次+幂等复查各一次）：project_document 加 2 列（submitter_id/plan_submit_date）+ 索引 idx_project_document_submitter + approval 加 2 列（round/reject_reason）+ 唯一索引 idx_approval_doc_id_uk（doc_id，PL块预检重复→本次0条重复正常建索引）+ approval_history 加 1 列（round）+ 菜单 2050-2056 共 7 项（2050 C 课题资料 parent=2010 + 6 F 按钮）+ 角色挂载 31 条（§2.4矩阵：7+7+2+2+2+6+5）；DB复查（project_document 15列/approval 15列+唯一索引UNIQUE/approval_history 13列/菜单7项属性/7角色挂载31条）全部 PASS |
| 15 | V1.0.14__honor_module.sql | devdm (F:\dmdbms\data\RUOYI) | Claude Code (dmPython) | 2026-08-15 | ✅ 45/45语句成功（首次+幂等复查各一次）：honor 加 2 列（certificate_no/certificate_url）+ honor_relation 加 2 列（role_desc/contribution_desc）+ ref_type 注释更新追加 UNIT 合作单位 + 字典 honor_ref_type（dict_id=231，20139-20141 PROJECT/RESEARCHER/UNIT）+ 菜单 2060-2066 共 7 项（2060 C 荣誉管理 parent=2010 order_num=6 icon=star + 6 F 按钮）+ 角色挂载 29 条（§1矩阵：7+7+3+3+3+3+3）；DB复查（honor 15列/honor_relation 12列/ref_type 注释三枚举/字典3条/菜单7项含icon/order_num/7角色挂载29条）全部 PASS |
| 16 | V1.0.15__rd_module.sql | devdm (F:\dmdbms\data\RUOYI) | Claude Code (dmPython) | 2026-08-15 | ✅ 78/78语句成功（首次+幂等复查各一次）：rd_labor_allocation 加 5 列（monthly_hours/hourly_rate/surcharge_detail/confirm_by/confirm_time，15列→20列）+ 3 非唯一索引（idx_rd_alloc_pm/idx_rd_worktime_daily_prd/idx_rd_salary_rm）+ 菜单 2070-2085 共 14 项（2070 C 工时填报 icon=time order_num=7 + 2071-2072 F；2075 C 工资与预算 icon=money order_num=8 + 2076-2079 F；2080 C 分摊管理 icon=chart order_num=9 + 2081-2085 F）+ 角色挂载 56 条（§4矩阵：14+14+14+3+5+1+5）；DB复查（rd_labor_allocation 20列/3索引/菜单14项含icon+path+component/7角色挂载56条/surcharge_rate 未触碰 10行 SUM=0.4986）全部 PASS |

**2026-08-11 执行时修正的达梦兼容问题**（已回写脚本）：
1. `comment` 是达梦保留字 → approval / approval_history 的审批意见列改名 **`comment_text`**（后续阶段5实体请用 `@TableField("comment_text")`）
2. 达梦不支持对视图执行 `COMMENT ON TABLE` → 相关语句改为注释
3. 移除 `trg_expense_after_insert` 触发器（阶段0任务卡决策：经费核减走 Service 层事务）
4. 22张业务表统一补 `remark VARCHAR(500)` 列（适配 BaseEntity，否则 MyBatis-Plus selectById 报列不存在）

复查方式：`python .tmp/check_db.py`（22表/视图/字典/菜单/角色/附加费全项检查，2026-08-11 全绿通过）。

---

## V1.0.1 — 字典数据初始化

**日期**：2026-07-08

**变更内容**：

1. 新增 18 个业务字典类型（`sys_dict_type`，dict_id 200–217）：
   - `project_status`：课题状态（立项/在研/结题/评审/归档）
   - `project_stage`：课题阶段（立项/节点考核/结题/评审）
   - `expense_category`：经费类别（设备费/材料费/差旅费/劳务费/测试化验加工费/出版文献信息传播/其他）
   - `contract_type`：合同类型（研究/服务/采购）
   - `node_type`：节点类型（付款/交付/验收）
   - `approval_status`：审批状态（审核中/已通过/驳回）
   - `cooperation_type`：合作类型（牵头/参与/协作）
   - `honor_level`：荣誉级别（国家级/省部级/国铁集团级/集团公司级/所级）
   - `honor_type`：荣誉类型（集体/个人）
   - `alert_type`：预警类型（合同节点/经费超限/资料逾期）
   - `alert_level`：预警级别（普通/重要/紧急）
   - `member_role`：课题成员角色（主持人/参与人）
   - `edu_level`：学历（本科/硕士/博士）
   - `title_level`：职称（初级/中级/副高/正高）
   - `rd_alloc_status`：研发分摊状态（草稿/已确认）
   - `rd_surcharge_rate`：工资附加费比例（10 项）
   - `unit_type`：单位类型（内部单位/外部单位）
   - `external_unit_type`：外部单位类型（公司/学校/其他）

2. 新增 67 条字典数据项（`sys_dict_data`，dict_code 20001–20067），每项含 `list_class` 样式标签。

3. 新增 10 条工资附加费比例数据（`surcharge_rate` 表），包含职工教育经费、工会经费、基本医疗保险费等 10 项计提比例。

**依赖**：
- 若依框架 `sys_dict_type` / `sys_dict_data` 表（已通过 `ruoyi-dm8.dmp` 导入）
- V1.0.0 `surcharge_rate` 表

---

## V1.0.2 — 菜单权限初始化

**日期**：2026-08-05

**变更内容**：

1. 新增科研管理业务目录与阶段1科研人员管理菜单权限（`sys_menu` / `sys_role_menu`）：
   - 科研管理目录（menu_id=2000, menu_type='M'）
   - 科研人员管理菜单（menu_id=2001, menu_type='C', perms='biz:userProfile:list'）
   - 7个按钮权限（menu_id=2002–2007, menu_type='F'）：查询/新增/修改/删除/导出/导入
   - 权限标识统一格式：`biz:userProfile:{action}`

2. 管理员角色 role_id=1 挂载全部菜单（`sys_role_menu`）。

3. 幂等设计：使用 `INSERT ... SELECT ... WHERE NOT EXISTS`，可重复执行。

**依赖**：
- 若依框架 `sys_menu` / `sys_role_menu` 表（已通过 `ruoyi-dm8.dmp` 导入）

---

## V1.0.0 — 基础表 DDL

**日期**：2026-07-08

**变更内容**：

1. 创建 25 张业务表 DDL，适配达梦 DM8 兼容语法：
   - **模块一**（若依框架基础表，4 张）：`sys_user`、`sys_dept`、`sys_role`、`sys_user_role`
     - 注意：已通过 `ruoyi-dm8.dmp` 导入，如已存在请跳过
   - **模块二**（科研人员管理，1 张）：`biz_user_profile`
   - **模块三**（课题管理，3 张）：`project`、`project_member`、`project_document`
   - **模块四**（合同管理，2 张）：`contract`、`contract_node`
   - **模块五**（经费管理，2 张）：`budget_split`、`expense`
   - **模块六**（审批管理，2 张）：`approval`、`approval_history`
   - **模块七**（合作单位管理，2 张）：`cooperative_unit`、`project_unit`
   - **模块八**（荣誉管理，2 张）：`honor`、`honor_relation`
   - **模块九**（预警与通知，2 张）：`alert`、`notification`
   - **模块十**（研发人工费管理，5 张）：`rd_labor_budget`、`rd_researcher_salary`、`rd_worktime_daily`、`rd_worktime_monthly`、`rd_labor_allocation`

2. 创建附加配置表 1 张：`surcharge_rate`（工资附加费比例配置表）

3. 创建触发器 1 个：`trg_expense_after_insert`（经费记账后自动扣减课题预算余额）

4. 创建视图 1 个：`v_biz_user_profile`（科研人员扩展信息联合查询）

**适配要点**：
- 自增主键统一使用 `IDENTITY(1,1)`（达梦原生自增，无需序列）
- 移除所有外键约束（若依规范，应用层保证关联完整性）
- 每张表增加 `del_flag CHAR(1) DEFAULT '0'` 逻辑删除字段
- 每张表增加 `create_by`/`create_time`/`update_by`/`update_time` 审计字段
- 根据字典类型补充了以下字段：`contract.contract_type`、`contract_node.node_type`、`honor.honor_type`、`cooperative_unit.unit_type`/`external_unit_type`
- 触发器使用 Oracle 风格 `BEGIN...END` 语法（达梦兼容）
- 视图使用标准 `CREATE OR REPLACE VIEW` 语法

**数据类型**：BIGINT / VARCHAR / DECIMAL / DATE / TIMESTAMP / CHAR / INT（均为达梦 DM8 原生支持类型）

---

## 脚本执行顺序

| 序号 | 脚本 | 说明 |
|------|------|------|
| 0 | `ruoyi-dm8.dmp` | 若依框架基础表与初始数据（数据泵导入） |
| 1 | `V1.0.0__base_tables.sql` | 科研管理平台 25 张业务表 DDL |
| 2 | `V1.0.1__dict_data.sql` | 业务字典数据初始化 |
| 3 | `V1.0.2__menu_permissions.sql` | 科研管理菜单与按钮权限 |
| 4 | `V1.0.3__views.sql` | 业务视图（v_biz_user_profile 等） |
| 5 | `V1.0.4__roles.sql` | 6 业务角色（100-105）+ sys_role_menu 挂载 |
| 6 | `V1.0.5__profile_into_user.sql` | 科研档案并入用户管理（删 id_number + 补 degree/major/bio + 3 字典） |
| 7 | `V1.0.6__project_module.sql` | 阶段2课题管理基线：project 加 2 列 + 唯一索引 + project_type 字典 6 项 + 菜单 2010-2019 + 6 业务角色差异化挂载 |
| 8 | `V1.0.7__budget_breakdown.sql` | 阶段2变更1预算细分基线：预算科目字典 budget_category（dict_id=222，dict_data 20097-20106 共10项）+ budget_split.category 注释更新 |
| 9 | `V1.0.8__project_category_specialty.sql` | 阶段2变更2项目类别/专业分类基线：字典 project_category（dict_id=223，20107-20109 共3项）+ specialty（dict_id=224，20110-20118 共9项）+ project 加列 project_category/specialty + member_role HOST 改组长 |
| 10 | `V1.0.9__cooperative_unit.sql` | 阶段6合作单位基线：cooperative_unit 加 6 树形列（parent_id/ancestors/company_type/company_category/expertise/order_num）+ 索引 + 新表 unit_contact（联系人=高校老师统一）+ company_type/company_category 两字典（8条）+ 菜单 2020-2027/2029（9项）+ 角色挂载 29 条 |
| 11 | `V1.0.10__contract_module.sql` | 阶段3合同管理基线：contract 加 5 列（contract_no/party_unit_id/party_name/start_date/file_url）+ 索引 idx_contract_no_uk（UNIQUE，含软删行）+ 索引 idx_contract_party_unit_id + contract_node 加 1 列 voucher_url + 字典 contract_status（dict_id=227，3项）+ node_status（dict_id=228，3项，OVERDUE 阶段9 预留）+ 菜单 2030-2036（7项）+ 角色挂载 29 条 |
| 12 | `V1.0.11__expense_module.sql` | 阶段4经费管理基线：budget_split 加 3 列（used_amount/balance/version）+ 存量初始化 + 唯一索引 idx_budget_split_pc_uk（project_id,category,del_flag，建前查重复行）+ expense 加 4 列（split_id/status/voucher_url/version）+ 存量初始化 + 索引 idx_expense_split_id + 字典 expense_status（dict_id=229，2项）+ sys_config biz.expense.allowOverdraft=false + 菜单 2040-2046（7项）+ 角色挂载 30 条 |
| 13 | `V1.0.12__fix_menu_icon_tax_rate.sql` | 阶段4收尾修复：菜单图标修复（2011 课题管理 '#'→education、2020 合作单位 '#'→peoples）+ expense.tax_rate 字段 DECIMAL(5,4)→VARCHAR(20) + 字典 tax_rate（dict_id=230，1%/3%/6%/13%） |
| 14 | `V1.0.13__document_approval.sql` | 阶段5资料与审批基线：project_document 加 2 列（submitter_id/plan_submit_date）+ 索引 idx_project_document_submitter + approval 加 2 列（round/reject_reason）+ 唯一索引 idx_approval_doc_id_uk（doc_id，建前查重复行）+ approval_history 加 1 列（round）+ 菜单 2050-2056（7项）+ 角色挂载 31 条 |
| 15 | `V1.0.14__honor_module.sql` | 阶段7荣誉管理基线：honor 加 2 列（certificate_no/certificate_url）+ honor_relation 加 2 列（role_desc/contribution_desc）+ ref_type 注释追加 UNIT 合作单位 + 字典 honor_ref_type（dict_id=231，20139-20141 PROJECT/RESEARCHER/UNIT）+ 菜单 2060-2066（7项，2060 C 荣誉管理 parent=2010 order_num=6 icon=star）+ 角色挂载 29 条 |
| 16 | `V1.0.15__rd_module.sql` | 阶段8研发加计扣除基线：rd_labor_allocation 加 5 列（monthly_hours/hourly_rate/surcharge_detail/confirm_by/confirm_time，15列→20列）+ 3 非唯一索引（idx_rd_alloc_pm/idx_rd_worktime_daily_prd/idx_rd_salary_rm）+ 菜单 2070-2085（14项，2070/2075/2080 三C菜单 icon=time/money/chart 路径rdworktime/rdsalary/rdallocation）+ 角色挂载 56 条（§4矩阵：14+14+14+3+5+1+5） |

---

## V1.0.6 — 阶段2 课题管理基线

**日期**：2026-08-12

**任务卡关联**：阶段2 课题管理 / Task 1：V1.0.6 + 后端 9 文件 + 编译（后端基线）

**变更内容**：

1. **project 表新增 2 列**（V1.0.0 15 列 → V1.0.6 17 列）
   - `project_no VARCHAR(50)`：课题编号，格式 `KY-{yyyy}-{3位流水}`，唯一索引 `idx_project_no_uk` 兜底，INSERT 时由 Service 生成（`SELECT MAX` + 重试 1 次）
   - `project_type VARCHAR(20)`：课题级别，字典 `project_type`，值大写

2. **新增字典 `project_type`**（dict_id=221，dict_code 20091–20096，6 项）
   - `NATIONAL` 国家级（danger）
   - `PROVINCIAL` 省部级（warning）
   - `CR_GROUP` 国铁集团级（primary，区别于 honor_level=GROUP）
   - `COMPANY` 集团公司级（success）
   - `INSTITUTE` 所级（info）
   - `LATERAL` 横向委托（default）

3. **新增菜单 10 项**（sys_menu 2010–2019）
   - 2010 M 科研管理（顶级目录，icon=form，path=biz）
   - 2011 C 课题管理（path=project, component=biz/project/index, perms=biz:project:list）
   - 2012–2019 F 8 个按钮：query / add / edit / remove / export / archive / member / detail

4. **角色挂载（按 §3.7.4 矩阵）**
   - admin(1) / science_admin(101)：全部 10 项
   - leader(100) / office(102)：只读 3 项（2010, 2011, 2012）
   - labor_hr(103)：4 项（2010, 2011, 2012, 2018，成员审核）
   - dept_leader(104)：8 项（2010, 2011, 2012, 2013, 2014, 2016, 2018, 2019，本部门，无 remove/archive）
   - researcher(105)：5 项（2010, 2011, 2012, 2016, 2019，仅本人相关，不挂写权限；R2.3 补挂 2016 export）

**幂等性设计**：
- 列添加走 `ALTER TABLE ADD IF NOT EXISTS`（达梦支持）
- 唯一索引通过 PL 匿名块预检 `USER_INDEXES` 后再 `CREATE UNIQUE INDEX`（达梦 `CREATE INDEX` 无 `IF NOT EXISTS`）
- 字典 / 菜单 / 角色菜单挂载走 `INSERT ... SELECT ... WHERE NOT EXISTS`
- **首次执行 64/64 成功；二次重跑 64/64 全绿零副作用**

**DB 复查**（`python .tmp/check_v106_after.py`，全部 PASS）：
- project 表现有 17 列，含 `PROJECT_NO` / `PROJECT_TYPE`
- `IDX_PROJECT_NO_UK` 存在且 UNIQUE
- `sys_dict_type` dict_id=221=dict_name='课题级别'/dict_type='project_type'
- `sys_dict_data` dict_code 20091–20096，dict_value 顺序与任务卡一致，list_class 与字典样式标签对应
- `sys_menu` 2010–2019 共 10 项，菜单类型 / 父菜单 / 路径 / 组件 / 权限标识全部对齐任务卡 §3.7.3
- `sys_role_menu` 7 个角色（admin + 6 业务角色）挂载数与 §3.7.4 矩阵完全一致

**依赖**：
- V1.0.0：`project` / `project_member` 表（15 列 + 8 列）
- V1.0.1：`project_status` / `member_role` 字典
- V1.0.4：6 业务角色（100–105）
- V1.0.5：`sys_user` 含 dept_id（详情/列表 JOIN 基础）

**后续任务**：
- 阶段2 Task 1：后端 9 文件实现（Project Domain / ProjectMember Domain / ProjectMapper / ProjectMemberMapper / 对应 XML / IProjectService / ProjectServiceImpl / ProjectController）+ `mvn clean compile -DskipTests` 通过
- 阶段2 Task 2：前端 3 文件（api/biz/project.js + views/biz/project/index.vue + views/biz/project/detail.vue）
- 阶段2 Task 3：冒烟测试（状态机非法迁移 / 唯一 HOST 约束 / 换主持人事务 / researcher 数据权限）

---

## V1.0.7 — 阶段2变更1 课题预算细分基线

**日期**：2026-08-13

**任务卡关联**：阶段2 课题管理·变更1 / Task 1：V1.0.7 SQL（预算科目字典）+ devdm 执行 + changelog

**变更内容**：

1. **新增字典 `budget_category`**（dict_id=222，dict_name='预算科目'，dict_type='budget_category'，status='0'）
   - dict_data 20097–20106 共 10 项（dict_sort 1-10，dict_value 大写），对应预算总额拆分的 10 个叶子科目：
     - `LABOR` 人工费（primary，直接费）
     - `EQUIPMENT` 设备费（success，直接费）
     - `MATERIAL` 材料费（info，直接费/业务费）
     - `TESTING` 测试化验加工费（warning，直接费/业务费）
     - `FUEL` 燃料动力费（default，直接费/业务费）
     - `TRAVEL` 差旅费会议费国际合作交流费（primary，直接费/业务费）
     - `PUBLICATION` 出版文献信息传播知识产权事务费（info，直接费/业务费）
     - `INDIRECT` 间接费（warning，顶级）
     - `OUTSOURCING` 委外支出费（primary，顶级）
     - `TAX` 税金（danger，顶级）

2. **budget_split.category 列注释更新**（表已存在 V1.0.0，无 DDL 变更）
   - `'经费类别（对应字典 expense_category）'` → `'预算科目（对应字典 budget_category）'`
   - category 字段语义由 expense_category 改为 budget_category

**幂等性设计**：
- 字典类型 / 字典数据走 `INSERT ... SELECT ... WHERE NOT EXISTS`
- `COMMENT ON COLUMN` 直接执行（达梦重跑覆盖为同值，零副作用，与 V1.0.6 一致）
- **首次执行 12/12 成功；二次重跑 12/12 全绿零副作用**

**DB 复查**（`python .tmp/check_v107_after.py`，全部 PASS）：
- `sys_dict_type` dict_id=222=dict_name='预算科目'/dict_type='budget_category'/status='0'
- `sys_dict_data` dict_code 20097–20106 共 10 项，dict_sort 顺序 / dict_value 大写 / list_class 与任务卡 §1.2 科目对应
- `budget_split.category` 注释已更新为「预算科目（对应字典 budget_category）」

**依赖**：
- V1.0.0：`budget_split` 表（split_id/project_id/category/budget_amount 等 10 列）
- V1.0.1：`sys_dict_type` / `sys_dict_data` 框架表

**后续任务**：
- 阶段2变更1 Task 2：后端（BudgetSplit 三件套 + Project 编号人工输入/预算细分保存/总额Σ/详情返回）
- 阶段2变更1 Task 3：前端（编号输入 + 预算细分分组表单 + 详情展示）
- 阶段2变更1 Task 4：冒烟（编号人工/唯一、预算Σ、回归）+ 数据权限抽查
- 挂账：监管上限校验（间接费/委外费比例）不在本变更范围

---

## V1.0.8 — 阶段2变更2 项目类别/专业分类基线

**日期**：2026-08-13

**任务卡关联**：阶段2 课题管理·变更2 / Task 1：V1.0.8 SQL（项目类别/专业分类字典）+ devdm 执行 + changelog

**变更内容**：

1. **新增字典 `project_category`**（dict_id=223，dict_name='项目类别'，dict_type='project_category'，status='0'）
   - dict_data 20107–20109 共 3 项（dict_sort 1-3，dict_value 大写），对应课题经费来源：
     - `A` 全额资助课题（primary）
     - `B` 定额补助课题（warning）
     - `C` 经费全部自筹课题（default）

2. **新增字典 `specialty`**（dict_id=224，dict_name='专业分类'，dict_type='specialty'，status='0'）
   - dict_data 20110–20118 共 9 项（dict_sort 1-9，dict_value 大写），对应铁路专业：
     - `Y` 运输 / `J` 机务 / `GD` 供电 / `C` 车辆 / `G` 工务工程 / `D` 电务 / `X` 信息技术 / `Z` 综合 / `F` 软科学

3. **project 表新增 2 列**（V1.0.6 17 列 → V1.0.8 19 列，均可空——存量数据无值，必填约束在应用层）
   - `project_category VARCHAR(20)`：项目类别（字典 project_category）
   - `specialty VARCHAR(20)`：专业分类（字典 specialty）

4. **member_role HOST 标签 主持人→组长**（dict_value=HOST，仅标签改；dict 值 HOST/PARTICIPANT 不变）

**幂等性设计**：
- 字典类型 / 字典数据走 `INSERT ... SELECT ... WHERE NOT EXISTS`
- 列添加走 `ALTER TABLE ADD IF NOT EXISTS`（达梦支持，同 V1.0.6）；`COMMENT ON COLUMN` 直接执行（重跑覆盖为同值，零副作用）
- `UPDATE sys_dict_data` 追加 `AND dict_label='主持人'`，二次重跑 WHERE 不命中，零副作用
- **首次执行 19/19 成功；二次重跑 19/19 全绿零副作用**

**DB 复查**（`python .tmp/check_v108_after.py`，全部 PASS）：
- `sys_dict_type` dict_id=223='项目类别'/project_category、dict_id=224='专业分类'/specialty，status='0'
- `sys_dict_data` 20107–20109 共 3 项 / 20110–20118 共 9 项，dict_sort 顺序、dict_value 与任务卡 §1.1 一致
- project 表含 `PROJECT_CATEGORY`/`SPECIALTY` 两列（VARCHAR，可空），列注释正确
- `member_role` HOST 的 dict_label='组长'

**依赖**：
- V1.0.0：`project` 表
- V1.0.1：`sys_dict_type` / `sys_dict_data` 框架表、`member_role` 字典

**后续任务**：
- 阶段2变更2 Task 2：后端（Project 两字段 + insert 必填校验 + 删除课题级联逻辑删 project_member + update 可改）
- 阶段2变更2 Task 3：前端（两字典下拉必填 + 列表/详情展示 + 文案 主持人→组长）
- 阶段2变更2 Task 4：冒烟（新增不传两字段报错 / 删除含组长+成员课题成功 / 回归不退化）

---

## V1.0.9 — 阶段6 合作单位基线

**日期**：2026-08-13

**任务卡关联**：阶段6 合作单位 / Task 1：V1.0.9 SQL（树列/unit_contact 表/2字典/菜单 2020-2029/角色挂载）+ devdm 执行 + changelog

**变更内容**：

1. **cooperative_unit 表加 6 树形列**（V1.0.0 14 列 → V1.0.9 20 列，幂等 `ADD IF NOT EXISTS` + `COMMENT ON COLUMN`）
   - `parent_id BIGINT DEFAULT 0`：父单位ID（0=顶级，照 sys_dept 模型；公司树≤3层/学校树≤2层）
   - `ancestors VARCHAR(200) DEFAULT ''`：祖级链（逗号分隔，首尾空串，例：`,3,5,`）
   - `company_type VARCHAR(20)`：公司类型（字典 company_type，公司用）
   - `company_category VARCHAR(20)`：公司性质（字典 company_category，公司用）
   - `expertise VARCHAR(500)`：擅长领域（公司/学校通用文本）
   - `order_num INT DEFAULT 0`：树内排序
   - 新索引 `idx_cooperative_unit_parent_id`（PL 块预检 `USER_INDEXES` 幂等创建）

2. **新表 `unit_contact`**（公司联系人/高校老师统一建模，PL 块预检 `user_tables` 幂等建表）
   - `contact_id BIGINT IDENTITY(1,1)` 主键 / `unit_id BIGINT NOT NULL` / `contact_name VARCHAR(50) NOT NULL` / `position VARCHAR(50)` / `phone VARCHAR(20)` / `email VARCHAR(100)` / `major VARCHAR(100)`（高校用）/ `research_field VARCHAR(200)`（高校用）/ `is_primary CHAR(1) DEFAULT '0'` / `del_flag CHAR(1) DEFAULT '0'` / 审计 4 字段 / `remark VARCHAR(500)`，共 15 列
   - 新索引 `idx_unit_contact_unit_id`（PL 块内 `EXECUTE IMMEDIATE` 创建）

3. **新增字典 `company_type`**（dict_id=225，dict_code 20119–20120，2 项，dict_value 大写）
   - `MICRO` 小微企业（primary）/ `GENERAL` 一般纳税人（success）

4. **新增字典 `company_category`**（dict_id=226，dict_code 20121–20126，6 项，dict_value 大写）
   - `SOE` 国有企业 / `PRIVATE` 私营企业 / `JV` 合资企业 / `FOREIGN` 外资企业 / `INSTITUTION` 事业单位 / `OTHER` 其他

5. **新增菜单 9 项**（sys_menu 2020–2029，跳 2028 预留）
   - 2020 C 合作单位管理（parent=2010，path=unit，component=biz/unit/index，perms=biz:unit:list）
   - 2021–2027 F 按钮：query / add / edit / remove / export / contact（联系人维护）/ treeselect（树选择器，不需单独权限，挂 admin/science_admin 供前端可见性）
   - 2029 F 关联单位（parent=2011 课题管理，perms=biz:project:unit，课题详情页「合作单位」tab）

6. **角色挂载（任务卡 §1.4 矩阵，共 29 条 `sys_role_menu`）**
   - admin(1) / science_admin(101)：全部 9 项（2020–2027 + 2029）
   - leader(100) / office(102) / labor_hr(103) / researcher(105)：只读 2 项（2020+2021）
   - dept_leader(104)：3 项（2020+2021+2029）

**幂等性设计**：
- 列添加走 `ALTER TABLE ADD IF NOT EXISTS`（达梦支持，同 V1.0.6/V1.0.8）；`COMMENT ON COLUMN` 直接执行（重跑覆盖为同值，零副作用）
- 索引 / 表走 PL 匿名块预检 `USER_INDEXES` / `USER_TABLES` 后再创建（达梦 `CREATE INDEX` / `CREATE TABLE` 无 `IF NOT EXISTS`）
- 字典 / 菜单 / 角色菜单挂载走 `INSERT ... SELECT ... WHERE NOT EXISTS`
- **首次执行 62/62 成功；二次重跑 62/62 全绿零副作用**

**DB 复查**（`python .tmp/check_v109_after.py`，全部 PASS）：
- cooperative_unit 含 6 新列（类型/注释对齐任务卡 §1.1），`IDX_COOPERATIVE_UNIT_PARENT_ID` 存在
- `unit_contact` 表 15 列列序对齐任务卡 §1.2，`IDX_UNIT_CONTACT_UNIT_ID` 存在
- `sys_dict_type` dict_id=225='公司类型'/company_type、226='公司性质'/company_category，status='0'
- `sys_dict_data` 20119–20120（2 项）/ 20121–20126（6 项），dict_sort 顺序、dict_value 大写与任务卡 §1.3 一致
- `sys_menu` 2020–2029 共 9 项（跳 2028），类型/父菜单/组件/权限/名称对齐任务卡 §1.4
- `sys_role_menu` 7 角色挂载总数 = 29 条（§1.4 矩阵一致）

**依赖**：
- V1.0.0：`cooperative_unit` 表（14 列）、`project_unit` 表
- V1.0.1：`sys_dict_type` / `sys_dict_data` 框架表、`unit_type`(216) / `external_unit_type`(217) / `cooperation_type`(206) 复用字典
- V1.0.4：6 业务角色（100–105）
- V1.0.6：菜单 2010（科研管理目录）/ 2011（课题管理）

**后续任务**：
- 阶段6 Task 2：后端（合作单位树 CRUD + 联系人子资源 + 课题关联子资源，照 SysDeptServiceImpl；`biz:project:unit` Service 先过 scoped selectProjectById 闸门）
- 阶段6 Task 3：前端（树表页 views/biz/unit/index.vue + 联系人弹窗 contactDialog.vue + 课题详情「合作单位」tab）
- 阶段6 Task 4：冒烟（任务卡 §六 8 项）+ 回归

---

## V1.0.10 — 阶段3 合同管理基线

**日期**：2026-08-14

**任务卡关联**：阶段3 合同管理 / Task 1：V1.0.10 SQL（5 列 + 2 索引 + 1 列 + 2 字典 + 7 菜单 + 29 角色挂载）+ devdm 执行 + changelog

**变更内容**：

1. **contract 表加 5 列**（V1.0.0 14 列 → V1.0.10 19 列，幂等 `ADD IF NOT EXISTS` + `COMMENT ON COLUMN`）
   - `contract_no VARCHAR(50)`：合同编号（人工输入必填；唯一索引 idx_contract_no_uk 兜底，含软删行，照 project_no 模式）
   - `party_unit_id BIGINT`：对方主体（cooperative_unit.unit_id，可空）
   - `party_name VARCHAR(200)`：对方名称（选单位时=单位名快照；未建档时手工填，仅展示冗余，非主数据）
   - `start_date DATE`：生效日期
   - `file_url VARCHAR(500)`：合同附件（/common/upload 相对路径）
   - 新索引 `idx_contract_no_uk`（UNIQUE on contract_no，PL 块预检 `USER_INDEXES` 幂等创建，照 V1.0.6 idx_project_no_uk 写法）
   - 新索引 `idx_contract_party_unit_id`（普通 on party_unit_id，PL 块预检幂等创建）

2. **contract_node 表加 1 列**（V1.0.0 13 列 → V1.0.10 14 列）
   - `voucher_url VARCHAR(500)`：完成凭证（验收单/发票，/common/upload 相对路径）
   - 完成日期复用现有 `actual_date`（总纲写 finish_date，语义等价，不加列不迁移——任务卡备案）

3. **新增字典 `contract_status`**（dict_id=227，dict_code 20127–20129，3 项，dict_value 大写）
   - `ACTIVE` 履行中（primary）
   - `EXPIRED` 已到期（warning；阶段9 定时任务写入）
   - `TERMINATED` 已终止（danger）

4. **新增字典 `node_status`**（dict_id=228，dict_code 20130–20132，3 项，dict_value 大写）
   - `PENDING` 待执行（info）
   - `DONE` 已完成（success）
   - `OVERDUE` 已逾期（danger；阶段9 定时任务写入，本期预留字典项）

5. **新增菜单 7 项**（sys_menu 2030–2036，挂在 2010 科研管理下）
   - 2030 C 合同管理（path=contract，component=biz/contract/index，perms=biz:contract:list，icon=documentation）
   - 2031 F 查询（biz:contract:query）
   - 2032 F 新增（biz:contract:add）
   - 2033 F 修改（biz:contract:edit）
   - 2034 F 删除（biz:contract:remove）
   - 2035 F 导出（biz:contract:export）
   - 2036 F 节点维护（biz:contract:node，节点 CRUD + 完成动作）

6. **角色挂载（任务卡 §1.4 矩阵，共 29 条 `sys_role_menu`）**
   - admin(1) / science_admin(101)：全部 7 项（2030–2036）
   - leader(100) / office(102) / labor_hr(103)：只读 2 项（2030+2031）
   - dept_leader(104)：6 项（2030/2031/2032/2033/2035/2036，无 remove）
   - researcher(105)：3 项（2030/2031/2035）

**幂等性设计**：
- 列添加走 `ALTER TABLE ADD IF NOT EXISTS`（达梦支持，同 V1.0.6/V1.0.8/V1.0.9）；`COMMENT ON COLUMN` 直接执行（重跑覆盖为同值，零副作用）
- 唯一/普通索引走 PL 匿名块预检 `USER_INDEXES` 后再 `CREATE [UNIQUE] INDEX`（达梦 `CREATE INDEX` 无 `IF NOT EXISTS`，照 V1.0.6 idx_project_no_uk 写法）
- 字典 / 菜单 / 角色菜单挂载走 `INSERT ... SELECT ... WHERE NOT EXISTS`
- **首次执行 58/58 成功；二次重跑 58/58 全绿零副作用**

**DB 复查**（`python .tmp/check_v1010_after.py`，全部 PASS）：
- contract 表现有 19 列（14 原 + 5 新），5 列注释对齐任务卡 §1.1
- `IDX_CONTRACT_NO_UK` 存在且 UNIQUE on CONTRACT_NO；`IDX_CONTRACT_PARTY_UNIT_ID` 存在，列 = PARTY_UNIT_ID
- `contract_node` 含 `VOUCHER_URL` 列（VARCHAR(500)），总列数 14
- `sys_dict_type` dict_id=227='合同状态'/contract_status、228='节点状态'/node_status，status='0'
- `sys_dict_data` 20127–20129（3 项，ACTIVE/EXPIRED/TERMINATED 大写，list_class primary/warning/danger）/ 20130–20132（3 项，PENDING/DONE/OVERDUE 大写，list_class info/success/danger）
- `sys_menu` 2030–2036 共 7 项，类型/父菜单/组件/权限/名称全部对齐任务卡 §1.4
- `sys_role_menu` 7 角色挂载总数 = 29 条（§1.4 矩阵一致：7+7+2+2+2+6+3）

**依赖**：
- V1.0.0：`contract` / `contract_node` 表（14 列 + 13 列）
- V1.0.1：`sys_dict_type` / `sys_dict_data` 框架表；`contract_type`(203) / `node_type`(204) 复用字典
- V1.0.4：6 业务角色（100–105）
- V1.0.6：菜单 2010（科研管理目录）/ 2011（课题管理）
- V1.0.9：`cooperative_unit` 表（20 列，含 unit_id 供 party_unit_id 关联）

**后续任务**：
- 阶段3 Task 2：后端（Contract/ContractNode Domain + Controller + 数据权限照 Project 模式 + 节点完成动作 + party 二选一校验 + contract_no 查重）
- 阶段3 Task 3：前端（index.vue + nodeDialog.vue + api；字典 useDict 四类；按钮 v-hasPermi 对齐 §1.4；前端分支 feature/biz-contract-ui）
- 阶段3 Task 4：冒烟（任务卡 §六 8 项：新增合同/party 二选一/节点 CRUD/完成动作/逾期标志/researcher 数据权限/级联逻辑删/回归）

---

## V1.0.11 — 阶段4 经费管理基线

**日期**：2026-08-14

**任务卡关联**：阶段4 经费管理 / Task 1：V1.0.11 SQL（两表加列 + 唯一索引 + 字典 + sys_config + 菜单 2040-2046 + 角色挂载）+ devdm 幂等执行 + changelog

**变更内容**：

1. **budget_split 表加 3 列**（V1.0.0 10 列 → V1.0.11 13 列，幂等 `ADD IF NOT EXISTS` + `COMMENT ON COLUMN`）
   - `used_amount DECIMAL(14,2) DEFAULT 0.00`：已用金额缓存 = Σ 有效 expense.amount（事务内重算写回）
   - `balance DECIMAL(14,2) DEFAULT 0.00`：余额缓存 = budget_amount - used_amount
   - `version INT DEFAULT 0`：乐观锁（MyBatis-Plus `@Version`）
   - 存量数据初始化：`UPDATE budget_split SET used_amount=0, balance=budget_amount, version=0 WHERE used_amount IS NULL`（幂等）
   - 新索引 `idx_budget_split_pc_uk`（UNIQUE on project_id, category, del_flag；PL 块预检 `USER_INDEXES` + 建索引前先查重复行，若存在重复行 `RAISE_APPLICATION_ERROR` 中止；本次执行前勘查 devdm 现有数据 0 条重复，正常建索引通过）

2. **expense 表加 4 列**（V1.0.0 13 列 → V1.0.11 17 列）
   - `split_id BIGINT`：关联预算分劈行（budget_split.split_id；NOT NULL 语义由应用层强校验）
   - `status VARCHAR(20) DEFAULT 'NORMAL'`：流水状态（字典 expense_status：NORMAL 正常 / VOID 已作废）
   - `voucher_url VARCHAR(500)`：凭证（/common/upload 相对路径）
   - `version INT DEFAULT 0`：乐观锁
   - 存量数据初始化：`UPDATE expense SET status='NORMAL', version=0 WHERE status IS NULL`（幂等）
   - 新索引 `idx_expense_split_id`（普通，PL 块预检幂等创建）

3. **新增字典 `expense_status`**（dict_id=229，dict_code 20133–20134，2 项，dict_value 大写）
   - `NORMAL` 正常（success，sort 1）
   - `VOID` 已作废（info，sort 2）

4. **新增 `sys_config` 配置项**
   - `config_name='经费记账-允许透支'`、`config_key='biz.expense.allowOverdraft'`、`config_value='false'`、`config_type='Y'`
   - `INSERT...WHERE NOT EXISTS` 幂等；`config_id` 取当前 `MAX(config_id)+1` 动态子查询（避免跨环境硬编码冲突），devdm 现有 MAX=6，本次落地为 7

5. **新增菜单 7 项**（sys_menu 2040–2046，挂在 2010 科研管理下，order_num=4）
   - 2040 C 经费管理（path=expense，component=biz/expense/index，perms=biz:expense:list，icon=money）
   - 2041 F 查询（biz:expense:query）
   - 2042 F 记账（biz:expense:add）
   - 2043 F 作废（biz:expense:void）
   - 2044 F 导出（biz:expense:export）
   - 2045 F 预算调整（biz:expense:budget）
   - 2046 F 预警查看（biz:expense:alert）

6. **角色挂载（任务卡 §2.5 矩阵，实算共 30 条 `sys_role_menu`）**
   - admin(1) / science_admin(101)：全部 7 项（2040–2046）
   - dept_leader(104)：6 项（2040/2041/2042/2043/2044/2045，无 alert）
   - researcher(105)：4 项（2040/2041/2042/2046，可查可记账可看预警，不可作废/调预算）
   - leader(100) / office(102) / labor_hr(103)：只读 2 项（2040+2041）
   - **注**：任务卡 §2.5 正文写"共 31 条"，但按矩阵逐项列出的角色×菜单实算为 7+7+6+4+2+2+2=30 条；矩阵优先于总数，已在 SQL 注释与本节说明，脚本按 30 条矩阵实算落地

**幂等性设计**：
- 列添加走 `ALTER TABLE ADD IF NOT EXISTS`（达梦支持，同 V1.0.6/V1.0.8/V1.0.9/V1.0.10）；`COMMENT ON COLUMN` 直接执行（重跑覆盖为同值，零副作用）
- 存量初始化 `UPDATE ... WHERE xxx IS NULL`：首次命中存量行，二次重跑因列已非 NULL 而 0 行命中，零副作用
- 唯一/普通索引走 PL 匿名块预检 `USER_INDEXES` 后再 `CREATE [UNIQUE] INDEX`；唯一索引额外在预检通过后、建索引前用子查询 `GROUP BY ... HAVING COUNT(*)>1` 查重复行，重复行数>0 则 `RAISE_APPLICATION_ERROR(-20001, ...)` 中止创建（本次 devdm 勘查 0 条重复，未触发）
- 字典 / sys_config / 菜单 / 角色菜单挂载走 `INSERT ... SELECT ... WHERE NOT EXISTS`
- **首次执行 59/59 成功；二次重跑 59/59 全绿零副作用**

**终审后追加（2026-08-14，随阶段4 终审修复轮回写脚本）**：
- **存量数据自愈 UPDATE**（脚本末尾追加，幂等）：DM8 对 `ALTER TABLE ADD col DEFAULT` 会给存量行回填默认值（非 NULL），导致上面"存量初始化 UPDATE ... WHERE used_amount IS NULL"命中 0 行——devdm 实测 2 行存量 balance 错位（=0 而非 budget_amount），Task 5 已一次性修复。脚本末尾现追加恒等式自愈语句 `UPDATE budget_split SET balance = budget_amount - NVL(used_amount,0) ... WHERE balance <> budget_amount - NVL(used_amount,0)`，任何环境重跑均可自愈，devdm 验证两次执行 0 行、恒等式零偏差。**其他环境执行 V1.0.11 请使用含此语句的最新版脚本**
- **业务语义备注（终审 P2-2）**：课题保存路径为全量终态语义——若把课题预算所有科目归零，已有流水的科目 balance 会变为负数并触发 CRITICAL 预警，这是预期行为（"该科目无预算但有历史支出"应当报警），非缺陷

**DB 复查**（`python .tmp/check_v1011_after.py`，全部 PASS）：
- budget_split 表现有 13 列（10 原 + 3 新），`USED_AMOUNT`/`BALANCE`/`VERSION` 列存在且无 NULL 行
- `IDX_BUDGET_SPLIT_PC_UK` 存在且 UNIQUE，列顺序 = PROJECT_ID/CATEGORY/DEL_FLAG
- expense 表现有 17 列（13 原 + 4 新），`SPLIT_ID`/`STATUS`/`VOUCHER_URL`/`VERSION` 列存在，STATUS 无 NULL 行
- `IDX_EXPENSE_SPLIT_ID` 存在
- `sys_dict_type` dict_id=229='经费记账状态'/expense_status，status='0'
- `sys_dict_data` 20133–20134（2 项，NORMAL/VOID 大写，list_class success/info，dict_sort 1/2）
- `sys_config` config_key='biz.expense.allowOverdraft'，config_value='false'，config_type='Y'（落地 config_id=7）
- `sys_menu` 2040–2046 共 7 项，类型/父菜单/组件/权限/名称全部对齐任务卡 §2.5
- `sys_role_menu` 7 角色挂载总数 = 30 条（§2.5 矩阵实算 7+7+6+4+2+2+2=30 一致）

**依赖**：
- V1.0.0：`budget_split` / `expense` 表（10 列 + 13 列）
- V1.0.1：`sys_dict_type` / `sys_dict_data` 框架表；`sys_config` 框架表
- V1.0.4：6 业务角色（100–105）
- V1.0.6：菜单 2010（科研管理目录）
- V1.0.7：字典 `budget_category`（dict_id=222，10 科目，budget_split.category 同源复用）

**后续任务**：
- 阶段4 Task 2：后端预算侧（BudgetSplit Domain 加三列 + `@Version` + D1 前置改造 ProjectServiceImpl.updateProject 预算分支「按 category 增量更新保 split_id」+ 监管上限校验 + /biz/budget 三端点 + project 汇总派生）
- 阶段4 Task 3：后端记账侧（Expense Domain/Mapper/XML + 记账/作废/冲销事务 + BudgetAlertService 双阈值预警 + /biz/expense 六端点 + 数据权限双通道）
- 阶段4 Task 4：前端（预算概览 + 流水 + 记账/调整/作废弹窗 + 预警区，前端分支 feature/biz-expense-ui）
- 阶段4 Task 5：冒烟（任务卡 §七 10 项：预算调整split_id不变/监管上限/记账正确性/预算不足/乐观锁并发/作废/冲销/双阈值预警/数据权限/回归）+ 回归 smoke_project / smoke_contract 关键子集

---

## V1.0.12 — 阶段4 收尾修复（菜单图标 + 税率字典）

**日期**：2026-08-15

**任务卡关联**：阶段4 收尾 / 用户反馈 4 项中的 2 项数据侧修复（菜单栏课题管理无图标；税率改字典）

**变更内容**：

1. **菜单图标修复**（`UPDATE sys_menu ... WHERE icon='#'`，幂等）
   - `2011 课题管理` icon `'#'` → `education`（V1.0.6 建菜单时笔误，'#' 为无效图标致菜单栏无图标）
   - `2020 合作单位` icon `'#'` → `peoples`（V1.0.9 同笔误）

2. **税率字段改字典**
   - `expense.tax_rate` 字段类型 `DECIMAL(5,4)` → `VARCHAR(20)`（PL 块预检 `USER_TAB_COLUMNS.DATA_TYPE` 后 `ALTER ... MODIFY`，幂等）——税率原为自由输入小数，13% 的整数百分比在 DECIMAL(5,4) 下溢出，且税率应受控于字典而非自由填写
   - 新增字典 `tax_rate`（dict_id=230，dict_code 20135-20138，4 项）：`1`→1%(primary) / `3`→3%(info) / `6`→6%(success) / `13`→13%(warning)，dict_value 存百分比整数
   - 后端 `Expense.taxRate` 由 `BigDecimal` 改 `String`（存字典值，加 `@Excel(dictType="tax_rate")`），与 category/status 等字典字段存法一致

**幂等性设计**：
- 菜单 icon 更新走 `UPDATE ... WHERE icon='#'`（改后不再命中，零副作用）
- 字段类型变更走 PL 块预检列类型（已是 VARCHAR 则跳过）；`COMMENT ON COLUMN` 直接执行（重跑覆盖同值）
- 字典走 `INSERT ... SELECT ... WHERE NOT EXISTS`
- **首次执行 9/9 成功；二次重跑 9/9 全绿零副作用**

**DB 复查**（dmPython 直查，全部 PASS）：
- `sys_menu` 2011 icon=education、2020 icon=peoples
- `USER_TAB_COLUMNS` expense.tax_rate DATA_TYPE='VARCHAR'
- `sys_dict_data` tax_rate 4 项 dict_value 1/3/6/13，dict_sort 1-4

**依赖**：
- V1.0.6 / V1.0.9：菜单 2011 / 2020
- V1.0.0 / V1.0.11：expense 表 tax_rate 列、sys_dict_type / sys_dict_data 框架表

**后续任务**：
- 前端：税率下拉（expenseDialog.vue 改 el-select + useDict('tax_rate')）+ 合作单位 tree-select 回显修复——分支 feature/biz-fix-ux
- 阶段5 资料与审批（下一阶段）

---

## V1.0.13 — 阶段5 资料与审批基线

**日期**：2026-08-15

**任务卡关联**：阶段5 资料与审批 / Task 1：V1.0.13 SQL（三表加列 + 唯一索引 + 菜单 2050-2056 + 角色挂载）+ devdm 幂等执行 + changelog

**变更内容**：

1. **project_document 表加 2 列**（实际基线 13 列 → V1.0.13 后 15 列，幂等 `ADD IF NOT EXISTS` + `COMMENT ON COLUMN`）
   - `submitter_id BIGINT`：提交人 user_id（发起审批时回填；upload_by 保留作冗余）
   - `plan_submit_date DATE`：计划提交日期（预警引擎用，科管/室主任手动维护）
   - 新索引 `idx_project_document_submitter`（普通，PL 块预检 `USER_INDEXES` 幂等创建）

2. **approval 表加 2 列**（实际基线 13 列 → V1.0.13 后 15 列）
   - `round INT DEFAULT 1`：审批轮次（驳回重报 +1；发起时=1，一份资料一条当前审批）
   - `reject_reason VARCHAR(500)`：最近一次驳回原因（REJECT 时回填；重报时清空）
   - 新唯一索引 `idx_approval_doc_id_uk`（UNIQUE on doc_id）：PL 块预检 `USER_INDEXES` + 建索引前先查重复 doc_id（`del_flag='0' GROUP BY doc_id HAVING COUNT(*)>1`），重复则 `RAISE_APPLICATION_ERROR` 中止；本次 devdm 勘查 0 条重复，正常建索引通过

3. **approval_history 表加 1 列**（实际基线 12 列 → V1.0.13 后 13 列）
   - `round INT`：该动作发生时的审批轮次（SUBMIT=1 / REJECT=1 / RESUBMIT=2 / APPROVE=2 等）

4. **新增菜单 7 项**（sys_menu 2050–2056，挂在 2010 科研管理下，order_num=5）
   - 2050 C 课题资料（path=document，component=biz/document/index，perms=biz:document:list，icon=documentation）
   - 2051 F 查询（biz:document:query）
   - 2052 F 上传（biz:document:add）
   - 2053 F 删除（biz:document:remove）
   - 2054 F 发起审批（biz:document:submit，发起+重报复用）
   - 2055 F 审批操作（biz:approval:audit）
   - 2056 F 审批历史（biz:approval:history）

5. **角色挂载（任务卡 §2.4 矩阵，共 31 条 `sys_role_menu`）**
   - admin(1) / science_admin(101)：全部 7 项（2050–2056）
   - dept_leader(104)：6 项（2050/2051/2052/2054/2055/2056，无 remove，本室审批）
   - researcher(105)：5 项（2050/2051/2052/2054/2056，本人相关，不可 remove/audit）
   - leader(100) / office(102) / labor_hr(103)：只读 2 项（2050+2051）
   - 合计 7+7+6+5+2+2+2 = 31 条（任务卡 §2.4 矩阵逐项实算）

**幂等性设计**：
- 列添加走 `ALTER TABLE ADD IF NOT EXISTS`（达梦支持，同 V1.0.6–V1.0.12）；`COMMENT ON COLUMN` 直接执行（重跑覆盖为同值，零副作用）
- 唯一/普通索引走 PL 匿名块预检 `USER_INDEXES` 后再 `CREATE [UNIQUE] INDEX`；唯一索引额外在预检通过后、建索引前用子查询查重复 doc_id，重复>0 则 `RAISE_APPLICATION_ERROR` 中止（本次 devdm 0 条重复，未触发）
- 菜单 / 角色菜单挂载走 `INSERT ... SELECT ... WHERE NOT EXISTS`
- **首次执行 50/50 成功；二次重跑 50/50 全绿零副作用**

**DB 复查**（`python .tmp/check_v1013_after.py`，全部 PASS）：
- project_document 表现有 15 列（实际基数 13 + 2），SUBMITTER_ID（BIGINT）/PLAN_SUBMIT_DATE（DATE）列存在且注释正确，`IDX_PROJECT_DOCUMENT_SUBMITTER` 存在
- approval 表现有 15 列（实际基数 13 + 2），ROUND（INT）/REJECT_REASON（VARCHAR 500）列存在，`IDX_APPROVAL_DOC_ID_UK` 存在且 UNIQUE on DOC_ID
- approval_history 表现有 13 列（实际基数 12 + 1），ROUND 列存在
- `sys_menu` 2050–2056 共 7 项，类型/父菜单/order_num/组件/权限/icon 全部对齐任务卡 §2.4
- `sys_role_menu` 7 角色挂载总数 = 31 条（§2.4 矩阵实算 7+7+6+5+2+2+2=31 一致）

**列数口径说明（与任务卡标注的差异）**：任务卡 §二 标注三表基线为 14/12/10 列、加列后 16/14/11 列；但 devdm 实际基线（V1.0.0 建表后 2026-08-11 统一补 `remark` 列）为 13/13/12 列，故本脚本按**增量 2+2+1 列**落地，加列后期望 15/15/13 列。列增量与任务卡完全一致，仅"前后总列数"随实际基数上浮；后端实体字段与任务卡 §二 对齐（submitter_id/plan_submit_date/round/reject_reason/round）。

**依赖**：
- V1.0.0：`project_document` / `approval` / `approval_history` 表
- V1.0.4：6 业务角色（100–105）
- V1.0.6：菜单 2010（科研管理目录）
- V1.0.1：`approval_status`（205 PENDING/APPROVED/REJECTED）、`project_stage`（201 INITIATION/MIDTERM/CLOSING/REVIEW）复用字典（本版本不新建）

**后续任务**：
- 阶段5 Task 2：后端资料侧（ProjectDocument 三件套 + 列表/上传/删除 + submit/resubmit + 数据权限双通道）
- 阶段5 Task 3：后端审批侧（Approval + ApprovalHistory 三件套 + 列表/history/audit + 数据权限）
- 阶段5 Task 4：前端（资料列表 + 上传/审批/历史弹窗，分支 feature/biz-document-ui）
- 阶段5 Task 5：冒烟（任务卡 §七 8 项）+ 回归

---

## V1.0.14 — 阶段7 荣誉管理基线

**日期**：2026-08-15

**任务卡关联**：阶段7 荣誉管理 / Task 1：V1.0.14 SQL（两表加 2+2 列 + ref_type 注释 + 字典 honor_ref_type + 菜单 2060-2066 + 角色挂载 29 条）+ devdm 幂等执行 + changelog

**变更内容**：

1. **honor 表加 2 列**（实际基线 13 列 → V1.0.14 后 15 列，幂等 PL 块预检 `USER_TAB_COLUMNS` 后 `EXECUTE IMMEDIATE ALTER TABLE ADD`）
   - `certificate_no VARCHAR(100)`：证书编号
   - `certificate_url VARCHAR(500)`：证书附件路径（`/common/upload` 相对路径）

2. **honor_relation 表加 2 列**（实际基线 10 列 → V1.0.14 后 12 列）
   - `role_desc VARCHAR(200)`：角色/名次说明（主持人、第 N 完成人等）
   - `contribution_desc VARCHAR(500)`：贡献说明（具体做了哪些工作）
   - **ref_type 列注释更新**（追加 UNIT 合作单位说明；COMMENT 天然幂等覆盖）：
     - 更新前：`关联类型（PROJECT课题/RESEARCHER科研人员...`（仅前两类，原 V1.0.0 注释）
     - 更新后：`关联类型（PROJECT课题/RESEARCHER科研人员(sys_user.user_id)/UNIT合作单位）`

3. **新增字典 `honor_ref_type`**（dict_id=231，dict_code 20139–20141，3 项，dict_value 大写）
   - `PROJECT` 课题（primary，sort 1）
   - `RESEARCHER` 人员（success，sort 2）
   - `UNIT` 合作单位（info，sort 3）
   - 复用 honor_level(207) / honor_type(208)，本版本不新建不修改

4. **新增菜单 7 项**（sys_menu 2060–2066，挂在 2010 科研管理下，order_num=6）
   - 2060 C 荣誉管理（path=honor，component=biz/honor/index，perms=biz:honor:list，icon=star）
   - 2061 F 查询（biz:honor:query）
   - 2062 F 新增（biz:honor:add）
   - 2063 F 修改（biz:honor:edit）
   - 2064 F 删除（biz:honor:remove）
   - 2065 F 导出（biz:honor:export）
   - 2066 F 关联维护（biz:honor:relation，按 ref_type=PROJECT/RESEARCHER/UNIT 维护 honor_relation 子资源）

5. **角色挂载（任务卡 §1 矩阵，共 29 条 `sys_role_menu`）**
   - admin(1) / science_admin(101)：全部 7 项（2060–2066）
   - leader(100) / office(102) / labor_hr(103) / dept_leader(104) / researcher(105)：只读 3 项（2060 + 2061 + 2065）
   - **合计 7 + 7 + 3 + 3 + 3 + 3 + 3 = 29 条**（任务卡 §1 矩阵逐项实算）

**幂等性设计**：
- 列添加走 PL 匿名块预检 `USER_TAB_COLUMNS`（达梦 `ALTER TABLE ADD` 无 `IF NOT EXISTS`，照 V1.0.13 PL 块风格）后 `EXECUTE IMMEDIATE 'ALTER TABLE ... ADD ...'`；`COMMENT ON COLUMN` 嵌入 PL 块内同样只首次执行；脚本末尾对 `honor_relation.ref_type` 的 `COMMENT ON COLUMN` 天然幂等覆盖
- 字典 / 菜单 / 角色菜单挂载走 `INSERT ... SELECT ... WHERE NOT EXISTS`（同 V1.0.6–V1.0.13）
- **首次执行 45/45 成功；二次重跑 45/45 全绿零副作用**

**DB 复查**（`python .tmp/check_v1014_after.py`，全部 PASS）：
- honor 表现有 15 列（实际基数 13 + 2），`CERTIFICATE_NO`(VARCHAR 100) / `CERTIFICATE_URL`(VARCHAR 500) 列存在且注释对齐任务卡 §1.1
- honor_relation 表现有 12 列（实际基数 10 + 2），`ROLE_DESC`(VARCHAR 200) / `CONTRIBUTION_DESC`(VARCHAR 500) 列存在且注释对齐任务卡 §1.2
- honor_relation.ref_type 注释 = `'关联类型（PROJECT课题/RESEARCHER科研人员(sys_user.user_id)/UNIT合作单位）'`，含 PROJECT/RESEARCHER/UNIT 三枚举
- `sys_dict_type` dict_id=231='荣誉关联类型'/honor_ref_type，status='0'
- `sys_dict_data` 20139–20141（3 项，PROJECT/RESEARCHER/UNIT 大写，list_class primary/success/info，dict_sort 1/2/3）
- `sys_menu` 2060–2066 共 7 项，类型/父菜单/order_num=6/组件/权限/名称/icon（含 2060='star'）/全部对齐任务卡
- `sys_role_menu` 7 角色挂载总数 = 29 条（§1 矩阵实算 7+7+3+3+3+3+3=29 一致）

**列数口径说明**：任务卡 §一 标注 honor/honor_relation 基线为 13/10 列、加列后 15/12 列；devdm 实际基线（V1.0.0 建表后 2026-08-11 统一补 `remark` 列）为 13/10 列，与任务卡标注一致；本脚本按任务卡**增量 2+2 列**落地，加列后期望 15/12 列。列增量与总列数均与任务卡完全对齐。

**依赖**：
- V1.0.0：`honor` / `honor_relation` 表（13 列 + 10 列）
- V1.0.1：`sys_dict_type` / `sys_dict_data` 框架表；`honor_level`(207) / `honor_type`(208) 复用字典
- V1.0.4：6 业务角色（100–105）
- V1.0.6：菜单 2010（科研管理目录）
- V1.0.9：`cooperative_unit` 表（20 列，含 unit_id 供 honor_relation REF_ID 在 ref_type=UNIT 时关联）

**后续任务**：
- 阶段7 Task 2：后端（Honor Domain + HonorRelation Domain + 列表/详情/CRUD + 证书附件上传 + 关联子资源按 ref_type 路由 PROJECT/RESEARCHER/UNIT + 数据权限双通道；前端仓 `star.svg` 图标已确认存在）
- 阶段7 Task 3：前端（views/biz/honor/index.vue + relationDialog.vue + api/biz/honor.js；分支 feature/biz-honor-ui）
- 阶段7 Task 4：冒烟（任务卡 §七 8 项：CRUD/证书上传/三种关联类型维护/级联逻辑删 honor_relation/数据权限/字典回显/回归）

---

## V1.0.15 — 阶段8 研发加计扣除基线

**日期**：2026-08-15

**任务卡关联**：阶段8 研发加计扣除 / Task 1：V1.0.15 SQL（rd_labor_allocation 加 5 列 + 3 非唯一索引 + 菜单 2070-2085 + 角色挂载 56 条）+ devdm 幂等执行 + changelog

**变更内容**：

1. **rd_labor_allocation 表加 5 列**（实际基线 15 列 → V1.0.15 后 20 列，幂等 PL 块预检 `USER_TAB_COLUMNS` 后 `EXECUTE IMMEDIATE ALTER TABLE ADD`，照 V1.0.14 PL 块风格）
   - `monthly_hours DECIMAL(8,2)`：月研发工时快照（按（researcher, month）聚合的当月研发工时，分摊批次写入时计算）
   - `hourly_rate DECIMAL(12,2)`：时薪快照（月薪÷174，展示口径，Task 2/3 工资与工时模块联动写入）
   - `surcharge_detail VARCHAR(2000)`：附加费逐项 JSON（`{"edu":金额,...}` 共 10 项，rate_code 为键；总和不存于本表，存 `surcharge_total`）
   - `confirm_by VARCHAR(64)`：确认人（分摊批次确认时的操作者 user_name/sys_user.user_name）
   - `confirm_time TIMESTAMP`：确认时间

2. **新增 3 个非唯一索引**（PL 块预检 `USER_INDEXES` 幂等创建）
   - `idx_rd_alloc_pm` ON `rd_labor_allocation(project_id, month)`：分摊批次按课题×月聚合查询
   - `idx_rd_worktime_daily_prd` ON `rd_worktime_daily(project_id, researcher_id, work_date)`：工时日表按"课题-人员-日"查询（D9 校验跨课题同日合计走此索引）
   - `idx_rd_salary_rm` ON `rd_researcher_salary(researcher_id, salary_month)`：工资表按"人员-月"查询（Task 2 工资月度 upsert/列表）

3. **新增菜单 14 项**（sys_menu 2070–2085，挂在 2010 科研管理下，C 菜单 order_num=7/8/9）
   - 2070 C 工时填报（path=rdworktime，component=biz/rd/worktime，perms=biz:rd:worktime:list，icon=time，order_num=7）
   - 2071 F 工时保存（biz:rd:worktime:save，order_num=1）
   - 2072 F 复制上月（biz:rd:worktime:copy，order_num=2）
   - 2075 C 工资与预算（path=rdsalary，component=biz/rd/salary，perms=biz:rd:salary:list，icon=money，order_num=8）
   - 2076 F 工资维护（biz:rd:salary:save，order_num=1）
   - 2077 F 工资导入（biz:rd:salary:import，order_num=2）
   - 2078 F 工资导出（biz:rd:salary:export，order_num=3）
   - 2079 F 预算维护（biz:rd:salary:budget，order_num=4）
   - 2080 C 分摊管理（path=rdallocation，component=biz/rd/allocation，perms=biz:rd:alloc:list，icon=chart，order_num=9）
   - 2081 F 分摊计算（biz:rd:alloc:calc，order_num=1）
   - 2082 F 批次确认（biz:rd:alloc:confirm，order_num=2）
   - 2083 F 撤销确认（biz:rd:alloc:revoke，order_num=3）
   - 2084 F 单课题导出（biz:rd:alloc:export，order_num=4）
   - 2085 F 多课题汇总导出（biz:rd:alloc:summary，order_num=5）
   - C 菜单 icon(time/money/chart)已确认存在于前端 svg 目录 `RuoYi-SpringBoot3-ElementPlus/src/assets/icons/svg/`，无需语义替换

4. **角色挂载（任务卡 §4 矩阵，共 56 条 `sys_role_menu`，逐项实算）**
   - admin(1) / science_admin(101) / leader(100)：各挂 14 项（全部 C+F，所领导有预算/工资/计算确认/汇总导出权）
   - dept_leader(104)：3 项（2070 本室工时查看 + 2080 分摊查看 + 2084 单课题导出）
   - researcher(105)：5 项（2070/2071/2072 本人填报 + 2080/2084 本人查看+导出）
   - office(102)：1 项（2080 行政查看）
   - labor_hr(103)：5 项（2070/2075/2078/2080/2084 工资可见可导+分摊只读）
   - **合计 14+14+14+3+5+1+5 = 56 条**

5. **不创建新表**：基线 5 张 rd_ 表（rd_labor_budget / rd_researcher_salary / rd_worktime_daily / rd_worktime_monthly / rd_labor_allocation）+ surcharge_rate 已在 V1.0.0 建立；本版本 DDL 变更仅 `rd_labor_allocation` 加列

6. **不修改 surcharge_rate 数据**：status 字面量 `'ACTIVE'`（不是 RuoYi 通用 `'0'`）为预期行为，Task 2/3 代码层直接使用字面量过滤；本版本不加字典映射

**幂等性设计**：
- 列添加走 PL 匿名块预检 `USER_TAB_COLUMNS`（达梦 `ALTER TABLE ADD` 无 `IF NOT EXISTS`，照 V1.0.14 PL 块风格）后 `EXECUTE IMMEDIATE 'ALTER TABLE ... ADD ...'`；`COMMENT ON COLUMN` 嵌入 PL 块内同样只首次执行
- 非唯一索引走 PL 匿名块预检 `USER_INDEXES` 后 `EXECUTE IMMEDIATE 'CREATE INDEX ...'`（达梦 `CREATE INDEX` 无 `IF NOT EXISTS`，照 V1.0.10/13 索引写法）
- 菜单 / 角色菜单挂载走 `INSERT ... SELECT ... WHERE NOT EXISTS`（同 V1.0.6–V1.0.14）
- **首次执行 78/78 成功；二次重跑 78/78 全绿零副作用**

**DB 复查**（`python .tmp/check_v1015_after.py`，全部 PASS）：
- rd_labor_allocation 表现有 20 列（实际基数 15 + 5），`MONTHLY_HOURS`(DECIMAL 8,2) / `HOURLY_RATE`(DECIMAL 12,2) / `SURCHARGE_DETAIL`(VARCHAR 2000) / `CONFIRM_BY`(VARCHAR 64) / `CONFIRM_TIME`(TIMESTAMP) 列存在且注释对齐任务卡 §1
- `IDX_RD_ALLOC_PM` 存在 on RD_LABOR_ALLOCATION(NONUNIQUE)；`IDX_RD_WORKTIME_DAILY_PRD` 存在 on RD_WORKTIME_DAILY(NONUNIQUE)；`IDX_RD_SALARY_RM` 存在 on RD_RESEARCHER_SALARY(NONUNIQUE)
- `sys_menu` 2070–2085 共 14 项，类型(C/F)/父菜单(2010/2070/2075/2080)/order_num(C=7/8/9，F=1-5)/组件/权限/icon(2070=time/2075=money/2080=chart)/路径(rdworktime/rdsalary/rdallocation)全部对齐任务卡 §3
- `sys_role_menu` 7 角色挂载总数 = 56 条（§4 矩阵实算 14+14+14+3+5+1+5=56 一致）
- `surcharge_rate` 未触碰：10 ACTIVE 行 SUM(rate_value)=0.4986 不变

**列数口径说明**：任务卡 §1 标注 `rd_labor_allocation` 基线 16 列、加列后 21 列；devdm 实际基线（V1.0.0 建表后 2026-08-11 统一补 `remark` 列后）为 15 列，故本脚本按任务卡**增量 5 列**落地，加列后期望 20 列。列增量与任务卡完全一致，仅"前后总列数"随实际基数下浮 1 列。

**依赖**：
- V1.0.0：`rd_labor_budget` / `rd_researcher_salary` / `rd_worktime_daily` / `rd_worktime_monthly` / `rd_labor_allocation`（5 张研发人工费基础表）+ `surcharge_rate`（10 项 ACTIVE 附加费比例）
- V1.0.4：6 业务角色（100–105）
- V1.0.6：菜单 2010（科研管理目录）

**后续任务**：
- 阶段8 Task 2：后端基础侧（5 Domain/Mapper + 预算/工资/工时，端点 1-10；`RdLaborAllocation` Domain 含本次新增 5 字段 + 非表字段 researcherName；任务卡已对齐 V1.0.0 列 + V1.0.15 新列）
- 阶段8 Task 3：后端业务侧（`RdWorktimeServiceImpl` 实现 D9 全套校验+月汇总重算、`RdSalaryServiceImpl` 工资月度 upsert+导入导出、`RdAllocationServiceImpl` 分摊批次计算+确认+撤销+JSON 写 `surcharge_detail`、6 Controller 拆分）
- 阶段8 Task 4：前端（`views/biz/rd/{worktime,salary,allocation}/index.vue` + 3 api + 6 dialog；icon 复用 `time.svg`/`money.svg`/`chart.svg`）
- 阶段8 Task 5：冒烟（任务卡 §七 12 项 + 回归）+ surcharge_rate 10 项 ACTIVE 比例透传校验

