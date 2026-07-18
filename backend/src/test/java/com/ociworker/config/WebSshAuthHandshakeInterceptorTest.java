package com.ociworker.config;

import com.ociworker.service.LoginSecurityService;
import com.ociworker.service.PanelAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSshAuthHandshakeInterceptorTest {

    @Test
    void rejectsWebSocketWithoutValidPanelCookie() {
        PanelAuthService auth = mock(PanelAuthService.class);
        LoginSecurityService security = mock(LoginSecurityService.class);
        WebSshAuthHandshakeInterceptor interceptor = interceptor(auth, security);
        MockHttpServletRequest rawRequest = new MockHttpServletRequest("GET", "/ws/log");
        MockHttpServletResponse rawResponse = new MockHttpServletResponse();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(rawRequest),
                new ServletServerHttpResponse(rawResponse),
                mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
        assertThat(rawResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void acceptsAuthenticatedNonDeniedWebSocket() {
        PanelAuthService auth = mock(PanelAuthService.class);
        LoginSecurityService security = mock(LoginSecurityService.class);
        when(auth.validateRequestToken(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(true))).thenReturn(true);
        WebSshAuthHandshakeInterceptor interceptor = interceptor(auth, security);
        MockHttpServletRequest rawRequest = new MockHttpServletRequest("GET", "/ws/log");
        MockHttpServletResponse rawResponse = new MockHttpServletResponse();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(rawRequest),
                new ServletServerHttpResponse(rawResponse),
                mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isTrue();
    }

    @Test
    void acceptsTokenFromWebSocketSubProtocolWhenCookieIsUnavailable() {
        PanelAuthService auth = mock(PanelAuthService.class);
        LoginSecurityService security = mock(LoginSecurityService.class);
        String token = "v2.test-token";
        when(auth.validateToken(token)).thenReturn(true);
        WebSshAuthHandshakeInterceptor interceptor = interceptor(auth, security);
        MockHttpServletRequest rawRequest = new MockHttpServletRequest("GET", "/ws/log");
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        rawRequest.addHeader("Sec-WebSocket-Protocol",
                "ociworker-log-v1, ociworker-token-b64." + encoded);
        MockHttpServletResponse rawResponse = new MockHttpServletResponse();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(rawRequest),
                new ServletServerHttpResponse(rawResponse),
                mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isTrue();
        verify(auth).validateToken(token);
    }

    @Test
    void rejectsMalformedWebSocketSubProtocolToken() {
        PanelAuthService auth = mock(PanelAuthService.class);
        LoginSecurityService security = mock(LoginSecurityService.class);
        WebSshAuthHandshakeInterceptor interceptor = interceptor(auth, security);
        MockHttpServletRequest rawRequest = new MockHttpServletRequest("GET", "/ws/log");
        rawRequest.addHeader("Sec-WebSocket-Protocol",
                "ociworker-log-v1, ociworker-token-b64.not_base64!");
        MockHttpServletResponse rawResponse = new MockHttpServletResponse();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(rawRequest),
                new ServletServerHttpResponse(rawResponse),
                mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
        assertThat(rawResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    private static WebSshAuthHandshakeInterceptor interceptor(
            PanelAuthService auth, LoginSecurityService security) {
        WebSshAuthHandshakeInterceptor interceptor = new WebSshAuthHandshakeInterceptor();
        ReflectionTestUtils.setField(interceptor, "panelAuthService", auth);
        ReflectionTestUtils.setField(interceptor, "loginSecurityService", security);
        return interceptor;
    }
}
