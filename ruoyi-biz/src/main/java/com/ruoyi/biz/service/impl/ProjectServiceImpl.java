package com.ruoyi.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.biz.domain.BudgetSplit;
import com.ruoyi.biz.domain.CooperativeUnit;
import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.domain.ProjectField;
import com.ruoyi.biz.domain.ProjectMember;
import com.ruoyi.biz.domain.ProjectUnit;
import com.ruoyi.biz.domain.ProjectUnitBudget;
import com.ruoyi.biz.domain.UserProfile;
import com.ruoyi.biz.domain.bo.ExternalMemberBo;
import com.ruoyi.biz.mapper.BudgetSplitMapper;
import com.ruoyi.biz.mapper.CooperativeUnitMapper;
import com.ruoyi.biz.mapper.ExpenseMapper;
import com.ruoyi.biz.mapper.ProjectFieldMapper;
import com.ruoyi.biz.mapper.ProjectMapper;
import com.ruoyi.biz.mapper.ProjectMemberMapper;
import com.ruoyi.biz.mapper.ProjectUnitBudgetMapper;
import com.ruoyi.biz.mapper.ProjectUnitMapper;
import com.ruoyi.biz.service.IProjectService;
import com.ruoyi.biz.service.IUserProfileService;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.mapper.SysDeptMapper;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.service.ISysDeptService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 课题 Service 实现
 *
 * @author kys
 * @date 2026-08-12
 */
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements IProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectServiceImpl.class);

    /** 角色 key 硬编码（与 V1.0.4 sys_role.role_key 一致） */
    private static final String ROLE_RESEARCHER = "researcher";

    /** 课题状态（与字典 project_status 一致） */
    private static final String STATUS_DRAFT     = "DRAFT";
    private static final String STATUS_ACTIVE    = "ACTIVE";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_ACCEPTED  = "ACCEPTED";
    private static final String STATUS_ARCHIVED  = "ARCHIVED";

    /** 成员角色（与字典 member_role 一致） */
    private static final String ROLE_HOST        = "HOST";
    private static final String ROLE_PARTICIPANT = "PARTICIPANT";
    private static final String ROLE_LIAISON     = "LIAISON";

    /** 关联单位合作类型（字典 cooperation_type） */
    private static final String ROLE_COLLABORATE = "COLLABORATE";

    /** 外部人员虚拟部门名（V1.0.20 预建，外单位人员账号统一挂此部门禁登录） */
    private static final String EXTERNAL_DEPT_NAME = "外部人员";

    /** 状态机迁移表（相邻单向；archive 走 archive 接口而非 changeStatus） */
    private static final Map<String, Set<String>> STATE_TRANSITIONS = new HashMap<String, Set<String>>() {{
        put(STATUS_DRAFT,     new HashSet<>(Collections.singletonList(STATUS_ACTIVE)));
        put(STATUS_ACTIVE,    new HashSet<>(Collections.singletonList(STATUS_COMPLETED)));
        put(STATUS_COMPLETED, new HashSet<>(Collections.singletonList(STATUS_ACCEPTED)));
        put(STATUS_ACCEPTED,  new HashSet<>(Collections.emptyList()));
        put(STATUS_ARCHIVED,  new HashSet<>(Collections.emptyList()));
    }};

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectFieldMapper projectFieldMapper;
    private final BudgetSplitMapper budgetSplitMapper;
    private final ProjectUnitBudgetMapper projectUnitBudgetMapper;
    private final ExpenseMapper expenseMapper;
    private final ProjectUnitMapper projectUnitMapper;
    private final CooperativeUnitMapper cooperativeUnitMapper;
    private final SysUserMapper sysUserMapper;
    private final SysDeptMapper sysDeptMapper;
    private final ISysDeptService sysDeptService;
    private final IUserProfileService userProfileService;
    private final BudgetSupport budgetSupport;

    // ========================================================
    //  列表 / 详情（数据范围）
    // ========================================================

    @Override
    public List<Project> selectProjectList(Project query) {
        if (query == null) {
            query = new Project();
        }
        // researcher (data_scope=5) 不走 @DataScope（注解对 dept 用户不友好），
        // Service 内按角色硬分支：走"本人相关"专用 SQL（leader_id=我 OR member 中包含我）
        if (isResearcher()) {
            Long me = SecurityUtils.getUserId();
            query.getParams().put("selfUserId", me);
            return projectMapper.selectProjectListForResearcher(query);
        }
        return projectMapper.selectProjectList(query);
    }

    @Override
    public Project selectProjectById(Long projectId) {
        if (projectId == null) {
            throw new ServiceException("projectId 不能为空");
        }
        Project p;
        if (isResearcher()) {
            Long me = SecurityUtils.getUserId();
            p = projectMapper.selectProjectScopedByIdForResearcher(projectId, me);
        } else {
            // 单参数 Project 承载 @DataScope 注入的 params.dataScope（science_admin/leader/office/dept_leader 走注解通道）
            Project q = new Project();
            q.setProjectId(projectId);
            p = projectMapper.selectProjectScopedById(q);
        }
        if (p == null) {
            // 不暴露是否存在信息，统一友好提示
            throw new ServiceException("无权访问");
        }
        // 字段冗余填充：详情页用字典翻译（list 已含字典文本，详情再算一次冗余但安全）
        p.setProjectTypeLabel(com.ruoyi.common.utils.DictUtils.getDictLabel("project_type", p.getProjectType()));
        p.setStatusLabel(com.ruoyi.common.utils.DictUtils.getDictLabel("project_status", p.getStatus()));
        // 一致性警告：project.leader_id 与 project_member.HOST.user_id 不一致时打日志（阶段2 不自动修复）
        if (p.getLeaderId() != null) {
            ProjectMember host = projectMemberMapper.selectHostMember(projectId);
            if (host != null && !p.getLeaderId().equals(host.getUserId())) {
                log.warn("课题[{}] leader_id={} 与成员 HOST user_id={} 不一致，以成员表为准（阶段2 仅记录）",
                        projectId, p.getLeaderId(), host.getUserId());
            }
        }
        // 预算细分（详情/编辑回显）
        p.setBudgetSplitList(budgetSplitMapper.selectByProjectId(projectId));
        // 研究领域（多选，V1.0.21）
        p.setFieldList(selectFieldCodes(projectId));
        // 按单位预算（V1.0.23，主持+参与单位各一套 10 科目）
        p.setUnitBudgetList(selectUnitBudgets(projectId));
        // 参与单位回填（V1.0.23，前端依赖 unitList 做编辑回显）
        p.setUnitList(projectUnitMapper.selectProjectUnitList(projectId));
        return p;
    }

    // ========================================================
    //  新增（project_no 人工输入校验 + HOST 写入 + 预算细分入库）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Project insertProject(Project project, String operName) {
        if (project == null) {
            throw new ServiceException("参数为空");
        }
        if (StringUtils.isEmpty(project.getProjectName())) {
            throw new ServiceException("课题名称不能为空");
        }
        if (StringUtils.isEmpty(project.getProjectType())) {
            throw new ServiceException("课题级别不能为空");
        }
        if (StringUtils.isEmpty(project.getProjectCategory())) {
            throw new ServiceException("项目类别不能为空");
        }
        if (StringUtils.isEmpty(project.getSpecialty())) {
            throw new ServiceException("专业分类不能为空");
        }
        if (project.getLeaderId() == null) {
            throw new ServiceException("组长不能为空");
        }
        // 主持标识（V1.0.23）：默认本单位主持；外单位主持 = 集团二级公司（sys_dept，不再选 cooperative_unit）
        if (StringUtils.isEmpty(project.getSelfHosted())) {
            project.setSelfHosted("1");
        }
        if ("0".equals(project.getSelfHosted())) {
            if (project.getHostUnitId() == null) {
                throw new ServiceException("外单位主持课题必须选择主持单位");
            }
            SysDept hostDept = sysDeptService.selectDeptById(project.getHostUnitId());
            if (hostDept == null || !"0".equals(hostDept.getDelFlag())) {
                throw new ServiceException("主持单位不存在");
            }
            Long groupRootId = findGroupRootDeptId();
            if (groupRootId == null || hostDept.getParentId() == null
                    || hostDept.getParentId().longValue() != groupRootId.longValue()) {
                throw new ServiceException("外单位主持课题必须选择集团二级公司作为主持单位");
            }
        } else {
            project.setSelfHosted("1");
            // 本单位主持：主持单位 = 当前用户所属二级公司 dept_id（沿 parent_id 链向上）；
            // 用户已在 root 或无明确二级公司时不置（保持 null）
            if (project.getHostUnitId() == null) {
                project.setHostUnitId(resolveSecondLevelDeptId(currentUserDeptId()));
            }
        }
        // 1. 校验组长存在
        if (sysUserMapper.selectUserById(project.getLeaderId()) == null) {
            throw new ServiceException("组长用户不存在");
        }
        // 2. 默认 dept_id 取组长部门
        if (project.getDeptId() == null) {
            com.ruoyi.common.core.domain.entity.SysUser u = sysUserMapper.selectUserById(project.getLeaderId());
            project.setDeptId(u == null ? null : u.getDeptId());
        }
        // 3. 课题编号：人工输入必填 + 查重（DB 唯一索引 idx_project_no_uk 兜底，含软删行）
        if (StringUtils.isEmpty(project.getProjectNo())) {
            throw new ServiceException("课题编号不能为空");
        }
        if (projectMapper.selectByProjectNo(project.getProjectNo()) != null) {
            throw new ServiceException("课题编号已存在");
        }
        // 4. 按单位预算聚合 → 项目级预算细分（每科目 = Σ 各单位该科目金额），预算总额 = Σ 各科目
        List<BudgetSplit> aggregated = aggregateUnitBudgets(project.getUnitBudgetList());
        BigDecimal budgetTotal = normalizeBudgetSplits(aggregated);
        project.setBudgetTotal(budgetTotal);
        if (project.getBudgetBalance() == null) {
            project.setBudgetBalance(budgetTotal);
        }
        project.setStatus(STATUS_DRAFT);
        project.setDelFlag("0");
        project.setCreateBy(operName);

        // 5. INSERT 课题主表（人工编号唯一性由 DB 唯一索引兜底）
        projectMapper.insert(project);

        // 6. 写入 HOST 成员行
        ProjectMember host = new ProjectMember();
        host.setProjectId(project.getProjectId());
        host.setUserId(project.getLeaderId());
        host.setRole(ROLE_HOST);
        host.setDelFlag("0");
        host.setCreateBy(operName);
        try {
            projectMemberMapper.insert(host);
        } catch (DuplicateKeyException e) {
            // 极端：组长成员行已存在（一般不会）
            throw new ServiceException("组长成员行写入冲突");
        }

        // 6.1 外单位主持课题：创建人自动成为联络人（承担系统录入职责；创建人=组长时跳过）
        if ("0".equals(project.getSelfHosted())) {
            Long creatorId = currentUserIdOrNull();
            if (creatorId != null && !creatorId.equals(project.getLeaderId())) {
                ProjectMember liaison = new ProjectMember();
                liaison.setProjectId(project.getProjectId());
                liaison.setUserId(creatorId);
                liaison.setRole(ROLE_LIAISON);
                liaison.setDelFlag("0");
                liaison.setCreateBy(operName);
                try {
                    projectMemberMapper.insert(liaison);
                } catch (DuplicateKeyException e) {
                    log.info("课题[{}] 创建人已是成员，跳过自动联络人", project.getProjectId());
                }
            }
        }

        // 7. 写入预算细分（与主表同事务；projectId 需等主表 insert 回填）
        //    走与编辑同一条增量通道（D1）：新建时库中无行，等价于逐行 INSERT（used=0/balance=budget/version=0），
        //    同时完成科目白名单+去重校验与 §4.4 监管上限校验，超限则整笔回滚。
        //    主表 budget_total/budget_balance 已在第 4 步按同一份清单算好（新建时 balance = budget），无需再写一次
        budgetSupport.applySplits(project.getProjectId(), aggregated, true, operName);

        // 8. 研究领域多选（V1.0.21）
        saveFields(project.getProjectId(), project.getFieldList(), operName);
        // 9. 按单位预算（V1.0.23）
        saveUnitBudgets(project.getProjectId(), project.getUnitBudgetList(), operName);
        // 10. 参与/协作单位随课题保存（V1.0.24 C3：全量替换 project_unit，add/edit 权限，
        //     不再依赖 biz:project:unit 的 addUnitBatch；unitList 为 null 时不动已有关联）
        saveUnitLinks(project.getProjectId(), project.getUnitList(), operName);
        return project;
    }

    // ========================================================
    //  修改（不允许改 leader_id/project_no/status；ARCHIVED 拒）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateProject(Project project, String operName) {
        if (project == null || project.getProjectId() == null) {
            throw new ServiceException("projectId 不能为空");
        }
        // 数据权限校验（项目级范围闸门，与成员端点一致；无权访问抛"无权访问"）
        selectProjectById(project.getProjectId());
        Project db = projectMapper.selectProjectById(project.getProjectId());
        if (db == null) {
            throw new ServiceException("课题不存在");
        }
        if (STATUS_ARCHIVED.equals(db.getStatus())) {
            throw new ServiceException("已归档课题不可修改");
        }
        // 强制以库中原值为准：projectNo / leaderId / status / 主持标识 不允许修改
        project.setProjectNo(db.getProjectNo());
        project.setLeaderId(db.getLeaderId());
        project.setStatus(db.getStatus());
        project.setSelfHosted(db.getSelfHosted());
        project.setHostUnitId(db.getHostUnitId());
        // 按单位预算聚合 → 项目级预算细分（决策 D1 / §4.5）：改金额保 split_id，绝不删旧插新，
        // 否则引入 expense.split_id 后历史流水会悬空；本次未传的科目金额置 0 但保留行（有流水的行删了会悬空）。
        // 请求体未带 unitBudgetList（null）则预算两列以库原值为准，忽略客户端传入值（保持 Σ 不变式）；传空列表表示全部置 0。
        if (project.getUnitBudgetList() != null) {
            List<BudgetSplit> aggregated = aggregateUnitBudgets(project.getUnitBudgetList());
            normalizeBudgetSplits(aggregated);
            // 全量终态语义：库中存在但本次未传的科目金额置 0（zeroMissing=true）
            BudgetSupport.Totals totals = budgetSupport.applySplits(
                    project.getProjectId(), aggregated, true, operName);
            // D3：两列均由 budget_split 派生，不接受前端直传
            project.setBudgetTotal(totals.getBudgetTotal());
            project.setBudgetBalance(totals.getBalanceTotal());
            // 按单位预算删旧写新（V1.0.23，仅请求体携带时更新）
            saveUnitBudgets(project.getProjectId(), project.getUnitBudgetList(), operName);
        } else {
            // 此分支不重跑监管上限校验（现状维持语义：用户未动预算）
            project.setBudgetTotal(db.getBudgetTotal());
            project.setBudgetBalance(db.getBudgetBalance());
        }
        // 参与/协作单位随课题保存（V1.0.24 C3：全量替换 project_unit；unitList 为 null 时不动已有关联，
        // 与预算 null 语义一致，避免旧客户端/缺权限端静默清空）
        saveUnitLinks(project.getProjectId(), project.getUnitList(), operName);
        project.setUpdateBy(operName);
        int n = projectMapper.updateById(project);
        // 研究领域多选（V1.0.21）：删旧写新（仅在请求体携带 fieldList 时更新）
        if (project.getFieldList() != null) {
            saveFields(project.getProjectId(), project.getFieldList(), operName);
        }
        return n;
    }

    // ========================================================
    //  删除（逻辑删除；级联逻辑删全部成员，含组长）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteProjectByIds(Long[] projectIds, String operName) {
        if (projectIds == null || projectIds.length == 0) {
            return 0;
        }
        for (Long pid : projectIds) {
            // 数据权限校验（项目级范围闸门，与其余变更操作一致；无权访问抛"无权访问"）
            selectProjectById(pid);
            Project p = projectMapper.selectProjectById(pid);
            if (p == null) {
                throw new ServiceException("课题[" + pid + "]不存在或已删除");
            }
            if (STATUS_ARCHIVED.equals(p.getStatus())) {
                throw new ServiceException("已归档课题不可删除");
            }
            // 级联逻辑删除全部有效成员（含组长），与课题删除同事务
            projectMemberMapper.softDeleteByProjectId(pid, operName);
            // 级联逻辑删除全部有效预算细分（任务卡 §九：删课题不级联 budget_split 挂账，本期落地）；
            // 经费流水 expense 的级联同事务一并落地（C-1 收口）
            budgetSplitMapper.softDeleteByProjectId(pid, operName);
            expenseMapper.softDeleteByProjectId(pid, operName);
            // 级联清理课题关联单位（V1.0.24 修复 B2：project_unit 逻辑删 del_flag='2'）与按单位预算
            // （project_unit_budget 物理删，与该表自身"删旧写新"生命周期一致）
            projectUnitMapper.softDeleteByProjectId(pid, operName);
            projectUnitBudgetMapper.deleteByProjectId(pid);
        }
        // BaseMapper.deleteByIds 走 @TableLogic 自动改写 del_flag='2'
        return projectMapper.deleteByIds(Arrays.asList(projectIds));
    }

    // ========================================================
    //  状态机迁移 / 归档
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int changeStatus(Long projectId, String targetStatus, String operName) {
        if (projectId == null || StringUtils.isEmpty(targetStatus)) {
            throw new ServiceException("参数不完整");
        }
        // 数据权限校验（项目级范围闸门，与成员端点一致；无权访问抛"无权访问"）
        selectProjectById(projectId);
        Project db = projectMapper.selectProjectById(projectId);
        if (db == null) {
            throw new ServiceException("课题不存在");
        }
        String current = db.getStatus();
        if (STATUS_ARCHIVED.equals(current)) {
            throw new ServiceException("已归档课题不可变更状态");
        }
        // §3.5 相邻单向校验
        Set<String> allowed = STATE_TRANSITIONS.get(current);
        if (allowed == null || !allowed.contains(targetStatus)) {
            throw new ServiceException("状态不允许从 " + current + " 变更为 " + targetStatus);
        }
        return projectMapper.updateStatus(projectId, targetStatus, operName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int archive(Long projectId, String operName) {
        if (projectId == null) {
            throw new ServiceException("projectId 不能为空");
        }
        Project db = projectMapper.selectProjectById(projectId);
        if (db == null) {
            throw new ServiceException("课题不存在");
        }
        if (!STATUS_COMPLETED.equals(db.getStatus()) && !STATUS_ACCEPTED.equals(db.getStatus())) {
            throw new ServiceException("仅 COMPLETED/ACCEPTED 可归档，当前状态：" + db.getStatus());
        }
        return projectMapper.updateStatus(projectId, STATUS_ARCHIVED, operName);
    }

    // ========================================================
    //  成员管理
    // ========================================================

    @Override
    @Transactional(readOnly = true)
    public List<ProjectMember> selectMemberList(Long projectId) {
        if (projectId == null) {
            throw new ServiceException("projectId 不能为空");
        }
        // 先校验当前用户对课题有数据权限（复用详情查询）
        selectProjectById(projectId);
        ProjectMember q = new ProjectMember();
        q.setProjectId(projectId);
        return projectMemberMapper.selectMemberList(q);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int addMembers(Long projectId, List<ProjectMember> members, String operName) {
        if (projectId == null || members == null || members.isEmpty()) {
            throw new ServiceException("参数不完整");
        }
        // 数据权限校验
        selectProjectById(projectId);

        // 校验课题是否存在 + 拒绝 HOST（换组长必须走专用接口）
        Project p = projectMapper.selectProjectById(projectId);
        if (p == null) {
            throw new ServiceException("课题不存在");
        }
        if (STATUS_ARCHIVED.equals(p.getStatus())) {
            throw new ServiceException("已归档课题不可新增成员");
        }
        // 是否有有效 HOST（仅用于校验）
        ProjectMember currentHost = projectMemberMapper.selectHostMember(projectId);

        // 批量有序：按 userId 升序
        List<ProjectMember> sorted = new java.util.ArrayList<>(members);
        sorted.sort((a, b) -> Long.compare(a.getUserId() == null ? 0 : a.getUserId(),
                                            b.getUserId() == null ? 0 : b.getUserId()));

        int n = 0;
        for (ProjectMember m : sorted) {
            if (m.getUserId() == null) {
                throw new ServiceException("成员 userId 不能为空");
            }
            // 拒 HOST：业务错误
            if (ROLE_HOST.equalsIgnoreCase(m.getRole())) {
                throw new ServiceException("换组长请用专用接口");
            }
            // 默认角色 PARTICIPANT
            if (StringUtils.isEmpty(m.getRole())) {
                m.setRole(ROLE_PARTICIPANT);
            }
            // 校验 sys_user 存在
            if (sysUserMapper.selectUserById(m.getUserId()) == null) {
                throw new ServiceException("用户[" + m.getUserId() + "]不存在");
            }
            // 校验重复（active + soft-deleted 都查）
            ProjectMember existing = projectMemberMapper.selectMemberByUser(projectId, m.getUserId());
            if (existing != null) {
                throw new ServiceException("用户[" + m.getUserId() + "]已是课题成员");
            }
            // 软删除记录是否已存在：UNIQUE(project_id, user_id) 索引仍约束物理冲突
            // 若存在 del_flag='2' 的物理行，需先恢复或物理删除，阶段2 简化：直接报业务错误（与上同义）
            m.setProjectId(projectId);
            m.setDelFlag("0");
            m.setCreateBy(operName);
            try {
                projectMemberMapper.insert(m);
                n++;
            } catch (DuplicateKeyException e) {
                throw new ServiceException("用户[" + m.getUserId() + "]已是课题成员");
            }
        }
        // 阶段2 不允许通过 addMembers 创建第二个 HOST；currentHost 已存在则仅插入 PARTICIPANT（上面已拒 HOST）
        if (currentHost != null) {
            log.info("课题[{}] 已有 HOST，本次新增 {} 个参与人", projectId, n);
        }
        return n;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeMembers(Long[] memberIds, String operName) {
        if (memberIds == null || memberIds.length == 0) {
            return 0;
        }
        // 每个 member 所属课题需通过数据权限
        Set<Long> projectIds = new HashSet<>();
        for (Long mid : memberIds) {
            Long pid = projectMemberMapper.selectProjectIdByMemberId(mid);
            if (pid == null) {
                throw new ServiceException("成员[" + mid + "]不存在或已删除");
            }
            projectIds.add(pid);
        }
        for (Long pid : projectIds) {
            selectProjectById(pid);  // 数据权限校验
            Project p = projectMapper.selectProjectById(pid);
            if (p != null && STATUS_ARCHIVED.equals(p.getStatus())) {
                throw new ServiceException("已归档课题不可删除成员");
            }
        }
        // 拒绝删除唯一 HOST：遍历 memberIds 涉及的每个 project，各自校验 HOST 是否落入待删列表
        // 不能 break：第一批跨多课题删除时，跳过第二课题的 HOST 校验会漏过
        for (Long pid : projectIds) {
            ProjectMember h = projectMemberMapper.selectHostMember(pid);
            if (h == null) {
                continue;  // 该课题无 HOST（理论上不会出现，仅防御）
            }
            for (Long mid : memberIds) {
                if (mid.equals(h.getMemberId())) {
                    throw new ServiceException("不能删除课题[" + pid + "]的唯一组长，请先换组长");
                }
            }
        }
        return projectMemberMapper.softDeleteByIds(memberIds, operName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int changeHost(Long projectId, Long newLeaderUserId, String operName) {
        if (projectId == null || newLeaderUserId == null) {
            throw new ServiceException("参数不完整");
        }
        // 1. 校验课题存在 + 数据权限
        Project p = projectMapper.selectProjectById(projectId);
        if (p == null) {
            throw new ServiceException("课题不存在");
        }
        if (STATUS_ARCHIVED.equals(p.getStatus())) {
            throw new ServiceException("已归档课题不可换组长");
        }
        selectProjectById(projectId);

        // 2. 取当前 HOST
        ProjectMember currentHost = projectMemberMapper.selectHostMember(projectId);
        if (currentHost == null) {
            throw new ServiceException("当前课题无 HOST，无法换组长");
        }
        if (newLeaderUserId.equals(currentHost.getUserId())) {
            throw new ServiceException("新组长已是当前 HOST，无需切换");
        }

        // 3. 新用户是否已是成员（任意 role + del_flag='0'）
        ProjectMember newMember = projectMemberMapper.selectMemberByUser(projectId, newLeaderUserId);
        if (newMember == null) {
            // 不是成员：先新增为 PARTICIPANT
            if (sysUserMapper.selectUserById(newLeaderUserId) == null) {
                throw new ServiceException("用户[" + newLeaderUserId + "]不存在");
            }
            ProjectMember pm = new ProjectMember();
            pm.setProjectId(projectId);
            pm.setUserId(newLeaderUserId);
            pm.setRole(ROLE_PARTICIPANT);
            pm.setDelFlag("0");
            pm.setCreateBy(operName);
            try {
                projectMemberMapper.insert(pm);
                newMember = projectMemberMapper.selectMemberByUser(projectId, newLeaderUserId);
            } catch (DuplicateKeyException e) {
                throw new ServiceException("用户[" + newLeaderUserId + "]已是课题成员");
            }
        }

        // 4. UPDATE 当前 HOST 行 role='PARTICIPANT'
        projectMemberMapper.updateRole(currentHost.getMemberId(), ROLE_PARTICIPANT, operName);

        // 5. UPDATE 新成员行 role='HOST'
        projectMemberMapper.updateRole(newMember.getMemberId(), ROLE_HOST, operName);

        // 6. UPDATE project.leader_id
        projectMapper.updateLeader(projectId, newLeaderUserId, operName);
        return 1;
    }

    // ========================================================
    //  导出
    // ========================================================

    @Override
    public List<Project> exportProject(Project query) {
        return selectProjectList(query);
    }

    // ========================================================
    //  合作单位关联（先过 scoped selectProjectById 闸门，现有模式）
    // ========================================================

    @Override
    @Transactional(readOnly = true)
    public List<ProjectUnit> selectProjectUnitList(Long projectId) {
        if (projectId == null) {
            throw new ServiceException("projectId 不能为空");
        }
        // 数据权限校验（项目级范围闸门，与成员端点一致）
        selectProjectById(projectId);
        return projectUnitMapper.selectProjectUnitList(projectId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int addProjectUnit(ProjectUnit projectUnit, String operName) {
        if (projectUnit == null || projectUnit.getProjectId() == null || projectUnit.getUnitId() == null) {
            throw new ServiceException("参数不完整");
        }
        // 数据权限校验（项目级范围闸门）
        selectProjectById(projectUnit.getProjectId());
        Project p = projectMapper.selectProjectById(projectUnit.getProjectId());
        if (p == null) {
            throw new ServiceException("课题不存在");
        }
        if (STATUS_ARCHIVED.equals(p.getStatus())) {
            throw new ServiceException("已归档课题不可新增关联单位");
        }
        // 合作类型默认参与（先定类型再分支存在性校验）
        if (StringUtils.isEmpty(projectUnit.getCooperationType())) {
            projectUnit.setCooperationType(ROLE_PARTICIPANT);  // 复用成员默认角色 PARTICPANT 语义，字典 cooperation_type 默认参与
        }
        // 双来源（V1.0.23）：协作单位走 cooperative_unit；参与/主持单位走集团二级公司 sys_dept
        if (ROLE_COLLABORATE.equalsIgnoreCase(projectUnit.getCooperationType())) {
            CooperativeUnit unit = cooperativeUnitMapper.selectUnitById(projectUnit.getUnitId());
            if (unit == null || !"0".equals(unit.getDelFlag())) {
                throw new ServiceException("单位不存在或已删除");
            }
        } else {
            SysDept dept = sysDeptService.selectDeptById(projectUnit.getUnitId());
            if (dept == null || !"0".equals(dept.getDelFlag())) {
                throw new ServiceException("二级公司不存在或已删除");
            }
            projectUnit.setDeptId(projectUnit.getUnitId());
        }
        // 同 project+unit 重复关联友好报错
        ProjectUnit existing = projectUnitMapper.selectByProjectAndUnit(projectUnit.getProjectId(), projectUnit.getUnitId());
        if (existing != null) {
            throw new ServiceException("该单位已关联此课题");
        }
        projectUnit.setDelFlag("0");  // 三层保险之一：Service 显式置
        projectUnit.setCreateBy(operName);
        return projectUnitMapper.insert(projectUnit);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int addProjectUnits(Long projectId, List<Long> unitIds, String cooperationType, java.math.BigDecimal allocatedAmount, String operName) {
        if (projectId == null || unitIds == null || unitIds.isEmpty()) {
            throw new ServiceException("请选择合作单位");
        }
        // 数据权限校验（闸门一次，与单条端点 addProjectUnit 一致）
        selectProjectById(projectId);
        Project p = projectMapper.selectProjectById(projectId);
        if (p == null) {
            throw new ServiceException("课题不存在");
        }
        if (STATUS_ARCHIVED.equals(p.getStatus())) {
            throw new ServiceException("已归档课题不可新增关联单位");
        }
        // cooperationType 为空默认 PARTICIPANT（与单条端点一致）
        String cType = StringUtils.isEmpty(cooperationType) ? ROLE_PARTICIPANT : cooperationType;
        boolean isCollaborate = ROLE_COLLABORATE.equalsIgnoreCase(cType);
        int inserted = 0;
        for (Long unitId : unitIds) {
            if (unitId == null) {
                throw new ServiceException("unitId 不能为空");
            }
            // 双来源存在性校验（有效行）；任一不存在则整体回滚
            if (isCollaborate) {
                CooperativeUnit unit = cooperativeUnitMapper.selectUnitById(unitId);
                if (unit == null || !"0".equals(unit.getDelFlag())) {
                    throw new ServiceException("单位[" + unitId + "]不存在或已删除");
                }
            } else {
                // 参与/主持单位：unit_id 存集团二级公司 dept_id，校验走 sys_dept
                SysDept dept = sysDeptService.selectDeptById(unitId);
                if (dept == null || !"0".equals(dept.getDelFlag())) {
                    throw new ServiceException("二级公司[" + unitId + "]不存在或已删除");
                }
            }
            // 已关联跳过不报错
            ProjectUnit existing = projectUnitMapper.selectByProjectAndUnit(projectId, unitId);
            if (existing != null) {
                continue;
            }
            ProjectUnit pu = new ProjectUnit();
            pu.setProjectId(projectId);
            pu.setUnitId(unitId);
            if (!isCollaborate) {
                pu.setDeptId(unitId);
            }
            pu.setCooperationType(cType);
            pu.setAllocatedAmount(allocatedAmount);
            pu.setDelFlag("0");
            pu.setCreateBy(operName);
            projectUnitMapper.insert(pu);
            inserted++;
        }
        return inserted;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeProjectUnits(Long[] ids, String operName) {
        if (ids == null || ids.length == 0) {
            return 0;
        }
        // 每个关联所属课题需通过数据权限
        Set<Long> projectIds = new HashSet<>();
        for (Long id : ids) {
            Long pid = projectUnitMapper.selectProjectIdById(id);
            if (pid == null) {
                throw new ServiceException("关联[" + id + "]不存在或已删除");
            }
            projectIds.add(pid);
        }
        for (Long pid : projectIds) {
            selectProjectById(pid);  // 数据权限校验
            Project p = projectMapper.selectProjectById(pid);
            if (p != null && STATUS_ARCHIVED.equals(p.getStatus())) {
                throw new ServiceException("已归档课题不可删除关联单位");
            }
        }
        return projectUnitMapper.softDeleteByIds(ids, operName);
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /**
     * 校验并归整预算细分：金额 ≥ 0（null 视为 0），返回 Σ 各科目金额。
     * 校验失败抛业务错误，整体回滚。
     */
    private BigDecimal normalizeBudgetSplits(List<BudgetSplit> splits) {
        BigDecimal total = BigDecimal.ZERO;
        if (splits == null) {
            return total;
        }
        for (BudgetSplit split : splits) {
            if (split == null) {
                continue;
            }
            if (split.getBudgetAmount() != null && split.getBudgetAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new ServiceException("预算科目金额不能为负数");
            }
            BigDecimal amt = split.getBudgetAmount() == null ? BigDecimal.ZERO : split.getBudgetAmount();
            split.setBudgetAmount(amt);
            total = total.add(amt);
        }
        return total;
    }

    /**
     * 当前登录用户是否「精确」为 researcher（数据范围 data_scope=5）。
     * 不能用 SecurityUtils.hasRole("researcher")：RuoYi 的 SUPER_ADMIN 捷径会让含 admin
     * 角色 key 的用户恒 true，导致 admin 被误路由到 researcher「本人相关」分支。
     * 精确策略：先判 admin 短路，再遍历角色列表逐条比对 role_key。
     */
    private boolean isResearcher() {
        try {
            List<SysRole> roles = SecurityUtils.getLoginUser().getUser().getRoles();
            if (roles == null || roles.isEmpty()) {
                return false;
            }
            boolean hasAdmin = false;
            boolean hasResearcher = false;
            for (SysRole r : roles) {
                if (r == null || StringUtils.isEmpty(r.getRoleKey())) {
                    continue;
                }
                if ("admin".equals(r.getRoleKey())) {
                    hasAdmin = true;
                }
                if (ROLE_RESEARCHER.equals(r.getRoleKey())) {
                    hasResearcher = true;
                }
            }
            // 含 admin 一律走全量分支；仅含 researcher 才走本人相关
            return hasResearcher && !hasAdmin;
        } catch (Exception e) {
            return false;
        }
    }

    /** 当前登录用户 ID（登录上下文必有；异常时返回 null 走安全分支） */
    private Long currentUserIdOrNull() {
        try {
            return SecurityUtils.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /** 查课题研究领域编码列表（V1.0.21） */
    private List<String> selectFieldCodes(Long projectId) {
        List<ProjectField> fields = projectFieldMapper.selectList(
                new LambdaQueryWrapper<ProjectField>().eq(ProjectField::getProjectId, projectId));
        List<String> codes = new java.util.ArrayList<>();
        if (fields != null) {
            for (ProjectField f : fields) {
                if (f != null && f.getFieldCode() != null) {
                    codes.add(f.getFieldCode());
                }
            }
        }
        return codes;
    }

    /** 保存课题研究领域（删旧写新；fieldList 为 null/空则清空，V1.0.21） */
    private void saveFields(Long projectId, List<String> fieldList, String operName) {
        projectFieldMapper.delete(new LambdaQueryWrapper<ProjectField>().eq(ProjectField::getProjectId, projectId));
        if (fieldList == null || fieldList.isEmpty()) {
            return;
        }
        for (String code : fieldList) {
            if (code == null || code.trim().isEmpty()) {
                continue;
            }
            ProjectField f = new ProjectField();
            f.setProjectId(projectId);
            f.setFieldCode(code.trim());
            f.setCreateBy(operName);
            projectFieldMapper.insert(f);
        }
    }

    /**
     * 按单位预算聚合 → 项目级预算细分（每科目 = Σ 各单位该科目金额；单位预算为零科目的单位行不产生细分）。
     * 记账事实来源仍是 budget_split（项目级），本方法只是把前端「按单位」录入转成「项目级」清单。
     */
    private List<BudgetSplit> aggregateUnitBudgets(List<ProjectUnitBudget> unitList) {
        Map<String, BigDecimal> m = new HashMap<>();
        if (unitList != null) {
            for (ProjectUnitBudget u : unitList) {
                if (u != null && u.getCategory() != null) {
                    m.merge(u.getCategory(), nz(u.getBudgetAmount()), BigDecimal::add);
                }
            }
        }
        List<BudgetSplit> out = new java.util.ArrayList<>();
        m.forEach((c, amt) -> {
            BudgetSplit s = new BudgetSplit();
            s.setCategory(c);
            s.setBudgetAmount(BudgetSupport.scale(amt));
            out.add(s);
        });
        return out;
    }

    /** 查课题按单位预算列表（V1.0.23） */
    private List<ProjectUnitBudget> selectUnitBudgets(Long projectId) {
        return projectUnitBudgetMapper.selectByProjectId(projectId);
    }

    /** 保存课题按单位预算（物理删旧 + 重插；category/deptId 为空过滤，V1.0.23） */
    private void saveUnitBudgets(Long projectId, List<ProjectUnitBudget> unitBudgetList, String operName) {
        projectUnitBudgetMapper.deleteByProjectId(projectId);
        if (unitBudgetList == null || unitBudgetList.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (ProjectUnitBudget u : unitBudgetList) {
            if (u == null || u.getDeptId() == null || u.getCategory() == null || u.getCategory().trim().isEmpty()) {
                continue;
            }
            String key = u.getDeptId() + ":" + u.getCategory();
            if (!seen.add(key)) {
                continue;  // 同一单位同一科目重复行跳过（唯一索引 idx_pub_pdc 兜底）
            }
            u.setProjectId(projectId);
            u.setBudgetAmount(BudgetSupport.scale(nz(u.getBudgetAmount())));
            u.setDelFlag("0");
            u.setCreateBy(operName);
            projectUnitBudgetMapper.insert(u);
        }
    }

    /**
     * 参与/协作单位随课题保存（V1.0.24 C3）：全量替换 project_unit。
     * 先物理删除该课题全部 project_unit 行再逐行 insert（del_flag='0'）。
     * 双来源约定（与 addProjectUnit/addProjectUnits 一致）：
     * 参与单位（cooperation_type=PARTICIPANT 或空）：unit_id=dept_id=集团二级公司 dept_id，校验走 sys_dept；
     * 协作单位（COLLABORATE）：unit_id=cooperative_unit.unit_id，dept_id=null，校验走 cooperative_unit。
     * 任一单位不存在抛业务错误整体回滚；unitList 为 null 时不动已有关联（与预算 null 语义一致），传空列表 = 清空全部。
     * allocated_amount 本期不再单独传（插入走列 DEFAULT 0），后续如需经费划分再扩展。
     */
    private void saveUnitLinks(Long projectId, List<ProjectUnit> unitList, String operName) {
        if (projectId == null || unitList == null) {
            return;
        }
        projectUnitMapper.deleteByProjectId(projectId);
        if (unitList.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (ProjectUnit u : unitList) {
            if (u == null || u.getUnitId() == null) {
                continue;  // 过滤 null/空行
            }
            String cType = StringUtils.isEmpty(u.getCooperationType()) ? ROLE_PARTICIPANT : u.getCooperationType();
            boolean isCollaborate = ROLE_COLLABORATE.equalsIgnoreCase(cType);
            if (isCollaborate) {
                CooperativeUnit unit = cooperativeUnitMapper.selectUnitById(u.getUnitId());
                if (unit == null || !"0".equals(unit.getDelFlag())) {
                    throw new ServiceException("协作单位[" + u.getUnitId() + "]不存在或已删除");
                }
            } else {
                SysDept dept = sysDeptService.selectDeptById(u.getUnitId());
                if (dept == null || !"0".equals(dept.getDelFlag())) {
                    throw new ServiceException("二级公司[" + u.getUnitId() + "]不存在或已删除");
                }
            }
            if (!seen.add(u.getUnitId() + ":" + cType)) {
                continue;  // 同一单位同一类型重复行跳过
            }
            ProjectUnit pu = new ProjectUnit();
            pu.setProjectId(projectId);
            pu.setUnitId(u.getUnitId());
            if (!isCollaborate) {
                pu.setDeptId(u.getUnitId());
            }
            pu.setCooperationType(cType);
            pu.setDelFlag("0");
            pu.setCreateBy(operName);
            projectUnitMapper.insert(pu);
        }
    }

    /** 集团 root（parent_id=0 的顶层节点，总公司） */
    private Long findGroupRootDeptId() {
        List<SysDept> depts = sysDeptMapper.selectDeptList(new SysDept());
        if (depts != null) {
            for (SysDept d : depts) {
                if (d != null && d.getParentId() != null && d.getParentId().longValue() == 0L) {
                    return d.getDeptId();
                }
            }
        }
        return null;
    }

    /**
     * 沿 sys_dept.parent_id 链向上找「集团二级公司」：父节点 = 集团 root 的节点。
     * 链断裂 / 已在 root / 不可达时返回 null。
     */
    private Long resolveSecondLevelDeptId(Long deptId) {
        if (deptId == null) {
            return null;
        }
        Long rootId = findGroupRootDeptId();
        if (rootId == null) {
            return null;
        }
        Long current = deptId;
        Set<Long> visited = new HashSet<>();
        while (current != null && visited.add(current)) {
            SysDept d = sysDeptService.selectDeptById(current);
            if (d == null) {
                return null;
            }
            if (d.getParentId() != null && d.getParentId().longValue() == rootId.longValue()) {
                return d.getDeptId();
            }
            current = d.getParentId();
        }
        return null;
    }

    /** 当前登录用户所属部门 ID（异常返回 null 走安全分支） */
    private Long currentUserDeptId() {
        try {
            SysUser u = SecurityUtils.getLoginUser().getUser();
            return u == null ? null : u.getDeptId();
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    // ========================================================
    //  外单位人员录入（V1.0.20：联络人维护外部人员）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createExternalMember(ExternalMemberBo bo, String operName) {
        if (bo == null || StringUtils.isEmpty(bo.getNickName())) {
            throw new ServiceException("外单位人员姓名不能为空");
        }
        // 1. 定位"外部人员"虚拟部门
        SysDept extDept = null;
        SysDept q = new SysDept();
        List<SysDept> depts = sysDeptMapper.selectDeptList(q);
        if (depts != null) {
            for (SysDept d : depts) {
                if (EXTERNAL_DEPT_NAME.equals(d.getDeptName()) && "0".equals(d.getDelFlag())) {
                    extDept = d;
                    break;
                }
            }
        }
        if (extDept == null) {
            throw new ServiceException("外部人员虚拟部门未配置，请先执行 V1.0.20 迁移");
        }
        // 2. 生成禁登录账号（EXT + 时间戳 + 随机后缀，不参与登录）
        String userName = "EXT" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        SysUser user = new SysUser();
        user.setDeptId(extDept.getDeptId());
        user.setUserName(userName);
        user.setNickName(bo.getNickName().trim());
        user.setPhonenumber(bo.getPhonenumber());
        user.setStatus("1"); // 停用 = 禁止登录
        user.setDelFlag("0");
        user.setPassword(SecurityUtils.encryptPassword(UUID.randomUUID().toString()));
        user.setRemark(bo.getUnitName());
        user.setCreateBy(operName);
        sysUserMapper.insertUser(user);
        Long userId = user.getUserId();

        // 3. 可选建档（职称/学历/学位/专业/研究方向有任一值才建档）
        if (StringUtils.isNotEmpty(bo.getTitleLevel()) || StringUtils.isNotEmpty(bo.getEduLevel())
                || StringUtils.isNotEmpty(bo.getDegree()) || StringUtils.isNotEmpty(bo.getMajor())
                || StringUtils.isNotEmpty(bo.getResearchDirection())) {
            UserProfile profile = new UserProfile();
            profile.setUserId(userId);
            profile.setTitleLevel(bo.getTitleLevel());
            profile.setEduLevel(bo.getEduLevel());
            profile.setDegree(bo.getDegree());
            profile.setMajor(bo.getMajor());
            profile.setResearchDirection(bo.getResearchDirection());
            profile.setDelFlag("0");
            profile.setCreateBy(operName);
            userProfileService.insertUserProfile(profile);
        }
        return userId;
    }
}