package com.ociworker.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.http.internal.ParamEncoder;
import com.oracle.bmc.http.signing.DefaultRequestSigner;
import com.oracle.bmc.http.signing.RequestSigner;
import com.oracle.bmc.ospgateway.SubscriptionServiceClient;
import com.ociworker.exception.OciException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OSP Gateway 订阅接口的签名 REST 调用，返回原始 JSON（避免 SDK 反序列化丢弃未建模字段）。
 */
public final class OspGatewayHttp {

    private static final String API_VERSION = "20191001";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OspGatewayHttp() {}

    public static JsonNode listSubscriptionsJson(
            HttpClient http,
            SubscriptionServiceClient ospClient,
            BasicAuthenticationDetailsProvider authProvider,
            String ospHomeRegion,
            String compartmentId) {
        String base = endpoint(ospClient);
        String q = query("ospHomeRegion", ospHomeRegion, "compartmentId", compartmentId);
        URI uri = URI.create(base + "/" + API_VERSION + "/subscriptions" + q);
        return signedGetJson(http, authProvider, uri);
    }

    public static JsonNode getSubscriptionJson(
            HttpClient http,
            SubscriptionServiceClient ospClient,
            BasicAuthenticationDetailsProvider authProvider,
            String ospHomeRegion,
            String compartmentId,
            String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return null;
        }
        String base = endpoint(ospClient);
        String path = "/" + API_VERSION + "/subscriptions/"
                + ParamEncoder.encodePathParam(subscriptionId.trim());
        String q = query("ospHomeRegion", ospHomeRegion, "compartmentId", compartmentId);
        URI uri = URI.create(base + path + q);
        return signedGetJson(http, authProvider, uri);
    }

    /** 从 list/get 响应中取出订阅对象节点（兼容包裹字段）。 */
    public static JsonNode unwrapSubscriptionBody(JsonNode body) {
        if (body == null || body.isNull()) {
            return null;
        }
        JsonNode sub = body.get("subscription");
        if (sub != null && !sub.isNull()) {
            return sub;
        }
        JsonNode items = body.get("items");
        if (items != null && items.isArray() && !items.isEmpty()) {
            return items.get(0);
        }
        if (body.has("id") || body.has("planType") || body.has("timeStart")) {
            return body;
        }
        return body;
    }

    private static String endpoint(SubscriptionServiceClient ospClient) {
        String endpoint = ospClient.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new OciException("OSP Gateway endpoint 为空");
        }
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    private static String query(String... kv) {
        StringBuilder sb = new StringBuilder("?");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(kv[i], StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(kv[i + 1] == null ? "" : kv[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static JsonNode signedGetJson(
            HttpClient http, BasicAuthenticationDetailsProvider authProvider, URI uri) {
        try {
            RequestSigner signer = DefaultRequestSigner.createRequestSigner(authProvider);
            Map<String, List<String>> headers = new LinkedHashMap<>();
            headers.put("accept", list("application/json"));
            headers.put("host", list(uri.getHost()));

            Map<String, List<String>> signed = castSignedHeaders(signer.signRequest(uri, "GET", headers, null));
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(45));
            applyHeaders(b, headers);
            applyHeaders(b, signed);
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 404) {
                return null;
            }
            if (code / 100 != 2) {
                throw new OciException("OSP 订阅接口失败: HTTP " + code + " " + truncate(resp.body()));
            }
            String body = resp.body();
            if (body == null || body.isBlank()) {
                return null;
            }
            return MAPPER.readTree(body);
        } catch (OciException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OciException("OSP 订阅请求中断: " + e.getMessage());
        } catch (IOException e) {
            throw new OciException("OSP 订阅请求失败: " + e.getMessage());
        }
    }

    private static List<String> list(String v) {
        List<String> l = new ArrayList<>(1);
        l.add(v);
        return l;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> castSignedHeaders(Object signed) {
        if (!(signed instanceof Map<?, ?> raw)) {
            throw new OciException("OSP 请求签名结果异常");
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object v = e.getValue();
            if (v instanceof List<?> list) {
                List<String> vals = new ArrayList<>();
                for (Object o : list) {
                    vals.add(String.valueOf(o));
                }
                out.put(key, vals);
            } else if (v != null) {
                out.put(key, list(String.valueOf(v)));
            }
        }
        return out;
    }

    private static void applyHeaders(HttpRequest.Builder builder, Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (isRestrictedHeader(e.getKey())) {
                continue;
            }
            if (e.getValue() == null) {
                continue;
            }
            for (String v : e.getValue()) {
                if (v != null) {
                    builder.header(e.getKey(), v);
                }
            }
        }
    }

    private static boolean isRestrictedHeader(String name) {
        if (name == null) {
            return false;
        }
        return "host".equalsIgnoreCase(name)
                || "connection".equalsIgnoreCase(name)
                || "content-length".equalsIgnoreCase(name)
                || "expect".equalsIgnoreCase(name)
                || "upgrade".equalsIgnoreCase(name);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }
}
