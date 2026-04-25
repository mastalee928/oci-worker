package com.ociworker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.http.signing.DefaultRequestSigner;
import com.oracle.bmc.http.signing.RequestSigner;
import com.ociworker.exception.OciException;
import com.ociworker.model.entity.OciUser;
import com.ociworker.util.OciBasicForSigning;
import com.ociworker.util.OciDuplicatableByteArrayInputStream;
import com.ociworker.util.OciRegionUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经 OCI IAM 签名将请求转发至 Generative AI OpenAI 兼容端点（推理面）。
 * Base: https://inference.generativeai.&lt;region&gt;.oci.oraclecloud.com/openai/v1
 * 模型列表来自管理面 ListModels（generativeai.&lt;region&gt;），因推理面通常不注册 {@code /openai/v1/models}。
 */
@Slf4j
@Service
public class OciGenerativeOpenAiService {

    public static final int DEFAULT_MAX_TOKENS = 4000;
    private static final String V1 = "/v1";
    private static final String GA_API_VERSION = "20231130";
    private static final int LIST_PAGE_LIMIT = 200;
    private static final int LIST_MAX_PAGES = 50;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private OciProxyConfigService ociProxyConfigService;

    public void proxy(OciUser tenant, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String pathAfterV1 = extractPathAfterV1(request);
        if (pathAfterV1 == null || pathAfterV1.isEmpty() || pathAfterV1.equals("/")) {
            pathAfterV1 = "/";
        }
        if (!pathAfterV1.startsWith("/")) {
            pathAfterV1 = "/" + pathAfterV1;
        }
        final String origPathAfterV1 = pathAfterV1;
        String regionId = OciRegionUtil.publicRegionId(tenant.getOciRegion());
        String base = "https://inference.generativeai." + regionId + ".oci.oraclecloud.com/openai/v1";
        String query = request.getQueryString();

        RequestSigner signer = newRequestSigner(tenant);

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

        // OCI：Multi Agent 模型不允许走 /v1/chat/completions，需要改走 /v1/responses
        if ("POST".equalsIgnoreCase(method)
                && isChatCompletionsPath(origPathAfterV1)
                && origBody != null
                && origBody.length > 0
                && contentType != null
                && contentType.toLowerCase().contains("json")) {
            try {
                JsonNode root = MAPPER.readTree(origBody);
                if (root != null && root.isObject()) {
                    String model = textOrNull(((ObjectNode) root), "model");
                    if (isLikelyMultiAgentModelName(model)) {
                        if (isStreamRequest(origBody, contentType)) {
                            // OCI 侧 Multi Agent 不适用于 chat-completions 流式；本网关会改走 /v1/responses 并关闭 stream。
                            log.debug(
                                    "Multi Agent 模型在 chat/completions 上收到 stream=true，将改写为 /v1/responses 且按非流式处理");
                        }
                        request.setAttribute("ociworker.rewrite.chatToResponses", Boolean.TRUE);
                        if (model != null) {
                            request.setAttribute("ociworker.rewrite.model", model);
                        }
                        pathAfterV1 = "/responses";
                        body = transformChatCompletionsToResponsesJson(origBody);
                        // responses 与 chat completions 的补默认策略不同，这里让下游按 OCI 行为处理
                    } else if (isChatCompletionsPath(origPathAfterV1)) {
                        body = transformChatCompletionsJson(origBody);
                    }
                } else if (isChatCompletionsPath(origPathAfterV1)) {
                    body = transformChatCompletionsJson(origBody);
                }
            } catch (Exception e) {
                if (isChatCompletionsPath(origPathAfterV1) && origBody != null) {
                    body = transformChatCompletionsJson(origBody);
                }
            }
        } else if (isChatCompletionsPath(origPathAfterV1) && body != null && body.length > 0
                && contentType != null && contentType.toLowerCase().contains("json")) {
            body = transformChatCompletionsJson(body);
        }

        StringBuilder u = new StringBuilder(base);
        u.append(pathAfterV1);
        if (query != null && !query.isEmpty()) {
            u.append("?").append(query);
        }
        URI target = URI.create(u.toString());

        HttpRequest httpRequest = buildSignedRequest(
                signer,
                method,
                target,
                body,
                contentType,
                accept,
                tenant != null ? tenant.getOciTenantId() : null,
                extractOciGenerativeForwardHeaders(request, tenant));
        HttpClient client = pickHttpClient();

        boolean useStreamCopy =
                isChatCompletionsPath(origPathAfterV1)
                        && isStreamRequest(origBody, contentType)
                        && !Boolean.TRUE.equals(request.getAttribute("ociworker.rewrite.chatToResponses"));
        if (useStreamCopy) {
            longCopyStream(client, httpRequest, response);
        } else {
            bufferAndCopy(client, httpRequest, response, request);
        }
    }

