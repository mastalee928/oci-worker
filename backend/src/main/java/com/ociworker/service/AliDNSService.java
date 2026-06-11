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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class AliDNSService {

    private static final String DNS_API = "https://alidns.aliyuncs.com";
    private static final String API_VERSION = "2015-01-09";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    @Resource
    private NotificationService notificationService;

    private String getAccessKeyId() {
        return notificationService.getKvValue(SysCfgEnum.ALIDNS_ACCESS_KEY_ID);
    }

    private String getAccessKeySecret() {
        return notificationService.getKvValue(SysCfgEnum.ALIDNS_ACCESS_KEY_SECRET);
    }

    public boolean isConfigured() {
        return StrUtil.isNotBlank(getAccessKeyId()) && StrUtil.isNotBlank(getAccessKeySecret());
    }

    public void saveAccountConfig(String accessKeyId, String accessKeySecret) {
        if (StrUtil.isNotBlank(accessKeyId)) {
            notificationService.saveKvValue(SysCfgEnum.ALIDNS_ACCESS_KEY_ID, accessKeyId.trim());
        }
        if (StrUtil.isNotBlank(accessKeySecret)) {
            notificationService.saveKvValue(SysCfgEnum.ALIDNS_ACCESS_KEY_SECRET, accessKeySecret.trim());
        }
    }

    public Map<String, Object> getAccountConfigForDisplay() {
        String accessKeyId = getAccessKeyId();
        String accessKeySecret = getAccessKeySecret();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", StrUtil.isNotBlank(accessKeyId) && StrUtil.isNotBlank(accessKeySecret));
        result.put("accessKeyId", StrUtil.nullToEmpty(accessKeyId));
        result.put("secretConfigured", StrUtil.isNotBlank(accessKeySecret));
        return result;
    }

    public String testAccountConfig(String accessKeyId, String accessKeySecret) {
        JSONObject json = request("DescribeDomains", Map.of(
                "PageNumber", "1",
                "PageSize", "1"
        ), accessKeyId, accessKeySecret);
        if (json.containsKey("Domains") || json.containsKey("Domain")) {
            return "连接成功";
        }
        return "连接失败";
    }

    public Map<String, Object> listDomains(int page, int perPage) {
        JSONObject json = request("DescribeDomains", Map.of(
                "PageNumber", String.valueOf(Math.max(page, 1)),
                "PageSize", String.valueOf(Math.max(perPage, 1))
        ));
        JSONArray domains = json.getJSONObject("Domains") != null
                ? json.getJSONObject("Domains").getJSONArray("Domain")
                : new JSONArray();
        List<Map<String, Object>> records = new ArrayList<>();
        if (domains != null) {
            for (int i = 0; i < domains.size(); i++) {
                JSONObject row = domains.getJSONObject(i);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("domainId", row.getStr("DomainId"));
                item.put("domainName", row.getStr("DomainName"));
                item.put("punyCode", row.getStr("PunyCode"));
                item.put("groupId", row.getStr("GroupId"));
                item.put("groupName", row.getStr("GroupName"));
                item.put("recordCount", row.getInt("RecordCount", 0));
                item.put("versionName", row.getStr("VersionName"));
                item.put("dnsStatus", getDomainDnsStatus(row.getStr("DomainName")));
                records.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", json.getInt("TotalCount", records.size()));
        result.put("page", json.getInt("PageNumber", page));
        result.put("perPage", json.getInt("PageSize", perPage));
        return result;
    }

    public Map<String, Object> listRecords(String domainName, String rrKeyWord, String typeKeyWord,
                                           String valueKeyWord, String line, int page, int perPage) {
        requireDomain(domainName);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("DomainName", domainName.trim());
        params.put("PageNumber", String.valueOf(Math.max(page, 1)));
        params.put("PageSize", String.valueOf(Math.max(perPage, 1)));
        putIfNotBlank(params, "RRKeyWord", rrKeyWord);
        putIfNotBlank(params, "TypeKeyWord", typeKeyWord);
        putIfNotBlank(params, "ValueKeyWord", valueKeyWord);
        putIfNotBlank(params, "Line", line);
        JSONObject json = request("DescribeDomainRecords", params);
        JSONArray array = json.getJSONObject("DomainRecords") != null
                ? json.getJSONObject("DomainRecords").getJSONArray("Record")
                : new JSONArray();
        List<Map<String, Object>> records = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                records.add(mapRecord(array.getJSONObject(i)));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", json.getInt("TotalCount", records.size()));
        result.put("page", json.getInt("PageNumber", page));
        result.put("perPage", json.getInt("PageSize", perPage));
        return result;
    }

    public Map<String, Object> addRecord(Map<String, Object> input) {
        Map<String, String> params = buildRecordParams(input, false);
        JSONObject json = request("AddDomainRecord", params);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", json.getStr("RecordId"));
        return result;
    }

    public Map<String, Object> updateRecord(Map<String, Object> input) {
        String recordId = parseString(input.get("recordId"));
        if (StrUtil.isBlank(recordId)) {
            throw new OciException("记录ID不能为空");
        }
        Map<String, String> params = buildRecordParams(input, true);
        params.put("DomainName", parseString(input.get("domainName")).trim());
        JSONObject json = request("UpdateDomainRecord", params);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", json.getStr("RecordId"));
        return result;
    }

    public void deleteRecord(String recordId) {
        if (StrUtil.isBlank(recordId)) {
            throw new OciException("记录ID不能为空");
        }
        request("DeleteDomainRecord", Map.of("RecordId", recordId.trim()));
    }

    public Map<String, Object> setRecordStatus(String recordId, String status) {
        if (StrUtil.isBlank(recordId)) {
            throw new OciException("记录ID不能为空");
        }
        String normalized = "DISABLE".equalsIgnoreCase(status)  ? "DISABLE" : "ENABLE";
        JSONObject json = request("SetDomainRecordStatus", Map.of(
                "RecordId", recordId.trim(),
                "Status", normalized
        ));
        return mapRecord(json);
    }

    public List<Map<String, Object>> listDomainDnsServers(String domainName) {
        requireDomain(domainName);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("DomainName", domainName.trim());
        JSONObject json = requestPost("DescribeDomainDnsServers", params);
        JSONArray servers = json.getJSONArray("DnsServers");
        if (servers == null && json.getJSONObject("DnsServers") != null) {
            servers = json.getJSONObject("DnsServers").getJSONArray("DnsServer");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        if (servers != null) {
            for (int i = 0; i < servers.size(); i++) {
                JSONObject srv = servers.getJSONObject(i);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("server", srv.getStr("Server"));
                item.put("status", srv.getStr("Status"));
                result.add(item);
            }
        }
        return result;
    }

    public List<Map<String, Object>> listSupportLines(String domainName, String domainType) {
        Map<String, String> params = new LinkedHashMap<>();
        // DescribeSupportLines only accepts DomainType (PUBLIC/PRIVATE), not DomainName
        putIfNotBlank(params, "DomainType", domainType);
        JSONObject json = request("DescribeSupportLines", params);
        Object recordLinesObj = json.get("RecordLines");
        JSONArray lines = null;
        if (recordLinesObj instanceof JSONArray) {
            lines = (JSONArray) recordLinesObj;
        } else if (recordLinesObj instanceof JSONObject) {
            lines = ((JSONObject) recordLinesObj).getJSONArray("RecordLine");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        if (lines != null) {
            for (int i = 0; i < lines.size(); i++) {
                JSONObject line = lines.getJSONObject(i);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("lineCode", firstNonBlank(line.getStr("LineCode"), line.getStr("LineCodeEn"), line.getStr("Code")));
                item.put("lineName", firstNonBlank(line.getStr("LineName"), line.getStr("LineDisplayName"), line.getStr("Name")));
                item.put("fatherCode", line.getStr("FatherCode"));
                item.put("lineDisplayName", firstNonBlank(line.getStr("LineDisplayName"), line.getStr("LineName")));
                result.add(item);
            }
        }
        if (result.isEmpty()) {
            result.add(defaultLine("default", "默认"));
            result.add(defaultLine("telecom", "中国电信"));
            result.add(defaultLine("unicom", "中国联通"));
            result.add(defaultLine("mobile", "中国移动"));
            result.add(defaultLine("edu", "教育网"));
            result.add(defaultLine("oversea", "海外"));
        }
        return result;
    }

    private JSONObject request(String action, Map<String, String> actionParams) {
        return request(action, actionParams, null, null);
    }

    private JSONObject request(String action, Map<String, String> actionParams,
                               String accessKeyIdOverride, String accessKeySecretOverride) {
        String accessKeyId = StrUtil.blankToDefault(StrUtil.trimToNull(accessKeyIdOverride), getAccessKeyId());
        String accessKeySecret = StrUtil.blankToDefault(StrUtil.trimToNull(accessKeySecretOverride), getAccessKeySecret());
        if (StrUtil.isBlank(accessKeyId) || StrUtil.isBlank(accessKeySecret)) {
            throw new OciException("阿里云DNS未配置");
        }
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("Action", action);
            params.put("Version", API_VERSION);
            params.put("AccessKeyId", accessKeyId);
            params.put("SignatureMethod", "HMAC-SHA1");
            params.put("SignatureVersion", "1.0");
            params.put("SignatureNonce", UUID.randomUUID().toString());
            params.put("Timestamp", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC).format(Instant.now()));
            params.put("Format", "JSON");
            if (actionParams != null) {
                params.putAll(actionParams);
            }
            params.put("Signature", sign(params, accessKeySecret, "GET"));
            String url = DNS_API + "?" + buildQuery(params);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject json = JSONUtil.parseObj(response.body());
            String code = json.getStr("Code"); if (code != null && !"200".equals(code)) {
                throw new OciException(json.getStr("Message", "阿里云DNS调用失败"));
            }
            return json;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("阿里云DNS调用异常: " + e.getMessage());
        }
    }

    private Map<String, String> buildRecordParams(Map<String, Object> input, boolean update) {
        String domainName = parseString(input.get("domainName"));
        String rr = parseString(input.get("rr"));
        String type = parseString(input.get("type"));
        String value = parseString(input.get("value"));
        if (!update) {
            requireDomain(domainName);
        }
        if (StrUtil.isBlank(rr)) {
            throw new OciException("请填写主机记录");
        }
        if (StrUtil.isBlank(type)) {
            throw new OciException("请填写记录类型");
        }
        if (StrUtil.isBlank(value)) {
            throw new OciException("请填写记录值");
        }
        Map<String, String> params = new LinkedHashMap<>();
        if (!update) {
            params.put("DomainName", domainName.trim());
        }
        params.put("RR", rr.trim());
        if (!update) { params.put("Type", type.trim().toUpperCase()); }
        params.put("Value", value.trim());
        params.put("Line", normalizeLine(parseString(input.get("line"))));
        putIfNotBlank(params, "Lang", parseString(input.get("lang")));
        Integer ttl = parseInteger(input.get("ttl"));
        if (ttl != null && ttl > 0) {
            params.put("TTL", String.valueOf(ttl));
        }
        Integer priority = parseInteger(input.get("priority"));
        if (priority != null && priority >= 0 && type != null && supportsPriority(type)) {
            params.put("Priority", String.valueOf(priority));
        }
        return params;
    }

    private Map<String, Object> mapRecord(JSONObject row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("recordId", row.getStr("RecordId"));
        item.put("domainName", row.getStr("DomainName"));
        item.put("rr", row.getStr("RR"));
        item.put("type", row.getStr("Type"));
        item.put("value", row.getStr("Value"));
        item.put("line", row.getStr("Line"));
        item.put("lineName", firstNonBlank(row.getStr("LineName"), row.getStr("Line")));
        item.put("ttl", row.getInt("TTL"));
        item.put("priority", row.getInt("Priority"));
        item.put("status", row.getStr("Status"));
        Boolean locked = row.getBool("Locked");
        item.put("locked", locked != null && locked);
        item.put("weight", row.getInt("Weight"));
        item.put("remark", row.getStr("Remark"));
        return item;
    }

    private String sign(Map<String, String> params, String secret, String method) throws Exception {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder canonical = new StringBuilder();
        boolean first = true;
        for (String key : keys) {
            if (!first) {
                canonical.append("&");
            }
            first = false;
            canonical.append(percentEncode(key)).append("=").append(percentEncode(params.get(key)));
        }
        String stringToSign = method + "&%2F&" + percentEncode(canonical.toString());
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec((secret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
    }

    private String percentEncode(String value) {
        return URLEncoder.encode(StrUtil.nullToEmpty(value), StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private String buildQuery(Map<String, String> params) {
        StringBuilder query = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                query.append("&");
            }
            first = false;
            query.append(percentEncode(entry.getKey())).append("=").append(percentEncode(entry.getValue()));
        }
        return query.toString();
    }

    private void requireDomain(String domainName) {
        if (StrUtil.isBlank(domainName)) {
            throw new OciException("请输入域名");
        }
    }

    private void putIfNotBlank(Map<String, String> params, String key, String value) {
        if (StrUtil.isNotBlank(value)) {
            params.put(key, value.trim());
        }
    }

    private String normalizeLine(String line) {
        return StrUtil.blankToDefault(StrUtil.trimToNull(line), "default");
    }

    private boolean supportsPriority(String type) {
        return "MX".equalsIgnoreCase(type) || "SRV".equalsIgnoreCase(type);
    }

    private String parseString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer parseInteger(Object value) {
        if (value == null || StrUtil.isBlank(String.valueOf(value))) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> defaultLine(String code, String name) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("lineCode", code);
        line.put("lineName", name);
        line.put("lineDisplayName", name);
        return line;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }


    private JSONObject requestPost(String action, Map<String, String> actionParams) {
        String ak = StrUtil.blankToDefault(StrUtil.trimToNull(getAccessKeyId()), "");
        String sk = StrUtil.blankToDefault(StrUtil.trimToNull(getAccessKeySecret()), "");
        if (StrUtil.isBlank(ak) || StrUtil.isBlank(sk)) {
            throw new OciException("阿里云DNS未配置");
        }
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("Action", action);
            params.put("Version", API_VERSION);
            params.put("AccessKeyId", ak);
            params.put("SignatureMethod", "HMAC-SHA1");
            params.put("SignatureVersion", "1.0");
            params.put("SignatureNonce", UUID.randomUUID().toString());
            params.put("Timestamp", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC).format(Instant.now()));
            params.put("Format", "JSON");
            if (actionParams != null) params.putAll(actionParams);
            params.put("Signature", sign(params, sk, "POST"));
            StringBuilder body = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!first) body.append("&");
                first = false;
                body.append(percentEncode(e.getKey())).append("=").append(percentEncode(e.getValue()));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(DNS_API))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject json = JSONUtil.parseObj(response.body());
            String code = json.getStr("Code");
            if (code != null && !"200".equals(code)) {
                throw new OciException(json.getStr("Message", "阿里云DNS调用失败"));
            }
            return json;
        } catch (OciException e) {
            throw e;
            throw new OciException("阿里云DNS调用异常: " + e.getMessage());
        }
    }

    private String getDomainDnsStatus(String domainName) {
        try {
            JSONObject json = requestPost("DescribeDomainDnsServers", Map.of("DomainName", domainName));
            JSONArray srvList = json.getJSONArray("DnsServers");
            if (srvList == null && json.getJSONObject("DnsServers") != null) {
                srvList = json.getJSONObject("DnsServers").getJSONArray("DnsServer");
            }
            if (srvList != null) {
                for (int i = 0; i < srvList.size(); i++) {
                    JSONObject srv = srvList.getJSONObject(i);
                    String server = srv != null ? srv.getStr("Server") : null;
                    if (server != null && (server.contains("alidns") || server.contains("hichina"))) {
                        return "normal";
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return "not_system";
    }
}
