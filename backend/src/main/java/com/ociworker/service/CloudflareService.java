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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        JSONObject verify = parseJson(apiGet(token, CF_API_BASE + "/user/tokens/verify"));
        requireSuccess(verify, "Token 验证失败");
        if (acc != null) {
            JSONObject accJson = parseJson(apiGet(token, CF_API_BASE + "/accounts/" + acc));
            requireSuccess(accJson, "Account ID 无效或无权限");
            JSONObject result = accJson.getJSONObject("result");
            String name = result != null ? result.getStr("name") : acc;
            return "连接成功：Token 有效，账户 " + name;
        }
        return "连接成功：Token 有效";
    }

  // -------------------------------------------------------------------------
    // Zones
    // -------------------------------------------------------------------------

    public List<Map<String, Object>> listZones(int page, int perPage) {
        Credentials c = requireCredentials();
        String url = String.format("%s/zones?page=%d&per_page=%d&account.id=%s",
                CF_API_BASE, page, perPage, c.accountId());
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取 Zone 列表失败");
        JSONArray result = json.getJSONArray("result");
        List<Map<String, Object>> zones = new ArrayList<>();
        if (result == null) {
            return zones;
        }
        for (int i = 0; i < result.size(); i++) {
            JSONObject z = result.getJSONObject(i);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", z.getStr("id"));
            map.put("name", z.getStr("name"));
            map.put("status", z.getStr("status"));
            map.put("paused", z.getBool("paused"));
            zones.add(map);
        }
        return zones;
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

    public List<Map<String, Object>> listDnsRecords(String zoneId, int page, int perPage) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = String.format("%s/zones/%s/dns_records?page=%d&per_page=%d",
                CF_API_BASE, zoneId.trim(), page, perPage);
        JSONObject json = parseJson(apiGet(c.apiToken(), url));
        requireSuccess(json, "拉取 DNS 记录失败");
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

    public void addDnsRecord(String zoneId, String type, String name, String content, boolean proxied, int ttl) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = String.format("%s/zones/%s/dns_records", CF_API_BASE, zoneId.trim());
        Map<String, Object> b = Map.of(
                "type", type, "name", name, "content", content,
                "proxied", proxied, "ttl", ttl);
        JSONObject json = parseJson(apiPost(c.apiToken(), url, b));
        requireSuccess(json, "添加 DNS 记录失败");
    }

    public void deleteDnsRecord(String zoneId, String recordId) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = String.format("%s/zones/%s/dns_records/%s", CF_API_BASE, zoneId.trim(), recordId);
        apiDelete(c.apiToken(), url);
    }

    public void updateDnsRecord(String zoneId, String recordId, String type, String name,
                                String content, boolean proxied, int ttl) {
        Credentials c = requireCredentials();
        requireZoneId(zoneId);
        String url = String.format("%s/zones/%s/dns_records/%s", CF_API_BASE, zoneId.trim(), recordId);
        Map<String, Object> b = Map.of(
                "type", type, "name", name, "content", content,
                "proxied", proxied, "ttl", ttl);
        JSONObject json = parseJson(apiPut(c.apiToken(), url, b));
        requireSuccess(json, "更新 DNS 记录失败");
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
