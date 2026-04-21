package com.ociworker.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.http.internal.ParamEncoder;
import com.oracle.bmc.http.signing.DefaultRequestSigner;
import com.oracle.bmc.http.signing.RequestSigner;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.ociworker.exception.OciException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OCI Java SDK 3.8x 已从 {@link ObjectStorageClient} 中移除桶策略专用 API，此处通过签名 REST 调用实现
 * {@code PUT /20160918/n/{ns}/b/{bucket}/policy}（与控制台/REST 一致）。
 */
public final class ObjectStorageBucketPolicyHttp {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectStorageBucketPolicyHttp() {
    }

    public static void putBucketPolicy(
            ObjectStorageClient objectStorageClient,
            BasicAuthenticationDetailsProvider authProvider,
            String namespace,
            String bucketName,
            String policy) {
        if (namespace == null || namespace.isBlank() || bucketName == null || bucketName.isBlank()) {
            throw new OciException("namespace / bucketName 不能为空");
        }
        if (policy == null) {
            throw new OciException("policy 不能为空");
        }
        String endpoint = objectStorageClient.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new OciException("Object Storage endpoint 为空");
        }
        if (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        String path = "/20160918/n/"
                + ParamEncoder.encodePathParam(namespace)
                + "/b/"
                + ParamEncoder.encodePathParam(bucketName)
                + "/policy";
        URI uri = URI.create(endpoint + path);

        RequestSigner signer = DefaultRequestSigner.createRequestSigner(authProvider);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String ifMatch = null;
        try {
            Map<String, List<String>> getHeaders = new LinkedHashMap<>();
            getHeaders.put("accept", list("application/json"));
            getHeaders.put("host", list(uri.getHost()));

            Map<String, List<String>> signedGet = castSignedHeaders(signer.signRequest(uri, "GET", getHeaders, null));
            HttpRequest.Builder getB = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(30));
            applyHeaders(getB, getHeaders);
            applyHeaders(getB, signedGet);
            HttpRequest getReq = getB.build();
            HttpResponse<String> getResp = http.send(getReq, HttpResponse.BodyHandlers.ofString());
            int gs = getResp.statusCode();
            if (gs == 200) {
                ifMatch = getResp.headers().firstValue("etag").orElse(null);
            } else if (gs != 404) {
                throw new OciException("读取桶策略失败: HTTP " + gs + " " + truncate(getResp.body()));
            }

            byte[] bodyBytes = MAPPER.writeValueAsBytes(Map.of("policy", policy));

            Map<String, List<String>> putHeaders = new LinkedHashMap<>();
            putHeaders.put("accept", list("application/json"));
            putHeaders.put("content-type", list("application/json"));
            putHeaders.put("host", list(uri.getHost()));
            if (ifMatch != null && !ifMatch.isBlank()) {
                putHeaders.put("if-match", list(ifMatch));
            } else {
                putHeaders.put("if-none-match", list("*"));
            }

            Map<String, List<String>> signedPut = castSignedHeaders(signer.signRequest(uri, "PUT", putHeaders, bodyBytes));
            HttpRequest.Builder putB = HttpRequest.newBuilder(uri)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .timeout(Duration.ofSeconds(60));
            applyHeaders(putB, putHeaders);
            applyHeaders(putB, signedPut);
            HttpRequest putReq = putB.build();
            HttpResponse<String> putResp = http.send(putReq, HttpResponse.BodyHandlers.ofString());
            if (putResp.statusCode() / 100 != 2) {
                throw new OciException("保存桶策略失败: HTTP " + putResp.statusCode() + " " + truncate(putResp.body()));
            }
        } catch (OciException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OciException("保存桶策略失败: " + e.getMessage());
        } catch (IOException e) {
            throw new OciException("保存桶策略失败: " + e.getMessage());
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
            throw new OciException("签名结果格式异常");
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

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
