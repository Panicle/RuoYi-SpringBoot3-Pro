package com.ruoyi.biz.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.biz.domain.vo.ChatSessionMessage;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 对话精灵会话上下文（任务卡 D9：Redis TTL 7 天，key 含 user_id 维度）
 *
 * <p>key = {@code chat:session:user:{userId}}，value = JSON 数组（role/content），
 * 仅保留最近 {@link #MAX_HISTORY}=20 条（滚动丢弃旧消息）；每次 append 重置 7 天 TTL。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    /** 会话上下文 Redis TTL（7 天，任务卡 D9） */
    private static final long SESSION_TTL_DAYS = 7;

    /** 会话历史保留上限（超出滚动丢弃） */
    private static final int MAX_HISTORY = 20;

    /** Redis key 前缀（含 user_id 维度） */
    private static final String SESSION_KEY_PREFIX = "chat:session:user:";

    private final RedisCache redisCache;
    private final ObjectMapper objectMapper;

    /** 组装当前用户的会话 key */
    private String sessionKey(Long userId) {
        return SESSION_KEY_PREFIX + userId;
    }

    /**
     * 读取当前用户会话历史（无则返回空列表，TTL 由 Redis 保证 7 天）
     */
    public List<ChatSessionMessage> getHistory(Long userId) {
        if (userId == null) {
            return new ArrayList<>();
        }
        String json = redisCache.getCacheObject(sessionKey(userId));
        if (StringUtils.isEmpty(json)) {
            return new ArrayList<>();
        }
        try {
            List<ChatSessionMessage> list = objectMapper.readValue(json,
                    new TypeReference<List<ChatSessionMessage>>() { });
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            log.warn("会话上下文解析失败，userId={}: {}", userId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 追加一条消息并重置 TTL（超上限滚动丢弃最早消息）
     */
    public void append(Long userId, String role, String content) {
        if (userId == null || StringUtils.isEmpty(content)) {
            return;
        }
        List<ChatSessionMessage> history = getHistory(userId);
        history.add(new ChatSessionMessage(role, content));
        if (history.size() > MAX_HISTORY) {
            history = new ArrayList<>(history.subList(history.size() - MAX_HISTORY, history.size()));
        }
        try {
            String json = objectMapper.writeValueAsString(history);
            redisCache.setCacheObject(sessionKey(userId), json, (int) TimeUnit.DAYS.toSeconds(SESSION_TTL_DAYS),
                    TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("会话上下文写入失败，userId={}: {}", userId, e.getMessage());
        }
    }
}
