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
