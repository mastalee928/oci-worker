package com.ociworker.config;

import com.ociworker.service.LoginSecurityService;
import com.ociworker.service.PanelAuthService;
import com.ociworker.service.WebSocketTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void acceptsOneTimeTicketForLogWebSocketWhenCookieIsUnavailable() {
        PanelAuthService auth = mock(PanelAuthService.class);
        LoginSecurityService security = mock(LoginSecurityService.class);
        WebSocketTicketService tickets = mock(WebSocketTicketService.class);
        when(tickets.consume("valid-ticket")).thenReturn(true);
        WebSshAuthHandshakeInterceptor interceptor = interceptor(auth, security, tickets);
        MockHttpServletRequest rawRequest = new MockHttpServletRequest("GET", "/ws/log");
        rawRequest.addParameter("ticket", "valid-ticket");
        MockHttpServletResponse rawResponse = new MockHttpServletResponse();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(rawRequest),
                new ServletServerHttpResponse(rawResponse),
                mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isTrue();
        verify(tickets).consume("valid-ticket");
        verify(auth, never()).validateRequestToken(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void doesNotAcceptLogTicketForOtherWebSocketEndpoints() {
        PanelAuthService auth = mock(PanelAuthService.class);
        LoginSecurityService security = mock(LoginSecurityService.class);
        WebSocketTicketService tickets = mock(WebSocketTicketService.class);
        WebSshAuthHandshakeInterceptor interceptor = interceptor(auth, security, tickets);
        MockHttpServletRequest rawRequest = new MockHttpServletRequest("GET", "/webssh-api/term");
        rawRequest.addParameter("ticket", "valid-ticket");
        MockHttpServletResponse rawResponse = new MockHttpServletResponse();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(rawRequest),
                new ServletServerHttpResponse(rawResponse),
                mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
        assertThat(rawResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(tickets, never()).consume(org.mockito.ArgumentMatchers.any());
    }

    private static WebSshAuthHandshakeInterceptor interceptor(
            PanelAuthService auth, LoginSecurityService security) {
        return interceptor(auth, security, mock(WebSocketTicketService.class));
    }

    private static WebSshAuthHandshakeInterceptor interceptor(
            PanelAuthService auth, LoginSecurityService security, WebSocketTicketService tickets) {
        WebSshAuthHandshakeInterceptor interceptor = new WebSshAuthHandshakeInterceptor();
        ReflectionTestUtils.setField(interceptor, "panelAuthService", auth);
        ReflectionTestUtils.setField(interceptor, "loginSecurityService", security);
        ReflectionTestUtils.setField(interceptor, "webSocketTicketService", tickets);
        return interceptor;
    }
}
