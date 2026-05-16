package com.ociworker.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;

/**
 * 为登录审计缓存 POST Body（{@link org.springframework.web.util.ContentCachingRequestWrapper}），
 * 便于 {@link com.ociworker.service.LoginAuditService} 将原始 Body 写入 login_detail。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class LoginAuditBodyCaptureFilter extends OncePerRequestFilter {

    /** 超过此长度的 Body 仅缓存前 N 字节（防恶意大包） */
    private static final int MAX_BODY_CACHE = 512 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isPasswordOrTgLoginPost(request);
    }

    private static boolean isPasswordOrTgLoginPost(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = pathWithoutContext(request);
        return "/api/auth/login".equals(path) || "/api/auth/tgLogin".equals(path);
    }

    private static String pathWithoutContext(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            return uri.substring(ctx.length());
        }
        return uri;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request, MAX_BODY_CACHE);
        filterChain.doFilter(wrapped, response);
    }
}
