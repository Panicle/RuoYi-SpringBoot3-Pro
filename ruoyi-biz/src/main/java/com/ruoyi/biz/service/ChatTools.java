package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.Approval;
import com.ruoyi.biz.domain.Contract;
import com.ruoyi.biz.domain.CooperativeUnit;
import com.ruoyi.biz.domain.Honor;
import com.ruoyi.biz.domain.Notification;
import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.domain.ProjectMember;
import com.ruoyi.biz.domain.RdWorktimeMonthly;
import com.ruoyi.biz.domain.UserProfile;
import com.ruoyi.biz.domain.vo.ConfirmCard;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话精灵 6 工具函数（任务卡 §三：query_project / query_budget / create_expense(写,需确认) /
 * query_approval / query_alert / query_worktime）
 *
 * <p>所有工具方法返回简洁文本给 LLM（不过度序列化）；数据权限复用各 Service 的 scoped/@DataScope
 * 通道，不裸查 Mapper；create_expense 是写操作，只生成确认卡片（Redis TTL 5min），
 * 用户 POST /biz/chat/confirm approved=true 才落库。</p>
 *
 * <p>工具在 ChatClient 阻塞调用（request 线程）内执行，SecurityUtils ThreadLocal 可用；
 * create_expense 通过 {@link ToolContext} 接收 ChatService 传入的确认卡片持有器（ConfirmCard[]）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Component
@RequiredArgsConstructor
public class ChatTools {

    /** 各查询工具返回行数上限（避免 LLM 上下文过长） */
    private static final int MAX_ROWS = 8;

    private final IProjectService projectService;
    private final IApprovalService approvalService;
    private final INotificationService notificationService;
    private final IRdWorktimeService rdWorktimeService;
    private final IUserProfileService userProfileService;
    private final IContractService contractService;
    private final IHonorService honorService;
    private final ICooperativeUnitService cooperativeUnitService;
    private final ChatConfirmService chatConfirmService;
    private final ChatReportService chatReportService;

    // ========================================================
    //  查询工具
    // ========================================================

