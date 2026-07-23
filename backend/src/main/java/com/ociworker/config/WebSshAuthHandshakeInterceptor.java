package com.ociworker.config;

import com.ociworker.service.LoginSecurityService;
import com.ociworker.service.PanelAuthService;
import com.ociworker.service.WebSocketTicketService;
import com.ociworker.util.HttpRequestUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
public class WebSshAuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final String AUTHENTICATED_ACCOUNT_ATTRIBUTE = "websshAuthenticatedAccount";

    @Resource
    private PanelAuthService panelAuthService;
    @Resource
    private LoginSecurityService loginSecurityService;
    @Resource
    private WebSocketTicketService webSocketTicketService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String uri = request.getURI().getPath();
        if (loginSecurityService.isSitePaused() && !loginSecurityService.isExemptFromSitePause(uri)) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }

        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest raw = servletRequest.getServletRequest();
            boolean ticketAuthenticated = "/ws/log".equals(uri)
                    && webSocketTicketService.consume(raw.getParameter("ticket"));
            String account = ticketAuthenticated
                    ? null
                    : panelAuthService.authenticatedAccount(raw, false, true);
            if (!ticketAuthenticated && account == null) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            String clientIp = HttpRequestUtil.getClientIp(raw);
            String deviceId = loginSecurityService.readDeviceIdFromRequest(raw);
            if (loginSecurityService.isDeniedForLogin(clientIp, deviceId)) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            if (account != null) {
                attributes.put(AUTHENTICATED_ACCOUNT_ATTRIBUTE, account);
            }
        } else {
            String account = panelAuthService.authenticatedAccount(
                    request.getHeaders(), request.getURI().getRawQuery());
            if (account == null) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            attributes.put(AUTHENTICATED_ACCOUNT_ATTRIBUTE, account);
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // Authentication state is copied into WebSocket attributes before the handshake completes.
    }
}
