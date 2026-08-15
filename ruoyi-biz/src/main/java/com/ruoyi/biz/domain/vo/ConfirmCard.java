package com.ruoyi.biz.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.Map;

/**
 * 确认卡片（任务卡 §三.10 对话精灵 create_expense 写操作确认）
 *
 * <p>LLM 解析出 create_expense 意图时，不直接记账——先生成确认卡片：
 * confirmId 存 Redis（TTL 5 分钟，value=JSON {tool,userId,params}），前端渲染"确认/取消"，
 * 用户 POST /biz/chat/confirm {confirmId, approved=true} 才真正调 ExpenseService 记账。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Data
public class ConfirmCard implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 确认卡片ID（UUID，Redis key chat:confirm:{confirmId}） */
    private String confirmId;

    /** 工具名（当前仅 create_expense） */
    private String tool;

    /** 操作摘要（确认卡片回显给用户，如"为课题[KY-2024-001]记账 1200.00 元（材料费）"） */
    private String summary;

    /** 参数回显（create_expense：projectId/amount/category 原样回显，approved 后才落库） */
    private Map<String, Object> params;

    /** 过期时间（Redis TTL 5 分钟） */
    private Date expireAt;

    /** 创建人用户名（服务端回填，审计用） */
    private String createBy;
}