    /**
     * 按课题名称/编号查询课题（数据权限双通道：researcher 本人相关 / 其余 @DataScope）
     */
    @Tool(description = "按课题名称关键词或课题编号查询课题，返回课题列表（编号/名称/组长/预算总额/余额/状态）。"
            + "参数 keyword 可为课题名称的一部分或完整课题编号。")
    public String queryProject(
            @ToolParam(description = "课题名称关键词或课题编号") String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return "请提供课题名称或编号关键词";
        }
        String kw = keyword.trim();
        List<Project> list = new ArrayList<>();
        // 先按编号精确匹配
        Project byNo = new Project();
        byNo.setProjectNo(kw);
        list.addAll(projectService.selectProjectList(byNo));
        // 再按名称模糊匹配
        Project byName = new Project();
        byName.setProjectName(kw);
        for (Project p : projectService.selectProjectList(byName)) {
            if (!containsId(list, p.getProjectId())) {
                list.add(p);
            }
        }
        if (list.isEmpty()) {
            return "未找到课题[" + kw + "]";
        }
        StringBuilder sb = new StringBuilder("找到课题 " + list.size() + " 条（展示前 " + Math.min(list.size(), MAX_ROWS) + " 条）：");
        int n = 0;
        for (Project p : list) {
            if (n++ >= MAX_ROWS) {
                break;
            }
            sb.append("\n").append(n).append(". ID ").append(p.getProjectId())
              .append(" | ").append(safe(p.getProjectNo())).append(" | ")
              .append(safe(p.getProjectName())).append(" | 组长：").append(safe(p.getLeaderName()))
              .append(" | 预算总额：").append(p.getBudgetTotal() == null ? "-" : p.getBudgetTotal().toPlainString())
              .append(" | 余额：").append(p.getBudgetBalance() == null ? "-" : p.getBudgetBalance().toPlainString())
              .append(" | 状态：").append(safe(p.getStatus()));
        }
        return sb.toString();
    }

    /**
     * 查询课题成员名单（selectMemberList 内含 scoped 闸门，无权课题直接抛异常）
     */
    @Tool(description = "按课题ID查询课题成员名单（返回姓名/登录账号/部门/角色），参数 projectId=课题ID。"
            + "用户只给课题名称或编号时，先用 query_project 查到课题ID再调用本工具。")
    public String queryProjectMember(
            @ToolParam(description = "课题ID") Long projectId) {
        if (projectId == null) {
            return "请提供课题ID";
        }
        List<ProjectMember> members;
        try {
            members = projectService.selectMemberList(projectId);
        } catch (ServiceException e) {
            return "无权查看该课题或课题不存在";
        }
        if (members == null || members.isEmpty()) {
            return "该课题暂无成员";
        }
        StringBuilder sb = new StringBuilder("课题成员 " + members.size() + " 人：");
        int n = 0;
        for (ProjectMember m : members) {
            if (n++ >= MAX_ROWS) {
                sb.append("\n…（仅展示前 " + MAX_ROWS + " 人）");
                break;
            }
            sb.append("\n").append(n).append(". ").append(safe(m.getNickName()))
              .append("（").append(safe(m.getUserName())).append("）")
              .append(" | 部门：").append(safe(m.getDeptName()))
              .append(" | 角色：").append("HOST".equals(m.getRole()) ? "组长" : "成员");
        }
        return sb.toString();
    }

    /**
     * 查询课题经费余额（project.budget_balance / budget_total；过 scoped 闸门）
     */
    @Tool(description = "按课题ID查询经费余额与预算总额（预算余额/预算总额），参数 projectId 为课题ID。")
    public String queryBudget(
            @ToolParam(description = "课题ID") Long projectId) {
        if (projectId == null) {
            return "请提供课题ID";
        }
        try {
            Project p = projectService.selectProjectById(projectId);
            return "课题[" + safe(p.getProjectNo()) + " " + safe(p.getProjectName())
                    + "] 预算总额 " + val(p.getBudgetTotal()) + " 元，余额 "
                    + val(p.getBudgetBalance()) + " 元，状态 " + safe(p.getStatus());
        } catch (ServiceException e) {
            return "无权查看该课题或课题不存在";
        }
    }

    /**
     * 查询审批状态（approval 表；数据权限：researcher 本人申请 / dept_leader 本室 / 其余全量）
     */
    @Tool(description = "按可选状态查询审批记录列表（返回审批ID/关联资料/申请人/状态/意见），"
            + "参数 status 可省略（省略查全部），取值：PENDING/APPROVED/REJECTED。")
    public String queryApproval(
            @ToolParam(required = false, description = "审批状态（PENDING/APPROVED/REJECTED），可省略") String status) {
        Approval query = new Approval();
        if (status != null && !status.trim().isEmpty()) {
            query.setStatus(status.trim().toUpperCase());
        }
        List<Approval> list = approvalService.selectApprovalList(query);
        if (list == null || list.isEmpty()) {
            return "暂无审批记录";
        }
        StringBuilder sb = new StringBuilder("审批记录 " + list.size() + " 条（展示前 " + Math.min(list.size(), MAX_ROWS) + " 条）：");
        int n = 0;
        for (Approval a : list) {
            if (n++ >= MAX_ROWS) {
                break;
            }
            sb.append("\n").append(n).append(". 审批ID ").append(a.getApprovalId())
              .append(" | 课题：").append(safe(a.getProjectName()))
              .append(" | 资料：").append(safe(a.getDocFileName()))
              .append(" | 状态：").append(safe(a.getStatus()))
              .append(" | 意见：").append(safe(a.getCommentText()));
        }
        return sb.toString();
    }

    /**
     * 查询我的未读预警通知（receiver_id=me 硬过滤；数据权限 D10）
     */
    @Tool(description = "查询当前用户的未读预警通知列表（返回通知ID/预警标题/类型/级别/内容），无参数。")
    public String queryAlert() {
        Notification query = new Notification();
        query.setStatus("UNREAD");
        List<Notification> list = notificationService.selectMyNotificationList(query);
        if (list == null || list.isEmpty()) {
            return "暂无未读预警";
        }
        StringBuilder sb = new StringBuilder("未读预警 " + list.size() + " 条（展示前 " + Math.min(list.size(), MAX_ROWS) + " 条）：");
        int n = 0;
        for (Notification no : list) {
            if (n++ >= MAX_ROWS) {
                break;
            }
            sb.append("\n").append(n).append(". ").append(safe(no.getAlertTitle()))
              .append(" | 类型：").append(safe(no.getAlertType()))
              .append(" | 级别：").append(safe(no.getAlertLevel()));
        }
        return sb.toString();
    }

    /**
     * 查询研发工时填报（rd_worktime_monthly；数据权限三档 D10，researcher 仅本人）
     */
    @Tool(description = "查询研发工时月度汇总（返回课题/研发人员/月份/当月工时/累计工时），"
            + "参数 month 格式 YYYY-MM 可省略（省略查全部），researcherId 可省略（省略查当前用户可见范围）。")
    public String queryWorktime(
            @ToolParam(required = false, description = "月份，格式 YYYY-MM，可省略") String month,
            @ToolParam(required = false, description = "研发人员ID，可省略") Long researcherId) {
        RdWorktimeMonthly query = new RdWorktimeMonthly();
        if (month != null && !month.trim().isEmpty()) {
            query.setMonth(month.trim());
        }
        if (researcherId != null) {
            query.setResearcherId(researcherId);
        }
        List<RdWorktimeMonthly> list = rdWorktimeService.selectMonthlyList(query);
        if (list == null || list.isEmpty()) {
            return "暂无工时填报记录";
        }
        StringBuilder sb = new StringBuilder("工时填报 " + list.size() + " 条（展示前 " + Math.min(list.size(), MAX_ROWS) + " 条）：");
        int n = 0;
        for (RdWorktimeMonthly w : list) {
            if (n++ >= MAX_ROWS) {
                break;
            }
            sb.append("\n").append(n).append(". 课题：").append(safe(w.getProjectName()))
              .append(" | 人员：").append(safe(w.getResearcherName()))
              .append(" | 月份：").append(safe(w.getMonth()))
              .append(" | 当月工时：").append(w.getTotalRdHours() == null ? "-" : w.getTotalRdHours().toPlainString())
              .append(" | 累计：").append(w.getCumulativeHours() == null ? "-" : w.getCumulativeHours().toPlainString());
        }
        return sb.toString();
    }

    /**
     * 查询人员信息（sys_user 主表 + 科研档案 LEFT JOIN；@DataScope 三档：
     * data_scope=1 全部 / dept_leader 本部门 / researcher 仅本人）
     */
    @Tool(description = "查询人员信息（返回姓名/登录账号/部门/职称/学历/学位/专业/研究方向/联系方式），"
            + "参数 keyword 为姓名或登录账号关键词，可省略（省略查当前用户数据范围内全部人员）。")
    public String queryUser(
            @ToolParam(required = false, description = "姓名或登录账号关键词，可省略") String keyword) {
        UserProfile query = new UserProfile();
        if (keyword != null && !keyword.trim().isEmpty()) {
            query.setNickName(keyword.trim());
        }
        List<UserProfile> list = userProfileService.selectChatUserList(query);
        if (list == null || list.isEmpty()) {
            return keyword == null || keyword.trim().isEmpty()
                    ? "数据范围内暂无人员" : "未找到人员[" + keyword.trim() + "]";
        }
        StringBuilder sb = new StringBuilder("人员 " + list.size() + " 名（展示前 " + Math.min(list.size(), MAX_ROWS) + " 名）：");
        int n = 0;
        for (UserProfile u : list) {
            if (n++ >= MAX_ROWS) {
                break;
            }
            sb.append("\n").append(n).append(". ").append(safe(u.getNickName()))
              .append("（").append(safe(u.getUserName())).append("）")
              .append(" | 部门：").append(safe(u.getDeptName()))
              .append(" | 职称：").append(safe(u.getTitleLevel()))
              .append(" | 学历：").append(safe(u.getEduLevel()))
              .append(" | 学位：").append(safe(u.getDegree()))
              .append(" | 专业：").append(safe(u.getMajor()))
              .append(" | 研究方向：").append(safe(u.getResearchDirection()))
              .append(" | 电话：").append(safe(firstNonEmpty(u.getPhonenumber(), u.getOfficePhone())));
        }
        return sb.toString();
    }

    /**
     * 查询合同（researcher 本人相关 / 其余全量，Service 内双通道）
     */
    @Tool(description = "按合同名称或编号关键词查询合同列表（返回合同编号/名称/关联课题/对方单位/金额/签订日期/到期日期/状态），"
            + "参数 keyword 可省略（省略查当前用户可见范围内全部合同）。")
    public String queryContract(
            @ToolParam(required = false, description = "合同名称或编号关键词，可省略") String keyword) {
        Contract query = new Contract();
        if (keyword != null && !keyword.trim().isEmpty()) {
            query.setContractName(keyword.trim());
        }
        List<Contract> list = contractService.selectContractList(query);
        if ((list == null || list.isEmpty()) && keyword != null && !keyword.trim().isEmpty()) {
            // 名称没中 → 按编号再试一次
            Contract byNo = new Contract();
            byNo.setContractNo(keyword.trim());
            list = contractService.selectContractList(byNo);
        }
        if (list == null || list.isEmpty()) {
            return keyword == null || keyword.trim().isEmpty() ? "暂无合同" : "未找到合同[" + keyword.trim() + "]";
        }
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
        StringBuilder sb = new StringBuilder("合同 " + list.size() + " 份（展示前 " + Math.min(list.size(), MAX_ROWS) + " 份）：");
        int n = 0;
        for (Contract c : list) {
            if (n++ >= MAX_ROWS) {
                break;
            }
            sb.append("\n").append(n).append(". ").append(safe(c.getContractNo()))
              .append(" | ").append(safe(c.getContractName()))
              .append(" | 课题：").append(safe(c.getProjectName()))
              .append(" | 对方单位：").append(safe(c.getPartyName() != null ? c.getPartyName() : c.getPartyUnitName()))
              .append(" | 金额：").append(c.getAmount() == null ? "-" : c.getAmount().toPlainString())
              .append(" | 签订：").append(c.getSignDate() == null ? "-" : sdf.format(c.getSignDate()))
              .append(" | 到期：").append(c.getExpireDate() == null ? "-" : sdf.format(c.getExpireDate()))
              .append(" | 状态：").append(safe(c.getStatus()));
        }
        return sb.toString();
    }

    /**
     * 查询荣誉（researcher 本人相关+本人录入 / 其余全量，Service 内双通道）
     */
    @Tool(description = "按荣誉名称关键词查询荣誉列表（返回荣誉名称/类型/级别/授予单位/获奖日期/证书编号），"
            + "参数 keyword 可省略（省略查当前用户可见范围内全部荣誉）。")
    public String queryHonor(
            @ToolParam(required = false, description = "荣誉名称关键词，可省略") String keyword) {
        Honor query = new Honor();
        if (keyword != null && !keyword.trim().isEmpty()) {
            query.setHonorName(keyword.trim());
        }
        List<Honor> list = honorService.selectHonorList(query);
        if (list == null || list.isEmpty()) {
            return keyword == null || keyword.trim().isEmpty() ? "暂无荣誉记录" : "未找到荣誉[" + keyword.trim() + "]";
        }
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
        StringBuilder sb = new StringBuilder("荣誉 " + list.size() + " 项（展示前 " + Math.min(list.size(), MAX_ROWS) + " 项）：");
        int n = 0;
        for (Honor h : list) {
            if (n++ >= MAX_ROWS) {
                break;
            }
            sb.append("\n").append(n).append(". ").append(safe(h.getHonorName()))
              .append(" | 类型：").append(safe(h.getHonorType()))
              .append(" | 级别：").append(safe(h.getAwardLevel()))
              .append(" | 授予单位：").append(safe(h.getAwardOrg()))
              .append(" | 获奖日期：").append(h.getAwardDate() == null ? "-" : sdf.format(h.getAwardDate()))
              .append(" | 证书编号：").append(safe(h.getCertificateNo()));
        }
        return sb.toString();
    }

    /**
     * 查询合作单位（全所共享主数据，无数据范围隔离）
     */
    @Tool(description = "按单位名称关键词查询合作单位列表（返回单位名称/内外部类型/公司或学校/联系人/联系电话/擅长领域），"
            + "参数 keyword 可省略（省略查全部合作单位）。")
    public String queryUnit(
            @ToolParam(required = false, description = "单位名称关键词，可省略") String keyword) {
        CooperativeUnit query = new CooperativeUnit();
        if (keyword != null && !keyword.trim().isEmpty()) {
            query.setUnitName(keyword.trim());
        }
        List<CooperativeUnit> list = cooperativeUnitService.selectUnitList(query);
        if (list == null || list.isEmpty()) {
            return keyword == null || keyword.trim().isEmpty() ? "暂无合作单位" : "未找到合作单位[" + keyword.trim() + "]";
        }
        StringBuilder sb = new StringBuilder("合作单位 " + list.size() + " 家（展示前 " + Math.min(list.size(), MAX_ROWS) + " 家）：");
        int n = 0;
        for (CooperativeUnit u : list) {
            if (n++ >= MAX_ROWS) {
                break;
            }
            sb.append("\n").append(n).append(". ").append(safe(u.getUnitName()))
              .append(" | 类型：").append(safe(u.getUnitType()))
              .append(safe(u.getExternalUnitType()).isEmpty() ? "" : "/" + u.getExternalUnitType())
              .append(" | 联系人：").append(safe(u.getContactPerson()))
              .append(" | 电话：").append(safe(u.getContactPhone()))
              .append(" | 擅长领域：").append(safe(u.getExpertise()));
        }
        return sb.toString();
    }

    // ========================================================
    //  文档生成工具（内容由代码模板保证，LLM 只传参）
    // ========================================================

    /**
     * 课题经费执行报告 Excel（预算执行 + 支出明细两个 sheet；数据过 scoped 闸门）
     */
    @Tool(description = "生成课题经费执行报告 Excel 文件（含预算科目执行情况与支出明细），参数 projectId=课题ID。"
            + "生成成功后返回下载路径，请把下载路径原样完整输出给用户，不要改写。")
    public String generateExpenseReport(
            @ToolParam(description = "课题ID") Long projectId) {
        try {
            String url = chatReportService.generateExpenseExcel(projectId);
            return "经费执行报告已生成（Excel），下载路径：" + url;
        } catch (ServiceException e) {
            return "报告生成失败：" + e.getMessage();
        }
    }

    /**
     * 课题综合档案 Word（基本信息 + 成员名单 + 预算科目；数据过 scoped 闸门）
     */
    @Tool(description = "生成课题综合档案 Word 文档（含基本信息/成员名单/预算科目），参数 projectId=课题ID。"
            + "生成成功后返回下载路径，请把下载路径原样完整输出给用户，不要改写。")
    public String generateProjectDoc(
            @ToolParam(description = "课题ID") Long projectId) {
        try {
            String url = chatReportService.generateProjectDocx(projectId);
            return "课题档案已生成（Word），下载路径：" + url;
        } catch (ServiceException e) {
            return "档案生成失败：" + e.getMessage();
        }
    }

    // ========================================================
    //  写工具（需确认卡片，不直接落库）
    // ========================================================

    /**
     * 经费记账（写操作，需确认）：只生成确认卡片（Redis TTL 5min）并回显给用户，
     * 用户 POST /biz/chat/confirm approved=true 才真正调 ExpenseService 记账。
     * 参数（projectId/amount/category）从 LLM 输出解析并原样回显。
     */
    @Tool(description = "为课题记账（经费支出）：参数 projectId=课题ID、amount=金额（元）、"
            + "category=预算科目（budget_category 字典，如 设备费/材料费/差旅费/测试化验加工费 等）。"
            + "本操作先生成确认卡片供用户确认，确认后才真正记账，请提示用户确认。")
    public String createExpense(
            @ToolParam(description = "课题ID") Long projectId,
            @ToolParam(description = "记账金额（元，数字）") String amount,
            @ToolParam(description = "预算科目（budget_category 字典）") String category,
            ToolContext toolContext) {
        try {
            BigDecimal amt = parseAmount(amount);
            if (projectId == null) {
                return "记账失败：课题ID不能为空，请提供要记账的课题ID";
            }
            if (amt == null || amt.compareTo(BigDecimal.ZERO) <= 0) {
                return "记账失败：金额必须为大于 0 的数字";
            }
            if (category == null || category.trim().isEmpty()) {
                return "记账失败：预算科目不能为空，请提供 budget_category 字典内的科目";
            }
            ConfirmCard[] holder = readHolder(toolContext);
            ConfirmCard card = chatConfirmService.createExpenseCard(projectId, amt, category.trim(),
                    SecurityUtils.getUserId(), SecurityUtils.getUsername());
            if (holder != null) {
                holder[0] = card;
            }
            return "已生成记账确认卡片：" + card.getSummary() + "。请用户点击确认后执行记账。";
        } catch (ServiceException e) {
            return "记账失败：" + e.getMessage();
        }
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /** 从 ToolContext 读取确认卡片持有器（ConfirmCard[]，ChatService 注入） */
    private ConfirmCard[] readHolder(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object holder = toolContext.getContext().get(ChatConfirmService.TOOL_CONTEXT_HOLDER_KEY);
        return holder instanceof ConfirmCard[] ? (ConfirmCard[]) holder : null;
    }

    /** 解析 LLM 输出的金额字符串（容忍"1,200元"/"1200.5"等），解析失败返回 null */
    private BigDecimal parseAmount(String s) {
        if (s == null) {
            return null;
        }
        String cleaned = s.replace(",", "").replace("，", "").trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+(\\.\\d+)?").matcher(cleaned);
        if (!m.find()) {
            return null;
        }
        try {
            return new BigDecimal(m.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean containsId(List<Project> list, Long id) {
        if (id == null) {
            return false;
        }
        for (Project p : list) {
            if (id.equals(p.getProjectId())) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** 取第一个非空字符串（queryUser 电话展示：手机号优先，无则办公电话） */
    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a;
        }
        return b == null ? "" : b;
    }

    private static String val(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }
}
