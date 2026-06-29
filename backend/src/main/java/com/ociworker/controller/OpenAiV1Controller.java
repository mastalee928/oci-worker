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
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
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
            if (!response.isCommitted() && !isClientAbort(e)) {
                error(response, 502, e.getMessage() != null ? e.getMessage() : "转发出错");
            }
        } catch (Exception e) {
            error(response, 500, e.getMessage() != null ? e.getMessage() : "internal_error");
        }
    }

    private void v1LoadBalanceProxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String lbRequestId = UUID.randomUUID().toString();
        request.setAttribute(OpenAiApiConstants.ATTR_LB_REQUEST_ID, lbRequestId);
        response.setHeader("x-ociworker-lb-request-id", lbRequestId);
        String pathAfterV1 = extractPathAfterV1(request);
        if ("GET".equalsIgnoreCase(request.getMethod()) && isModelsPath(pathAfterV1)) {
            response.setStatus(200);
            response.setContentType("application/json; charset=utf-8");
            response.getOutputStream().write(loadBalanceService.modelsJson().toString().getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body = shouldReadBody(request.getMethod()) ? request.getInputStream().readAllBytes() : null;
        String requestedModel = extractModelFromBody(body, request.getContentType());
        boolean stream = isStreamRequest(body, request.getContentType());
        boolean bufferedToolStream = stream && hasToolRequest(body, request.getContentType());
        boolean requireGenerativeContext = isResponsesPath(pathAfterV1)
                && requiresResponsesGenerativeContext(requestedModel);
        long estimatedTokens = estimateTokens(body, request.getContentType());
        Set<String> triedMembers = new HashSet<>();
        int eligibleCount = loadBalanceService.eligibleMemberCount(requestedModel, estimatedTokens, requireGenerativeContext);
        if (requireGenerativeContext && eligibleCount <= 0) {
            error(response, 503, "Responses API 调用非 OpenAI 模型需要 OpenAI-Project 或 opc-conversation-store-id，自动创建默认 OpenAI-Project 失败，请检查成员租户是否有 Generative AI Project 创建权限");
            return;
        }
        int maxAttempts = stream && !bufferedToolStream
                ? 1
                : Math.max(2, Math.min(6, eligibleCount <= 0 ? 2 : eligibleCount));
        String lastError = null;
        int lastStatus = 503;
        loadBalanceService.touchKey((String) request.getAttribute(OpenAiApiConstants.ATTR_LB_KEY_ID));
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            OciOpenaiLoadBalanceService.Selection selection;
            try {
                selection = selectMemberWithBriefWait(requestedModel, estimatedTokens, triedMembers, requireGenerativeContext);
            } catch (OciException e) {
                if (attempt == 0) {
                    error(response, 503, e.getMessage());
                    return;
                }
                break;
            }
            triedMembers.add(selection.member().getId());
            var binding = selection.binding();
            OciUser user = ociUserMapper.selectById(binding.getOciUserId());
            long started = System.nanoTime();
            if (user == null) {
                long latency = elapsedMs(started);
                loadBalanceService.finishRequest(selection.member().getId(), 403, 0L, latency,
                        "tenant_missing", "负载均衡成员绑定的租户不存在", requestedModel);
                recordAttempt(request, lbRequestId, selection, requestedModel, stream, estimatedTokens, 403,
                        "failed", "tenant_missing", "负载均衡成员绑定的租户不存在", latency, attempt);
                error(response, 403, "负载均衡成员绑定的租户不存在");
                return;
            }
            resetProxyAttributes(request);
            configureProxyAttributes(request, selection);
            HttpServletResponse targetResponse = stream && !bufferedToolStream ? response : new BufferingResponse(response);
            try {
                if (stream) {
                    targetResponse.setHeader("x-ociworker-lb-member-id", selection.member().getId());
                    targetResponse.setHeader("x-ociworker-lb-port", String.valueOf(binding.getPort()));
                }
                HttpServletRequest proxyRequest = body == null ? request : new CachedBodyRequest(request, body);
                generativeOpenAiService.proxy(user, proxyRequest, targetResponse);
                long latency = elapsedMs(started);
                if (Boolean.TRUE.equals(request.getAttribute(OpenAiApiConstants.ATTR_CLIENT_ABORTED))) {
                    loadBalanceService.finishClientAborted(selection.member().getId());
                    recordAttempt(request, lbRequestId, selection, requestedModel, stream, estimatedTokens,
                            statusFrom(request, targetResponse), "client_aborted", "client_aborted", null, latency, attempt);
                    log.info("OpenAI LB client aborted requestId={} memberId={} port={}",
                            lbRequestId, selection.member().getId(), binding.getPort());
                    return;
                }
                int status = statusFrom(request, targetResponse);
                long tokens = usageTokens(request);
                String statusError = status >= 400 ? responseBodySnippet(targetResponse, "HTTP " + status) : null;
                boolean retry = attempt + 1 < maxAttempts
                        && (!stream || bufferedToolStream)
                        && isRetryableStatus(status, bufferedToolStream);
                String statusErrorType = status >= 400 ? (retry ? "retryable_status" : "upstream_status") : null;
                loadBalanceService.finishRequest(selection.member().getId(), status, tokens, latency,
                        statusErrorType, statusError, requestedModel);
                recordAttempt(request, lbRequestId, selection, requestedModel, stream, estimatedTokens, status,
                        status >= 200 && status < 400 ? "success" : "failed",
                        statusErrorType, statusError, latency, attempt);
                if (retry) {
                    lastStatus = status;
                    lastError = statusError;
                    log.warn("OpenAI LB retrying requestId={} failedMember={} port={} status={}",
                            lbRequestId, selection.member().getId(), binding.getPort(), status);
                    continue;
                }
                response.setHeader("x-ociworker-lb-member-id", selection.member().getId());
                response.setHeader("x-ociworker-lb-port", String.valueOf(binding.getPort()));
                if (targetResponse instanceof BufferingResponse buffered) {
                    buffered.copyTo(response);
                }
                return;
            } catch (OciException e) {
                long latency = elapsedMs(started);
                String message = e.getMessage() != null ? e.getMessage() : "OCI 错误";
                loadBalanceService.finishRequest(selection.member().getId(), 502, 0L, latency,
                        errorType(request, "oci_error"), message, requestedModel);
                recordAttempt(request, lbRequestId, selection, requestedModel, stream, estimatedTokens, 502,
                        "failed", errorType(request, "oci_error"), message, latency, attempt);
                lastStatus = 502;
                lastError = message;
                log.warn("OpenAI LB upstream error requestId={} memberId={} port={} message={}",
                        lbRequestId, selection.member().getId(), binding.getPort(), message);
                if ((!stream || bufferedToolStream) && attempt + 1 < maxAttempts) {
                    continue;
                }
                error(response, 502, message);
                return;
            } catch (IOException e) {
                long latency = elapsedMs(started);
                if (isClientAbort(e)) {
                    loadBalanceService.finishClientAborted(selection.member().getId());
                    recordAttempt(request, lbRequestId, selection, requestedModel, stream, estimatedTokens,
                            statusFrom(request, targetResponse), "client_aborted", "client_aborted", e.getMessage(), latency, attempt);
                    log.info("OpenAI LB client aborted requestId={} memberId={} port={} message={}",
                            lbRequestId, selection.member().getId(), binding.getPort(), e.getMessage());
                    return;
                }
                String type = errorType(request, "io_error");
                int status = statusFrom(request, targetResponse);
                status = status >= 400 ? status : 502;
                loadBalanceService.finishRequest(selection.member().getId(), status, 0L, latency,
                        type, e.getMessage(), requestedModel);
                recordAttempt(request, lbRequestId, selection, requestedModel, stream, estimatedTokens, status,
                        "failed", type, e.getMessage(), latency, attempt);
                lastStatus = status;
                lastError = e.getMessage() != null ? e.getMessage() : "转发出错";
                log.warn("OpenAI LB IO error requestId={} memberId={} port={} message={}",
                        lbRequestId, selection.member().getId(), binding.getPort(), e.getMessage());
                if ((!stream || bufferedToolStream) && attempt + 1 < maxAttempts) {
                    continue;
                }
                if (!response.isCommitted()) {
                    error(response, status, lastError);
                }
                return;
            } catch (Exception e) {
                long latency = elapsedMs(started);
                String message = e.getMessage() != null ? e.getMessage() : "internal_error";
                loadBalanceService.finishRequest(selection.member().getId(), 500, 0L, latency,
                        "internal_error", message, requestedModel);
                recordAttempt(request, lbRequestId, selection, requestedModel, stream, estimatedTokens, 500,
                        "failed", "internal_error", message, latency, attempt);
                log.warn("OpenAI LB internal error requestId={} memberId={} port={} message={}",
                        lbRequestId, selection.member().getId(), binding.getPort(), message);
                error(response, 500, message);
                return;
            }
        }
        error(response, lastStatus, lastError != null ? lastError : "没有可用的负载均衡成员");
    }

    private OciOpenaiLoadBalanceService.Selection selectMemberWithBriefWait(
            String requestedModel,
            long estimatedTokens,
            Set<String> triedMembers,
            boolean requireGenerativeContext) {
        OciException last = null;
        for (int i = 0; i < 4; i++) {
            try {
                return loadBalanceService.selectMember(requestedModel, estimatedTokens, triedMembers, requireGenerativeContext);
            } catch (OciException e) {
                last = e;
                if (i >= 3) {
                    break;
                }
                try {
                    Thread.sleep(250L * (i + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last == null ? new OciException("没有可用的负载均衡成员") : last;
    }

    private void configureProxyAttributes(HttpServletRequest request, OciOpenaiLoadBalanceService.Selection selection) {
        var binding = selection.binding();
        request.setAttribute(OpenAiApiConstants.ATTR_TENANT_USER_ID, binding.getOciUserId());
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
        setPositiveAttr(request, OpenAiApiConstants.ATTR_STREAM_FIRST_CHUNK_TIMEOUT_SECONDS,
                selection.member().getStreamFirstChunkTimeoutSeconds());
        setPositiveAttr(request, OpenAiApiConstants.ATTR_STREAM_IDLE_TIMEOUT_SECONDS,
                selection.member().getStreamIdleTimeoutSeconds());
        setPositiveAttr(request, OpenAiApiConstants.ATTR_STREAM_MAX_SECONDS,
                selection.member().getStreamMaxSeconds());
    }

    private static void resetProxyAttributes(HttpServletRequest request) {
        request.removeAttribute(OpenAiApiConstants.ATTR_TENANT_USER_ID);
        request.removeAttribute(OpenAiApiConstants.ATTR_OCI_REGION);
        request.removeAttribute(OpenAiApiConstants.ATTR_OPENAI_KEY_ID);
        request.removeAttribute(OpenAiApiConstants.ATTR_PORT_BINDING_ID);
        request.removeAttribute(OpenAiApiConstants.ATTR_LB_MEMBER_ID);
        request.removeAttribute(OpenAiApiConstants.ATTR_DEFAULT_MAX_TOKENS);
        request.removeAttribute(OpenAiApiConstants.ATTR_ALLOWED_MODELS_JSON);
        request.removeAttribute(OpenAiApiConstants.ATTR_STREAM_FIRST_CHUNK_TIMEOUT_SECONDS);
        request.removeAttribute(OpenAiApiConstants.ATTR_STREAM_IDLE_TIMEOUT_SECONDS);
        request.removeAttribute(OpenAiApiConstants.ATTR_STREAM_MAX_SECONDS);
        request.removeAttribute(OpenAiApiConstants.ATTR_GENERATIVE_OPENAI_PROJECT);
        request.removeAttribute(OpenAiApiConstants.ATTR_GENERATIVE_CONVERSATION_STORE_ID);
        request.removeAttribute(OpenAiApiConstants.ATTR_CLIENT_ABORTED);
        request.removeAttribute(OpenAiApiConstants.ATTR_USAGE_TOKENS);
        request.removeAttribute(OpenAiApiConstants.ATTR_UPSTREAM_STATUS);
        request.removeAttribute(OpenAiApiConstants.ATTR_STREAM_FIRST_CHUNK_MS);
        request.removeAttribute(OpenAiApiConstants.ATTR_STREAM_CHUNK_COUNT);
        request.removeAttribute(OpenAiApiConstants.ATTR_STREAM_TIMEOUT_TYPE);
        request.removeAttribute(OpenAiApiConstants.ATTR_STREAM_ESTIMATED_TOKENS);
    }

    private static void setPositiveAttr(HttpServletRequest request, String name, Integer value) {
        if (value != null && value > 0) {
            request.setAttribute(name, value);
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

    private static boolean isResponsesPath(String pathAfterV1) {
        return pathAfterV1 != null && (pathAfterV1.equals("/responses") || pathAfterV1.endsWith("/responses"));
    }

    private static boolean requiresResponsesGenerativeContext(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            return false;
        }
        String model = requestedModel.trim().toLowerCase();
        return !model.startsWith("openai.");
    }

    private static boolean isClientAbort(IOException e) {
        String message = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return message.contains("broken pipe")
                || message.contains("aborted")
                || message.contains("connection reset")
                || message.contains("reset by peer")
                || message.contains("clientabort");
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

    private static boolean hasToolRequest(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return false;
        }
        if (contentType != null && !contentType.isBlank() && !contentType.toLowerCase().contains("json")) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                return false;
            }
            JsonNode tools = root.get("tools");
            if (tools != null && tools.isArray() && tools.size() > 0) {
                return true;
            }
            JsonNode functions = root.get("functions");
            return functions != null && functions.isArray() && functions.size() > 0;
        } catch (Exception ignored) {
            return false;
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

    private void recordAttempt(
            HttpServletRequest request,
            String lbRequestId,
            OciOpenaiLoadBalanceService.Selection selection,
            String model,
            boolean stream,
            long estimatedTokens,
            int statusCode,
            String status,
            String errorType,
            String errorMessage,
            long latencyMs,
            int retryCount) {
        loadBalanceService.recordRequestLog(new OciOpenaiLoadBalanceService.RequestLogInput(
                lbRequestId,
                (String) request.getAttribute(OpenAiApiConstants.ATTR_LB_KEY_ID),
                selection.member().getId(),
                selection.binding().getId(),
                selection.binding().getPort(),
                model,
                stream,
                estimatedTokens,
                statusCode,
                status,
                errorType,
                errorMessage,
                latencyMs,
                longAttr(request, OpenAiApiConstants.ATTR_STREAM_FIRST_CHUNK_MS),
                intAttr(request, OpenAiApiConstants.ATTR_STREAM_CHUNK_COUNT),
                usageTokens(request),
                Boolean.TRUE.equals(request.getAttribute(OpenAiApiConstants.ATTR_CLIENT_ABORTED)),
                retryCount));
    }

    private static int statusFrom(HttpServletRequest request, HttpServletResponse response) {
        Object upstream = request == null ? null : request.getAttribute(OpenAiApiConstants.ATTR_UPSTREAM_STATUS);
        if (upstream instanceof Number n) {
            return n.intValue();
        }
        int status = response == null ? 0 : response.getStatus();
        return status > 0 ? status : 200;
    }

    private static boolean isRetryableStatus(int status, boolean bufferedToolStream) {
        if (status == 429 || status >= 500) {
            return true;
        }
        if (!bufferedToolStream) {
            return false;
        }
        if (status != 400 && status != 401 && status != 403 && status != 404 && status != 422) {
            return false;
        }
        return true;
    }

    private static String responseBodySnippet(HttpServletResponse response, String fallback) {
        if (response instanceof BufferingResponse buffered) {
            String body = buffered.bodyText();
            if (body != null && !body.isBlank()) {
                String value = body.trim();
                return value.length() > 500 ? value.substring(0, 500) : value;
            }
        }
        return fallback;
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String errorType(HttpServletRequest request, String fallback) {
        Object timeout = request == null ? null : request.getAttribute(OpenAiApiConstants.ATTR_STREAM_TIMEOUT_TYPE);
        if (timeout != null && !String.valueOf(timeout).isBlank()) {
            return "stream_" + timeout;
        }
        return fallback;
    }

    private static Long longAttr(HttpServletRequest request, String attr) {
        Object value = request == null ? null : request.getAttribute(attr);
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Integer intAttr(HttpServletRequest request, String attr) {
        Long value = longAttr(request, attr);
        return value == null ? null : (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
    }

    private static boolean isStreamRequest(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return false;
        }
        if (contentType != null && !contentType.isBlank() && !contentType.toLowerCase().contains("json")) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode stream = root == null ? null : root.get("stream");
            return stream != null && stream.isBoolean() && stream.asBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long estimateTokens(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return 0L;
        }
        if (contentType != null && !contentType.isBlank() && !contentType.toLowerCase().contains("json")) {
            return Math.max(1L, body.length / 4L);
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            long chars = countTextChars(root);
            long maxTokens = numericField(root, "max_tokens", "maxTokens", "max_output_tokens", "maxOutputTokens");
            long estimate = Math.max(1L, (chars + 3L) / 4L);
            return estimate + Math.max(0L, maxTokens);
        } catch (Exception ignored) {
            return Math.max(1L, body.length / 4L);
        }
    }

    private static long countTextChars(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0L;
        }
        if (node.isTextual()) {
            return node.asText("").length();
        }
        long total = 0L;
        if (node.isArray()) {
            for (JsonNode child : node) {
                total += countTextChars(child);
            }
        } else if (node.isObject()) {
            for (var it = node.properties().iterator(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase();
                if ("model".equals(key) || "stream".equals(key)) {
                    continue;
                }
                total += countTextChars(entry.getValue());
            }
        }
        return total;
    }

    private static long numericField(JsonNode root, String... names) {
        if (root == null || !root.isObject()) {
            return 0L;
        }
        for (String name : names) {
            JsonNode value = root.get(name);
            if (value != null && value.isNumber()) {
                return Math.max(0L, value.asLong());
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

    private static final class BufferingResponse extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream(8192);
        private final Map<String, Collection<String>> headers = new LinkedHashMap<>();
        private ServletOutputStream outputStream;
        private PrintWriter writer;
        private int status = 200;
        private String contentType;
        private String characterEncoding = StandardCharsets.UTF_8.name();

        private BufferingResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setStatus(int sc) {
            this.status = sc;
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public void sendError(int sc) {
            this.status = sc;
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            this.status = sc;
            if (msg != null) {
                body.write(msg.getBytes(StandardCharsets.UTF_8));
            }
        }

        @Override
        public void setHeader(String name, String value) {
            if (name != null) {
                headers.put(name, new java.util.ArrayList<>(value == null ? List.of() : List.of(value)));
            }
        }

        @Override
        public void addHeader(String name, String value) {
            if (name != null && value != null) {
                headers.computeIfAbsent(name, ignored -> new java.util.ArrayList<>()).add(value);
            }
        }

        @Override
        public Collection<String> getHeaderNames() {
            return headers.keySet();
        }

        @Override
        public Collection<String> getHeaders(String name) {
            return headers.getOrDefault(name, List.of());
        }

        @Override
        public String getHeader(String name) {
            Collection<String> values = getHeaders(name);
            return values.isEmpty() ? null : values.iterator().next();
        }

        @Override
        public void setContentType(String type) {
            this.contentType = type;
            setHeader("content-type", type);
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public void setCharacterEncoding(String charset) {
            if (charset != null && !charset.isBlank()) {
                this.characterEncoding = charset;
            }
        }

        @Override
        public String getCharacterEncoding() {
            return characterEncoding;
        }

        @Override
        public ServletOutputStream getOutputStream() {
            if (outputStream == null) {
                outputStream = new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener writeListener) {
                    }

                    @Override
                    public void write(int b) {
                        body.write(b);
                    }
                };
            }
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() {
            if (writer == null) {
                writer = new PrintWriter(body, true, StandardCharsets.UTF_8);
            }
            return writer;
        }

        @Override
        public void flushBuffer() {
            if (writer != null) {
                writer.flush();
            }
        }

        @Override
        public boolean isCommitted() {
            return false;
        }

        private void copyTo(HttpServletResponse response) throws IOException {
            flushBuffer();
            response.setStatus(status);
            for (Map.Entry<String, Collection<String>> entry : headers.entrySet()) {
                String name = entry.getKey();
                if (name == null || "transfer-encoding".equalsIgnoreCase(name)
                        || "connection".equalsIgnoreCase(name)
                        || "content-length".equalsIgnoreCase(name)) {
                    continue;
                }
                boolean first = true;
                for (String value : entry.getValue()) {
                    if (value == null) {
                        continue;
                    }
                    if (first) {
                        response.setHeader(name, value);
                        first = false;
                    } else {
                        response.addHeader(name, value);
                    }
                }
            }
            response.getOutputStream().write(body.toByteArray());
        }

        private String bodyText() {
            flushBuffer();
            return body.toString(StandardCharsets.UTF_8);
        }
    }
}
