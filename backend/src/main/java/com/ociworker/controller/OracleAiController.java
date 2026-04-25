package com.ociworker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ociworker.model.entity.OciOpenaiKey;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.OciGenerativeOpenAiService;
import com.ociworker.service.OciOpenaiKeyService;
import com.ociworker.mapper.OciUserMapper;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    @PostMapping("/gateway")
    public ResponseData<?> gateway() {
        Map<String, Object> m = new HashMap<>();
        m.put("openaiApiPort", openaiApiPort);
        m.put("pathPrefix", "/v1");
        m.put("baseUrlExample", OciGenerativeOpenAiService.gatewayHint(openaiApiPort));
        return ResponseData.ok(m);
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
        d.put("warning", "此密钥只显示一次，请立即保存。对接 New API 时 API 地址为 http://<本机或域名>:" + openaiApiPort + "/v1");
        return ResponseData.ok(d);
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

    private static String trimToNullOrBlank(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
