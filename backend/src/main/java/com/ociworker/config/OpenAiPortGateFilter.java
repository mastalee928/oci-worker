package com.ociworker.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 当配置了独立 {@code ociworker.openaiApi.port} 时，仅该端口上允许 {@code /v1} 路径；避免在面板端口上误暴露与占用。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OpenAiPortGateFilter extends OncePerRequestFilter {

    @Value("${server.port:8818}")
    private int serverPort;

    @Value("${ociworker.openaiApi.port:8080}")
    private int openaiApiPort;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path == null) {
            path = "";
        }
        String ctx = request.getContextPath() == null ? "" : request.getContextPath();
        if (ctx.length() > 0 && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }
        if (path == null || !path.startsWith("/v1")) {
            filterChain.doFilter(request, response);
            return;
        }
        if (openaiApiPort <= 0 || openaiApiPort == serverPort) {
            filterChain.doFilter(request, response);
            return;
        }
        if (request.getLocalPort() != openaiApiPort) {
            response.setStatus(404);
            response.setContentType("application/json; charset=utf-8");
            String msg = "{\"error\":{\"message\":\"OpenAI 兼容 API 请使用 :"
                    + openaiApiPort
                    + " 端口（/v1），面板端口不暴露此能力\",\"type\":\"ociworker_error\"}}";
            response.getOutputStream().write(msg.getBytes(StandardCharsets.UTF_8));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
