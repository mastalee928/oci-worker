package com.ociworker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.model.entity.OciOpenaiKey;
import com.ociworker.model.entity.OciOpenaiPortBinding;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.entity.OciKv;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.OciGenerativeOpenAiService;
import com.ociworker.service.OciOpenaiKeyService;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/oci/oracle-ai")
public class OracleAiController {

    @Value("${ociworker.openaiApi.port:8080}")
    private int openaiApiPort;

    @Resource
    private OciOpenaiKeyService openaiKeyService;
    @Resource
    private OciGenerativeOpenAiService generativeOpenAiService;
    @Resource
    private OciUserMapper ociUserMapper;
    @Resource
    private com.ociworker.service.OracleAiGatewayToggleService gatewayToggleService;
    @Resource
    private com.ociworker.service.OracleAiGatewayConfigService gatewayConfigService;
    @Resource
    private com.ociworker.service.OracleAiPortBindingService portBindingService;
    @Resource
    private com.ociworker.service.OciOpenaiLoadBalanceService loadBalanceService;
    @Resource
    private com.ociworker.service.OracleAiModelWhitelistService modelWhitelistService;
    @Resource
    private OciKvMapper kvMapper;

    private static final String UI_STATE_TYPE = "ui_state";
    private static final String UI_STATE_CODE = "oracle_ai.page_state.v1";
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final List<String> PUBLIC_IPV4_ENDPOINTS = List.of(
            "https://ipv4.icanhazip.com",
            "https://v4.ident.me",
            "https://api.ipify.org");
    private static final Duration PUBLIC_IP_CACHE_TTL = Duration.ofMinutes(10);
    private volatile String cachedPublicIp;
    private volatile Instant cachedPublicIpAt = Instant.EPOCH;
    private final AtomicBoolean publicIpRefreshing = new AtomicBoolean(false);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/gateway")
    public ResponseData<?> gateway() {
        Map<String, Object> m = new HashMap<>();
        m.put("openaiApiPort", openaiApiPort);
        m.put("pathPrefix", "/v1");
        m.put("baseUrlExample", OciGenerativeOpenAiService.gatewayHint(openaiApiPort));
        m.put("serverIp", detectServerIp());
        m.put("openaiProxyEnabled", gatewayToggleService.isEnabled());
        m.put("defaultMaxTokens", gatewayConfigService.getDefaultMaxTokens());
        return ResponseData.ok(m);
    }

    private String detectServerIp() {
        String cached = cachedPublicIp;
        if (cached != null && !cached.isBlank()
                && Duration.between(cachedPublicIpAt, Instant.now()).compareTo(PUBLIC_IP_CACHE_TTL) < 0) {
            return cached;
        }
        refreshPublicIpAsync();
        return cached == null ? "" : cached;
    }

    private void refreshPublicIpAsync() {
        if (!publicIpRefreshing.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("oracle-ai-public-ip-refresh").start(() -> {
            try {
                refreshPublicIp();
            } finally {
                publicIpRefreshing.set(false);
            }
        });
    }

    private void refreshPublicIp() {
        for (String endpoint : PUBLIC_IPV4_ENDPOINTS) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<String> resp = HttpClient.newHttpClient()
                        .send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    String ip = resp.body() == null ? "" : resp.body().trim();
                    if (IPV4_PATTERN.matcher(ip).matches()) {
                        cachedPublicIp = ip;
                        cachedPublicIpAt = Instant.now();
                        return;
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to detect public IPv4 from {}: {}", endpoint, e.getMessage());
            }
        }
    }

