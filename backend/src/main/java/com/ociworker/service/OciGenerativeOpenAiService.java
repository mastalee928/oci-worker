package com.ociworker.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.generativeaiinference.GenerativeAiInferenceClient;
import com.oracle.bmc.generativeaiinference.model.AssistantMessage;
import com.oracle.bmc.generativeaiinference.model.AudioContent;
import com.oracle.bmc.generativeaiinference.model.AudioUrl;
import com.oracle.bmc.generativeaiinference.model.BaseChatResponse;
import com.oracle.bmc.generativeaiinference.model.ChatChoice;
import com.oracle.bmc.generativeaiinference.model.ChatContent;
import com.oracle.bmc.generativeaiinference.model.ChatDetails;
import com.oracle.bmc.generativeaiinference.model.ChatResult;
import com.oracle.bmc.generativeaiinference.model.CohereAssistantMessageV2;
import com.oracle.bmc.generativeaiinference.model.CohereChatRequestV2;
import com.oracle.bmc.generativeaiinference.model.CohereChatResponseV2;
import com.oracle.bmc.generativeaiinference.model.CohereContentV2;
import com.oracle.bmc.generativeaiinference.model.CohereDocumentContentV2;
import com.oracle.bmc.generativeaiinference.model.CohereImageContentV2;
import com.oracle.bmc.generativeaiinference.model.CohereImageUrlV2;
import com.oracle.bmc.generativeaiinference.model.CohereMessageV2;
import com.oracle.bmc.generativeaiinference.model.CohereSystemMessageV2;
import com.oracle.bmc.generativeaiinference.model.CohereTextContentV2;
import com.oracle.bmc.generativeaiinference.model.CohereThinkingContentV2;
import com.oracle.bmc.generativeaiinference.model.CohereThinkingV2;
import com.oracle.bmc.generativeaiinference.model.CohereToolCallV2;
import com.oracle.bmc.generativeaiinference.model.CohereToolMessageV2;
import com.oracle.bmc.generativeaiinference.model.CohereToolV2;
import com.oracle.bmc.generativeaiinference.model.CohereUserMessageV2;
import com.oracle.bmc.generativeaiinference.model.DeveloperMessage;
import com.oracle.bmc.generativeaiinference.model.DocumentContent;
import com.oracle.bmc.generativeaiinference.model.DocumentUrl;
import com.oracle.bmc.generativeaiinference.model.Function;
import com.oracle.bmc.generativeaiinference.model.FunctionCall;
import com.oracle.bmc.generativeaiinference.model.FunctionDefinition;
import com.oracle.bmc.generativeaiinference.model.GenericChatRequest;
import com.oracle.bmc.generativeaiinference.model.GenericChatResponse;
import com.oracle.bmc.generativeaiinference.model.ImageContent;
import com.oracle.bmc.generativeaiinference.model.ImageUrl;
import com.oracle.bmc.generativeaiinference.model.Message;
import com.oracle.bmc.generativeaiinference.model.OnDemandServingMode;
import com.oracle.bmc.generativeaiinference.model.SystemMessage;
import com.oracle.bmc.generativeaiinference.model.TextContent;
import com.oracle.bmc.generativeaiinference.model.ToolCall;
import com.oracle.bmc.generativeaiinference.model.ToolChoice;
import com.oracle.bmc.generativeaiinference.model.ToolChoiceAuto;
import com.oracle.bmc.generativeaiinference.model.ToolChoiceFunction;
import com.oracle.bmc.generativeaiinference.model.ToolChoiceNone;
import com.oracle.bmc.generativeaiinference.model.ToolChoiceRequired;
import com.oracle.bmc.generativeaiinference.model.ToolDefinition;
import com.oracle.bmc.generativeaiinference.model.ToolMessage;
import com.oracle.bmc.generativeaiinference.model.Usage;
import com.oracle.bmc.generativeaiinference.model.UserMessage;
import com.oracle.bmc.generativeaiinference.model.VideoContent;
import com.oracle.bmc.generativeaiinference.model.VideoUrl;
import com.oracle.bmc.generativeaiinference.requests.ChatRequest;
import com.oracle.bmc.http.signing.DefaultRequestSigner;
import com.oracle.bmc.http.signing.RequestSigner;
import com.oracle.bmc.http.ClientConfigurator;
import com.oracle.bmc.http.client.ProxyConfiguration;
import com.oracle.bmc.http.client.StandardClientProperties;
import com.oracle.bmc.http.client.jersey3.ApacheClientProperties;
import com.ociworker.config.OpenAiApiConstants;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.model.entity.OciKv;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.dto.OciProxySnapshot;
import com.ociworker.util.CommonUtils;
import com.ociworker.util.OciBasicForSigning;
import com.ociworker.util.OciDuplicatableByteArrayInputStream;
import com.ociworker.util.OracleAiModelCapability;
import com.ociworker.util.OciRegionUtil;
import com.ociworker.util.socks.OciSocksApacheConnectionManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.conn.HttpClientConnectionManager;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * 经 OCI IAM 签名将请求转发至 Generative AI OpenAI 兼容端点（推理面）。
 * Base: https://inference.generativeai.&lt;region&gt;.oci.oraclecloud.com/openai/v1
 * 模型列表来自管理面 ListModels（generativeai.&lt;region&gt;），因推理面通常不注册 {@code /openai/v1/models}。
 */
@Slf4j
@Service
public class OciGenerativeOpenAiService {

    public static final int DEFAULT_MAX_TOKENS = OracleAiGatewayConfigService.FALLBACK_DEFAULT_MAX_TOKENS;
    private static final String V1 = "/v1";
    private static final String GA_API_VERSION = "20231130";
    private static final int LIST_PAGE_LIMIT = 200;
    private static final int LIST_MAX_PAGES = 50;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration MODEL_LIST_CACHE_TTL = Duration.ofMinutes(5);
    private static final int GEMINI_MIN_CHAT_COMPLETION_TOKENS = 128;
    private static final int META_LLAMA_ON_DEMAND_MAX_TOKENS = 4000;
    private static final int COHERE_COMMAND_A_REASONING_ON_DEMAND_MAX_TOKENS = 4000;
    private static final String REGION_CONTEXT_TYPE = "oracle_ai_region_context";
    private static final Set<String> OCI_TOOL_SCHEMA_ALLOWED_FIELDS = Set.of(
            "type", "format", "description", "nullable", "enum",
            "items", "properties", "required", "propertyOrdering",
            "minItems", "maxItems", "minLength", "maxLength",
            "minimum", "maximum", "minProperties", "maxProperties");
    private static final Set<String> GEMINI_NATIVE_DOCUMENT_MIME_TYPES = Set.of(
            "text/plain", "application/pdf");
    private static final Set<String> GEMINI_NATIVE_IMAGE_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/heic", "image/heif");
    private static final Set<String> GEMINI_NATIVE_AUDIO_MIME_TYPES = Set.of(
            "audio/wav", "audio/mp3", "audio/aiff", "audio/aac", "audio/ogg", "audio/flac");
    private static final Set<String> GEMINI_NATIVE_VIDEO_MIME_TYPES = Set.of(
            "video/mp4", "video/mpeg", "video/mov", "video/avi", "video/x-flv",
            "video/mpg", "video/webm", "video/wmv", "video/3gpp");
    private static volatile IntSupplier defaultMaxTokensSupplier = () -> DEFAULT_MAX_TOKENS;
    private final Map<String, CachedModels> modelsCache = new ConcurrentHashMap<>();

    @Resource
    private OciProxyConfigService ociProxyConfigService;
    @Resource
    private OracleAiGatewayConfigService gatewayConfigService;
    @Resource
    private OciKvMapper kvMapper;

    @PostConstruct
    public void initDefaultMaxTokensSupplier() {
        defaultMaxTokensSupplier = gatewayConfigService::getDefaultMaxTokens;
    }

