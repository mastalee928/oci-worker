package com.ociworker.bastion;

import com.ociworker.config.WebSshAuthHandshakeInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BastionWebSocketConfigTest {

    @Test
    void registersOnlyTheDedicatedBastionPath() {
        BastionWebSocketConfig config = new BastionWebSocketConfig();
        BastionWebSocketHandler handler = mock(BastionWebSocketHandler.class);
        WebSshAuthHandshakeInterceptor interceptor = mock(WebSshAuthHandshakeInterceptor.class);
        ReflectionTestUtils.setField(config, "bastionWebSocketHandler", handler);
        ReflectionTestUtils.setField(config, "webSshAuthHandshakeInterceptor", interceptor);

        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(registry.addHandler(handler, "/webssh-api/bastion-term")).thenReturn(registration);
        when(registration.addInterceptors(interceptor)).thenReturn(registration);

        config.registerWebSocketHandlers(registry);

        verify(registration).addInterceptors(interceptor);
        verify(registration).setAllowedOriginPatterns("*");
    }
}