    /**
     * Oracle AI 页面：记住上次选择（无痕模式也可恢复）。
     * 存储位置：oci_kv(type=ui_state, code=oracle_ai.page_state.v1)
     */
    @PostMapping("/ui-state/get")
    public ResponseData<?> getUiState() {
        try {
            OciKv kv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getCode, UI_STATE_CODE)
                    .eq(OciKv::getType, UI_STATE_TYPE));
            if (kv == null || kv.getValue() == null || kv.getValue().isBlank()) {
                return ResponseData.ok(Map.of());
            }
            Object obj = objectMapper.readValue(kv.getValue(), Object.class);
            return ResponseData.ok(obj != null ? obj : Map.of());
        } catch (Exception e) {
            // 不影响主功能，读取失败时返回空
            return ResponseData.ok(Map.of());
        }
    }

    @PostMapping("/ui-state/save")
    @SuppressWarnings("unchecked")
    public ResponseData<?> saveUiState(@RequestBody Map<String, Object> body) {
        if (body == null) {
            return ResponseData.error("参数错误");
        }
        String ociUserId = body.get("ociUserId") == null ? "" : String.valueOf(body.get("ociUserId")).trim();
        // 仅保存“偏好/选择”，做个简单限长，避免被滥用塞超大 payload
        if (ociUserId.length() > 128) {
            ociUserId = ociUserId.substring(0, 128);
        }

        Map<String, Object> state = new HashMap<>();
        state.put("ociUserId", ociUserId);
        state.put("updateAt", System.currentTimeMillis());

        try {
            String json = objectMapper.writeValueAsString(state);
            OciKv existing = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getCode, UI_STATE_CODE)
                    .eq(OciKv::getType, UI_STATE_TYPE));
            if (existing != null) {
                existing.setValue(json);
                kvMapper.updateById(existing);
            } else {
                OciKv kv = new OciKv();
                kv.setId(CommonUtils.generateId());
                kv.setCode(UI_STATE_CODE);
                kv.setType(UI_STATE_TYPE);
                kv.setValue(json);
                kvMapper.insert(kv);
            }
            return ResponseData.ok();
        } catch (Exception e) {
            return ResponseData.error("保存失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }

    @PostMapping("/gateway/setEnabled")
    public ResponseData<?> setGatewayEnabled(@RequestBody Map<String, Object> body) {
        Object v = body == null ? null : body.get("enabled");
        boolean enabled = v instanceof Boolean ? (Boolean) v : (v != null && "true".equalsIgnoreCase(String.valueOf(v)));
        gatewayToggleService.setEnabled(enabled);
        return ResponseData.ok(Map.of("openaiProxyEnabled", enabled));
    }

    @PostMapping("/gateway/default-max-tokens")
    public ResponseData<?> setDefaultMaxTokens(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("defaultMaxTokens");
        if (raw == null) {
            raw = body == null ? null : body.get("max_tokens");
        }
        if (raw == null) {
            return ResponseData.error("defaultMaxTokens 必填");
        }
        int value;
        try {
            if (raw instanceof Number n) {
                value = n.intValue();
            } else {
                value = Integer.parseInt(String.valueOf(raw).trim());
            }
        } catch (Exception e) {
            return ResponseData.error("defaultMaxTokens 必须是数字");
        }
        int saved = gatewayConfigService.setDefaultMaxTokens(value);
        return ResponseData.ok(Map.of("defaultMaxTokens", saved));
    }

    @PostMapping("/keys/create")
    public ResponseData<?> createKey(@RequestBody Map<String, String> body) {
        String tid = body == null ? null : body.get("ociUserId");
        String name = body == null ? null : body.get("name");
        OciOpenaiKeyService.KeyCreateResult c = openaiKeyService.create(tid, name);
        Map<String, String> d = new HashMap<>();
        d.put("id", c.id());
        d.put("apiKey", c.plainKey());
        d.put("keyPrefix", c.keyPrefix());
        d.put("keyMasked", c.keyMasked());
        d.put("warning", "密钥已入库，可在列表中点击「查看」再次复制。对接 New API 时 API 地址为 http://<本机或域名>:" + openaiApiPort + "/v1");
        return ResponseData.ok(d);
    }

    @PostMapping("/keys/reveal")
    public ResponseData<?> revealKey(@RequestBody Map<String, String> body) {
        if (body == null || body.get("id") == null) {
            return ResponseData.error("id 必填");
        }
        try {
            String plain = openaiKeyService.revealPlainKey(body.get("id"));
            return ResponseData.ok(Map.of("apiKey", plain));
        } catch (com.ociworker.exception.OciException e) {
            return ResponseData.error(e.getMessage());
        }
    }

    @PostMapping("/keys/list")
    public ResponseData<?> listKeys(@RequestBody Map<String, String> body) {
        String tid = body == null ? null : body.get("ociUserId");
        List<OciOpenaiKey> list = openaiKeyService.listByTenant(tid);
        return ResponseData.ok(
                list.stream()
                        .map(
                                k -> {
                                    Map<String, Object> row = new HashMap<>();
                                    row.put("id", k.getId());
                                    row.put("name", k.getName());
                                    row.put("keyPrefix", k.getKeyPrefix());
                                    row.put("keyMasked", openaiKeyService.maskForList(k));
                                    row.put("disabled", k.getDisabled() != null && k.getDisabled() == 1);
                                    row.put("createTime", k.getCreateTime());
                                    row.put("lastUsed", k.getLastUsed());
                                    return row;
                                })
                        .collect(Collectors.toList()));
    }

    @PostMapping("/keys/setDisabled")
    public ResponseData<?> setDisabled(@RequestBody Map<String, Object> body) {
        if (body == null) {
            return ResponseData.error("参数错误");
        }
        String id = (String) body.get("id");
        Object d = body.get("disabled");
        boolean dis = d instanceof Boolean ? (Boolean) d : (d != null && "true".equals(d.toString()));
        openaiKeyService.setDisabled(id, dis);
        return ResponseData.ok();
    }

    @PostMapping("/keys/remove")
    public ResponseData<?> removeKey(@RequestBody Map<String, String> body) {
        if (body == null || body.get("id") == null) {
            return ResponseData.error("id 必填");
        }
        openaiKeyService.remove(body.get("id"));
        return ResponseData.ok();
    }

    @PostMapping("/ports/list")
    public ResponseData<?> listPortBindings() {
        List<OciOpenaiPortBinding> list = portBindingService.list();
        return ResponseData.ok(list.stream().map(this::portBindingRow).collect(Collectors.toList()));
    }

    @PostMapping("/ports/save")
    public ResponseData<?> savePortBinding(@RequestBody Map<String, Object> body) {
        try {
            String id = body == null ? null : trimObj(body.get("id"));
            String name = body == null ? null : trimObj(body.get("name"));
            String ociUserId = body == null ? null : trimObj(body.get("ociUserId"));
            String ociRegion = body == null ? null : trimObj(body.get("ociRegion"));
            String openaiKeyId = body == null ? null : trimObj(body.get("openaiKeyId"));
            int port = intValue(body == null ? null : body.get("port"), -1);
            Integer defaultMaxTokens = nullableIntValue(body == null ? null : body.get("defaultMaxTokens"));
            List<String> allowedModels = stringListValue(body == null ? null : body.get("allowedModels"));
            boolean enabled = boolValue(body == null ? null : body.get("enabled"), true);
            OciOpenaiPortBinding row = (id == null)
                    ? portBindingService.create(name, port, ociUserId, ociRegion, openaiKeyId, defaultMaxTokens, allowedModels, enabled)
                    : portBindingService.update(id, name, port, ociUserId, ociRegion, openaiKeyId, defaultMaxTokens, allowedModels, enabled);
            return ResponseData.ok(portBindingRow(row));
        } catch (com.ociworker.exception.OciException e) {
            return ResponseData.error(e.getMessage());
        } catch (Exception e) {
            return ResponseData.error(e.getMessage() != null ? e.getMessage() : "保存失败");
        }
    }

    @PostMapping("/ports/setEnabled")
    public ResponseData<?> setPortBindingEnabled(@RequestBody Map<String, Object> body) {
        String id = body == null ? null : trimObj(body.get("id"));
        if (id == null) {
            return ResponseData.error("id 必填");
        }
        boolean enabled = boolValue(body.get("enabled"), true);
        try {
            portBindingService.setEnabled(id, enabled);
            return ResponseData.ok();
        } catch (com.ociworker.exception.OciException e) {
            return ResponseData.error(e.getMessage());
        }
    }

    @PostMapping("/ports/remove")
    public ResponseData<?> removePortBinding(@RequestBody Map<String, Object> body) {
        String id = body == null ? null : trimObj(body.get("id"));
        if (id == null) {
            return ResponseData.error("id 必填");
        }
        portBindingService.remove(id);
        return ResponseData.ok();
    }

    @PostMapping("/lb/overview")
    public ResponseData<?> lbOverview() {
        return ResponseData.ok(loadBalanceService.overview());
    }

    @PostMapping("/lb/keys/create")
    public ResponseData<?> createLbKey(@RequestBody Map<String, String> body) {
        String name = body == null ? null : body.get("name");
        var created = loadBalanceService.createKey(name);
        Map<String, String> data = new HashMap<>();
        data.put("id", created.id());
        data.put("apiKey", created.plainKey());
        data.put("keyPrefix", created.keyPrefix());
        data.put("keyMasked", created.keyMasked());
        data.put("baseUrl", "http://<host>:" + com.ociworker.service.DynamicOpenAiPortService.loadBalancePort() + "/v1");
        return ResponseData.ok(data);
    }

    @PostMapping("/lb/keys/list")
    public ResponseData<?> listLbKeys() {
        return ResponseData.ok(loadBalanceService.listKeys());
    }

    @PostMapping("/lb/keys/reveal")
    public ResponseData<?> revealLbKey(@RequestBody Map<String, String> body) {
        String id = body == null ? null : body.get("id");
        if (id == null || id.isBlank()) {
            return ResponseData.error("id 必填");
        }
        try {
            return ResponseData.ok(Map.of("apiKey", loadBalanceService.revealPlainKey(id)));
        } catch (com.ociworker.exception.OciException e) {
            return ResponseData.error(e.getMessage());
        }
    }

    @PostMapping("/model-whitelist/get")
    public ResponseData<?> getModelWhitelist(@RequestBody Map<String, String> body) {
        String ociUserId = body == null ? null : trimToNullOrBlank(body.get("ociUserId"));
        if (ociUserId == null) {
            return ResponseData.error("租户不能为空");
        }
        OciUser u = ociUserMapper.selectById(ociUserId);
        if (u == null) {
            return ResponseData.error("租户不存在");
        }
        return ResponseData.ok(Map.of(
                "ociUserId", ociUserId,
                "allowedModels", modelWhitelistService.list(ociUserId)));
    }

    @PostMapping("/model-whitelist/save")
    public ResponseData<?> saveModelWhitelist(@RequestBody Map<String, Object> body) {
        String ociUserId = body == null ? null : trimObj(body.get("ociUserId"));
        if (ociUserId == null) {
            return ResponseData.error("租户不能为空");
        }
        OciUser u = ociUserMapper.selectById(ociUserId);
        if (u == null) {
            return ResponseData.error("租户不存在");
        }
        modelWhitelistService.save(ociUserId, stringListValue(body.get("allowedModels")));
        return ResponseData.ok(Map.of(
                "ociUserId", ociUserId,
                "allowedModels", modelWhitelistService.list(ociUserId)));
    }

    @PostMapping("/lb/keys/setDisabled")
    public ResponseData<?> setLbKeyDisabled(@RequestBody Map<String, Object> body) {
        String id = body == null ? null : trimObj(body.get("id"));
        if (id == null) {
            return ResponseData.error("id 必填");
        }
        loadBalanceService.setKeyDisabled(id, boolValue(body.get("disabled"), true));
        return ResponseData.ok();
    }

    @PostMapping("/lb/keys/remove")
    public ResponseData<?> removeLbKey(@RequestBody Map<String, String> body) {
        String id = body == null ? null : body.get("id");
        if (id == null || id.isBlank()) {
            return ResponseData.error("id 必填");
        }
        loadBalanceService.removeKey(id);
        return ResponseData.ok();
    }

    @PostMapping("/lb/members/list")
    public ResponseData<?> listLbMembers() {
        return ResponseData.ok(loadBalanceService.listMembers());
    }

    @PostMapping("/lb/members/save")
    public ResponseData<?> saveLbMember(@RequestBody Map<String, Object> body) {
        try {
            var row = loadBalanceService.saveMember(
                    body == null ? null : trimObj(body.get("id")),
                    body == null ? null : trimObj(body.get("portBindingId")),
                    nullableIntValue(body == null ? null : body.get("weight"), "权重必须是数字"),
                    boolValue(body == null ? null : body.get("enabled"), true),
                    nullableIntValue(body == null ? null : body.get("requestLimit5h"), "5小时请求上限必须是数字"),
                    nullableIntValue(body == null ? null : body.get("requestLimit7d"), "7天请求上限必须是数字"),
                    nullableIntValue(body == null ? null : body.get("maxConcurrency"), "最大并发必须是数字"),
                    nullableIntValue(body == null ? null : body.get("rpmLimit"), "RPM 上限必须是数字"),
                    nullableLongValue(body == null ? null : body.get("tpmLimit"), "TPM 上限必须是数字"),
                    nullableIntValue(body == null ? null : body.get("contextLimit"), "上下文上限必须是数字"),
                    nullableIntValue(body == null ? null : body.get("streamFirstChunkTimeoutSeconds"), "首块超时必须是数字"),
                    nullableIntValue(body == null ? null : body.get("streamIdleTimeoutSeconds"), "空闲超时必须是数字"),
                    nullableIntValue(body == null ? null : body.get("streamMaxSeconds"), "最长流时长必须是数字"));
            return ResponseData.ok(Map.of("id", row.getId()));
        } catch (com.ociworker.exception.OciException e) {
            return ResponseData.error(e.getMessage());
        } catch (Exception e) {
            return ResponseData.error(e.getMessage() != null ? e.getMessage() : "保存失败");
        }
    }

    @PostMapping("/lb/members/setEnabled")
    public ResponseData<?> setLbMemberEnabled(@RequestBody Map<String, Object> body) {
        String id = body == null ? null : trimObj(body.get("id"));
        if (id == null) {
            return ResponseData.error("id 必填");
        }
        loadBalanceService.setMemberEnabled(id, boolValue(body.get("enabled"), true));
        return ResponseData.ok();
    }

    @PostMapping("/lb/members/remove")
    public ResponseData<?> removeLbMember(@RequestBody Map<String, Object> body) {
        String id = body == null ? null : trimObj(body.get("id"));
        if (id == null) {
            return ResponseData.error("id 必填");
        }
        loadBalanceService.removeMember(id);
        return ResponseData.ok();
    }

    @PostMapping("/lb/members/model-state/clear")
    public ResponseData<?> clearLbMemberModelState(@RequestBody Map<String, Object> body) {
        String id = body == null ? null : trimObj(body.get("id"));
        if (id == null) {
            return ResponseData.error("id 必填");
        }
        loadBalanceService.clearMemberModelState(id, trimObj(body.get("model")));
        return ResponseData.ok();
    }

    @PostMapping("/lb/requests/list")
    public ResponseData<?> listLbRequests(@RequestBody(required = false) Map<String, Object> body) {
        int limit = intValue(body == null ? null : body.get("limit"), 50);
        return ResponseData.ok(loadBalanceService.recentRequests(limit));
    }

    @PostMapping("/models")
    public ResponseData<?> models(@RequestBody Map<String, String> body) {
        if (body == null || body.get("ociUserId") == null) {
            return ResponseData.error("ociUserId 必填");
        }
        OciUser u = ociUserMapper.selectById(body.get("ociUserId"));
        if (u == null) {
            return ResponseData.error("租户不存在");
        }
        String after = body.get("after");
        String modelId = body.get("modelId");
        String ociRegion = trimToNullOrBlank(body.get("ociRegion"));
        try {
            JsonNode j = generativeOpenAiService.getModelsAsJson(u, ociRegion, after, modelId);
            return ResponseData.ok(j);
        } catch (com.ociworker.exception.OciException e) {
            return ResponseData.error(e.getMessage());
        } catch (Exception e) {
            return ResponseData.error("拉取模型失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }

    @PostMapping("/generative-projects/list")
    public ResponseData<?> listGenerativeProjects(@RequestBody Map<String, String> body) {
        if (body == null || body.get("ociUserId") == null) {
            return ResponseData.error("ociUserId 必填");
        }
        OciUser u = ociUserMapper.selectById(body.get("ociUserId"));
        if (u == null) {
            return ResponseData.error("租户不存在");
        }
        String ociRegion = trimToNullOrBlank(body.get("ociRegion"));
        try {
            JsonNode j = generativeOpenAiService.listGenerativeAiProjectSummaries(u, ociRegion);
            return ResponseData.ok(j);
        } catch (com.ociworker.exception.OciException e) {
            return ResponseData.error(e.getMessage());
        } catch (Exception e) {
            return ResponseData.error("列举项目失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }

    @PostMapping("/generative-projects/create")
    public ResponseData<?> createGenerativeProject(@RequestBody Map<String, String> body) {
        if (body == null || body.get("ociUserId") == null) {
            return ResponseData.error("ociUserId 必填");
        }
        OciUser u = ociUserMapper.selectById(body.get("ociUserId"));
        if (u == null) {
            return ResponseData.error("租户不存在");
        }
        String displayName = body.get("displayName");
        String ociRegion = trimToNullOrBlank(body.get("ociRegion"));
        try {
            JsonNode j = generativeOpenAiService.createGenerativeAiProject(u, ociRegion, displayName);
            // 创建后自动写入当前 Region 的默认 OpenAI-Project，便于 Multi-Agent 直接可用
            if (j != null && j.isObject()) {
                String id = j.get("id") != null && j.get("id").isTextual() ? j.get("id").asText() : null;
                if (id != null && !id.isBlank()) {
                    if (ociRegion == null || ociRegion.isBlank()) {
                        u.setGenerativeOpenaiProject(id);
                        ociUserMapper.updateById(u);
                    }
                    generativeOpenAiService.saveGenerativeContext(
                            u,
                            ociRegion,
                            id,
                            u.getGenerativeConversationStoreId());
                }
            }
            return ResponseData.ok(j);
        } catch (com.ociworker.exception.OciException e) {
            return ResponseData.error(e.getMessage());
        } catch (Exception e) {
            return ResponseData.error("创建项目失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }

    @PostMapping("/generative-context/get")
    public ResponseData<?> getGenerativeContext(@RequestBody Map<String, String> body) {
        if (body == null || body.get("ociUserId") == null) {
            return ResponseData.error("ociUserId 必填");
        }
        OciUser u = ociUserMapper.selectById(body.get("ociUserId"));
        if (u == null) {
            return ResponseData.error("租户不存在");
        }
        String ociRegion = trimToNullOrBlank(body.get("ociRegion"));
        return ResponseData.ok(generativeOpenAiService.getGenerativeContext(u, ociRegion));
    }

    @PostMapping("/generative-context/save")
    public ResponseData<?> saveGenerativeContext(@RequestBody Map<String, String> body) {
        if (body == null || body.get("ociUserId") == null) {
            return ResponseData.error("ociUserId 必填");
        }
        OciUser u = ociUserMapper.selectById(body.get("ociUserId"));
        if (u == null) {
            return ResponseData.error("租户不存在");
        }
        String ociRegion = trimToNullOrBlank(body.get("ociRegion"));
        String project = trimToNullOrBlank(body.get("generativeOpenaiProject"));
        String store = trimToNullOrBlank(body.get("generativeConversationStoreId"));
        if (ociRegion == null || ociRegion.isBlank()) {
            u.setGenerativeOpenaiProject(project);
            u.setGenerativeConversationStoreId(store);
            ociUserMapper.updateById(u);
        }
        generativeOpenAiService.saveGenerativeContext(u, ociRegion, project, store);
        return ResponseData.ok();
    }

    /**
     * 面板内置对话测试：浏览器调用本接口（同源 /api），由后端在服务器本机直连 OpenAI 兼容端口（:openaiApiPort/v1）。
     * 解决浏览器无法访问 8080（防火墙/反代/跨域）的问题。
     */
    @PostMapping("/chat-test")
    public ResponseData<?> chatTest(@RequestBody Map<String, Object> body) {
        String apiKey = body == null ? null : String.valueOf(body.getOrDefault("apiKey", "")).trim();
        String model = body == null ? null : String.valueOf(body.getOrDefault("model", "")).trim();
        String input = body == null ? null : String.valueOf(body.getOrDefault("input", "")).trim();
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseData.error("apiKey 必填");
        }
        if (model == null || model.isBlank()) {
            return ResponseData.error("model 必填");
        }
        if (input == null || input.isBlank()) {
            return ResponseData.error("input 必填");
        }
        String bearer = apiKey.toLowerCase().startsWith("bearer ") ? apiKey : "Bearer " + apiKey;
        boolean multiAgent = model.toLowerCase().contains("multi-agent")
                || model.toLowerCase().contains("multi agent")
                || model.toLowerCase().contains("multiagent");
        // Multi-Agent 在 OCI 只能走 /v1/responses（chat/completions 会直接 400）
        String url = "http://127.0.0.1:" + openaiApiPort + (multiAgent ? "/v1/responses" : "/v1/chat/completions");
        log.info("chat-test -> {} model={} (multiAgent={})", url, model, multiAgent);
        String payload;
        if (multiAgent) {
            payload = "{\"model\":" + jsonString(model)
                    + ",\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":"
                    + jsonString(input)
                    + "}]}],\"stream\":false}";
        } else {
            payload = "{\"model\":" + jsonString(model)
                    + ",\"messages\":[{\"role\":\"user\",\"content\":" + jsonString(input) + "}],\"stream\":false}";
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(120))
                    .header("content-type", "application/json")
                    .header("authorization", bearer)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> out = new HashMap<>();
            out.put("status", resp.statusCode());
            out.put("body", resp.body() != null ? resp.body() : "");
            return ResponseData.ok(out);
        } catch (Exception e) {
            return ResponseData.error("chat-test 调用失败: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        String t = s.replace("\\", "\\\\").replace("\"", "\\\"");
        t = t.replace("\r", "\\r").replace("\n", "\\n");
        return "\"" + t + "\"";
    }

    private static String trimToNullOrBlank(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String trimObj(Object v) {
        return v == null ? null : trimToNullOrBlank(String.valueOf(v));
    }

    private static int intValue(Object v, int def) {
        if (v == null) {
            return def;
        }
        try {
            if (v instanceof Number n) {
                return n.intValue();
            }
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private static Integer nullableIntValue(Object v) {
        return nullableIntValue(v, "defaultMaxTokens 必须是数字");
    }

    private static Integer nullableIntValue(Object v, String message) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            if (v instanceof Number n) {
                return n.intValue();
            }
            return Integer.parseInt(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(message);
        }
    }

    private static Long nullableLongValue(Object v) {
        return nullableLongValue(v, "数值必须是数字");
    }

    private static Long nullableLongValue(Object v, String message) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            if (v instanceof Number n) {
                return n.longValue();
            }
            return Long.parseLong(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean boolValue(Object v, boolean def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
            return true;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) {
            return false;
        }
        return def;
    }

    private static List<String> stringListValue(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        } else if (v != null) {
            String s = String.valueOf(v).trim();
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return com.ociworker.service.OracleAiPortBindingService.normalizeAllowedModels(out);
    }

    private Map<String, Object> portBindingRow(OciOpenaiPortBinding b) {
        Map<String, Object> row = new HashMap<>();
        if (b == null) {
            return row;
        }
        row.put("id", b.getId());
        row.put("name", b.getName());
        row.put("port", b.getPort());
        row.put("ociUserId", b.getOciUserId());
        row.put("ociRegion", b.getOciRegion());
        row.put("openaiKeyId", b.getOpenaiKeyId());
        row.put("defaultMaxTokens", b.getDefaultMaxTokens());
        row.put("allowedModels", com.ociworker.service.OracleAiPortBindingService.decodeAllowedModels(b.getAllowedModelsJson()));
        row.put("enabled", b.getEnabled() != null && b.getEnabled() == 1);
        row.put("status", b.getStatus());
        row.put("statusMessage", b.getStatusMessage());
        row.put("createTime", b.getCreateTime());
        row.put("updateTime", b.getUpdateTime());
        row.put("lastUsed", b.getLastUsed());
        row.put("baseUrl", "http://<host>:" + b.getPort() + "/v1");

        OciUser u = b.getOciUserId() == null ? null : ociUserMapper.selectById(b.getOciUserId());
        if (u != null) {
            row.put("tenantName", u.getUsername());
            if (row.get("ociRegion") == null || String.valueOf(row.get("ociRegion")).isBlank()) {
                row.put("ociRegion", u.getOciRegion());
            }
            row.put("tenantDefaultRegion", u.getOciRegion());
        }
        OciOpenaiKey key = b.getOpenaiKeyId() == null ? null : openaiKeyService.getById(b.getOpenaiKeyId());
        if (key != null) {
            row.put("keyMasked", openaiKeyService.maskForList(key));
            row.put("keyName", key.getName());
            row.put("keyDisabled", key.getDisabled() != null && key.getDisabled() == 1);
        }
        return row;
    }
}