    public void proxy(OciUser tenant, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String pathAfterV1 = extractPathAfterV1(request);
        if (pathAfterV1 == null || pathAfterV1.isEmpty() || pathAfterV1.equals("/")) {
            pathAfterV1 = "/";
        }
        if (!pathAfterV1.startsWith("/")) {
            pathAfterV1 = "/" + pathAfterV1;
        }
        final String origPathAfterV1 = pathAfterV1;
        String regionId = effectivePublicRegionId(tenant, request.getAttribute(OpenAiApiConstants.ATTR_OCI_REGION));
        String baseRawApi = "https://inference.generativeai." + regionId + ".oci.oraclecloud.com";
        String baseOpenAi = "https://inference.generativeai." + regionId + ".oci.oraclecloud.com/openai/v1";
        String baseRawV1 = "https://inference.generativeai." + regionId + ".oci.oraclecloud.com/v1";
        String query = request.getQueryString();

        RequestSigner signer = newRequestSigner(tenant, regionId);

        String method = request.getMethod().toUpperCase();
        String accept = request.getHeader("Accept");
        if (accept == null || accept.isBlank()) {
            accept = "*/*";
        }

        String contentType = request.getContentType();
        if (contentType != null && contentType.contains(";")) {
            contentType = contentType.split(";")[0].trim();
        }

        byte[] body = null;
        if (!"GET".equals(method) && !"HEAD".equals(method) && !"DELETE".equals(method)) {
            body = request.getInputStream().readAllBytes();
        }
        final byte[] origBody = body;
        boolean looksLikeJson =
                contentType == null
                        || contentType.isBlank()
                        || contentType.toLowerCase().contains("json");
        final int requestDefaultMaxTokens = requestDefaultMaxTokens(request);
        final List<String> requestAllowedModels = requestAllowedModels(request);
        if ("GET".equalsIgnoreCase(method) && isModelsPath(origPathAfterV1)) {
            if (!requestAllowedModels.isEmpty()) {
                writeJson(response, allowedModelsToOpenAiList(requestAllowedModels));
            } else {
                try {
                    writeJson(response, getModelsAsJsonCached(tenant, regionId));
                } catch (Exception e) {
                    throw new OciException("拉取模型列表失败: " + e.getMessage());
                }
            }
            return;
        }
        String requestedModel = extractModelFromBody(origBody, contentType);
        if (isModelScopedRequestPath(origPathAfterV1)
                && !isAllowedModel(requestedModel, requestAllowedModels)) {
            writeOpenAiError(response, 400, "invalid_request_error",
                    "Model is not allowed for this port binding: " + requestedModel,
                    "model_not_allowed");
            return;
        }
        if ((isChatCompletionsPath(origPathAfterV1) || isResponsesPath(origPathAfterV1))
                && requestedModel != null
                && !requestedModel.isBlank()
                && !OracleAiModelCapability.isChatEndpointCompatible(requestedModel)) {
            writeOpenAiError(response, 400, "invalid_request_error",
                    OracleAiModelCapability.chatEndpointMismatchMessage(requestedModel),
                    "model_endpoint_mismatch");
            return;
        }
        if (isEmbeddingsPath(origPathAfterV1)
                && requestedModel != null
                && !requestedModel.isBlank()
                && !OracleAiModelCapability.isEmbeddingEndpointCompatible(requestedModel)) {
            writeOpenAiError(response, 400, "invalid_request_error",
                    OracleAiModelCapability.embeddingEndpointMismatchMessage(requestedModel),
                    "model_endpoint_mismatch");
            return;
        }
        if (isRerankPath(origPathAfterV1)
                && requestedModel != null
                && !requestedModel.isBlank()
                && !OracleAiModelCapability.isRerankEndpointCompatible(requestedModel)) {
            writeOpenAiError(response, 400, "invalid_request_error",
                    OracleAiModelCapability.rerankEndpointMismatchMessage(requestedModel),
                    "model_endpoint_mismatch");
            return;
        }
        if (isAudioSpeechPath(origPathAfterV1)
                && requestedModel != null
                && !requestedModel.isBlank()
                && !OracleAiModelCapability.isAudioSpeechEndpointCompatible(requestedModel)) {
            writeOpenAiError(response, 400, "invalid_request_error",
                    OracleAiModelCapability.audioSpeechEndpointMismatchMessage(requestedModel),
                    "model_endpoint_mismatch");
            return;
        }
        // 记录原始 /v1 之后路径，便于排障
        request.setAttribute("ociworker.debug.origPathAfterV1", origPathAfterV1);

        boolean useRawApiBase = false;
        String opcCompartmentId = tenant != null ? tenant.getOciTenantId() : null;
        if ("POST".equalsIgnoreCase(method) && isRerankPath(origPathAfterV1)) {
            if (!looksLikeJson || origBody == null || origBody.length == 0) {
                writeOpenAiError(response, 400, "invalid_request_error",
                        "Rerank 请求必须是 JSON，并包含 model、query/input 和 documents。",
                        "invalid_rerank_request");
                return;
            }
            try {
                RerankBridgeRequest rerank = transformRerankRequestJson(origBody, opcCompartmentId);
                body = rerank.body();
                pathAfterV1 = "/" + GA_API_VERSION + "/actions/rerankText";
                useRawApiBase = true;
                contentType = "application/json";
                accept = "application/json";
                opcCompartmentId = firstNonBlank(rerank.compartmentId(), opcCompartmentId);
                request.setAttribute("ociworker.rewrite.rerankToCommon", Boolean.TRUE);
                if (rerank.originalDocumentsJson() != null) {
                    request.setAttribute("ociworker.rerank.originalDocumentsJson", rerank.originalDocumentsJson());
                }
                request.setAttribute("ociworker.rerank.returnDocuments", rerank.returnDocuments());
            } catch (IllegalArgumentException e) {
                writeOpenAiError(response, 400, "invalid_request_error",
                        e.getMessage() != null ? e.getMessage() : "Rerank 请求格式无效",
                        "invalid_rerank_request");
                return;
            } catch (Exception e) {
                throw new OciException("转换 Rerank 请求失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        }

        // OCI：Multi Agent 模型不允许走 /v1/chat/completions，需要改走 /v1/responses
        // 且按 OCI 文档，该模型的 endpoints 为 /v1/responses（非 /openai/v1/responses）
        if ("POST".equalsIgnoreCase(method)
                && isChatCompletionsPath(origPathAfterV1)
                && origBody != null
                && origBody.length > 0) {
            // 仅按 model 字段判断是否需要改写到 /responses。不能扫描整个请求体，
            // Codex/Codex++ 的系统提示或工具描述可能包含 multi-agent 字样，误判后会破坏 Chat Completions 工具调用。
            boolean rewriteChatToResponses = false;
            boolean chatBodyAlreadyTransformed = false;
            String rewriteModel = "multi-agent";
            if (looksLikeJson) {
                try {
                    JsonNode root = MAPPER.readTree(origBody);
                    if (root != null && root.isObject()) {
                        String model = textOrNull(((ObjectNode) root), "model");
                        if (shouldRewriteChatCompletionsToResponses(model)) {
                            rewriteChatToResponses = true;
                            rewriteModel = firstNonBlank(model, rewriteModel);
                        }
                    }
                } catch (Exception e) {
                    body = transformChatCompletionsJson(origBody, requestDefaultMaxTokens);
                    chatBodyAlreadyTransformed = true;
                }
            }
            if (rewriteChatToResponses) {
                if (isStreamRequest(origBody, contentType)) {
                    // 上游想要 chat.completions SSE，这里后续会在返回侧模拟 SSE。
                    request.setAttribute("ociworker.rewrite.simulateSse", Boolean.TRUE);
                }
                request.setAttribute("ociworker.rewrite.chatToResponses", Boolean.TRUE);
                request.setAttribute("ociworker.lb.bridgeType", "chat_to_responses_native");
                request.setAttribute("ociworker.rewrite.useRawV1Base", Boolean.TRUE);
                request.setAttribute("ociworker.rewrite.model", rewriteModel);
                pathAfterV1 = "/responses";
                body = transformChatCompletionsToResponsesJson(origBody, requestDefaultMaxTokens);
                try {
                    if (request != null && body != null) {
                        request.setAttribute("ociworker.debug.responsesInputShape.before", describeResponsesInputShape(body));
                    }
                    body = normalizeResponsesInputForOci(body);
                    // Multi-Agent 典型会携带超长历史导致 TPM/结构错误；在网关侧截断 input，仅保留最近 N 条。
                    body = truncateResponsesInputForMultiAgent(body, 20);
                    if (request != null && body != null) {
                        request.setAttribute("ociworker.debug.responsesInputShape.after", describeResponsesInputShape(body));
                    }
                } catch (Exception ignored) {
                }
            } else if (looksLikeJson && !chatBodyAlreadyTransformed) {
                body = transformChatCompletionsJson(origBody, requestDefaultMaxTokens);
            }
            if (!rewriteChatToResponses
                    && shouldUseGeminiNativeChat(requestedModel, body)) {
                body = forceChatCompletionNonStreamJson(body);
                if (isStreamRequest(origBody, contentType)) {
                    request.setAttribute("ociworker.rewrite.forceBuffer", Boolean.TRUE);
                    request.setAttribute("ociworker.rewrite.simulateChatCompletionSse", Boolean.TRUE);
                }
                request.setAttribute("ociworker.rewrite.useNativeGenericChat", Boolean.TRUE);
                request.setAttribute("ociworker.lb.bridgeType", "native_generic_chat");
                request.setAttribute("ociworker.rewrite.model", requestedModel);
            } else if (!rewriteChatToResponses
                    && shouldUseCohereCommandAReasoningNativeChat(requestedModel, body)) {
                body = forceChatCompletionNonStreamJson(body);
                if (isStreamRequest(origBody, contentType)) {
                    request.setAttribute("ociworker.rewrite.forceBuffer", Boolean.TRUE);
                    request.setAttribute("ociworker.rewrite.simulateChatCompletionSse", Boolean.TRUE);
                }
                request.setAttribute("ociworker.rewrite.useNativeCohereChatV2", Boolean.TRUE);
                request.setAttribute("ociworker.lb.bridgeType", "native_cohere_chat_v2");
                request.setAttribute("ociworker.rewrite.model", requestedModel);
            }
        } else if (isChatCompletionsPath(origPathAfterV1) && body != null && body.length > 0 && looksLikeJson) {
            body = transformChatCompletionsJson(body, requestDefaultMaxTokens);
        }

        if ("POST".equalsIgnoreCase(method)
                && isResponsesPath(origPathAfterV1)
                && body != null
                && body.length > 0
                && looksLikeJson
                && !isLikelyMultiAgentModelName(requestedModel)) {
            request.setAttribute("ociworker.rewrite.responsesToChat", Boolean.TRUE);
            request.setAttribute("ociworker.lb.bridgeType", "responses_to_chat");
            request.setAttribute("ociworker.rewrite.model", requestedModel);
            pathAfterV1 = "/chat/completions";
            body = transformResponsesToChatCompletionsJson(origBody, requestDefaultMaxTokens);
            if (isStreamRequest(origBody, contentType)) {
                request.setAttribute("ociworker.rewrite.simulateResponsesSse", Boolean.TRUE);
            }
            if (shouldUseGeminiNativeChat(requestedModel, body)) {
                body = forceChatCompletionNonStreamJson(body);
                request.setAttribute("ociworker.rewrite.forceBuffer", Boolean.TRUE);
                request.setAttribute("ociworker.rewrite.useNativeGenericChat", Boolean.TRUE);
                request.setAttribute("ociworker.lb.bridgeType", "native_generic_chat_responses");
                request.setAttribute("ociworker.rewrite.model", requestedModel);
            } else if (shouldUseCohereCommandAReasoningNativeChat(requestedModel, body)) {
                body = forceChatCompletionNonStreamJson(body);
                request.setAttribute("ociworker.rewrite.forceBuffer", Boolean.TRUE);
                request.setAttribute("ociworker.rewrite.useNativeCohereChatV2", Boolean.TRUE);
                request.setAttribute("ociworker.lb.bridgeType", "native_cohere_chat_v2_responses");
                request.setAttribute("ociworker.rewrite.model", requestedModel);
            }
        }

        // 对直接调用 /v1/responses 的请求：OCI 对 input 的 ModelInput 格式较严格。
        // 某些上游（IDE/New API）会按 chat 风格拼 input（content 为 string），OCI 会报反序列化失败。
        // 这里做宽松规范化：只要检测到“chat 风格”就转换为 input_text 块数组（对非 Multi-Agent 也安全）。
        if ("POST".equalsIgnoreCase(method)
                && isResponsesPath(origPathAfterV1)
                && !Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.responsesToChat"))
                && body != null
                && body.length > 0
                && looksLikeJson) {
            try {
                if (request != null) {
                    request.setAttribute("ociworker.debug.responsesInputShape.before", describeResponsesInputShape(body));
                }
                body = normalizeResponsesInputForOci(body);
                body = truncateResponsesInputForMultiAgent(body, 20);
                if (request != null) {
                    request.setAttribute("ociworker.debug.responsesInputShape.after", describeResponsesInputShape(body));
                    if (isStreamRequest(origBody, contentType)) {
                        // Cursor/New API 对 /responses 的流式事件形态不一致，先强制走 buffer 以保证能返回可见错误/结果
                        request.setAttribute("ociworker.rewrite.forceBuffer", Boolean.TRUE);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // /v1/responses 对 Multi-Agent 模型需要走 raw /v1 base；其它情况仍走 /openai/v1 base
        boolean useRawV1Base = Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.useRawV1Base"));
        if (!useRawV1Base && isResponsesPath(origPathAfterV1) && origBody != null && origBody.length > 0 && looksLikeJson) {
            try {
                JsonNode root = MAPPER.readTree(origBody);
                if (root != null && root.isObject()) {
                    String model = textOrNull((ObjectNode) root, "model");
                    if (isLikelyMultiAgentModelName(model)) {
                        useRawV1Base = true;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        request.setAttribute("ociworker.debug.finalPathAfterV1", pathAfterV1);

        if (Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.useNativeGenericChat"))) {
            proxyNativeGenericChat(tenant, regionId, body, requestedModel, request, response);
            return;
        }
        if (Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.useNativeCohereChatV2"))) {
            proxyNativeCohereChatV2(tenant, regionId, body, requestedModel, request, response);
            return;
        }

        StringBuilder u = new StringBuilder(useRawApiBase ? baseRawApi : (useRawV1Base ? baseRawV1 : baseOpenAi));
        u.append(pathAfterV1);
        if (query != null && !query.isEmpty()) {
            u.append("?").append(query);
        }
        URI target = URI.create(u.toString());

        boolean useStreamCopy =
                (isChatCompletionsPath(origPathAfterV1) || isResponsesPath(origPathAfterV1))
                        && isStreamRequest(origBody, contentType)
                        && !Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.chatToResponses"))
                        && !Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.forceBuffer"));
        HttpRequest httpRequest = buildSignedRequest(
                signer,
                method,
                target,
                body,
                contentType,
                accept,
                opcCompartmentId,
                extractOciGenerativeForwardHeaders(request, tenant),
                useStreamCopy ? Duration.ofMillis(timeoutMs(request, OpenAiApiConstants.ATTR_STREAM_FIRST_CHUNK_TIMEOUT_SECONDS, 60)) : Duration.ofHours(1L));
        HttpClient client = pickHttpClient();

        if (useStreamCopy) {
            longCopyStream(client, httpRequest, response, request);
        } else if (shouldUseBinaryProxy(origPathAfterV1)) {
            bufferAndCopyBytes(client, httpRequest, response, request);
        } else {
            bufferAndCopy(client, httpRequest, response, request);
        }
    }

    public JsonNode getModelsAsJson(OciUser tenant) throws Exception {
        return getModelsAsJson(tenant, null, null);
    }

    public JsonNode getModelsAsJson(OciUser tenant, String after, String modelId) throws Exception {
        return getModelsAsJson(tenant, null, after, modelId);
    }

    public JsonNode getModelsAsJsonCached(OciUser tenant, String ociRegion) throws Exception {
        String regionId = effectivePublicRegionId(tenant, ociRegion);
        String tenantKey = tenant == null ? "" : firstNonBlank(tenant.getId(), tenant.getOciTenantId(), tenant.getOciUserId());
        String key = tenantKey + "|" + regionId;
        CachedModels cached = modelsCache.get(key);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.body().deepCopy();
        }
        JsonNode fresh = getModelsAsJson(tenant, regionId, null, null);
        modelsCache.put(key, new CachedModels(fresh.deepCopy(), now.plus(MODEL_LIST_CACHE_TTL)));
        return fresh;
    }

    public JsonNode getModelsAsJson(OciUser tenant, String ociRegion, String after, String modelId) throws Exception {
        String regionId = effectivePublicRegionId(tenant, ociRegion);
        String managementHost = "generativeai." + regionId + ".oci.oraclecloud.com";
        String tenantId = tenant.getOciTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new OciException("租户无 ociTenantId，无法 list models");
        }
        if (modelId != null && !modelId.isBlank()) {
            String path = "/" + GA_API_VERSION + "/models/" + encodePathSegmentOciModel(modelId);
            return managementGetToOpenAiList(tenant, regionId, "https://" + managementHost + path, true);
        }
        List<JsonNode> all = new ArrayList<>();
        String page = (after != null && !after.isBlank()) ? after : null;
        for (int p = 0; p < LIST_MAX_PAGES; p++) {
            String q =
                    "compartmentId=" + java.net.URLEncoder.encode(tenantId, StandardCharsets.UTF_8)
                            + "&limit=" + LIST_PAGE_LIMIT;
            if (page != null) {
                q = q + "&page=" + java.net.URLEncoder.encode(page, StandardCharsets.UTF_8);
            }
            URI listUri = URI.create("https://" + managementHost + "/" + GA_API_VERSION + "/models?" + q);
            HttpRequest req = buildSignedRequest(
                    newRequestSigner(tenant, regionId),
                    "GET",
                    listUri,
                    null,
                    "application/json",
                    "application/json",
                    tenantId,
                    null);
            HttpResponse<String> resp;
            try {
                resp = pickHttpClient()
                        .send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new OciException("拉取 models 异常(" + e.getClass().getSimpleName() + "): "
                        + (e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
            if (resp.statusCode() / 100 != 2) {
                throw new OciException(
                        "拉取 models 失败: HTTP " + resp.statusCode()
                                + " headers=" + truncate(String.valueOf(resp.headers().map()), 500)
                                + " body=" + truncate(resp.body(), 500));
            }
            JsonNode root = MAPPER.readTree(resp.body() != null ? resp.body() : "{}");
            JsonNode items = root.get("items");
            if (items != null && items.isArray()) {
                for (JsonNode it : items) {
                    all.add(it);
                }
            }
            String next = resp.headers().firstValue("opc-next-page").orElse(null);
            if (next == null || next.isBlank()) {
                break;
            }
            page = next;
        }
        return ociModelsToOpenAiList(MAPPER.createObjectNode().set("items", toArrayNode(all)));
    }

    /**
     * 管理面：列出 Generative AI Project，用于面板一键填入 OpenAI-Project 头（值为 Project OCID）。
     * 使用与 ListModels 相同的 compartmentId（当前为租户 tenant OCID，与现网 /v1 行为一致）。
     */
    public JsonNode listGenerativeAiProjectSummaries(OciUser tenant) throws Exception {
        return listGenerativeAiProjectSummaries(tenant, null);
    }

    public JsonNode listGenerativeAiProjectSummaries(OciUser tenant, String ociRegion) throws Exception {
        String regionId = effectivePublicRegionId(tenant, ociRegion);
        String managementHost = "generativeai." + regionId + ".oci.oraclecloud.com";
        String compartmentId = tenant.getOciTenantId();
        if (compartmentId == null || compartmentId.isBlank()) {
            throw new OciException("租户无 ociTenantId，无法列举 Generative AI 项目");
        }
        List<JsonNode> all = new ArrayList<>();
        String page = null;
        for (int p = 0; p < LIST_MAX_PAGES; p++) {
            String q =
                    "compartmentId="
                            + java.net.URLEncoder.encode(compartmentId, StandardCharsets.UTF_8)
                            + "&limit="
                            + LIST_PAGE_LIMIT;
            if (page != null) {
                q = q + "&page=" + java.net.URLEncoder.encode(page, StandardCharsets.UTF_8);
            }
            URI listUri = URI.create(
                    "https://" + managementHost + "/" + GA_API_VERSION + "/generativeAiProjects?" + q);
            HttpRequest req = buildSignedRequest(
                    newRequestSigner(tenant, regionId),
                    "GET",
                    listUri,
                    null,
                    "application/json",
                    "application/json",
                    compartmentId,
                    null);
            HttpResponse<String> resp;
            try {
                resp = pickHttpClient()
                        .send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new OciException("列举 generativeAiProjects 异常(" + e.getClass().getSimpleName() + "): "
                        + (e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
            if (resp.statusCode() / 100 != 2) {
                throw new OciException("列举 generativeAiProjects 失败: HTTP " + resp.statusCode()
                        + " body=" + truncate(resp.body(), 800));
            }
            JsonNode root = MAPPER.readTree(resp.body() != null ? resp.body() : "{}");
            JsonNode items = root.get("items");
            if (items != null && items.isArray()) {
                for (JsonNode it : items) {
                    all.add(it);
                }
            }
            String next = resp.headers().firstValue("opc-next-page").orElse(null);
            if (next == null || next.isBlank()) {
                break;
            }
            page = next;
        }
        com.fasterxml.jackson.databind.node.ArrayNode arr = MAPPER.createArrayNode();
        for (JsonNode it : all) {
            if (it == null || !it.isObject()) {
                continue;
            }
            String id = firstText(it, "id");
            if (id == null || id.isBlank()) {
                continue;
            }
            ObjectNode row = MAPPER.createObjectNode();
            row.put("id", id);
            String dn = firstText(it, "displayName");
            if (dn != null && !dn.isBlank()) {
                row.put("displayName", dn);
            }
            arr.add(row);
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.set("items", arr);
        return out;
    }

    /**
     * 管理面：创建 Generative AI Project，返回包含 {@code id}/{@code displayName} 的 JSON。
     * 注意：需要调用方在 IAM 中具备创建权限；否则会返回 403。
     */
    public JsonNode createGenerativeAiProject(OciUser tenant, String displayName) throws Exception {
        return createGenerativeAiProject(tenant, null, displayName);
    }

    public JsonNode createGenerativeAiProject(OciUser tenant, String ociRegion, String displayName) throws Exception {
        String regionId = effectivePublicRegionId(tenant, ociRegion);
        String managementHost = "generativeai." + regionId + ".oci.oraclecloud.com";
        String compartmentId = tenant.getOciTenantId();
        if (compartmentId == null || compartmentId.isBlank()) {
            throw new OciException("租户无 ociTenantId，无法创建 Generative AI 项目");
        }
        String name = (displayName == null || displayName.isBlank()) ? "ociworker-default" : displayName.trim();
        ObjectNode body = MAPPER.createObjectNode();
        body.put("compartmentId", compartmentId);
        body.put("displayName", name);
        // Console 创建项目时会要求配置 Data retention；部分租户/区域的 API 也会校验该字段。
        // 这里给出保守默认值：30 天（720h），避免 400 Bad Request。
        ObjectNode conversationConfig = MAPPER.createObjectNode();
        conversationConfig.put("responsesRetentionInHours", 720);
        conversationConfig.put("conversationsRetentionInHours", 720);
        body.set("conversationConfig", conversationConfig);
        // 其余可选配置交给用户后续在控制台/面板完善；此处仅满足最小可用闭环
        byte[] bytes = MAPPER.writeValueAsBytes(body);

        URI uri = URI.create("https://" + managementHost + "/" + GA_API_VERSION + "/generativeAiProjects");
        HttpRequest req = buildSignedRequest(
                newRequestSigner(tenant, regionId),
                "POST",
                uri,
                bytes,
                "application/json",
                "application/json",
                compartmentId,
                null);
        HttpResponse<String> resp;
        try {
            resp = pickHttpClient().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new OciException("创建 generativeAiProject 异常(" + e.getClass().getSimpleName() + "): "
                    + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
        if (resp.statusCode() / 100 != 2) {
            String rid = resp.headers().firstValue("opc-request-id").orElse("");
            throw new OciException("创建 generativeAiProject 失败: HTTP " + resp.statusCode()
                    + (rid.isBlank() ? "" : " opc-request-id=" + rid)
                    + " body=" + truncate(resp.body(), 1200));
        }
        JsonNode root = MAPPER.readTree(resp.body() != null ? resp.body() : "{}");
        if (root != null && root.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            String id = firstText(root, "id");
            if (id != null) {
                out.put("id", id);
            }
            String dn = firstText(root, "displayName");
            if (dn != null) {
                out.put("displayName", dn);
            }
            return out;
        }
        return root;
    }

    public Map<String, String> getGenerativeContext(OciUser tenant, String ociRegion) {
        String regionId = effectivePublicRegionId(tenant, ociRegion);
        Map<String, String> ctx = readRegionContext(tenant, regionId);
        if (ctx.isEmpty() && tenant != null) {
            putIfNotBlank(ctx, "generativeOpenaiProject", tenant.getGenerativeOpenaiProject());
            putIfNotBlank(ctx, "generativeConversationStoreId", tenant.getGenerativeConversationStoreId());
        }
        ctx.put("ociRegion", regionId);
        return ctx;
    }

    public void saveGenerativeContext(
            OciUser tenant,
            String ociRegion,
            String generativeOpenaiProject,
            String generativeConversationStoreId) {
        String regionId = effectivePublicRegionId(tenant, ociRegion);
        ObjectNode root = MAPPER.createObjectNode();
        putJson(root, "generativeOpenaiProject", generativeOpenaiProject);
        putJson(root, "generativeConversationStoreId", generativeConversationStoreId);
        root.put("ociRegion", regionId);
        root.put("updateAt", System.currentTimeMillis());

        String code = regionContextCode(tenant, regionId);
        OciKv row = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getType, REGION_CONTEXT_TYPE)
                .eq(OciKv::getCode, code));
        if (row == null) {
            row = new OciKv();
            row.setId(CommonUtils.generateId());
            row.setType(REGION_CONTEXT_TYPE);
            row.setCode(code);
            row.setCreateTime(LocalDateTime.now());
            row.setValue(root.toString());
            kvMapper.insert(row);
        } else {
            row.setValue(root.toString());
            kvMapper.updateById(row);
        }
    }

    private static ArrayNode toArrayNode(List<JsonNode> nodes) {
        ArrayNode a = MAPPER.createArrayNode();
        for (JsonNode n : nodes) {
            a.add(n);
        }
        return a;
    }

    private JsonNode managementGetToOpenAiList(OciUser tenant, String regionId, String url, boolean oneItemAsList) throws Exception {
        RequestSigner signer = newRequestSigner(tenant, regionId);
        URI uri = URI.create(url);
        HttpRequest req = buildSignedRequest(
                signer,
                "GET",
                uri,
                null,
                "application/json",
                "application/json",
                tenant != null ? tenant.getOciTenantId() : null,
                null);
        HttpResponse<String> resp;
        try {
            resp = pickHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new OciException("拉取 models 异常(" + e.getClass().getSimpleName() + "): "
                    + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
        if (resp.statusCode() / 100 != 2) {
            throw new OciException("拉取 models 失败: HTTP " + resp.statusCode()
                    + " headers=" + truncate(String.valueOf(resp.headers().map()), 500)
                    + " body=" + truncate(resp.body(), 500));
        }
        return ociModelsToOpenAiList(MAPPER.readTree(resp.body() != null ? resp.body() : "{}"), oneItemAsList);
    }

    /**
     * 将 OCI model / modelCollection JSON 转为 OpenAI 风格 {@code { object, data: [{id, object}] } }。
     */
    private JsonNode ociModelsToOpenAiList(JsonNode ociBody) {
        return ociModelsToOpenAiList(ociBody, false);
    }

    private JsonNode ociModelsToOpenAiList(JsonNode ociBody, boolean single) {
        ArrayNode outItems = MAPPER.createArrayNode();
        if (single && ociBody != null && !ociBody.isObject()) {
            return buildOpenAiModelList(outItems);
        }
        if (single && ociBody != null && ociBody.isObject() && !ociBody.has("items")
                && ociBody.has("id")) {
            outItems.add(ociItemToOpenAi(ociBody));
        } else if (ociBody != null && ociBody.isObject() && ociBody.has("items")) {
            for (JsonNode n : ociBody.withArray("items")) {
                outItems.add(ociItemToOpenAi(n));
            }
        }
        return buildOpenAiModelList(outItems);
    }

    private static ObjectNode buildOpenAiModelList(ArrayNode data) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("object", "list");
        root.set("data", data);
        return root;
    }

    private static ObjectNode ociItemToOpenAi(JsonNode oci) {
        ObjectNode row = MAPPER.createObjectNode();
        // 推理/Chat 的 model 字段优先用服务侧 name（如 cohere.command ），其次用看起来像“模型名”的 displayName/modelName，
        // 最后才回退到资源 OCID。否则前端选中后会把 OCID 传到 /v1/chat/completions，导致 Multi-Agent 无法命中改写。
        String id = firstText(oci, "name", "modelName", "model", "modelId");
        JsonNode display = oci != null ? (oci.get("displayName") != null ? oci.get("displayName") : oci.get("modelName")) : null;
        String dn = (display != null && display.isTextual()) ? display.asText().trim() : null;
        // 如果 displayName 看起来像真实模型名（例如 xai.grok-...），优先使用它作为 OpenAI model id
        if (dn != null && !dn.isBlank()) {
            String dnl = dn.toLowerCase(java.util.Locale.ROOT);
            boolean looksLikeModelName =
                    dnl.startsWith("xai.")
                            || dnl.startsWith("cohere.")
                            || dnl.startsWith("meta.")
                            || dnl.startsWith("mistral.")
                            || dnl.startsWith("openai.")
                            || dn.matches("^[a-z0-9]+\\.[a-z0-9._\\-]+$");
            if (looksLikeModelName) {
                id = dn;
            }
        }
        if ((id == null || id.isBlank())
                && dn != null
                && !dn.isBlank()) {
            // 兜底：仍允许把 displayName 当作 id
            id = dn;
        }
        if ((id == null || id.isBlank()) && oci != null) {
            JsonNode idn = oci.get("id");
            if (idn != null && !idn.isNull()) {
                id = idn.asText();
            }
        }
        if (id == null || id.isBlank()) {
            id = "unknown";
        }
        row.put("id", id);
        row.put("object", "model");
        if (dn != null && !dn.isBlank()) {
            row.put("displayName", dn);
        }
        String rawType = firstText(oci, "type", "modelType", "inferenceType");
        String capability = OracleAiModelCapability.classifyAny(id, dn, rawType);
        row.put("ociworkerCapability", capability);
        // 额外透出原始 OCID，便于前端 hover/排障（不影响 OpenAI model id）
        if (oci != null) {
            JsonNode ociId = oci.get("id");
            if (ociId != null && ociId.isTextual() && !ociId.asText().isBlank()) {
                row.put("ociId", ociId.asText());
            }
        }
        // 管理面 ListModels 会返回多种“模型产品形态”，不保证都适用于 OpenAI 兼容的 /v1/chat/completions
        if (OracleAiModelCapability.MULTI_AGENT.equals(capability)) {
            row.put(
                    "ociworkerNote",
                    "该模型为 Multi Agent：本网关会把 /v1/chat/completions 改写为 /v1/responses 并尽量把响应装成 chat.completion。"
                            + " OCI 通常要求 OpenAI-Project 或 opc-conversation-store-id；可在「Oracle 生成式 AI」页为租户保存默认值，或由上游转发明文头。");
        }
        return row;
    }

    private static boolean isLikelyMultiAgentModelName(String s) {
        if (s == null) {
            return false;
        }
        String t = s.toLowerCase();
        // 以名称启发式为主（避免在网关侧做额外管理面查询）
        return OracleAiModelCapability.isMultiAgent(t);
    }

    static boolean shouldRewriteChatCompletionsToResponses(String model) {
        return OracleAiModelCapability.isMultiAgent(model);
    }

    static boolean shouldBufferChatCompletionStream(String model) {
        return isGeminiChatModel(model) || isCohereCommandAReasoningModel(model);
    }

    static boolean shouldUseGeminiNativeChat(String model, byte[] body) {
        return isGeminiChatModel(model) && canUseNativeGenericChat(body);
    }

    static boolean shouldUseCohereCommandAReasoningNativeChat(String model, byte[] body) {
        return isCohereCommandAReasoningModel(model) && canUseNativeCohereChatV2(body);
    }

    private static boolean isGeminiChatModel(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String value = model.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("google.gemini-");
    }

    private static boolean isCohereCommandAReasoningModel(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String value = model.trim().toLowerCase(Locale.ROOT);
        return value.equals("cohere.command-a-reasoning");
    }

    static byte[] forceChatCompletionNonStreamJson(byte[] input) {
        if (input == null || input.length == 0) {
            return input;
        }
        try {
            JsonNode root = MAPPER.readTree(input);
            if (root instanceof ObjectNode object) {
                object.put("stream", false);
                object.remove("stream_options");
                return MAPPER.writeValueAsBytes(object);
            }
        } catch (Exception ignored) {
        }
        return input;
    }

    static boolean canUseNativeGenericChat(byte[] input) {
        if (input == null || input.length == 0) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(input);
            if (!(root instanceof ObjectNode object)) {
                return false;
            }
            JsonNode messages = object.get("messages");
            if (messages == null || !messages.isArray() || messages.isEmpty()) {
                return false;
            }
            boolean hasUsableMessage = false;
            for (JsonNode message : messages) {
                if (!(message instanceof ObjectNode messageObject)) {
                    return false;
                }
                String role = normalizeChatRole(textOrNull(messageObject, "role"));
                JsonNode nativeContent = nativeMessageContent(messageObject);
                if (hasNativeGenericChatPayload(messageObject)) {
                    hasUsableMessage = true;
                }
                if (!isNativeGenericChatContent(nativeContent)) {
                    return false;
                }
                if (!"user".equals(role) && hasNativeMediaContent(nativeContent)) {
                    return false;
                }
            }
            return hasUsableMessage;
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean canUseNativeCohereChatV2(byte[] input) {
        if (input == null || input.length == 0) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(input);
            if (!(root instanceof ObjectNode object)) {
                return false;
            }
            JsonNode messages = object.get("messages");
            if (messages == null || !messages.isArray() || messages.isEmpty()) {
                return false;
            }
            boolean hasUsableMessage = false;
            for (JsonNode message : messages) {
                if (!(message instanceof ObjectNode messageObject)) {
                    return false;
                }
                String role = normalizeChatRole(textOrNull(messageObject, "role"));
                JsonNode nativeContent = nativeMessageContent(messageObject);
                if (hasNativeCohereChatV2Payload(messageObject)) {
                    hasUsableMessage = true;
                }
                if (!isNativeCohereChatV2Content(nativeContent)) {
                    return false;
                }
                if (!"user".equals(role) && hasNativeCohereChatV2MediaContent(nativeContent)) {
                    return false;
                }
            }
            return hasUsableMessage;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean hasNativeCohereChatV2Payload(ObjectNode message) {
        if (message == null) {
            return false;
        }
        JsonNode toolCalls = message.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
            return true;
        }
        return hasNativeCohereChatV2PayloadContent(nativeMessageContent(message));
    }

    private static boolean hasNativeCohereChatV2PayloadContent(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return false;
        }
        if (content.isTextual()) {
            return !content.asText().isBlank();
        }
        if (content.isNumber() || content.isBoolean()) {
            return true;
        }
        if (content instanceof ObjectNode object) {
            return hasNativeCohereChatV2PayloadObject(object);
        }
        if (!content.isArray()) {
            return false;
        }
        for (JsonNode part : content) {
            if (hasNativeCohereChatV2PayloadContent(part)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNativeCohereChatV2PayloadObject(ObjectNode object) {
        if (object == null || object.isEmpty()) {
            return false;
        }
        String type = nativeContentObjectType(object);
        if (isTextLikeNativeContentObject(object, type)) {
            String text = chatTextPartText(object);
            return text != null && !text.isBlank();
        }
        if (isImageLikeNativeContentObject(object, type)) {
            return nativeImageUrl(object) != null;
        }
        if (isDocumentLikeNativeContentObject(object, type)) {
            return firstExisting(object, "document", "file", "source") != null;
        }
        if (isAudioLikeNativeContentObject(object, type) || isVideoLikeNativeContentObject(object, type)) {
            return false;
        }
        return !isUnsupportedNativeContentObject(object);
    }

    private static boolean isNativeCohereChatV2Content(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return true;
        }
        if (content.isTextual() || content.isNumber() || content.isBoolean()) {
            return true;
        }
        if (content instanceof ObjectNode object) {
            return isNativeCohereChatV2ContentObject(object);
        }
        if (!content.isArray()) {
            return false;
        }
        for (JsonNode part : content) {
            if (!isNativeCohereChatV2Content(part)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNativeCohereChatV2ContentObject(ObjectNode object) {
        if (object == null) {
            return false;
        }
        String type = nativeContentObjectType(object);
        if (isAudioLikeNativeContentObject(object, type) || isVideoLikeNativeContentObject(object, type)) {
            return false;
        }
        if (isTextLikeNativeContentObject(object, type)
                || isImageLikeNativeContentObject(object, type)
                || isDocumentLikeNativeContentObject(object, type)) {
            return true;
        }
        return !isUnsupportedNativeContentObject(object);
    }

    private static boolean hasNativeCohereChatV2MediaContent(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return false;
        }
        if (content instanceof ObjectNode object) {
            String type = nativeContentObjectType(object);
            return isImageLikeNativeContentObject(object, type)
                    || isDocumentLikeNativeContentObject(object, type);
        }
        if (content.isArray()) {
            for (JsonNode part : content) {
                if (hasNativeCohereChatV2MediaContent(part)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasNativeGenericChatPayload(ObjectNode message) {
        if (message == null) {
            return false;
        }
        JsonNode toolCalls = message.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
            return true;
        }
        return hasNativeGenericChatPayloadContent(nativeMessageContent(message));
    }

    private static JsonNode nativeMessageContent(ObjectNode message) {
        if (message == null) {
            return null;
        }
        JsonNode content = message.get("content");
        if (hasNativeGenericChatPayloadContent(content) || !isNativeGenericChatContent(content)) {
            return content;
        }
        JsonNode fallback = firstExisting(message, "text", "value", "prompt", "input", "query", "parts");
        return fallback == null ? content : fallback;
    }

    private static boolean hasNativeGenericChatPayloadContent(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return false;
        }
        if (content.isTextual()) {
            return !content.asText().isBlank();
        }
        if (content.isNumber() || content.isBoolean()) {
            return true;
        }
        if (content instanceof ObjectNode object) {
            return hasNativeGenericChatPayloadObject(object);
        }
        if (!content.isArray()) {
            return false;
        }
        for (JsonNode part : content) {
            if (hasNativeGenericChatPayloadPart(part)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNativeGenericChatPayloadPart(JsonNode part) {
        if (part == null || part.isNull()) {
            return false;
        }
        if (part.isTextual()) {
            return !part.asText().isBlank();
        }
        if (part.isNumber() || part.isBoolean()) {
            return true;
        }
        if (part instanceof ObjectNode object) {
            return hasNativeGenericChatPayloadObject(object);
        }
        return true;
    }

    private static boolean hasNativeGenericChatPayloadObject(ObjectNode object) {
        if (object == null || object.isEmpty()) {
            return false;
        }
        String type = nativeContentObjectType(object);
        if (isTextLikeNativeContentObject(object, type)) {
            String text = chatTextPartText(object);
            return text != null && !text.isBlank();
        }
        if (isImageLikeNativeContentObject(object, type)) {
            return nativeImageUrl(object) != null;
        }
        if (isDocumentLikeNativeContentObject(object, type)) {
            return nativeDocumentUrl(object) != null;
        }
        if (isAudioLikeNativeContentObject(object, type)) {
            return nativeAudioUrl(object) != null;
        }
        if (isVideoLikeNativeContentObject(object, type)) {
            return nativeVideoUrl(object) != null;
        }
        return !isUnsupportedNativeContentObject(object);
    }

    private static boolean isNativeGenericChatContent(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return true;
        }
        if (content.isTextual() || content.isNumber() || content.isBoolean()) {
            return true;
        }
        if (content instanceof ObjectNode object) {
            return isNativeGenericChatContentObject(object);
        }
        if (!content.isArray()) {
            return false;
        }
        for (JsonNode part : content) {
            if (!isNativeGenericChatContentPart(part)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNativeGenericChatContentPart(JsonNode part) {
        if (part == null || part.isNull()) {
            return true;
        }
        if (part.isTextual() || part.isNumber() || part.isBoolean()) {
            return true;
        }
        if (part instanceof ObjectNode object) {
            return isNativeGenericChatContentObject(object);
        }
        return true;
    }

    private static boolean isNativeGenericChatContentObject(ObjectNode object) {
        if (object == null) {
            return true;
        }
        String type = nativeContentObjectType(object);
        if ("text".equals(type) || "input_text".equals(type)
                || (type.isBlank() && object.get("text") != null && object.size() <= 2)) {
            return true;
        }
        if (isImageLikeNativeContentObject(object, type)) {
            return nativeImageUrl(object) != null;
        }
        if (isDocumentLikeNativeContentObject(object, type)) {
            return nativeDocumentUrl(object) != null;
        }
        if (isAudioLikeNativeContentObject(object, type)) {
            return nativeAudioUrl(object) != null;
        }
        if (isVideoLikeNativeContentObject(object, type)) {
            return nativeVideoUrl(object) != null;
        }
        return !isUnsupportedNativeContentObject(object);
    }

    private static boolean hasNativeMediaContent(JsonNode content) {
        if (content instanceof ObjectNode object) {
            return hasNativeMediaContentObject(object);
        }
        if (content == null || !content.isArray()) {
            return false;
        }
        for (JsonNode part : content) {
            if (!(part instanceof ObjectNode object)) {
                continue;
            }
            if (hasNativeMediaContentObject(object)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNativeMediaContentObject(ObjectNode object) {
        if (object == null) {
            return false;
        }
        String type = nativeContentObjectType(object);
        return isImageLikeNativeContentObject(object, type) && nativeImageUrl(object) != null
                || isDocumentLikeNativeContentObject(object, type) && nativeDocumentUrl(object) != null
                || isAudioLikeNativeContentObject(object, type) && nativeAudioUrl(object) != null
                || isVideoLikeNativeContentObject(object, type) && nativeVideoUrl(object) != null;
    }

    private static String nativeContentObjectType(ObjectNode object) {
        return firstNonBlank(textOrNull(object, "type"), "").toLowerCase(Locale.ROOT);
    }

    private static boolean isTextLikeNativeContentObject(ObjectNode object, String type) {
        return "text".equals(type)
                || "input_text".equals(type)
                || (type.isBlank() && (object.get("text") != null
                || object.get("value") != null
                || object.get("content") != null));
    }

    private static boolean isImageLikeNativeContentObject(ObjectNode object, String type) {
        return "image_url".equals(type)
                || "input_image".equals(type)
                || "image".equals(type)
                || object.get("image_url") != null
                || object.get("image") != null;
    }

    private static boolean isDocumentLikeNativeContentObject(ObjectNode object, String type) {
        return "document".equals(type)
                || "input_document".equals(type)
                || "input_file".equals(type)
                || "file".equals(type)
                || object.get("document_url") != null
                || object.get("document") != null
                || object.get("file") != null
                || object.get("file_data") != null
                || object.get("file_url") != null;
    }

    private static boolean isAudioLikeNativeContentObject(ObjectNode object, String type) {
        return "audio".equals(type)
                || "input_audio".equals(type)
                || object.get("audio_url") != null
                || object.get("audio") != null;
    }

    private static boolean isVideoLikeNativeContentObject(ObjectNode object, String type) {
        return "video".equals(type)
                || "input_video".equals(type)
                || object.get("video_url") != null
                || object.get("video") != null;
    }

    private static boolean isUnsupportedNativeContentObject(ObjectNode object) {
        String type = nativeContentObjectType(object);
        if (isDocumentLikeNativeContentObject(object, type) && nativeDocumentUrl(object) != null
                || isAudioLikeNativeContentObject(object, type) && nativeAudioUrl(object) != null
                || isVideoLikeNativeContentObject(object, type) && nativeVideoUrl(object) != null) {
            return false;
        }
        return isDocumentLikeNativeContentObject(object, type)
                || isAudioLikeNativeContentObject(object, type)
                || isVideoLikeNativeContentObject(object, type)
                || object.get("document") != null
                || object.get("audio") != null
                || object.get("video") != null
                || object.get("source") != null;
    }

    private static ImageUrl nativeImageUrl(ObjectNode object) {
        if (object == null) {
            return null;
        }
        String url = firstNonBlank(
                nativeImageUrlString(object.get("image_url")),
                nativeImageUrlString(object.get("image")),
                nativeImageUrlString(object.get("url")),
                nativeImageUrlString(object.get("uri")),
                nativeImageSourceUrl(object.get("source")));
        if (url == null || url.isBlank()) {
            return null;
        }
        ImageUrl.Builder builder = ImageUrl.builder().url(url.trim());
        ImageUrl.Detail detail = nativeImageDetail(firstNonBlank(
                textOrNull(object, "detail"),
                nativeImageDetailString(object.get("image_url")),
                nativeImageDetailString(object.get("image"))));
        if (detail != null) {
            builder.detail(detail);
        }
        return builder.build();
    }

    private static String nativeImageUrlString(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return nativeMediaDirectUrl(node.asText(), GEMINI_NATIVE_IMAGE_MIME_TYPES);
        }
        if (node instanceof ObjectNode object) {
            String mediaType = nativeMediaType(object, null);
            return firstNonBlank(
                    nativeMediaDirectUrl(textOrNull(object, "url"), mediaType, GEMINI_NATIVE_IMAGE_MIME_TYPES),
                    nativeMediaDirectUrl(textOrNull(object, "uri"), mediaType, GEMINI_NATIVE_IMAGE_MIME_TYPES),
                    nativeMediaDirectUrl(textOrNull(object, "image_url"), mediaType, GEMINI_NATIVE_IMAGE_MIME_TYPES),
                    nativeMediaInlineDataUrl(textOrNull(object, "data"),
                            nativeMediaType(object, "image/png"),
                            GEMINI_NATIVE_IMAGE_MIME_TYPES),
                    nativeImageSourceUrl(object.get("source")));
        }
        return null;
    }

    private static String nativeImageSourceUrl(JsonNode sourceNode) {
        return nativeMediaSourceUrl(sourceNode, GEMINI_NATIVE_IMAGE_MIME_TYPES, "image/png");
    }

    private static String nativeImageDetailString(JsonNode node) {
        if (node instanceof ObjectNode object) {
            return textOrNull(object, "detail");
        }
        return null;
    }

    private static ImageUrl.Detail nativeImageDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        return switch (detail.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> ImageUrl.Detail.Auto;
            case "high" -> ImageUrl.Detail.High;
            case "low" -> ImageUrl.Detail.Low;
            default -> null;
        };
    }

    private static DocumentUrl nativeDocumentUrl(ObjectNode object) {
        if (object == null) {
            return null;
        }
        String url = firstNonBlank(
                nativeMediaUrlString(object.get("document_url"), GEMINI_NATIVE_DOCUMENT_MIME_TYPES, null),
                nativeMediaUrlString(object.get("document"), GEMINI_NATIVE_DOCUMENT_MIME_TYPES, null),
                nativeMediaUrlString(object.get("file"), GEMINI_NATIVE_DOCUMENT_MIME_TYPES, null),
                nativeMediaDirectUrl(textOrNull(object, "url"),
                        nativeMediaType(object, null),
                        GEMINI_NATIVE_DOCUMENT_MIME_TYPES),
                nativeMediaDirectUrl(textOrNull(object, "uri"),
                        nativeMediaType(object, null),
                        GEMINI_NATIVE_DOCUMENT_MIME_TYPES),
                nativeMediaDirectUrl(textOrNull(object, "file_url"),
                        nativeMediaType(object, null),
                        GEMINI_NATIVE_DOCUMENT_MIME_TYPES),
                nativeMediaInlineDataUrl(textOrNull(object, "file_data"),
                        nativeMediaType(object, null),
                        GEMINI_NATIVE_DOCUMENT_MIME_TYPES),
                nativeMediaInlineDataUrl(textOrNull(object, "data"),
                        nativeMediaType(object, null),
                        GEMINI_NATIVE_DOCUMENT_MIME_TYPES),
                nativeMediaSourceUrl(object.get("source"), GEMINI_NATIVE_DOCUMENT_MIME_TYPES, null));
        if (url == null || url.isBlank()) {
            return null;
        }
        DocumentUrl.Builder builder = DocumentUrl.builder().url(url.trim());
        DocumentUrl.Detail detail = nativeDocumentDetail(firstNonBlank(
                textOrNull(object, "detail"),
                nativeMediaDetailString(object.get("document_url")),
                nativeMediaDetailString(object.get("document")),
                nativeMediaDetailString(object.get("file"))));
        if (detail != null) {
            builder.detail(detail);
        }
        return builder.build();
    }

    private static AudioUrl nativeAudioUrl(ObjectNode object) {
        if (object == null) {
            return null;
        }
        String url = firstNonBlank(
                nativeMediaUrlString(object.get("audio_url"), GEMINI_NATIVE_AUDIO_MIME_TYPES, null),
                nativeMediaUrlString(object.get("audio"), GEMINI_NATIVE_AUDIO_MIME_TYPES, null),
                nativeMediaDirectUrl(textOrNull(object, "url"),
                        nativeMediaType(object, null),
                        GEMINI_NATIVE_AUDIO_MIME_TYPES),
                nativeMediaDirectUrl(textOrNull(object, "uri"),
                        nativeMediaType(object, null),
                        GEMINI_NATIVE_AUDIO_MIME_TYPES),
                nativeMediaInlineDataUrl(textOrNull(object, "data"),
                        nativeMediaType(object, null),
                        GEMINI_NATIVE_AUDIO_MIME_TYPES),
                nativeMediaSourceUrl(object.get("source"), GEMINI_NATIVE_AUDIO_MIME_TYPES, null));
        if (url == null || url.isBlank()) {
            return null;
        }
        AudioUrl.Builder builder = AudioUrl.builder().url(url.trim());
        AudioUrl.Detail detail = nativeAudioDetail(firstNonBlank(
                textOrNull(object, "detail"),
                nativeMediaDetailString(object.get("audio_url")),
                nativeMediaDetailString(object.get("audio"))));
        if (detail != null) {
            builder.detail(detail);
        }
        return builder.build();
    }

    private static VideoUrl nativeVideoUrl(ObjectNode object) {
        if (object == null) {
            return null;
        }
        String url = firstNonBlank(
                nativeMediaUrlString(object.get("video_url"), GEMINI_NATIVE_VIDEO_MIME_TYPES, null),
                nativeMediaUrlString(object.get("video"), GEMINI_NATIVE_VIDEO_MIME_TYPES, null),
                nativeMediaDirectUrl(textOrNull(object, "url"),
                        nativeMediaType(object, null),
                        GEMINI_NATIVE_VIDEO_MIME_TYPES),
                nativeMediaDirectUrl(textOrNull(object, "uri"),
                        nativeMediaType(object, null),
                        GEMINI_NATIVE_VIDEO_MIME_TYPES),
                nativeMediaInlineDataUrl(textOrNull(object, "data"),
                        nativeMediaType(object, null),
                        GEMINI_NATIVE_VIDEO_MIME_TYPES),
                nativeMediaSourceUrl(object.get("source"), GEMINI_NATIVE_VIDEO_MIME_TYPES, null));
        if (url == null || url.isBlank()) {
            return null;
        }
        VideoUrl.Builder builder = VideoUrl.builder().url(url.trim());
        VideoUrl.Detail detail = nativeVideoDetail(firstNonBlank(
                textOrNull(object, "detail"),
                nativeMediaDetailString(object.get("video_url")),
                nativeMediaDetailString(object.get("video"))));
        if (detail != null) {
            builder.detail(detail);
        }
        return builder.build();
    }

    private static String nativeMediaUrlString(JsonNode node, Set<String> allowedMimeTypes, String defaultMimeType) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return nativeMediaDirectUrl(node.asText(), allowedMimeTypes);
        }
        if (node instanceof ObjectNode object) {
            String mediaType = nativeMediaType(object, defaultMimeType);
            return firstNonBlank(
                    nativeMediaDirectUrl(textOrNull(object, "url"), mediaType, allowedMimeTypes),
                    nativeMediaDirectUrl(textOrNull(object, "uri"), mediaType, allowedMimeTypes),
                    nativeMediaDirectUrl(textOrNull(object, "file_url"), mediaType, allowedMimeTypes),
                    nativeMediaInlineDataUrl(textOrNull(object, "file_data"), mediaType, allowedMimeTypes),
                    nativeMediaInlineDataUrl(textOrNull(object, "data"), mediaType, allowedMimeTypes),
                    nativeMediaSourceUrl(object.get("source"), allowedMimeTypes, defaultMimeType));
        }
        return null;
    }

    private static String nativeMediaSourceUrl(JsonNode sourceNode, Set<String> allowedMimeTypes, String defaultMimeType) {
        if (!(sourceNode instanceof ObjectNode source)) {
            return null;
        }
        String sourceType = firstNonBlank(textOrNull(source, "type"), "").toLowerCase(Locale.ROOT);
        if ("url".equals(sourceType) || "uri".equals(sourceType)) {
            return nativeMediaDirectUrl(firstNonBlank(textOrNull(source, "url"), textOrNull(source, "uri")),
                    nativeMediaType(source, null),
                    allowedMimeTypes);
        }
        if ("base64".equals(sourceType)) {
            return nativeMediaInlineDataUrl(textOrNull(source, "data"),
                    nativeMediaType(source, defaultMimeType),
                    allowedMimeTypes);
        }
        if ("text".equals(sourceType) && allowedMimeTypes.contains("text/plain")) {
            String text = firstNonBlank(textOrNull(source, "text"), textOrNull(source, "data"));
            if (text == null || text.isBlank()) {
                return null;
            }
            String encoded = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
            return "data:text/plain;base64," + encoded;
        }
        return null;
    }

    private static String nativeMediaDirectUrl(String value, Set<String> allowedMimeTypes) {
        return nativeMediaDirectUrl(value, null, allowedMimeTypes);
    }

    private static String nativeMediaDirectUrl(String value, String mediaType, Set<String> allowedMimeTypes) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (mediaType != null && !isAllowedNativeMimeType(mediaType, allowedMimeTypes)) {
            return null;
        }
        if (isDataUrl(trimmed) && !isAllowedNativeMimeType(dataUrlMimeType(trimmed), allowedMimeTypes)) {
            return null;
        }
        return trimmed;
    }

    private static String nativeMediaInlineDataUrl(String data, String mediaType, Set<String> allowedMimeTypes) {
        if (data == null || data.isBlank()) {
            return null;
        }
        String trimmed = data.trim();
        if (isDataUrl(trimmed)) {
            return nativeMediaDirectUrl(trimmed, allowedMimeTypes);
        }
        if (looksLikeExternalMediaUrl(trimmed)) {
            return nativeMediaDirectUrl(trimmed, allowedMimeTypes);
        }
        if (!isAllowedNativeMimeType(mediaType, allowedMimeTypes)) {
            return null;
        }
        return "data:" + normalizeMediaType(mediaType) + ";base64," + trimmed;
    }

    private static String nativeMediaType(ObjectNode object, String defaultMimeType) {
        if (object == null) {
            return defaultMimeType;
        }
        return firstNonBlank(
                textOrNull(object, "media_type"),
                textOrNull(object, "mediaType"),
                textOrNull(object, "mime_type"),
                textOrNull(object, "mimeType"),
                defaultMimeType);
    }

    private static String nativeMediaDetailString(JsonNode node) {
        if (node instanceof ObjectNode object) {
            return textOrNull(object, "detail");
        }
        return null;
    }

    private static String dataUrlMimeType(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (!isDataUrl(trimmed)) {
            return null;
        }
        int comma = trimmed.indexOf(',');
        if (comma < 0) {
            return null;
        }
        String header = trimmed.substring(5, comma);
        int semicolon = header.indexOf(';');
        String mediaType = semicolon >= 0 ? header.substring(0, semicolon) : header;
        return mediaType == null || mediaType.isBlank() ? null : mediaType;
    }

    private static boolean isDataUrl(String value) {
        return value != null && value.trim().regionMatches(true, 0, "data:", 0, 5);
    }

    private static boolean looksLikeExternalMediaUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("oci://")
                || lower.startsWith("gs://")
                || lower.startsWith("file://");
    }

    private static boolean isAllowedNativeMimeType(String mediaType, Set<String> allowedMimeTypes) {
        String normalized = normalizeMediaType(mediaType);
        return normalized != null && allowedMimeTypes.contains(normalized);
    }

    private static String normalizeMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return null;
        }
        String value = mediaType.trim().toLowerCase(Locale.ROOT);
        int semicolon = value.indexOf(';');
        return semicolon >= 0 ? value.substring(0, semicolon).trim() : value;
    }

    private static DocumentUrl.Detail nativeDocumentDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        return switch (detail.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> DocumentUrl.Detail.Auto;
            case "high" -> DocumentUrl.Detail.High;
            case "low" -> DocumentUrl.Detail.Low;
            default -> null;
        };
    }

    private static AudioUrl.Detail nativeAudioDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        return switch (detail.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> AudioUrl.Detail.Auto;
            case "high" -> AudioUrl.Detail.High;
            case "low" -> AudioUrl.Detail.Low;
            default -> null;
        };
    }

    private static VideoUrl.Detail nativeVideoDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        return switch (detail.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> VideoUrl.Detail.Auto;
            case "high" -> VideoUrl.Detail.High;
            case "low" -> VideoUrl.Detail.Low;
            default -> null;
        };
    }

    private void proxyNativeGenericChat(
            OciUser tenant,
            String regionId,
            byte[] body,
            String modelHint,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        try (NativeGenericChatClient nativeClient = newNativeGenericChatClient(tenant, regionId)) {
            ObjectNode input = readObjectNode(body, "Gemini 原生 Chat 请求必须是 JSON 对象");
            String model = firstNonBlank(textOrNull(input, "model"), modelHint);
            if (model == null || model.isBlank()) {
                throw new OciException("Gemini 原生 Chat 请求缺少 model");
            }
            GenericChatRequest chatRequest = toNativeGenericChatRequest(input);
            ChatDetails details = ChatDetails.builder()
                    .compartmentId(tenant != null ? tenant.getOciTenantId() : null)
                    .servingMode(OnDemandServingMode.builder().modelId(model).build())
                    .chatRequest(chatRequest)
                    .build();
            ChatResult result = nativeClient.client().chat(ChatRequest.builder()
                            .chatDetails(details)
                            .build())
                    .getChatResult();
            String json = nativeGenericChatResultToOpenAiJson(result, model);
            captureUsageTokens(request, json);
            captureChatCompletionToolStats(request, json);
            if (Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.responsesToChat"))) {
                response.setStatus(200);
                if (Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.simulateResponsesSse"))) {
                    response.setHeader("cache-control", "no-cache");
                    response.setContentType("text/event-stream; charset=utf-8");
                    String sse = chatCompletionJsonToResponsesSse(json, model, request);
                    response.getOutputStream().write(sse.getBytes(StandardCharsets.UTF_8));
                    return;
                }
                response.setContentType("application/json; charset=utf-8");
                response.getOutputStream().write(convertChatCompletionJsonToResponsesJson(json, model).getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.simulateChatCompletionSse"))) {
                response.setStatus(200);
                response.setHeader("cache-control", "no-cache");
                response.setContentType("text/event-stream; charset=utf-8");
                String sse = chatCompletionJsonToSse(json, model);
                response.getOutputStream().write((sse == null ? "data: [DONE]\n\n" : sse).getBytes(StandardCharsets.UTF_8));
                return;
            }
            response.setStatus(200);
            response.setContentType("application/json; charset=utf-8");
            response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        } catch (OciException | IOException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("Gemini 原生 Chat 调用失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }

    private void proxyNativeCohereChatV2(
            OciUser tenant,
            String regionId,
            byte[] body,
            String modelHint,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        try (NativeGenericChatClient nativeClient = newNativeGenericChatClient(tenant, regionId)) {
            ObjectNode input = readObjectNode(body, "Cohere V2 Chat 请求必须是 JSON 对象");
            String model = firstNonBlank(textOrNull(input, "model"), modelHint);
            if (model == null || model.isBlank()) {
                throw new OciException("Cohere V2 Chat 请求缺少 model");
            }
            CohereChatRequestV2 chatRequest = toNativeCohereChatRequestV2(input);
            ChatDetails details = ChatDetails.builder()
                    .compartmentId(tenant != null ? tenant.getOciTenantId() : null)
                    .servingMode(OnDemandServingMode.builder().modelId(model).build())
                    .chatRequest(chatRequest)
                    .build();
            ChatResult result = nativeClient.client().chat(ChatRequest.builder()
                            .chatDetails(details)
                            .build())
                    .getChatResult();
            String json = nativeCohereChatV2ResultToOpenAiJson(result, model);
            captureUsageTokens(request, json);
            captureChatCompletionToolStats(request, json);
            if (Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.responsesToChat"))) {
                response.setStatus(200);
                if (Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.simulateResponsesSse"))) {
                    response.setHeader("cache-control", "no-cache");
                    response.setContentType("text/event-stream; charset=utf-8");
                    String sse = chatCompletionJsonToResponsesSse(json, model, request);
                    response.getOutputStream().write(sse.getBytes(StandardCharsets.UTF_8));
                    return;
                }
                response.setContentType("application/json; charset=utf-8");
                response.getOutputStream().write(convertChatCompletionJsonToResponsesJson(json, model).getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.simulateChatCompletionSse"))) {
                response.setStatus(200);
                response.setHeader("cache-control", "no-cache");
                response.setContentType("text/event-stream; charset=utf-8");
                String sse = chatCompletionJsonToSse(json, model);
                response.getOutputStream().write((sse == null ? "data: [DONE]\n\n" : sse).getBytes(StandardCharsets.UTF_8));
                return;
            }
            response.setStatus(200);
            response.setContentType("application/json; charset=utf-8");
            response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        } catch (OciException | IOException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("Cohere V2 Chat 调用失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }

    private NativeGenericChatClient newNativeGenericChatClient(OciUser tenant, String regionId) {
        ClientConfiguration clientConfig = ClientConfiguration.builder()
                .connectionTimeoutMillis(10_000)
                .readTimeoutMillis(3_600_000)
                .build();
        OciProxyConfigService ps = OciProxyConfigService.instance();
        OciProxySnapshot snap = ps == null ? null : ps.snapshot();
        if (ps == null || !ps.ociUsesExplicitClientProxy()) {
            OciProxyConfigService.clearInProcessHttpSocksProxySystemProperties();
        }
        final org.apache.http.impl.conn.PoolingHttpClientConnectionManager socksPool =
                snap != null && snap.usesSocksForOci() ? OciSocksApacheConnectionManager.create(snap) : null;
        final ClientConfigurator ociClientConfigurator;
        if (socksPool != null) {
            ociClientConfigurator = b -> {
                b.property(ApacheClientProperties.CONNECTION_MANAGER, socksPool);
                b.property(ApacheClientProperties.CONNECTION_MANAGER_SHARED, Boolean.TRUE);
            };
        } else {
            java.util.Optional<ProxyConfiguration> proxyConfiguration =
                    ps == null ? java.util.Optional.empty() : ps.getOciProxyConfiguration();
            if (proxyConfiguration.isPresent()) {
                ProxyConfiguration pc = proxyConfiguration.get();
                ociClientConfigurator = c -> c.property(StandardClientProperties.PROXY, pc);
            } else {
                ociClientConfigurator = OciProxyConfigService.ociSdkJerseyDirectConfigurator();
            }
        }
        var builder = GenerativeAiInferenceClient.builder().configuration(clientConfig);
        builder.additionalClientConfigurator(ociClientConfigurator);
        GenerativeAiInferenceClient client = builder.build(buildProvider(tenant, regionId));
        client.setRegion(regionId);
        return new NativeGenericChatClient(client, socksPool);
    }

    private static ObjectNode readObjectNode(byte[] body, String message) throws Exception {
        JsonNode root = body == null ? null : MAPPER.readTree(body);
        if (root instanceof ObjectNode object) {
            return object;
        }
        throw new OciException(message);
    }

    static GenericChatRequest toNativeGenericChatRequest(ObjectNode input) {
        GenericChatRequest.Builder builder = GenericChatRequest.builder()
                .messages(toNativeMessages(input.get("messages")))
                .isStream(false);
        Integer maxTokens = firstInteger(input, "max_tokens", "maxTokens");
        if (maxTokens != null && maxTokens > 0) {
            builder.maxTokens(maxTokens);
        }
        Integer maxCompletionTokens = firstInteger(input, "max_completion_tokens", "maxCompletionTokens");
        if (maxCompletionTokens != null && maxCompletionTokens > 0) {
            builder.maxCompletionTokens(maxCompletionTokens);
        }
        Double temperature = firstDouble(input, "temperature");
        if (temperature != null) {
            builder.temperature(temperature);
        }
        Double topP = firstDouble(input, "top_p", "topP");
        if (topP != null) {
            builder.topP(topP);
        }
        Double frequencyPenalty = firstDouble(input, "frequency_penalty", "frequencyPenalty");
        if (frequencyPenalty != null) {
            builder.frequencyPenalty(frequencyPenalty);
        }
        Double presencePenalty = firstDouble(input, "presence_penalty", "presencePenalty");
        if (presencePenalty != null) {
            builder.presencePenalty(presencePenalty);
        }
        List<String> stop = stringList(input.get("stop"));
        if (!stop.isEmpty()) {
            builder.stop(stop);
        }
        Boolean parallelToolCalls = firstBoolean(input, "parallel_tool_calls", "parallelToolCalls");
        if (parallelToolCalls != null) {
            builder.isParallelToolCalls(parallelToolCalls);
        }
        List<ToolDefinition> tools = toNativeToolDefinitions(input.get("tools"));
        if (!tools.isEmpty()) {
            builder.tools(tools);
        }
        ToolChoice toolChoice = toNativeToolChoice(input.get("tool_choice"));
        if (toolChoice != null) {
            builder.toolChoice(toolChoice);
        }
        return builder.build();
    }

    static CohereChatRequestV2 toNativeCohereChatRequestV2(ObjectNode input) {
        CohereChatRequestV2.Builder builder = CohereChatRequestV2.builder()
                .messages(toCohereMessagesV2(input.get("messages")))
                .isStream(false);
        Integer maxTokens = firstInteger(input, "max_tokens", "maxTokens");
        if (maxTokens != null && maxTokens > 0) {
            builder.maxTokens(Math.min(maxTokens, COHERE_COMMAND_A_REASONING_ON_DEMAND_MAX_TOKENS));
        }
        Double temperature = firstDouble(input, "temperature");
        if (temperature != null) {
            builder.temperature(temperature);
        }
        Integer topK = firstInteger(input, "top_k", "topK");
        if (topK != null) {
            builder.topK(topK);
        }
        Double topP = firstDouble(input, "top_p", "topP");
        if (topP != null) {
            builder.topP(topP);
        }
        Double frequencyPenalty = firstDouble(input, "frequency_penalty", "frequencyPenalty");
        if (frequencyPenalty != null) {
            builder.frequencyPenalty(frequencyPenalty);
        }
        Double presencePenalty = firstDouble(input, "presence_penalty", "presencePenalty");
        if (presencePenalty != null) {
            builder.presencePenalty(presencePenalty);
        }
        Integer seed = firstInteger(input, "seed");
        if (seed != null) {
            builder.seed(seed);
        }
        List<String> stop = stringList(input.get("stop"));
        if (!stop.isEmpty()) {
            builder.stopSequences(stop);
        }
        CohereThinkingV2 thinking = toCohereThinkingV2(input.get("thinking"));
        if (thinking != null) {
            builder.thinking(thinking);
        }
        CohereChatRequestV2.SafetyMode safetyMode = toCohereSafetyMode(firstText(input, "safety_mode", "safetyMode"));
        if (safetyMode != null) {
            builder.safetyMode(safetyMode);
        }
        Boolean strictTools = firstBoolean(input, "strict_tools", "strictTools", "is_strict_tools_enabled", "isStrictToolsEnabled");
        if (strictTools != null) {
            builder.isStrictToolsEnabled(strictTools);
        }
        List<CohereToolV2> tools = toCohereToolDefinitionsV2(input.get("tools"));
        if (!tools.isEmpty()) {
            builder.tools(tools);
        }
        CohereChatRequestV2.ToolsChoice toolsChoice = toCohereToolsChoice(input.get("tool_choice"));
        if (toolsChoice != null) {
            builder.toolsChoice(toolsChoice);
        }
        return builder.build();
    }

    private static CohereThinkingV2 toCohereThinkingV2(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        CohereThinkingV2.Builder builder = CohereThinkingV2.builder();
        boolean hasValue = false;
        if (node.isBoolean()) {
            builder.type(node.asBoolean() ? CohereThinkingV2.Type.Enabled : CohereThinkingV2.Type.Disabled);
            hasValue = true;
        } else if (node.isTextual()) {
            String value = node.asText("").trim().toLowerCase(Locale.ROOT);
            if ("disabled".equals(value) || "false".equals(value) || "off".equals(value)) {
                builder.type(CohereThinkingV2.Type.Disabled);
                hasValue = true;
            } else if ("enabled".equals(value) || "true".equals(value) || "on".equals(value)) {
                builder.type(CohereThinkingV2.Type.Enabled);
                hasValue = true;
            }
        } else if (node instanceof ObjectNode object) {
            String type = firstText(object, "type");
            if (type != null && !type.isBlank()) {
                String value = type.trim().toLowerCase(Locale.ROOT);
                if ("disabled".equals(value) || "false".equals(value) || "off".equals(value)) {
                    builder.type(CohereThinkingV2.Type.Disabled);
                    hasValue = true;
                } else if ("enabled".equals(value) || "true".equals(value) || "on".equals(value)) {
                    builder.type(CohereThinkingV2.Type.Enabled);
                    hasValue = true;
                }
            }
            Integer budget = firstInteger(object, "token_budget", "tokenBudget");
            if (budget != null && budget > 0) {
                builder.tokenBudget(budget);
                hasValue = true;
            }
        }
        return hasValue ? builder.build() : null;
    }

    private static CohereChatRequestV2.SafetyMode toCohereSafetyMode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "contextual" -> CohereChatRequestV2.SafetyMode.Contextual;
            case "strict" -> CohereChatRequestV2.SafetyMode.Strict;
            case "off", "none", "disabled" -> CohereChatRequestV2.SafetyMode.Off;
            default -> null;
        };
    }

    private static CohereChatRequestV2.ToolsChoice toCohereToolsChoice(JsonNode toolChoice) {
        if (toolChoice == null || toolChoice.isNull() || toolChoice.isMissingNode()) {
            return null;
        }
        String value = null;
        if (toolChoice.isTextual()) {
            value = toolChoice.asText("");
        } else if (toolChoice instanceof ObjectNode object) {
            value = firstText(object, "type");
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "none" -> CohereChatRequestV2.ToolsChoice.None;
            case "required", "any" -> CohereChatRequestV2.ToolsChoice.Required;
            default -> null;
        };
    }

    private static List<CohereMessageV2> toCohereMessagesV2(JsonNode messagesNode) {
        List<CohereMessageV2> out = new ArrayList<>();
        if (messagesNode != null && messagesNode.isArray()) {
            for (JsonNode item : messagesNode) {
                if (!(item instanceof ObjectNode message)) {
                    continue;
                }
                String role = normalizeChatRole(textOrNull(message, "role"));
                List<CohereContentV2> content = toCohereContentV2(nativeMessageContent(message));
                switch (role) {
                    case "system", "developer" -> {
                        if (!hasUsableCohereContentV2(content)) {
                            continue;
                        }
                        out.add(CohereSystemMessageV2.builder().content(content).build());
                    }
                    case "assistant" -> {
                        List<CohereToolCallV2> toolCalls = toCohereToolCallsV2(message.get("tool_calls"));
                        if (!hasUsableCohereContentV2(content) && toolCalls.isEmpty()) {
                            continue;
                        }
                        CohereAssistantMessageV2.Builder builder = CohereAssistantMessageV2.builder().content(content);
                        String reasoning = textOrNull(message, "reasoning_content");
                        if (reasoning != null && !reasoning.isBlank()) {
                            builder.toolPlan(reasoning);
                        }
                        if (!toolCalls.isEmpty()) {
                            builder.toolCalls(toolCalls);
                        }
                        out.add(builder.build());
                    }
                    case "tool" -> {
                        String toolCallId = textOrNull(message, "tool_call_id");
                        if (!hasUsableCohereContentV2(content)) {
                            if (toolCallId == null || toolCallId.isBlank()) {
                                continue;
                            }
                            content = List.of(CohereTextContentV2.builder().text("null").build());
                        }
                        CohereToolMessageV2.Builder builder = CohereToolMessageV2.builder().content(content);
                        if (toolCallId != null && !toolCallId.isBlank()) {
                            builder.toolCallId(toolCallId);
                        }
                        out.add(builder.build());
                    }
                    default -> {
                        if (!hasUsableCohereContentV2(content)) {
                            continue;
                        }
                        out.add(CohereUserMessageV2.builder().content(content).build());
                    }
                }
            }
        }
        if (out.isEmpty()) {
            out.add(CohereUserMessageV2.builder()
                    .content(List.of(CohereTextContentV2.builder().text(" ").build()))
                    .build());
        }
        return out;
    }

    private static List<CohereContentV2> toCohereContentV2(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return List.of();
        }
        if (content.isTextual()) {
            String text = content.asText();
            return text == null || text.isBlank()
                    ? List.of()
                    : List.of(CohereTextContentV2.builder().text(text).build());
        }
        if (content.isNumber() || content.isBoolean()) {
            return List.of(CohereTextContentV2.builder().text(content.asText()).build());
        }
        if (content instanceof ObjectNode object) {
            CohereContentV2 part = toCohereContentPartV2(object);
            return hasUsableCohereContentV2(part) ? List.of(part) : List.of();
        }
        if (!content.isArray()) {
            return List.of(CohereTextContentV2.builder().text(content.toString()).build());
        }
        List<CohereContentV2> out = new ArrayList<>();
        for (JsonNode part : content) {
            if (part == null || part.isNull()) {
                continue;
            }
            CohereContentV2 converted = toCohereContentPartV2(part);
            if (hasUsableCohereContentV2(converted)) {
                out.add(converted);
            }
        }
        return out;
    }

    private static CohereContentV2 toCohereContentPartV2(JsonNode part) {
        if (part == null || part.isNull()) {
            return CohereTextContentV2.builder().text("").build();
        }
        if (part.isTextual() || part.isNumber() || part.isBoolean()) {
            return CohereTextContentV2.builder().text(part.asText()).build();
        }
        if (!(part instanceof ObjectNode object)) {
            return CohereTextContentV2.builder().text(part.toString()).build();
        }
        if (isImageLikeNativeContentObject(object, nativeContentObjectType(object))) {
            CohereImageUrlV2 imageUrl = cohereImageUrlV2(object);
            if (imageUrl != null) {
                return CohereImageContentV2.builder().imageUrl(imageUrl).build();
            }
        }
        String type = nativeContentObjectType(object);
        if (isDocumentLikeNativeContentObject(object, type)) {
            JsonNode document = firstExisting(object, "document", "file", "source");
            if (document != null && !document.isNull() && !document.isMissingNode()) {
                return CohereDocumentContentV2.builder().document(MAPPER.convertValue(document, Object.class)).build();
            }
        }
        String text = chatTextPartText(object);
        return CohereTextContentV2.builder().text(text == null ? object.toString() : text).build();
    }

    private static CohereImageUrlV2 cohereImageUrlV2(ObjectNode object) {
        ImageUrl imageUrl = nativeImageUrl(object);
        if (imageUrl == null || imageUrl.getUrl() == null || imageUrl.getUrl().isBlank()) {
            return null;
        }
        CohereImageUrlV2.Builder builder = CohereImageUrlV2.builder().url(imageUrl.getUrl());
        if (imageUrl.getDetail() != null) {
            builder.detail(CohereImageUrlV2.Detail.create(imageUrl.getDetail().getValue()));
        }
        return builder.build();
    }

    private static boolean hasUsableCohereContentV2(List<CohereContentV2> content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        for (CohereContentV2 item : content) {
            if (hasUsableCohereContentV2(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUsableCohereContentV2(CohereContentV2 content) {
        if (content == null) {
            return false;
        }
        if (content instanceof CohereTextContentV2 textContent) {
            String text = textContent.getText();
            return text != null && !text.isBlank();
        }
        if (content instanceof CohereThinkingContentV2 thinkingContent) {
            String thinking = thinkingContent.getThinking();
            return thinking != null && !thinking.isBlank();
        }
        if (content instanceof CohereImageContentV2 imageContent) {
            return imageContent.getImageUrl() != null
                    && imageContent.getImageUrl().getUrl() != null
                    && !imageContent.getImageUrl().getUrl().isBlank();
        }
        return true;
    }

    private static List<CohereToolV2> toCohereToolDefinitionsV2(JsonNode toolsNode) {
        List<CohereToolV2> out = new ArrayList<>();
        if (toolsNode == null || !toolsNode.isArray()) {
            return out;
        }
        for (JsonNode item : toolsNode) {
            if (!(item instanceof ObjectNode tool)) {
                continue;
            }
            JsonNode fnNode = tool.get("function");
            ObjectNode fn = fnNode instanceof ObjectNode functionObject ? functionObject : tool;
            String type = firstNonBlank(textOrNull(tool, "type"), "function");
            if (!"function".equalsIgnoreCase(type)) {
                continue;
            }
            String name = textOrNull(fn, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            Function.Builder function = Function.builder().name(name);
            String description = textOrNull(fn, "description");
            if (description != null && !description.isBlank()) {
                function.description(description);
            }
            JsonNode parameters = fn.get("parameters");
            if (parameters != null && !parameters.isNull() && !parameters.isMissingNode()) {
                JsonNode sanitized = sanitizeOciToolParameters(parameters);
                function.parameters(MAPPER.convertValue(sanitized == null ? parameters : sanitized, Object.class));
            }
            out.add(CohereToolV2.builder()
                    .type(CohereToolV2.Type.Function)
                    .function(function.build())
                    .build());
        }
        return out;
    }

    private static List<CohereToolCallV2> toCohereToolCallsV2(JsonNode toolCallsNode) {
        List<CohereToolCallV2> out = new ArrayList<>();
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return out;
        }
        for (JsonNode item : toolCallsNode) {
            if (!(item instanceof ObjectNode call)) {
                continue;
            }
            JsonNode fnNode = call.get("function");
            ObjectNode fn = fnNode instanceof ObjectNode functionObject ? functionObject : MAPPER.createObjectNode();
            String name = firstNonBlank(textOrNull(fn, "name"), textOrNull(call, "name"), "tool");
            String arguments = firstNonBlank(textOrNull(fn, "arguments"), textOrNull(call, "arguments"), "{}");
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", name);
            function.put("arguments", arguments);
            out.add(CohereToolCallV2.builder()
                    .id(firstNonBlank(textOrNull(call, "id"), "call_" + CommonUtils.generateId()))
                    .type(CohereToolCallV2.Type.Function)
                    .function(function)
                    .build());
        }
        return out;
    }

    private static List<Message> toNativeMessages(JsonNode messagesNode) {
        List<Message> out = new ArrayList<>();
        if (messagesNode != null && messagesNode.isArray()) {
            for (JsonNode item : messagesNode) {
                if (!(item instanceof ObjectNode message)) {
                    continue;
                }
                String role = normalizeChatRole(textOrNull(message, "role"));
                List<ChatContent> content = toNativeContent(nativeMessageContent(message));
                String name = textOrNull(message, "name");
                switch (role) {
                    case "system" -> {
                        if (!hasUsableNativeContent(content)) {
                            continue;
                        }
                        SystemMessage.Builder builder = SystemMessage.builder().content(content);
                        if (name != null && !name.isBlank()) {
                            builder.name(name);
                        }
                        out.add(builder.build());
                    }
                    case "developer" -> {
                        if (!hasUsableNativeContent(content)) {
                            continue;
                        }
                        DeveloperMessage.Builder builder = DeveloperMessage.builder().content(content);
                        if (name != null && !name.isBlank()) {
                            builder.name(name);
                        }
                        out.add(builder.build());
                    }
                    case "assistant" -> {
                        List<ToolCall> toolCalls = toNativeToolCalls(message.get("tool_calls"));
                        if (!hasUsableNativeContent(content) && toolCalls.isEmpty()) {
                            continue;
                        }
                        AssistantMessage.Builder builder = AssistantMessage.builder().content(content);
                        if (name != null && !name.isBlank()) {
                            builder.name(name);
                        }
                        String reasoning = textOrNull(message, "reasoning_content");
                        if (reasoning != null && !reasoning.isBlank()) {
                            builder.reasoningContent(reasoning);
                        }
                        if (!toolCalls.isEmpty()) {
                            builder.toolCalls(toolCalls);
                        }
                        out.add(builder.build());
                    }
                    case "tool" -> {
                        ToolMessage.Builder builder = ToolMessage.builder().content(content);
                        String toolCallId = textOrNull(message, "tool_call_id");
                        if (!hasUsableNativeContent(content)) {
                            if (toolCallId == null || toolCallId.isBlank()) {
                                continue;
                            }
                            builder.content(List.of(TextContent.builder().text("null").build()));
                        }
                        if (toolCallId != null && !toolCallId.isBlank()) {
                            builder.toolCallId(toolCallId);
                        }
                        out.add(builder.build());
                    }
                    default -> {
                        if (!hasUsableNativeContent(content)) {
                            continue;
                        }
                        UserMessage.Builder builder = UserMessage.builder().content(content);
                        if (name != null && !name.isBlank()) {
                            builder.name(name);
                        }
                        out.add(builder.build());
                    }
                }
            }
        }
        if (out.isEmpty()) {
            out.add(UserMessage.builder().content(List.of(TextContent.builder().text(" ").build())).build());
        }
        return out;
    }

    private static List<ChatContent> toNativeContent(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return List.of();
        }
        if (content.isTextual()) {
            String text = content.asText();
            return text == null || text.isBlank()
                    ? List.of()
                    : List.of(TextContent.builder().text(text).build());
        }
        if (content.isNumber() || content.isBoolean()) {
            return List.of(TextContent.builder().text(content.asText()).build());
        }
        if (content instanceof ObjectNode object) {
            ChatContent part = toNativeContentPart(object);
            return hasUsableNativeContent(part) ? List.of(part) : List.of();
        }
        if (!content.isArray()) {
            return List.of(TextContent.builder().text(content.toString()).build());
        }
        List<ChatContent> out = new ArrayList<>();
        for (JsonNode part : content) {
            if (part == null || part.isNull()) {
                continue;
            }
            ChatContent converted = toNativeContentPart(part);
            if (hasUsableNativeContent(converted)) {
                out.add(converted);
            }
        }
        return out;
    }

    private static ChatContent toNativeContentPart(JsonNode part) {
        if (part == null || part.isNull()) {
            return TextContent.builder().text("").build();
        }
        if (part.isTextual() || part.isNumber() || part.isBoolean()) {
            return TextContent.builder().text(part.asText()).build();
        }
        if (!(part instanceof ObjectNode object)) {
            return TextContent.builder().text(part.toString()).build();
        }
        if (isImageLikeNativeContentObject(object, nativeContentObjectType(object))) {
            ImageUrl imageUrl = nativeImageUrl(object);
            if (imageUrl != null) {
                return ImageContent.builder().imageUrl(imageUrl).build();
            }
        }
        String type = nativeContentObjectType(object);
        if (isDocumentLikeNativeContentObject(object, type)) {
            DocumentUrl documentUrl = nativeDocumentUrl(object);
            if (documentUrl != null) {
                return DocumentContent.builder().documentUrl(documentUrl).build();
            }
        }
        if (isAudioLikeNativeContentObject(object, type)) {
            AudioUrl audioUrl = nativeAudioUrl(object);
            if (audioUrl != null) {
                return AudioContent.builder().audioUrl(audioUrl).build();
            }
        }
        if (isVideoLikeNativeContentObject(object, type)) {
            VideoUrl videoUrl = nativeVideoUrl(object);
            if (videoUrl != null) {
                return VideoContent.builder().videoUrl(videoUrl).build();
            }
        }
        String text = chatTextPartText(object);
        return TextContent.builder().text(text == null ? object.toString() : text).build();
    }

    private static boolean hasUsableNativeContent(List<ChatContent> content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        for (ChatContent item : content) {
            if (hasUsableNativeContent(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUsableNativeContent(ChatContent content) {
        if (content == null) {
            return false;
        }
        if (content instanceof TextContent textContent) {
            String text = textContent.getText();
            return text != null && !text.isBlank();
        }
        if (content instanceof ImageContent imageContent) {
            return imageContent.getImageUrl() != null
                    && imageContent.getImageUrl().getUrl() != null
                    && !imageContent.getImageUrl().getUrl().isBlank();
        }
        if (content instanceof DocumentContent documentContent) {
            return documentContent.getDocumentUrl() != null
                    && documentContent.getDocumentUrl().getUrl() != null
                    && !documentContent.getDocumentUrl().getUrl().isBlank();
        }
        if (content instanceof AudioContent audioContent) {
            return audioContent.getAudioUrl() != null
                    && audioContent.getAudioUrl().getUrl() != null
                    && !audioContent.getAudioUrl().getUrl().isBlank();
        }
        if (content instanceof VideoContent videoContent) {
            return videoContent.getVideoUrl() != null
                    && videoContent.getVideoUrl().getUrl() != null
                    && !videoContent.getVideoUrl().getUrl().isBlank();
        }
        return true;
    }

    private static String chatTextPartText(ObjectNode object) {
        if (object == null) {
            return null;
        }
        JsonNode text = object.get("text");
        if (text == null || text.isNull() || text.isMissingNode()) {
            text = firstExisting(object, "value", "content");
        }
        if (text == null || text.isNull() || text.isMissingNode()) {
            return null;
        }
        if (text.isTextual() || text.isNumber() || text.isBoolean()) {
            return text.asText();
        }
        if (text instanceof ObjectNode textObject) {
            String value = firstText(textObject, "text", "value", "content");
            if (value != null) {
                return value;
            }
        }
        return text.toString();
    }

    private static List<ToolCall> toNativeToolCalls(JsonNode toolCallsNode) {
        List<ToolCall> out = new ArrayList<>();
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return out;
        }
        for (JsonNode item : toolCallsNode) {
            if (!(item instanceof ObjectNode call)) {
                continue;
            }
            JsonNode fnNode = call.get("function");
            ObjectNode fn = fnNode instanceof ObjectNode functionObject ? functionObject : MAPPER.createObjectNode();
            String name = firstNonBlank(textOrNull(fn, "name"), textOrNull(call, "name"), "tool");
            String arguments = firstNonBlank(textOrNull(fn, "arguments"), textOrNull(call, "arguments"), "{}");
            out.add(FunctionCall.builder()
                    .id(firstNonBlank(textOrNull(call, "id"), "call_" + CommonUtils.generateId()))
                    .name(name)
                    .arguments(arguments)
                    .build());
        }
        return out;
    }

    private static List<ToolDefinition> toNativeToolDefinitions(JsonNode toolsNode) {
        List<ToolDefinition> out = new ArrayList<>();
        if (toolsNode == null || !toolsNode.isArray()) {
            return out;
        }
        for (JsonNode item : toolsNode) {
            if (!(item instanceof ObjectNode tool)) {
                continue;
            }
            JsonNode fnNode = tool.get("function");
            ObjectNode fn = fnNode instanceof ObjectNode functionObject ? functionObject : tool;
            String type = firstNonBlank(textOrNull(tool, "type"), "function");
            if (!"function".equalsIgnoreCase(type)) {
                continue;
            }
            String name = textOrNull(fn, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            FunctionDefinition.Builder builder = FunctionDefinition.builder().name(name);
            String description = textOrNull(fn, "description");
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            JsonNode parameters = fn.get("parameters");
            if (parameters != null && !parameters.isNull() && !parameters.isMissingNode()) {
                JsonNode sanitized = sanitizeOciToolParameters(parameters);
                builder.parameters(MAPPER.convertValue(sanitized == null ? parameters : sanitized, Object.class));
            }
            out.add(builder.build());
        }
        return out;
    }

    private static ToolChoice toNativeToolChoice(JsonNode toolChoice) {
        if (toolChoice == null || toolChoice.isNull() || toolChoice.isMissingNode()) {
            return null;
        }
        if (toolChoice.isTextual()) {
            String value = toolChoice.asText("").trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "auto" -> ToolChoiceAuto.builder().build();
                case "none" -> ToolChoiceNone.builder().build();
                case "required", "any" -> ToolChoiceRequired.builder().build();
                default -> null;
            };
        }
        if (toolChoice instanceof ObjectNode object) {
            String type = firstNonBlank(textOrNull(object, "type"), "").toLowerCase(Locale.ROOT);
            if ("auto".equals(type)) {
                return ToolChoiceAuto.builder().build();
            }
            if ("none".equals(type)) {
                return ToolChoiceNone.builder().build();
            }
            if ("required".equals(type) || "any".equals(type)) {
                return ToolChoiceRequired.builder().build();
            }
            if ("function".equals(type)) {
                JsonNode fnNode = object.get("function");
                ObjectNode fn = fnNode instanceof ObjectNode functionObject ? functionObject : object;
                String name = textOrNull(fn, "name");
                if (name != null && !name.isBlank()) {
                    return ToolChoiceFunction.builder().name(name).build();
                }
            }
        }
        return null;
    }

    static String nativeGenericChatResultToOpenAiJson(ChatResult result, String modelHint) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "chatcmpl-" + CommonUtils.generateId());
        root.put("object", "chat.completion");
        root.put("created", Instant.now().getEpochSecond());
        root.put("model", firstNonBlank(result == null ? null : result.getModelId(), modelHint, ""));
        ArrayNode choices = MAPPER.createArrayNode();
        Usage usage = null;
        BaseChatResponse response = result == null ? null : result.getChatResponse();
        if (!(response instanceof GenericChatResponse generic)) {
            throw new OciException("Gemini 原生 Chat 未返回可转换的对话结果");
        }
        usage = generic.getUsage();
        List<ChatChoice> nativeChoices = generic.getChoices();
        if (nativeChoices == null || nativeChoices.isEmpty()) {
            throw new OciException("Gemini 原生 Chat 未返回 choices");
        }
        boolean hasVisibleOutput = false;
        for (ChatChoice choice : nativeChoices) {
            if (choice == null) {
                continue;
            }
            ObjectNode item = MAPPER.createObjectNode();
            item.put("index", choice.getIndex() == null ? choices.size() : choice.getIndex());
            ObjectNode message = nativeMessageToOpenAiMessage(choice.getMessage());
            if (hasVisibleChatCompletionMessage(message)) {
                hasVisibleOutput = true;
            }
            item.set("message", message);
            item.put("finish_reason", normalizeNativeFinishReason(
                    choice.getFinishReason(),
                    message.path("tool_calls").isArray() && !message.path("tool_calls").isEmpty()));
            choices.add(item);
            if (usage == null) {
                usage = choice.getUsage();
            }
        }
        if (choices.isEmpty()) {
            throw new OciException("Gemini 原生 Chat 返回的 choices 无有效内容");
        }
        if (!hasVisibleOutput && log.isDebugEnabled()) {
            log.debug("Gemini native Chat returned choices without visible output; model={}", root.path("model").asText());
        }
        root.set("choices", choices);
        root.set("usage", nativeUsageToOpenAiUsage(usage));
        return MAPPER.writeValueAsString(root);
    }

    static String nativeCohereChatV2ResultToOpenAiJson(ChatResult result, String modelHint) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "chatcmpl-" + CommonUtils.generateId());
        root.put("object", "chat.completion");
        root.put("created", Instant.now().getEpochSecond());
        root.put("model", firstNonBlank(result == null ? null : result.getModelId(), modelHint, ""));
        BaseChatResponse response = result == null ? null : result.getChatResponse();
        if (!(response instanceof CohereChatResponseV2 cohere)) {
            throw new OciException("Cohere V2 Chat 未返回可转换的对话结果");
        }
        if (cohere.getErrorMessage() != null && !cohere.getErrorMessage().isBlank()) {
            throw new OciException("Cohere V2 Chat 返回错误: " + cohere.getErrorMessage());
        }
        CohereAssistantMessageV2 message = cohere.getMessage();
        if (message == null) {
            throw new OciException("Cohere V2 Chat 未返回 message");
        }
        ObjectNode openAiMessage = cohereV2MessageToOpenAiMessage(message);
        if (!hasVisibleChatCompletionMessage(openAiMessage)) {
            throw new OciException("Cohere V2 Chat 返回空内容");
        }
        ArrayNode choices = MAPPER.createArrayNode();
        ObjectNode choice = MAPPER.createObjectNode();
        choice.put("index", 0);
        choice.set("message", openAiMessage);
        boolean hasToolCalls = openAiMessage.path("tool_calls").isArray() && !openAiMessage.path("tool_calls").isEmpty();
        choice.put("finish_reason", normalizeNativeFinishReason(
                cohere.getFinishReason() == null ? null : cohere.getFinishReason().getValue(),
                hasToolCalls));
        choices.add(choice);
        root.set("choices", choices);
        root.set("usage", nativeUsageToOpenAiUsage(cohere.getUsage()));
        return MAPPER.writeValueAsString(root);
    }

    private static ObjectNode cohereV2MessageToOpenAiMessage(CohereAssistantMessageV2 message) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("role", "assistant");
        String text = cohereV2ContentText(message == null ? null : message.getContent(), false);
        String thinking = cohereV2ContentText(message == null ? null : message.getContent(), true);
        List<CohereToolCallV2> toolCalls = message == null ? null : message.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            out.putNull("content");
        } else {
            out.put("content", text == null ? "" : text);
        }
        if (thinking != null && !thinking.isBlank()) {
            out.put("reasoning_content", thinking);
        } else if (message != null && message.getToolPlan() != null && !message.getToolPlan().isBlank()) {
            out.put("reasoning_content", message.getToolPlan());
        }
        ArrayNode calls = cohereV2ToolCallsToOpenAi(toolCalls);
        if (!calls.isEmpty()) {
            out.set("tool_calls", calls);
        }
        return out;
    }

    private static String cohereV2ContentText(List<CohereContentV2> content, boolean thinkingOnly) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CohereContentV2 item : content) {
            String text = null;
            if (thinkingOnly) {
                if (item instanceof CohereThinkingContentV2 thinking) {
                    text = thinking.getThinking();
                }
            } else if (item instanceof CohereTextContentV2 textContent) {
                text = textContent.getText();
            }
            if (text == null || text.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text);
        }
        return sb.toString();
    }

    private static ArrayNode cohereV2ToolCallsToOpenAi(List<CohereToolCallV2> toolCalls) {
        ArrayNode calls = MAPPER.createArrayNode();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return calls;
        }
        for (CohereToolCallV2 toolCall : toolCalls) {
            if (toolCall == null || toolCall.getType() != CohereToolCallV2.Type.Function) {
                continue;
            }
            ObjectNode call = MAPPER.createObjectNode();
            call.put("id", firstNonBlank(toolCall.getId(), "call_" + CommonUtils.generateId()));
            call.put("type", "function");
            ObjectNode fn = MAPPER.createObjectNode();
            JsonNode function = MAPPER.convertValue(toolCall.getFunction(), JsonNode.class);
            String name = function instanceof ObjectNode object
                    ? firstNonBlank(firstText(object, "name"), firstText(object, "functionName"), "tool")
                    : "tool";
            JsonNode argumentsNode = function instanceof ObjectNode object
                    ? firstExisting(object, "arguments", "parameters")
                    : null;
            String arguments;
            if (argumentsNode == null || argumentsNode.isNull() || argumentsNode.isMissingNode()) {
                arguments = "{}";
            } else if (argumentsNode.isTextual()) {
                arguments = firstNonBlank(argumentsNode.asText(), "{}");
            } else {
                try {
                    arguments = MAPPER.writeValueAsString(argumentsNode);
                } catch (Exception ignored) {
                    arguments = "{}";
                }
            }
            fn.put("name", name);
            fn.put("arguments", arguments);
            call.set("function", fn);
            calls.add(call);
        }
        return calls;
    }

    private static boolean hasVisibleChatCompletionMessage(ObjectNode message) {
        if (message == null) {
            return false;
        }
        String content = chatMessageContentText(message.get("content"));
        if (content != null && !content.isBlank()) {
            return true;
        }
        String reasoning = textOrNull(message, "reasoning_content");
        if (reasoning != null && !reasoning.isBlank()) {
            return true;
        }
        JsonNode toolCalls = message.get("tool_calls");
        return toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty();
    }

    private static ObjectNode nativeMessageToOpenAiMessage(Message message) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("role", "assistant");
        out.put("content", nativeContentText(message == null ? null : message.getContent()));
        if (message instanceof AssistantMessage assistant) {
            String reasoning = assistant.getReasoningContent();
            if (reasoning != null && !reasoning.isBlank()) {
                out.put("reasoning_content", reasoning);
            }
            List<ToolCall> toolCalls = assistant.getToolCalls();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                ArrayNode calls = MAPPER.createArrayNode();
                for (ToolCall toolCall : toolCalls) {
                    if (toolCall instanceof FunctionCall functionCall) {
                        ObjectNode call = MAPPER.createObjectNode();
                        call.put("id", firstNonBlank(functionCall.getId(), "call_" + CommonUtils.generateId()));
                        call.put("type", "function");
                        ObjectNode fn = MAPPER.createObjectNode();
                        fn.put("name", firstNonBlank(functionCall.getName(), "tool"));
                        fn.put("arguments", firstNonBlank(functionCall.getArguments(), "{}"));
                        call.set("function", fn);
                        calls.add(call);
                    }
                }
                if (!calls.isEmpty()) {
                    out.putNull("content");
                    out.set("tool_calls", calls);
                }
            }
        }
        return out;
    }

    private static String nativeContentText(List<ChatContent> content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatContent item : content) {
            String text;
            if (item instanceof TextContent textContent) {
                text = textContent.getText();
            } else {
                text = reflectiveText(item);
                if (text == null || text.isBlank()) {
                    text = item == null ? "" : item.toString();
                }
            }
            if (text == null || text.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text);
        }
        return sb.toString();
    }

    private static String reflectiveText(Object item) {
        if (item == null) {
            return null;
        }
        try {
            Object value = item.getClass().getMethod("getText").invoke(item);
            return value == null ? null : String.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ObjectNode nativeUsageToOpenAiUsage(Usage usage) {
        ObjectNode out = MAPPER.createObjectNode();
        int prompt = usage == null || usage.getPromptTokens() == null ? 0 : Math.max(0, usage.getPromptTokens());
        int completion = usage == null || usage.getCompletionTokens() == null ? 0 : Math.max(0, usage.getCompletionTokens());
        int total = usage == null || usage.getTotalTokens() == null ? prompt + completion : Math.max(0, usage.getTotalTokens());
        out.put("prompt_tokens", prompt);
        out.put("completion_tokens", completion);
        out.put("total_tokens", total > 0 ? total : prompt + completion);
        return out;
    }

    private static String normalizeNativeFinishReason(String reason, boolean hasToolCalls) {
        if (hasToolCalls) {
            return "tool_calls";
        }
        if (reason == null || reason.isBlank()) {
            return "stop";
        }
        String value = reason.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (value.contains("tool")) {
            return "tool_calls";
        }
        if (value.contains("length") || value.contains("max")) {
            return "length";
        }
        if (value.contains("filter") || value.contains("safety")) {
            return "content_filter";
        }
        if (value.contains("stop") || value.contains("end")) {
            return "stop";
        }
        return "stop";
    }

    private static String firstText(JsonNode o, String... fieldNames) {
        if (o == null) {
            return null;
        }
        for (String f : fieldNames) {
            JsonNode n = o.get(f);
            if (n != null && n.isTextual() && !n.asText().isBlank()) {
                return n.asText();
            }
        }
        return null;
    }

    public static String extractPathAfterV1(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String path = request.getContextPath() != null ? request.getContextPath() : "";
        if (!path.isEmpty() && uri.startsWith(path)) {
            uri = uri.substring(path.length());
        }
        int p = uri.indexOf(V1);
        if (p < 0) {
            return "/";
        }
        String sub = uri.substring(p + V1.length());
        if (sub.isEmpty()) {
            return "/";
        }
        if (!sub.startsWith("/")) {
            return "/" + sub;
        }
        return sub;
    }

    public static String gatewayHint(int openaiPort) {
        return "http://<本机或域名>:" + openaiPort + "/v1";
    }

    public static SimpleAuthenticationDetailsProvider buildProvider(OciUser tenant) {
        return buildProvider(tenant, null);
    }

    public static SimpleAuthenticationDetailsProvider buildProvider(OciUser tenant, String regionId) {
        if (tenant == null) {
            throw new OciException("租户无效");
        }
        String effectiveRegion = effectivePublicRegionId(tenant, regionId);
        return SimpleAuthenticationDetailsProvider.builder()
                .tenantId(tenant.getOciTenantId())
                .userId(tenant.getOciUserId())
                .fingerprint(tenant.getOciFingerprint())
                .region(OciRegionUtil.toRegion(effectiveRegion))
                .privateKeySupplier(() -> {
                    try (var fis = new java.io.FileInputStream(tenant.getOciKeyPath());
                         var baos = new java.io.ByteArrayOutputStream()) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        return new java.io.ByteArrayInputStream(baos.toByteArray());
                    } catch (Exception e) {
                        throw new OciException("无法读取 OCI 私钥: " + e.getMessage());
                    }
                })
                .build();
    }

    /**
     * {@link DefaultRequestSigner} 的工厂方法要求 {@link BasicAuthenticationDetailsProvider}；
     * 当前 OCI Java SDK 中 {@link SimpleAuthenticationDetailsProvider} 在运行时即为此类型。
     */
    private static RequestSigner newRequestSigner(OciUser tenant) {
        return newRequestSigner(tenant, null);
    }

    private static RequestSigner newRequestSigner(OciUser tenant, String regionId) {
        return DefaultRequestSigner.createRequestSigner(
                OciBasicForSigning.from(buildProvider(tenant, regionId)));
    }

    private static String effectivePublicRegionId(OciUser tenant, Object region) {
        String r = region == null ? null : String.valueOf(region).trim();
        if (r == null || r.isEmpty() || "null".equalsIgnoreCase(r)) {
            r = tenant == null ? null : tenant.getOciRegion();
        }
        return OciRegionUtil.publicRegionId(r);
    }

    private Map<String, String> readRegionContext(OciUser tenant, String regionId) {
        Map<String, String> out = new LinkedHashMap<>();
        if (tenant == null || regionId == null || regionId.isBlank() || kvMapper == null) {
            return out;
        }
        try {
            OciKv row = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, REGION_CONTEXT_TYPE)
                    .eq(OciKv::getCode, regionContextCode(tenant, regionId)));
            if (row == null || row.getValue() == null || row.getValue().isBlank()) {
                return out;
            }
            JsonNode root = MAPPER.readTree(row.getValue());
            if (root != null && root.isObject()) {
                putIfNotBlank(out, "generativeOpenaiProject", text(root, "generativeOpenaiProject"));
                putIfNotBlank(out, "generativeConversationStoreId", text(root, "generativeConversationStoreId"));
            }
        } catch (Exception e) {
            log.debug("Failed to read Oracle AI region context tenant={} region={} message={}",
                    tenant.getId(), regionId, e.getMessage());
        }
        return out;
    }

    private static String regionContextCode(OciUser tenant, String regionId) {
        String base = firstNonBlank(
                tenant == null ? null : tenant.getId(),
                tenant == null ? null : tenant.getOciTenantId(),
                tenant == null ? null : tenant.getOciUserId(),
                "unknown") + "|" + String.valueOf(regionId);
        return "ai.ctx." + DigestUtil.sha256Hex(base).substring(0, 56);
    }

    private static void putJson(ObjectNode root, String field, String value) {
        String v = value == null ? null : value.trim();
        if (v == null || v.isBlank()) {
            root.putNull(field);
        } else {
            root.put(field, v);
        }
    }

    private static void putIfNotBlank(Map<String, String> out, String key, String value) {
        if (out == null || key == null) {
            return;
        }
        String v = value == null ? null : value.trim();
        if (v != null && !v.isBlank()) {
            out.put(key, v);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String encodePathSegmentOciModel(String s) {
        if (s == null) {
            return "";
        }
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isChatCompletionsPath(String p) {
        return p != null && (p.equals("/chat/completions") || p.endsWith("/chat/completions"));
    }

    private static boolean isResponsesPath(String p) {
        return p != null && (p.equals("/responses") || p.endsWith("/responses"));
    }

    private static boolean isModelsPath(String p) {
        return p != null && (p.equals("/models") || p.endsWith("/models"));
    }

    private static boolean isEmbeddingsPath(String p) {
        return p != null && (p.equals("/embeddings") || p.endsWith("/embeddings"));
    }

    static boolean isAudioSpeechPath(String p) {
        return p != null && (p.equals("/audio/speech") || p.endsWith("/audio/speech"));
    }

    static boolean shouldUseBinaryProxy(String p) {
        return isAudioSpeechPath(p);
    }

    private static boolean isRerankPath(String p) {
        return p != null && (p.equals("/rerank")
                || p.endsWith("/rerank")
                || p.equals("/rerankText")
                || p.endsWith("/rerankText")
                || p.equals("/rerank_text")
                || p.endsWith("/rerank_text"));
    }

    private static boolean isModelScopedRequestPath(String p) {
        return isChatCompletionsPath(p)
                || isResponsesPath(p)
                || isEmbeddingsPath(p)
                || isRerankPath(p)
                || isAudioSpeechPath(p);
    }

    private static String extractModelFromBody(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return null;
        }
        if (contentType != null && !contentType.isBlank() && !contentType.toLowerCase().contains("json")) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root != null && root.isObject()) {
                ObjectNode object = (ObjectNode) root;
                return firstNonBlank(
                        textOrNull(object, "model"),
                        textOrNull(object, "modelId"),
                        textOrNull(object, "model_id"),
                        servingModeModelId(object.get("servingMode")));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isAllowedModel(String model, List<String> allowedModels) {
        if (allowedModels == null || allowedModels.isEmpty()) {
            return true;
        }
        if (model == null || model.isBlank()) {
            return false;
        }
        Set<String> allowed = new HashSet<>();
        for (String item : allowedModels) {
            if (item != null && !item.isBlank()) {
                allowed.add(item.trim());
            }
        }
        return allowed.contains(model.trim());
    }

    private static List<String> requestAllowedModels(HttpServletRequest request) {
        if (request == null) {
            return List.of();
        }
        Object v = request.getAttribute(OpenAiApiConstants.ATTR_ALLOWED_MODELS_JSON);
        if (v == null) {
            return List.of();
        }
        return OracleAiPortBindingService.decodeAllowedModels(String.valueOf(v));
    }

    private static ObjectNode allowedModelsToOpenAiList(List<String> models) {
        ArrayNode data = MAPPER.createArrayNode();
        if (models != null) {
            for (String model : models) {
                if (model == null || model.isBlank()) {
                    continue;
                }
                ObjectNode row = MAPPER.createObjectNode();
                String id = model.trim();
                row.put("id", id);
                row.put("object", "model");
                row.put("ociworkerCapability", OracleAiModelCapability.classify(id));
                data.add(row);
            }
        }
        return buildOpenAiModelList(data);
    }

    private static void writeJson(HttpServletResponse response, JsonNode body) throws IOException {
        response.setStatus(200);
        response.setContentType("application/json; charset=utf-8");
        response.getOutputStream().write(MAPPER.writeValueAsBytes(body));
    }

    private static void writeOpenAiError(HttpServletResponse response, int status, String type, String message, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json; charset=utf-8");
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode error = MAPPER.createObjectNode();
        error.put("type", type);
        error.put("message", message);
        error.put("code", code);
        root.set("error", error);
        response.getOutputStream().write(MAPPER.writeValueAsBytes(root));
    }

    static RerankBridgeRequest transformRerankRequestJson(byte[] input, String defaultCompartmentId) throws Exception {
        if (input == null || input.length == 0) {
            throw new IllegalArgumentException("Rerank 请求体不能为空");
        }
        JsonNode root = MAPPER.readTree(input);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Rerank 请求必须是 JSON 对象");
        }
        ObjectNode in = (ObjectNode) root;
        String model = firstNonBlank(
                textOrNull(in, "model"),
                textOrNull(in, "modelId"),
                textOrNull(in, "model_id"),
                servingModeModelId(in.get("servingMode")));
        String query = firstNonBlank(textOrNull(in, "query"), textOrNull(in, "input"));
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Rerank 请求缺少 query/input");
        }
        JsonNode documentsNode = firstExisting(in, "documents", "texts");
        if (documentsNode == null || !documentsNode.isArray()) {
            throw new IllegalArgumentException("Rerank 请求缺少 documents 数组");
        }
        List<String> rankFields = textList(firstExisting(in, "rank_fields", "rankFields"));
        ArrayNode documents = MAPPER.createArrayNode();
        ArrayNode originalDocuments = MAPPER.createArrayNode();
        int documentIndex = 0;
        for (JsonNode document : documentsNode) {
            if (document == null || document.isNull()) {
                documentIndex++;
                continue;
            }
            String text = rerankDocumentToText(document, rankFields);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Rerank documents[" + documentIndex + "] 不能为空");
            }
            documents.add(text);
            originalDocuments.add(document.deepCopy());
            documentIndex++;
        }
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("Rerank documents 至少需要 1 条内容");
        }
        String compartmentId = firstNonBlank(
                textOrNull(in, "compartmentId"),
                textOrNull(in, "compartment_id"),
                defaultCompartmentId);
        if (compartmentId == null || compartmentId.isBlank()) {
            throw new IllegalArgumentException("Rerank 请求缺少 compartmentId，且当前租户无 ociTenantId");
        }

        ObjectNode out = MAPPER.createObjectNode();
        out.put("input", query);
        out.put("compartmentId", compartmentId);
        JsonNode servingMode = in.get("servingMode");
        if (servingMode != null && servingMode.isObject()) {
            out.set("servingMode", servingMode.deepCopy());
        } else {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("Rerank 请求缺少 model/modelId");
            }
            ObjectNode onDemand = MAPPER.createObjectNode();
            onDemand.put("servingType", "ON_DEMAND");
            onDemand.put("modelId", model);
            out.set("servingMode", onDemand);
        }
        out.set("documents", documents);
        putPositiveIntegerIfPresent(out, "topN", firstInteger(in, "topN", "top_n"), "top_n");
        Boolean returnDocuments = firstBoolean(in, "return_documents", "returnDocuments", "isEcho", "is_echo");
        if (returnDocuments != null) {
            out.put("isEcho", returnDocuments);
        }
        putPositiveIntegerIfPresent(out, "maxChunksPerDocument",
                firstInteger(in, "maxChunksPerDocument", "max_chunks_per_document", "max_chunks_per_doc"),
                "max_chunks_per_document");
        putPositiveIntegerIfPresent(out, "maxTokensPerDocument",
                firstInteger(in, "maxTokensPerDocument", "max_tokens_per_document", "max_tokens_per_doc"),
                "max_tokens_per_document");
        return new RerankBridgeRequest(
                MAPPER.writeValueAsBytes(out),
                Boolean.TRUE.equals(returnDocuments) ? MAPPER.writeValueAsString(originalDocuments) : null,
                Boolean.TRUE.equals(returnDocuments),
                compartmentId);
    }

    static String transformRerankResponseJson(String body, String originalDocumentsJson, boolean returnDocuments) throws Exception {
        if (body == null || body.isBlank()) {
            return body;
        }
        JsonNode root = MAPPER.readTree(body);
        if (root == null || !root.isObject()) {
            return body;
        }
        if (root.has("results") && !root.has("documentRanks")) {
            return body;
        }
        JsonNode documentRanks = root.get("documentRanks");
        if (documentRanks == null || !documentRanks.isArray()) {
            return body;
        }
        ArrayNode originalDocuments = readJsonArray(originalDocumentsJson);
        ObjectNode out = MAPPER.createObjectNode();
        String id = firstNonBlank(firstText(root, "id"), "rerank-" + CommonUtils.generateId());
        out.put("id", id);
        String modelId = firstText(root, "modelId");
        if (modelId != null && !modelId.isBlank()) {
            out.put("model", modelId);
            out.put("model_id", modelId);
        }
        String modelVersion = firstText(root, "modelVersion");
        if (modelVersion != null && !modelVersion.isBlank()) {
            out.put("model_version", modelVersion);
        }
        ArrayNode results = MAPPER.createArrayNode();
        int fallbackIndex = 0;
        for (JsonNode rank : documentRanks) {
            if (rank == null || !rank.isObject()) {
                continue;
            }
            ObjectNode rankObject = (ObjectNode) rank;
            Integer parsedIndex = firstInteger(rankObject, "index");
            int index = parsedIndex != null ? parsedIndex : fallbackIndex;
            ObjectNode item = MAPPER.createObjectNode();
            item.put("index", index);
            JsonNode score = firstExisting(rankObject, "relevanceScore", "relevance_score");
            if (score != null && score.isNumber()) {
                item.put("relevance_score", score.asDouble());
            } else {
                item.put("relevance_score", 0D);
            }
            JsonNode document = returnDocuments ? commonRerankDocument(rankObject.get("document"), originalDocuments, index) : null;
            if (document != null) {
                item.set("document", document);
            }
            results.add(item);
            fallbackIndex++;
        }
        out.set("results", results);
        ObjectNode meta = MAPPER.createObjectNode();
        ObjectNode apiVersion = MAPPER.createObjectNode();
        apiVersion.put("version", "2");
        meta.set("api_version", apiVersion);
        out.set("meta", meta);
        return MAPPER.writeValueAsString(out);
    }

    private static JsonNode firstExisting(ObjectNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            if (field == null) {
                continue;
            }
            JsonNode value = node.get(field);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private static String servingModeModelId(JsonNode servingMode) {
        if (servingMode != null && servingMode.isObject()) {
            return textOrNull((ObjectNode) servingMode, "modelId");
        }
        return null;
    }

    private static List<String> textList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                out.add(item.asText().trim());
            }
        }
        return out;
    }

    private static String rerankDocumentToText(JsonNode document, List<String> rankFields) throws Exception {
        if (document == null || document.isNull()) {
            return "";
        }
        if (document.isTextual()) {
            return document.asText();
        }
        if (document.isNumber() || document.isBoolean()) {
            return document.asText();
        }
        if (document.isObject()) {
            ObjectNode object = (ObjectNode) document;
            StringBuilder sb = new StringBuilder();
            if (rankFields != null && !rankFields.isEmpty()) {
                for (String field : rankFields) {
                    appendRerankText(sb, object.get(field));
                }
            }
            if (sb.length() == 0) {
                appendRerankText(sb, firstExisting(object, "text", "content", "body", "title"));
            }
            return sb.length() > 0 ? sb.toString() : MAPPER.writeValueAsString(document);
        }
        return MAPPER.writeValueAsString(document);
    }

    private static void appendRerankText(StringBuilder sb, JsonNode value) throws Exception {
        if (value == null || value.isNull()) {
            return;
        }
        String text;
        if (value.isTextual() || value.isNumber() || value.isBoolean()) {
            text = value.asText();
        } else {
            text = MAPPER.writeValueAsString(value);
        }
        if (text == null || text.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(text);
    }

    private static void putIntegerIfPresent(ObjectNode node, String field, Integer value) {
        if (node != null && field != null && value != null) {
            node.put(field, value);
        }
    }

    private static void putPositiveIntegerIfPresent(ObjectNode node, String field, Integer value, String inputName) {
        if (value == null) {
            return;
        }
        if (value < 1) {
            throw new IllegalArgumentException("Rerank " + firstNonBlank(inputName, field) + " 必须大于 0");
        }
        putIntegerIfPresent(node, field, value);
    }

    private static Integer firstInteger(ObjectNode node, String... fields) {
        JsonNode value = firstExisting(node, fields);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        if (value.isNumber()) {
            return (int) Math.round(value.asDouble());
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText().trim());
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Double firstDouble(ObjectNode node, String... fields) {
        JsonNode value = firstExisting(node, fields);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isTextual()) {
            try {
                return Double.parseDouble(value.asText().trim());
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (node.isTextual()) {
            String value = node.asText();
            return value == null || value.isBlank() ? List.of() : List.of(value);
        }
        if (!node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                out.add(item.asText());
            }
        }
        return out;
    }

    private static Boolean firstBoolean(ObjectNode node, String... fields) {
        JsonNode value = firstExisting(node, fields);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isTextual()) {
            String s = value.asText().trim();
            if ("true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s) || "0".equals(s) || "no".equalsIgnoreCase(s)) {
                return false;
            }
        }
        return null;
    }

    private static ArrayNode readJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return MAPPER.createArrayNode();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root != null && root.isArray()) {
                return (ArrayNode) root;
            }
        } catch (Exception ignored) {
        }
        return MAPPER.createArrayNode();
    }

    private static JsonNode commonRerankDocument(JsonNode ociDocument, ArrayNode originalDocuments, int index) {
        if (ociDocument != null && !ociDocument.isNull() && !ociDocument.isMissingNode()) {
            if (ociDocument.isObject()) {
                return ociDocument.deepCopy();
            }
            ObjectNode doc = MAPPER.createObjectNode();
            doc.put("text", ociDocument.asText(""));
            return doc;
        }
        if (originalDocuments != null && index >= 0 && index < originalDocuments.size()) {
            JsonNode original = originalDocuments.get(index);
            if (original != null && !original.isNull()) {
                if (original.isObject()) {
                    return original.deepCopy();
                }
                ObjectNode doc = MAPPER.createObjectNode();
                doc.put("text", original.asText(""));
                return doc;
            }
        }
        return null;
    }

    /**
     * 将 responses 请求中的 input 规范化为 OCI 能接受的 ModelInput：
     * - input 为 string -> 转为 [{role:"user", content:[{type:"input_text", text:"..."}]}]
     * - input 为 object -> 尝试当作单条 message 或带 messages 的对象
     * - input 为 array 且元素形如 {role, content:"..."} -> 转为 content 块数组
     * - 其余保持原样（尽量不破坏已是 responses 原生结构的请求）
     */
    private static byte[] normalizeResponsesInputForOci(byte[] input) {
        try {
            JsonNode root = MAPPER.readTree(input);
            if (root == null || !root.isObject()) {
                return input;
            }
            ObjectNode in = (ObjectNode) root;
            sanitizeResponsesToolSchema(in);
            JsonNode inputNode = in.get("input");
            if (inputNode == null || inputNode.isNull() || inputNode.isMissingNode()) {
                return MAPPER.writeValueAsBytes(in);
            }
            if (inputNode.isTextual()) {
                ArrayNode arr = MAPPER.createArrayNode();
                ObjectNode item = MAPPER.createObjectNode();
                item.put("role", "user");
                ArrayNode parts = MAPPER.createArrayNode();
                ObjectNode p = MAPPER.createObjectNode();
                p.put("type", "input_text");
                p.put("text", inputNode.asText());
                parts.add(p);
                item.set("content", parts);
                arr.add(item);
                in.set("input", arr);
                return MAPPER.writeValueAsBytes(in);
            }
            if (inputNode.isObject()) {
                ObjectNode io = (ObjectNode) inputNode;
                JsonNode msgs = io.get("messages");
                if (msgs != null && msgs.isArray()) {
                    ObjectNode fauxChat = MAPPER.createObjectNode();
                    fauxChat.set("messages", msgs);
                    byte[] mapped = transformChatCompletionsToResponsesJson(MAPPER.writeValueAsBytes(fauxChat), defaultMaxTokens());
                    JsonNode mappedRoot = MAPPER.readTree(mapped);
                    if (mappedRoot != null && mappedRoot.isObject() && mappedRoot.get("input") != null) {
                        in.set("input", mappedRoot.get("input"));
                        return MAPPER.writeValueAsBytes(in);
                    }
                }
                ArrayNode arr = MAPPER.createArrayNode();
                ObjectNode item = MAPPER.createObjectNode();
                item.put("role", normalizeResponsesMessageRole(textOrNull(io, "role")));
                JsonNode content = io.get("content");
                if (content != null && content.isTextual()) {
                    item.set("content", toInputTextParts(content.asText()));
                } else if (content != null && content.isArray()) {
                    item.set("content", content);
                } else if (content != null && content.isObject()) {
                    item.set("content", toInputTextParts(content.toString()));
                } else {
                    item.set("content", toInputTextParts(String.valueOf(content)));
                }
                arr.add(item);
                in.set("input", arr);
                // 继续让 array 分支做更细的块规范化
                inputNode = in.get("input");
            }
            if (inputNode.isArray()) {
                ArrayNode outArr = MAPPER.createArrayNode();
                for (JsonNode it : inputNode) {
                    if (it == null) {
                        continue;
                    }
                    if (it.isTextual()) {
                        ObjectNode item = MAPPER.createObjectNode();
                        item.put("role", "user");
                        item.set("content", toInputTextParts(it.asText()));
                        outArr.add(item);
                        continue;
                    }
                    if (!it.isObject()) {
                        // 兜底：未知类型转为文本块
                        ObjectNode item = MAPPER.createObjectNode();
                        item.put("role", "user");
                        item.set("content", toInputTextParts(String.valueOf(it)));
                        outArr.add(item);
                        continue;
                    }
                    ObjectNode io = (ObjectNode) it;
                    // role 只允许文本；未知 role 统一降级为 user（OCI ModelInput 更严格）
                    io.put("role", normalizeResponsesMessageRole(textOrNull(io, "role")));
                    JsonNode content = io.get("content");
                    if (content != null && content.isTextual()) {
                        io.set("content", toInputTextParts(content.asText()));
                    } else if (content != null && content.isArray()) {
                        // 兼容：content=[{type:"text", text:"..."}] 或 content=["..."]
                        ArrayNode normalized = MAPPER.createArrayNode();
                        for (JsonNode part : content) {
                            if (part == null || part.isNull()) {
                                continue;
                            }
                            if (part.isTextual()) {
                                normalized.add(toInputTextPartNode(part.asText()));
                                continue;
                            }
                            if (part.isObject()) {
                                ObjectNode po = (ObjectNode) part;
                                String t = textOrNull(po, "type");
                                if (t != null && ("text".equalsIgnoreCase(t) || "input_text".equalsIgnoreCase(t))) {
                                    String tx = textOrNull(po, "text");
                                    if (tx != null) {
                                        normalized.add(toInputTextPartNode(tx));
                                        continue;
                                    }
                                }
                                // 允许已是 responses 图片块（尽量不破坏）
                                if (t != null && "input_image".equalsIgnoreCase(t)) {
                                    normalized.add(po);
                                    continue;
                                }
                            }
                            // 兜底：未知块一律转为 input_text，避免 OCI ModelInput 反序列化失败
                            normalized.add(toInputTextPartNode(part.isTextual() ? part.asText() : part.toString()));
                        }
                        if (normalized.size() > 0) {
                            io.set("content", normalized);
                        } else {
                            io.set("content", toInputTextParts(""));
                        }
                    } else if (content != null && content.isObject()) {
                        io.set("content", toInputTextParts(content.toString()));
                    } else if (content == null || content.isNull()) {
                        io.set("content", toInputTextParts(""));
                    }
                    outArr.add(io);
                }
                in.set("input", outArr);
                return MAPPER.writeValueAsBytes(in);
            }
            return MAPPER.writeValueAsBytes(in);
        } catch (Exception e) {
            return input;
        }
    }

    private static void sanitizeResponsesToolSchema(ObjectNode root) {
        JsonNode tools = root.get("tools");
        if (tools == null || !tools.isArray()) {
            return;
        }
        ArrayNode normalizedTools = MAPPER.createArrayNode();
        boolean changed = false;
        for (JsonNode tool : tools) {
            if (!(tool instanceof ObjectNode source)) {
                normalizedTools.add(tool);
                continue;
            }
            ObjectNode normalized = source.deepCopy();
            ObjectNode fn = normalized.get("function") instanceof ObjectNode functionObject
                    ? functionObject
                    : normalized;
            JsonNode before = fn.get("parameters");
            JsonNode after = sanitizeOciToolParameters(before);
            if (after != null && !after.equals(before)) {
                fn.set("parameters", after);
                changed = true;
            }
            normalizedTools.add(normalized);
        }
        if (changed) {
            root.set("tools", normalizedTools);
        }
    }

    /**
     * 对 Multi-Agent 的 /v1/responses 请求做最小侵入的“截断历史”：
     * - 仅当 model 名称启发式命中 multi-agent
     * - 且 input 是数组并超过 maxItems
     * 则保留最后 maxItems 条，降低 TPM 触发概率与结构复杂度。
     */
    private static byte[] truncateResponsesInputForMultiAgent(byte[] body, int maxItems) {
        if (body == null || body.length == 0 || maxItems <= 0) {
            return body;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                return body;
            }
            ObjectNode o = (ObjectNode) root;
            String model = textOrNull(o, "model");
            if (!isLikelyMultiAgentModelName(model)) {
                return body;
            }
            JsonNode input = o.get("input");
            if (input == null || !input.isArray()) {
                return body;
            }
            ArrayNode arr = (ArrayNode) input;
            int n = arr.size();
            if (n <= maxItems) {
                return body;
            }
            ArrayNode out = MAPPER.createArrayNode();
            for (int i = n - maxItems; i < n; i++) {
                JsonNode it = arr.get(i);
                if (it != null) {
                    out.add(it);
                }
            }
            o.set("input", out);
            return MAPPER.writeValueAsBytes(o);
        } catch (Exception ignored) {
            return body;
        }
    }

    private static String describeResponsesInputShape(byte[] body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                return "root=" + (root == null ? "null" : root.getNodeType());
            }
            JsonNode input = root.get("input");
            if (input == null) {
                return "input=<missing>";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("input=").append(input.getNodeType());
            if (input.isTextual()) {
                sb.append("(len=").append(input.asText().length()).append(")");
                return sb.toString();
            }
            if (input.isObject()) {
                sb.append("(keys=");
                Iterator<String> it = input.fieldNames();
                int c = 0;
                while (it.hasNext() && c < 8) {
                    if (c > 0) sb.append(",");
                    sb.append(it.next());
                    c++;
                }
                if (it.hasNext()) sb.append(",…");
                sb.append(")");
                return sb.toString();
            }
            if (input.isArray()) {
                sb.append("(n=").append(input.size()).append(")");
                if (input.size() > 0) {
                    JsonNode first = input.get(0);
                    sb.append(" first=").append(first == null ? "null" : first.getNodeType());
                    if (first != null && first.isObject()) {
                        JsonNode ctn = first.get("content");
                        sb.append(" content=").append(ctn == null ? "<missing>" : ctn.getNodeType().toString());
                        if (ctn != null && ctn.isArray() && ctn.size() > 0) {
                            JsonNode p0 = ctn.get(0);
                            String t = (p0 != null && p0.isObject()) ? textOrNull((ObjectNode) p0, "type") : null;
                            if (t != null) {
                                sb.append(" part0.type=").append(t);
                            }
                        }
                    }
                }
                return sb.toString();
            }
            return sb.toString();
        } catch (Exception e) {
            return "parse_error(" + e.getClass().getSimpleName() + ")";
        }
    }

    private static ArrayNode toInputTextParts(String text) {
        ArrayNode parts = MAPPER.createArrayNode();
        parts.add(toInputTextPartNode(text));
        return parts;
    }

    private static ObjectNode toInputTextPartNode(String text) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("type", "input_text");
        p.put("text", text == null ? "" : text);
        return p;
    }

    private static boolean isStreamRequest(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return false;
        }
        if (contentType != null && !contentType.isBlank() && !contentType.toLowerCase().contains("json")) {
            return false;
        }
        try {
            JsonNode n = MAPPER.readTree(body);
            if (n != null && n.isObject()) {
                JsonNode s = n.get("stream");
                if (s == null) {
                    return false;
                }
                if (s.isBoolean()) {
                    return s.asBoolean();
                }
                if (s.isTextual() && "true".equalsIgnoreCase(s.asText())) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private static int defaultMaxTokens() {
        try {
            return OracleAiGatewayConfigService.normalizeDefaultMaxTokens(defaultMaxTokensSupplier.getAsInt());
        } catch (Exception ignored) {
            return DEFAULT_MAX_TOKENS;
        }
    }

    private static int requestDefaultMaxTokens(HttpServletRequest request) {
        if (request != null) {
            Object v = request.getAttribute(OpenAiApiConstants.ATTR_DEFAULT_MAX_TOKENS);
            if (v instanceof Number n) {
                return OracleAiGatewayConfigService.normalizeDefaultMaxTokens(n.intValue());
            }
            if (v != null) {
                try {
                    return OracleAiGatewayConfigService.normalizeDefaultMaxTokens(Integer.parseInt(String.valueOf(v)));
                } catch (Exception ignored) {
                }
            }
        }
        return defaultMaxTokens();
    }

    static byte[] transformChatCompletionsJson(byte[] input, int defaultMaxTokens) {
        try {
            JsonNode root = MAPPER.readTree(input);
            if (root == null || !root.isObject()) {
                return input;
            }
            ObjectNode o = (ObjectNode) root;
            normalizeChatCompletionTokenBudget(o, defaultMaxTokens);
            JsonNode force = o.get("force_non_stream");
            if (force != null && (force.isBoolean() && force.asBoolean()
                    || (force.isTextual() && "true".equalsIgnoreCase(force.asText())))) {
                o.put("stream", false);
            }
            removeOciUnsupportedChatRequestFields(o);
            normalizeChatToolSchema(o);
            JsonNode messages = o.get("messages");
            if (messages instanceof ArrayNode arrayMessages) {
                ArrayNode normalizedMessages = normalizeChatToolMessages(
                        arrayMessages,
                        isGeminiChatModel(textOrNull(o, "model")));
                String fallback = chatPromptFallback(o);
                if (!hasUsableChatMessages(normalizedMessages) && fallback != null && !fallback.isBlank()) {
                    addChatMessage(normalizedMessages, "user", fallback);
                }
                o.set("messages", normalizedMessages);
            }
            o.remove("force_non_stream");
            return MAPPER.writeValueAsBytes(o);
        } catch (Exception e) {
            return input;
        }
    }

    private static void removeOciUnsupportedChatRequestFields(ObjectNode root) {
        if (root == null) {
            return;
        }
        root.remove("reasoningEffort");
        root.remove("reasoning_effort");
        root.remove("reasoning");
        root.remove("max_completion_tokens");
    }

    private static void normalizeChatCompletionTokenBudget(ObjectNode root, int defaultMaxTokens) {
        if (root == null) {
            return;
        }
        JsonNode maxTokens = root.get("max_tokens");
        JsonNode maxCompletionTokens = root.get("max_completion_tokens");
        if (maxTokens == null || maxTokens.isNull() || maxTokens.isMissingNode()) {
            if (maxCompletionTokens != null && !maxCompletionTokens.isNull() && !maxCompletionTokens.isMissingNode()) {
                root.set("max_tokens", maxCompletionTokens.deepCopy());
            } else {
                root.put("max_tokens", OracleAiGatewayConfigService.normalizeDefaultMaxTokens(defaultMaxTokens));
            }
        }
        int value = positiveInt(root.get("max_tokens"), OracleAiGatewayConfigService.normalizeDefaultMaxTokens(defaultMaxTokens));
        if (value <= 0) {
            value = OracleAiGatewayConfigService.normalizeDefaultMaxTokens(defaultMaxTokens);
        }
        if (isGeminiChatModel(textOrNull(root, "model"))) {
            if (value > 0 && value < GEMINI_MIN_CHAT_COMPLETION_TOKENS) {
                value = GEMINI_MIN_CHAT_COMPLETION_TOKENS;
            }
        }
        if (isMetaLlamaOnDemandCappedModel(textOrNull(root, "model"))
                && value > META_LLAMA_ON_DEMAND_MAX_TOKENS) {
            value = META_LLAMA_ON_DEMAND_MAX_TOKENS;
        }
        if (isCohereCommandAReasoningModel(textOrNull(root, "model"))
                && value > COHERE_COMMAND_A_REASONING_ON_DEMAND_MAX_TOKENS) {
            value = COHERE_COMMAND_A_REASONING_ON_DEMAND_MAX_TOKENS;
        }
        root.put("max_tokens", value);
    }

    private static boolean isMetaLlamaOnDemandCappedModel(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String value = model.trim().toLowerCase(Locale.ROOT);
        return value.contains("llama-4-")
                || value.contains("llama-3.3-70b-instruct");
    }

    private static int positiveInt(JsonNode node, int fallback) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return fallback;
        }
        try {
            if (node.isNumber()) {
                return node.intValue();
            }
            if (node.isTextual()) {
                return Integer.parseInt(node.asText().trim());
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static void normalizeChatToolSchema(ObjectNode root) {
        JsonNode tools = root.get("tools");
        if (tools != null && tools.isArray()) {
            ArrayNode normalizedTools = MAPPER.createArrayNode();
            boolean changed = false;
            for (JsonNode tool : tools) {
                if (tool != null && tool.isObject() && tool.get("function") == null) {
                    ObjectNode source = (ObjectNode) tool;
                    String type = textOrNull(source, "type");
                    if (type == null || type.isBlank() || "function".equalsIgnoreCase(type)) {
                        ObjectNode normalized = MAPPER.createObjectNode();
                        normalized.put("type", "function");
                        ObjectNode fn = MAPPER.createObjectNode();
                        copyIfPresent(source, fn, "name");
                        copyIfPresent(source, fn, "description");
                        copySanitizedParametersIfPresent(source, fn);
                        copyIfPresent(source, fn, "strict");
                        normalized.set("function", fn);
                        normalizedTools.add(normalized);
                        changed = true;
                        continue;
                    }
                }
                if (tool instanceof ObjectNode source) {
                    ObjectNode normalized = source.deepCopy();
                    ObjectNode fn = normalized.get("function") instanceof ObjectNode functionObject
                            ? functionObject
                            : normalized;
                    JsonNode before = fn.get("parameters");
                    JsonNode after = sanitizeOciToolParameters(before);
                    if (after != null && !after.equals(before)) {
                        fn.set("parameters", after);
                        changed = true;
                    }
                    normalizedTools.add(normalized);
                } else {
                    normalizedTools.add(tool);
                }
            }
            if (changed) {
                root.set("tools", normalizedTools);
            }
        }
        JsonNode toolChoice = root.get("tool_choice");
        if (toolChoice != null && toolChoice.isObject()) {
            ObjectNode choice = (ObjectNode) toolChoice;
            if (choice.get("function") == null) {
                String type = textOrNull(choice, "type");
                String name = textOrNull(choice, "name");
                if ((type == null || type.isBlank() || "function".equalsIgnoreCase(type))
                        && name != null && !name.isBlank()) {
                    ObjectNode normalized = MAPPER.createObjectNode();
                    normalized.put("type", "function");
                    ObjectNode fn = MAPPER.createObjectNode();
                    fn.put("name", name);
                    normalized.set("function", fn);
                    root.set("tool_choice", normalized);
                }
            }
        }
    }

    private static void copyIfPresent(ObjectNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull() && !value.isMissingNode()) {
            target.set(field, value);
        }
    }

    private static void copySanitizedParametersIfPresent(ObjectNode source, ObjectNode target) {
        JsonNode value = source.get("parameters");
        if (value == null || value.isNull() || value.isMissingNode()) {
            return;
        }
        JsonNode sanitized = sanitizeOciToolParameters(value);
        target.set("parameters", sanitized == null ? value : sanitized);
    }

    private static JsonNode sanitizeOciToolParameters(JsonNode schema) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            return null;
        }
        JsonNode sanitized = sanitizeOciToolSchema(schema);
        if (!(sanitized instanceof ObjectNode object)) {
            ObjectNode fallback = MAPPER.createObjectNode();
            fallback.put("type", "object");
            fallback.set("properties", MAPPER.createObjectNode());
            return fallback;
        }
        if (!object.hasNonNull("type")) {
            if (object.has("properties")) {
                object.put("type", "object");
            } else if (object.has("items")) {
                object.put("type", "array");
            } else {
                object.put("type", "object");
                object.set("properties", MAPPER.createObjectNode());
            }
        }
        return object;
    }

    private static JsonNode sanitizeOciToolSchema(JsonNode schema) {
        if (!(schema instanceof ObjectNode object)) {
            return null;
        }
        ObjectNode out = MAPPER.createObjectNode();

        JsonNode type = object.get("type");
        if (type != null && !type.isNull() && !type.isMissingNode()) {
            copySanitizedSchemaType(out, type);
        }

        for (String field : OCI_TOOL_SCHEMA_ALLOWED_FIELDS) {
            if ("type".equals(field) || "enum".equals(field) || "items".equals(field)
                    || "properties".equals(field) || "required".equals(field)
                    || "propertyOrdering".equals(field)) {
                continue;
            }
            JsonNode value = object.get(field);
            if (value != null && !value.isNull() && !value.isMissingNode()) {
                out.set(field, value.deepCopy());
            }
        }

        JsonNode enumNode = object.get("enum");
        if (enumNode != null && enumNode.isArray()) {
            ArrayNode values = MAPPER.createArrayNode();
            for (JsonNode item : enumNode) {
                if (item == null || item.isNull()) {
                    out.put("nullable", true);
                } else if (item.isTextual() || item.isNumber() || item.isBoolean()) {
                    values.add(item.asText());
                }
            }
            if (!values.isEmpty()) {
                out.set("enum", values);
            }
        }

        JsonNode properties = object.get("properties");
        if (properties != null && properties.isObject()) {
            ObjectNode outProperties = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode child = sanitizeOciToolSchema(entry.getValue());
                if (child instanceof ObjectNode childObject) {
                    if (!childObject.hasNonNull("type")) {
                        if (childObject.has("properties")) {
                            childObject.put("type", "object");
                        } else if (childObject.has("items")) {
                            childObject.put("type", "array");
                        }
                    }
                    outProperties.set(entry.getKey(), childObject);
                } else {
                    ObjectNode fallback = MAPPER.createObjectNode();
                    fallback.put("type", "string");
                    outProperties.set(entry.getKey(), fallback);
                }
            }
            if (!outProperties.isEmpty()) {
                out.set("properties", outProperties);
            }
        }

        JsonNode items = object.get("items");
        JsonNode sanitizedItems = sanitizeOciToolSchema(items);
        if (sanitizedItems instanceof ObjectNode itemObject) {
            if (!itemObject.hasNonNull("type")) {
                itemObject.put("type", "string");
            }
            out.set("items", itemObject);
        }

        copyStringArraySchemaField(object, out, "required");
        copyStringArraySchemaField(object, out, "propertyOrdering");

        if (!out.hasNonNull("type")) {
            JsonNode union = firstSupportedUnionSchema(object);
            if (union instanceof ObjectNode unionObject) {
                unionObject.fields().forEachRemaining(entry -> {
                    if (!out.has(entry.getKey()) && OCI_TOOL_SCHEMA_ALLOWED_FIELDS.contains(entry.getKey())) {
                        out.set(entry.getKey(), entry.getValue().deepCopy());
                    }
                });
            }
        }
        return out;
    }

    private static void copySanitizedSchemaType(ObjectNode out, JsonNode type) {
        if (type.isTextual()) {
            String value = sanitizeSchemaType(type.asText());
            if (value != null) {
                out.put("type", value);
            } else if ("null".equalsIgnoreCase(type.asText())) {
                out.put("nullable", true);
            }
            return;
        }
        if (type.isArray()) {
            boolean nullable = false;
            String selected = null;
            for (JsonNode item : type) {
                if (!item.isTextual()) {
                    continue;
                }
                String raw = item.asText();
                if ("null".equalsIgnoreCase(raw)) {
                    nullable = true;
                    continue;
                }
                String candidate = sanitizeSchemaType(raw);
                if (selected == null && candidate != null) {
                    selected = candidate;
                }
            }
            if (selected != null) {
                out.put("type", selected);
            }
            if (nullable) {
                out.put("nullable", true);
            }
        }
    }

    private static String sanitizeSchemaType(String type) {
        if (type == null) {
            return null;
        }
        String value = type.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "object", "array", "string", "number", "integer", "boolean" -> value;
            default -> null;
        };
    }

    private static void copyStringArraySchemaField(ObjectNode source, ObjectNode target, String field) {
        JsonNode node = source.get(field);
        if (node == null || !node.isArray()) {
            return;
        }
        ArrayNode values = MAPPER.createArrayNode();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        if (!values.isEmpty()) {
            target.set(field, values);
        }
    }

    private static JsonNode firstSupportedUnionSchema(ObjectNode object) {
        for (String field : List.of("anyOf", "oneOf", "allOf")) {
            JsonNode union = object.get(field);
            if (union == null || !union.isArray()) {
                continue;
            }
            for (JsonNode candidate : union) {
                JsonNode sanitized = sanitizeOciToolSchema(candidate);
                if (sanitized instanceof ObjectNode schema && (schema.hasNonNull("type")
                        || schema.has("properties") || schema.has("items"))) {
                    return schema;
                }
            }
        }
        return null;
    }

    static byte[] transformResponsesToChatCompletionsJson(byte[] input, int defaultMaxTokens) {
        try {
            JsonNode root = MAPPER.readTree(input);
            if (root == null || !root.isObject()) {
                return input;
            }
            ObjectNode in = (ObjectNode) root;
            ObjectNode out = MAPPER.createObjectNode();
            copyIfPresent(in, out, "model");

            ArrayNode messages = MAPPER.createArrayNode();
            String instructions = textOrNull(in, "instructions");
            if (instructions != null && !instructions.isBlank()) {
                ObjectNode sys = MAPPER.createObjectNode();
                sys.put("role", "system");
                sys.put("content", instructions);
                messages.add(sys);
            }
            appendResponsesInputAsChatMessages(messages, in.get("input"));
            messages = normalizeChatToolMessages(messages, isGeminiChatModel(textOrNull(in, "model")));
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
                    ObjectNode source = (ObjectNode) tool;
                    String type = textOrNull(source, "type");
                    if (type != null && !type.isBlank() && !"function".equalsIgnoreCase(type)) {
                        continue;
                    }
                    ObjectNode chatTool = MAPPER.createObjectNode();
                    chatTool.put("type", "function");
                    ObjectNode fn = MAPPER.createObjectNode();
                    copyIfPresent(source, fn, "name");
                    copyIfPresent(source, fn, "description");
                    copySanitizedParametersIfPresent(source, fn);
                    copyIfPresent(source, fn, "strict");
                    chatTool.set("function", fn);
                    chatTools.add(chatTool);
                }
                if (!chatTools.isEmpty()) {
                    out.set("tools", chatTools);
                }
            }

            JsonNode toolChoice = in.get("tool_choice");
            if (toolChoice != null && !toolChoice.isNull() && !toolChoice.isMissingNode()) {
                out.set("tool_choice", responsesToolChoiceToChatToolChoice(toolChoice));
            }

            JsonNode maxOutput = in.get("max_output_tokens");
            if (maxOutput != null && !maxOutput.isNull() && !maxOutput.isMissingNode()) {
                out.set("max_tokens", maxOutput);
            } else {
                out.put("max_tokens", OracleAiGatewayConfigService.normalizeDefaultMaxTokens(defaultMaxTokens));
            }
            copyIfPresent(in, out, "temperature");
            copyIfPresent(in, out, "top_p");
            copyIfPresent(in, out, "stream");
            copyIfPresent(in, out, "service_tier");
            copyResponsesStructuredOutputFormat(in, out);
            if (in.get("stream") != null && in.get("stream").asBoolean(false)) {
                ObjectNode streamOptions = MAPPER.createObjectNode();
                streamOptions.put("include_usage", true);
                out.set("stream_options", streamOptions);
            }
            normalizeChatCompletionTokenBudget(out, defaultMaxTokens);
            return MAPPER.writeValueAsBytes(out);
        } catch (Exception e) {
            return input;
        }
    }

    private static void copyResponsesStructuredOutputFormat(ObjectNode in, ObjectNode out) {
        if (in == null || out == null || out.has("response_format")) {
            return;
        }
        JsonNode responseFormat = in.get("response_format");
        if (responseFormat != null && !responseFormat.isNull() && !responseFormat.isMissingNode()) {
            out.set("response_format", responseFormat.deepCopy());
            return;
        }
        JsonNode text = in.get("text");
        if (!(text instanceof ObjectNode textObject)) {
            return;
        }
        JsonNode formatNode = textObject.get("format");
        if (!(formatNode instanceof ObjectNode format)) {
            return;
        }
        String type = textOrNull(format, "type");
        if (type == null || type.isBlank()) {
            return;
        }
        if ("json_schema".equalsIgnoreCase(type)) {
            ObjectNode response = MAPPER.createObjectNode();
            response.put("type", "json_schema");
            ObjectNode jsonSchema = MAPPER.createObjectNode();
            JsonNode existing = format.get("json_schema");
            if (existing instanceof ObjectNode existingObject) {
                jsonSchema.setAll(existingObject);
            }
            copyIfAbsent(format, jsonSchema, "name");
            copyIfAbsent(format, jsonSchema, "description");
            copyIfAbsent(format, jsonSchema, "schema");
            copyIfAbsent(format, jsonSchema, "strict");
            response.set("json_schema", jsonSchema);
            out.set("response_format", response);
            return;
        }
        if ("json_object".equalsIgnoreCase(type)) {
            ObjectNode response = MAPPER.createObjectNode();
            response.put("type", "json_object");
            out.set("response_format", response);
        }
    }

    private static void copyIfAbsent(ObjectNode source, ObjectNode target, String field) {
        if (source == null || target == null || target.has(field)) {
            return;
        }
        JsonNode value = source.get(field);
        if (value != null && !value.isNull() && !value.isMissingNode()) {
            target.set(field, value.deepCopy());
        }
    }

    private static void appendResponsesInputAsChatMessages(ArrayNode messages, JsonNode input) {
        if (input == null || input.isNull() || input.isMissingNode()) {
            return;
        }
        if (input.isTextual()) {
            addChatMessage(messages, "user", input.asText());
            return;
        }
        if (!input.isArray()) {
            addChatMessage(messages, "user", input.toString());
            return;
        }
        for (JsonNode item : input) {
            if (item == null || item.isNull()) {
                continue;
            }
            if (item.isTextual()) {
                addChatMessage(messages, "user", item.asText());
                continue;
            }
            if (!item.isObject()) {
                addChatMessage(messages, "user", item.toString());
                continue;
            }
            ObjectNode o = (ObjectNode) item;
            String type = textOrNull(o, "type");
            if (isResponsesFunctionCallType(type)) {
                ObjectNode assistant = MAPPER.createObjectNode();
                assistant.put("role", "assistant");
                ArrayNode toolCalls = MAPPER.createArrayNode();
                ObjectNode call = MAPPER.createObjectNode();
                call.put("id", firstNonBlank(textOrNull(o, "call_id"), textOrNull(o, "id"), "call_ociworker"));
                call.put("type", "function");
                ObjectNode fn = MAPPER.createObjectNode();
                fn.put("name", firstNonBlank(textOrNull(o, "name"), "tool"));
                fn.put("arguments", firstNonBlank(payloadStringOrNull(o, "arguments"), payloadStringOrNull(o, "input"), "{}"));
                call.set("function", fn);
                toolCalls.add(call);
                assistant.set("tool_calls", toolCalls);
                messages.add(assistant);
                continue;
            }
            if (isResponsesFunctionCallOutputType(type)) {
                ObjectNode tool = MAPPER.createObjectNode();
                tool.put("role", "tool");
                tool.put("tool_call_id", firstNonBlank(textOrNull(o, "call_id"), textOrNull(o, "id"), "call_ociworker"));
                tool.put("content", firstNonBlank(payloadStringOrNull(o, "output"), ""));
                messages.add(tool);
                continue;
            }
            String role = firstNonBlank(textOrNull(o, "role"), "message".equalsIgnoreCase(type) ? "user" : "user");
            addResponsesChatMessage(messages, normalizeChatRole(role), o.get("content"), o);
        }
    }

    private static void addResponsesChatMessage(ArrayNode messages, String role, JsonNode content, ObjectNode fallback) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", normalizeChatRole(role));
        JsonNode chatContent = responsesContentToChatContent(content, fallback);
        if (chatContent == null || chatContent.isNull() || chatContent.isMissingNode()) {
            msg.put("content", "");
        } else if (chatContent.isTextual()) {
            msg.put("content", chatContent.asText());
        } else {
            msg.set("content", chatContent);
        }
        messages.add(msg);
    }

    private static JsonNode responsesContentToChatContent(JsonNode content, ObjectNode fallback) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return MAPPER.getNodeFactory().textNode(firstNonBlank(textOrNull(fallback, "text"), ""));
        }
        if (content.isTextual()) {
            return content.deepCopy();
        }
        if (content.isObject()) {
            ObjectNode object = (ObjectNode) content;
            String type = textOrNull(object, "type");
            if ("input_image".equalsIgnoreCase(type) || "image_url".equalsIgnoreCase(type)) {
                ObjectNode image = responsesImagePartToChatImage(object);
                if (image != null) {
                    ArrayNode rich = MAPPER.createArrayNode();
                    rich.add(image);
                    return rich;
                }
            }
            String text = firstNonBlank(textOrNull(object, "text"), content.toString());
            return MAPPER.getNodeFactory().textNode(text);
        }
        if (!content.isArray()) {
            return MAPPER.getNodeFactory().textNode(content.toString());
        }

        StringBuilder text = new StringBuilder();
        ArrayNode rich = MAPPER.createArrayNode();
        boolean hasRichPart = false;
        for (JsonNode part : content) {
            if (part == null || part.isNull()) {
                continue;
            }
            if (part.isTextual()) {
                appendResponseTextPart(text, part.asText());
                continue;
            }
            if (!(part instanceof ObjectNode partObject)) {
                appendResponseTextPart(text, part.toString());
                continue;
            }
            String type = textOrNull(partObject, "type");
            if ("text".equalsIgnoreCase(type) || "input_text".equalsIgnoreCase(type)) {
                appendResponseTextPart(text, firstNonBlank(textOrNull(partObject, "text"), ""));
                continue;
            }
            if ("input_image".equalsIgnoreCase(type) || "image_url".equalsIgnoreCase(type)) {
                ObjectNode image = responsesImagePartToChatImage(partObject);
                if (image != null) {
                    flushChatTextPart(rich, text);
                    rich.add(image);
                    hasRichPart = true;
                    continue;
                }
            }
            appendResponseTextPart(text, part.toString());
        }
        if (!hasRichPart) {
            return MAPPER.getNodeFactory().textNode(text.toString());
        }
        flushChatTextPart(rich, text);
        return rich;
    }

    private static ObjectNode responsesImagePartToChatImage(ObjectNode part) {
        if (part == null) {
            return null;
        }
        String url = null;
        JsonNode imageUrl = part.get("image_url");
        if (imageUrl != null && !imageUrl.isNull() && !imageUrl.isMissingNode()) {
            if (imageUrl.isTextual()) {
                url = imageUrl.asText();
            } else if (imageUrl instanceof ObjectNode imageUrlObject) {
                url = textOrNull(imageUrlObject, "url");
            }
        }
        url = firstNonBlank(url, textOrNull(part, "url"), textOrNull(part, "file_data"), textOrNull(part, "data"));
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmedUrl = url.trim();
        if (!isDataUrl(trimmedUrl) && !trimmedUrl.regionMatches(true, 0, "http://", 0, 7)
                && !trimmedUrl.regionMatches(true, 0, "https://", 0, 8)) {
            String mediaType = firstNonBlank(textOrNull(part, "media_type"), textOrNull(part, "mime_type"), "image/png");
            trimmedUrl = "data:" + normalizeMediaType(mediaType) + ";base64," + trimmedUrl;
        }
        ObjectNode imageUrlObject = MAPPER.createObjectNode();
        imageUrlObject.put("url", trimmedUrl);
        String detail = firstNonBlank(textOrNull(part, "detail"),
                imageUrl instanceof ObjectNode object ? textOrNull(object, "detail") : null);
        if (detail != null && !detail.isBlank()) {
            imageUrlObject.put("detail", detail);
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("type", "image_url");
        out.set("image_url", imageUrlObject);
        return out;
    }

    private static void appendResponseTextPart(StringBuilder text, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (text.length() > 0) {
            text.append("\n\n");
        }
        text.append(value);
    }

    private static void flushChatTextPart(ArrayNode rich, StringBuilder text) {
        if (rich == null || text == null || text.length() == 0) {
            return;
        }
        ObjectNode textPart = MAPPER.createObjectNode();
        textPart.put("type", "text");
        textPart.put("text", text.toString());
        rich.add(textPart);
        text.setLength(0);
    }

    private static boolean isResponsesFunctionCallType(String type) {
        return "function_call".equalsIgnoreCase(type) || "custom_tool_call".equalsIgnoreCase(type);
    }

    private static boolean isResponsesFunctionCallOutputType(String type) {
        return "function_call_output".equalsIgnoreCase(type) || "custom_tool_call_output".equalsIgnoreCase(type);
    }

    static ArrayNode normalizeChatToolMessages(ArrayNode messages) {
        return normalizeChatToolMessages(messages, false);
    }

    static ArrayNode normalizeChatToolMessages(ArrayNode messages, boolean splitParallelToolCalls) {
        ArrayNode normalized = MAPPER.createArrayNode();
        if (messages == null || messages.isEmpty()) {
            return normalized;
        }

        Map<String, ObjectNode> toolRepliesById = new LinkedHashMap<>();
        for (JsonNode message : messages) {
            if (!(message instanceof ObjectNode object)) {
                continue;
            }
            if (!"tool".equalsIgnoreCase(normalizeChatRole(textOrNull(object, "role")))) {
                continue;
            }
            String toolCallId = textOrNull(object, "tool_call_id");
            if (toolCallId != null && !toolCallId.isBlank()) {
                toolRepliesById.put(toolCallId, object);
            }
        }

        Set<String> emittedToolReplyIds = new HashSet<>();
        for (JsonNode message : messages) {
            if (!(message instanceof ObjectNode object)) {
                ObjectNode scalarMessage = normalizeNonObjectChatMessage(message);
                if (scalarMessage != null) {
                    normalized.add(scalarMessage);
                }
                continue;
            }
            String role = normalizeChatRole(textOrNull(object, "role"));
            if ("tool".equalsIgnoreCase(role)) {
                String toolCallId = textOrNull(object, "tool_call_id");
                if (toolCallId == null || toolCallId.isBlank()) {
                    normalized.add(normalizeChatMessageForOci(object));
                }
                continue;
            }
            JsonNode toolCalls = object.get("tool_calls");
            if (!"assistant".equalsIgnoreCase(role) || toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
                normalized.add(normalizeChatMessageForOci(object));
                continue;
            }

            ArrayNode answeredToolCalls = MAPPER.createArrayNode();
            List<ObjectNode> answeredReplies = new ArrayList<>();
            for (JsonNode toolCall : toolCalls) {
                if (!(toolCall instanceof ObjectNode toolCallObject)) {
                    continue;
                }
                String toolCallId = textOrNull(toolCallObject, "id");
                if (toolCallId == null || toolCallId.isBlank() || emittedToolReplyIds.contains(toolCallId)) {
                    continue;
                }
                ObjectNode reply = toolRepliesById.get(toolCallId);
                if (reply == null) {
                    continue;
                }
                answeredToolCalls.add(toolCallObject.deepCopy());
                answeredReplies.add(reply);
                emittedToolReplyIds.add(toolCallId);
            }

            if (answeredToolCalls.isEmpty()) {
                if (hasUsableChatContent(object.get("content"))) {
                    ObjectNode plainAssistant = object.deepCopy();
                    plainAssistant.remove("tool_calls");
                    normalized.add(normalizeChatMessageForOci(plainAssistant));
                }
                continue;
            }

            ObjectNode assistant = object.deepCopy();
            assistant.set("tool_calls", answeredToolCalls);
            if (!splitParallelToolCalls || answeredToolCalls.size() <= 1) {
                normalized.add(normalizeChatMessageForOci(assistant));
                for (ObjectNode reply : answeredReplies) {
                    normalized.add(normalizeChatMessageForOci(reply));
                }
                continue;
            }

            for (int i = 0; i < answeredToolCalls.size(); i++) {
                ObjectNode singleAssistant = object.deepCopy();
                ArrayNode singleCall = MAPPER.createArrayNode();
                singleCall.add(answeredToolCalls.get(i).deepCopy());
                singleAssistant.set("tool_calls", singleCall);
                if (i > 0 && singleAssistant.has("content")) {
                    singleAssistant.put("content", "");
                }
                normalized.add(normalizeChatMessageForOci(singleAssistant));
                normalized.add(normalizeChatMessageForOci(answeredReplies.get(i)));
            }
        }
        return normalized;
    }

    private static ObjectNode normalizeNonObjectChatMessage(JsonNode message) {
        if (message == null || message.isNull() || message.isMissingNode()) {
            return null;
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("role", "user");
        if (message.isTextual() || message.isNumber() || message.isBoolean()) {
            out.put("content", message.asText());
        } else {
            out.put("content", message.toString());
        }
        return out;
    }

    private static ObjectNode normalizeChatMessageForOci(ObjectNode source) {
        ObjectNode out = source == null ? MAPPER.createObjectNode() : source.deepCopy();
        out.put("role", normalizeChatRole(textOrNull(out, "role")));
        JsonNode content = out.get("content");
        if (content == null || content.isNull() || content.isMissingNode()) {
            out.put("content", "");
            return out;
        }
        if (!content.isArray()) {
            return out;
        }
        ArrayNode normalized = MAPPER.createArrayNode();
        for (JsonNode part : content) {
            if (part == null || part.isNull()) {
                continue;
            }
            if (part instanceof ObjectNode objectPart) {
                ObjectNode copy = objectPart.deepCopy();
                String type = firstNonBlank(textOrNull(copy, "type"), "").toLowerCase(Locale.ROOT);
                if ("text".equals(type) || "input_text".equals(type) || (type.isBlank() && copy.get("text") != null)) {
                    String text = chatTextPartText(copy);
                    copy.put("text", text == null ? "" : text);
                    if (type.isBlank()) {
                        copy.put("type", "text");
                    }
                }
                normalized.add(copy);
            } else {
                normalized.add(part.deepCopy());
            }
        }
        if (normalized.isEmpty()) {
            out.put("content", "");
        } else {
            out.set("content", normalized);
        }
        return out;
    }

    private static boolean hasUsableChatContent(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return false;
        }
        if (content.isTextual()) {
            return !content.asText().isBlank();
        }
        if (content.isArray() || content.isObject()) {
            return !content.isEmpty();
        }
        return true;
    }

    private static boolean hasUsableChatMessages(ArrayNode messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (JsonNode message : messages) {
            if (!(message instanceof ObjectNode object)) {
                continue;
            }
            JsonNode toolCalls = object.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
                return true;
            }
            String text = chatMessageContentText(object.get("content"));
            if (text != null && !text.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String chatPromptFallback(ObjectNode root) {
        if (root == null) {
            return null;
        }
        JsonNode value = firstExisting(root, "prompt", "input", "query");
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isTextual() || value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return value.toString();
    }

    private static String payloadStringOrNull(ObjectNode o, String field) {
        if (o == null) {
            return null;
        }
        JsonNode n = o.get(field);
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        return n.isTextual() ? n.asText() : n.toString();
    }

    private static void addChatMessage(ArrayNode messages, String role, String content) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", normalizeChatRole(role));
        msg.put("content", content == null ? "" : content);
        messages.add(msg);
    }

    private static String normalizeChatRole(String role) {
        if (role == null || role.isBlank()) {
            return "user";
        }
        String value = role.trim().toLowerCase(java.util.Locale.ROOT);
        if ("assistant".equals(value) || "system".equals(value) || "tool".equals(value) || "developer".equals(value)) {
            return value;
        }
        if ("model".equals(value) || "ai".equals(value) || "bot".equals(value)) {
            return "assistant";
        }
        if ("human".equals(value)) {
            return "user";
        }
        return "user";
    }

    private static String normalizeResponsesMessageRole(String role) {
        String value = normalizeChatRole(role);
        if ("assistant".equals(value) || "system".equals(value) || "user".equals(value)) {
            return value;
        }
        if ("developer".equals(value)) {
            return "system";
        }
        return "user";
    }

    private static String responsesContentText(JsonNode content, ObjectNode fallback) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return firstNonBlank(textOrNull(fallback, "text"), "");
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isObject()) {
            ObjectNode o = (ObjectNode) content;
            return firstNonBlank(textOrNull(o, "text"), content.toString());
        }
        if (!content.isArray()) {
            return content.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content) {
            if (part == null || !part.isObject()) {
                continue;
            }
            ObjectNode po = (ObjectNode) part;
            String text = textOrNull(po, "text");
            if (text == null || text.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(text);
        }
        return sb.toString();
    }

    private static JsonNode responsesToolChoiceToChatToolChoice(JsonNode toolChoice) {
        if (toolChoice == null || !toolChoice.isObject()) {
            return toolChoice;
        }
        ObjectNode choice = (ObjectNode) toolChoice;
        String type = textOrNull(choice, "type");
        if (!"function".equalsIgnoreCase(firstNonBlank(type, ""))) {
            return toolChoice;
        }
        String name = firstNonBlank(textOrNull(choice, "name"),
                choice.get("function") != null && choice.get("function").isObject()
                        ? textOrNull((ObjectNode) choice.get("function"), "name")
                        : null);
        if (name == null || name.isBlank()) {
            return toolChoice;
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("type", "function");
        ObjectNode fn = MAPPER.createObjectNode();
        fn.put("name", name);
        out.set("function", fn);
        return out;
    }

    static byte[] transformChatCompletionsToResponsesJson(byte[] input, int defaultMaxTokens) {
        try {
            JsonNode root = MAPPER.readTree(input);
            if (root == null || !root.isObject()) {
                return input;
            }
            ObjectNode in = (ObjectNode) root;
            String model = textOrNull(in, "model");
            ObjectNode out = MAPPER.createObjectNode();
            if (model != null && !model.isBlank()) {
                out.put("model", model);
            }
            JsonNode messages = in.get("messages");
            if (messages != null && messages.isArray()) {
                // Responses API 的 input 期望为 message items，其中 content 多为数组块（例如 input_text）。
                // 将 chat.completions 的 {role, content:"..."} 转换为 {role, content:[{type:"input_text", text:"..."}]}。
                ArrayNode inputArr = MAPPER.createArrayNode();
                for (JsonNode m : messages) {
                    if (m == null || !m.isObject()) {
                        continue;
                    }
                    ObjectNode mo = (ObjectNode) m;
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("role", normalizeResponsesMessageRole(textOrNull(mo, "role")));

                    JsonNode content = mo.get("content");
                    if (content == null || content.isNull()) {
                        continue;
                    }
                    item.set("content", chatContentToResponsesParts(content));
                    inputArr.add(item);
                }
                out.set("input", inputArr);
            } else {
                // 兼容极少数不规范请求：没有 messages 时，尽可能把 prompt 当 input
                JsonNode p = in.get("prompt");
                if (p != null && p.isTextual()) {
                    out.put("input", p.asText());
                }
            }
            JsonNode tools = in.get("tools");
            if (tools != null && tools.isArray()) {
                ArrayNode responseTools = chatToolsToResponsesTools((ArrayNode) tools);
                if (!responseTools.isEmpty()) {
                    out.set("tools", responseTools);
                    JsonNode toolChoice = chatToolChoiceToResponsesToolChoice(in.get("tool_choice"));
                    if (toolChoice != null) {
                        out.set("tool_choice", toolChoice);
                    }
                    copyIfPresent(in, out, "parallel_tool_calls");
                }
            }
            JsonNode mt = in.get("max_tokens");
            if (mt != null && !mt.isNull() && !mt.isMissingNode()) {
                if (mt.isNumber()) {
                    out.put("max_output_tokens", mt.intValue());
                } else {
                    out.put("max_output_tokens", mt.asInt(OracleAiGatewayConfigService.normalizeDefaultMaxTokens(defaultMaxTokens)));
                }
            } else {
                out.put("max_output_tokens", OracleAiGatewayConfigService.normalizeDefaultMaxTokens(defaultMaxTokens));
            }
            JsonNode temp = in.get("temperature");
            if (temp != null && !temp.isNull() && !temp.isMissingNode()) {
                out.set("temperature", temp);
            }
            JsonNode topP = in.get("top_p");
            if (topP != null && !topP.isNull() && !topP.isMissingNode()) {
                out.set("top_p", topP);
            }
            copyChatStructuredOutputFormat(in, out);
            // responses API 的流式事件与 chat_completions 不同，默认关闭
            out.put("stream", false);
            return MAPPER.writeValueAsBytes(out);
        } catch (Exception e) {
            return input;
        }
    }

    private static ArrayNode chatContentToResponsesParts(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return toInputTextParts("");
        }
        if (content.isTextual()) {
            return toInputTextParts(content.asText());
        }
        ArrayNode out = MAPPER.createArrayNode();
        if (content.isArray()) {
            for (JsonNode part : content) {
                appendChatContentPartAsResponsesPart(out, part);
            }
        } else {
            appendChatContentPartAsResponsesPart(out, content);
        }
        if (out.isEmpty()) {
            out.add(toInputTextPartNode(""));
        }
        return out;
    }

    private static void appendChatContentPartAsResponsesPart(ArrayNode out, JsonNode part) {
        if (out == null || part == null || part.isNull()) {
            return;
        }
        if (part.isTextual()) {
            out.add(toInputTextPartNode(part.asText()));
            return;
        }
        if (!(part instanceof ObjectNode object)) {
            out.add(toInputTextPartNode(part.toString()));
            return;
        }
        ObjectNode image = chatImagePartToResponsesImage(object);
        if (image != null) {
            out.add(image);
            return;
        }
        String type = textOrNull(object, "type");
        if ("text".equalsIgnoreCase(type) || "input_text".equalsIgnoreCase(type)
                || (type == null && object.get("text") != null)) {
            out.add(toInputTextPartNode(firstNonBlank(chatTextPartText(object), "")));
            return;
        }
        out.add(toInputTextPartNode(part.toString()));
    }

    private static ObjectNode chatImagePartToResponsesImage(ObjectNode part) {
        if (part == null) {
            return null;
        }
        String type = firstNonBlank(textOrNull(part, "type"), "").toLowerCase(Locale.ROOT);
        if (!"image_url".equals(type) && !"input_image".equals(type) && part.get("image_url") == null) {
            return null;
        }
        String url = null;
        JsonNode imageUrl = part.get("image_url");
        if (imageUrl != null && !imageUrl.isNull() && !imageUrl.isMissingNode()) {
            if (imageUrl.isTextual()) {
                url = imageUrl.asText();
            } else if (imageUrl instanceof ObjectNode object) {
                url = textOrNull(object, "url");
            }
        }
        url = firstNonBlank(url, textOrNull(part, "url"), textOrNull(part, "file_data"), textOrNull(part, "data"));
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmedUrl = url.trim();
        if (!isDataUrl(trimmedUrl) && !trimmedUrl.regionMatches(true, 0, "http://", 0, 7)
                && !trimmedUrl.regionMatches(true, 0, "https://", 0, 8)) {
            String mediaType = firstNonBlank(textOrNull(part, "media_type"), textOrNull(part, "mime_type"), "image/png");
            trimmedUrl = "data:" + normalizeMediaType(mediaType) + ";base64," + trimmedUrl;
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("type", "input_image");
        out.put("image_url", trimmedUrl);
        String detail = firstNonBlank(textOrNull(part, "detail"),
                imageUrl instanceof ObjectNode object ? textOrNull(object, "detail") : null);
        if (detail != null && !detail.isBlank()) {
            out.put("detail", detail);
        }
        return out;
    }

    private static void copyChatStructuredOutputFormat(ObjectNode in, ObjectNode out) {
        if (in == null || out == null || out.has("text")) {
            return;
        }
        JsonNode responseFormat = in.get("response_format");
        if (!(responseFormat instanceof ObjectNode formatSource)) {
            return;
        }
        String type = textOrNull(formatSource, "type");
        if (type == null || type.isBlank() || "text".equalsIgnoreCase(type)) {
            return;
        }
        ObjectNode format = MAPPER.createObjectNode();
        if ("json_schema".equalsIgnoreCase(type)) {
            format.put("type", "json_schema");
            JsonNode jsonSchema = formatSource.get("json_schema");
            if (jsonSchema instanceof ObjectNode schemaObject) {
                copyIfPresent(schemaObject, format, "name");
                copyIfPresent(schemaObject, format, "description");
                copyIfPresent(schemaObject, format, "schema");
                copyIfPresent(schemaObject, format, "strict");
            }
            copyIfAbsent(formatSource, format, "name");
            copyIfAbsent(formatSource, format, "description");
            copyIfAbsent(formatSource, format, "schema");
            copyIfAbsent(formatSource, format, "strict");
        } else if ("json_object".equalsIgnoreCase(type)) {
            format.put("type", "json_object");
        } else {
            return;
        }
        ObjectNode text = MAPPER.createObjectNode();
        text.set("format", format);
        out.set("text", text);
    }

    private static ArrayNode chatToolsToResponsesTools(ArrayNode tools) {
        ArrayNode out = MAPPER.createArrayNode();
        if (tools == null || tools.isEmpty()) {
            return out;
        }
        for (JsonNode tool : tools) {
            if (!(tool instanceof ObjectNode source)) {
                continue;
            }
            String type = textOrNull(source, "type");
            JsonNode functionNode = source.get("function");
            ObjectNode fn = functionNode != null && functionNode.isObject()
                    ? (ObjectNode) functionNode
                    : source;
            if (type != null && !type.isBlank() && !"function".equalsIgnoreCase(type)
                    && functionNode == null) {
                continue;
            }
            String name = textOrNull(fn, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            ObjectNode responseTool = MAPPER.createObjectNode();
            responseTool.put("type", "function");
            responseTool.put("name", name);
            copyIfPresent(fn, responseTool, "description");
            copySanitizedParametersIfPresent(fn, responseTool);
            copyIfPresent(fn, responseTool, "strict");
            out.add(responseTool);
        }
        return out;
    }

    private static JsonNode chatToolChoiceToResponsesToolChoice(JsonNode toolChoice) {
        if (toolChoice == null || toolChoice.isNull() || toolChoice.isMissingNode()) {
            return null;
        }
        if (toolChoice.isTextual()) {
            String value = toolChoice.asText();
            if (value == null || value.isBlank()) {
                return null;
            }
            return MAPPER.getNodeFactory().textNode(value);
        }
        if (!toolChoice.isObject()) {
            return toolChoice;
        }
        ObjectNode choice = (ObjectNode) toolChoice;
        String type = textOrNull(choice, "type");
        if (!"function".equalsIgnoreCase(firstNonBlank(type, ""))) {
            return toolChoice;
        }
        String name = firstNonBlank(textOrNull(choice, "name"),
                choice.get("function") != null && choice.get("function").isObject()
                        ? textOrNull((ObjectNode) choice.get("function"), "name")
                        : null);
        if (name == null || name.isBlank()) {
            return null;
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("type", "function");
        out.put("name", name);
        return out;
    }

    private void longCopyStream(
            HttpClient client, HttpRequest httpRequest, HttpServletResponse response, HttpServletRequest request)
            throws IOException {
        try {
            HttpResponse<InputStream> resp = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            int code = resp.statusCode();
            if (request != null) {
                request.setAttribute(OpenAiApiConstants.ATTR_UPSTREAM_STATUS, code);
            }
            if (code >= 400 && Boolean.TRUE.equals(request == null ? null : request.getAttribute(OpenAiApiConstants.ATTR_LB_REQUEST))) {
                byte[] bytes;
                try (InputStream in = resp.body()) {
                    bytes = in == null ? new byte[0] : in.readAllBytes();
                }
                logStreamProxyError(request, code, bytes);
                throw new OciException(upstreamStatusMessage(code, bytes));
            }
            for (var e : resp.headers().map().entrySet()) {
                String k = e.getKey();
                if (k == null) {
                    continue;
                }
                if ("transfer-encoding".equalsIgnoreCase(k) || "connection".equalsIgnoreCase(k)) {
                    continue;
                }
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    if ("content-length".equalsIgnoreCase(k) && code >= 200 && code < 300) {
                        // 流式经常无固定长度
                        continue;
                    }
                    if ("content-type".equalsIgnoreCase(k) || "cache-control".equalsIgnoreCase(k)) {
                        response.setHeader(k, e.getValue().get(0));
                    } else {
                        for (String v : e.getValue()) {
                            response.addHeader(k, v);
                        }
                    }
                }
            }
            response.setStatus(code);
            if (code >= 400) {
                byte[] bytes = new byte[0];
                try (InputStream in = resp.body()) {
                    if (in != null) {
                        bytes = in.readAllBytes();
                        response.getOutputStream().write(bytes);
                    }
                }
                logStreamProxyError(request, code, bytes);
                return;
            }
            if (response.getContentType() == null) {
                String ct = resp.headers().firstValue("content-type").orElse("text/event-stream; charset=utf-8");
                response.setContentType(ct);
            }
            boolean normalizeSse = response.getContentType() != null
                    && response.getContentType().toLowerCase(java.util.Locale.ROOT).contains("text/event-stream");
            boolean responsesToChatStream = Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.responsesToChat"));
            ResponsesBridgeStreamState responsesBridgeState = responsesToChatStream
                    ? new ResponsesBridgeStreamState(firstNonBlank((String) request.getAttribute("ociworker.rewrite.model"), ""))
                    : null;
            try (InputStream in = resp.body();
                 OutputStream out = response.getOutputStream()) {
                if (in == null) {
                    return;
                }
                response.setBufferSize(8192);
                byte[] buf = new byte[16384];
                long startNanos = System.nanoTime();
                long firstChunkTimeoutMs = timeoutMs(request, OpenAiApiConstants.ATTR_STREAM_FIRST_CHUNK_TIMEOUT_SECONDS, 60);
                long idleTimeoutMs = timeoutMs(request, OpenAiApiConstants.ATTR_STREAM_IDLE_TIMEOUT_SECONDS, 180);
                long maxStreamMs = timeoutMs(request, OpenAiApiConstants.ATTR_STREAM_MAX_SECONDS, 7200);
                boolean firstChunk = true;
                int chunks = 0;
                long outputChars = 0L;
                StringBuilder sseBuffer = new StringBuilder(8192);
                StringBuilder ssePending = new StringBuilder(8192);
                CharsetDecoder sseDecoder = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE);
                int n;
                while ((n = timedRead(in, buf, firstChunk ? firstChunkTimeoutMs : idleTimeoutMs)) != -1) {
                    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
                    if (firstChunk) {
                        firstChunk = false;
                        if (request != null) {
                            request.setAttribute(OpenAiApiConstants.ATTR_STREAM_FIRST_CHUNK_MS, elapsedMs);
                        }
                    }
                    if (maxStreamMs > 0 && elapsedMs > maxStreamMs) {
                        if (request != null) {
                            request.setAttribute(OpenAiApiConstants.ATTR_STREAM_TIMEOUT_TYPE, "max_stream");
                        }
                        throw new OciException("流式响应超过最大时长，已断开上游连接");
                    }
                    chunks++;
                    if (request != null) {
                        request.setAttribute(OpenAiApiConstants.ATTR_STREAM_CHUNK_COUNT, chunks);
                    }
                    outputChars += estimateStreamOutputChars(sseBuffer, buf, n);
                    if (request != null && outputChars > 0) {
                        long estimatedTokens = Math.max(1L, (outputChars + 3L) / 4L);
                        request.setAttribute(OpenAiApiConstants.ATTR_STREAM_ESTIMATED_TOKENS, estimatedTokens);
                        request.setAttribute(OpenAiApiConstants.ATTR_USAGE_TOKENS, estimatedTokens);
                    }
                    if (normalizeSse) {
                        ssePending.append(decodeUtf8Chunk(sseDecoder, buf, n, false));
                        String normalized = responsesToChatStream
                                ? drainChatCompletionsAsResponsesEvents(ssePending, request, responsesBridgeState)
                                : drainSseEvents(ssePending, request);
                        if (!normalized.isEmpty()) {
                            out.write(normalized.getBytes(StandardCharsets.UTF_8));
                        }
                    } else {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                }
                if (normalizeSse) {
                    ssePending.append(decodeUtf8Chunk(sseDecoder, new byte[0], 0, true));
                    if (!ssePending.isEmpty()) {
                        String drained = responsesToChatStream
                                ? drainChatCompletionsAsResponsesEvents(ssePending, request, responsesBridgeState)
                                : drainSseEvents(ssePending, request);
                        out.write(drained.getBytes(StandardCharsets.UTF_8));
                        if (!ssePending.isEmpty()) {
                            out.write(ssePending.toString().getBytes(StandardCharsets.UTF_8));
                            ssePending.setLength(0);
                        }
                    }
                    if (responsesToChatStream && responsesBridgeState != null && !responsesBridgeState.doneSent) {
                        try {
                            out.write(finalizeResponsesBridgeStream(responsesBridgeState).getBytes(StandardCharsets.UTF_8));
                            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                            responsesBridgeState.doneSent = true;
                            captureResponsesBridgeToolStats(request, responsesBridgeState);
                        } catch (Exception e) {
                            throw new IOException("finalize responses stream failed", e);
                        }
                    }
                    out.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OciException("流式请求中断");
        } catch (IOException e) {
            if (isClientAbort(e)) {
                markClientAborted(request);
                return;
            }
            if (request != null && e.getMessage() != null
                    && e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("stream idle timeout")) {
                request.setAttribute(OpenAiApiConstants.ATTR_STREAM_TIMEOUT_TYPE, "idle");
            }
            throw e;
        }
    }

    private static String upstreamStatusMessage(int code, byte[] bytes) {
        String body = bytes == null || bytes.length == 0 ? "" : new String(bytes, StandardCharsets.UTF_8).trim();
        if (body.isBlank()) {
            return "HTTP " + code;
        }
        return "HTTP " + code + ": " + truncate(body, 500);
    }

    private static void logStreamProxyError(HttpServletRequest request, int code, byte[] bytes) {
        try {
            String b = bytes == null || bytes.length == 0 ? "" : new String(bytes, StandardCharsets.UTF_8);
            String bl = b.toLowerCase(java.util.Locale.ROOT);
            boolean looksLikeInputDeserializeError =
                    bl.contains("failed to deserialize")
                            || bl.contains("untagged enum")
                            || bl.contains("modelinput")
                            || bl.contains("modellnput");
            if (request != null) {
                String rid = firstRequestHeader(
                        request,
                        "x-request-id",
                        "x-cursor-request-id",
                        "x-openai-request-id",
                        "x-amzn-trace-id",
                        "traceparent");
                String origPath = String.valueOf(request.getAttribute("ociworker.debug.origPathAfterV1"));
                String finalPath = String.valueOf(request.getAttribute("ociworker.debug.finalPathAfterV1"));
                String before = String.valueOf(request.getAttribute("ociworker.debug.responsesInputShape.before"));
                String after = String.valueOf(request.getAttribute("ociworker.debug.responsesInputShape.after"));
                // 任何 4xx/5xx 都打印一条结构化摘要，避免客户端不显示 body 时“无输出”
                log.warn("OCI proxy error(stream); rid={} code={} origPath={} finalPath={} before={} after={} body={}",
                        rid, code, origPath, finalPath, before, after, truncate(b, 1200));
                if (looksLikeInputDeserializeError && isResponsesPath(extractPathAfterV1(request))) {
                    log.warn("OCI /responses ModelInput error(stream); rid={} before={} after={} body={}",
                            rid, before, after, truncate(b, 1200));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String decodeUtf8Chunk(CharsetDecoder decoder, byte[] buf, int len, boolean endOfInput) throws IOException {
        CharBuffer out = CharBuffer.allocate(Math.max(32, len * 2 + 16));
        StringBuilder sb = new StringBuilder();
        ByteBuffer in = ByteBuffer.wrap(buf, 0, len);
        while (true) {
            var result = decoder.decode(in, out, endOfInput);
            out.flip();
            sb.append(out);
            out.clear();
            if (result.isOverflow()) {
                continue;
            }
            if (result.isError()) {
                result.throwException();
            }
            break;
        }
        if (endOfInput) {
            while (true) {
                var result = decoder.flush(out);
                out.flip();
                sb.append(out);
                out.clear();
                if (!result.isOverflow()) {
                    break;
                }
            }
        }
        return sb.toString();
    }

    private static String drainSseEvents(StringBuilder pending, HttpServletRequest request) {
        StringBuilder out = new StringBuilder();
        while (true) {
            int idx = indexOfSseEventEnd(pending);
            if (idx < 0) {
                break;
            }
            int sepLen = pending.charAt(idx) == '\r' ? 4 : 2;
            String event = pending.substring(0, idx);
            pending.delete(0, idx + sepLen);
            out.append(normalizeSseEvent(event, request)).append("\n\n");
        }
        return out.toString();
    }

    private static String drainChatCompletionsAsResponsesEvents(
            StringBuilder pending, HttpServletRequest request, ResponsesBridgeStreamState state) {
        StringBuilder out = new StringBuilder();
        while (true) {
            int idx = indexOfSseEventEnd(pending);
            if (idx < 0) {
                break;
            }
            int sepLen = pending.charAt(idx) == '\r' ? 4 : 2;
            String event = pending.substring(0, idx);
            pending.delete(0, idx + sepLen);
            String payload = sseDataPayload(event);
            if (payload == null || payload.isBlank()) {
                continue;
            }
            if ("[DONE]".equals(payload)) {
                try {
                    out.append(finalizeResponsesBridgeStream(state));
                } catch (Exception ignored) {
                }
                state.doneSent = true;
                captureResponsesBridgeToolStats(request, state);
                out.append("data: [DONE]\n\n");
                continue;
            }
            try {
                JsonNode chunk = MAPPER.readTree(payload);
                out.append(chatChunkToResponsesSse(chunk, state));
                long tokens = usageTokens(chunk);
                if (request != null && tokens > 0) {
                    request.setAttribute(OpenAiApiConstants.ATTR_USAGE_TOKENS, tokens);
                }
            } catch (Exception ignored) {
            }
        }
        return out.toString();
    }

    private static String sseDataPayload(String event) {
        if (event == null || event.isEmpty()) {
            return null;
        }
        String[] lines = event.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String prefix = line.startsWith("data: ") ? "data: " : "data:";
            String payload = line.substring(prefix.length()).trim();
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(payload);
        }
        return sb.length() == 0 ? null : sb.toString().trim();
    }

    static String chatChunkToResponsesSse(JsonNode chunk, ResponsesBridgeStreamState state) throws Exception {
        if (chunk == null || !chunk.isObject() || state == null) {
            return "";
        }
        ObjectNode co = (ObjectNode) chunk;
        state.responseId = firstNonBlank(textOrNull(co, "id"), state.responseId);
        state.model = firstNonBlank(state.model, textOrNull(co, "model"), "");
        JsonNode usage = co.get("usage");
        if (usage != null && usage.isObject()) {
            state.usage = chatUsageToResponsesUsage((ObjectNode) usage);
        }
        StringBuilder out = new StringBuilder();
        out.append(ensureResponsesBridgeCreated(state));
        JsonNode choices = co.get("choices");
        if (choices == null || !choices.isArray()) {
            return out.toString();
        }
        for (JsonNode choice : choices) {
            if (choice == null || !choice.isObject()) {
                continue;
            }
            JsonNode delta = choice.get("delta");
            if (delta != null && delta.isObject()) {
                ObjectNode d = (ObjectNode) delta;
                String reasoning = textOrNull(d, "reasoning_content");
                if (reasoning != null && !reasoning.isBlank()) {
                    out.append(ensureResponsesBridgeReasoningItem(state));
                    state.reasoning.append(reasoning);
                    out.append(responsesSseEvent(state, "response.reasoning_summary_text.delta",
                            responsesReasoningTextEvent(state, reasoning, false)));
                }
                String content = textOrNull(d, "content");
                if (content != null && !content.isEmpty()) {
                    out.append(closeResponsesBridgeReasoningItem(state));
                    out.append(ensureResponsesBridgeMessageItem(state));
                    out.append(ensureResponsesBridgeContentPart(state));
                    state.text.append(content);
                    out.append(responsesSseEvent(state, "response.output_text.delta",
                            responsesTextEvent(state, content, false)));
                }
                JsonNode toolCalls = d.get("tool_calls");
                if (toolCalls != null && toolCalls.isArray()) {
                    out.append(closeResponsesBridgeReasoningItem(state));
                    for (JsonNode toolCall : toolCalls) {
                        if (toolCall == null || !toolCall.isObject()) {
                            continue;
                        }
                        out.append(handleResponsesBridgeToolCall(state, (ObjectNode) toolCall));
                    }
                }
            }
            String finish = text(choice, "finish_reason");
            if (finish != null && !finish.isBlank()) {
                state.finishReason = finish;
            }
        }
        return out.toString();
    }

    private static String ensureResponsesBridgeCreated(ResponsesBridgeStreamState state) throws Exception {
        if (state.createdSent) {
            return "";
        }
        state.createdSent = true;
        ObjectNode response = responsesResponseObject(
                state.responseId, state.model, "in_progress", MAPPER.createArrayNode(), null, null, state.createdAt);
        ObjectNode event = MAPPER.createObjectNode();
        event.set("response", response);
        return responsesSseEvent(state, "response.created", event);
    }

    private static String ensureResponsesBridgeMessageItem(ResponsesBridgeStreamState state) throws Exception {
        if (state.messageItemId != null) {
            return "";
        }
        state.messageItemId = "msg_" + CommonUtils.generateId();
        state.messageOutputIndex = state.nextOutputIndex++;
        ObjectNode event = MAPPER.createObjectNode();
        event.put("output_index", state.messageOutputIndex);
        ObjectNode item = MAPPER.createObjectNode();
        item.put("type", "message");
        item.put("id", state.messageItemId);
        item.put("role", "assistant");
        item.put("status", "in_progress");
        item.set("content", MAPPER.createArrayNode());
        event.set("item", item);
        return responsesSseEvent(state, "response.output_item.added", event);
    }

    private static String ensureResponsesBridgeContentPart(ResponsesBridgeStreamState state) throws Exception {
        if (state.contentPartOpen) {
            return "";
        }
        state.contentPartOpen = true;
        ObjectNode event = MAPPER.createObjectNode();
        event.put("output_index", state.messageOutputIndex);
        event.put("content_index", 0);
        event.put("item_id", state.messageItemId);
        ObjectNode part = MAPPER.createObjectNode();
        part.put("type", "output_text");
        part.put("text", "");
        part.set("annotations", MAPPER.createArrayNode());
        part.set("logprobs", MAPPER.createArrayNode());
        event.set("part", part);
        return responsesSseEvent(state, "response.content_part.added", event);
    }

    private static ObjectNode responsesTextEvent(ResponsesBridgeStreamState state, String text, boolean done) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("output_index", state.messageOutputIndex);
        event.put("content_index", 0);
        event.put("item_id", state.messageItemId);
        if (done) {
            event.put("text", text == null ? "" : text);
        } else {
            event.put("delta", text == null ? "" : text);
        }
        return event;
    }

    private static String ensureResponsesBridgeReasoningItem(ResponsesBridgeStreamState state) throws Exception {
        if (state.reasoningOpen || state.reasoningDone) {
            return "";
        }
        state.reasoningOpen = true;
        state.reasoningItemId = "rs_" + CommonUtils.generateId();
        state.reasoningOutputIndex = state.nextOutputIndex++;
        ObjectNode added = MAPPER.createObjectNode();
        added.put("output_index", state.reasoningOutputIndex);
        ObjectNode item = MAPPER.createObjectNode();
        item.put("type", "reasoning");
        item.put("id", state.reasoningItemId);
        item.put("status", "in_progress");
        item.set("summary", MAPPER.createArrayNode());
        added.set("item", item);
        ObjectNode partEvent = MAPPER.createObjectNode();
        partEvent.put("output_index", state.reasoningOutputIndex);
        partEvent.put("summary_index", 0);
        partEvent.put("item_id", state.reasoningItemId);
        ObjectNode part = MAPPER.createObjectNode();
        part.put("type", "summary_text");
        part.put("text", "");
        partEvent.set("part", part);
        return responsesSseEvent(state, "response.output_item.added", added)
                + responsesSseEvent(state, "response.reasoning_summary_part.added", partEvent);
    }

    private static String closeResponsesBridgeReasoningItem(ResponsesBridgeStreamState state) throws Exception {
        if (!state.reasoningOpen) {
            return "";
        }
        state.reasoningOpen = false;
        state.reasoningDone = true;
        String text = state.reasoning.toString();
        ObjectNode doneText = responsesReasoningTextEvent(state, text, true);
        ObjectNode partDone = MAPPER.createObjectNode();
        partDone.put("output_index", state.reasoningOutputIndex);
        partDone.put("summary_index", 0);
        partDone.put("item_id", state.reasoningItemId);
        ObjectNode part = MAPPER.createObjectNode();
        part.put("type", "summary_text");
        part.put("text", text);
        partDone.set("part", part);
        ObjectNode itemDone = MAPPER.createObjectNode();
        itemDone.put("output_index", state.reasoningOutputIndex);
        itemDone.set("item", responsesReasoningOutput(text));
        ((ObjectNode) itemDone.get("item")).put("id", state.reasoningItemId);
        ((ObjectNode) itemDone.get("item")).put("status", "completed");
        return responsesSseEvent(state, "response.reasoning_summary_text.done", doneText)
                + responsesSseEvent(state, "response.reasoning_summary_part.done", partDone)
                + responsesSseEvent(state, "response.output_item.done", itemDone);
    }

    private static ObjectNode responsesReasoningTextEvent(ResponsesBridgeStreamState state, String text, boolean done) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("output_index", state.reasoningOutputIndex);
        event.put("summary_index", 0);
        event.put("item_id", state.reasoningItemId);
        if (done) {
            event.put("text", text == null ? "" : text);
        } else {
            event.put("delta", text == null ? "" : text);
        }
        return event;
    }

    private static String handleResponsesBridgeToolCall(ResponsesBridgeStreamState state, ObjectNode toolCall) throws Exception {
        int idx = 0;
        JsonNode index = toolCall.get("index");
        if (index != null && index.isNumber()) {
            idx = index.asInt();
        }
        ResponsesBridgeTool tool = state.tools.get(idx);
        StringBuilder out = new StringBuilder();
        JsonNode fnNode = toolCall.get("function");
        ObjectNode fn = fnNode != null && fnNode.isObject() ? (ObjectNode) fnNode : MAPPER.createObjectNode();
        if (tool == null) {
            tool = new ResponsesBridgeTool();
            tool.index = idx;
            tool.itemId = "fc_" + CommonUtils.generateId();
            tool.outputIndex = state.nextOutputIndex++;
            tool.callId = firstNonBlank(textOrNull(toolCall, "id"), "call_" + CommonUtils.generateId());
            tool.name = firstNonBlank(textOrNull(fn, "name"), "tool");
            state.tools.put(idx, tool);
            ObjectNode event = MAPPER.createObjectNode();
            event.put("output_index", tool.outputIndex);
            ObjectNode item = MAPPER.createObjectNode();
            item.put("type", "function_call");
            item.put("id", tool.itemId);
            item.put("call_id", tool.callId);
            item.put("name", tool.name);
            item.put("arguments", "");
            item.put("status", "in_progress");
            event.set("item", item);
            out.append(responsesSseEvent(state, "response.output_item.added", event));
        } else {
            tool.callId = firstNonBlank(textOrNull(toolCall, "id"), tool.callId);
            tool.name = firstNonBlank(textOrNull(fn, "name"), tool.name);
        }
        String argsDelta = textOrNull(fn, "arguments");
        if (argsDelta != null && !argsDelta.isEmpty()) {
            tool.arguments.append(argsDelta);
            ObjectNode event = MAPPER.createObjectNode();
            event.put("output_index", tool.outputIndex);
            event.put("item_id", tool.itemId);
            event.put("delta", argsDelta);
            event.put("call_id", tool.callId);
            event.put("name", tool.name);
            out.append(responsesSseEvent(state, "response.function_call_arguments.delta", event));
        }
        return out.toString();
    }

    static String finalizeResponsesBridgeStream(ResponsesBridgeStreamState state) throws Exception {
        if (state == null || state.completedSent) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append(ensureResponsesBridgeCreated(state));
        out.append(closeResponsesBridgeReasoningItem(state));
        if (state.messageItemId != null) {
            String text = state.text.toString();
            if (state.contentPartOpen) {
                out.append(responsesSseEvent(state, "response.output_text.done", responsesTextEvent(state, text, true)));
                ObjectNode partDone = MAPPER.createObjectNode();
                partDone.put("output_index", state.messageOutputIndex);
                partDone.put("content_index", 0);
                partDone.put("item_id", state.messageItemId);
                ObjectNode part = MAPPER.createObjectNode();
                part.put("type", "output_text");
                part.put("text", text);
                part.set("annotations", MAPPER.createArrayNode());
                part.set("logprobs", MAPPER.createArrayNode());
                partDone.set("part", part);
                out.append(responsesSseEvent(state, "response.content_part.done", partDone));
            }
            ObjectNode itemDone = MAPPER.createObjectNode();
            itemDone.put("output_index", state.messageOutputIndex);
            ObjectNode item = responsesMessageOutput(text);
            item.put("id", state.messageItemId);
            itemDone.set("item", item);
            out.append(responsesSseEvent(state, "response.output_item.done", itemDone));
        }
        for (ResponsesBridgeTool tool : state.tools.values()) {
            String args = firstNonBlank(tool.arguments.toString(), "{}");
            ObjectNode argsDone = MAPPER.createObjectNode();
            argsDone.put("output_index", tool.outputIndex);
            argsDone.put("item_id", tool.itemId);
            argsDone.put("call_id", tool.callId);
            argsDone.put("name", tool.name);
            argsDone.put("arguments", args);
            out.append(responsesSseEvent(state, "response.function_call_arguments.done", argsDone));

            ObjectNode itemDone = MAPPER.createObjectNode();
            itemDone.put("output_index", tool.outputIndex);
            ObjectNode item = MAPPER.createObjectNode();
            item.put("type", "function_call");
            item.put("id", tool.itemId);
            item.put("call_id", tool.callId);
            item.put("name", tool.name);
            item.put("arguments", args);
            item.put("status", "completed");
            itemDone.set("item", item);
            out.append(responsesSseEvent(state, "response.output_item.done", itemDone));
        }
        state.completedSent = true;
        ObjectNode completed = MAPPER.createObjectNode();
        String status = "length".equalsIgnoreCase(state.finishReason) ? "incomplete" : "completed";
        ArrayNode output = MAPPER.createArrayNode();
        if (state.reasoning.length() > 0) {
            ObjectNode reasoning = responsesReasoningOutput(state.reasoning.toString());
            if (state.reasoningItemId != null) {
                reasoning.put("id", state.reasoningItemId);
            }
            output.add(reasoning);
        }
        if (state.messageItemId != null || state.tools.isEmpty()) {
            ObjectNode message = responsesMessageOutput(state.text.toString());
            if (state.messageItemId != null) {
                message.put("id", state.messageItemId);
            }
            output.add(message);
        }
        for (ResponsesBridgeTool tool : state.tools.values()) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("type", "function_call");
            item.put("id", tool.itemId);
            item.put("call_id", tool.callId);
            item.put("name", tool.name);
            item.put("arguments", firstNonBlank(tool.arguments.toString(), "{}"));
            item.put("status", "completed");
            output.add(item);
        }
        ObjectNode incompleteDetails = null;
        if ("length".equalsIgnoreCase(state.finishReason)) {
            incompleteDetails = MAPPER.createObjectNode();
            incompleteDetails.put("reason", "max_output_tokens");
        }
        ObjectNode response = responsesResponseObject(
                state.responseId, state.model, status, output, state.usage, incompleteDetails, state.createdAt);
        response.put("completed_at", Instant.now().getEpochSecond());
        completed.set("response", response);
        out.append(responsesSseEvent(state, "response.completed", completed));
        return out.toString();
    }

    private static String responsesSseEvent(ResponsesBridgeStreamState state, String type, ObjectNode event) throws Exception {
        ObjectNode out = event == null ? MAPPER.createObjectNode() : event.deepCopy();
        out.put("type", type);
        if (out.get("sequence_number") == null) {
            out.put("sequence_number", state == null ? 0 : state.nextSequenceNumber++);
        }
        return "event: " + type + "\n" + "data: " + MAPPER.writeValueAsString(out) + "\n\n";
    }

    private static int indexOfSseEventEnd(StringBuilder pending) {
        for (int i = 0; i < pending.length() - 1; i++) {
            char c = pending.charAt(i);
            if (c == '\n' && pending.charAt(i + 1) == '\n') {
                return i;
            }
            if (c == '\r'
                    && i + 3 < pending.length()
                    && pending.charAt(i + 1) == '\n'
                    && pending.charAt(i + 2) == '\r'
                    && pending.charAt(i + 3) == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeSseEvent(String event, HttpServletRequest request) {
        if (event == null || event.isEmpty()) {
            return "";
        }
        String[] lines = event.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder(event.length() + 32);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("data:")) {
                String prefix = line.startsWith("data: ") ? "data: " : "data:";
                String payload = line.substring(prefix.length()).trim();
                out.append(prefix).append(normalizeSseDataPayload(payload, request));
            } else {
                out.append(line);
            }
            if (i + 1 < lines.length) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    static String normalizeSseDataPayload(String payload, HttpServletRequest request) {
        if (payload == null || payload.isBlank() || "[DONE]".equals(payload)) {
            return payload == null ? "" : payload;
        }
        try {
            JsonNode root = MAPPER.readTree(payload);
            if (root != null
                    && root.isObject()
                    && "chat.completion.chunk".equals(text(root, "object"))
                    && root.get("choices") == null
                    && root.get("error") == null
                    && root.get("usage") != null
                    && root.get("usage").isObject()) {
                ObjectNode normalized = ((ObjectNode) root).deepCopy();
                normalized.set("choices", MAPPER.createArrayNode());
                long tokens = usageTokens(normalized);
                if (request != null && tokens > 0) {
                    request.setAttribute(OpenAiApiConstants.ATTR_USAGE_TOKENS, tokens);
                }
                return normalized.toString();
            }
            if (root != null
                    && root.isObject()
                    && "chat.completion.chunk".equals(text(root, "object"))
                    && root.get("choices") != null
                    && root.get("choices").isArray()) {
                ObjectNode normalized = null;
                ArrayNode choices = null;
                for (int i = 0; i < root.get("choices").size(); i++) {
                    JsonNode choice = root.get("choices").get(i);
                    JsonNode delta = choice == null ? null : choice.get("delta");
                    JsonNode toolCalls = delta == null ? null : delta.get("tool_calls");
                    if (toolCalls != null && toolCalls.isArray() && request != null) {
                        incrementIntAttribute(request, "ociworker.lb.responseToolCallCount",
                                countNewStreamingToolCalls(toolCalls, request));
                    }
                    String finishReason = text(choice, "finish_reason");
                    if ("tool_calls".equalsIgnoreCase(finishReason) && request != null) {
                        request.setAttribute("ociworker.lb.toolLifecycleCompleted", Boolean.TRUE);
                    }
                    if (delta != null
                            && delta.isObject()
                            && delta.get("role") == null
                            && delta.get("tool_calls") != null) {
                        if (normalized == null) {
                            normalized = ((ObjectNode) root).deepCopy();
                            choices = (ArrayNode) normalized.get("choices");
                        }
                        JsonNode normalizedChoice = choices.get(i);
                        JsonNode normalizedDelta = normalizedChoice == null ? null : normalizedChoice.get("delta");
                        if (normalizedDelta instanceof ObjectNode deltaObject) {
                            deltaObject.put("role", "assistant");
                        }
                    }
                }
                if (normalized != null) {
                    return normalized.toString();
                }
            }
        } catch (Exception ignored) {
        }
        return payload;
    }

    private static int timedRead(InputStream in, byte[] buf, long timeoutMs) throws IOException, InterruptedException {
        if (timeoutMs <= 0) {
            return in.read(buf);
        }
        final int[] read = new int[]{Integer.MIN_VALUE};
        final IOException[] error = new IOException[1];
        Thread reader = Thread.ofVirtual().name("oci-openai-stream-read").start(() -> {
            try {
                read[0] = in.read(buf);
            } catch (IOException e) {
                error[0] = e;
            }
        });
        reader.join(timeoutMs);
        if (reader.isAlive()) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            reader.interrupt();
            throw new IOException("stream idle timeout after " + timeoutMs + "ms");
        }
        if (error[0] != null) {
            throw error[0];
        }
        return read[0];
    }

    private static long estimateStreamOutputChars(StringBuilder pending, byte[] buf, int len) {
        if (pending == null || buf == null || len <= 0) {
            return 0L;
        }
        pending.append(new String(buf, 0, len, StandardCharsets.UTF_8));
        long chars = 0L;
        int idx;
        while ((idx = indexOfLineBreak(pending)) >= 0) {
            String line = pending.substring(0, idx).strip();
            pending.delete(0, idx + 1);
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            chars += streamPayloadTextChars(payload);
        }
        if (pending.length() > 65536) {
            pending.delete(0, pending.length() - 8192);
        }
        return chars;
    }

    private static int indexOfLineBreak(StringBuilder value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static long streamPayloadTextChars(String payload) {
        try {
            JsonNode root = MAPPER.readTree(payload);
            return streamTextChars(root);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static long streamTextChars(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0L;
        }
        if (node.isArray()) {
            long total = 0L;
            for (JsonNode child : node) {
                total += streamTextChars(child);
            }
            return total;
        }
        if (!node.isObject()) {
            return 0L;
        }
        long total = 0L;
        for (var it = node.properties().iterator(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(java.util.Locale.ROOT);
            JsonNode value = entry.getValue();
            if (isStreamTextKey(key) && value != null && value.isTextual()) {
                total += value.asText("").length();
                continue;
            }
            if (shouldDescendStreamField(key)) {
                total += streamTextChars(value);
            }
        }
        return total;
    }

    private static boolean isStreamTextKey(String key) {
        return "content".equals(key)
                || "text".equals(key)
                || "delta".equals(key)
                || "output_text".equals(key);
    }

    private static boolean shouldDescendStreamField(String key) {
        return !"model".equals(key)
                && !"id".equals(key)
                && !"object".equals(key)
                && !"type".equals(key)
                && !"role".equals(key)
                && !"finish_reason".equals(key)
                && !"index".equals(key);
    }

    private static long timeoutMs(HttpServletRequest request, String attr, int defaultSeconds) {
        long seconds = defaultSeconds;
        Object value = request == null ? null : request.getAttribute(attr);
        if (value instanceof Number n) {
            seconds = n.longValue();
        } else if (value != null) {
            try {
                seconds = Long.parseLong(String.valueOf(value).trim());
            } catch (Exception ignored) {
            }
        }
        if (seconds <= 0) {
            return 0L;
        }
        return Math.min(21600L, seconds) * 1000L;
    }

    private void bufferAndCopy(
            HttpClient client, HttpRequest httpRequest, HttpServletResponse response, HttpServletRequest request)
            throws IOException {
        try {
            HttpResponse<String> resp = client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = resp.statusCode();
            if (request != null) {
                request.setAttribute(OpenAiApiConstants.ATTR_UPSTREAM_STATUS, code);
            }
            resp.headers().map().forEach((k, vals) -> {
                if (k == null || vals == null) {
                    return;
                }
                if ("transfer-encoding".equalsIgnoreCase(k) || "connection".equalsIgnoreCase(k)
                        || "content-length".equalsIgnoreCase(k)) {
                    return;
                }
                if (vals != null) {
                    for (String v : vals) {
                        if (v != null) {
                            response.addHeader(k, v);
                        }
                    }
                }
            });
            if (response.getContentType() == null) {
                String ct = resp.headers().firstValue("content-type").orElse("application/json; charset=utf-8");
                response.setContentType(ct);
            }
            response.setStatus(code);
            String b = resp.body() != null ? resp.body() : "";
            captureUsageTokens(request, b);
            captureChatCompletionToolStats(request, b);
            if (code >= 200
                    && code < 300
                    && request != null
                    && Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.rerankToCommon"))
                    && b != null
                    && !b.isBlank()) {
                String ct = resp.headers().firstValue("content-type").orElse("application/json; charset=utf-8");
                if (ct.toLowerCase().contains("json")) {
                    try {
                        b = transformRerankResponseJson(
                                b,
                                stringAttr(request, "ociworker.rerank.originalDocumentsJson"),
                                Boolean.TRUE.equals(request.getAttribute("ociworker.rerank.returnDocuments")));
                        response.setContentType("application/json; charset=utf-8");
                    } catch (Exception e) {
                        log.warn("Failed to transform OCI rerank response: {}", e.getMessage());
                    }
                }
            }
            if (code >= 400
                    && request != null
                    && b != null) {
                String bl = b.toLowerCase(java.util.Locale.ROOT);
                boolean looksLikeInputDeserializeError =
                        bl.contains("failed to deserialize")
                                || bl.contains("untagged enum")
                                || bl.contains("modelinput")
                                || bl.contains("modellnput");
                boolean maybeResponses =
                        Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.useRawV1Base"))
                                || isResponsesPath(String.valueOf(request.getAttribute("ociworker.debug.finalPathAfterV1")))
                                || isResponsesPath(String.valueOf(request.getAttribute("ociworker.debug.origPathAfterV1")))
                                || isResponsesPath(extractPathAfterV1(request));
                String before = String.valueOf(request.getAttribute("ociworker.debug.responsesInputShape.before"));
                String after = String.valueOf(request.getAttribute("ociworker.debug.responsesInputShape.after"));
                String rid = firstRequestHeader(
                        request,
                        "x-request-id",
                        "x-cursor-request-id",
                        "x-openai-request-id",
                        "x-amzn-trace-id",
                        "traceparent");
                String origPath = String.valueOf(request.getAttribute("ociworker.debug.origPathAfterV1"));
                String finalPath = String.valueOf(request.getAttribute("ociworker.debug.finalPathAfterV1"));
                // 任何 4xx/5xx 都打印一条结构化摘要，避免 Cursor 端不显示 body 时“无输出”
                log.warn("OCI proxy error; rid={} code={} origPath={} finalPath={} maybeResponses={} before={} after={} body={}",
                        rid, code, origPath, finalPath, maybeResponses, before, after, truncate(b, 1200));
                if (looksLikeInputDeserializeError && maybeResponses) {
                    log.warn("OCI /responses ModelInput error; rid={} code={} origPath={} finalPath={} before={} after={} body={}",
                            rid, code, origPath, finalPath, before, after, truncate(b, 1200));
                }
            }
            if (code >= 200
                    && code < 300
                    && request != null
                    && Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.responsesToChat"))
                    && b != null
                    && !b.isBlank()) {
                String ct = resp.headers().firstValue("content-type").orElse("application/json; charset=utf-8");
                if (ct.toLowerCase().contains("json")) {
                    try {
                        String modelHint = (String) request.getAttribute("ociworker.rewrite.model");
                        int responseToolCalls = countChatCompletionToolCalls(b);
                        request.setAttribute("ociworker.lb.responseToolCallCount", responseToolCalls);
                        request.setAttribute("ociworker.lb.toolLifecycleCompleted", responseToolCalls > 0);
                        b = convertChatCompletionJsonToResponsesJson(b, modelHint);
                        response.setContentType("application/json; charset=utf-8");
                    } catch (Exception ignored) {
                    }
                }
            }
            if (code >= 200
                    && code < 300
                    && request != null
                    && Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.chatToResponses"))
                    && b != null
                    && !b.isBlank()) {
                String ct = resp.headers().firstValue("content-type").orElse("application/json; charset=utf-8");
                if (ct.toLowerCase().contains("json")) {
                    try {
                        String modelHint = (String) request.getAttribute("ociworker.rewrite.model");
                        // 如果上游请求 stream=true，则在响应侧模拟 SSE（更兼容 New API/IDE）。
                        if (Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.simulateSse"))) {
                            String text = extractResponsesAssistantText((ObjectNode) MAPPER.readTree(b));
                            if (text != null) {
                                response.setStatus(200);
                                response.setHeader("cache-control", "no-cache");
                                response.setContentType("text/event-stream; charset=utf-8");
                                writeChatCompletionSseFromText(response, text, modelHint);
                                return;
                            }
                        }
                        b = convertResponsesJsonToChatCompletionJson(b, modelHint);
                        response.setContentType("application/json; charset=utf-8");
                    } catch (Exception ignored) {
                    }
                }
            }
            if (code >= 200
                    && code < 300
                    && request != null
                    && Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.simulateChatCompletionSse"))
                    && b != null
                    && !b.isBlank()) {
                String ct = resp.headers().firstValue("content-type").orElse("application/json; charset=utf-8");
                if (ct.toLowerCase(Locale.ROOT).contains("json")) {
                    try {
                        String modelHint = (String) request.getAttribute("ociworker.rewrite.model");
                        String sse = chatCompletionJsonToSse(b, modelHint);
                        if (sse != null && !sse.isBlank()) {
                            response.setStatus(200);
                            response.setHeader("cache-control", "no-cache");
                            response.setContentType("text/event-stream; charset=utf-8");
                            response.getOutputStream().write(sse.getBytes(StandardCharsets.UTF_8));
                            return;
                        }
                    } catch (Exception e) {
                        log.warn("Failed to simulate chat completion SSE: {}", e.getMessage());
                    }
                }
            }
            response.getOutputStream().write(b.getBytes(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OciException("请求中断");
        } catch (IOException e) {
            if (isClientAbort(e)) {
                markClientAborted(request);
                return;
            }
            throw e;
        }
    }

    private void bufferAndCopyBytes(
            HttpClient client, HttpRequest httpRequest, HttpServletResponse response, HttpServletRequest request)
            throws IOException {
        try {
            HttpResponse<byte[]> resp = client.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();
            if (request != null) {
                request.setAttribute(OpenAiApiConstants.ATTR_UPSTREAM_STATUS, code);
            }
            resp.headers().map().forEach((k, vals) -> {
                if (k == null || vals == null) {
                    return;
                }
                if ("transfer-encoding".equalsIgnoreCase(k) || "connection".equalsIgnoreCase(k)
                        || "content-length".equalsIgnoreCase(k)) {
                    return;
                }
                for (String v : vals) {
                    if (v != null) {
                        response.addHeader(k, v);
                    }
                }
            });
            if (response.getContentType() == null) {
                String ct = resp.headers().firstValue("content-type").orElse("application/octet-stream");
                response.setContentType(ct);
            }
            response.setStatus(code);
            byte[] b = resp.body() != null ? resp.body() : new byte[0];
            if (code >= 400 && request != null) {
                String rid = firstRequestHeader(
                        request,
                        "x-request-id",
                        "x-cursor-request-id",
                        "x-openai-request-id",
                        "x-amzn-trace-id",
                        "traceparent");
                String origPath = String.valueOf(request.getAttribute("ociworker.debug.origPathAfterV1"));
                String finalPath = String.valueOf(request.getAttribute("ociworker.debug.finalPathAfterV1"));
                String bodyText = new String(b, StandardCharsets.UTF_8);
                log.warn("OCI binary proxy error; rid={} code={} origPath={} finalPath={} body={}",
                        rid, code, origPath, finalPath, truncate(bodyText, 1200));
            }
            response.getOutputStream().write(b);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OciException("请求中断");
        } catch (IOException e) {
            if (isClientAbort(e)) {
                markClientAborted(request);
                return;
            }
            throw e;
        }
    }

    private static void markClientAborted(HttpServletRequest request) {
        if (request != null) {
            request.setAttribute(OpenAiApiConstants.ATTR_CLIENT_ABORTED, Boolean.TRUE);
        }
    }

    private static boolean isClientAbort(IOException e) {
        String message = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
        return message.contains("broken pipe")
                || message.contains("aborted")
                || message.contains("connection reset")
                || message.contains("reset by peer")
                || message.contains("clientabort");
    }

    private static void captureUsageTokens(HttpServletRequest request, String body) {
        if (request == null || body == null || body.isBlank()) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            long tokens = usageTokens(root);
            if (tokens > 0) {
                request.setAttribute(OpenAiApiConstants.ATTR_USAGE_TOKENS, tokens);
            }
        } catch (Exception ignored) {
        }
    }

    private static void captureResponsesBridgeToolStats(HttpServletRequest request, ResponsesBridgeStreamState state) {
        if (request == null || state == null) {
            return;
        }
        request.setAttribute("ociworker.lb.responseToolCallCount", state.tools.size());
        request.setAttribute("ociworker.lb.toolLifecycleCompleted", state.completedSent && !state.tools.isEmpty());
    }

    private static void captureChatCompletionToolStats(HttpServletRequest request, String body) {
        if (request == null || body == null || body.isBlank()) {
            return;
        }
        int count = countChatCompletionToolCalls(body);
        if (count > 0) {
            request.setAttribute("ociworker.lb.responseToolCallCount", count);
            request.setAttribute("ociworker.lb.toolLifecycleCompleted", Boolean.TRUE);
        }
    }

    private static void incrementIntAttribute(HttpServletRequest request, String attr, int delta) {
        if (request == null || attr == null || delta <= 0) {
            return;
        }
        Object value = request.getAttribute(attr);
        int current = 0;
        if (value instanceof Number n) {
            current = n.intValue();
        } else if (value != null) {
            try {
                current = Integer.parseInt(String.valueOf(value));
            } catch (Exception ignored) {
            }
        }
        request.setAttribute(attr, Math.max(0, current) + delta);
    }

    static int countNewStreamingToolCalls(JsonNode toolCalls) {
        if (toolCalls == null || !toolCalls.isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode call : toolCalls) {
            if (call == null || !call.isObject()) {
                continue;
            }
            JsonNode id = call.get("id");
            JsonNode fn = call.get("function");
            JsonNode name = fn != null && fn.isObject() ? fn.get("name") : null;
            if ((id != null && id.isTextual() && !id.asText().isBlank())
                    || (name != null && name.isTextual() && !name.asText().isBlank())) {
                count++;
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    static int countNewStreamingToolCalls(JsonNode toolCalls, HttpServletRequest request) {
        if (request == null || toolCalls == null || !toolCalls.isArray()) {
            return countNewStreamingToolCalls(toolCalls);
        }
        Object existing = request.getAttribute("ociworker.lb.seenStreamingToolCalls");
        Set<String> seen;
        if (existing instanceof Set<?> set) {
            seen = (Set<String>) set;
        } else {
            seen = new HashSet<>();
            request.setAttribute("ociworker.lb.seenStreamingToolCalls", seen);
        }
        int count = 0;
        for (JsonNode call : toolCalls) {
            String signature = streamingToolCallSignature(call);
            if (signature != null && seen.add(signature)) {
                count++;
            }
        }
        return count;
    }

    private static String streamingToolCallSignature(JsonNode call) {
        if (call == null || !call.isObject()) {
            return null;
        }
        JsonNode id = call.get("id");
        if (id != null && id.isTextual() && !id.asText().isBlank()) {
            return "id:" + id.asText();
        }
        JsonNode index = call.get("index");
        if (index != null && index.isNumber()) {
            return "index:" + index.asInt();
        }
        JsonNode fn = call.get("function");
        JsonNode name = fn != null && fn.isObject() ? fn.get("name") : null;
        if (name != null && name.isTextual() && !name.asText().isBlank()) {
            return "name:" + name.asText();
        }
        return null;
    }

    private static int countChatCompletionToolCalls(String body) {
        if (body == null || body.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode choices = root == null ? null : root.get("choices");
            if (choices == null || !choices.isArray()) {
                return 0;
            }
            int count = 0;
            for (JsonNode choice : choices) {
                JsonNode calls = choice == null ? null : choice.at("/message/tool_calls");
                if (calls != null && calls.isArray()) {
                    count += calls.size();
                }
            }
            return count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static long usageTokens(JsonNode root) {
        if (root == null || !root.isObject()) {
            return 0L;
        }
        JsonNode usage = root.get("usage");
        if (usage == null || !usage.isObject()) {
            return 0L;
        }
        long total = longField(usage, "total_tokens", "totalTokens", "totalTokenCount");
        if (total > 0) {
            return total;
        }
        long prompt = longField(usage, "prompt_tokens", "promptTokens", "input_tokens", "inputTokens");
        long completion = longField(usage, "completion_tokens", "completionTokens", "output_tokens", "outputTokens");
        return Math.max(0L, prompt + completion);
    }

    private static long longField(JsonNode node, String... names) {
        if (node == null || names == null) {
            return 0L;
        }
        for (String name : names) {
            JsonNode v = node.get(name);
            if (v == null || v.isNull() || v.isMissingNode()) {
                continue;
            }
            if (v.isNumber()) {
                return Math.max(0L, v.asLong());
            }
            if (v.isTextual()) {
                try {
                    return Math.max(0L, Long.parseLong(v.asText().trim()));
                } catch (Exception ignored) {
                }
            }
        }
        return 0L;
    }

    private static String textOrNull(ObjectNode o, String field) {
        if (o == null) {
            return null;
        }
        JsonNode n = o.get(field);
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isNumber() || n.isBoolean()) {
            return n.toString();
        }
        return null;
    }

    static String convertChatCompletionJsonToResponsesJson(String chatJson, String modelHint) throws Exception {
        JsonNode c = MAPPER.readTree(chatJson);
        if (c == null || !c.isObject()) {
            return chatJson;
        }
        ObjectNode co = (ObjectNode) c;
        String id = firstNonBlank(textOrNull(co, "id"), "resp_ociworker");
        String model = firstNonBlank(modelHint, textOrNull(co, "model"), "");
        String status = "completed";
        ObjectNode incompleteDetails = null;
        ArrayNode output = MAPPER.createArrayNode();
        JsonNode choices = co.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode message = choices.get(0).path("message");
            String reasoning = text(message, "reasoning_content");
            if (reasoning != null && !reasoning.isBlank()) {
                output.add(responsesReasoningOutput(reasoning));
            }
            String content = chatMessageContentText(message.get("content"));
            JsonNode toolCalls = message.get("tool_calls");
            if ((content != null && !content.isBlank()) || toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
                output.add(responsesMessageOutput(content == null ? "" : content));
            }
            if (toolCalls != null && toolCalls.isArray()) {
                for (JsonNode call : toolCalls) {
                    if (call == null || !call.isObject()) {
                        continue;
                    }
                    output.add(responsesFunctionCallOutput((ObjectNode) call));
                }
            }
            String finishReason = text(choices.get(0), "finish_reason");
            if ("length".equalsIgnoreCase(finishReason)) {
                status = "incomplete";
                incompleteDetails = MAPPER.createObjectNode();
                incompleteDetails.put("reason", "max_output_tokens");
            }
        }
        if (output.isEmpty()) {
            output.add(responsesMessageOutput(""));
        }
        JsonNode usage = co.get("usage");
        ObjectNode responseUsage = usage != null && usage.isObject() ? chatUsageToResponsesUsage((ObjectNode) usage) : null;
        ObjectNode out = responsesResponseObject(
                id, model, status, output, responseUsage, incompleteDetails, Instant.now().getEpochSecond());
        out.put("completed_at", Instant.now().getEpochSecond());
        return MAPPER.writeValueAsString(out);
    }

    private static ObjectNode responsesResponseObject(
            String id,
            String model,
            String status,
            ArrayNode output,
            JsonNode usage,
            JsonNode incompleteDetails,
            long createdAt) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("id", firstNonBlank(id, "resp_" + CommonUtils.generateId()));
        response.put("object", "response");
        response.put("created_at", createdAt > 0 ? createdAt : Instant.now().getEpochSecond());
        response.put("status", firstNonBlank(status, "completed"));
        response.putNull("error");
        if (incompleteDetails != null && incompleteDetails.isObject()) {
            response.set("incomplete_details", incompleteDetails);
        } else {
            response.putNull("incomplete_details");
        }
        response.putNull("instructions");
        response.putNull("max_output_tokens");
        response.put("model", firstNonBlank(model, ""));
        response.set("output", output == null ? MAPPER.createArrayNode() : output);
        response.put("parallel_tool_calls", true);
        response.putNull("previous_response_id");
        ObjectNode reasoning = MAPPER.createObjectNode();
        reasoning.putNull("effort");
        reasoning.putNull("summary");
        response.set("reasoning", reasoning);
        response.put("store", true);
        response.put("temperature", 1.0);
        ObjectNode text = MAPPER.createObjectNode();
        ObjectNode format = MAPPER.createObjectNode();
        format.put("type", "text");
        text.set("format", format);
        response.set("text", text);
        response.put("tool_choice", "auto");
        response.set("tools", MAPPER.createArrayNode());
        response.put("top_p", 1.0);
        response.put("truncation", "disabled");
        if (usage != null && usage.isObject()) {
            response.set("usage", usage);
        } else {
            response.putNull("usage");
        }
        response.putNull("user");
        response.set("metadata", MAPPER.createObjectNode());
        return response;
    }

    private static ObjectNode responsesMessageOutput(String text) {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("type", "message");
        item.put("id", "msg_" + CommonUtils.generateId());
        item.put("role", "assistant");
        item.put("status", "completed");
        ArrayNode content = MAPPER.createArrayNode();
        ObjectNode part = MAPPER.createObjectNode();
        part.put("type", "output_text");
        part.put("text", text == null ? "" : text);
        part.set("annotations", MAPPER.createArrayNode());
        part.set("logprobs", MAPPER.createArrayNode());
        content.add(part);
        item.set("content", content);
        return item;
    }

    private static ObjectNode responsesReasoningOutput(String text) {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("type", "reasoning");
        item.put("id", "rs_" + CommonUtils.generateId());
        ArrayNode summary = MAPPER.createArrayNode();
        ObjectNode part = MAPPER.createObjectNode();
        part.put("type", "summary_text");
        part.put("text", text == null ? "" : text);
        summary.add(part);
        item.set("summary", summary);
        return item;
    }

    private static ObjectNode responsesFunctionCallOutput(ObjectNode call) {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("type", "function_call");
        item.put("id", "fc_" + CommonUtils.generateId());
        item.put("call_id", firstNonBlank(textOrNull(call, "id"), "call_" + CommonUtils.generateId()));
        JsonNode fnNode = call.get("function");
        ObjectNode fn = fnNode != null && fnNode.isObject() ? (ObjectNode) fnNode : MAPPER.createObjectNode();
        item.put("name", firstNonBlank(textOrNull(fn, "name"), "tool"));
        item.put("arguments", firstNonBlank(textOrNull(fn, "arguments"), "{}"));
        item.put("status", "completed");
        return item;
    }

    private static String chatMessageContentText(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return content.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content) {
            if (part == null || !part.isObject()) {
                continue;
            }
            String text = chatTextPartText((ObjectNode) part);
            if (text == null || text.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(text);
        }
        return sb.toString();
    }

    private static ObjectNode chatUsageToResponsesUsage(ObjectNode usage) {
        ObjectNode out = MAPPER.createObjectNode();
        long input = longField(usage, "prompt_tokens", "promptTokens");
        long output = longField(usage, "completion_tokens", "completionTokens");
        long total = longField(usage, "total_tokens", "totalTokens");
        out.put("input_tokens", input);
        out.put("output_tokens", output);
        out.put("total_tokens", total > 0 ? total : input + output);
        JsonNode promptDetails = usage.get("prompt_tokens_details");
        if (promptDetails != null && promptDetails.isObject()) {
            long cached = longField(promptDetails, "cached_tokens", "cachedTokens");
            if (cached > 0) {
                ObjectNode details = MAPPER.createObjectNode();
                details.put("cached_tokens", cached);
                out.set("input_tokens_details", details);
            }
        }
        return out;
    }

    private static String convertResponsesJsonToChatCompletionJson(String responsesJson, String modelHint) throws Exception {
        JsonNode r = MAPPER.readTree(responsesJson);
        if (r == null || !r.isObject()) {
            return responsesJson;
        }
        ObjectNode ro = (ObjectNode) r;
        String text = extractResponsesAssistantText(ro);
        if (text == null) {
            return responsesJson;
        }
        String model = modelHint;
        if (model == null || model.isBlank()) {
            JsonNode m = ro.get("model");
            if (m != null && m.isTextual()) {
                model = m.asText();
            }
        }
        if (model == null) {
            model = "";
        }
        long created = System.currentTimeMillis() / 1000L;
        String id = "chatcmpl-ociworker";
        JsonNode idn = ro.get("id");
        if (idn != null && idn.isTextual() && !idn.asText().isBlank()) {
            id = idn.asText();
        }

        ObjectNode out = MAPPER.createObjectNode();
        out.put("id", id);
        out.put("object", "chat.completion");
        out.put("created", created);
        out.put("model", model);
        com.fasterxml.jackson.databind.node.ArrayNode choices = MAPPER.createArrayNode();
        ObjectNode ch = MAPPER.createObjectNode();
        ch.put("index", 0);
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", "assistant");
        msg.put("content", text);
        ch.set("message", msg);
        ch.put("finish_reason", "stop");
        choices.add(ch);
        out.set("choices", choices);
        return MAPPER.writeValueAsString(out);
    }

    private static String extractResponsesAssistantText(ObjectNode r) {
        if (r == null) {
            return null;
        }
        JsonNode ot = r.get("output_text");
        if (ot != null && ot.isTextual() && !ot.asText().isBlank()) {
            return ot.asText();
        }
        JsonNode out = r.get("output");
        if (out == null || !out.isArray()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : out) {
            if (item == null || !item.isObject()) {
                continue;
            }
            ObjectNode io = (ObjectNode) item;
            String type = textOrNull(io, "type");
            if (type != null
                    && !"message".equalsIgnoreCase(type)
                    && !"output_message".equalsIgnoreCase(type)) {
                // 仍尝试解析：有的实现会省略 type
            }
            JsonNode role = io.get("role");
            if (role != null && role.isTextual() && !"assistant".equalsIgnoreCase(role.asText())) {
                continue;
            }
            JsonNode content = io.get("content");
            if (content == null) {
                continue;
            }
            if (content.isTextual()) {
                appendText(sb, content.asText());
                continue;
            }
            if (content.isArray()) {
                for (JsonNode part : content) {
                    if (part == null || !part.isObject()) {
                        continue;
                    }
                    ObjectNode po = (ObjectNode) part;
                    String pt = textOrNull(po, "type");
                    if (pt == null) {
                        continue;
                    }
                    if ("output_text".equalsIgnoreCase(pt) || "text".equalsIgnoreCase(pt)) {
                        JsonNode tx = po.get("text");
                        if (tx != null && tx.isTextual()) {
                            appendText(sb, tx.asText());
                        }
                    }
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void appendText(StringBuilder sb, String s) {
        if (s == null || s.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n");
        }
        sb.append(s);
    }

    private static void writeChatCompletionSseFromText(
            HttpServletResponse response, String text, String modelHint) throws IOException {
        OutputStream out = response.getOutputStream();
        String id = "chatcmpl-ociworker";
        long created = System.currentTimeMillis() / 1000L;
        String model = modelHint == null ? "" : modelHint;

        // role chunk
        String first =
                "{\"id\":\""
                        + id
                        + "\",\"object\":\"chat.completion.chunk\",\"created\":"
                        + created
                        + ",\"model\":\""
                        + escapeJson(model)
                        + "\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}";
        out.write(("data: " + first + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();

        int step = 200;
        for (int i = 0; i < text.length(); i += step) {
            String part = text.substring(i, Math.min(text.length(), i + step));
            String j =
                    "{\"id\":\""
                            + id
                            + "\",\"object\":\"chat.completion.chunk\",\"created\":"
                            + created
                            + ",\"model\":\""
                            + escapeJson(model)
                            + "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                            + escapeJson(part)
                            + "\"},\"finish_reason\":null}]}";
            out.write(("data: " + j + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        String last =
                "{\"id\":\""
                        + id
                        + "\",\"object\":\"chat.completion.chunk\",\"created\":"
                        + created
                        + ",\"model\":\""
                        + escapeJson(model)
                        + "\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";
        out.write(("data: " + last + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    static String chatCompletionJsonToSse(String body, String modelHint) throws Exception {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode root = MAPPER.readTree(body);
        if (!(root instanceof ObjectNode object)) {
            return null;
        }
        JsonNode choicesNode = object.get("choices");
        if (choicesNode == null || !choicesNode.isArray()) {
            return null;
        }
        String id = firstNonBlank(textOrNull(object, "id"), "chatcmpl-ociworker");
        long created = longField(object, "created");
        if (created <= 0) {
            created = System.currentTimeMillis() / 1000L;
        }
        String model = firstNonBlank(textOrNull(object, "model"), modelHint, "");
        StringBuilder out = new StringBuilder(Math.max(256, body.length() + 128));
        int emittedChoices = 0;
        for (JsonNode choiceNode : choicesNode) {
            if (!(choiceNode instanceof ObjectNode choice)) {
                continue;
            }
            int index = (int) longField(choice, "index");
            JsonNode messageNode = choice.get("message");
            ObjectNode message = messageNode instanceof ObjectNode messageObject ? messageObject : MAPPER.createObjectNode();
            JsonNode toolCalls = message.get("tool_calls");
            boolean hasToolCalls = toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty();
            String content = chatMessageContentText(message.get("content"));
            String reasoning = textOrNull(message, "reasoning_content");

            ObjectNode roleDelta = MAPPER.createObjectNode();
            roleDelta.put("role", "assistant");
            appendChatCompletionSseChunk(out, id, created, model, index, roleDelta, null, null);

            if (reasoning != null && !reasoning.isEmpty()) {
                appendChatCompletionReasoningSse(out, id, created, model, index, reasoning);
            }
            if (content != null && !content.isEmpty()) {
                appendChatCompletionContentSse(out, id, created, model, index, content);
            }
            if (hasToolCalls) {
                ObjectNode toolDelta = MAPPER.createObjectNode();
                toolDelta.set("tool_calls", chatCompletionSseToolCalls(toolCalls));
                appendChatCompletionSseChunk(out, id, created, model, index, toolDelta, null, null);
            }

            String finishReason = hasToolCalls
                    ? "tool_calls"
                    : firstNonBlank(textOrNull(choice, "finish_reason"), "stop");
            appendChatCompletionSseChunk(out, id, created, model, index, MAPPER.createObjectNode(), finishReason, null);
            emittedChoices++;
        }
        if (emittedChoices <= 0) {
            return null;
        }
        JsonNode usage = object.get("usage");
        if (usage != null && usage.isObject()) {
            appendChatCompletionSseChunk(out, id, created, model, -1, null, null, usage);
        }
        out.append("data: [DONE]\n\n");
        return out.toString();
    }

    static String chatCompletionJsonToResponsesSse(String body, String modelHint, HttpServletRequest request) throws Exception {
        ResponsesBridgeStreamState state = new ResponsesBridgeStreamState(firstNonBlank(modelHint, ""));
        String chatSse = chatCompletionJsonToSse(body, modelHint);
        StringBuilder pending = new StringBuilder(chatSse == null ? "" : chatSse);
        StringBuilder out = new StringBuilder();
        out.append(drainChatCompletionsAsResponsesEvents(pending, request, state));
        if (!state.doneSent) {
            out.append(finalizeResponsesBridgeStream(state));
            state.doneSent = true;
            captureResponsesBridgeToolStats(request, state);
            out.append("data: [DONE]\n\n");
        }
        return out.toString();
    }

    private static ArrayNode chatCompletionSseToolCalls(JsonNode toolCalls) {
        ArrayNode out = MAPPER.createArrayNode();
        if (toolCalls == null || !toolCalls.isArray()) {
            return out;
        }
        int fallbackIndex = 0;
        for (JsonNode item : toolCalls) {
            if (!(item instanceof ObjectNode call)) {
                continue;
            }
            ObjectNode copy = call.deepCopy();
            JsonNode index = copy.get("index");
            if (index == null || !index.isNumber()) {
                copy.put("index", fallbackIndex);
            }
            fallbackIndex++;
            out.add(copy);
        }
        return out;
    }

    private static void appendChatCompletionReasoningSse(
            StringBuilder out, String id, long created, String model, int index, String reasoning) throws Exception {
        int step = 200;
        for (int i = 0; i < reasoning.length(); i += step) {
            ObjectNode delta = MAPPER.createObjectNode();
            delta.put("reasoning_content", reasoning.substring(i, Math.min(reasoning.length(), i + step)));
            appendChatCompletionSseChunk(out, id, created, model, index, delta, null, null);
        }
    }

    private static void appendChatCompletionContentSse(
            StringBuilder out, String id, long created, String model, int index, String content) throws Exception {
        int step = 200;
        for (int i = 0; i < content.length(); i += step) {
            ObjectNode delta = MAPPER.createObjectNode();
            delta.put("content", content.substring(i, Math.min(content.length(), i + step)));
            appendChatCompletionSseChunk(out, id, created, model, index, delta, null, null);
        }
    }

    private static void appendChatCompletionSseChunk(
            StringBuilder out,
            String id,
            long created,
            String model,
            int index,
            ObjectNode delta,
            String finishReason,
            JsonNode usage) throws Exception {
        ObjectNode chunk = MAPPER.createObjectNode();
        chunk.put("id", firstNonBlank(id, "chatcmpl-ociworker"));
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", model == null ? "" : model);
        ArrayNode choices = MAPPER.createArrayNode();
        if (index >= 0) {
            ObjectNode choice = MAPPER.createObjectNode();
            choice.put("index", index);
            choice.set("delta", delta == null ? MAPPER.createObjectNode() : delta);
            if (finishReason == null) {
                choice.putNull("finish_reason");
            } else {
                choice.put("finish_reason", finishReason);
            }
            choices.add(choice);
        }
        chunk.set("choices", choices);
        if (usage != null && usage.isObject()) {
            chunk.set("usage", usage.deepCopy());
        }
        out.append("data: ").append(MAPPER.writeValueAsString(chunk)).append("\n\n");
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    /**
     * 组合 OCI Generative（尤其 Multi-Agent / responses）可能要求的请求头并参与签名：
     * 优先使用入站 HTTP 头；缺省项使用租户在面板中保存的默认值（应对 New API 等不转发自定义头的情况）。
     */
    private Map<String, String> extractOciGenerativeForwardHeaders(
            HttpServletRequest request, OciUser tenant) {
        Map<String, String> out = new LinkedHashMap<>();
        if (request != null) {
            String project = firstRequestHeader(request, "OpenAI-Project", "openai-project", "X-OpenAI-Project");
            if (project != null && !project.isBlank()) {
                out.put("OpenAI-Project", project.trim());
            }
            String convStore = firstRequestHeader(request, "opc-conversation-store-id", "OPC-Conversation-Store-Id");
            if (convStore != null && !convStore.isBlank()) {
                out.put("opc-conversation-store-id", convStore.trim());
            }
        }
        if (request != null) {
            if (!out.containsKey("OpenAI-Project")) {
                putIfNotBlank(out, "OpenAI-Project",
                        stringAttr(request, OpenAiApiConstants.ATTR_GENERATIVE_OPENAI_PROJECT));
            }
            if (!out.containsKey("opc-conversation-store-id")) {
                putIfNotBlank(out, "opc-conversation-store-id",
                        stringAttr(request, OpenAiApiConstants.ATTR_GENERATIVE_CONVERSATION_STORE_ID));
            }
        }
        if (tenant != null) {
            String region = request == null ? null : stringAttr(request, OpenAiApiConstants.ATTR_OCI_REGION);
            Map<String, String> ctx = getGenerativeContext(tenant, region);
            if (!out.containsKey("OpenAI-Project")) {
                putIfNotBlank(out, "OpenAI-Project", ctx.get("generativeOpenaiProject"));
            }
            if (!out.containsKey("opc-conversation-store-id")) {
                putIfNotBlank(out, "opc-conversation-store-id", ctx.get("generativeConversationStoreId"));
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static String stringAttr(HttpServletRequest request, String name) {
        Object value = request == null ? null : request.getAttribute(name);
        return value == null ? null : String.valueOf(value);
    }

    private static String firstRequestHeader(HttpServletRequest request, String... headerNames) {
        if (request == null || headerNames == null) {
            return null;
        }
        for (String name : headerNames) {
            if (name == null) {
                continue;
            }
            String v = request.getHeader(name);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private HttpRequest buildSignedRequest(
            RequestSigner signer,
            String method,
            URI uri,
            byte[] body,
            String contentType,
            String clientAccept,
            String opcCompartmentId,
            Map<String, String> extraSignedHeaders) {
        return buildSignedRequest(signer, method, uri, body, contentType, clientAccept, opcCompartmentId,
                extraSignedHeaders, Duration.ofHours(1L));
    }

    private HttpRequest buildSignedRequest(
            RequestSigner signer,
            String method,
            URI uri,
            byte[] body,
            String contentType,
            String clientAccept,
            String opcCompartmentId,
            Map<String, String> extraSignedHeaders,
            Duration timeout) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("host", list(h(uri.getHost())));
        headers.put("accept", list(h(clientAccept)));
        // OCI 推理端点通常要求提供 compartmentId（否则 400: Compartment ID must be provided）
        if (opcCompartmentId != null && !opcCompartmentId.isBlank()) {
            headers.put("opc-compartment-id", list(opcCompartmentId));
        }
        if (extraSignedHeaders != null) {
            for (Map.Entry<String, String> e : extraSignedHeaders.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                String val = e.getValue();
                if (val == null || val.isBlank()) {
                    continue;
                }
                headers.put(e.getKey(), list(h(val.trim())));
            }
        }
        if (contentType != null && !contentType.isBlank()) {
            headers.put("content-type", list(contentType));
        } else if (body != null && body.length > 0) {
            headers.put("content-type", list("application/json"));
        }
        // OCI Java SDK 的 signer 对 body 类型有限制：
        // - byte[] 会触发 IllegalArgumentException: Unexpected body type: [B
        // - 普通 InputStream 会触发 IllegalArgumentException: Only DuplicatableInputStream supported...
        Object toSign = null;
        if (body != null && body.length > 0) {
            toSign = new OciDuplicatableByteArrayInputStream(body);
        }
        Object signedObject = signer.signRequest(uri, method, headers, toSign);
        Map<String, List<String>> signed = castSignedHeaders(signedObject);
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(uri)
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofHours(1L) : timeout);
        applyToBuilder(b, headers);
        applyToBuilder(b, signed);
        if (body == null || body.length == 0) {
            if ("GET".equalsIgnoreCase(method)) {
                return b.GET().build();
            }
            if ("HEAD".equalsIgnoreCase(method)) {
                return b.method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            }
            return b.method(method, HttpRequest.BodyPublishers.noBody()).build();
        }
        return b.method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private static String h(String s) {
        return s == null ? "" : s;
    }

    private static Map<String, List<String>> castSignedHeaders(Object signed) {
        if (signed == null) {
            return new LinkedHashMap<>();
        }
        if (signed instanceof Map<?, ?> raw) {
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (e.getValue() == null) {
                    continue;
                }
                if (e.getValue() instanceof List<?> list) {
                    List<String> ls = new ArrayList<>();
                    for (Object o : list) {
                        if (o != null) {
                            ls.add(String.valueOf(o));
                        }
                    }
                    if (!ls.isEmpty()) {
                        out.put(key, ls);
                    }
                } else if (e.getValue() instanceof String s) {
                    out.put(key, list(s));
                } else {
                    out.put(key, list(String.valueOf(e.getValue())));
                }
            }
            return out;
        }
        if (signed instanceof String) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>();
    }

    private static List<String> list(String v) {
        List<String> l = new ArrayList<>(1);
        l.add(v);
        return l;
    }

    private void applyToBuilder(HttpRequest.Builder b, Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            String name = e.getKey();
            if (name == null || isDisallowedOnHttpRequestBuilder(name)) {
                // OCI 签名需含 host 参与计算，但 java.net.http.HttpRequest 禁止手工设置
                // Host/Connection/Content-Length 等，由客户端根据 URI 与协议自动带齐。
                continue;
            }
            for (String v : e.getValue()) {
                if (v != null) {
                    b.header(name, v);
                }
            }
        }
    }

    private static boolean isDisallowedOnHttpRequestBuilder(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.equals("host")
                || n.equals("connection")
                || n.equals("content-length")
                || n.equals("expect")
                || n.equals("upgrade");
    }

    private HttpClient pickHttpClient() {
        return ociProxyConfigService == null
                ? HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(java.time.Duration.ofSeconds(30)).build()
                : ociProxyConfigService.newOutboundHttpClient();
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    static class ResponsesBridgeStreamState {
        String responseId = "resp_" + CommonUtils.generateId();
        String model;
        final long createdAt = Instant.now().getEpochSecond();
        boolean createdSent;
        boolean completedSent;
        boolean doneSent;
        int nextOutputIndex;
        int nextSequenceNumber;
        String finishReason = "stop";
        ObjectNode usage;
        String messageItemId;
        int messageOutputIndex;
        boolean contentPartOpen;
        String reasoningItemId;
        int reasoningOutputIndex;
        boolean reasoningOpen;
        boolean reasoningDone;
        final StringBuilder text = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final Map<Integer, ResponsesBridgeTool> tools = new LinkedHashMap<>();

        ResponsesBridgeStreamState(String model) {
            this.model = model == null ? "" : model;
        }
    }

    private static class ResponsesBridgeTool {
        int index;
        int outputIndex;
        String itemId;
        String callId;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    private record CachedModels(JsonNode body, Instant expiresAt) {}

    record RerankBridgeRequest(
            byte[] body,
            String originalDocumentsJson,
            boolean returnDocuments,
            String compartmentId) {}

    private record NativeGenericChatClient(
            GenerativeAiInferenceClient client,
            HttpClientConnectionManager socksPool) implements Closeable {
        @Override
        public void close() throws IOException {
            if (client != null) {
                client.close();
            }
            if (socksPool != null) {
                try {
                    socksPool.shutdown();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
