package com.ociworker.config;

import com.ociworker.webssh.WebSshConsoleTerminalWebSocketHandler;
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
    private WebSshConsoleTerminalWebSocketHandler webSshConsoleTerminalWebSocketHandler;
    @Resource
    private WebSshUploadProgressWebSocketHandler webSshUploadProgressWebSocketHandler;
    @Resource
    private WebSshAuthHandshakeInterceptor webSshAuthHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(logWebSocketHandler, "/ws/log")
                .setAllowedOrigins("*");
        registry.addHandler(webSshTerminalWebSocketHandler, "/webssh-api/term")
                .addInterceptors(webSshAuthHandshakeInterceptor)
                .setAllowedOrigins("*");
        registry.addHandler(webSshConsoleTerminalWebSocketHandler, "/webssh-api/console-term")
                .addInterceptors(webSshAuthHandshakeInterceptor)
                .setAllowedOrigins("*");
        registry.addHandler(webSshUploadProgressWebSocketHandler, "/webssh-api/file/progress")
                .addInterceptors(webSshAuthHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
