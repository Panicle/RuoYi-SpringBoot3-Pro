package com.ruoyi.biz.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

/**
 * WebSocket 配置（任务卡 D9：/ws/pet 预警推送 + 对话精灵共用端点）
 *
 * <p>照 RuoYi-Vue 既有惯例：JSR-356 {@code @ServerEndpoint} + {@code ServerEndpointExporter} 暴露端点；
 * 内嵌 Tomcat 自带 tomcat-embed-websocket（由 spring-boot-starter-tomcat 传递引入），无需额外容器配置。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Configuration
public class WebSocketConfig {

    /**
     * JSR-356 @ServerEndpoint 端点暴露器（内嵌 Tomcat 场景必需）
     */
    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }
}
