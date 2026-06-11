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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AliDNSService {

    private static final String DNS_API = "https://alidns.aliyuncs.com";
    private static final String API_VERSION = "2015-01-09";

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

        if (StrUtil.isBlank(accessKeyId)) {
            throw new OciException("AccessKeyId cannot be empty");
        }

        if (StrUtil.isBlank(accessKeySecret)) {
            throw new OciException("AccessKeySecret cannot be empty");
        }

        notificationService.saveKvValue(
                SysCfgEnum.ALIDNS_ACCESS_KEY_ID,
                accessKeyId.trim()
        );

        notificationService.saveKvValue(
                SysCfgEnum.ALIDNS_ACCESS_KEY_SECRET,
                accessKeySecret.trim()
        );
    }

    private String maskAccessKey(String accessKeyId) {

        if (StrUtil.isBlank(accessKeyId)) {
            return "";
        }

        if (accessKeyId.length() <= 8) {
            return "****";
        }

        return accessKeyId.substring(0, 4)
                + "********"
                + accessKeyId.substring(accessKeyId.length() - 4);
    }

    public Map<String, Object> getAccountConfigForDisplay() {
        String accessKeyId = getAccessKeyId();
        String accessKeySecret = getAccessKeySecret();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", StrUtil.isNotBlank(accessKeyId) && StrUtil.isNotBlank(accessKeySecret));
        result.put("accessKeyId", maskAccessKey(accessKeyId));
        result.put("secretConfigured", StrUtil.isNotBlank(accessKeySecret));
        return result;
    }

    public String testAccountConfig(String accessKeyId, String accessKeySecret) {
        JSONObject json = request("DescribeDomains", Map.of(
                "PageNumber", "1",
                "PageSize", "1"
        ), accessKeyId, accessKeySecret);
        if (json != null && (json.containsKey("Domains") || json.containsKey("Domain"))) {
            return "SUCCESS";
        }
        return "FAILED";
    }

    public Map<String, Object> listDomains(int page, int perPage) {
        JSONObject json = request("DescribeDomains", Map.of(
                "PageNumber", String.valueOf(Math.max(page, 1)),
                "PageSize", String.valueOf(Math.max(perPage, 1))
        ), getAccessKeyId(), getAccessKeySecret());
        
        List<Map<String, Object>> records = new ArrayList<>();
        
        if (json != null && json.getJSONObject("Domains") != null) {
            JSONArray domains = json.getJSONObject("Domains").getJSONArray("Domain");
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
                    records.add(item);
                }
            }
        }
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        if (json != null) {
            result.put("total", json.getInt("TotalCount", records.size()));
            result.put("page", json.getInt("PageNumber", page));
            result.put("perPage", json.getInt("PageSize", perPage));
        } else {
            result.put("total", records.size());
            result.put("page", page);
            result.put("perPage", perPage);
        }
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
        putIfNotBlank(params, "Line", normalizeLine(line));

        JSONObject json = request("DescribeDomainRecords", params, getAccessKeyId(), getAccessKeySecret());
        List<Map<String, Object>> records = new ArrayList<>();

        if (json != null && json.getJSONObject("DomainRecords") != null) {
            JSONArray array = json.getJSONObject("DomainRecords").getJSONArray("Record");
            if (array != null) {
                for (int i = 0; i < array.size(); i++) {
                    JSONObject row = array.getJSONObject(i);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("recordId", row.getStr("RecordId"));
                    item.put("domainName", row.getStr("DomainName"));
                    item.put("rr", row.getStr("RR"));
                    item.put("type", row.getStr("Type"));
                    item.put("value", row.getStr("Value"));
                    item.put("ttl", row.getLong("TTL", 600L));
                    item.put("line", row.getStr("Line"));
                    item.put("status", row.getStr("Status"));
                    item.put("locked", row.getBool("Locked", false));
                    records.add(item);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        if (json != null) {
            result.put("total", json.getInt("TotalCount", records.size()));
            result.put("page", json.getInt("PageNumber", page));
            result.put("perPage", json.getInt("PageSize", perPage));
        } else {
            result.put("total", records.size());
            result.put("page", page);
            result.put("perPage", perPage);
        }
        return result;
    }

    private void requireDomain(String domainName) {
        if (domainName == null || domainName.trim().isEmpty()) {
            throw new IllegalArgumentException("Domain name cannot be empty");
        }
    }

    private void putIfNotBlank(Map<String, String> params, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            params.put(key, value.trim());
        }
    }

    private String normalizeLine(String line) {
        return (line == null || line.trim().isEmpty()) ? "default" : line.trim();
    }

    private JSONObject request(String action, Map<String, String> params, String ak, String sk) {
        // 請保留您原本檔案頂部那些加密（Mac/SecretKeySpec）、HttpClient 請求的實作程式碼
        return new JSONObject(); 
    }
}