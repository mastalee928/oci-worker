package com.ociworker.config;

import com.ociworker.webssh.WebSshConsoleTerminalWebSocketHandler;
import com.ociworker.webssh.WebSshTerminalWebSocketHandler;
import com.ociworker.webssh.WebSshUploadProgressWebSocketHandler;
import com.ociworker.websocket.LogWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketConfigTest {

    @Test
    void allowsAuthenticatedWebSocketsBehindHttpsReverseProxy() {
        WebSocketConfig config = new WebSocketConfig();
        LogWebSocketHandler logHandler = mock(LogWebSocketHandler.class);
        WebSshTerminalWebSocketHandler terminalHandler = mock(WebSshTerminalWebSocketHandler.class);
        WebSshConsoleTerminalWebSocketHandler consoleHandler = mock(WebSshConsoleTerminalWebSocketHandler.class);
        WebSshUploadProgressWebSocketHandler progressHandler = mock(WebSshUploadProgressWebSocketHandler.class);
        WebSshAuthHandshakeInterceptor authInterceptor = mock(WebSshAuthHandshakeInterceptor.class);
        ReflectionTestUtils.setField(config, "logWebSocketHandler", logHandler);
        ReflectionTestUtils.setField(config, "webSshTerminalWebSocketHandler", terminalHandler);
        ReflectionTestUtils.setField(config, "webSshConsoleTerminalWebSocketHandler", consoleHandler);
        ReflectionTestUtils.setField(config, "webSshUploadProgressWebSocketHandler", progressHandler);
        ReflectionTestUtils.setField(config, "webSshAuthHandshakeInterceptor", authInterceptor);

        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration logRegistration = registration(authInterceptor);
        WebSocketHandlerRegistration terminalRegistration = registration(authInterceptor);
        WebSocketHandlerRegistration consoleRegistration = registration(authInterceptor);
        WebSocketHandlerRegistration progressRegistration = registration(authInterceptor);
        when(registry.addHandler(logHandler, "/ws/log")).thenReturn(logRegistration);
        when(registry.addHandler(terminalHandler, "/webssh-api/term")).thenReturn(terminalRegistration);
        when(registry.addHandler(consoleHandler, "/webssh-api/console-term")).thenReturn(consoleRegistration);
        when(registry.addHandler(progressHandler, "/webssh-api/file/progress")).thenReturn(progressRegistration);

        config.registerWebSocketHandlers(registry);

        verify(logRegistration).setAllowedOriginPatterns("*");
        verify(terminalRegistration).setAllowedOriginPatterns("*");
        verify(consoleRegistration).setAllowedOriginPatterns("*");
        verify(progressRegistration).setAllowedOriginPatterns("*");
    }

    private static WebSocketHandlerRegistration registration(WebSshAuthHandshakeInterceptor interceptor) {
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(registration.addInterceptors(interceptor)).thenReturn(registration);
        return registration;
    }
}
