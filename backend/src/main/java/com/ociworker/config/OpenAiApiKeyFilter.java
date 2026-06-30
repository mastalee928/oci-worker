package com.ociworker.config;

import com.ociworker.model.entity.OciOpenaiKey;
import com.ociworker.model.entity.OciOpenaiLbKey;
import com.ociworker.model.entity.OciOpenaiPortBinding;
import com.ociworker.model.entity.OciUser;
import com.ociworker.service.DynamicOpenAiPortService;
import com.ociworker.service.OciOpenaiLoadBalanceService;
import com.ociworker.service.OciOpenaiKeyService;
import com.ociworker.service.OracleAiModelWhitelistService;
import com.ociworker.service.OracleAiPortBindingService;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class OpenAiApiKeyFilter extends OncePerRequestFilter {

    @Resource
    private OciOpenaiKeyService openaiKeyService;
    @Resource
    private OciUserMapper ociUserMapper;
    @Resource
    private OracleAiPortBindingService portBindingService;
    @Resource
    private OciOpenaiLoadBalanceService loadBalanceService;
    @Resource
    private OracleAiModelWhitelistService modelWhitelistService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
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
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String auth = request.getHeader("Authorization");
        String token = null;
        if (auth != null && auth.toLowerCase().startsWith("bearer ")) {
            token = auth.substring(7).trim();
        } else {
            token = request.getHeader("x-api-key");
        }
        if (token == null) {
            writeError(response, 401, "invalid_request_error", "请使用 Authorization: Bearer <api_key> 或 x-api-key", "auth_missing");
            return;
        }
        token = token.trim();
        if (token.isEmpty()) {
            writeError(response, 401, "invalid_request_error", "API key 为空", "auth_empty");
            return;
        }
        int localPort = request.getLocalPort();
        if (DynamicOpenAiPortService.isLoadBalancePort(localPort)) {
            OciOpenaiLbKey lbKey = loadBalanceService.findKeyByPlain(token);
            if (lbKey == null) {
                writeError(response, 401, "invalid_request_error", "负载均衡 Key 无效", "invalid_lb_key");
                return;
            }
            if (lbKey.getDisabled() != null && lbKey.getDisabled() == 1) {
                writeError(response, 403, "permission_error", "负载均衡 Key 已禁用", "lb_key_disabled");
                return;
            }
            request.setAttribute(OpenAiApiConstants.ATTR_LB_REQUEST, Boolean.TRUE);
            request.setAttribute(OpenAiApiConstants.ATTR_LB_KEY_ID, lbKey.getId());
            filterChain.doFilter(request, response);
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
        OciOpenaiPortBinding binding = null;
        String tenantId = key.getOciUserId();
        if (DynamicOpenAiPortService.isManagedPort(localPort)) {
            binding = portBindingService.getByPort(localPort);
            if (binding == null) {
                writeError(response, 404, "invalid_request_error", "中转端口未绑定", "unknown_channel");
                return;
            }
            if (binding.getEnabled() == null || binding.getEnabled() != 1) {
                writeError(response, 403, "permission_error", "中转端口已禁用", "channel_disabled");
                return;
            }
            if (!key.getId().equals(binding.getOpenaiKeyId())) {
                writeError(response, 403, "permission_error", "API Key 不属于该中转端口", "key_not_allowed_for_channel");
                return;
            }
            tenantId = binding.getOciUserId();
        }
        OciUser u = ociUserMapper.selectById(tenantId);
        if (u == null) {
            writeError(response, 403, "invalid_request_error", "绑定的租户已删除", "tenant_gone");
            return;
        }
        request.setAttribute(OpenAiApiConstants.ATTR_TENANT_USER_ID, u.getId());
        if (binding != null && binding.getOciRegion() != null && !binding.getOciRegion().isBlank()) {
            request.setAttribute(OpenAiApiConstants.ATTR_OCI_REGION, binding.getOciRegion().trim());
        }
        request.setAttribute(OpenAiApiConstants.ATTR_OPENAI_KEY_ID, key.getId());
        if (binding != null) {
            request.setAttribute(OpenAiApiConstants.ATTR_PORT_BINDING_ID, binding.getId());
            if (binding.getDefaultMaxTokens() != null && binding.getDefaultMaxTokens() > 0) {
                request.setAttribute(OpenAiApiConstants.ATTR_DEFAULT_MAX_TOKENS, binding.getDefaultMaxTokens());
            }
            if (binding.getAllowedModelsJson() != null && !binding.getAllowedModelsJson().isBlank()) {
                request.setAttribute(OpenAiApiConstants.ATTR_ALLOWED_MODELS_JSON, binding.getAllowedModelsJson());
            }
        } else {
            String allowedModelsJson = modelWhitelistService.allowedModelsJson(u.getId());
            if (allowedModelsJson != null && !allowedModelsJson.isBlank()) {
                request.setAttribute(OpenAiApiConstants.ATTR_ALLOWED_MODELS_JSON, allowedModelsJson);
            }
        }
        try {
            openaiKeyService.updateLastUsed(key.getId());
            if (binding != null) {
                portBindingService.touchLastUsed(binding.getId());
            }
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
