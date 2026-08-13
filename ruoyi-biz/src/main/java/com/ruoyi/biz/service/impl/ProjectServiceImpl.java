package com.ruoyi.biz.service.impl;

import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.domain.ProjectMember;
import com.ruoyi.biz.mapper.ProjectMapper;
import com.ruoyi.biz.mapper.ProjectMemberMapper;
import com.ruoyi.biz.service.IProjectService;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final SysUserMapper sysUserMapper;

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
        return p;
    }

    // ========================================================
    //  新增（含 project_no 生成 + HOST 写入 + 唯一索引重试）
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
        if (project.getLeaderId() == null) {
            throw new ServiceException("主持人不能为空");
        }
        // 1. 校验主持人存在
        if (sysUserMapper.selectUserById(project.getLeaderId()) == null) {
            throw new ServiceException("主持人用户不存在");
        }
        // 2. 默认 dept_id 取主持人部门
        if (project.getDeptId() == null) {
            com.ruoyi.common.core.domain.entity.SysUser u = sysUserMapper.selectUserById(project.getLeaderId());
            project.setDeptId(u == null ? null : u.getDeptId());
        }
        // 3. 预算默认 0.00
        if (project.getBudgetTotal() == null) {
            project.setBudgetTotal(BigDecimal.ZERO);
        }
        if (project.getBudgetBalance() == null) {
            project.setBudgetBalance(project.getBudgetTotal());
        }
        project.setStatus(STATUS_DRAFT);
        project.setDelFlag("0");
        project.setCreateBy(operName);

        // 4. 生成 project_no（§3.3），唯一索引兜底：冲突时刷新 max 重试 1 次
        String year = new SimpleDateFormat("yyyy").format(new Date());
        String projectNo = generateProjectNo(year);
        project.setProjectNo(projectNo);

        // 5. INSERT（带重试）
        boolean inserted = false;
        for (int attempt = 0; attempt < 2 && !inserted; attempt++) {
            try {
                projectMapper.insert(project);
                inserted = true;
            } catch (DuplicateKeyException e) {
                log.warn("课题编号 {} 唯一冲突，第 {} 次重试", projectNo, attempt + 1);
                projectNo = generateProjectNo(year);
                project.setProjectNo(projectNo);
            }
        }
        if (!inserted) {
            throw new ServiceException("课题编号生成冲突，请稍后重试");
        }

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
            // 极端：主持人成员行已存在（一般不会）
            throw new ServiceException("主持人成员行写入冲突");
        }
        return project;
    }

    /**
     * 生成 KY-{yyyy}-{3位流水}。唯一索引冲突时调用方刷新 max 重试 1 次（§3.3）。
     */
    private String generateProjectNo(String year) {
        Long maxSeq = projectMapper.selectMaxSeqByYear(year);
        long next = (maxSeq == null ? 0L : maxSeq) + 1L;
        return String.format("KY-%s-%03d", year, next);
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
        Project db = projectMapper.selectProjectById(project.getProjectId());
        if (db == null) {
            throw new ServiceException("课题不存在");
        }
        if (STATUS_ARCHIVED.equals(db.getStatus())) {
            throw new ServiceException("已归档课题不可修改");
        }
        // 强制以库中原值为准：projectNo / leaderId / status 不允许修改
        project.setProjectNo(db.getProjectNo());
        project.setLeaderId(db.getLeaderId());
        project.setStatus(db.getStatus());
        if (project.getBudgetBalance() == null) {
            // 余额不通过此接口改（阶段4 维护）
            project.setBudgetBalance(db.getBudgetBalance());
        }
        project.setUpdateBy(operName);
        return projectMapper.updateById(project);
    }

    // ========================================================
    //  删除（逻辑删除；有有效成员时拒）
    // ========================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteProjectByIds(Long[] projectIds, String operName) {
        if (projectIds == null || projectIds.length == 0) {
            return 0;
        }
        for (Long pid : projectIds) {
            Project p = projectMapper.selectProjectById(pid);
            if (p == null) {
                throw new ServiceException("课题[" + pid + "]不存在或已删除");
            }
            if (STATUS_ARCHIVED.equals(p.getStatus())) {
                throw new ServiceException("已归档课题不可删除");
            }
            // 校验有效成员是否存在
            ProjectMember q = new ProjectMember();
            q.setProjectId(pid);
            List<ProjectMember> members = projectMemberMapper.selectMemberList(q);
            if (!members.isEmpty()) {
                throw new ServiceException("课题[" + p.getProjectName() + "]仍有有效成员，请先清空成员或迁移后再删");
            }
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

        // 校验课题是否存在 + 拒绝 HOST（换主持人必须走专用接口）
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
                throw new ServiceException("换主持人请用专用接口");
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
                    throw new ServiceException("不能删除课题[" + pid + "]的唯一 HOST，请先换主持人");
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
            throw new ServiceException("已归档课题不可换主持人");
        }
        selectProjectById(projectId);

        // 2. 取当前 HOST
        ProjectMember currentHost = projectMemberMapper.selectHostMember(projectId);
        if (currentHost == null) {
            throw new ServiceException("当前课题无 HOST，无法换主持人");
        }
        if (newLeaderUserId.equals(currentHost.getUserId())) {
            throw new ServiceException("新主持人已是当前 HOST，无需切换");
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
    //  私有工具
    // ========================================================

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
}