package com.ociworker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.LoginSecurityService;
import com.ociworker.service.PanelAuthService;
import com.ociworker.util.HttpRequestUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Resource
    private LoginSecurityService loginSecurityService;
    @Resource
    private PanelAuthService panelAuthService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        // 更新恢复探针必须完全绕开数据库鉴权和站点状态查询，否则服务刚启动时
        // 仍可能被数据库连接阻塞，失去“轻量就绪检查”的意义。
        if (uri.equals("/api/sys/ready")) {
            return true;
        }

        // WebSSH 自身的固定静态资源不需要为每个 CSS/JS 文件查询数据库；
        // 入口 HTML、WebSSH API 和终端 WebSocket 仍继续走后续完整鉴权。
        if (uri.startsWith("/webssh/static/")) {
            return true;
        }

        if (loginSecurityService.isSitePaused() && !loginSecurityService.isExemptFromSitePause(uri)) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(503);
            response.getWriter().write(objectMapper.writeValueAsString(ResponseData.error(503,
                    "站点已暂停访问。请通过 Telegram 中的「恢复全站访问」或修改数据库配置项 site_access_paused 后重试。")));
            return false;
        }

        if (loginSecurityService.isLoginHardenedPath(uri)) {
            String ip = HttpRequestUtil.getClientIp(request);
            String did = loginSecurityService.readDeviceIdFromRequest(request);
            if (loginSecurityService.isDeniedForLogin(ip, did)) {
                response.setContentType("application/json;charset=UTF-8");
                response.setStatus(403);
                response.getWriter().write(objectMapper.writeValueAsString(ResponseData.error(403, "访问被拒绝")));
                return false;
            }
        }

        if (uri.startsWith("/api/auth/login")
                || uri.startsWith("/api/auth/needSetup")
                || uri.startsWith("/api/auth/setup")
                || uri.startsWith("/api/auth/tgLogin")
                || uri.startsWith("/api/auth/tgLoginAvailable")
                || uri.startsWith("/api/auth/device")
                || uri.startsWith("/ws/")
                || uri.equals("/") || uri.startsWith("/assets/")
                || (uri.endsWith(".html") && !uri.startsWith("/webssh/")) || uri.endsWith(".js")
                || uri.endsWith(".css") || uri.endsWith(".ico")
                || uri.startsWith("/ip-info")) {
            return true;
        }

        boolean isWebSshRequest = uri.startsWith("/webssh");
        if (!panelAuthService.validateRequestToken(request, !isWebSshRequest, isWebSshRequest)) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(401);
            response.getWriter().write(objectMapper.writeValueAsString(ResponseData.error(401, "Unauthorized")));
            return false;
        }

        String clientIp = HttpRequestUtil.getClientIp(request);
        String deviceId = loginSecurityService.readDeviceIdFromRequest(request);
        if (loginSecurityService.isDeniedForLogin(clientIp, deviceId)) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(403);
            response.getWriter().write(objectMapper.writeValueAsString(ResponseData.error(403, "访问被拒绝")));
            return false;
        }
        return true;
    }
}
