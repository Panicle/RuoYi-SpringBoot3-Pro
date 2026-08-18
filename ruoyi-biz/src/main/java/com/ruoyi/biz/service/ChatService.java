package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.vo.ChatReply;
import com.ruoyi.biz.domain.vo.ChatSessionMessage;
import com.ruoyi.biz.domain.vo.ConfirmCard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话精灵编排服务（任务卡 §三.8 / D8：ChatClient + 6 工具函数 + LLM 未配置降级）
 *
 * <p>LLM 接入：通过 {@link ObjectProvider}<{@link ChatModel}> 弱注入——应用配置
 * {@code spring.ai.model.chat=openai} 且 api-key 非空时才有 ChatModel Bean；
 * 未配置（降级模式）时 Provider 为空，/ask 直接返回友好提示"对话精灵未配置 LLM"，
 * 绝不抛异常堆栈（任务卡 D8）。</p>
 *
 * <p>会话上下文：每次 /ask 读写 Redis（ChatSessionService，TTL 7 天），
 * 历史消息作为前缀随 prompt 回传给 LLM 实现多轮上下文。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Slf4j
@Service
public class ChatService {

    /** 系统提示词：限定助手只做 13 个工具能力，不编造 */
    private static final String SYSTEM_PROMPT = "你是科研管理平台的对话精灵助手。"
            + "你只能使用提供的工具函数查询和操作平台数据，工具包括："
            + "query_project（查课题，返回含课题ID）、query_project_member（按课题ID查成员名单）、"
            + "query_budget（查课题经费余额）、create_expense（记账，需用户确认）、"
            + "query_approval（查审批）、query_alert（查我的未读预警）、query_worktime（查研发工时）、"
            + "query_user（查人员信息：姓名/部门/职称/学历/研究方向/联系方式）、"
            + "query_contract（查合同）、query_honor（查荣誉）、query_unit（查合作单位）、"
            + "generate_expense_report（生成课题经费执行报告 Excel）、generate_project_doc（生成课题综合档案 Word）。"
            + "规则：不要编造任何数据，用户询问数据时先调用对应工具；"
            + "需要课题ID的工具（成员/经费/文档生成），用户只给课题名称或编号时先用 query_project 查到课题ID再调用；"
            + "生成文档类工具返回的下载路径必须原样完整输出，不要改写或省略；"
            + "create_expense 是写操作，会生成确认卡片供用户确认，请向用户说明并等待其确认；"
            + "回答使用简洁中文。";

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ChatTools chatTools;
    private final ChatSessionService chatSessionService;

    public ChatService(ObjectProvider<ChatModel> chatModelProvider, ChatTools chatTools,
                       ChatSessionService chatSessionService) {
        this.chatModelProvider = chatModelProvider;
        this.chatTools = chatTools;
        this.chatSessionService = chatSessionService;
    }

    /**
     * 对话（/biz/chat/ask 入口）：
     * LLM 未配置 → 友好降级提示；配置后 → 会话历史 + 当前消息 + 6 工具调用。
     *
     * @return ChatReply（configured=false 表示降级模式）
     */
    public ChatReply ask(String message, Long userId, String username) {
        if (!StringUtils.hasText(message)) {
            return ChatReply.degraded("请输入要咨询的内容");
        }
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            // D8：key 未配置 → 友好提示，绝不 500 堆栈
            return ChatReply.degraded("对话精灵未配置 LLM，请联系管理员配置 LLM 服务后使用");
        }
        try {
            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultSystem(SYSTEM_PROMPT)
                    .build();

            // 会话历史（前缀回传实现多轮上下文）
            List<Message> history = toSpringAiMessages(chatSessionService.getHistory(userId));

            // 确认卡片持有器（create_expense 工具写入，本方法提取回填 reply.confirmCard）
            ConfirmCard[] holder = new ConfirmCard[1];
            Map<String, Object> toolContext = new HashMap<>();
            toolContext.put(ChatConfirmService.TOOL_CONTEXT_HOLDER_KEY, holder);

            String reply = chatClient.prompt()
                    .messages(history)
                    .user(message)
                    .tools(chatTools)
                    .toolContext(toolContext)
                    .call()
                    .content();

            // 落会话上下文（user + assistant 各一条）
            chatSessionService.append(userId, "user", message);
            chatSessionService.append(userId, "assistant", reply == null ? "" : reply);

            ConfirmCard card = holder[0];
            List<String> toolCalls = card != null ? java.util.List.of("create_expense") : null;
            return ChatReply.ok(reply == null ? "" : reply, toolCalls, card);
        } catch (Exception e) {
            log.error("对话精灵调用 LLM 失败，userId={}", userId, e);
            return ChatReply.degraded("对话精灵暂时不可用，请稍后再试");
        }
    }

    /** 会话历史（Redis 中的 {role,content}）转 Spring AI Message 列表 */
    private List<Message> toSpringAiMessages(List<ChatSessionMessage> history) {
        List<Message> messages = new ArrayList<>();
        if (history == null) {
            return messages;
        }
        for (ChatSessionMessage m : history) {
            if (m == null || m.getContent() == null) {
                continue;
            }
            switch (m.getRole() == null ? "" : m.getRole()) {
                case "user" -> messages.add(new UserMessage(m.getContent()));
                case "assistant" -> messages.add(new AssistantMessage(m.getContent()));
                case "system" -> messages.add(new SystemMessage(m.getContent()));
                default -> { /* 未知角色跳过 */ }
            }
        }
        return messages;
    }
}
