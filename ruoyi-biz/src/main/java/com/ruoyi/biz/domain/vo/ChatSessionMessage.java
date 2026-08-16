package com.ruoyi.biz.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 会话上下文单条消息（任务卡 D9：会话上下文 Redis TTL 7 天，key 含 user_id 维度）
 *
 * <p>role ∈ {user, assistant, system}，与 Spring AI Message 角色对应；
 * 会话历史仅保留最近 {@code MAX_HISTORY=20} 条，超出滚动丢弃旧消息。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息角色（user/assistant/system） */
    private String role;

    /** 消息内容 */
    private String content;
}
