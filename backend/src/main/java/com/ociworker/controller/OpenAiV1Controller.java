package com.ociworker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        String pathAfterV1 = extractPathAfterV1(request);
        boolean anthropicLikeRequest = isMessagesPath(pathAfterV1) || isMessagesCountTokensPath(pathAfterV1);
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
            if (isMessagesCountTokensPath(pathAfterV1) && shouldReadBody(request.getMethod())) {
                byte[] originalBody = request.getInputStream().readAllBytes();
                writeAnthropicCountTokensResponse(response, originalBody, request.getContentType());
                return;
            }
            if (isMessagesPath(pathAfterV1) && shouldReadBody(request.getMethod())) {
                byte[] originalBody = request.getInputStream().readAllBytes();
                String modelHint = extractAnthropicModelFromBody(originalBody, request.getContentType());
                boolean anthropicStream = isStreamRequest(originalBody, request.getContentType());
                byte[] chatBody = transformAnthropicMessagesToChatCompletionsJson(originalBody);
                BufferingResponse buffered = new BufferingResponse(response);
                HttpServletRequest proxyRequest = new CachedBodyRequest(request, chatBody, "/chat/completions");
                generativeOpenAiService.proxy(u, proxyRequest, buffered);
                writeAnthropicMessagesResponse(
                        response,
                        buffered,
                        anthropicStream,
                        firstNonBlank(extractModelFromBody(chatBody, request.getContentType()), modelHint));
                return;
            }
            generativeOpenAiService.proxy(u, request, response);
        } catch (OciException e) {
            if (anthropicLikeRequest) {
                anthropicError(response, 502, "api_error", e.getMessage() != null ? e.getMessage() : "OCI 错误");
            } else {
                error(response, 502, e.getMessage() != null ? e.getMessage() : "OCI 错误");
            }
        } catch (IOException e) {
            if (!response.isCommitted() && !isClientAbort(e)) {
                if (anthropicLikeRequest) {
                    anthropicError(response, 502, "api_error", e.getMessage() != null ? e.getMessage() : "转发出错");
                } else {
                    error(response, 502, e.getMessage() != null ? e.getMessage() : "转发出错");
                }
            }
        } catch (Exception e) {
            if (anthropicLikeRequest) {
                anthropicError(response, 500, "api_error", e.getMessage() != null ? e.getMessage() : "internal_error");
            } else {
                error(response, 500, e.getMessage() != null ? e.getMessage() : "internal_error");
            }
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
        String requestLogPath = pathAfterV1;
        boolean anthropicMessages = isMessagesPath(pathAfterV1);
        boolean anthropicStream = anthropicMessages && isStreamRequest(body, request.getContentType());
        if (isMessagesCountTokensPath(pathAfterV1)) {
            writeAnthropicCountTokensResponse(response, body, request.getContentType());
            return;
        }
        if (anthropicMessages) {
            request.setAttribute("ociworker.lb.bridgeType", anthropicBridgeType(body, request.getContentType()));
            body = transformAnthropicMessagesToChatCompletionsJson(body);
            pathAfterV1 = "/chat/completions";
        }
        String requestedModel = extractModelFromBody(body, request.getContentType());
        boolean stream = isStreamRequest(body, request.getContentType());
        boolean bufferedToolStream = stream && hasToolRequest(body, request.getContentType());
        int toolCount = toolCount(body, request.getContentType());
        request.setAttribute("ociworker.lb.requestPath", normalizeRequestPath(requestLogPath));
        request.setAttribute("ociworker.lb.hasTools", toolCount > 0);
        request.setAttribute("ociworker.lb.toolCount", toolCount);
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
                    if (anthropicMessages) {
                        anthropicError(response, 503, "overloaded_error", e.getMessage());
                    } else {
                        error(response, 503, e.getMessage());
                    }
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
            HttpServletResponse targetResponse = (stream && !bufferedToolStream && !anthropicMessages)
                    ? response
                    : new BufferingResponse(response);
            try {
                if (stream) {
                    targetResponse.setHeader("x-ociworker-lb-member-id", selection.member().getId());
                    targetResponse.setHeader("x-ociworker-lb-port", String.valueOf(binding.getPort()));
                }
                HttpServletRequest proxyRequest = body == null
                        ? request
                        : new CachedBodyRequest(request, body, anthropicMessages ? "/chat/completions" : null);
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
                    if (anthropicMessages) {
                        writeAnthropicMessagesResponse(response, buffered, anthropicStream, requestedModel);
                    } else {
                        buffered.copyTo(response);
                    }
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
                if (anthropicMessages) {
                    anthropicError(response, 502, "api_error", message);
                } else {
                    error(response, 502, message);
                }
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
                    if (anthropicMessages) {
                        anthropicError(response, status, status >= 500 ? "api_error" : "invalid_request_error", lastError);
                    } else {
                        error(response, status, lastError);
                    }
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
                if (anthropicMessages) {
                    anthropicError(response, 500, "api_error", message);
                } else {
                    error(response, 500, message);
                }
                return;
            }
        }
        if (anthropicMessages) {
            anthropicError(response, lastStatus, lastStatus >= 500 ? "api_error" : "invalid_request_error",
                    lastError != null ? lastError : "没有可用的负载均衡成员");
        } else {
            error(response, lastStatus, lastError != null ? lastError : "没有可用的负载均衡成员");
        }
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

    private static void anthropicError(HttpServletResponse response, int status, String type, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType("application/json; charset=utf-8");
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "error");
        ObjectNode error = MAPPER.createObjectNode();
        error.put("type", firstNonBlank(type, "api_error"));
        error.put("message", firstNonBlank(message, "请求失败"));
        root.set("error", error);
        response.getOutputStream().write(MAPPER.writeValueAsBytes(root));
    }

    private static void writeAnthropicCountTokensResponse(
            HttpServletResponse response,
            byte[] body,
            String contentType) throws IOException {
        response.setStatus(200);
        response.setContentType("application/json; charset=utf-8");
        ObjectNode root = MAPPER.createObjectNode();
        root.put("input_tokens", Math.max(1L, estimateInputTokens(body, contentType)));
        response.getOutputStream().write(MAPPER.writeValueAsBytes(root));
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

    private static boolean isMessagesPath(String pathAfterV1) {
        return pathAfterV1 != null && (pathAfterV1.equals("/messages") || pathAfterV1.endsWith("/messages"));
    }

    private static boolean isMessagesCountTokensPath(String pathAfterV1) {
        return pathAfterV1 != null
                && (pathAfterV1.equals("/count_tokens")
                || pathAfterV1.endsWith("/count_tokens")
                || pathAfterV1.equals("/messages/count_tokens")
                || pathAfterV1.endsWith("/messages/count_tokens"));
    }

    private static String normalizeRequestPath(String pathAfterV1) {
        if (pathAfterV1 == null || pathAfterV1.isBlank()) {
            return "/";
        }
        if (pathAfterV1.equals("/chat/completions") || pathAfterV1.endsWith("/chat/completions")) {
            return "chat/completions";
        }
        if (pathAfterV1.equals("/responses") || pathAfterV1.endsWith("/responses")) {
            return "responses";
        }
        if (pathAfterV1.equals("/messages") || pathAfterV1.endsWith("/messages")) {
            return "messages";
        }
        if (pathAfterV1.equals("/models") || pathAfterV1.endsWith("/models")) {
            return "models";
        }
        return pathAfterV1.length() > 64 ? pathAfterV1.substring(0, 64) : pathAfterV1;
    }

    static byte[] transformAnthropicMessagesToChatCompletionsJson(byte[] input) {
        if (input == null || input.length == 0) {
            return input;
        }
        try {
            JsonNode root = MAPPER.readTree(input);
            if (root == null || !root.isObject()) {
                return input;
            }
            ObjectNode in = (ObjectNode) root;
            ObjectNode out = MAPPER.createObjectNode();
            String model = sanitizeAnthropicModel(text(in, "model"));
            if (model != null && !model.isBlank()) {
                out.put("model", model);
            }
            ArrayNode messages = MAPPER.createArrayNode();
            appendAnthropicSystem(messages, in.get("system"));
            JsonNode inMessages = in.get("messages");
            if (inMessages != null && inMessages.isArray()) {
                for (JsonNode message : inMessages) {
                    appendAnthropicMessage(messages, message);
                }
            }
            if (messages.isEmpty()) {
                ObjectNode user = MAPPER.createObjectNode();
                user.put("role", "user");
                user.put("content", "");
                messages.add(user);
            }
            out.set("messages", messages);
            JsonNode tools = in.get("tools");
            if (tools != null && tools.isArray()) {
                ArrayNode chatTools = MAPPER.createArrayNode();
                for (JsonNode tool : tools) {
                    if (tool == null || !tool.isObject()) {
                        continue;
                    }
                    ObjectNode src = (ObjectNode) tool;
                    ObjectNode chatTool = MAPPER.createObjectNode();
                    chatTool.put("type", "function");
                    ObjectNode fn = MAPPER.createObjectNode();
                    copyText(src, fn, "name");
                    copyText(src, fn, "description");
                    JsonNode schema = src.get("input_schema");
                    if (schema != null && !schema.isNull() && !schema.isMissingNode()) {
                        fn.set("parameters", schema);
                    }
                    chatTool.set("function", fn);
                    chatTools.add(chatTool);
                }
                if (!chatTools.isEmpty()) {
                    out.set("tools", chatTools);
                    JsonNode toolChoice = anthropicToolChoiceToChatToolChoice(in.get("tool_choice"));
                    if (toolChoice != null) {
                        out.set("tool_choice", toolChoice);
                    } else {
                        out.put("tool_choice", "auto");
                    }
                }
            }
            JsonNode maxTokens = in.get("max_tokens");
            if (maxTokens != null && maxTokens.isNumber()) {
                out.set("max_tokens", maxTokens);
            }
            copyIfPresent(in, out, "temperature");
            copyIfPresent(in, out, "top_p");
            out.put("stream", false);
            return MAPPER.writeValueAsBytes(out);
        } catch (Exception ignored) {
            return input;
        }
    }

    private static void appendAnthropicSystem(ArrayNode messages, JsonNode system) {
        String text = anthropicContentText(system);
        if (text == null || text.isBlank()) {
            return;
        }
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", "system");
        msg.put("content", text);
        messages.add(msg);
    }

    private static void appendAnthropicMessage(ArrayNode messages, JsonNode message) {
        if (message == null || !message.isObject()) {
            return;
        }
        ObjectNode src = (ObjectNode) message;
        String role = text(src, "role");
        JsonNode content = src.get("content");
        if ("assistant".equalsIgnoreCase(role) && content != null && content.isArray()) {
            ArrayNode toolCalls = MAPPER.createArrayNode();
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                if (part == null || !part.isObject()) {
                    continue;
                }
                ObjectNode po = (ObjectNode) part;
                String type = text(po, "type");
                if ("tool_use".equalsIgnoreCase(type)) {
                    ObjectNode call = MAPPER.createObjectNode();
                    call.put("id", firstNonBlank(text(po, "id"), "toolu_" + UUID.randomUUID()));
                    call.put("type", "function");
                    ObjectNode fn = MAPPER.createObjectNode();
                    fn.put("name", firstNonBlank(text(po, "name"), "tool"));
                    JsonNode input = po.get("input");
                    fn.put("arguments", input == null || input.isNull() ? "{}" : input.toString());
                    call.set("function", fn);
                    toolCalls.add(call);
                } else if ("text".equalsIgnoreCase(type)) {
                    appendText(text, text(po, "text"));
                }
            }
            ObjectNode msg = MAPPER.createObjectNode();
            msg.put("role", "assistant");
            msg.put("content", text.toString());
            if (!toolCalls.isEmpty()) {
                msg.set("tool_calls", toolCalls);
            }
            messages.add(msg);
            return;
        }
        if ("user".equalsIgnoreCase(role) && content != null && content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                if (part == null || !part.isObject()) {
                    continue;
                }
                ObjectNode po = (ObjectNode) part;
                String type = text(po, "type");
                if ("tool_result".equalsIgnoreCase(type)) {
                    ObjectNode tool = MAPPER.createObjectNode();
                    tool.put("role", "tool");
                    tool.put("tool_call_id", firstNonBlank(text(po, "tool_use_id"), text(po, "id"), "toolu_unknown"));
                    tool.put("content", anthropicContentText(po.get("content")));
                    messages.add(tool);
                } else if ("text".equalsIgnoreCase(type)) {
                    appendText(text, text(po, "text"));
                } else {
                    appendText(text, unsupportedAnthropicContentText(type));
                }
            }
            if (text.length() > 0) {
                ObjectNode msg = MAPPER.createObjectNode();
                msg.put("role", "user");
                msg.put("content", text.toString());
                messages.add(msg);
            }
            return;
        }
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", "assistant".equalsIgnoreCase(role) ? "assistant" : "user");
        msg.put("content", anthropicContentText(content));
        messages.add(msg);
    }

    private static JsonNode anthropicToolChoiceToChatToolChoice(JsonNode toolChoice) {
        if (toolChoice == null || toolChoice.isNull() || toolChoice.isMissingNode()) {
            return null;
        }
        String type;
        String name = null;
        if (toolChoice.isTextual()) {
            type = toolChoice.asText();
        } else if (toolChoice.isObject()) {
            type = text(toolChoice, "type");
            name = text(toolChoice, "name");
        } else {
            return null;
        }
        if (type == null || type.isBlank() || "auto".equalsIgnoreCase(type)) {
            return MAPPER.getNodeFactory().textNode("auto");
        }
        if ("none".equalsIgnoreCase(type)) {
            return MAPPER.getNodeFactory().textNode("none");
        }
        if ("any".equalsIgnoreCase(type)) {
            return MAPPER.getNodeFactory().textNode("required");
        }
        if ("tool".equalsIgnoreCase(type) && name != null && !name.isBlank()) {
            ObjectNode out = MAPPER.createObjectNode();
            out.put("type", "function");
            ObjectNode function = MAPPER.createObjectNode();
            function.put("name", name);
            out.set("function", function);
            return out;
        }
        return null;
    }

    private static void writeAnthropicMessagesResponse(
            HttpServletResponse response,
            BufferingResponse buffered,
            boolean stream,
            String modelHint) throws IOException {
        if (buffered.getStatus() >= 400) {
            anthropicError(
                    response,
                    buffered.getStatus(),
                    buffered.getStatus() >= 500 ? "api_error" : "invalid_request_error",
                    extractErrorMessage(buffered.bodyText()));
            return;
        }
        ObjectNode message = chatCompletionToAnthropicMessage(buffered.bodyText(), modelHint);
        response.setStatus(buffered.getStatus());
        if (stream) {
            response.setContentType("text/event-stream; charset=utf-8");
            writeAnthropicMessageSse(response, message);
        } else {
            response.setContentType("application/json; charset=utf-8");
            response.getOutputStream().write(MAPPER.writeValueAsBytes(message));
        }
    }

    static ObjectNode chatCompletionToAnthropicMessage(String body, String modelHint) {
        ObjectNode out = MAPPER.createObjectNode();
        String id = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String model = modelHint;
        String stopReason = "end_turn";
        ArrayNode content = MAPPER.createArrayNode();
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root != null && root.isObject()) {
                id = firstNonBlank(text((ObjectNode) root, "id"), id);
                model = firstNonBlank(model, text((ObjectNode) root, "model"));
                JsonNode choice = root.path("choices").isArray() && !root.path("choices").isEmpty()
                        ? root.path("choices").get(0)
                        : null;
                JsonNode message = choice == null ? null : choice.path("message");
                if (choice != null && "tool_calls".equalsIgnoreCase(text(choice, "finish_reason"))) {
                    stopReason = "tool_use";
                }
                String text = chatContentText(message == null ? null : message.get("content"));
                if (text != null && !text.isBlank()) {
                    ObjectNode textPart = MAPPER.createObjectNode();
                    textPart.put("type", "text");
                    textPart.put("text", text);
                    content.add(textPart);
                }
                JsonNode toolCalls = message == null ? null : message.get("tool_calls");
                if (toolCalls != null && toolCalls.isArray()) {
                    for (JsonNode call : toolCalls) {
                        if (call == null || !call.isObject()) {
                            continue;
                        }
                        ObjectNode toolUse = MAPPER.createObjectNode();
                        toolUse.put("type", "tool_use");
                        toolUse.put("id", firstNonBlank(text((ObjectNode) call, "id"), "toolu_" + UUID.randomUUID()));
                        JsonNode fnNode = call.get("function");
                        ObjectNode fn = fnNode != null && fnNode.isObject() ? (ObjectNode) fnNode : MAPPER.createObjectNode();
                        toolUse.put("name", firstNonBlank(text(fn, "name"), "tool"));
                        toolUse.set("input", parseJsonObjectOrEmpty(text(fn, "arguments")));
                        content.add(toolUse);
                    }
                }
                JsonNode usage = root.get("usage");
                if (usage != null && usage.isObject()) {
                    ObjectNode anthropicUsage = MAPPER.createObjectNode();
                    anthropicUsage.put("input_tokens", Math.max(0, numeric(usage, "prompt_tokens", "input_tokens")));
                    anthropicUsage.put("output_tokens", Math.max(0, numeric(usage, "completion_tokens", "output_tokens")));
                    out.set("usage", anthropicUsage);
                }
            }
        } catch (Exception ignored) {
        }
        if (content.isEmpty()) {
            ObjectNode textPart = MAPPER.createObjectNode();
            textPart.put("type", "text");
            textPart.put("text", "");
            content.add(textPart);
        }
        out.put("id", id);
        out.put("type", "message");
        out.put("role", "assistant");
        out.put("model", firstNonBlank(model, ""));
        out.set("content", content);
        out.put("stop_reason", stopReason);
        out.putNull("stop_sequence");
        if (out.get("usage") == null) {
            ObjectNode usage = MAPPER.createObjectNode();
            usage.put("input_tokens", 0);
            usage.put("output_tokens", 0);
            out.set("usage", usage);
        }
        return out;
    }

    private static String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "请求失败";
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode message = root.path("error").path("message");
            if (message.isTextual() && !message.asText().isBlank()) {
                return message.asText();
            }
            JsonNode direct = root.path("message");
            if (direct.isTextual() && !direct.asText().isBlank()) {
                return direct.asText();
            }
        } catch (Exception ignored) {
        }
        String value = body.trim();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static void writeAnthropicMessageSse(HttpServletResponse response, ObjectNode message) throws IOException {
        ServletOutputStream out = response.getOutputStream();
        ObjectNode startMessage = message.deepCopy();
        startMessage.set("content", MAPPER.createArrayNode());
        startMessage.putNull("stop_reason");
        writeAnthropicSse(out, "message_start", objectNode("type", "message_start", "message", startMessage));
        JsonNode content = message.get("content");
        if (content != null && content.isArray()) {
            for (int i = 0; i < content.size(); i++) {
                JsonNode part = content.get(i);
                if (!(part instanceof ObjectNode partObject)) {
                    continue;
                }
                String type = text(partObject, "type");
                ObjectNode startBlock = MAPPER.createObjectNode();
                startBlock.put("type", firstNonBlank(type, "text"));
                if ("tool_use".equalsIgnoreCase(type)) {
                    startBlock.put("id", firstNonBlank(text(partObject, "id"), "toolu_" + UUID.randomUUID()));
                    startBlock.put("name", firstNonBlank(text(partObject, "name"), "tool"));
                    startBlock.set("input", MAPPER.createObjectNode());
                } else {
                    startBlock.put("text", "");
                }
                writeAnthropicSse(out, "content_block_start",
                        objectNode("type", "content_block_start", "index", i, "content_block", startBlock));
                if ("text".equalsIgnoreCase(type)) {
                    ObjectNode delta = MAPPER.createObjectNode();
                    delta.put("type", "text_delta");
                    delta.put("text", text(partObject, "text"));
                    writeAnthropicSse(out, "content_block_delta",
                            objectNode("type", "content_block_delta", "index", i, "delta", delta));
                } else if ("tool_use".equalsIgnoreCase(type)) {
                    ObjectNode delta = MAPPER.createObjectNode();
                    delta.put("type", "input_json_delta");
                    JsonNode input = partObject.get("input");
                    delta.put("partial_json", input == null || input.isNull() ? "{}" : input.toString());
                    writeAnthropicSse(out, "content_block_delta",
                            objectNode("type", "content_block_delta", "index", i, "delta", delta));
                }
                writeAnthropicSse(out, "content_block_stop", objectNode("type", "content_block_stop", "index", i));
            }
        }
        ObjectNode delta = MAPPER.createObjectNode();
        delta.put("stop_reason", text(message, "stop_reason"));
        delta.putNull("stop_sequence");
        writeAnthropicSse(out, "message_delta",
                objectNode("type", "message_delta", "delta", delta, "usage", message.get("usage")));
        writeAnthropicSse(out, "message_stop", objectNode("type", "message_stop"));
        out.flush();
    }

    private static String sanitizeAnthropicModel(String model) {
        return model == null ? null : model.trim();
    }

    private static void copyText(ObjectNode source, ObjectNode target, String field) {
        String value = text(source, field);
        if (value != null && !value.isBlank()) {
            target.put(field, value);
        }
    }

    private static void copyIfPresent(ObjectNode source, ObjectNode target, String field) {
        JsonNode value = source == null ? null : source.get(field);
        if (value != null && !value.isNull() && !value.isMissingNode()) {
            target.set(field, value);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject() || field == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void appendText(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(value);
    }

    private static String anthropicContentText(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText("");
        }
        if (content.isValueNode()) {
            return content.asText("");
        }
        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode part : content) {
                if (part == null || part.isNull()) {
                    continue;
                }
                if (part.isTextual()) {
                    appendText(sb, part.asText());
                    continue;
                }
                if (part.isObject()) {
                    String type = text(part, "type");
                    if ("text".equalsIgnoreCase(type)) {
                        appendText(sb, text(part, "text"));
                    } else if ("tool_result".equalsIgnoreCase(type)) {
                        appendText(sb, anthropicContentText(part.get("content")));
                    } else {
                        appendText(sb, unsupportedAnthropicContentText(type));
                    }
                }
            }
            return sb.toString();
        }
        if (content.isObject()) {
            String type = text(content, "type");
            if (type != null && !"text".equalsIgnoreCase(type) && !"tool_result".equalsIgnoreCase(type)) {
                return unsupportedAnthropicContentText(type);
            }
            String direct = text(content, "text");
            if (direct != null) {
                return direct;
            }
        }
        return content.toString();
    }

    private static String unsupportedAnthropicContentText(String type) {
        String safeType = firstNonBlank(type, "unknown");
        if ("image".equalsIgnoreCase(safeType) || "document".equalsIgnoreCase(safeType)) {
            return "[OCIworker 提示：当前 OpenAI 兼容转发暂不支持 Anthropic " + safeType + " 内容块，已按文本占位处理。]";
        }
        if ("thinking".equalsIgnoreCase(safeType) || "redacted_thinking".equalsIgnoreCase(safeType)) {
            return "";
        }
        return "[OCIworker 提示：已忽略暂不支持的 Anthropic " + safeType + " 内容块。]";
    }

    private static String chatContentText(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText("");
        }
        if (content.isValueNode()) {
            return content.asText("");
        }
        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode part : content) {
                if (part == null || part.isNull()) {
                    continue;
                }
                if (part.isTextual()) {
                    appendText(sb, part.asText());
                    continue;
                }
                if (part.isObject()) {
                    appendText(sb, firstNonBlank(text(part, "text"), text(part, "content")));
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private static ObjectNode parseJsonObjectOrEmpty(String value) {
        if (value == null || value.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(value);
            if (node != null && node.isObject()) {
                return (ObjectNode) node;
            }
        } catch (Exception ignored) {
        }
        return MAPPER.createObjectNode();
    }

    private static int numeric(JsonNode node, String... fields) {
        if (node == null || !node.isObject() || fields == null) {
            return 0;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isNumber()) {
                return value.asInt();
            }
        }
        return 0;
    }

    private static void writeAnthropicSse(ServletOutputStream out, String event, ObjectNode data) throws IOException {
        out.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        out.write(("data: " + MAPPER.writeValueAsString(data) + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    private static ObjectNode objectNode(Object... keyValues) {
        ObjectNode node = MAPPER.createObjectNode();
        if (keyValues == null) {
            return node;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String key = String.valueOf(keyValues[i]);
            Object value = keyValues[i + 1];
            if (value == null) {
                node.putNull(key);
            } else if (value instanceof JsonNode json) {
                node.set(key, json);
            } else if (value instanceof Boolean bool) {
                node.put(key, bool);
            } else if (value instanceof Integer integer) {
                node.put(key, integer);
            } else if (value instanceof Long longValue) {
                node.put(key, longValue);
            } else if (value instanceof Double doubleValue) {
                node.put(key, doubleValue);
            } else if (value instanceof Float floatValue) {
                node.put(key, floatValue);
            } else {
                node.put(key, String.valueOf(value));
            }
        }
        return node;
    }

    private static boolean requiresResponsesGenerativeContext(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            return false;
        }
        String model = requestedModel.trim().toLowerCase();
        return model.contains("multi-agent") || model.contains("multiagent") || model.contains("multi agent");
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

    private static String extractAnthropicModelFromBody(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return null;
        }
        if (contentType != null && !contentType.isBlank() && !contentType.toLowerCase().contains("json")) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root instanceof ObjectNode object) {
                return sanitizeAnthropicModel(text(object, "model"));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String anthropicBridgeType(byte[] body, String contentType) {
        String base = "anthropic_messages_to_chat";
        if (body == null || body.length == 0) {
            return base;
        }
        if (contentType != null && !contentType.isBlank() && !contentType.toLowerCase().contains("json")) {
            return base;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (!(root instanceof ObjectNode object)) {
                return base;
            }
            JsonNode toolChoice = object.get("tool_choice");
            if (toolChoice instanceof ObjectNode choice) {
                String type = text(choice, "type");
                if ("tool".equalsIgnoreCase(type)) {
                    return "anthropic_messages_to_chat_forced_tool";
                }
                if ("any".equalsIgnoreCase(type)) {
                    return "anthropic_messages_to_chat_required_tool";
                }
                if ("none".equalsIgnoreCase(type)) {
                    return "anthropic_messages_to_chat_no_tool";
                }
            } else if (toolChoice != null && toolChoice.isTextual()) {
                String type = toolChoice.asText();
                if ("any".equalsIgnoreCase(type)) {
                    return "anthropic_messages_to_chat_required_tool";
                }
                if ("none".equalsIgnoreCase(type)) {
                    return "anthropic_messages_to_chat_no_tool";
                }
            }
        } catch (Exception ignored) {
        }
        return base;
    }

    private static boolean hasToolRequest(byte[] body, String contentType) {
        return toolCount(body, contentType) > 0;
    }

    private static int toolCount(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return 0;
        }
        if (contentType != null && !contentType.isBlank() && !contentType.toLowerCase().contains("json")) {
            return 0;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                return 0;
            }
            int count = 0;
            JsonNode tools = root.get("tools");
            if (tools != null && tools.isArray()) {
                count += tools.size();
            }
            JsonNode functions = root.get("functions");
            if (functions != null && functions.isArray()) {
                count += functions.size();
            }
            return count;
        } catch (Exception ignored) {
            return 0;
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
                stringAttr(request, "ociworker.lb.requestPath"),
                boolAttr(request, "ociworker.lb.hasTools"),
                intAttr(request, "ociworker.lb.toolCount"),
                stringAttr(request, "ociworker.lb.bridgeType"),
                intAttrValue(request, "ociworker.lb.responseToolCallCount"),
                boolAttr(request, "ociworker.lb.toolLifecycleCompleted"),
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

    private static int intAttrValue(HttpServletRequest request, String attr) {
        Integer value = intAttr(request, attr);
        return value == null ? 0 : value;
    }

    private static String stringAttr(HttpServletRequest request, String attr) {
        Object value = request == null ? null : request.getAttribute(attr);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean boolAttr(HttpServletRequest request, String attr) {
        Object value = request == null ? null : request.getAttribute(attr);
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
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

    static long estimateInputTokens(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return 1L;
        }
        if (contentType != null && !contentType.isBlank() && !contentType.toLowerCase().contains("json")) {
            return Math.max(1L, body.length / 4L);
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            long chars = countTextChars(root);
            return Math.max(1L, (chars + 3L) / 4L);
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
        private final String overridePathAfterV1;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            this(request, body, null);
        }

        private CachedBodyRequest(HttpServletRequest request, byte[] body, String overridePathAfterV1) {
            super(request);
            this.body = body == null ? new byte[0] : body;
            this.overridePathAfterV1 = overridePathAfterV1 == null || overridePathAfterV1.isBlank()
                    ? null
                    : (overridePathAfterV1.startsWith("/") ? overridePathAfterV1 : "/" + overridePathAfterV1);
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

        @Override
        public String getRequestURI() {
            if (overridePathAfterV1 == null) {
                return super.getRequestURI();
            }
            String original = super.getRequestURI();
            if (original == null || original.isBlank()) {
                return "/v1" + overridePathAfterV1;
            }
            String ctx = super.getContextPath() == null ? "" : super.getContextPath();
            String path = original;
            if (!ctx.isEmpty() && path.startsWith(ctx)) {
                path = path.substring(ctx.length());
            }
            int idx = path.indexOf("/v1");
            if (idx < 0) {
                return original;
            }
            String prefix = original.substring(0, original.length() - path.length() + idx + 3);
            return prefix + overridePathAfterV1;
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
