package com.ociworker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.http.signing.DefaultRequestSigner;
import com.oracle.bmc.http.signing.RequestSigner;
import com.ociworker.exception.OciException;
import com.ociworker.model.entity.OciUser;
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
 * 经 OCI IAM 签名将请求转发至 Generative AI OpenAI 兼容端点。
 * Base: https://inference.generativeai.&lt;region&gt;.oci.oraclecloud.com/openai/v1
 */
@Slf4j
@Service
public class OciGenerativeOpenAiService {

    public static final int DEFAULT_MAX_TOKENS = 4000;
    private static final String V1 = "/v1";
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
        StringBuilder b = new StringBuilder(
                "https://inference.generativeai." + regionId + ".oci.oraclecloud.com/openai/v1/models");
        if (modelId != null && !modelId.isBlank()) {
            b.append("/");
            b.append(encodePathSegmentOciModel(modelId));
        } else if (after != null && !after.isBlank()) {
            b.append("?after=").append(java.net.URLEncoder.encode(after, StandardCharsets.UTF_8));
        }
        URI uri = URI.create(b.toString());
        RequestSigner signer = newRequestSigner(tenant);
        HttpRequest req = buildSignedRequest(signer, "GET", uri, null, null, "application/json");
        HttpClient c = pickHttpClient();
        HttpResponse<String> resp = c.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new OciException("拉取 models 失败: HTTP " + resp.statusCode() + " " + truncate(resp.body(), 500));
        }
        return MAPPER.readTree(resp.body());
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
        SimpleAuthenticationDetailsProvider p = buildProvider(tenant);
        if (p instanceof BasicAuthenticationDetailsProvider) {
            return DefaultRequestSigner.createRequestSigner((BasicAuthenticationDetailsProvider) p);
        }
        return DefaultRequestSigner.createRequestSigner((BasicAuthenticationDetailsProvider) (Object) p);
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

    private void longCopyStream(HttpClient client, HttpRequest httpRequest, HttpServletResponse response) {
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

    private void bufferAndCopy(HttpClient client, HttpRequest httpRequest, HttpServletResponse response) {
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
        if (body != null && body.length > 0) {
            String ct = (contentType != null && !contentType.isBlank()) ? contentType : "application/json";
            headers.put("content-type", list(ct));
        }
        Object toSign = body;
        if (toSign != null && ((byte[]) toSign).length == 0) {
            toSign = null;
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
            for (String v : e.getValue()) {
                if (v != null) {
                    b.header(e.getKey(), v);
                }
            }
        }
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
