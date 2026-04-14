package com.ociworker.config;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.util.CommonUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Value("${web.account}")
    private String account;
    @Value("${web.password}")
    private String password;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        if (uri.startsWith("/api/auth/login") || uri.startsWith("/ws/")
                || uri.equals("/") || uri.startsWith("/assets/")
                || uri.endsWith(".html") || uri.endsWith(".js")
                || uri.endsWith(".css") || uri.endsWith(".ico")
                || uri.startsWith("/ip-info")) {
            return true;
        }

        String token = request.getHeader("Authorization");
        if (StrUtil.isBlank(token)) {
            token = request.getParameter("token");
        }

        if (StrUtil.isBlank(token) || !CommonUtils.validateToken(token, account, password)) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(401);
            response.getWriter().write(objectMapper.writeValueAsString(ResponseData.error(401, "Unauthorized")));
            return false;
        }
        return true;
    }
}
