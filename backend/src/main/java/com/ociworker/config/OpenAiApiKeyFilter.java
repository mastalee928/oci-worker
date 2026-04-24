package com.ociworker.config;

import com.ociworker.model.entity.OciOpenaiKey;
import com.ociworker.model.entity.OciUser;
import com.ociworker.service.OciOpenaiKeyService;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.ociworker.mapper.OciUserMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 校验 {@code /v1} 请求上的本地面板颁发的 API Key，并绑定至租户。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class OpenAiApiKeyFilter extends OncePerRequestFilter {

    @Resource
    private OciOpenaiKeyService openaiKeyService;
    @Resource
    private OciUserMapper ociUserMapper;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws IOException {
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
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.toLowerCase().startsWith("bearer ")) {
            writeError(response, 401, "invalid_request_error", "请使用 Authorization: Bearer <api_key>", "auth_missing");
            return;
        }
        String token = auth.substring(7).trim();
        if (token.isEmpty()) {
            writeError(response, 401, "invalid_request_error", "Bearer token 为空", "auth_empty");
            return;
        }
        OciOpenaiKey key = openaiKeyService.findByPlainKey(token);
        if (key == null) {
            writeError(response, 401, "invalid_request_error", "API Key 无效", "invalid_api_key");
            return;
        }
        if (key.getDisabled() != null && key.getDisabled() == 1) {
            writeError(response, 403, "permission_error", "API Key 已禁用", "key_disabled");
            return;
        }
        OciUser u = ociUserMapper.selectById(key.getOciUserId());
        if (u == null) {
            writeError(response, 403, "invalid_request_error", "绑定的租户已删除", "tenant_gone");
            return;
        }
        request.setAttribute(OpenAiApiConstants.ATTR_TENANT_USER_ID, u.getId());
        request.setAttribute(OpenAiApiConstants.ATTR_OPENAI_KEY_ID, key.getId());
        try {
            openaiKeyService.updateLastUsed(key.getId());
        } catch (Exception ignored) {
        }
        filterChain.doFilter(request, response);
    }

    private static void writeError(HttpServletResponse r, int status, String type, String message, String code) throws IOException {
        r.setStatus(status);
        r.setContentType("application/json; charset=utf-8");
        String j = String.format("{\"error\":{\"type\":\"%s\",\"code\":\"%s\",\"message\":\"%s\"}}",
                escapeJson(type), escapeJson(code), escapeJson(message));
        r.getOutputStream().write(j.getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
