package com.ociworker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ociworker.config.OpenAiApiConstants;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciUser;
import com.ociworker.service.OciGenerativeOpenAiService;
import com.ociworker.service.OciOpenaiLoadBalanceService;
import jakarta.annotation.Resource;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Controller
public class OpenAiV1Controller {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private OciGenerativeOpenAiService generativeOpenAiService;
    @Resource
    private OciUserMapper ociUserMapper;
    @Resource
    private OciOpenaiLoadBalanceService loadBalanceService;

    @RequestMapping(
            value = {"/v1", "/v1/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.HEAD})
    public void v1Proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (Boolean.TRUE.equals(request.getAttribute(OpenAiApiConstants.ATTR_LB_REQUEST))) {
            v1LoadBalanceProxy(request, response);
            return;
        }
        String id = (String) request.getAttribute(OpenAiApiConstants.ATTR_TENANT_USER_ID);
        if (id == null) {
            response.setStatus(401);
            return;
        }
        OciUser u = ociUserMapper.selectById(id);
        if (u == null) {
            response.setStatus(403);
            return;
        }
        try {
            generativeOpenAiService.proxy(u, request, response);
        } catch (OciException e) {
            error(response, 502, e.getMessage() != null ? e.getMessage() : "OCI 错误");
        } catch (IOException e) {
            if (!response.isCommitted() && (e.getMessage() == null
                    || !e.getMessage().toLowerCase().contains("broken")
                    && !e.getMessage().toLowerCase().contains("aborted"))) {
                error(response, 502, e.getMessage() != null ? e.getMessage() : "转发出错");
            }
        } catch (Exception e) {
            error(response, 500, e.getMessage() != null ? e.getMessage() : "internal_error");
        }
    }

    private void v1LoadBalanceProxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String pathAfterV1 = extractPathAfterV1(request);
        if ("GET".equalsIgnoreCase(request.getMethod()) && isModelsPath(pathAfterV1)) {
            response.setStatus(200);
            response.setContentType("application/json; charset=utf-8");
            response.getOutputStream().write(loadBalanceService.modelsJson().toString().getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body = shouldReadBody(request.getMethod()) ? request.getInputStream().readAllBytes() : null;
        String requestedModel = extractModelFromBody(body, request.getContentType());
        OciOpenaiLoadBalanceService.Selection selection;
        try {
            selection = loadBalanceService.selectMember(requestedModel);
        } catch (OciException e) {
            error(response, 503, e.getMessage());
            return;
        }
        var binding = selection.binding();
        OciUser user = ociUserMapper.selectById(binding.getOciUserId());
        if (user == null) {
            loadBalanceService.finishRequest(selection.member().getId(), 403);
            error(response, 403, "负载均衡成员绑定的租户不存在");
            return;
        }
        request.setAttribute(OpenAiApiConstants.ATTR_TENANT_USER_ID, user.getId());
        request.setAttribute(OpenAiApiConstants.ATTR_OPENAI_KEY_ID, binding.getOpenaiKeyId());
        request.setAttribute(OpenAiApiConstants.ATTR_PORT_BINDING_ID, binding.getId());
        request.setAttribute(OpenAiApiConstants.ATTR_LB_MEMBER_ID, selection.member().getId());
        if (binding.getOciRegion() != null && !binding.getOciRegion().isBlank()) {
            request.setAttribute(OpenAiApiConstants.ATTR_OCI_REGION, binding.getOciRegion().trim());
        }
        if (binding.getDefaultMaxTokens() != null && binding.getDefaultMaxTokens() > 0) {
            request.setAttribute(OpenAiApiConstants.ATTR_DEFAULT_MAX_TOKENS, binding.getDefaultMaxTokens());
        }
        if (binding.getAllowedModelsJson() != null && !binding.getAllowedModelsJson().isBlank()) {
            request.setAttribute(OpenAiApiConstants.ATTR_ALLOWED_MODELS_JSON, binding.getAllowedModelsJson());
        }
        try {
            loadBalanceService.touchKey((String) request.getAttribute(OpenAiApiConstants.ATTR_LB_KEY_ID));
            HttpServletRequest proxyRequest = body == null ? request : new CachedBodyRequest(request, body);
            generativeOpenAiService.proxy(user, proxyRequest, response);
            loadBalanceService.finishRequest(selection.member().getId(), response.getStatus(), usageTokens(request));
        } catch (OciException e) {
            loadBalanceService.finishRequest(selection.member().getId(), 502);
            error(response, 502, e.getMessage() != null ? e.getMessage() : "OCI 错误");
        } catch (IOException e) {
            loadBalanceService.finishRequest(selection.member().getId(), response.getStatus() >= 400 ? response.getStatus() : 499);
            if (!response.isCommitted() && (e.getMessage() == null
                    || !e.getMessage().toLowerCase().contains("broken")
                    && !e.getMessage().toLowerCase().contains("aborted"))) {
                error(response, 502, e.getMessage() != null ? e.getMessage() : "转发出错");
            }
        } catch (Exception e) {
            loadBalanceService.finishRequest(selection.member().getId(), 500);
            error(response, 500, e.getMessage() != null ? e.getMessage() : "internal_error");
        }
    }

    private static void error(HttpServletResponse response, int status, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType("application/json; charset=utf-8");
        String safe = MAPPER.writeValueAsString(message == null ? "" : message);
        response.getOutputStream().write(
                String.format("{\"error\":{\"type\":\"oci_error\",\"message\":%s}}", safe).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean shouldReadBody(String method) {
        return !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method);
    }

    private static boolean isModelsPath(String pathAfterV1) {
        return pathAfterV1 != null && (pathAfterV1.equals("/models") || pathAfterV1.endsWith("/models"));
    }

    private static String extractPathAfterV1(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return "/";
        }
        String ctx = request.getContextPath() == null ? "" : request.getContextPath();
        if (!ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }
        int idx = path.indexOf("/v1");
        if (idx < 0) {
            return "/";
        }
        String rest = path.substring(idx + 3);
        return rest.isEmpty() ? "/" : rest;
    }

    private String extractModelFromBody(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return null;
        }
        if (contentType != null && !contentType.isBlank() && !contentType.toLowerCase().contains("json")) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode model = root == null ? null : root.get("model");
            return model != null && model.isTextual() ? model.asText() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long usageTokens(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(OpenAiApiConstants.ATTR_USAGE_TOKENS);
        if (value instanceof Number n) {
            return Math.max(0L, n.longValue());
        }
        if (value != null) {
            try {
                return Math.max(0L, Long.parseLong(String.valueOf(value).trim()));
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body == null ? new byte[0] : body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                }

                @Override
                public int read() {
                    return input.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
