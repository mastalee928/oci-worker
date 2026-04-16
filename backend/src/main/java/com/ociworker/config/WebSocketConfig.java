package com.ociworker.config;

import com.ociworker.websocket.LogWebSocketHandler;
import com.ociworker.websocket.WebSSHProxyHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import jakarta.annotation.Resource;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private LogWebSocketHandler logWebSocketHandler;
    @Resource
    private WebSSHProxyHandler webSSHProxyHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(logWebSocketHandler, "/ws/log")
                .setAllowedOrigins("*");
        registry.addHandler(webSSHProxyHandler, "/webssh-api/term", "/webssh-api/file/progress")
                .setAllowedOrigins("*");
    }
}
