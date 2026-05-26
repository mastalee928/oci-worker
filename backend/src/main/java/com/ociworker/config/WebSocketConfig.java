package com.ociworker.config;

import com.ociworker.webssh.WebSshTerminalWebSocketHandler;
import com.ociworker.webssh.WebSshUploadProgressWebSocketHandler;
import com.ociworker.websocket.LogWebSocketHandler;
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
    private WebSshTerminalWebSocketHandler webSshTerminalWebSocketHandler;
    @Resource
    private WebSshUploadProgressWebSocketHandler webSshUploadProgressWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(logWebSocketHandler, "/ws/log")
                .setAllowedOrigins("*");
        registry.addHandler(webSshTerminalWebSocketHandler, "/webssh-api/term")
                .setAllowedOrigins("*");
        registry.addHandler(webSshUploadProgressWebSocketHandler, "/webssh-api/file/progress")
                .setAllowedOrigins("*");
    }
}