    public JsonNode getModelsAsJson(OciUser tenant) throws Exception {
        return getModelsAsJson(tenant, null, null);
    }

    public JsonNode getModelsAsJson(OciUser tenant, String after, String modelId) throws Exception {
        String regionId = OciRegionUtil.publicRegionId(tenant.getOciRegion());
        String managementHost = "generativeai." + regionId + ".oci.oraclecloud.com";
        String tenantId = tenant.getOciTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new OciException("租户无 ociTenantId，无法 list models");
        }
        if (modelId != null && !modelId.isBlank()) {
            String path = "/" + GA_API_VERSION + "/models/" + encodePathSegmentOciModel(modelId);
            return managementGetToOpenAiList(tenant, "https://" + managementHost + path, true);
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
                    newRequestSigner(tenant),
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
        String regionId = OciRegionUtil.publicRegionId(tenant.getOciRegion());
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
                    newRequestSigner(tenant),
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
        String regionId = OciRegionUtil.publicRegionId(tenant.getOciRegion());
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
                newRequestSigner(tenant),
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

    private static ArrayNode toArrayNode(List<JsonNode> nodes) {
        ArrayNode a = MAPPER.createArrayNode();
        for (JsonNode n : nodes) {
            a.add(n);
        }
        return a;
    }

    private JsonNode managementGetToOpenAiList(OciUser tenant, String url, boolean oneItemAsList) throws Exception {
        RequestSigner signer = newRequestSigner(tenant);
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
        // 推理/Chat 的 model 字段优先用服务侧 name（如 cohere.command ），否则用资源 OCID
        String id = firstText(oci, "name");
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
        JsonNode display = oci.get("displayName");
        if (display != null && display.isTextual() && !display.asText().isBlank()) {
            row.put("displayName", display.asText());
        }
        // 管理面 ListModels 会返回多种“模型产品形态”，不保证都适用于 OpenAI 兼容的 /v1/chat/completions
        if (isLikelyMultiAgentModelName(id) || (display != null && display.isTextual() && isLikelyMultiAgentModelName(display.asText()))) {
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
        return t.contains("multi-agent") || t.contains("multi agent") || t.contains("multiagent");
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
        if (tenant == null) {
            throw new OciException("租户无效");
        }
        return SimpleAuthenticationDetailsProvider.builder()
                .tenantId(tenant.getOciTenantId())
                .userId(tenant.getOciUserId())
                .fingerprint(tenant.getOciFingerprint())
                .region(OciRegionUtil.toRegion(tenant.getOciRegion()))
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
        return DefaultRequestSigner.createRequestSigner(
                OciBasicForSigning.from(buildProvider(tenant)));
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

    private static boolean isStreamRequest(byte[] body, String contentType) {
        if (body == null || contentType == null || !contentType.toLowerCase().contains("json")) {
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

    private static byte[] transformChatCompletionsJson(byte[] input) {
        try {
            JsonNode root = MAPPER.readTree(input);
            if (root == null || !root.isObject()) {
                return input;
            }
            ObjectNode o = (ObjectNode) root;
            if (o.get("max_tokens") == null || o.get("max_tokens").isNull() || o.get("max_tokens").isMissingNode()) {
                o.put("max_tokens", DEFAULT_MAX_TOKENS);
            }
            JsonNode force = o.get("force_non_stream");
            if (force != null && (force.isBoolean() && force.asBoolean()
                    || (force.isTextual() && "true".equalsIgnoreCase(force.asText())))) {
                o.put("stream", false);
            }
            o.remove("force_non_stream");
            return MAPPER.writeValueAsBytes(o);
        } catch (Exception e) {
            return input;
        }
    }

    private static byte[] transformChatCompletionsToResponsesJson(byte[] input) {
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
                com.fasterxml.jackson.databind.node.ArrayNode inputArr = MAPPER.createArrayNode();
                for (JsonNode m : messages) {
                    if (m != null && m.isObject()) {
                        inputArr.add(m);
                    }
                }
                out.set("input", inputArr);
            } else {
                // 兼容极少数不规范请求：没有 messages 时，尽可能把 prompt 当 input
                JsonNode p = in.get("prompt");
                if (p != null && p.isTextual()) {
                    out.put("input", p.asText());
                }
            }
            JsonNode mt = in.get("max_tokens");
            if (mt != null && !mt.isNull() && !mt.isMissingNode()) {
                if (mt.isNumber()) {
                    out.put("max_output_tokens", mt.intValue());
                } else {
                    out.put("max_output_tokens", mt.asInt(DEFAULT_MAX_TOKENS));
                }
            } else {
                out.put("max_output_tokens", DEFAULT_MAX_TOKENS);
            }
            JsonNode temp = in.get("temperature");
            if (temp != null && !temp.isNull() && !temp.isMissingNode()) {
                out.set("temperature", temp);
            }
            JsonNode topP = in.get("top_p");
            if (topP != null && !topP.isNull() && !topP.isMissingNode()) {
                out.set("top_p", topP);
            }
            // responses API 的流式事件与 chat_completions 不同，默认关闭
            out.put("stream", false);
            return MAPPER.writeValueAsBytes(out);
        } catch (Exception e) {
            return input;
        }
    }

    private void longCopyStream(HttpClient client, HttpRequest httpRequest, HttpServletResponse response)
            throws IOException {
        try {
            HttpResponse<InputStream> resp = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            int code = resp.statusCode();
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
                try (InputStream in = resp.body()) {
                    if (in != null) {
                        in.transferTo(response.getOutputStream());
                    }
                }
                return;
            }
            if (response.getContentType() == null) {
                String ct = resp.headers().firstValue("content-type").orElse("text/event-stream; charset=utf-8");
                response.setContentType(ct);
            }
            try (InputStream in = resp.body();
                 OutputStream out = response.getOutputStream()) {
                if (in == null) {
                    return;
                }
                response.setBufferSize(8192);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OciException("流式请求中断");
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Broken pipe")) {
                return;
            }
            throw e;
        }
    }

    private void bufferAndCopy(
            HttpClient client, HttpRequest httpRequest, HttpServletResponse response, HttpServletRequest request)
            throws IOException {
        try {
            HttpResponse<String> resp = client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = resp.statusCode();
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
                        b = convertResponsesJsonToChatCompletionJson(b, modelHint);
                        response.setContentType("application/json; charset=utf-8");
                    } catch (Exception ignored) {
                    }
                }
            }
            response.getOutputStream().write(b.getBytes(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OciException("请求中断");
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Broken pipe")) {
                return;
            }
            throw e;
        }
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

    /**
     * 组合 OCI Generative（尤其 Multi-Agent / responses）可能要求的请求头并参与签名：
     * 优先使用入站 HTTP 头；缺省项使用租户在面板中保存的默认值（应对 New API 等不转发自定义头的情况）。
     */
    private static Map<String, String> extractOciGenerativeForwardHeaders(
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
        if (tenant != null) {
            if (!out.containsKey("OpenAI-Project")
                    && tenant.getGenerativeOpenaiProject() != null
                    && !tenant.getGenerativeOpenaiProject().isBlank()) {
                out.put("OpenAI-Project", tenant.getGenerativeOpenaiProject().trim());
            }
            if (!out.containsKey("opc-conversation-store-id")
                    && tenant.getGenerativeConversationStoreId() != null
                    && !tenant.getGenerativeConversationStoreId().isBlank()) {
                out.put("opc-conversation-store-id", tenant.getGenerativeConversationStoreId().trim());
            }
        }
        return out.isEmpty() ? null : out;
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
                .timeout(java.time.Duration.ofHours(1L));
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
}
