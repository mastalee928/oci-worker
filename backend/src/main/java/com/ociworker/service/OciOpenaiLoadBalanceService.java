package com.ociworker.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciOpenaiKeyMapper;
import com.ociworker.mapper.OciOpenaiLbKeyMapper;
import com.ociworker.mapper.OciOpenaiLbMemberMapper;
import com.ociworker.mapper.OciOpenaiLbUsageWindowMapper;
import com.ociworker.mapper.OciOpenaiPortBindingMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciOpenaiKey;
import com.ociworker.model.entity.OciOpenaiLbKey;
import com.ociworker.model.entity.OciOpenaiLbMember;
import com.ociworker.model.entity.OciOpenaiLbUsageWindow;
import com.ociworker.model.entity.OciOpenaiPortBinding;
import com.ociworker.model.entity.OciUser;
import com.ociworker.util.CommonUtils;
import com.ociworker.util.OciOpenaiKeyCipher;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OciOpenaiLoadBalanceService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_PREFIX = "sk-lb-";

    @Resource
    private DynamicOpenAiPortService dynamicPortService;
    @Resource
    private OciOpenaiLbKeyMapper lbKeyMapper;
    @Resource
    private OciOpenaiLbMemberMapper memberMapper;
    @Resource
    private OciOpenaiLbUsageWindowMapper usageWindowMapper;
    @Resource
    private OciOpenaiPortBindingMapper portBindingMapper;
    @Resource
    private OciOpenaiKeyMapper openaiKeyMapper;
    @Resource
    private OciOpenaiKeyService openaiKeyService;
    @Resource
    private OciUserMapper userMapper;
    @Resource
    private OciGenerativeOpenAiService generativeOpenAiService;

    @Value("${web.password}")
    private String webPassword;

    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE + 20)
    public void onWebServerInitialized(WebServerInitializedEvent event) {
        try {
            dynamicPortService.startLoadBalancePort();
        } catch (Exception e) {
            log.warn("OpenAI load-balance connector start failed: {}", e.getMessage());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public KeyCreateResult createKey(String name) {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        StringBuilder builder = new StringBuilder(KEY_PREFIX);
        for (byte item : buf) {
            builder.append(String.format("%02x", item));
        }
        String plain = builder.toString();
        OciOpenaiLbKey row = new OciOpenaiLbKey();
        row.setId(CommonUtils.generateId());
        row.setKeyHash(DigestUtil.sha256Hex(plain));
        row.setKeyPrefix(plain.substring(0, Math.min(16, plain.length())));
        row.setKeyEncrypted(OciOpenaiKeyCipher.encrypt(plain, webPassword));
        row.setName(trimName(name));
        row.setDisabled(0);
        row.setCreateTime(LocalDateTime.now());
        lbKeyMapper.insert(row);
        return new KeyCreateResult(row.getId(), plain, row.getKeyPrefix(), OciOpenaiKeyCipher.maskForDisplay(plain));
    }

    public List<Map<String, Object>> listKeys() {
        return lbKeyMapper.selectList(new LambdaQueryWrapper<OciOpenaiLbKey>()
                        .orderByDesc(OciOpenaiLbKey::getCreateTime))
                .stream()
                .map(this::keyRow)
                .collect(Collectors.toList());
    }

    public OciOpenaiLbKey findKeyByPlain(String plain) {
        if (plain == null || plain.isBlank() || !plain.startsWith(KEY_PREFIX)) {
            return null;
        }
        return lbKeyMapper.selectOne(new LambdaQueryWrapper<OciOpenaiLbKey>()
                .eq(OciOpenaiLbKey::getKeyHash, DigestUtil.sha256Hex(plain)));
    }

    public String revealPlainKey(String id) {
        OciOpenaiLbKey key = lbKeyMapper.selectById(id);
        if (key == null) {
            throw new OciException("负载均衡 Key 不存在");
        }
        if (key.getKeyEncrypted() == null || key.getKeyEncrypted().isBlank()) {
            throw new OciException("该 Key 未保存完整密钥，请删除后重新生成");
        }
        return OciOpenaiKeyCipher.decrypt(key.getKeyEncrypted(), webPassword);
    }

    @Transactional(rollbackFor = Exception.class)
    public void setKeyDisabled(String id, boolean disabled) {
        OciOpenaiLbKey key = lbKeyMapper.selectById(id);
        if (key == null) {
            return;
        }
        key.setDisabled(disabled ? 1 : 0);
        lbKeyMapper.updateById(key);
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeKey(String id) {
        lbKeyMapper.deleteById(id);
    }

    public void touchKey(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        lbKeyMapper.update(null, new LambdaUpdateWrapper<OciOpenaiLbKey>()
                .set(OciOpenaiLbKey::getLastUsed, LocalDateTime.now())
                .eq(OciOpenaiLbKey::getId, id));
    }

    public List<Map<String, Object>> listMembers() {
        List<OciOpenaiLbMember> rows = memberMapper.selectList(new LambdaQueryWrapper<OciOpenaiLbMember>()
                .orderByDesc(OciOpenaiLbMember::getEnabled)
                .orderByAsc(OciOpenaiLbMember::getCreateTime));
        return rows.stream().map(this::memberRow).collect(Collectors.toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public OciOpenaiLbMember saveMember(
            String id,
            String portBindingId,
            Integer weight,
            boolean enabled,
            Integer requestLimit5h,
            Integer requestLimit7d) {
        if (portBindingId == null || portBindingId.isBlank()) {
            throw new OciException("请选择中转成员");
        }
        OciOpenaiPortBinding binding = portBindingMapper.selectById(portBindingId);
        if (binding == null) {
            throw new OciException("中转端口不存在");
        }
        int normalizedWeight = weight == null || weight < 1 ? 1 : Math.min(weight, 1000);
        OciOpenaiLbMember row = id == null || id.isBlank() ? null : memberMapper.selectById(id);
        if (row == null) {
            OciOpenaiLbMember existing = memberMapper.selectOne(new LambdaQueryWrapper<OciOpenaiLbMember>()
                    .eq(OciOpenaiLbMember::getPortBindingId, portBindingId));
            row = existing == null ? new OciOpenaiLbMember() : existing;
            if (existing == null) {
                row.setId(CommonUtils.generateId());
                row.setPortBindingId(portBindingId);
                row.setFailCount(0);
                row.setCreateTime(LocalDateTime.now());
            }
        }
        row.setWeight(normalizedWeight);
        row.setEnabled(enabled ? 1 : 0);
        row.setRequestLimit5h(normalizeLimit(requestLimit5h));
        row.setRequestLimit7d(normalizeLimit(requestLimit7d));
        row.setUpdateTime(LocalDateTime.now());
        if (memberMapper.selectById(row.getId()) == null) {
            memberMapper.insert(row);
        } else {
            memberMapper.updateById(row);
        }
        return memberMapper.selectById(row.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void setMemberEnabled(String id, boolean enabled) {
        OciOpenaiLbMember row = memberMapper.selectById(id);
        if (row == null) {
            return;
        }
        row.setEnabled(enabled ? 1 : 0);
        row.setUpdateTime(LocalDateTime.now());
        memberMapper.updateById(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeMember(String id) {
        memberMapper.deleteById(id);
    }

    public Selection selectMember(String requestedModel) {
        LocalDateTime now = LocalDateTime.now();
        List<Candidate> candidates = new ArrayList<>();
        for (OciOpenaiLbMember member : memberMapper.selectList(new LambdaQueryWrapper<OciOpenaiLbMember>()
                .eq(OciOpenaiLbMember::getEnabled, 1))) {
            OciOpenaiPortBinding binding = portBindingMapper.selectById(member.getPortBindingId());
            if (binding == null || binding.getEnabled() == null || binding.getEnabled() != 1) {
                continue;
            }
            if (member.getCooldownUntil() != null && member.getCooldownUntil().isAfter(now)) {
                continue;
            }
            List<String> models = OracleAiPortBindingService.decodeAllowedModels(binding.getAllowedModelsJson());
            if (!modelAllowed(requestedModel, models)) {
                continue;
            }
            int weight = member.getWeight() == null || member.getWeight() < 1 ? 1 : member.getWeight();
            int current = inFlight.computeIfAbsent(member.getId(), ignored -> new AtomicInteger()).get();
            candidates.add(new Candidate(member, binding, current / (double) weight));
        }
        Candidate selected = candidates.stream()
                .min(Comparator.comparingDouble(Candidate::loadRate)
                        .thenComparing(candidate -> candidate.member().getLastUsed(), Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(candidate -> candidate.member().getCreateTime(), Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow(() -> new OciException("没有可用的负载均衡成员"));
        inFlight.computeIfAbsent(selected.member().getId(), ignored -> new AtomicInteger()).incrementAndGet();
        return new Selection(selected.member(), selected.binding());
    }

    public void finishRequest(String memberId, int status) {
        finishRequest(memberId, status, 0L);
    }

    public void finishRequest(String memberId, int status, long tokenCount) {
        if (memberId == null || memberId.isBlank()) {
            return;
        }
        releaseInFlight(memberId);
        OciOpenaiLbMember member = memberMapper.selectById(memberId);
        if (member == null) {
            return;
        }
        boolean success = status >= 200 && status < 400;
        boolean shouldCooldown = shouldCooldown(status);
        LocalDateTime now = LocalDateTime.now();
        member.setLastUsed(now);
        member.setUpdateTime(now);
        if (success) {
            member.setFailCount(0);
            member.setCooldownUntil(null);
            member.setLastError(null);
        } else {
            member.setLastError("HTTP " + status);
            if (shouldCooldown) {
                int failCount = member.getFailCount() == null ? 1 : member.getFailCount() + 1;
                member.setFailCount(failCount);
                member.setCooldownUntil(now.plusSeconds(Math.min(300, 10L * failCount)));
            }
        }
        memberMapper.updateById(member);
        recordUsage(memberId, success, tokenCount);
    }

    public void finishClientAborted(String memberId) {
        if (memberId == null || memberId.isBlank()) {
            return;
        }
        releaseInFlight(memberId);
        OciOpenaiLbMember member = memberMapper.selectById(memberId);
        if (member == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        member.setLastUsed(now);
        member.setUpdateTime(now);
        memberMapper.updateById(member);
    }

    private void releaseInFlight(String memberId) {
        AtomicInteger current = inFlight.get(memberId);
        if (current != null) {
            current.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    public ObjectNode modelsJson() {
        Set<String> models = new LinkedHashSet<>();
        for (OciOpenaiLbMember member : memberMapper.selectList(new LambdaQueryWrapper<OciOpenaiLbMember>()
                .eq(OciOpenaiLbMember::getEnabled, 1))) {
            OciOpenaiPortBinding binding = portBindingMapper.selectById(member.getPortBindingId());
            if (binding == null || binding.getEnabled() == null || binding.getEnabled() != 1) {
                continue;
            }
            List<String> allowedModels = OracleAiPortBindingService.decodeAllowedModels(binding.getAllowedModelsJson());
            if (!allowedModels.isEmpty()) {
                models.addAll(allowedModels);
                continue;
            }
            OciUser user = binding.getOciUserId() == null ? null : userMapper.selectById(binding.getOciUserId());
            if (user == null) {
                continue;
            }
            try {
                addModelIds(models, generativeOpenAiService.getModelsAsJson(user, binding.getOciRegion(), null, null));
            } catch (Exception e) {
                log.debug("Failed to load LB member models for binding {}: {}", binding.getId(), e.getMessage());
            }
        }
        ArrayNode data = MAPPER.createArrayNode();
        for (String model : models) {
            ObjectNode row = MAPPER.createObjectNode();
            row.put("id", model);
            row.put("object", "model");
            data.add(row);
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("object", "list");
        root.set("data", data);
        return root;
    }

    private static void addModelIds(Set<String> out, JsonNode root) {
        if (out == null || root == null) {
            return;
        }
        JsonNode data = root.get("data");
        if (data != null && data.isArray()) {
            for (JsonNode item : data) {
                String id = modelId(item);
                if (id != null && !id.isBlank()) {
                    out.add(id.trim());
                }
            }
            return;
        }
        if (root.isArray()) {
            for (JsonNode item : root) {
                String id = modelId(item);
                if (id != null && !id.isBlank()) {
                    out.add(id.trim());
                }
            }
        }
    }

    private static String modelId(JsonNode item) {
        if (item == null || item.isNull()) {
            return null;
        }
        if (item.isTextual()) {
            return item.asText();
        }
        if (item.isObject()) {
            JsonNode id = item.get("id");
            if (id != null && id.isTextual()) {
                return id.asText();
            }
            JsonNode name = item.get("name");
            if (name != null && name.isTextual()) {
                return name.asText();
            }
        }
        return null;
    }

    public Map<String, Object> overview() {
        int port = DynamicOpenAiPortService.loadBalancePort();
        Map<String, Object> row = new HashMap<>();
        row.put("port", port);
        row.put("baseUrl", "http://<host>:" + port + "/v1");
        row.put("running", dynamicPortService.isRunning(port));
        row.put("keyCount", lbKeyMapper.selectCount(null));
        row.put("memberCount", memberMapper.selectCount(null));
        return row;
    }

    private void recordUsage(String memberId, boolean success, long tokenCount) {
        LocalDateTime hour = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        long tokens = Math.max(0L, tokenCount);
        LambdaUpdateWrapper<OciOpenaiLbUsageWindow> update = new LambdaUpdateWrapper<OciOpenaiLbUsageWindow>()
                .setSql("request_count = request_count + 1")
                .setSql(success ? "success_count = success_count + 1" : "failure_count = failure_count + 1")
                .set(OciOpenaiLbUsageWindow::getUpdateTime, LocalDateTime.now())
                .eq(OciOpenaiLbUsageWindow::getMemberId, memberId)
                .eq(OciOpenaiLbUsageWindow::getWindowStart, hour);
        if (tokens > 0) {
            update.setSql("token_count = token_count + " + tokens);
        }
        int updated = usageWindowMapper.update(null, update);
        if (updated > 0) {
            return;
        }
        OciOpenaiLbUsageWindow row = new OciOpenaiLbUsageWindow();
        row.setId(CommonUtils.generateId());
        row.setMemberId(memberId);
        row.setWindowStart(hour);
        row.setRequestCount(1);
        row.setSuccessCount(success ? 1 : 0);
        row.setFailureCount(success ? 0 : 1);
        row.setTokenCount(tokens);
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        usageWindowMapper.insert(row);
    }

    private static boolean shouldCooldown(int status) {
        return status == 429 || status == 499 || status >= 500;
    }

    private Map<String, Object> keyRow(OciOpenaiLbKey key) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", key.getId());
        row.put("name", key.getName());
        row.put("keyPrefix", key.getKeyPrefix());
        row.put("keyMasked", maskForList(key));
        row.put("disabled", key.getDisabled() != null && key.getDisabled() == 1);
        row.put("createTime", key.getCreateTime());
        row.put("lastUsed", key.getLastUsed());
        return row;
    }

    private Map<String, Object> memberRow(OciOpenaiLbMember member) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", member.getId());
        row.put("portBindingId", member.getPortBindingId());
        row.put("weight", member.getWeight());
        row.put("enabled", member.getEnabled() != null && member.getEnabled() == 1);
        row.put("failCount", member.getFailCount());
        row.put("cooldownUntil", member.getCooldownUntil());
        row.put("lastError", member.getLastError());
        row.put("requestLimit5h", member.getRequestLimit5h());
        row.put("requestLimit7d", member.getRequestLimit7d());
        row.put("lastUsed", member.getLastUsed());
        row.put("inFlight", inFlight.getOrDefault(member.getId(), new AtomicInteger()).get());
        row.put("usage5h", usageStats(member.getId(), LocalDateTime.now().minusHours(5)));
        row.put("usage7d", usageStats(member.getId(), LocalDateTime.now().minusDays(7)));

        OciOpenaiPortBinding binding = portBindingMapper.selectById(member.getPortBindingId());
        if (binding != null) {
            row.put("port", binding.getPort());
            row.put("bindingName", binding.getName());
            row.put("ociUserId", binding.getOciUserId());
            row.put("ociRegion", binding.getOciRegion());
            row.put("allowedModels", OracleAiPortBindingService.decodeAllowedModels(binding.getAllowedModelsJson()));
            row.put("bindingEnabled", binding.getEnabled() != null && binding.getEnabled() == 1);
            row.put("bindingStatus", binding.getStatus());
            OciUser user = binding.getOciUserId() == null ? null : userMapper.selectById(binding.getOciUserId());
            if (user != null) {
                row.put("tenantName", user.getUsername());
                row.put("tenantDefaultRegion", user.getOciRegion());
            }
            OciOpenaiKey key = binding.getOpenaiKeyId() == null ? null : openaiKeyMapper.selectById(binding.getOpenaiKeyId());
            if (key != null) {
                row.put("keyName", key.getName());
                row.put("keyMasked", openaiKeyService.maskForList(key));
                row.put("keyDisabled", key.getDisabled() != null && key.getDisabled() == 1);
            }
        }
        return row;
    }

    private UsageStats usageStats(String memberId, LocalDateTime start) {
        int requests = 0;
        int success = 0;
        int failure = 0;
        long tokens = 0L;
        List<OciOpenaiLbUsageWindow> rows = usageWindowMapper.selectList(
                new LambdaQueryWrapper<OciOpenaiLbUsageWindow>()
                        .eq(OciOpenaiLbUsageWindow::getMemberId, memberId)
                        .ge(OciOpenaiLbUsageWindow::getWindowStart, start));
        for (OciOpenaiLbUsageWindow row : rows) {
            requests += row.getRequestCount() == null ? 0 : row.getRequestCount();
            success += row.getSuccessCount() == null ? 0 : row.getSuccessCount();
            failure += row.getFailureCount() == null ? 0 : row.getFailureCount();
            tokens += row.getTokenCount() == null ? 0 : row.getTokenCount();
        }
        return new UsageStats(requests, success, failure, tokens);
    }

    private String maskForList(OciOpenaiLbKey key) {
        if (key == null) {
            return KEY_PREFIX + "****";
        }
        if (key.getKeyEncrypted() != null && !key.getKeyEncrypted().isBlank()) {
            try {
                return OciOpenaiKeyCipher.maskForDisplay(
                        OciOpenaiKeyCipher.decrypt(key.getKeyEncrypted(), webPassword));
            } catch (Exception ignored) {
            }
        }
        return key.getKeyPrefix() == null ? KEY_PREFIX + "****" : key.getKeyPrefix() + "****";
    }

    private static boolean modelAllowed(String requestedModel, List<String> allowedModels) {
        if (allowedModels == null || allowedModels.isEmpty()) {
            return true;
        }
        if (requestedModel == null || requestedModel.isBlank()) {
            return false;
        }
        String model = requestedModel.trim();
        return allowedModels.stream().anyMatch(item -> item != null && model.equals(item.trim()));
    }

    private static String trimName(String name) {
        if (name == null) {
            return null;
        }
        String value = name.trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.length() > 128 ? value.substring(0, 128) : value;
    }

    private static Integer normalizeLimit(Integer value) {
        if (value == null || value <= 0) {
            return null;
        }
        return value;
    }

    public record KeyCreateResult(String id, String plainKey, String keyPrefix, String keyMasked) {}

    public record Selection(OciOpenaiLbMember member, OciOpenaiPortBinding binding) {}

    private record Candidate(OciOpenaiLbMember member, OciOpenaiPortBinding binding, double loadRate) {}

    public record UsageStats(int requestCount, int successCount, int failureCount, long tokenCount) {}
}
