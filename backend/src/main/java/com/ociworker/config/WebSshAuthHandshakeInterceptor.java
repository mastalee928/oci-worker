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

import java.util.Map;

@Component
public class WebSshAuthHandshakeInterceptor implements HandshakeInterceptor {

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
            if (!panelAuthService.validateRequestToken(raw, false, true)) {
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

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
