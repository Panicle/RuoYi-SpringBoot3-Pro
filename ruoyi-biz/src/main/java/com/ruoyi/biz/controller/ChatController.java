package com.ruoyi.biz.controller;

import com.ruoyi.biz.service.ChatConfirmService;
import com.ruoyi.biz.service.ChatService;
import com.ruoyi.biz.service.ChatSessionService;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 对话精灵 Controller（任务卡 §三.8-10：/ask 意图识别 + 工具调用、/session 会话上下文、/confirm 确认卡片）
 *
 * <p>权限串复用 {@code biz:alert:notify}（所有登录用户，任务卡 §6.2 权限矩阵）。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@RestController
@RequestMapping("/biz/chat")
@PreAuthorize("@ss.hasPermi('biz:alert:notify')")
@RequiredArgsConstructor
public class ChatController extends BaseController {

    private final ChatService chatService;
    private final ChatSessionService chatSessionService;
    private final ChatConfirmService chatConfirmService;

    /**
     * 8. 对话：意图识别 + 工具调用，返回 {reply, toolCalls?, confirmCard?}。
     * LLM 未配置时返回友好降级提示（configured=false），绝不 500 堆栈。
     */
    @PostMapping("/ask")
    public AjaxResult ask(@RequestBody AskRequest req) {
        if (req == null || req.getMessage() == null || req.getMessage().trim().isEmpty()) {
            return error("message 不能为空");
        }
        return success(chatService.ask(req.getMessage().trim(),
                SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }

    /**
     * 9. 会话上下文（Redis，TTL 7 天，key 含 user_id 维度）
     */
    @GetMapping("/session")
    public AjaxResult session() {
        return success(chatSessionService.getHistory(SecurityUtils.getUserId()));
    }

    /**
     * 10. 确认卡片回调：{confirmId, approved}；approved=true 才执行写操作（create_expense 记账），
     * false 丢弃；执行后删除 Redis key。
     */
    @PostMapping("/confirm")
    public AjaxResult confirm(@RequestBody ConfirmRequest req) {
        if (req == null || req.getConfirmId() == null || req.getConfirmId().trim().isEmpty()) {
            return error("confirmId 不能为空");
        }
        try {
            Map<String, Object> result = chatConfirmService.execute(req.getConfirmId().trim(),
                    req.getApproved(), SecurityUtils.getUserId(), SecurityUtils.getUsername());
            return success(result);
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    /** /ask 请求体 */
    @Data
    public static class AskRequest {
        /** 用户消息 */
        private String message;
    }

    /** /confirm 请求体 */
    @Data
    public static class ConfirmRequest {
        /** 确认卡片ID */
        private String confirmId;
        /** true=确认执行（仅 approved=true 才执行写操作） */
        private Boolean approved;
    }
}
