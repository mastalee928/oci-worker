package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ociworker.enums.SysCfgEnum;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.CfCfgMapper;
import com.ociworker.model.entity.CfCfg;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class CloudflareService {

    private static final String CF_API_BASE = "https://api.cloudflare.com/client/v4";

    @Resource
    private CfCfgMapper cfCfgMapper;
    @Resource
    private NotificationService notificationService;
    @Lazy
    @Resource
    private OciProxyConfigService ociProxyConfigService;

    // -------------------------------------------------------------------------
    // Global account config (oci_kv)
    // -------------------------------------------------------------------------

    public Map<String, Object> getAccountConfigForDisplay() {
        String accountId = notificationService.getKvValue(SysCfgEnum.CF_ACCOUNT_ID);
        String token = notificationService.getKvValue(SysCfgEnum.CF_API_TOKEN);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountId", StrUtil.nullToEmpty(accountId));
        m.put("tokenConfigured", StrUtil.isNotBlank(token));
        m.put("apiToken", StrUtil.isBlank(token) ? "" : maskSecret(token));
        m.put("configured", StrUtil.isNotBlank(accountId) && StrUtil.isNotBlank(token));
        return m;
    }

    public void saveAccountConfig(String accountId, String apiTokenFromClient) {
        String curToken = notificationService.getKvValue(SysCfgEnum.CF_API_TOKEN);
        if (StrUtil.isNotBlank(accountId)) {
            notificationService.saveKvValue(SysCfgEnum.CF_ACCOUNT_ID, accountId.trim());
        }
        String token = resolveMasked(apiTokenFromClient, curToken);
        if (StrUtil.isNotBlank(token)) {
            notificationService.saveKvValue(SysCfgEnum.CF_API_TOKEN, token.trim());
        }
    }

    public String testAccountConfig(String accountId, String apiTokenFromClient) {
        String acc = StrUtil.trimToNull(accountId);
        if (acc == null) {
            acc = StrUtil.trimToNull(notificationService.getKvValue(SysCfgEnum.CF_ACCOUNT_ID));
        }
        String token = resolveMasked(apiTokenFromClient, notificationService.getKvValue(SysCfgEnum.CF_API_TOKEN));
        if (StrUtil.isBlank(token)) {
            throw new OciException("请先填写 API Token");
        }
        verifyApiToken(token, acc);
        if (acc != null) {
            JSONObject accJson = parseJson(apiGet(token, CF_API_BASE + "/accounts/" + acc));
            requireSuccess(accJson, "Account ID 无效或无权限");
            JSONObject result = accJson.getJSONObject("result");
            String name = result != null ? result.getStr("name") : acc;
            return "连接成功：Token 有效，账户 " + name;
        }
        return "连接成功：Token 有效";
    }

    /** 账户 API 令牌（cfat_）与用户 API 令牌使用不同的 verify 端点。 */
    private void verifyApiToken(String token, String accountId) {
        if (isAccountApiToken(token)) {
            if (StrUtil.isBlank(accountId)) {
                throw new OciException("账户 API 令牌（cfat_）测试需填写 Account ID");
            }
            JSONObject verify = parseJson(apiGet(token,
                    CF_API_BASE + "/accounts/" + accountId.trim() + "/tokens/verify"));
            requireSuccess(verify, "Token 验证失败");
            return;
        }
        JSONObject verify = parseJson(apiGet(token, CF_API_BASE + "/user/tokens/verify"));
        requireSuccess(verify, "Token 验证失败");
    }

    private static boolean isAccountApiToken(String token) {
        return token != null && token.startsWith("cfat_");
    }

    // -------------------------------------------------------------------------
    // Zones
    // -------------------------------------------------------------------------

    public Map<String, Object> listZonesPage(int page, int perPage) {
        Credentials c = requireCredentials();
        String url = String.format("%s/zones?page=%d&per_page=%d&account.id=%s",
                CF_API_BASE, page, perPage, c.accountId());
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取 Zone 列表失败");
        JSONArray result = json.getJSONArray("result");
        List<Map<String, Object>> zones = new ArrayList<>();
        if (result != null) {
            for (int i = 0; i < result.size(); i++) {
                zones.add(mapZoneSummary(result.getJSONObject(i)));
            }
        }
        JSONObject info = json.getJSONObject("result_info");
        int total = info != null ? info.getInt("total_count", zones.size()) : zones.size();
        int totalPages = info != null ? info.getInt("total_pages", 1) : 1;
        int curPage = info != null ? info.getInt("page", page) : page;
        int curPerPage = info != null ? info.getInt("per_page", perPage) : perPage;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", zones);
        out.put("total", total);
        out.put("page", curPage);
        out.put("perPage", curPerPage);
        out.put("totalPages", totalPages);
        return out;
    }

    /** 向后兼容：仅返回 records 列表 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listZones(int page, int perPage) {
        return (List<Map<String, Object>>) listZonesPage(page, perPage).get("records");
    }

    public Map<String, Object> getZoneDetail(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = CF_API_BASE + "/zones/" + zoneId.trim();
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取 Zone 详情失败");
        JSONObject z = json.getJSONObject("result");
        if (z == null) {
            throw new OciException("Zone 不存在");
        }
        Map<String, Object> map = mapZoneSummary(z);
        JSONArray ns = z.getJSONArray("name_servers");
        List<String> nameServers = new ArrayList<>();
        if (ns != null) {
            for (int i = 0; i < ns.size(); i++) {
                nameServers.add(ns.getStr(i));
            }
        }
        map.put("nameServers", nameServers);
        JSONObject plan = z.getJSONObject("plan");
        map.put("planName", plan != null ? plan.getStr("name") : null);
        return map;
    }

    public Map<String, Object> createZone(String name) {
        Credentials c = requireCredentials();
        if (StrUtil.isBlank(name)) {
            throw new OciException("域名不能为空");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name.trim());
        body.put("account", Map.of("id", c.accountId()));
        String url = CF_API_BASE + "/zones";
        JSONObject json = parseJson(apiPost(c.apiToken(), url, body));
        requireSuccess(json, "创建 Zone 失败");
        JSONObject result = json.getJSONObject("result");
        if (result == null) {
            throw new OciException("创建 Zone 失败：无返回数据");
        }
        return mapZoneSummary(result);
    }

    public void deleteZone(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = CF_API_BASE + "/zones/" + zoneId.trim();
        apiDelete(c.apiToken(), url);
    }

    public Map<String, Object> setZonePaused(String zoneId, boolean paused) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = CF_API_BASE + "/zones/" + zoneId.trim();
        JSONObject json = parseJson(apiPatch(c.apiToken(), url, Map.of("paused", paused)));
        requireSuccess(json, paused ? "暂停 Zone 失败" : "恢复 Zone 失败");
        JSONObject result = json.getJSONObject("result");
        return result != null ? mapZoneSummary(result) : Map.of("paused", paused);
    }

    private static Map<String, Object> mapZoneSummary(JSONObject z) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", z.getStr("id"));
        map.put("name", z.getStr("name"));
        map.put("status", z.getStr("status"));
        map.put("paused", z.getBool("paused"));
        return map;
    }

    // -------------------------------------------------------------------------
    // Tunnels (cloudflared)
    // -------------------------------------------------------------------------

    public List<Map<String, Object>> listTunnels() {
        Credentials c = requireCredentials();
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/cfd_tunnel?per_page=50";
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取 Tunnel 列表失败");
        JSONArray result = json.getJSONArray("result");
        List<Map<String, Object>> tunnels = new ArrayList<>();
        if (result == null) {
            return tunnels;
        }
        for (int i = 0; i < result.size(); i++) {
            JSONObject t = result.getJSONObject(i);
            tunnels.add(mapTunnel(t));
        }
        return tunnels;
    }

    public Map<String, Object> createTunnel(String name) {
        Credentials c = requireCredentials();
        if (StrUtil.isBlank(name)) {
            throw new OciException("Tunnel 名称不能为空");
        }
        Map<String, Object> body = Map.of(
                "name", name.trim(),
                "config_src", "cloudflare");
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/cfd_tunnel";
        JSONObject json = parseJson(apiPost(c.apiToken(), url, body));
        requireSuccess(json, "创建 Tunnel 失败");
        JSONObject result = json.getJSONObject("result");
        if (result == null) {
            throw new OciException("创建 Tunnel 失败：无返回数据");
        }
        Map<String, Object> out = mapTunnel(result);
        out.put("token", result.getStr("token"));
        return out;
    }

    public void deleteTunnel(String tunnelId) {
        Credentials c = requireCredentials();
        if (StrUtil.isBlank(tunnelId)) {
            throw new OciException("Tunnel ID 不能为空");
        }
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/cfd_tunnel/" + tunnelId.trim();
        apiDelete(c.apiToken(), url);
    }

    public String getTunnelRunToken(String tunnelId) {
        Credentials c = requireCredentials();
        if (StrUtil.isBlank(tunnelId)) {
            throw new OciException("Tunnel ID 不能为空");
        }
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/cfd_tunnel/" + tunnelId.trim() + "/token";
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "获取 Tunnel Token 失败");
        String token = json.getStr("result");
        if (StrUtil.isBlank(token)) {
            throw new OciException("Tunnel Token 为空");
        }
        return token;
    }

    public List<Map<String, Object>> listTunnelConnections(String tunnelId) {
        Credentials c = requireCredentials();
        if (StrUtil.isBlank(tunnelId)) {
            throw new OciException("Tunnel ID 不能为空");
        }
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/cfd_tunnel/" + tunnelId.trim() + "/connections";
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取连接状态失败");
        JSONArray result = json.getJSONArray("result");
        List<Map<String, Object>> list = new ArrayList<>();
        if (result == null) {
            return list;
        }
        for (int i = 0; i < result.size(); i++) {
            JSONObject conn = result.getJSONObject(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("coloName", conn.getStr("colo_name"));
            m.put("uuid", conn.getStr("uuid"));
            m.put("isPendingReconnect", conn.getBool("is_pending_reconnect"));
            m.put("openedAt", conn.getStr("opened_at"));
            m.put("originIp", conn.getStr("origin_ip"));
            list.add(m);
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // Legacy per-zone cfg (cf_cfg) — kept for compatibility
    // -------------------------------------------------------------------------

    public Page<CfCfg> listCfgPage(int current, int size) {
        return cfCfgMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<CfCfg>().orderByDesc(CfCfg::getCreateTime));
    }

    public void addCfg(CfCfg cfg) {
        cfg.setId(CommonUtils.generateId());
        cfg.setCreateTime(LocalDateTime.now());
        cfCfgMapper.insert(cfg);
    }

    public void removeCfg(String id) {
        cfCfgMapper.deleteById(id);
    }

    // -------------------------------------------------------------------------
    // DNS (global token + zoneId)
    // -------------------------------------------------------------------------

    private static final Set<String> DNS_TYPES = Set.of(
            "A", "AAAA", "CNAME", "TXT", "MX", "NS", "SRV", "CAA", "HTTPS", "PTR");

    public Map<String, Object> listDnsRecordsPage(String zoneId, int page, int perPage,
                                                   String search, String type) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        StringBuilder url = new StringBuilder(String.format("%s/zones/%s/dns_records?page=%d&per_page=%d",
                CF_API_BASE, zoneId.trim(), page, perPage));
        if (StrUtil.isNotBlank(search)) {
            url.append("&name.contains=").append(URLEncoder.encode(search.trim(), StandardCharsets.UTF_8));
        }
        if (StrUtil.isNotBlank(type)) {
            url.append("&type=").append(URLEncoder.encode(type.trim().toUpperCase(), StandardCharsets.UTF_8));
        }
        JSONObject json = parseJson(apiGet(c.apiToken(), url.toString()));
        requireSuccess(json, "拉取 DNS 记录失败");
        JSONArray result = json.getJSONArray("result");
        List<Map<String, Object>> records = new ArrayList<>();
        if (result != null) {
            for (int i = 0; i < result.size(); i++) {
                records.add(mapDnsRecord(result.getJSONObject(i)));
            }
        }
        JSONObject info = json.getJSONObject("result_info");
        int total = info != null ? info.getInt("total_count", records.size()) : records.size();
        int totalPages = info != null ? info.getInt("total_pages", 1) : 1;
        int curPage = info != null ? info.getInt("page", page) : page;
        int curPerPage = info != null ? info.getInt("per_page", perPage) : perPage;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", records);
        out.put("total", total);
        out.put("page", curPage);
        out.put("perPage", curPerPage);
        out.put("totalPages", totalPages);
        return out;
    }

    /** 向后兼容：仅返回 records 列表 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listDnsRecords(String zoneId, int page, int perPage) {
        return (List<Map<String, Object>>) listDnsRecordsPage(zoneId, page, perPage, null, null).get("records");
    }

    private static Map<String, Object> mapDnsRecord(JSONObject r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.getStr("id"));
        map.put("type", r.getStr("type"));
        map.put("name", r.getStr("name"));
        map.put("content", r.getStr("content"));
        map.put("proxied", r.getBool("proxied"));
        map.put("ttl", r.getInt("ttl"));
        if (r.containsKey("priority")) {
            map.put("priority", r.getInt("priority"));
        }
        if (r.containsKey("comment")) {
            map.put("comment", r.getStr("comment"));
        }
        return map;
    }

    private Map<String, Object> buildDnsBody(String type, String name, String content,
                                             Boolean proxied, Integer ttl,
                                             Integer priority, String comment) {
        if (StrUtil.isBlank(type)) {
            throw new OciException("DNS 记录类型不能为空");
        }
        String upperType = type.trim().toUpperCase();
        if (!DNS_TYPES.contains(upperType)) {
            throw new OciException("不支持的 DNS 记录类型: " + upperType);
        }
        if (StrUtil.isBlank(name)) {
            throw new OciException("DNS 记录名称不能为空");
        }
        if (StrUtil.isBlank(content)) {
            throw new OciException("DNS 记录内容不能为空");
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", upperType);
        b.put("name", name.trim());
        b.put("content", content.trim());
        b.put("ttl", ttl != null ? ttl : 1);
        if (proxied != null && Set.of("A", "AAAA", "CNAME").contains(upperType)) {
            b.put("proxied", proxied);
        }
        if (priority != null && Set.of("MX", "SRV").contains(upperType)) {
            b.put("priority", priority);
        }
        if (StrUtil.isNotBlank(comment)) {
            b.put("comment", comment.trim());
        }
        return b;
    }

    public void addDnsRecord(String zoneId, String type, String name, String content,
                             Boolean proxied, Integer ttl, Integer priority, String comment) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = String.format("%s/zones/%s/dns_records", CF_API_BASE, zoneId.trim());
        Map<String, Object> b = buildDnsBody(type, name, content, proxied, ttl, priority, comment);
        JSONObject json = parseJson(apiPost(c.apiToken(), url, b));
        requireSuccess(json, "添加 DNS 记录失败");
    }

    public void deleteDnsRecord(String zoneId, String recordId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        if (StrUtil.isBlank(recordId)) {
            throw new OciException("DNS 记录 ID 不能为空");
        }
        String url = String.format("%s/zones/%s/dns_records/%s", CF_API_BASE, zoneId.trim(), recordId.trim());
        apiDelete(c.apiToken(), url);
    }

    public void updateDnsRecord(String zoneId, String recordId, String type, String name,
                                String content, Boolean proxied, Integer ttl,
                                Integer priority, String comment) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        if (StrUtil.isBlank(recordId)) {
            throw new OciException("DNS 记录 ID 不能为空");
        }
        String url = String.format("%s/zones/%s/dns_records/%s", CF_API_BASE, zoneId.trim(), recordId.trim());
        Map<String, Object> b = buildDnsBody(type, name, content, proxied, ttl, priority, comment);
        JSONObject json = parseJson(apiPut(c.apiToken(), url, b));
        requireSuccess(json, "更新 DNS 记录失败");
    }

    public String exportDnsRecords(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/dns_records/export";
        return apiGet(c.apiToken(), url);
    }

    public void importDnsRecords(String zoneId, String bindContent, Boolean proxied) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        if (StrUtil.isBlank(bindContent)) {
            throw new OciException("BIND 文件内容不能为空");
        }
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/dns_records/import";
        Map<String, String> fields = new LinkedHashMap<>();
        if (proxied != null) {
            fields.put("proxied", proxied.toString());
        }
        JSONObject json = parseJson(apiPostMultipart(c.apiToken(), url, "file", "zone.txt",
                bindContent, fields));
        requireSuccess(json, "导入 DNS 记录失败");
    }

    public Map<String, Object> getDnssec(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/dnssec";
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取 DNSSEC 状态失败");
        JSONObject result = json.getJSONObject("result");
        Map<String, Object> m = new LinkedHashMap<>();
        if (result != null) {
            m.put("status", result.getStr("status"));
            m.put("algorithm", result.getStr("algorithm"));
            m.put("digest", result.getStr("digest"));
            m.put("digestAlgorithm", result.getStr("digest_algorithm"));
            m.put("digestType", result.getStr("digest_type"));
            m.put("ds", result.getStr("ds"));
            m.put("flags", result.getInt("flags"));
            m.put("keyTag", result.getInt("key_tag"));
            m.put("keyType", result.getStr("key_type"));
            m.put("modifiedOn", result.getStr("modified_on"));
            m.put("publicKey", result.getStr("public_key"));
        }
        return m;
    }

    public Map<String, Object> setDnssec(String zoneId, String status) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        if (StrUtil.isBlank(status)) {
            throw new OciException("DNSSEC 状态不能为空");
        }
        String normalized = status.trim().toLowerCase();
        if (!"active".equals(normalized) && !"disabled".equals(normalized)) {
            throw new OciException("DNSSEC 状态仅支持 active 或 disabled");
        }
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/dnssec";
        JSONObject json = parseJson(apiPatch(c.apiToken(), url, Map.of("status", normalized)));
        requireSuccess(json, "更新 DNSSEC 状态失败");
        return getDnssec(zoneId);
    }

    // -------------------------------------------------------------------------
    // Email Routing
    // -------------------------------------------------------------------------

    public Map<String, Object> getEmailRoutingSettings(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/email/routing";
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取 Email Routing 设置失败");
        JSONObject result = json.getJSONObject("result");
        return mapEmailSettings(result);
    }

    public Map<String, Object> enableEmailRouting(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/email/routing/enable";
        JSONObject json = parseJson(apiPost(c.apiToken(), url, Map.of()));
        requireSuccess(json, "启用 Email Routing 失败");
        return mapEmailSettings(json.getJSONObject("result"));
    }

    public void disableEmailRouting(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/email/routing/disable";
        JSONObject json = parseJson(apiPost(c.apiToken(), url, Map.of()));
        requireSuccess(json, "禁用 Email Routing 失败");
    }

    public List<Map<String, Object>> getEmailRoutingDns(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/email/routing/dns";
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取 Email Routing DNS 记录失败");
        JSONArray result = json.getJSONArray("result");
        List<Map<String, Object>> list = new ArrayList<>();
        if (result == null) {
            return list;
        }
        for (int i = 0; i < result.size(); i++) {
            JSONObject r = result.getJSONObject(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getStr("id"));
            m.put("type", r.getStr("type"));
            m.put("name", r.getStr("name"));
            m.put("content", r.getStr("content"));
            m.put("priority", r.getInt("priority"));
            m.put("ttl", r.getInt("ttl"));
            m.put("proxied", r.getBool("proxied"));
            m.put("locked", r.getBool("locked"));
            list.add(m);
        }
        return list;
    }

    public void lockEmailDns(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/email/routing/dns";
        JSONObject json = parseJson(apiPost(c.apiToken(), url, Map.of()));
        requireSuccess(json, "锁定 Email Routing DNS 失败");
    }

    public void unlockEmailDns(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/email/routing/dns";
        JSONObject json = parseJson(apiPatch(c.apiToken(), url, Map.of()));
        requireSuccess(json, "解锁 Email Routing DNS 失败");
    }

    public List<Map<String, Object>> listEmailRoutingRules(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/email/routing/rules";
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取邮件路由规则失败");
        JSONArray result = json.getJSONArray("result");
        List<Map<String, Object>> list = new ArrayList<>();
        if (result == null) {
            return list;
        }
        for (int i = 0; i < result.size(); i++) {
            list.add(mapEmailRule(result.getJSONObject(i)));
        }
        return list;
    }

    public Map<String, Object> createEmailRoutingRule(String zoneId, String name, String customAddress,
                                                      String actionType, List<String> destinations,
                                                      String workerName, Integer priority, Boolean enabled) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        if (StrUtil.isBlank(customAddress)) {
            throw new OciException("自定义地址不能为空");
        }
        String act = StrUtil.blankToDefault(actionType, "forward").trim().toLowerCase();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", StrUtil.blankToDefault(name, customAddress.trim()));
        body.put("enabled", enabled == null || enabled);
        if (priority != null) {
            body.put("priority", priority);
        }
        body.put("matchers", List.of(Map.of(
                "type", "literal",
                "field", "to",
                "value", customAddress.trim())));
        body.put("actions", List.of(buildEmailAction(act, destinations, workerName)));
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/email/routing/rules";
        JSONObject json = parseJson(apiPost(c.apiToken(), url, body));
        requireSuccess(json, "创建邮件路由规则失败");
        return mapEmailRule(json.getJSONObject("result"));
    }

    /** 向后兼容：单目标转发 */
    public Map<String, Object> createEmailRoutingRule(String zoneId, String name, String customAddress,
                                                      String destination, boolean enabled) {
        List<String> dests = StrUtil.isBlank(destination) ? List.of() : List.of(destination.trim());
        return createEmailRoutingRule(zoneId, name, customAddress, "forward", dests, null, null, enabled);
    }

    public void deleteEmailRoutingRule(String zoneId, String ruleId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        if (StrUtil.isBlank(ruleId)) {
            throw new OciException("规则 ID 不能为空");
        }
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/email/routing/rules/" + ruleId.trim();
        apiDelete(c.apiToken(), url);
    }

    public Map<String, Object> updateEmailRoutingRule(String zoneId, String ruleId, String name,
                                                      String customAddress, String actionType,
                                                      List<String> destinations, String workerName,
                                                      Boolean enabled, Integer priority) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        if (StrUtil.isBlank(ruleId)) {
            throw new OciException("规则 ID 不能为空");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(name)) {
            body.put("name", name.trim());
        }
        if (enabled != null) {
            body.put("enabled", enabled);
        }
        if (priority != null) {
            body.put("priority", priority);
        }
        if (StrUtil.isNotBlank(customAddress)) {
            body.put("matchers", List.of(Map.of(
                    "type", "literal",
                    "field", "to",
                    "value", customAddress.trim())));
        }
        if (StrUtil.isNotBlank(actionType)) {
            body.put("actions", List.of(buildEmailAction(actionType.trim().toLowerCase(),
                    destinations, workerName)));
        }
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/email/routing/rules/" + ruleId.trim();
        JSONObject json = parseJson(apiPut(c.apiToken(), url, body));
        requireSuccess(json, "更新邮件路由规则失败");
        return mapEmailRule(json.getJSONObject("result"));
    }

    public Map<String, Object> getCatchAllRule(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/email/routing/rules/catch_all";
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取 Catch-All 规则失败");
        JSONObject result = json.getJSONObject("result");
        return result != null ? mapEmailRule(result) : Map.of();
    }

    public Map<String, Object> updateCatchAllRule(String zoneId, String actionType,
                                                  List<String> destinations, String workerName,
                                                  Boolean enabled) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        if (StrUtil.isBlank(actionType)) {
            throw new OciException("Catch-All 动作类型不能为空");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled == null || enabled);
        body.put("actions", List.of(buildEmailAction(actionType.trim().toLowerCase(),
                destinations, workerName)));
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/email/routing/rules/catch_all";
        JSONObject json = parseJson(apiPut(c.apiToken(), url, body));
        requireSuccess(json, "更新 Catch-All 规则失败");
        JSONObject result = json.getJSONObject("result");
        return result != null ? mapEmailRule(result) : Map.of();
    }

    public List<Map<String, Object>> listEmailDestinations() {
        Credentials c = requireCredentials();
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/email/routing/addresses";
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取目标邮箱失败");
        JSONArray result = json.getJSONArray("result");
        List<Map<String, Object>> list = new ArrayList<>();
        if (result == null) {
            return list;
        }
        for (int i = 0; i < result.size(); i++) {
            list.add(mapEmailDestination(result.getJSONObject(i)));
        }
        return list;
    }

    public Map<String, Object> createEmailDestination(String email) {
        Credentials c = requireCredentials();
        if (StrUtil.isBlank(email)) {
            throw new OciException("邮箱地址不能为空");
        }
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/email/routing/addresses";
        JSONObject json = parseJson(apiPost(c.apiToken(), url, Map.of("email", email.trim())));
        requireSuccess(json, "添加目标邮箱失败");
        return mapEmailDestination(json.getJSONObject("result"));
    }

    public void deleteEmailDestination(String destinationId) {
        Credentials c = requireCredentials();
        if (StrUtil.isBlank(destinationId)) {
            throw new OciException("目标邮箱 ID 不能为空");
        }
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/email/routing/addresses/" + destinationId.trim();
        apiDelete(c.apiToken(), url);
    }

    public Map<String, Object> resendEmailDestination(String email) {
        Credentials c = requireCredentials();
        if (StrUtil.isBlank(email)) {
            throw new OciException("邮箱地址不能为空");
        }
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/email/routing/addresses";
        JSONObject json = parseJson(apiPost(c.apiToken(), url, Map.of("email", email.trim())));
        requireSuccess(json, "重发验证邮件失败");
        return mapEmailDestination(json.getJSONObject("result"));
    }

    public List<Map<String, Object>> listWorkers() {
        Credentials c = requireCredentials();
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/workers/scripts";
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取 Workers 列表失败");
        JSONArray result = json.getJSONArray("result");
        List<Map<String, Object>> list = new ArrayList<>();
        if (result == null) {
            return list;
        }
        for (int i = 0; i < result.size(); i++) {
            JSONObject w = result.getJSONObject(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", w.getStr("id"));
            m.put("createdOn", w.getStr("created_on"));
            m.put("modifiedOn", w.getStr("modified_on"));
            list.add(m);
        }
        return list;
    }

    private static Map<String, Object> buildEmailAction(String actionType, List<String> destinations,
                                                        String workerName) {
        return switch (actionType) {
            case "drop" -> Map.of("type", "drop");
            case "worker" -> {
                if (StrUtil.isBlank(workerName)) {
                    throw new OciException("Worker 动作需指定 workerName");
                }
                yield Map.of("type", "worker", "value", List.of(workerName.trim()));
            }
            case "forward" -> {
                if (destinations == null || destinations.isEmpty()) {
                    throw new OciException("转发动作需指定至少一个目标邮箱");
                }
                List<String> cleaned = new ArrayList<>();
                for (String d : destinations) {
                    if (StrUtil.isNotBlank(d)) {
                        cleaned.add(d.trim());
                    }
                }
                if (cleaned.isEmpty()) {
                    throw new OciException("转发动作需指定至少一个目标邮箱");
                }
                yield Map.of("type", "forward", "value", cleaned);
            }
            default -> throw new OciException("不支持的邮件路由动作: " + actionType);
        };
    }

    private static Map<String, Object> mapEmailSettings(JSONObject s) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (s == null) {
            return m;
        }
        m.put("id", s.getStr("id"));
        m.put("enabled", s.getBool("enabled"));
        m.put("name", s.getStr("name"));
        m.put("status", s.getStr("status"));
        m.put("created", s.getStr("created"));
        m.put("modified", s.getStr("modified"));
        return m;
    }

    private static Map<String, Object> mapEmailRule(JSONObject r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getStr("id"));
        m.put("name", r.getStr("name"));
        m.put("enabled", r.getBool("enabled"));
        m.put("priority", r.getInt("priority"));
        String customAddress = null;
        JSONArray matchers = r.getJSONArray("matchers");
        if (matchers != null) {
            for (int i = 0; i < matchers.size(); i++) {
                JSONObject matcher = matchers.getJSONObject(i);
                if ("literal".equals(matcher.getStr("type")) && "to".equals(matcher.getStr("field"))) {
                    customAddress = matcher.getStr("value");
                    break;
                }
            }
        }
        m.put("customAddress", customAddress);
        List<String> destinations = new ArrayList<>();
        String actionType = "forward";
        String workerName = null;
        JSONArray actions = r.getJSONArray("actions");
        if (actions != null && !actions.isEmpty()) {
            JSONObject action = actions.getJSONObject(0);
            String type = action.getStr("type");
            if ("drop".equals(type)) {
                actionType = "drop";
            } else if ("worker".equals(type)) {
                actionType = "worker";
                JSONArray values = action.getJSONArray("value");
                if (values != null && !values.isEmpty()) {
                    workerName = values.getStr(0);
                }
            } else if ("forward".equals(type)) {
                actionType = "forward";
                JSONArray values = action.getJSONArray("value");
                if (values != null) {
                    for (int j = 0; j < values.size(); j++) {
                        destinations.add(values.getStr(j));
                    }
                }
            } else if (StrUtil.isNotBlank(type)) {
                actionType = type;
            }
        }
        m.put("destinations", destinations);
        m.put("actionType", actionType);
        m.put("workerName", workerName);
        return m;
    }

    private static Map<String, Object> mapEmailDestination(JSONObject d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getStr("id"));
        m.put("email", d.getStr("email"));
        m.put("verified", d.getStr("verified"));
        m.put("created", d.getStr("created"));
        m.put("modified", d.getStr("modified"));
        return m;
    }

    /** @deprecated use zoneId + global token */
    public List<Map<String, Object>> listDnsRecordsByCfgId(String cfgId, int page, int perPage) {
        CfCfg cfg = cfCfgMapper.selectById(cfgId);
        if (cfg == null) {
            throw new OciException("CF 配置不存在");
        }
        return listDnsRecordsLegacy(cfg, page, perPage);
    }

    private List<Map<String, Object>> listDnsRecordsLegacy(CfCfg cfg, int page, int perPage) {
        String url = String.format("%s/zones/%s/dns_records?page=%d&per_page=%d",
                CF_API_BASE, cfg.getZoneId(), page, perPage);
        JSONObject json = parseJson(apiGet(cfg.getApiToken(), url));
        requireSuccess(json, "Cloudflare API 错误");
        JSONArray result = json.getJSONArray("result");
        List<Map<String, Object>> records = new ArrayList<>();
        if (result == null) {
            return records;
        }
        for (int i = 0; i < result.size(); i++) {
            JSONObject r = result.getJSONObject(i);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", r.getStr("id"));
            map.put("type", r.getStr("type"));
            map.put("name", r.getStr("name"));
            map.put("content", r.getStr("content"));
            map.put("proxied", r.getBool("proxied"));
            map.put("ttl", r.getInt("ttl"));
            records.add(map);
        }
        return records;
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private record Credentials(String accountId, String apiToken) {}

    private Credentials requireCredentials() {
        String accountId = StrUtil.trimToNull(notificationService.getKvValue(SysCfgEnum.CF_ACCOUNT_ID));
        String token = StrUtil.trimToNull(notificationService.getKvValue(SysCfgEnum.CF_API_TOKEN));
        if (accountId == null || token == null) {
            throw new OciException("请先在系统设置 → Cloudflare 中配置 Account ID 与 API Token");
        }
        return new Credentials(accountId, token);
    }

    private static void requireZoneId(String zoneId) {
        if (StrUtil.isBlank(zoneId)) {
            throw new OciException("请选择 Zone");
        }
    }

    private String apiGet(String token, String url) {
        return httpSend(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET());
    }

    private String apiPost(String token, String url, Object body) {
        return httpSend(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSONUtil.toJsonStr(body)))
                .timeout(Duration.ofSeconds(30)));
    }

    private String apiPut(String token, String url, Object body) {
        return httpSend(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method("PUT", HttpRequest.BodyPublishers.ofString(JSONUtil.toJsonStr(body)))
                .timeout(Duration.ofSeconds(30)));
    }

    private String apiPatch(String token, String url, Object body) {
        return httpSend(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(JSONUtil.toJsonStr(body)))
                .timeout(Duration.ofSeconds(30)));
    }

    private String apiPostMultipart(String token, String url, String fileFieldName, String fileName,
                                    String fileContent, Map<String, String> formFields) {
        String boundary = "----CloudflareBoundary" + System.currentTimeMillis();
        byte[] body = buildMultipartBody(boundary, fileFieldName, fileName, fileContent, formFields);
        return httpSend(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(60)));
    }

    private static byte[] buildMultipartBody(String boundary, String fileFieldName, String fileName,
                                             String fileContent, Map<String, String> formFields) {
        String lineEnd = "\r\n";
        StringBuilder sb = new StringBuilder();
        if (formFields != null) {
            for (Map.Entry<String, String> e : formFields.entrySet()) {
                sb.append("--").append(boundary).append(lineEnd);
                sb.append("Content-Disposition: form-data; name=\"").append(e.getKey()).append("\"").append(lineEnd);
                sb.append(lineEnd);
                sb.append(e.getValue()).append(lineEnd);
            }
        }
        sb.append("--").append(boundary).append(lineEnd);
        sb.append("Content-Disposition: form-data; name=\"").append(fileFieldName)
                .append("\"; filename=\"").append(fileName).append("\"").append(lineEnd);
        sb.append("Content-Type: text/plain").append(lineEnd);
        sb.append(lineEnd);
        sb.append(fileContent).append(lineEnd);
        sb.append("--").append(boundary).append("--").append(lineEnd);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void apiDelete(String token, String url) {
        httpSend(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(30))
                .DELETE());
    }

    private String httpSend(HttpRequest.Builder b) {
        try {
            HttpClient client = ociProxyConfigService.newOutboundHttpClient();
            HttpRequest req = b.build();
            HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
            String body = r.body() == null ? "" : r.body();
            if (r.statusCode() < 200 || r.statusCode() >= 400) {
                String msg = parseCfError(body);
                throw new OciException("HTTP " + r.statusCode() + (msg != null ? (": " + msg) : ""));
            }
            return body;
        } catch (OciException e) {
            throw e;
        } catch (IOException e) {
            throw new OciException("请求失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OciException("请求中断");
        }
    }

    private static JSONObject parseJson(String body) {
        if (StrUtil.isBlank(body)) {
            return new JSONObject();
        }
        return JSONUtil.parseObj(body);
    }

    private static void requireSuccess(JSONObject json, String prefix) {
        if (json.getBool("success", false)) {
            return;
        }
        String err = json.getStr("errors");
        if (StrUtil.isBlank(err) && json.getJSONArray("errors") != null) {
            err = json.getJSONArray("errors").toString();
        }
        throw new OciException(prefix + (StrUtil.isNotBlank(err) ? ": " + err : ""));
    }

    private static String parseCfError(String body) {
        try {
            JSONObject j = JSONUtil.parseObj(body);
            if (j.getJSONArray("errors") != null && !j.getJSONArray("errors").isEmpty()) {
                return j.getJSONArray("errors").toString();
            }
        } catch (Exception ignored) {
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    private static Map<String, Object> mapTunnel(JSONObject t) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", t.getStr("id"));
        map.put("name", t.getStr("name"));
        map.put("status", t.getStr("status"));
        map.put("createdAt", t.getStr("created_at"));
        map.put("deletedAt", t.getStr("deleted_at"));
        map.put("remoteConfig", t.getBool("remote_config"));
        map.put("connections", t.getJSONArray("connections"));
        return map;
    }

    private static String maskSecret(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        if (s.length() <= 8) {
            return "****";
        }
        return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }

    private static String resolveMasked(String fromClient, String existing) {
        if (fromClient != null && fromClient.contains("****")
                && existing != null && !existing.isBlank()) {
            return existing;
        }
        return fromClient == null ? "" : fromClient.trim();
    }
}
