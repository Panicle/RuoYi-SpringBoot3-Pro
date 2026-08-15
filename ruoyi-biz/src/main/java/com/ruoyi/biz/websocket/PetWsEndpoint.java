package com.ruoyi.biz.websocket;

import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.TokenService;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.server.standard.SpringConfigurator;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话精灵 / 预警推送 WebSocket 端点（任务卡 D9：/ws/pet 对话 + 预警推送共用）
 *
 * <p>连接 URL：{@code ws://host:port/ws/pet?token={登录token}}。握手时用 TokenService 解析
 * token 换取 LoginUser（无效/过期则拒绝连接）；会话按 userId 维度登记（ConcurrentHashMap），
 * {@link #pushToUser} 供预警推送方调用（如 AlertScanService 新建通知后推送未读提醒）。</p>
 *
 * <p>单例 Bean（SpringConfigurator 从容器取实例，构造注入可用），连接态存静态表共享。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Slf4j
@Component
@ServerEndpoint(value = "/ws/pet", configurator = SpringConfigurator.class)
public class PetWsEndpoint {

    /** userId → 该用户的在线会话集合（静态共享） */
    private static final ConcurrentHashMap<Long, Set<Session>> SESSIONS = new ConcurrentHashMap<>();

    /** sessionId → userId（断开清理用） */
    private static final ConcurrentHashMap<String, Long> SESSION_USER = new ConcurrentHashMap<>();

    private final TokenService tokenService;

    public PetWsEndpoint(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * 连接建立：校验 token → 按 userId 登记会话。
     * 无效/缺失 token 直接关闭连接（CloseReason VIOLATED_POLICY）。
     */
    @OnOpen
    public void onOpen(Session session) {
        Long userId = resolveUserId(session);
        if (userId == null) {
            closeUnauthorized(session);
            return;
        }
        SESSIONS.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
        SESSION_USER.put(session.getId(), userId);
        log.info("WebSocket /ws/pet 连接建立：userId={}, session={}", userId, session.getId());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        // V1.0 对话走 HTTP /biz/chat/ask，WebSocket 仅做预警推送（任务卡 §三.8："WebSocket 只做预警推送"）。
        // 收到客户端消息时仅记录日志，不回业务处理。
        log.debug("WebSocket /ws/pet 收到消息：session={}, msg={}", session.getId(), message);
    }

    @OnClose
    public void onClose(Session session) {
        Long userId = SESSION_USER.remove(session.getId());
        if (userId != null) {
            Set<Session> set = SESSIONS.get(userId);
            if (set != null) {
                set.remove(session);
                if (set.isEmpty()) {
                    SESSIONS.remove(userId);
                }
            }
        }
        log.info("WebSocket /ws/pet 连接关闭：userId={}, session={}", userId, session.getId());
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.warn("WebSocket /ws/pet 异常：session={}, error={}", session == null ? "-" : session.getId(),
                error == null ? "unknown" : error.getMessage());
    }

    // ========================================================
    //  预警推送入口（供业务方调用）
    // ========================================================

    /**
     * 向指定用户的全部在线连接推送文本（预警推送用）。
     * 静默容错：单条发送失败不影响其他连接；已断开会话惰性清理。
     *
     * @return 实际送达的连接数
     */
    public int pushToUser(Long userId, String payload) {
        if (userId == null) {
            return 0;
        }
        Set<Session> sessions = SESSIONS.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (Session session : sessions) {
            if (session == null || !session.isOpen()) {
                continue;
            }
            try {
                synchronized (session) {
                    session.getBasicRemote().sendText(payload);
                }
                sent++;
            } catch (IOException e) {
                log.warn("WebSocket 推送失败：userId={}, session={}, error={}", userId, session.getId(),
                        e.getMessage());
            }
        }
        return sent;
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /** 从握手 query 参数解析 token → LoginUser.userId（无效返回 null） */
    private Long resolveUserId(Session session) {
        try {
            List<String> tokens = session.getRequestParameterMap().get("token");
            if (tokens == null || tokens.isEmpty()) {
                return null;
            }
            LoginUser user = tokenService.getLoginUser(tokens.get(0));
            return user == null ? null : user.getUserId();
        } catch (Exception e) {
            log.warn("WebSocket 握手 token 解析失败：{}", e.getMessage());
            return null;
        }
    }

    /** 拒绝未授权连接（立即关闭，符合 WebSocket 协议） */
    private void closeUnauthorized(Session session) {
        try {
            session.close(new jakarta.websocket.CloseReason(
                    jakarta.websocket.CloseReason.CloseCodes.VIOLATED_POLICY, "unauthorized"));
        } catch (IOException e) {
            log.debug("关闭未授权 WebSocket 连接失败：{}", e.getMessage());
        }
    }
}
