package com.ociworker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.model.entity.OciOpenaiKey;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.entity.OciKv;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.OciGenerativeOpenAiService;
import com.ociworker.service.OciOpenaiKeyService;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

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
    private OciKvMapper kvMapper;

    private static final String UI_STATE_TYPE = "ui_state";
    private static final String UI_STATE_CODE = "oracle_ai.page_state.v1";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/gateway")
    public ResponseData<?> gateway() {
        Map<String, Object> m = new HashMap<>();
        m.put("openaiApiPort", openaiApiPort);
        m.put("pathPrefix", "/v1");
        m.put("baseUrlExample", OciGenerativeOpenAiService.gatewayHint(openaiApiPort));
        m.put("openaiProxyEnabled", gatewayToggleService.isEnabled());
        return ResponseData.ok(m);
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
        Object mp = body.get("modelPick");
        java.util.List<String> modelPick = new java.util.ArrayList<>();
        if (mp instanceof java.util.List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    String s = String.valueOf(o).trim();
                    if (!s.isBlank()) {
                        modelPick.add(s);
                    }
                }
            }
        }
        // 仅保存“偏好/选择”，做个简单限长，避免被滥用塞超大 payload
        if (ociUserId.length() > 128) {
            ociUserId = ociUserId.substring(0, 128);
        }
        if (modelPick.size() > 200) {
            modelPick = modelPick.subList(0, 200);
        }

        Map<String, Object> state = new HashMap<>();
        state.put("ociUserId", ociUserId);
        state.put("modelPick", modelPick);
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
        try {
            JsonNode j = generativeOpenAiService.getModelsAsJson(u, after, modelId);
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
        try {
            JsonNode j = generativeOpenAiService.listGenerativeAiProjectSummaries(u);
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
        try {
            JsonNode j = generativeOpenAiService.createGenerativeAiProject(u, displayName);
            // 创建后自动写入租户默认 OpenAI-Project，便于 Multi-Agent 直接可用
            if (j != null && j.isObject()) {
                String id = j.get("id") != null && j.get("id").isTextual() ? j.get("id").asText() : null;
                if (id != null && !id.isBlank()) {
                    u.setGenerativeOpenaiProject(id);
                    ociUserMapper.updateById(u);
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
        Map<String, String> m = new HashMap<>();
        m.put("generativeOpenaiProject", u.getGenerativeOpenaiProject());
        m.put("generativeConversationStoreId", u.getGenerativeConversationStoreId());
        return ResponseData.ok(m);
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
        u.setGenerativeOpenaiProject(trimToNullOrBlank(body.get("generativeOpenaiProject")));
        u.setGenerativeConversationStoreId(trimToNullOrBlank(body.get("generativeConversationStoreId")));
        ociUserMapper.updateById(u);
        return ResponseData.ok();
    }

    /**
     * 面板内置对话测试：浏览器调用本接口（同源 /api），由后端在服务器本机直连 OpenAI 兼容端口（:openaiApiPort/v1）。
     * 解决浏览器无法访问 8080（防火墙/反代/跨域）的问题。
     */
    /**
     * 面板对话测试：SSE 真流式；统一走 /v1/chat/completions + stream（Multi-Agent 由网关改写到 /v1/responses 并转译 SSE）。
     */
    @PostMapping(value = "/chat-test", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void chatTest(@RequestBody Map<String, Object> body, HttpServletResponse servletResponse) throws Exception {
        String apiKey = body == null ? null : String.valueOf(body.getOrDefault("apiKey", "")).trim();
        String model = body == null ? null : String.valueOf(body.getOrDefault("model", "")).trim();
        String input = body == null ? null : String.valueOf(body.getOrDefault("input", "")).trim();
        if (apiKey == null || apiKey.isBlank()) {
            writeChatTestError(servletResponse, 400, "apiKey 必填");
            return;
        }
        if (model == null || model.isBlank()) {
            writeChatTestError(servletResponse, 400, "model 必填");
            return;
        }
        if (input == null || input.isBlank()) {
            writeChatTestError(servletResponse, 400, "input 必填");
            return;
        }
        String bearer = apiKey.toLowerCase().startsWith("bearer ") ? apiKey : "Bearer " + apiKey;
        String token = bearer.length() > 7 ? bearer.substring(7).trim() : "";
        OciUser keyTenant = null;
        if (!token.isEmpty()) {
            com.ociworker.model.entity.OciOpenaiKey keyRow = openaiKeyService.findByPlainKey(token);
            if (keyRow != null && keyRow.getOciUserId() != null) {
                keyTenant = ociUserMapper.selectById(keyRow.getOciUserId());
            }
        }
        if (isMultiAgentModelName(model)) {
            if (keyTenant == null) {
                writeChatTestError(servletResponse, 400, "API Key 无效或未绑定租户，无法调用 Multi-Agent");
                return;
            }
            String project = keyTenant.getGenerativeOpenaiProject();
            String store = keyTenant.getGenerativeConversationStoreId();
            boolean hasProject = project != null && !project.isBlank();
            boolean hasStore = store != null && !store.isBlank();
            if (!hasProject && !hasStore) {
                writeChatTestError(
                        servletResponse,
                        400,
                        "Multi-Agent 需要 OpenAI-Project（Generative AI Project OCID）。"
                                + "请在上方「Multi-Agent 上下文」一键创建或保存后再试。");
                return;
            }
        }
        String url = "http://127.0.0.1:" + openaiApiPort + "/v1/chat/completions";
        String payload =
                "{\"model\":"
                        + jsonString(model)
                        + ",\"messages\":[{\"role\":\"user\",\"content\":"
                        + jsonString(input)
                        + "}],\"stream\":true}";
        log.info("chat-test(stream) -> {} model={}", url, model);
        servletResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        servletResponse.setHeader("Cache-Control", "no-cache");
        servletResponse.setHeader("X-Accel-Buffering", "no");
        HttpRequest.Builder reqBuilder =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(java.time.Duration.ofSeconds(300))
                        .header("content-type", "application/json")
                        .header("accept", "text/event-stream")
                        .header("authorization", bearer);
        if (keyTenant != null) {
            String project = keyTenant.getGenerativeOpenaiProject();
            if (project != null && !project.isBlank()) {
                reqBuilder.header("OpenAI-Project", project.trim());
            }
            String store = keyTenant.getGenerativeConversationStoreId();
            if (store != null && !store.isBlank()) {
                reqBuilder.header("opc-conversation-store-id", store.trim());
            }
        }
        HttpRequest req =
                reqBuilder.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
        HttpResponse<InputStream> resp =
                HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofInputStream());
        servletResponse.setStatus(resp.statusCode());
        if (resp.statusCode() >= 400) {
            byte[] err = resp.body() != null ? resp.body().readAllBytes() : new byte[0];
            servletResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            servletResponse.getOutputStream().write(err);
            servletResponse.getOutputStream().flush();
            return;
        }
        servletResponse.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE + "; charset=utf-8");
        try (InputStream in = resp.body();
                OutputStream out = servletResponse.getOutputStream()) {
            if (in == null) {
                return;
            }
            in.transferTo(out);
            out.flush();
        }
    }

    private static void writeChatTestError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String json = "{\"code\":1,\"message\":" + jsonString(message) + "}";
        response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
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

    private static boolean isMultiAgentModelName(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String t = model.toLowerCase(Locale.ROOT);
        return t.contains("multi-agent") || t.contains("multi agent") || t.contains("multiagent");
    }
}
