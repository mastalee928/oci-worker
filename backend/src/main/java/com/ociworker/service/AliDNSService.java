package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ociworker.enums.SysCfgEnum;
import com.ociworker.exception.OciException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
public class AliDNSService {

    private static final String DNS_API = "https://alidns.aliyuncs.com";

    @Resource
    private NotificationService notificationService;

    private String getAccessKeyId() {
        return notificationService.getKvValue(SysCfgEnum.ALIDNS_ACCESS_KEY_ID);
    }

    private String getAccessKeySecret() {
        return notificationService.getKvValue(SysCfgEnum.ALIDNS_ACCESS_KEY_SECRET);
    }

    public boolean isConfigured() {
        String ak = getAccessKeyId();
        String sk = getAccessKeySecret();
        return StrUtil.isNotBlank(ak) && StrUtil.isNotBlank(sk);
    }

    public void saveAccountConfig(String accessKeyId, String accessKeySecret) {
        notificationService.saveKvValue(SysCfgEnum.ALIDNS_ACCESS_KEY_ID, StrUtil.trimToEmpty(accessKeyId));
        notificationService.saveKvValue(SysCfgEnum.ALIDNS_ACCESS_KEY_SECRET, StrUtil.trimToEmpty(accessKeySecret));
    }

    public Map<String, Object> getAccountConfigForDisplay() {
        String ak = getAccessKeyId();
        boolean configured = StrUtil.isNotBlank(ak);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("configured", configured);
        r.put("accessKeyId", configured ? ak.substring(0, Math.min(8, ak.length())) + "****" : null);
        return r;
    }

    public String testAccountConfig(String accessKeyId, String accessKeySecret) {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("Action", "DescribeDomains");
            params.put("Version", "2015-01-09");
            params.put("AccessKeyId", accessKeyId);
            params.put("SignatureMethod", "HMAC-SHA1");
            params.put("SignatureVersion", "1.0");
            params.put("SignatureNonce", UUID.randomUUID().toString());
            params.put("Timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC)));
            params.put("Format", "JSON");
            params.put("PageNumber", "1");
            params.put("PageSize", "1");

            String signature = sign(params, accessKeySecret, "GET");
            params.put("Signature", signature);

            String url = DNS_API + "?" + buildQuery(params);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            JSONObject json = JSONUtil.parseObj(resp.body());
            if (json.containsKey("Domain") || json.containsKey("Domains")) {
                return "连接成功";
            }
            if (json.containsKey("Code")) {
                throw new OciException("阿里云DNS测试失败: " + json.getStr("Message"));
            }
            return "连接成功";
        } catch (Exception e) {
            throw new OciException("阿里云DNS测试失败: " + e.getMessage());
        }
    }

    private String sign(Map<String, String> params, String secret, String method) throws Exception {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        sb.append(method).append("&%2F&");
        boolean first = true;
        for (String k : keys) {
            if (!first) sb.append("%26");
            first = false;
            sb.append(percentEncode(k)).append("%3D").append(percentEncode(params.get(k)));
        }
        String stringToSign = sb.toString();
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec((secret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signData);
    }

    private String percentEncode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    private String buildQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append("&");
            first = false;
            sb.append(e.getKey()).append("=").append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    public List<Map<String, Object>> listDomains(int page, int perPage) {
        if (!isConfigured()) throw new OciException("阿里云DNS未配置");
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("Action", "DescribeDomains");
            params.put("Version", "2015-01-09");
            params.put("AccessKeyId", getAccessKeyId());
            params.put("SignatureMethod", "HMAC-SHA1");
            params.put("SignatureVersion", "1.0");
            params.put("SignatureNonce", UUID.randomUUID().toString());
            params.put("Timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC)));
            params.put("Format", "JSON");
            params.put("PageNumber", String.valueOf(page));
            params.put("PageSize", String.valueOf(perPage));

            String signature = sign(params, getAccessKeySecret(), "GET");
            params.put("Signature", signature);

            String url = DNS_API + "?" + buildQuery(params);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            JSONObject json = JSONUtil.parseObj(resp.body());
            if (json.containsKey("Code")) {
                throw new OciException(json.getStr("Message", "查询失败"));
            }
            JSONArray arr = json.getJSONObject("Domains").getJSONArray("Domain");
            List<Map<String, Object>> list = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject d = arr.getJSONObject(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("domainId", d.getStr("DomainId"));
                m.put("domainName", d.getStr("DomainName"));
                m.put("punyCode", d.getStr("PunyCode"));
                m.put("recordCount", d.getInt("RecordCount"));
                list.add(m);
            }
            return list;
        } catch (Exception e) {
            throw new OciException("查询域名失败: " + e.getMessage());
        }
    }
}
