package com.ociworker.bastion;

import com.ociworker.config.WebSshAuthHandshakeInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
public class BastionWebSocketConfig implements WebSocketConfigurer {

    @Resource
    private BastionWebSocketHandler bastionWebSocketHandler;
    @Resource
    private WebSshAuthHandshakeInterceptor webSshAuthHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(bastionWebSocketHandler, "/webssh-api/bastion-term")
                .addInterceptors(webSshAuthHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
