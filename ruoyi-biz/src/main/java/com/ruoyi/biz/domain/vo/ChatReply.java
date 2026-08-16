package com.ruoyi.biz.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 对话精灵 /biz/chat/ask 响应（任务卡 §三.8）
 *
 * <p>{@code reply} 为 LLM 回复文本；create_expense 命中确认卡片时 {@code confirmCard} 非空，
 * 前端据此渲染确认/取消卡片；LLM 未配置时 {@code configured=false}，{@code reply} 为友好降级提示。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatReply implements Serializable {

    private static final long serialVersionUID = 1L;

    /** LLM 回复文本（降级时为友好提示） */
    private String reply;

    /** 本轮回调用的工具名列表（null=未调用工具，仅展示用） */
    private java.util.List<String> toolCalls;

    /** 确认卡片（create_expense 写操作触发时非空） */
    private ConfirmCard confirmCard;

    /** LLM 是否已配置（false=降级模式，reply 为友好提示） */
    private Boolean configured;

    /** 构造降级回复（LLM 未配置，任务卡 D8：友好提示而非 500 堆栈） */
    public static ChatReply degraded(String message) {
        return new ChatReply(message, null, null, false);
    }

    /** 构造正常回复 */
    public static ChatReply ok(String reply, java.util.List<String> toolCalls, ConfirmCard confirmCard) {
        return new ChatReply(reply, toolCalls, confirmCard, true);
    }
}
