package com.ociworker.config;

import com.ociworker.service.LoginSecurityService;
import com.ociworker.service.PanelAuthService;
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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Component
public class WebSshAuthHandshakeInterceptor implements HandshakeInterceptor {

    private static final String WEB_SOCKET_PROTOCOL_HEADER = "Sec-WebSocket-Protocol";
    private static final String TOKEN_PROTOCOL_PREFIX = "ociworker-token-b64.";

    @Resource
    private PanelAuthService panelAuthService;
    @Resource
    private LoginSecurityService loginSecurityService;

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
            boolean authenticated = panelAuthService.validateRequestToken(raw, false, true);
            if (!authenticated) {
                String protocolToken = readProtocolToken(raw.getHeader(WEB_SOCKET_PROTOCOL_HEADER));
                authenticated = panelAuthService.validateToken(protocolToken);
            }
            if (!authenticated) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            String clientIp = HttpRequestUtil.getClientIp(raw);
            String deviceId = loginSecurityService.readDeviceIdFromRequest(raw);
            if (loginSecurityService.isDeniedForLogin(clientIp, deviceId)) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
        } else {
            String token = panelAuthService.readToken(request.getHeaders(), request.getURI().getRawQuery());
            if (!panelAuthService.validateToken(token)) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
        }
        return true;
    }

    private static String readProtocolToken(String protocolHeader) {
        if (protocolHeader == null || protocolHeader.isBlank()) {
            return null;
        }
        for (String item : protocolHeader.split(",")) {
            String protocol = item.trim();
            if (!protocol.startsWith(TOKEN_PROTOCOL_PREFIX)) {
                continue;
            }
            String encoded = protocol.substring(TOKEN_PROTOCOL_PREFIX.length());
            if (encoded.isBlank() || encoded.length() > 4096) {
                return null;
            }
            try {
                return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
