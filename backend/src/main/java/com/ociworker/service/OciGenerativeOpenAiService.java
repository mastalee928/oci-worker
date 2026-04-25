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
        String regionId = OciRegionUtil.publicRegionId(tenant.getOciRegion());
        String base = "https://inference.generativeai." + regionId + ".oci.oraclecloud.com/openai/v1";
        String query = request.getQueryString();
        StringBuilder u = new StringBuilder(base);
        u.append(pathAfterV1);
        if (query != null && !query.isEmpty()) {
            u.append("?").append(query);
        }
        URI target = URI.create(u.toString());

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

        if (isChatCompletionsPath(pathAfterV1) && body != null && body.length > 0
                && contentType != null && contentType.toLowerCase().contains("json")) {
            body = transformChatCompletionsJson(body);
        }

        HttpRequest httpRequest = buildSignedRequest(signer, method, target, body, contentType, accept);
        HttpClient client = pickHttpClient();

        if (isChatCompletionsPath(pathAfterV1) && isStreamRequest(body, contentType)) {
            longCopyStream(client, httpRequest, response);
        } else {
            bufferAndCopy(client, httpRequest, response);
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
                    newRequestSigner(tenant), "GET", listUri, null, "application/json", "application/json");
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
        HttpRequest req = buildSignedRequest(signer, "GET", uri, null, "application/json", "application/json");
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
        return row;
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

    private void bufferAndCopy(HttpClient client, HttpRequest httpRequest, HttpServletResponse response)
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

    private HttpRequest buildSignedRequest(
            RequestSigner signer, String method, URI uri, byte[] body, String contentType, String clientAccept) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("host", list(h(uri.getHost())));
        headers.put("accept", list(h(clientAccept)));
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
            toSign = new com.oracle.bmc.http.internal.WrappedByteArrayInputStream(body);
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
