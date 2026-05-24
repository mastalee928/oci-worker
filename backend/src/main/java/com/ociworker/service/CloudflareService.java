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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
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

    // -------------------------------------------------------------------------
    // IP Access Rules (account-level)
    // -------------------------------------------------------------------------

    private static final Set<String> IP_ACCESS_TARGETS = Set.of("ip", "ip6", "ip_range", "country", "asn");
    private static final Set<String> IP_ACCESS_MODES = Set.of(
            "block", "challenge", "js_challenge", "managed_challenge", "whitelist");

    public List<Map<String, Object>> listIpAccessRules() {
        Credentials c = requireCredentials();
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/firewall/access_rules/rules?per_page=100";
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取 IP 访问规则失败");
        JSONArray result = json.getJSONArray("result");
        List<Map<String, Object>> list = new ArrayList<>();
        if (result == null) {
            return list;
        }
        for (int i = 0; i < result.size(); i++) {
            list.add(mapIpAccessRule(result.getJSONObject(i)));
        }
        return list;
    }

    public Map<String, Object> createIpAccessRule(String target, String value, String mode, String notes) {
        Credentials c = requireCredentials();
        if (StrUtil.isBlank(target)) {
            throw new OciException("匹配类型不能为空");
        }
        String tgt = target.trim().toLowerCase();
        if (!IP_ACCESS_TARGETS.contains(tgt)) {
            throw new OciException("不支持的匹配类型: " + tgt);
        }
        if (StrUtil.isBlank(value)) {
            throw new OciException("匹配值不能为空");
        }
        if (StrUtil.isBlank(mode)) {
            throw new OciException("动作不能为空");
        }
        String act = mode.trim().toLowerCase();
        if (!IP_ACCESS_MODES.contains(act)) {
            throw new OciException("不支持的动作: " + act);
        }
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("target", tgt);
        configuration.put("value", value.trim());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", act);
        body.put("configuration", configuration);
        if (StrUtil.isNotBlank(notes)) {
            body.put("notes", notes.trim());
        }
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/firewall/access_rules/rules";
        JSONObject json = parseJson(apiPost(c.apiToken(), url, body));
        requireSuccess(json, "创建 IP 访问规则失败");
        JSONObject result = json.getJSONObject("result");
        if (result == null) {
            throw new OciException("创建 IP 访问规则失败：无返回数据");
        }
        return mapIpAccessRule(result);
    }

    public void deleteIpAccessRule(String ruleId) {
        Credentials c = requireCredentials();
        if (StrUtil.isBlank(ruleId)) {
            throw new OciException("规则 ID 不能为空");
        }
        String url = CF_API_BASE + "/accounts/" + c.accountId()
                + "/firewall/access_rules/rules/" + ruleId.trim();
        apiDelete(c.apiToken(), url);
    }

    private static Map<String, Object> mapIpAccessRule(JSONObject r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getStr("id"));
        m.put("mode", r.getStr("mode"));
        m.put("notes", r.getStr("notes"));
        m.put("createdOn", r.getStr("created_on"));
        m.put("modifiedOn", r.getStr("modified_on"));
        JSONObject cfg = r.getJSONObject("configuration");
        if (cfg != null) {
            m.put("target", cfg.getStr("target"));
            m.put("value", cfg.getStr("value"));
        }
        JSONObject scope = r.getJSONObject("scope");
        if (scope != null) {
            m.put("scopeType", scope.getStr("type"));
            m.put("scopeEmail", scope.getStr("email"));
        }
        return m;
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
            JSONObject client = result.getJSONObject(i);
            JSONArray conns = client.getJSONArray("conns");
            if (conns != null && !conns.isEmpty()) {
                for (int j = 0; j < conns.size(); j++) {
                    list.add(mapTunnelConnection(conns.getJSONObject(j), client));
                }
            } else {
                // 兼容 list tunnels 内嵌的扁平 connections 结构
                list.add(mapTunnelConnection(client, null));
            }
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // Tunnel Public Hostname (ingress + auto CNAME)
    // -------------------------------------------------------------------------

    private static final Set<String> TUNNEL_SERVICE_PREFIXES = Set.of(
            "http://", "https://", "tcp://", "unix://", "ssh://", "rdp://", "smb://", "http_status:");

    public List<Map<String, Object>> listTunnelRoutes(String tunnelId) {
        Credentials c = requireCredentials();
        requireTunnelId(tunnelId);
        return loadHostnameIngressRules(c, tunnelId.trim());
    }

    public Map<String, Object> addTunnelRoute(String tunnelId, String zoneId, String subdomain, String service) {
        Credentials c = requireCredentials();
        requireTunnelId(tunnelId);
        requireZoneId(zoneId);
        String tid = tunnelId.trim();
        String svc = validateTunnelService(service);
        Map<String, Object> zone = getZoneDetail(zoneId);
        String zoneName = String.valueOf(zone.get("name"));
        String hostname = buildTunnelHostname(zoneName, subdomain);
        List<Map<String, Object>> rules = loadHostnameIngressRules(c, tid);
        for (Map<String, Object> r : rules) {
            if (hostname.equalsIgnoreCase(String.valueOf(r.get("hostname")))) {
                throw new OciException("该 Public Hostname 已存在: " + hostname);
            }
        }
        Map<String, Object> newRule = new LinkedHashMap<>();
        newRule.put("hostname", hostname);
        newRule.put("service", svc);
        newRule.put("originRequest", Map.of());
        rules.add(newRule);
        // 先确保 DNS，再写 ingress，避免 ingress 成功但 DNS 失败后无法重试
        Map<String, Object> dnsResult = ensureTunnelCname(c, zoneId.trim(), tid, hostname);
        putTunnelHostnameIngress(c, tid, rules);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hostname", hostname);
        out.put("service", svc);
        out.put("zoneId", zoneId.trim());
        out.put("zoneName", zoneName);
        out.putAll(dnsResult);
        return out;
    }

    public void deleteTunnelRoute(String tunnelId, String hostname) {
        Credentials c = requireCredentials();
        requireTunnelId(tunnelId);
        if (StrUtil.isBlank(hostname)) {
            throw new OciException("Public Hostname 不能为空");
        }
        String host = hostname.trim().toLowerCase();
        List<Map<String, Object>> rules = loadHostnameIngressRules(c, tunnelId.trim());
        List<Map<String, Object>> kept = new ArrayList<>();
        boolean removed = false;
        for (Map<String, Object> r : rules) {
            String h = String.valueOf(r.get("hostname"));
            if (host.equalsIgnoreCase(h)) {
                removed = true;
                continue;
            }
            kept.add(r);
        }
        if (!removed) {
            throw new OciException("未找到 Public Hostname: " + hostname);
        }
        putTunnelHostnameIngress(c, tunnelId.trim(), kept);
    }

    private void requireTunnelId(String tunnelId) {
        if (StrUtil.isBlank(tunnelId)) {
            throw new OciException("Tunnel ID 不能为空");
        }
    }

    private static String validateTunnelService(String service) {
        if (StrUtil.isBlank(service)) {
            throw new OciException("Service URL 不能为空");
        }
        String svc = service.trim();
        boolean ok = TUNNEL_SERVICE_PREFIXES.stream().anyMatch(svc::startsWith);
        if (!ok) {
            throw new OciException("Service URL 须以 http://、https://、tcp:// 等协议开头");
        }
        return svc;
    }

    private static String buildTunnelHostname(String zoneName, String subdomain) {
        if (StrUtil.isBlank(zoneName)) {
            throw new OciException("Zone 域名无效");
        }
        String zone = zoneName.trim().toLowerCase();
        String sub = StrUtil.blankToDefault(subdomain, "").trim();
        if (sub.isEmpty() || "@".equals(sub)) {
            return zone;
        }
        if (sub.contains(".")) {
            String lower = sub.toLowerCase();
            if (!lower.endsWith("." + zone) && !lower.equals(zone)) {
                throw new OciException("子域名须属于 Zone: " + zone);
            }
            return lower;
        }
        return sub.toLowerCase() + "." + zone;
    }

    private JSONArray fetchTunnelIngressArray(Credentials c, String tunnelId) {
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/cfd_tunnel/" + tunnelId + "/configurations";
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取 Tunnel 配置失败");
        JSONObject result = json.getJSONObject("result");
        if (result == null) {
            return new JSONArray();
        }
        JSONObject config = result.getJSONObject("config");
        if (config == null) {
            return new JSONArray();
        }
        JSONArray ingress = config.getJSONArray("ingress");
        return ingress != null ? ingress : new JSONArray();
    }

    private List<Map<String, Object>> loadHostnameIngressRules(Credentials c, String tunnelId) {
        JSONArray ingress = fetchTunnelIngressArray(c, tunnelId);
        List<Map<String, Object>> rules = new ArrayList<>();
        for (int i = 0; i < ingress.size(); i++) {
            JSONObject row = ingress.getJSONObject(i);
            if (row == null || isCatchAllIngress(row)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hostname", row.getStr("hostname"));
            m.put("service", row.getStr("service"));
            if (StrUtil.isNotBlank(row.getStr("path"))) {
                m.put("path", row.getStr("path"));
            }
            JSONObject originRequest = row.getJSONObject("originRequest");
            if (originRequest != null && !originRequest.isEmpty()) {
                m.put("originRequest", originRequest);
            }
            rules.add(m);
        }
        return rules;
    }

    private static boolean isCatchAllIngress(JSONObject row) {
        return StrUtil.isBlank(row.getStr("hostname"));
    }

    private void putTunnelHostnameIngress(Credentials c, String tunnelId, List<Map<String, Object>> hostnameRules) {
        List<Map<String, Object>> ingress = new ArrayList<>();
        for (Map<String, Object> rule : hostnameRules) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("hostname", rule.get("hostname"));
            item.put("service", rule.get("service"));
            if (rule.get("path") != null) {
                item.put("path", rule.get("path"));
            }
            Object originRequest = rule.get("originRequest");
            item.put("originRequest", originRequest != null ? originRequest : Map.of());
            ingress.add(item);
        }
        Map<String, Object> catchAll = new LinkedHashMap<>();
        catchAll.put("service", "http_status:404");
        ingress.add(catchAll);
        Map<String, Object> body = Map.of("config", Map.of("ingress", ingress));
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/cfd_tunnel/" + tunnelId + "/configurations";
        JSONObject json = parseJson(apiPut(c.apiToken(), url, body));
        requireSuccess(json, "更新 Tunnel 路由失败");
    }

    private Map<String, Object> ensureTunnelCname(Credentials c, String zoneId, String tunnelId, String hostname) {
        String target = tunnelId + ".cfargotunnel.com";
        String url = String.format("%s/zones/%s/dns_records?type=CNAME&name=%s",
                CF_API_BASE, zoneId, URLEncoder.encode(hostname, StandardCharsets.UTF_8));
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "查询 DNS 记录失败");
        JSONArray result = json.getJSONArray("result");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dnsCreated", false);
        out.put("dnsUpdated", false);
        if (result != null && !result.isEmpty()) {
            JSONObject rec = result.getJSONObject(0);
            String recordType = rec.getStr("type");
            if (!"CNAME".equalsIgnoreCase(recordType)) {
                throw new OciException("DNS 记录 " + hostname + " 已存在且类型为 "
                        + recordType + "，请先删除或手动改为 CNAME");
            }
            String content = normalizeDnsContent(rec.getStr("content"));
            if (target.equalsIgnoreCase(content)) {
                return out;
            }
            String recordId = rec.getStr("id");
            updateDnsRecord(zoneId, recordId, "CNAME", rec.getStr("name"), target, true, 1, null,
                    "OCI Worker Tunnel");
            out.put("dnsUpdated", true);
            return out;
        }
        addDnsRecord(zoneId, "CNAME", hostname, target, true, 1, null, "OCI Worker Tunnel");
        out.put("dnsCreated", true);
        return out;
    }

    private static String normalizeDnsContent(String content) {
        if (content == null) {
            return "";
        }
        return content.endsWith(".") ? content.substring(0, content.length() - 1) : content;
    }

    private static Map<String, Object> mapTunnelConnection(JSONObject conn, JSONObject client) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("coloName", conn.getStr("colo_name"));
        m.put("uuid", StrUtil.blankToDefault(conn.getStr("uuid"), conn.getStr("id")));
        m.put("isPendingReconnect", conn.getBool("is_pending_reconnect"));
        m.put("openedAt", conn.getStr("opened_at"));
        m.put("originIp", conn.getStr("origin_ip"));
        m.put("clientId", conn.getStr("client_id"));
        m.put("clientVersion", StrUtil.blankToDefault(conn.getStr("client_version"),
                client != null ? client.getStr("version") : null));
        if (client != null) {
            m.put("arch", client.getStr("arch"));
            m.put("runAt", client.getStr("run_at"));
        }
        return m;
    }

    // -------------------------------------------------------------------------
    // SSL/TLS, Cache, Security, Workers Routes, Page Rules (zone-scoped)
    // -------------------------------------------------------------------------

    private static final List<String> SSL_SETTING_IDS = List.of(
            "ssl", "always_use_https", "min_tls_version", "tls_1_3");

    private static final List<String> CACHE_SETTING_IDS = List.of(
            "cache_level", "browser_cache_ttl", "development_mode", "always_online");

    private static final List<String> SECURITY_SETTING_IDS = List.of(
            "security_level", "bot_fight_mode", "browser_check");

    public Map<String, Object> getSecuritySettings(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String id : SECURITY_SETTING_IDS) {
            out.put(id, readZoneSettingValueOptional(c.apiToken(), zoneId, id));
        }
        return out;
    }

    public Map<String, Object> updateSecuritySetting(String zoneId, String settingId, Object value) {
        if (!SECURITY_SETTING_IDS.contains(settingId)) {
            throw new OciException("不支持的防护设置: " + settingId);
        }
        if ("security_level".equals(settingId)) {
            String level = value != null ? value.toString().trim().toLowerCase() : "";
            if (!Set.of("off", "essentially_off", "low", "medium", "high", "under_attack").contains(level)) {
                throw new OciException("无效的安全级别: " + level);
            }
        }
        patchZoneSetting(zoneId, settingId, value);
        return getSecuritySettings(zoneId);
    }

    public Map<String, Object> getSslSettings(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String id : SSL_SETTING_IDS) {
            out.put(id, readZoneSettingValue(c.apiToken(), zoneId, id));
        }
        return out;
    }

    public Map<String, Object> updateSslSetting(String zoneId, String settingId, Object value) {
        patchZoneSetting(zoneId, settingId, value);
        return getSslSettings(zoneId);
    }

    public Map<String, Object> getCacheSettings(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String id : CACHE_SETTING_IDS) {
            out.put(id, readZoneSettingValue(c.apiToken(), zoneId, id));
        }
        return out;
    }

    public Map<String, Object> updateCacheSetting(String zoneId, String settingId, Object value) {
        patchZoneSetting(zoneId, settingId, value);
        return getCacheSettings(zoneId);
    }

    public void purgeZoneCache(String zoneId, boolean purgeEverything, List<String> files) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        Map<String, Object> body = new LinkedHashMap<>();
        if (purgeEverything) {
            body.put("purge_everything", true);
        } else if (files != null && !files.isEmpty()) {
            body.put("files", files);
        } else {
            throw new OciException("请指定 purge_everything 或 files");
        }
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/purge_cache";
        JSONObject json = parseJson(apiPost(c.apiToken(), url, body));
        requireSuccess(json, "清理缓存失败");
    }

    private static final Set<String> FIREWALL_ACTIONS = Set.of(
            "block", "challenge", "js_challenge", "managed_challenge", "allow", "log", "bypass");

    public List<Map<String, Object>> listFirewallRules(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/firewall/rules?per_page=100";
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取防火墙规则失败");
        JSONArray result = json.getJSONArray("result");
        List<Map<String, Object>> list = new ArrayList<>();
        if (result == null) {
            return list;
        }
        for (int i = 0; i < result.size(); i++) {
            list.add(mapFirewallRule(result.getJSONObject(i)));
        }
        Map<String, Integer> eventCounts = fetchFirewallEventCounts24h(c, zoneId.trim());
        for (Map<String, Object> rule : list) {
            String id = (String) rule.get("id");
            if (eventCounts.containsKey(id)) {
                rule.put("events24h", eventCounts.get(id));
            }
        }
        return list;
    }

    public Map<String, Object> createFirewallRule(String zoneId, String action, String expression,
                                                    String description, boolean paused) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        if (StrUtil.isBlank(action)) {
            throw new OciException("防火墙动作不能为空");
        }
        String act = action.trim().toLowerCase();
        if (!FIREWALL_ACTIONS.contains(act)) {
            throw new OciException("不支持的防火墙动作: " + act);
        }
        if (StrUtil.isBlank(expression)) {
            throw new OciException("过滤表达式不能为空");
        }
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("expression", expression.trim());
        filter.put("paused", false);
        String desc = StrUtil.trimToNull(description);
        if (desc != null) {
            filter.put("description", desc);
        }
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("action", act);
        rule.put("filter", filter);
        rule.put("paused", paused);
        if (desc != null) {
            rule.put("description", desc);
        }
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/firewall/rules";
        JSONObject json = parseJson(apiPost(c.apiToken(), url, List.of(rule)));
        requireSuccess(json, "创建防火墙规则失败");
        JSONArray result = json.getJSONArray("result");
        if (result == null || result.isEmpty()) {
            throw new OciException("创建防火墙规则失败：无返回数据");
        }
        return mapFirewallRule(result.getJSONObject(0));
    }

    public Map<String, Object> setFirewallRulePaused(String zoneId, String ruleId, boolean paused) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        if (StrUtil.isBlank(ruleId)) {
            throw new OciException("规则 ID 不能为空");
        }
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/firewall/rules/" + ruleId.trim();
        JSONObject getJson = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(getJson, "获取防火墙规则失败");
        JSONObject rule = getJson.getJSONObject("result");
        if (rule == null) {
            throw new OciException("防火墙规则不存在");
        }
        Map<String, Object> body = buildFirewallRuleUpdateBody(rule, paused, null, null, null);
        JSONObject json = parseJson(apiPut(c.apiToken(), url, body));
        requireSuccess(json, paused ? "暂停规则失败" : "启用规则失败");
        JSONObject result = json.getJSONObject("result");
        return result != null ? mapFirewallRule(result) : Map.of("id", ruleId, "paused", paused);
    }

    public Map<String, Object> updateFirewallRule(String zoneId, String ruleId, String action,
                                                    String description, String expression, Boolean paused) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        if (StrUtil.isBlank(ruleId)) {
            throw new OciException("规则 ID 不能为空");
        }
        if (StrUtil.isNotBlank(action)) {
            String act = action.trim().toLowerCase();
            if (!FIREWALL_ACTIONS.contains(act)) {
                throw new OciException("不支持的防火墙动作: " + act);
            }
        }
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/firewall/rules/" + ruleId.trim();
        JSONObject getJson = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(getJson, "获取防火墙规则失败");
        JSONObject rule = getJson.getJSONObject("result");
        if (rule == null) {
            throw new OciException("防火墙规则不存在");
        }
        Map<String, Object> body = buildFirewallRuleUpdateBody(rule, paused, action, description, expression);
        JSONObject json = parseJson(apiPut(c.apiToken(), url, body));
        requireSuccess(json, "更新防火墙规则失败");
        JSONObject result = json.getJSONObject("result");
        return result != null ? mapFirewallRule(result) : Map.of("id", ruleId);
    }

    public void deleteFirewallRule(String zoneId, String ruleId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        if (StrUtil.isBlank(ruleId)) {
            throw new OciException("规则 ID 不能为空");
        }
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/firewall/rules/" + ruleId.trim();
        apiDelete(c.apiToken(), url);
    }

    private Map<String, Integer> fetchFirewallEventCounts24h(Credentials c, String zoneId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try {
            Instant until = Instant.now();
            Instant since = until.minus(24, ChronoUnit.HOURS);
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("datetime_geq", since.toString());
            filter.put("datetime_leq", until.toString());
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("zoneTag", zoneId);
            variables.put("filter", filter);
            String gql = """
                    query FWEvents($zoneTag: string!, $filter: FirewallEventsAdaptiveFilter_InputObject!) {
                      viewer {
                        zones(filter: { zoneTag: $zoneTag }) {
                          firewallEventsAdaptive(filter: $filter, limit: 10000) {
                            ruleId
                          }
                        }
                      }
                    }""";
            Map<String, Object> payload = Map.of("query", gql, "variables", variables);
            JSONObject json = parseJson(apiPost(c.apiToken(), CF_API_BASE + "/graphql", payload));
            JSONObject data = json.getJSONObject("data");
            if (data == null) {
                return counts;
            }
            JSONObject viewer = data.getJSONObject("viewer");
            if (viewer == null) {
                return counts;
            }
            JSONArray zones = viewer.getJSONArray("zones");
            if (zones == null || zones.isEmpty()) {
                return counts;
            }
            JSONArray events = zones.getJSONObject(0).getJSONArray("firewallEventsAdaptive");
            if (events == null) {
                return counts;
            }
            for (int i = 0; i < events.size(); i++) {
                JSONObject ev = events.getJSONObject(i);
                String ruleId = ev.getStr("ruleId");
                if (StrUtil.isNotBlank(ruleId)) {
                    counts.merge(ruleId, 1, Integer::sum);
                }
            }
        } catch (Exception e) {
            log.debug("Firewall 24h events GraphQL skipped: {}", e.getMessage());
        }
        return counts;
    }

    private static Map<String, Object> buildFirewallRuleUpdateBody(JSONObject rule, Boolean paused,
                                                                    String action, String description,
                                                                    String expression) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", rule.getStr("id"));
        body.put("action", StrUtil.isNotBlank(action) ? action.trim().toLowerCase() : rule.getStr("action"));
        body.put("description", description != null ? description : rule.getStr("description"));
        body.put("paused", paused != null ? paused : Boolean.TRUE.equals(rule.getBool("paused")));
        Object priority = rule.get("priority");
        if (priority != null && !JSONUtil.isNull(priority)) {
            body.put("priority", rule.getInt("priority"));
        }
        JSONObject filter = rule.getJSONObject("filter");
        if (filter != null) {
            Map<String, Object> filterBody = new LinkedHashMap<>();
            filterBody.put("id", filter.getStr("id"));
            filterBody.put("expression", StrUtil.isNotBlank(expression)
                    ? expression.trim() : filter.getStr("expression"));
            filterBody.put("paused", filter.getBool("paused", false));
            String filterDesc = filter.getStr("description");
            if (StrUtil.isNotBlank(filterDesc)) {
                filterBody.put("description", filterDesc);
            }
            body.put("filter", filterBody);
        }
        return body;
    }

    private static Map<String, Object> mapFirewallRule(JSONObject r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getStr("id"));
        m.put("description", r.getStr("description"));
        m.put("action", r.getStr("action"));
        m.put("paused", Boolean.TRUE.equals(r.getBool("paused")));
        Object priority = r.get("priority");
        if (priority != null && !JSONUtil.isNull(priority)) {
            m.put("priority", r.getInt("priority"));
        }
        JSONObject filter = r.getJSONObject("filter");
        if (filter != null) {
            m.put("filterId", filter.getStr("id"));
            m.put("expression", filter.getStr("expression"));
        }
        return m;
    }

    public List<Map<String, Object>> listWorkersRoutes(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/workers/routes";
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取 Workers 路由失败");
        JSONArray result = json.getJSONArray("result");
        List<Map<String, Object>> list = new ArrayList<>();
        if (result == null) {
            return list;
        }
        for (int i = 0; i < result.size(); i++) {
            JSONObject r = result.getJSONObject(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getStr("id"));
            m.put("pattern", r.getStr("pattern"));
            m.put("script", r.getStr("script"));
            list.add(m);
        }
        return list;
    }

    public Map<String, Object> createWorkersRoute(String zoneId, String pattern, String script) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        if (StrUtil.isBlank(pattern)) {
            throw new OciException("路由 pattern 不能为空");
        }
        if (StrUtil.isBlank(script)) {
            throw new OciException("Worker 脚本名不能为空");
        }
        Map<String, Object> body = Map.of(
                "pattern", pattern.trim(),
                "script", script.trim());
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/workers/routes";
        JSONObject json = parseJson(apiPost(c.apiToken(), url, body));
        requireSuccess(json, "创建 Workers 路由失败");
        JSONObject result = json.getJSONObject("result");
        Map<String, Object> m = new LinkedHashMap<>();
        if (result != null) {
            m.put("id", result.getStr("id"));
            m.put("pattern", result.getStr("pattern"));
            m.put("script", result.getStr("script"));
        }
        return m;
    }

    public void deleteWorkersRoute(String zoneId, String routeId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        if (StrUtil.isBlank(routeId)) {
            throw new OciException("路由 ID 不能为空");
        }
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/workers/routes/" + routeId.trim();
        apiDelete(c.apiToken(), url);
    }

    public List<Map<String, Object>> listZoneRules(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String listUrl = CF_API_BASE + "/zones/" + zoneId.trim() + "/rulesets";
        JSONObject listJson = parseJson(apiGet(c.apiToken(), listUrl));
        requireSuccess(listJson, "拉取 Rulesets 失败");
        JSONArray rulesets = listJson.getJSONArray("result");
        List<Map<String, Object>> rules = new ArrayList<>();
        if (rulesets == null) {
            return rules;
        }
        Set<String> userKinds = Set.of("zone", "custom", "root");
        for (int i = 0; i < rulesets.size(); i++) {
            JSONObject summary = rulesets.getJSONObject(i);
            String kind = summary.getStr("kind");
            if (kind == null || !userKinds.contains(kind)) {
                continue;
            }
            String rulesetId = summary.getStr("id");
            if (StrUtil.isBlank(rulesetId)) {
                continue;
            }
            String detailUrl = CF_API_BASE + "/zones/" + zoneId.trim() + "/rulesets/" + rulesetId.trim();
            JSONObject detailJson = parseJson(apiGet(c.apiToken(), detailUrl));
            requireSuccess(detailJson, "拉取 Ruleset 详情失败");
            JSONObject detail = detailJson.getJSONObject("result");
            if (detail == null) {
                continue;
            }
            String phase = detail.getStr("phase");
            String rulesetName = detail.getStr("name");
            JSONArray ruleArr = detail.getJSONArray("rules");
            if (ruleArr == null) {
                continue;
            }
            for (int j = 0; j < ruleArr.size(); j++) {
                JSONObject rule = ruleArr.getJSONObject(j);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rule.getStr("id"));
                m.put("ref", rule.getStr("ref"));
                m.put("rulesetId", rulesetId);
                m.put("phase", phase);
                m.put("rulesetName", rulesetName);
                m.put("description", rule.getStr("description"));
                m.put("expression", rule.getStr("expression"));
                m.put("action", rule.getStr("action"));
                m.put("enabled", rule.getBool("enabled", true));
                rules.add(m);
            }
        }
        return rules;
    }

    /** @deprecated Page Rules 不支持 cfat_ 账户令牌，请用 {@link #listZoneRules} */
    public List<Map<String, Object>> listPageRules(String zoneId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/pagerules?status=active,disabled";
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取 Page Rules 失败");
        JSONArray result = json.getJSONArray("result");
        List<Map<String, Object>> list = new ArrayList<>();
        if (result == null) {
            return list;
        }
        for (int i = 0; i < result.size(); i++) {
            JSONObject r = result.getJSONObject(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getStr("id"));
            m.put("status", r.getStr("status"));
            m.put("priority", r.getInt("priority"));
            m.put("targets", r.get("targets"));
            m.put("actions", r.get("actions"));
            list.add(m);
        }
        return list;
    }

    public void deletePageRule(String zoneId, String ruleId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        if (StrUtil.isBlank(ruleId)) {
            throw new OciException("规则 ID 不能为空");
        }
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/pagerules/" + ruleId.trim();
        apiDelete(c.apiToken(), url);
    }

    /** 账户级 Workers 脚本列表（与 email/workers/list 相同数据源） */
    public List<Map<String, Object>> listWorkerScripts() {
        return listWorkers();
    }

    private Object readZoneSettingValue(String token, String zoneId, String settingId) {
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/settings/" + settingId.trim();
        JSONObject json = parseJson(apiGet(token, url));
        requireSuccess(json, "读取 Zone 设置 " + settingId + " 失败");
        JSONObject result = json.getJSONObject("result");
        return result != null ? result.get("value") : null;
    }

    private Object readZoneSettingValueOptional(String token, String zoneId, String settingId) {
        try {
            return readZoneSettingValue(token, zoneId, settingId);
        } catch (Exception e) {
            log.debug("Zone setting {} skipped: {}", settingId, e.getMessage());
            return null;
        }
    }

    private void patchZoneSetting(String zoneId, String settingId, Object value) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        if (StrUtil.isBlank(settingId)) {
            throw new OciException("设置项不能为空");
        }
        String url = CF_API_BASE + "/zones/" + zoneId.trim() + "/settings/" + settingId.trim();
        JSONObject json = parseJson(apiPatch(c.apiToken(), url, Map.of("value", value)));
        requireSuccess(json, "更新 Zone 设置失败");
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

    // -------------------------------------------------------------------------
    // Workers & Pages (account)
    // -------------------------------------------------------------------------

    private static final String WORKER_HELLO_WORLD = """
            export default {
              async fetch() {
                return new Response('Hello World!', {
                  headers: { 'content-type': 'text/plain;charset=UTF-8' },
                });
              },
            };
            """;

    private static final Map<String, String> WORKER_TEMPLATES = Map.of(
            "json-api", """
                    export default {
                      async fetch() {
                        return Response.json({ ok: true, message: 'Hello from Workers' });
                      },
                    };
                    """,
            "html", """
                    export default {
                      async fetch() {
                        const html = '<!DOCTYPE html><html><body><h1>Hello World</h1></body></html>';
                        return new Response(html, { headers: { 'content-type': 'text/html;charset=UTF-8' } });
                      },
                    };
                    """);

    private static final int MAX_PAGES_UPLOAD_FILES = 100;
    private static final long MAX_PAGES_UPLOAD_BYTES = 25L * 1024 * 1024;

    private static final Map<String, List<Map<String, String>>> PAGES_TEMPLATE_FILES = Map.of(
            "static-starter", List.of(
                    Map.of("path", "index.html", "content", """
                            <!DOCTYPE html>
                            <html lang="zh-CN">
                            <head><meta charset="utf-8"><title>Cloudflare Pages</title></head>
                            <body><h1>Hello from Cloudflare Pages</h1></body>
                            </html>
                            """)),
            "blog-starter", List.of(
                    Map.of("path", "index.html", "content", """
                            <!DOCTYPE html>
                            <html lang="zh-CN">
                            <head><meta charset="utf-8"><title>My Blog</title></head>
                            <body><h1>My Blog</h1><p>Powered by Cloudflare Pages.</p></body>
                            </html>
                            """),
                    Map.of("path", "styles.css", "content", "body { font-family: system-ui, sans-serif; margin: 2rem; }")));

    private record MetricQuery(long value, boolean available) {}

    public Map<String, Object> getWorkersUsageSummary() {
        Credentials c = requireCredentials();
        Instant now = Instant.now();
        Instant todayStart = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant monthStart = now.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();

        MetricQuery todayRequests = queryWorkersRequestsSum(c, todayStart, now);
        MetricQuery periodRequests = queryWorkersRequestsSum(c, monthStart, now);
        MetricQuery cpuTimeMs = queryWorkersCpuTimeMs(c, monthStart, now);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dateRangeLabel", formatUsageDateRange(monthStart, now));
        m.put("limitsNote", "以下为 Workers 免费版参考限额；付费账户限额不同");
        m.put("todayRequestsLimit", 100_000L);
        m.put("todayObservabilityLimit", 200_000L);
        putMetric(m, "todayRequests", todayRequests);
        putMetric(m, "periodRequests", periodRequests);
        putMetric(m, "cpuTimeMs", cpuTimeMs);
        m.put("todayObservabilityEvents", null);
        m.put("observabilityEvents", null);
        m.put("buildMinutes", null);
        m.put("observabilityAvailable", false);
        m.put("buildMinutesAvailable", false);
        return m;
    }

    private static void putMetric(Map<String, Object> m, String key, MetricQuery q) {
        m.put(key, q.available() ? q.value() : null);
        m.put(key + "Available", q.available());
    }

    public List<Map<String, Object>> listWorkersAndPagesApplications() {
        List<Map<String, Object>> items = new ArrayList<>();
        Credentials c = requireCredentials();
        String workersDevSubdomain = fetchAccountWorkersSubdomain(c);
        try {
            for (Map<String, Object> w : listWorkers()) {
                Map<String, Object> row = new LinkedHashMap<>(w);
                row.put("kind", "worker");
                String scriptName = String.valueOf(w.get("id"));
                row.put("name", scriptName);
                row.put("url", buildWorkerDevUrl(c, scriptName, workersDevSubdomain));
                items.add(row);
            }
        } catch (Exception e) {
            log.warn("Workers 列表拉取失败: {}", e.getMessage());
        }
        try {
            items.addAll(listPagesProjects());
        } catch (Exception e) {
            log.warn("Pages 列表拉取失败: {}", e.getMessage());
        }
        return items;
    }

    public List<Map<String, Object>> listWorkerTemplates() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(templateMeta("json-api", "JSON API", "返回 JSON 响应", "worker"));
        list.add(templateMeta("html", "HTML", "返回简单 HTML 页面", "worker"));
        list.add(templateMeta("static-starter", "静态站点", "单页 HTML 静态站点", "pages"));
        list.add(templateMeta("blog-starter", "博客入门", "带样式的简单博客首页", "pages"));
        return list;
    }

    private static Map<String, Object> templateMeta(String id, String name, String description, String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description);
        m.put("kind", kind);
        return m;
    }

    public Map<String, Object> getWorkersSubdomainInfo() {
        Credentials c = requireCredentials();
        String subdomain = fetchAccountWorkersSubdomain(c);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subdomain", subdomain);
        m.put("suffix", ".workers.dev");
        return m;
    }

    public Map<String, Object> getWorkersPagesTemplatePreview(String templateId) {
        if (StrUtil.isBlank(templateId)) {
            throw new OciException("模板 ID 不能为空");
        }
        if ("hello-world".equals(templateId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "hello-world");
            m.put("kind", "worker");
            m.put("name", "Hello World");
            m.put("script", WORKER_HELLO_WORLD);
            m.put("module", "worker.mjs");
            return m;
        }
        String workerCode = WORKER_TEMPLATES.get(templateId);
        if (workerCode != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", templateId);
            m.put("kind", "worker");
            m.put("script", workerCode);
            m.put("module", "worker.mjs");
            return m;
        }
        List<Map<String, String>> pagesFiles = PAGES_TEMPLATE_FILES.get(templateId);
        if (pagesFiles != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", templateId);
            m.put("kind", "pages");
            m.put("files", pagesFiles);
            return m;
        }
        throw new OciException("不支持的模板: " + templateId);
    }

    public Map<String, Object> deployWorker(String scriptName, String scriptContent) {
        if (StrUtil.isBlank(scriptContent)) {
            throw new OciException("Worker 代码不能为空");
        }
        Credentials c = requireCredentials();
        String name = normalizeWorkerScriptName(scriptName);
        Map<String, Object> result = uploadWorkerScript(name, scriptContent.trim());
        enableWorkerSubdomain(c, name);
        String accountSubdomain = fetchAccountWorkersSubdomain(c);
        result.put("url", resolveWorkerPublicUrl(name, accountSubdomain));
        result.put("subdomainEnabled", true);
        return result;
    }

    public Map<String, Object> getWorkerScriptContent(String scriptName) {
        Credentials c = requireCredentials();
        String name = normalizeWorkerScriptName(scriptName);
        HttpBinaryResponse resp = apiGetBinary(c.apiToken(),
                CF_API_BASE + "/accounts/" + c.accountId() + "/workers/scripts/" + urlEncodePath(name));
        Map<String, Object> parsed = parseDownloadedWorkerScript(resp.body(), resp.contentType());
        parsed.put("name", name);
        return parsed;
    }

    public Map<String, Object> updateWorkerScript(String scriptName, String scriptContent) {
        return deployWorker(scriptName, scriptContent);
    }

    public Map<String, Object> renameWorkerScript(String oldName, String newName) {
        String oldNorm = normalizeWorkerScriptName(oldName);
        String newNorm = normalizeWorkerScriptName(newName);
        if (oldNorm.equals(newNorm)) {
            throw new OciException("新名称不能与当前名称相同");
        }
        Map<String, Object> content = getWorkerScriptContent(oldNorm);
        String script = String.valueOf(content.get("script"));
        if (StrUtil.isBlank(script)) {
            throw new OciException("无法读取 Worker 脚本内容");
        }
        Map<String, Object> result = deployWorker(newNorm, script);
        deleteWorkerScriptInternal(oldNorm);
        result.put("oldName", oldNorm);
        result.put("name", newNorm);
        return result;
    }

    public void deleteWorkerScript(String scriptName) {
        deleteWorkerScriptInternal(normalizeWorkerScriptName(scriptName));
    }

    private void deleteWorkerScriptInternal(String scriptName) {
        Credentials c = requireCredentials();
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/workers/scripts/"
                + urlEncodePath(scriptName);
        apiDelete(c.apiToken(), url);
    }

    public Map<String, Object> createWorkerHelloWorld(String scriptName, String scriptContent) {
        String code = StrUtil.isNotBlank(scriptContent) ? scriptContent : WORKER_HELLO_WORLD;
        return deployWorker(scriptName, code);
    }

    public Map<String, Object> createWorkerFromTemplate(String scriptName, String templateId, String scriptContent) {
        String code = StrUtil.isNotBlank(scriptContent) ? scriptContent : WORKER_TEMPLATES.get(templateId);
        if (code == null) {
            throw new OciException("不支持的 Worker 模板: " + templateId);
        }
        return deployWorker(scriptName, code);
    }

    public Map<String, Object> createPagesFromTemplate(String projectName, String templateId) {
        List<Map<String, String>> files = PAGES_TEMPLATE_FILES.get(templateId);
        if (files == null) {
            throw new OciException("不支持的 Pages 模板: " + templateId);
        }
        return deployPagesStaticFiles(projectName, files);
    }

    public Map<String, Object> deployPagesStaticFromUpload(String projectName, List<Map<String, String>> encodedFiles) {
        List<Map<String, Object>> decoded = decodePagesUploadFiles(encodedFiles);
        return deployPagesStaticFileEntries(projectName, decoded);
    }

    public Map<String, Object> deployPagesStaticFiles(String projectName, List<Map<String, String>> files) {
        return deployPagesStaticFileEntries(projectName, toPagesFileBytes(files));
    }

    private Map<String, Object> deployPagesStaticFileEntries(String projectName, List<Map<String, Object>> fileEntries) {
        Credentials c = requireCredentials();
        String name = normalizePagesProjectName(projectName);
        if (fileEntries == null || fileEntries.isEmpty()) {
            throw new OciException("请至少上传一个文件");
        }
        ensurePagesProject(c, name);
        return createPagesDeployment(c, name, fileEntries);
    }

    private static List<Map<String, Object>> toPagesFileBytes(List<Map<String, String>> files) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (files == null) {
            return out;
        }
        for (Map<String, String> f : files) {
            String path = f.get("path");
            String content = f.get("content");
            if (StrUtil.isBlank(path) || content == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", path);
            row.put("bytes", content.getBytes(StandardCharsets.UTF_8));
            out.add(row);
        }
        return out;
    }

    public List<Map<String, Object>> listPagesProjects() {
        Credentials c = requireCredentials();
        String apiUrl = CF_API_BASE + "/accounts/" + c.accountId() + "/pages/projects?per_page=50";
        JSONObject json = parseJson(apiGet(c.apiToken(), apiUrl));
        requireSuccess(json, "拉取 Pages 项目失败");
        JSONArray result = json.getJSONArray("result");
        List<Map<String, Object>> list = new ArrayList<>();
        if (result == null) {
            return list;
        }
        for (int i = 0; i < result.size(); i++) {
            JSONObject p = result.getJSONObject(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "pages");
            m.put("id", p.getStr("id"));
            m.put("name", p.getStr("name"));
            m.put("createdOn", p.getStr("created_on"));
            m.put("modifiedOn", p.getStr("modified_on"));
            String url = resolvePagesProjectUrl(p);
            if (StrUtil.isNotBlank(url)) {
                m.put("url", url);
            }
            JSONObject latest = p.getJSONObject("latest_deployment");
            if (latest != null) {
                if (StrUtil.isBlank(url) && StrUtil.isNotBlank(latest.getStr("url"))) {
                    m.put("url", latest.getStr("url"));
                }
                m.put("deploymentUrl", latest.getStr("url"));
                m.put("deploymentStatus", latest.getStr("latest_stage"));
            }
            list.add(m);
        }
        return list;
    }

    private static String resolvePagesProjectUrl(JSONObject p) {
        JSONObject latest = p.getJSONObject("latest_deployment");
        if (latest != null && StrUtil.isNotBlank(latest.getStr("url"))) {
            return latest.getStr("url");
        }
        String subdomain = p.getStr("subdomain");
        if (StrUtil.isNotBlank(subdomain)) {
            if (subdomain.startsWith("http://") || subdomain.startsWith("https://")) {
                return subdomain;
            }
            if (subdomain.contains(".")) {
                return "https://" + subdomain;
            }
            return "https://" + subdomain + ".pages.dev";
        }
        String name = p.getStr("name");
        if (StrUtil.isNotBlank(name)) {
            return "https://" + name + ".pages.dev";
        }
        return null;
    }

    private String fetchAccountWorkersSubdomain(Credentials c) {
        try {
            String url = CF_API_BASE + "/accounts/" + c.accountId() + "/workers/subdomain";
            JSONObject json = parseJson(apiGet(c.apiToken(), url));
            if (!json.getBool("success", false)) {
                return null;
            }
            JSONObject result = json.getJSONObject("result");
            return result != null ? StrUtil.trimToNull(result.getStr("subdomain")) : null;
        } catch (Exception e) {
            log.debug("Workers 子域拉取跳过: {}", e.getMessage());
            return null;
        }
    }

    private String buildWorkerDevUrl(Credentials c, String scriptName, String accountSubdomain) {
        if (StrUtil.isBlank(scriptName)) {
            return null;
        }
        try {
            String url = CF_API_BASE + "/accounts/" + c.accountId() + "/workers/scripts/"
                    + urlEncodePath(scriptName) + "/subdomain";
            JSONObject json = parseJson(apiGet(c.apiToken(), url));
            JSONObject result = json.getJSONObject("result");
            if (result != null && Boolean.FALSE.equals(result.getBool("enabled"))) {
                return null;
            }
        } catch (Exception e) {
            log.debug("Worker subdomain 状态跳过 {}: {}", scriptName, e.getMessage());
        }
        if (StrUtil.isNotBlank(accountSubdomain)) {
            return "https://" + scriptName + "." + accountSubdomain + ".workers.dev";
        }
        return "https://" + scriptName + ".workers.dev";
    }

    private void ensurePagesProject(Credentials c, String projectName) {
        String getUrl = CF_API_BASE + "/accounts/" + c.accountId() + "/pages/projects/"
                + urlEncodePath(projectName);
        try {
            JSONObject getJson = parseJson(apiGet(c.apiToken(), getUrl));
            if (getJson.getBool("success", false)) {
                return;
            }
        } catch (OciException e) {
            // 404 等视为不存在，继续创建
            log.debug("Pages 项目不存在，准备创建: {}", projectName);
        }
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/pages/projects";
        Map<String, Object> body = Map.of(
                "name", projectName,
                "production_branch", "main");
        JSONObject json = parseJson(apiPost(c.apiToken(), url, body));
        requireSuccess(json, "创建 Pages 项目失败");
    }

    private void enableWorkerSubdomain(Credentials c, String scriptName) {
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/workers/scripts/"
                + urlEncodePath(scriptName) + "/subdomain";
        JSONObject json = parseJson(apiPut(c.apiToken(), url, Map.of("enabled", true)));
        requireSuccess(json, "启用 workers.dev 子域失败");
    }

    private static String resolveWorkerPublicUrl(String scriptName, String accountSubdomain) {
        if (StrUtil.isNotBlank(accountSubdomain)) {
            return "https://" + scriptName + "." + accountSubdomain + ".workers.dev";
        }
        return "https://" + scriptName + ".workers.dev";
    }

    private record HttpBinaryResponse(String contentType, byte[] body) {}

    private HttpBinaryResponse apiGetBinary(String token, String url) {
        try {
            HttpClient client = ociProxyConfigService.newOutboundHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> r = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = r.body() == null ? new byte[0] : r.body();
            if (r.statusCode() < 200 || r.statusCode() >= 400) {
                String errBody = body.length == 0 ? "" : new String(body, StandardCharsets.UTF_8);
                String msg = parseCfError(errBody);
                throw new OciException("HTTP " + r.statusCode() + (msg != null ? (": " + msg) : ""));
            }
            String contentType = r.headers().firstValue("Content-Type").orElse("");
            return new HttpBinaryResponse(contentType, body);
        } catch (OciException e) {
            throw e;
        } catch (IOException e) {
            throw new OciException("请求失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OciException("请求中断");
        }
    }

    private static Map<String, Object> parseDownloadedWorkerScript(byte[] raw, String contentType) {
        if (raw == null || raw.length == 0) {
            throw new OciException("Worker 脚本为空");
        }
        String boundary = extractMultipartBoundary(contentType);
        if (boundary == null) {
            String text = new String(raw, StandardCharsets.UTF_8).trim();
            if (text.startsWith("export") || text.startsWith("{")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("module", "worker.mjs");
                m.put("script", text);
                return m;
            }
            throw new OciException("无法解析 Worker 脚本响应");
        }
        String bodyText = new String(raw, StandardCharsets.UTF_8);
        String module = "worker.mjs";
        String script = extractMultipartPart(bodyText, boundary, "worker.mjs");
        if (StrUtil.isBlank(script)) {
            script = extractFirstScriptPart(bodyText, boundary);
            if (StrUtil.isBlank(script)) {
                throw new OciException("Worker 脚本中未找到可编辑模块");
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("module", module);
        m.put("script", script);
        return m;
    }

    private static String extractMultipartBoundary(String contentType) {
        if (StrUtil.isBlank(contentType)) {
            return null;
        }
        for (String part : contentType.split(";")) {
            String p = part.trim();
            if (p.startsWith("boundary=")) {
                String b = p.substring("boundary=".length()).trim();
                if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) {
                    b = b.substring(1, b.length() - 1);
                }
                return b;
            }
        }
        return null;
    }

    private static String extractMultipartPart(String body, String boundary, String fieldName) {
        String marker = "name=\"" + fieldName + "\"";
        int idx = body.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        return extractPartBody(body, idx);
    }

    private static String extractFirstScriptPart(String body, String boundary) {
        int searchFrom = 0;
        while (true) {
            int idx = body.indexOf("Content-Disposition:", searchFrom);
            if (idx < 0) {
                return null;
            }
            int lineEnd = body.indexOf('\n', idx);
            String headerLine = lineEnd > idx ? body.substring(idx, lineEnd) : body.substring(idx);
            if (headerLine.contains("name=\"metadata\"")) {
                searchFrom = idx + 1;
                continue;
            }
            if (headerLine.contains("filename=") || headerLine.contains("name=\"worker")) {
                String part = extractPartBody(body, idx);
                if (StrUtil.isNotBlank(part)) {
                    return part;
                }
            }
            searchFrom = idx + 1;
        }
    }

    private static String extractPartBody(String body, int dispositionIdx) {
        int headerEnd = body.indexOf("\r\n\r\n", dispositionIdx);
        if (headerEnd < 0) {
            headerEnd = body.indexOf("\n\n", dispositionIdx);
            if (headerEnd < 0) {
                return null;
            }
            headerEnd += 2;
        } else {
            headerEnd += 4;
        }
        int nextBoundary = body.indexOf("\r\n--", headerEnd);
        if (nextBoundary < 0) {
            nextBoundary = body.indexOf("\n--", headerEnd);
        }
        String content = nextBoundary > headerEnd ? body.substring(headerEnd, nextBoundary) : body.substring(headerEnd);
        return content.stripTrailing();
    }

    private Map<String, Object> uploadWorkerScript(String scriptName, String scriptContent) {
        Credentials c = requireCredentials();
        String name = normalizeWorkerScriptName(scriptName);
        String module = "worker.mjs";
        String metadata = JSONUtil.toJsonStr(Map.of(
                "main_module", module,
                "compatibility_date", "2024-09-23"));
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/workers/scripts/" + urlEncodePath(name);
        JSONObject json = parseJson(apiPutMultipart(c.apiToken(), url,
                Map.of("metadata", metadata),
                Map.of(module, scriptContent)));
        requireSuccess(json, "上传 Worker 脚本失败");
        JSONObject result = json.getJSONObject("result");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", name);
        m.put("kind", "worker");
        if (result != null) {
            m.put("createdOn", result.getStr("created_on"));
            m.put("modifiedOn", result.getStr("modified_on"));
        }
        return m;
    }

    private Map<String, Object> createPagesDeployment(Credentials c, String projectName,
                                                      List<Map<String, Object>> fileEntries) {
        Map<String, String> manifest = new LinkedHashMap<>();
        Map<String, byte[]> fileBytes = new LinkedHashMap<>();
        for (Map<String, Object> f : fileEntries) {
            String path = normalizePagesFilePath(String.valueOf(f.get("path")));
            Object raw = f.get("bytes");
            if (StrUtil.isBlank(path) || !(raw instanceof byte[] bytes) || bytes.length == 0) {
                continue;
            }
            manifest.put(path, sha256Hex(bytes));
            fileBytes.put(path, bytes);
        }
        if (manifest.isEmpty()) {
            throw new OciException("没有有效的静态文件");
        }
        String url = CF_API_BASE + "/accounts/" + c.accountId() + "/pages/projects/"
                + urlEncodePath(projectName) + "/deployments";
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("manifest", JSONUtil.toJsonStr(manifest));
        fields.put("branch", "main");
        JSONObject json = parseJson(apiPostMultipartBinary(c.apiToken(), url, fields, fileBytes));
        requireSuccess(json, "Pages 部署失败");
        JSONObject result = json.getJSONObject("result");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "pages");
        m.put("name", projectName);
        if (result != null) {
            m.put("id", result.getStr("id"));
            m.put("url", result.getStr("url"));
        }
        return m;
    }

    public List<Map<String, Object>> decodePagesUploadFiles(List<Map<String, String>> encodedFiles) {
        List<Map<String, Object>> files = new ArrayList<>();
        if (encodedFiles == null) {
            return files;
        }
        long totalBytes = 0L;
        for (Map<String, String> f : encodedFiles) {
            String path = f.get("path");
            String contentBase64 = f.get("contentBase64");
            if (StrUtil.isBlank(path) || StrUtil.isBlank(contentBase64)) {
                continue;
            }
            byte[] raw = Base64.getDecoder().decode(contentBase64);
            totalBytes += raw.length;
            if (files.size() >= MAX_PAGES_UPLOAD_FILES) {
                throw new OciException("单次最多上传 " + MAX_PAGES_UPLOAD_FILES + " 个文件");
            }
            if (totalBytes > MAX_PAGES_UPLOAD_BYTES) {
                throw new OciException("上传总大小不能超过 25 MiB");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", path);
            row.put("bytes", raw);
            files.add(row);
        }
        if (files.isEmpty()) {
            throw new OciException("没有有效的静态文件");
        }
        return normalizeDecodedUploadPaths(files);
    }

    /** 若所有路径共享同一顶层目录（文件夹上传），去掉该前缀 */
    private static List<Map<String, Object>> normalizeDecodedUploadPaths(List<Map<String, Object>> files) {
        if (files.size() <= 1) {
            return files;
        }
        String firstPath = String.valueOf(files.get(0).get("path"));
        int slash = firstPath.indexOf('/');
        if (slash <= 0) {
            return files;
        }
        String root = firstPath.substring(0, slash);
        boolean allShareRoot = true;
        for (Map<String, Object> f : files) {
            String p = String.valueOf(f.get("path"));
            if (!p.startsWith(root + "/")) {
                allShareRoot = false;
                break;
            }
        }
        if (!allShareRoot) {
            return files;
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> f : files) {
            String p = String.valueOf(f.get("path"));
            String stripped = p.substring(root.length() + 1);
            if (StrUtil.isBlank(stripped)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>(f);
            row.put("path", stripped);
            normalized.add(row);
        }
        return normalized.isEmpty() ? files : normalized;
    }

    private MetricQuery queryWorkersRequestsSum(Credentials c, Instant start, Instant end) {
        try {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("accountTag", c.accountId());
            variables.put("datetimeStart", start.toString());
            variables.put("datetimeEnd", end.toString());
            String gql = """
                    query WorkersUsage($accountTag: string!, $datetimeStart: string!, $datetimeEnd: string!) {
                      viewer {
                        accounts(filter: { accountTag: $accountTag }) {
                          workersInvocationsAdaptive(
                            limit: 10000,
                            filter: { datetime_geq: $datetimeStart, datetime_leq: $datetimeEnd }
                          ) {
                            sum { requests }
                          }
                        }
                      }
                    }""";
            JSONObject data = graphqlData(c, gql, variables);
            if (data == null) {
                return new MetricQuery(0L, false);
            }
            JSONObject viewer = data.getJSONObject("viewer");
            if (viewer == null) {
                return new MetricQuery(0L, false);
            }
            JSONArray accounts = viewer.getJSONArray("accounts");
            if (accounts == null || accounts.isEmpty()) {
                return new MetricQuery(0L, false);
            }
            JSONArray rows = accounts.getJSONObject(0).getJSONArray("workersInvocationsAdaptive");
            if (rows == null) {
                return new MetricQuery(0L, false);
            }
            long total = 0L;
            for (int i = 0; i < rows.size(); i++) {
                JSONObject row = rows.getJSONObject(i);
                JSONObject sum = row.getJSONObject("sum");
                if (sum != null && sum.get("requests") != null) {
                    total += sum.getLong("requests", 0L);
                }
            }
            return new MetricQuery(total, true);
        } catch (Exception e) {
            log.debug("Workers usage requests GraphQL skipped: {}", e.getMessage());
            return new MetricQuery(0L, false);
        }
    }

    private MetricQuery queryWorkersCpuTimeMs(Credentials c, Instant start, Instant end) {
        try {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("accountTag", c.accountId());
            variables.put("datetimeStart", start.toString());
            variables.put("datetimeEnd", end.toString());
            String gql = """
                    query WorkersCpu($accountTag: string!, $datetimeStart: string!, $datetimeEnd: string!) {
                      viewer {
                        accounts(filter: { accountTag: $accountTag }) {
                          workersInvocationsAdaptive(
                            limit: 10000,
                            filter: { datetime_geq: $datetimeStart, datetime_leq: $datetimeEnd }
                          ) {
                            sum { cpuTime }
                          }
                        }
                      }
                    }""";
            JSONObject data = graphqlData(c, gql, variables);
            if (data == null) {
                return new MetricQuery(0L, false);
            }
            JSONObject viewer = data.getJSONObject("viewer");
            if (viewer == null) {
                return new MetricQuery(0L, false);
            }
            JSONArray accounts = viewer.getJSONArray("accounts");
            if (accounts == null || accounts.isEmpty()) {
                return new MetricQuery(0L, false);
            }
            JSONArray rows = accounts.getJSONObject(0).getJSONArray("workersInvocationsAdaptive");
            if (rows == null) {
                return new MetricQuery(0L, false);
            }
            long total = 0L;
            for (int i = 0; i < rows.size(); i++) {
                JSONObject row = rows.getJSONObject(i);
                JSONObject sum = row.getJSONObject("sum");
                if (sum != null && sum.get("cpuTime") != null) {
                    total += sum.getLong("cpuTime", 0L);
                }
            }
            return new MetricQuery(total, true);
        } catch (Exception e) {
            log.debug("Workers CPU GraphQL skipped: {}", e.getMessage());
            return new MetricQuery(0L, false);
        }
    }

    private JSONObject graphqlData(Credentials c, String query, Map<String, Object> variables) {
        Map<String, Object> payload = Map.of("query", query, "variables", variables);
        JSONObject json = parseJson(apiPost(c.apiToken(), CF_API_BASE + "/graphql", payload));
        if (json.getJSONArray("errors") != null && !json.getJSONArray("errors").isEmpty()) {
            log.debug("GraphQL errors: {}", json.getJSONArray("errors"));
            return null;
        }
        return json.getJSONObject("data");
    }

    private static String formatUsageDateRange(Instant start, Instant end) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("M月d日").withZone(ZoneOffset.UTC);
        LocalDate startDate = start.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate endDate = end.atZone(ZoneOffset.UTC).toLocalDate();
        if (startDate.getYear() != endDate.getYear()) {
            DateTimeFormatter withYear = DateTimeFormatter.ofPattern("yyyy年M月d日").withZone(ZoneOffset.UTC);
            return withYear.format(start) + " - " + withYear.format(end);
        }
        return fmt.format(start) + " - " + fmt.format(end);
    }

    private static String sha256Hex(String content) {
        return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new OciException("计算文件哈希失败");
        }
    }

    private static String normalizeWorkerScriptName(String name) {
        String n = StrUtil.trimToNull(name);
        if (n == null) {
            throw new OciException("Worker 名称不能为空");
        }
        if (!n.matches("^[a-zA-Z0-9_-]{1,64}$")) {
            throw new OciException("Worker 名称仅允许字母、数字、下划线与连字符");
        }
        return n;
    }

    private static String normalizePagesProjectName(String name) {
        String n = StrUtil.trimToNull(name);
        if (n == null) {
            throw new OciException("项目名称不能为空");
        }
        n = n.toLowerCase().replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-");
        if (n.length() < 1 || n.length() > 58) {
            throw new OciException("项目名称长度须为 1–58 个字符");
        }
        return n;
    }

    private static String normalizePagesFilePath(String path) {
        String p = StrUtil.trimToNull(path);
        if (p == null) {
            throw new OciException("文件路径不能为空");
        }
        p = p.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.contains("..")) {
            throw new OciException("非法文件路径");
        }
        return p;
    }

    private static String urlEncodePath(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
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
        // CF: verified 为验证通过时间（ISO 8601），null/空 表示未验证
        String verifiedAt = d.getStr("verified");
        m.put("verifiedAt", verifiedAt);
        m.put("verified", StrUtil.isNotBlank(verifiedAt));
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
                .timeout(Duration.ofSeconds(120)));
    }

    private String apiPostMultipart(String token, String url, Map<String, String> formFields,
                                    Map<String, String> fileContents) {
        String boundary = "----CloudflareBoundary" + System.currentTimeMillis();
        byte[] body = buildMultipartBodyMulti(boundary, formFields, fileContents, false);
        return httpSend(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(180)));
    }

    private String apiPostMultipartBinary(String token, String url, Map<String, String> formFields,
                                            Map<String, byte[]> fileContents) {
        String boundary = "----CloudflareBoundary" + System.currentTimeMillis();
        byte[] body = buildMultipartBodyBinary(boundary, formFields, fileContents);
        return httpSend(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(180)));
    }

    private String apiPutMultipart(String token, String url, Map<String, String> formFields,
                                     Map<String, String> fileContents) {
        String boundary = "----CloudflareBoundary" + System.currentTimeMillis();
        byte[] body = buildMultipartBodyMulti(boundary, formFields, fileContents, true);
        return httpSend(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method("PUT", HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(120)));
    }

    private static byte[] buildMultipartBodyMulti(String boundary, Map<String, String> formFields,
                                                  Map<String, String> fileContents, boolean workerModule) {
        String lineEnd = "\r\n";
        StringBuilder sb = new StringBuilder();
        if (formFields != null) {
            for (Map.Entry<String, String> e : formFields.entrySet()) {
                sb.append("--").append(boundary).append(lineEnd);
                sb.append("Content-Disposition: form-data; name=\"").append(e.getKey()).append("\"").append(lineEnd);
                if ("metadata".equals(e.getKey()) && workerModule) {
                    sb.append("Content-Type: application/json").append(lineEnd);
                }
                sb.append(lineEnd);
                sb.append(e.getValue()).append(lineEnd);
            }
        }
        if (fileContents != null) {
            for (Map.Entry<String, String> e : fileContents.entrySet()) {
                sb.append("--").append(boundary).append(lineEnd);
                sb.append("Content-Disposition: form-data; name=\"").append(e.getKey())
                        .append("\"; filename=\"").append(e.getKey()).append("\"").append(lineEnd);
                String contentType = workerModule
                        ? "application/javascript+module"
                        : guessPagesContentType(e.getKey());
                sb.append("Content-Type: ").append(contentType).append(lineEnd);
                sb.append(lineEnd);
                sb.append(e.getValue()).append(lineEnd);
            }
        }
        sb.append("--").append(boundary).append("--").append(lineEnd);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String guessPagesContentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        }
        if (lower.endsWith(".css")) {
            return "text/css";
        }
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            return "application/javascript";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    private static byte[] buildMultipartBodyBinary(String boundary, Map<String, String> formFields,
                                                   Map<String, byte[]> fileContents) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String lineEnd = "\r\n";
            if (formFields != null) {
                for (Map.Entry<String, String> e : formFields.entrySet()) {
                    out.write(("--" + boundary + lineEnd).getBytes(StandardCharsets.UTF_8));
                    out.write(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"" + lineEnd).getBytes(StandardCharsets.UTF_8));
                    out.write(lineEnd.getBytes(StandardCharsets.UTF_8));
                    out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    out.write(lineEnd.getBytes(StandardCharsets.UTF_8));
                }
            }
            if (fileContents != null) {
                for (Map.Entry<String, byte[]> e : fileContents.entrySet()) {
                    out.write(("--" + boundary + lineEnd).getBytes(StandardCharsets.UTF_8));
                    out.write(("Content-Disposition: form-data; name=\"" + e.getKey()
                            + "\"; filename=\"" + e.getKey() + "\"" + lineEnd).getBytes(StandardCharsets.UTF_8));
                    out.write(("Content-Type: " + guessPagesContentType(e.getKey()) + lineEnd).getBytes(StandardCharsets.UTF_8));
                    out.write(lineEnd.getBytes(StandardCharsets.UTF_8));
                    out.write(e.getValue());
                    out.write(lineEnd.getBytes(StandardCharsets.UTF_8));
                }
            }
            out.write(("--" + boundary + "--" + lineEnd).getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new OciException("构建上传请求失败");
        }
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
