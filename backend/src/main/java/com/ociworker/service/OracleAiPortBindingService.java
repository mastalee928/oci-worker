package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciOpenaiKeyMapper;
import com.ociworker.mapper.OciOpenaiPortBindingMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciOpenaiKey;
import com.ociworker.model.entity.OciOpenaiPortBinding;
import com.ociworker.model.entity.OciUser;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class OracleAiPortBindingService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private OciOpenaiPortBindingMapper bindingMapper;
    @Resource
    private OciOpenaiKeyMapper keyMapper;
    @Resource
    private OciUserMapper userMapper;
    @Resource
    private DynamicOpenAiPortService dynamicPortService;

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public void onWebServerInitialized(WebServerInitializedEvent event) {
        syncEnabledConnectors();
    }

    public List<OciOpenaiPortBinding> list() {
        return bindingMapper.selectList(
                new LambdaQueryWrapper<OciOpenaiPortBinding>()
                        .orderByAsc(OciOpenaiPortBinding::getPort)
                        .orderByDesc(OciOpenaiPortBinding::getCreateTime));
    }

    public OciOpenaiPortBinding getByPort(int port) {
        if (!DynamicOpenAiPortService.isManagedPort(port)) {
            return null;
        }
        return bindingMapper.selectOne(new LambdaQueryWrapper<OciOpenaiPortBinding>()
                .eq(OciOpenaiPortBinding::getPort, port));
    }

    @Transactional(rollbackFor = Exception.class)
    public OciOpenaiPortBinding create(
            String name,
            int port,
            String ociUserId,
            String openaiKeyId,
            Integer defaultMaxTokens,
            List<String> allowedModels,
            boolean enabled) {
        DynamicOpenAiPortService.validateManagedPort(port);
        validateTenantAndKey(ociUserId, openaiKeyId);
        OciOpenaiPortBinding existing = getByPort(port);
        if (existing != null) {
            throw new OciException("端口已绑定: " + port);
        }
        OciOpenaiPortBinding row = new OciOpenaiPortBinding();
        row.setId(CommonUtils.generateId());
        row.setName(trimName(name));
        row.setPort(port);
        row.setOciUserId(ociUserId);
        row.setOpenaiKeyId(openaiKeyId);
        row.setDefaultMaxTokens(normalizeDefaultMaxTokens(defaultMaxTokens));
        row.setAllowedModelsJson(encodeAllowedModels(allowedModels));
        row.setEnabled(enabled ? 1 : 0);
        row.setStatus("stopped");
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        bindingMapper.insert(row);
        if (enabled) {
            startAndMark(row);
        }
        return bindingMapper.selectById(row.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public OciOpenaiPortBinding update(
            String id,
            String name,
            int port,
            String ociUserId,
            String openaiKeyId,
            Integer defaultMaxTokens,
            List<String> allowedModels,
            boolean enabled) {
        OciOpenaiPortBinding row = bindingMapper.selectById(id);
        if (row == null) {
            throw new OciException("绑定不存在");
        }
        DynamicOpenAiPortService.validateManagedPort(port);
        validateTenantAndKey(ociUserId, openaiKeyId);
        OciOpenaiPortBinding samePort = getByPort(port);
        if (samePort != null && !samePort.getId().equals(id)) {
            throw new OciException("端口已绑定: " + port);
        }
        int oldPort = row.getPort() == null ? -1 : row.getPort();
        row.setName(trimName(name));
        row.setPort(port);
        row.setOciUserId(ociUserId);
        row.setOpenaiKeyId(openaiKeyId);
        row.setDefaultMaxTokens(normalizeDefaultMaxTokens(defaultMaxTokens));
        row.setAllowedModelsJson(encodeAllowedModels(allowedModels));
        row.setEnabled(enabled ? 1 : 0);
        row.setUpdateTime(LocalDateTime.now());
        bindingMapper.updateById(row);

        if (enabled) {
            startAndMark(row);
            if (oldPort != port) {
                dynamicPortService.stopPort(oldPort);
            }
        } else {
            dynamicPortService.stopPort(port);
            if (oldPort != port) {
                dynamicPortService.stopPort(oldPort);
            }
            markStatus(id, "disabled", null);
        }
        return bindingMapper.selectById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void setEnabled(String id, boolean enabled) {
        OciOpenaiPortBinding row = bindingMapper.selectById(id);
        if (row == null) {
            return;
        }
        row.setEnabled(enabled ? 1 : 0);
        row.setUpdateTime(LocalDateTime.now());
        bindingMapper.updateById(row);
        if (enabled) {
            startAndMark(row);
        } else {
            dynamicPortService.stopPort(row.getPort());
            markStatus(row.getId(), "disabled", null);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(String id) {
        OciOpenaiPortBinding row = bindingMapper.selectById(id);
        if (row != null && row.getPort() != null) {
            dynamicPortService.stopPort(row.getPort());
        }
        bindingMapper.deleteById(id);
    }

    public void syncEnabledConnectors() {
        List<OciOpenaiPortBinding> rows = bindingMapper.selectList(new LambdaQueryWrapper<OciOpenaiPortBinding>()
                .eq(OciOpenaiPortBinding::getEnabled, 1));
        for (OciOpenaiPortBinding row : rows) {
            try {
                startAndMark(row);
            } catch (Exception ignored) {
            }
        }
    }

    public void touchLastUsed(String id) {
        if (id == null) {
            return;
        }
        bindingMapper.update(
                null,
                new LambdaUpdateWrapper<OciOpenaiPortBinding>()
                        .set(OciOpenaiPortBinding::getLastUsed, LocalDateTime.now())
                        .eq(OciOpenaiPortBinding::getId, id));
    }

    public void markStatus(String id, String status, String message) {
        if (id == null) {
            return;
        }
        bindingMapper.update(
                null,
                new LambdaUpdateWrapper<OciOpenaiPortBinding>()
                        .set(OciOpenaiPortBinding::getStatus, status)
                        .set(OciOpenaiPortBinding::getStatusMessage, message)
                        .set(OciOpenaiPortBinding::getUpdateTime, LocalDateTime.now())
                        .eq(OciOpenaiPortBinding::getId, id));
    }

    private void startAndMark(OciOpenaiPortBinding row) {
        try {
            dynamicPortService.startPort(row.getPort());
            markStatus(row.getId(), "listening", null);
        } catch (Exception e) {
            markStatus(row.getId(), "failed", e.getMessage());
            throw new OciException(e.getMessage());
        }
    }

    private void validateTenantAndKey(String ociUserId, String openaiKeyId) {
        if (ociUserId == null || ociUserId.isBlank()) {
            throw new OciException("请选择租户");
        }
        if (openaiKeyId == null || openaiKeyId.isBlank()) {
            throw new OciException("请选择 API Key");
        }
        OciUser user = userMapper.selectById(ociUserId);
        if (user == null) {
            throw new OciException("租户不存在");
        }
        OciOpenaiKey key = keyMapper.selectById(openaiKeyId);
        if (key == null) {
            throw new OciException("API Key 不存在");
        }
        if (key.getDisabled() != null && key.getDisabled() == 1) {
            throw new OciException("API Key 已禁用");
        }
        if (!ociUserId.equals(key.getOciUserId())) {
            throw new OciException("API Key 不属于所选租户");
        }
    }

    private static String trimName(String name) {
        if (name == null) {
            return null;
        }
        String s = name.trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > 128 ? s.substring(0, 128) : s;
    }

    private static Integer normalizeDefaultMaxTokens(Integer value) {
        if (value == null) {
            return null;
        }
        return OracleAiGatewayConfigService.normalizeDefaultMaxTokens(value);
    }

    public static List<String> decodeAllowedModels(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root != null && root.isArray()) {
                for (JsonNode n : root) {
                    if (n != null && n.isTextual()) {
                        String s = n.asText().trim();
                        if (!s.isBlank()) {
                            out.add(s);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return normalizeAllowedModels(out);
    }

    public static List<String> normalizeAllowedModels(List<String> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String raw : input) {
            if (raw == null) {
                continue;
            }
            String s = raw.trim();
            if (s.isBlank()) {
                continue;
            }
            if (s.length() > 256) {
                s = s.substring(0, 256);
            }
            set.add(s);
            if (set.size() >= 200) {
                break;
            }
        }
        return new ArrayList<>(set);
    }

    private static String encodeAllowedModels(List<String> input) {
        List<String> normalized = normalizeAllowedModels(input);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new OciException("allowedModels 保存失败");
        }
    }
}
