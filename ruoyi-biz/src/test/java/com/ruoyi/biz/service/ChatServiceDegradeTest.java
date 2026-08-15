package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.vo.ChatReply;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 对话精灵 LLM 未配置降级单测（任务卡 D8：key 未配置时 /ask 返回友好提示而非 500 堆栈）
 *
 * <p>不拉起 Spring 上下文：以空 ObjectProvider<ChatModel>（模拟 LLM 未配置，ChatModel Bean 不存在）
 * 构造 ChatService，断言 ask() 返回 configured=false 的友好降级提示、且不触碰工具/会话依赖。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
class ChatServiceDegradeTest {

    /** 空 Provider：getIfAvailable() 返回 null（ChatModel Bean 不存在） */
    private static final ObjectProvider<ChatModel> EMPTY_PROVIDER = new ObjectProvider<ChatModel>() {
        @Override
        public ChatModel getObject() {
            return null;
        }

        @Override
        public ChatModel getObject(Object... args) {
            return null;
        }
    };

    @Test
    void ask_whenChatModelAbsent_returnsFriendlyDegradedReply() {
        ChatService service = new ChatService(EMPTY_PROVIDER, null, null);

        ChatReply reply = service.ask("查询一下我的课题", 1L, "admin");

        assertNotNull(reply);
        assertFalse(Boolean.TRUE.equals(reply.getConfigured()), "configured 应为 false（降级模式）");
        assertNotNull(reply.getReply());
        assertEquals("对话精灵未配置 LLM，请联系管理员配置 LLM 服务后使用", reply.getReply());
        assertNull(reply.getConfirmCard(), "降级模式不应有确认卡片");
        assertNull(reply.getToolCalls(), "降级模式不应有工具调用");
    }

    @Test
    void ask_whenMessageBlank_returnsPrompt() {
        ChatService service = new ChatService(EMPTY_PROVIDER, null, null);

        ChatReply reply = service.ask("   ", 1L, "admin");

        assertNotNull(reply);
        assertFalse(Boolean.TRUE.equals(reply.getConfigured()));
        assertEquals("请输入要咨询的内容", reply.getReply());
    }
}
