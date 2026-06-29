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
import com.ociworker.mapper.OciOpenaiLbMemberModelStateMapper;
import com.ociworker.mapper.OciOpenaiLbRequestLogMapper;
import com.ociworker.mapper.OciOpenaiLbUsageWindowMapper;
import com.ociworker.mapper.OciOpenaiPortBindingMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciOpenaiKey;
import com.ociworker.model.entity.OciOpenaiLbKey;
import com.ociworker.model.entity.OciOpenaiLbMember;
import com.ociworker.model.entity.OciOpenaiLbMemberModelState;
import com.ociworker.model.entity.OciOpenaiLbRequestLog;
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
import org.springframework.scheduling.annotation.Scheduled;
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
    private OciOpenaiLbMemberModelStateMapper memberModelStateMapper;
    @Resource
    private OciOpenaiLbRequestLogMapper requestLogMapper;
    @Resource
    private OciOpenaiLbUsageWindowMapper usageWindowMapper;
    @Resource
    private OciOpenaiPortBindingMapper portBindingMapper;
    @Resource
    private OciOpenaiKeyMapper openaiKeyMapper;
    @Resource
    private OciUserMapper userMapper;
    @Resource
    private OciGenerativeOpenAiService generativeOpenAiService;

    @Value("${web.password}")
    private String webPassword;
    @Value("${ociworker.openaiLoadBalance.requestLogRetentionDays:7}")
    private int requestLogRetentionDays;
    @Value("${ociworker.openaiLoadBalance.requestLogMaxRows:5000}")
    private int requestLogMaxRows;
    @Value("${ociworker.openaiLoadBalance.modelFailThreshold:1}")
    private int modelFailThreshold;
    @Value("${ociworker.openaiLoadBalance.modelUnavailableHours:24}")
    private int modelUnavailableHours;

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
        purgeOrphanMembers();
        purgeInvalidModelStates();
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
            Integer requestLimit7d,
            Integer maxConcurrency,
            Integer rpmLimit,
            Long tpmLimit,
            Integer contextLimit,
            Integer streamFirstChunkTimeoutSeconds,
            Integer streamIdleTimeoutSeconds,
            Integer streamMaxSeconds) {
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
        row.setMaxConcurrency(normalizeLimit(maxConcurrency));
        row.setRpmLimit(normalizeLimit(rpmLimit));
        row.setTpmLimit(normalizeLongLimit(tpmLimit));
        row.setContextLimit(normalizeLimit(contextLimit));
        row.setStreamFirstChunkTimeoutSeconds(normalizeTimeout(streamFirstChunkTimeoutSeconds, 5, 600));
        row.setStreamIdleTimeoutSeconds(normalizeTimeout(streamIdleTimeoutSeconds, 5, 600));
        row.setStreamMaxSeconds(normalizeTimeout(streamMaxSeconds, 30, 21600));
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
        deleteMemberArtifacts(id);
        memberMapper.deleteById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public int removeMembersByPortBindingId(String portBindingId) {
        if (portBindingId == null || portBindingId.isBlank()) {
            return 0;
        }
        List<OciOpenaiLbMember> members = memberMapper.selectList(new LambdaQueryWrapper<OciOpenaiLbMember>()
                .eq(OciOpenaiLbMember::getPortBindingId, portBindingId));
        for (OciOpenaiLbMember member : members) {
            if (member == null || member.getId() == null) {
                continue;
            }
            deleteMemberArtifacts(member.getId());
            memberMapper.deleteById(member.getId());
        }
        return members.size();
    }

    private void deleteMemberArtifacts(String memberId) {
        if (memberId == null || memberId.isBlank()) {
            return;
        }
        inFlight.remove(memberId);
        memberModelStateMapper.delete(new LambdaQueryWrapper<OciOpenaiLbMemberModelState>()
                .eq(OciOpenaiLbMemberModelState::getMemberId, memberId));
        usageWindowMapper.delete(new LambdaQueryWrapper<OciOpenaiLbUsageWindow>()
                .eq(OciOpenaiLbUsageWindow::getMemberId, memberId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void clearMemberModelState(String memberId, String model) {
        if (memberId == null || memberId.isBlank()) {
            return;
        }
        LambdaQueryWrapper<OciOpenaiLbMemberModelState> wrapper = new LambdaQueryWrapper<OciOpenaiLbMemberModelState>()
                .eq(OciOpenaiLbMemberModelState::getMemberId, memberId);
        String normalizedModel = normalizeModel(model);
        if (normalizedModel != null) {
            wrapper.eq(OciOpenaiLbMemberModelState::getModel, normalizedModel);
        }
        memberModelStateMapper.delete(wrapper);
    }

    @Scheduled(fixedDelayString = "${ociworker.openaiLoadBalance.healthCheckIntervalMs:60000}")
    public void refreshMemberHealth() {
        purgeOrphanMembers();
        purgeInvalidModelStates();
        LocalDateTime now = LocalDateTime.now();
        for (OciOpenaiLbMember member : memberMapper.selectList(null)) {
            try {
                HealthCheckResult health = localHealth(member, now);
                if (health == null) {
                    continue;
                }
                member.setHealthStatus(health.status());
                member.setHealthMessage(health.message());
                member.setHealthCheckedAt(now);
                member.setUpdateTime(now);
                memberMapper.updateById(member);
            } catch (Exception e) {
                log.debug("OpenAI LB member health refresh failed memberId={} message={}", member.getId(), e.getMessage());
            }
        }
    }

    private int purgeOrphanMembers() {
        List<OciOpenaiLbMember> members = memberMapper.selectList(null);
        if (members.isEmpty()) {
            return 0;
        }
        Set<String> bindingIds = new LinkedHashSet<>();
        for (OciOpenaiLbMember member : members) {
            String portBindingId = member == null ? null : member.getPortBindingId();
            if (portBindingId != null && !portBindingId.isBlank()) {
                bindingIds.add(portBindingId.trim());
            }
        }
        Set<String> existingBindingIds = new LinkedHashSet<>();
        if (!bindingIds.isEmpty()) {
            for (OciOpenaiPortBinding binding : portBindingMapper.selectList(new LambdaQueryWrapper<OciOpenaiPortBinding>()
                    .in(OciOpenaiPortBinding::getId, bindingIds))) {
                if (binding != null && binding.getId() != null) {
                    existingBindingIds.add(binding.getId());
                }
            }
        }
        int removed = 0;
        for (OciOpenaiLbMember member : members) {
            if (member == null || member.getId() == null) {
                continue;
            }
            String portBindingId = member.getPortBindingId();
            if (portBindingId != null && !portBindingId.isBlank() && existingBindingIds.contains(portBindingId.trim())) {
                continue;
            }
            deleteMemberArtifacts(member.getId());
            memberMapper.deleteById(member.getId());
            removed++;
        }
        if (removed > 0) {
            log.info("Removed {} orphan OpenAI LB member(s)", removed);
        }
        return removed;
    }

    private int purgeInvalidModelStates() {
        int removed = 0;
        for (OciOpenaiLbMemberModelState state : memberModelStateMapper.selectList(null)) {
            if (state == null || state.getId() == null) {
                continue;
            }
            String status = state.getStatus();
            if (!"unavailable".equalsIgnoreCase(status) && !"suspect".equalsIgnoreCase(status)) {
                continue;
            }
            int lastStatus = state.getLastStatus() == null ? 0 : state.getLastStatus();
            if (isModelAvailabilityFailure(lastStatus, state.getLastError())) {
                continue;
            }
            memberModelStateMapper.deleteById(state.getId());
            removed++;
        }
        if (removed > 0) {
            log.info("Removed {} invalid OpenAI LB member model state(s)", removed);
        }
        return removed;
    }

    public Selection selectMember(String requestedModel) {
        return selectMember(requestedModel, 0L, Set.of());
    }

    public int eligibleMemberCount(String requestedModel, long estimatedTokens) {
        return eligibleCandidates(requestedModel, estimatedTokens, Set.of(), LocalDateTime.now(), true).size();
    }

    public Selection selectMember(String requestedModel, long estimatedTokens, Set<String> excludedMemberIds) {
        LocalDateTime now = LocalDateTime.now();
        List<Candidate> candidates = eligibleCandidates(requestedModel, estimatedTokens, excludedMemberIds, now, false);
        if (candidates.isEmpty()) {
            candidates = eligibleCandidates(requestedModel, estimatedTokens, excludedMemberIds, now, true);
        }
        Candidate selected = candidates.stream()
                .min(Comparator.comparingDouble(Candidate::loadRate)
                        .thenComparing(candidate -> candidate.member().getLastUsed(), Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(candidate -> candidate.member().getCreateTime(), Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow(() -> new OciException("没有可用的负载均衡成员"));
        inFlight.computeIfAbsent(selected.member().getId(), ignored -> new AtomicInteger()).incrementAndGet();
        return new Selection(selected.member(), selected.binding());
    }

    private List<Candidate> eligibleCandidates(String requestedModel, long estimatedTokens, Set<String> excludedMemberIds, LocalDateTime now, boolean includeCooling) {
        List<Candidate> candidates = new ArrayList<>();
        for (OciOpenaiLbMember member : memberMapper.selectList(new LambdaQueryWrapper<OciOpenaiLbMember>()
                .eq(OciOpenaiLbMember::getEnabled, 1))) {
            if (excludedMemberIds != null && excludedMemberIds.contains(member.getId())) {
                continue;
            }
            OciOpenaiPortBinding binding = portBindingMapper.selectById(member.getPortBindingId());
            if (binding == null || binding.getEnabled() == null || binding.getEnabled() != 1) {
                continue;
            }
            boolean cooling = member.getCooldownUntil() != null && member.getCooldownUntil().isAfter(now);
            if (cooling && !includeCooling) {
                continue;
            }
            List<String> models = OracleAiPortBindingService.decodeAllowedModels(binding.getAllowedModelsJson());
            if (!modelAllowed(requestedModel, models)) {
                continue;
            }
            if (modelUnavailable(member.getId(), requestedModel, now)) {
                continue;
            }
            int weight = member.getWeight() == null || member.getWeight() < 1 ? 1 : member.getWeight();
            int current = inFlight.computeIfAbsent(member.getId(), ignored -> new AtomicInteger()).get();
            if (member.getMaxConcurrency() != null && member.getMaxConcurrency() > 0
                    && current >= member.getMaxConcurrency()) {
                continue;
            }
            if (estimatedTokens > 0 && member.getContextLimit() != null && member.getContextLimit() > 0
                    && estimatedTokens > member.getContextLimit()) {
                continue;
            }
            if (member.getRpmLimit() != null && member.getRpmLimit() > 0
                    && recentRequestCount(member.getId(), now.minusMinutes(1)) >= member.getRpmLimit()) {
                continue;
            }
            if (member.getTpmLimit() != null && member.getTpmLimit() > 0
                    && recentTokenCount(member.getId(), now.minusMinutes(1)) + Math.max(0L, estimatedTokens) > member.getTpmLimit()) {
                continue;
            }
            double score = adaptiveScore(member, current, weight, now);
            if (cooling) {
                score += 4D;
            }
            candidates.add(new Candidate(member, binding, score));
        }
        return candidates;
    }

    public void finishRequest(String memberId, int status) {
        finishRequest(memberId, status, 0L);
    }

    public void finishRequest(String memberId, int status, long tokenCount) {
        finishRequest(memberId, status, tokenCount, null, null, null);
    }

    public void finishRequest(
            String memberId,
            int status,
            long tokenCount,
            Long latencyMs,
            String errorType,
            String errorMessage) {
        finishRequest(memberId, status, tokenCount, latencyMs, errorType, errorMessage, null);
    }

    public void finishRequest(
            String memberId,
            int status,
            long tokenCount,
            Long latencyMs,
            String errorType,
            String errorMessage,
            String requestedModel) {
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
        member.setLastStatus(status);
        if (latencyMs != null && latencyMs >= 0) {
            member.setLastLatencyMs((int) Math.min(Integer.MAX_VALUE, latencyMs));
        }
        member.setLastErrorType(trimTo(errorType, 64));
        member.setHealthCheckedAt(now);
        updateEwma(member, success, latencyMs);
        if (success) {
            member.setFailCount(0);
            member.setCooldownUntil(null);
            member.setLastError(null);
            member.setHealthStatus("healthy");
            member.setHealthMessage(null);
            if (member.getRecoveryUntil() != null && member.getRecoveryUntil().isBefore(now)) {
                member.setRecoveryUntil(null);
            }
        } else {
            String msg = errorMessage == null || errorMessage.isBlank() ? "HTTP " + status : errorMessage;
            member.setLastError(trimTo(msg, 512));
            member.setHealthStatus("unhealthy");
            member.setHealthMessage(trimTo(msg, 512));
            if (shouldCooldown) {
                int failCount = member.getFailCount() == null ? 1 : member.getFailCount() + 1;
                member.setFailCount(failCount);
                LocalDateTime cooldownUntil = now.plusSeconds(Math.min(300, 10L * failCount));
                member.setCooldownUntil(cooldownUntil);
                member.setRecoveryUntil(cooldownUntil.plusSeconds(Math.min(600, 30L * failCount)));
            }
        }
        memberMapper.updateById(member);
        recordUsage(memberId, success, tokenCount);
        updateModelState(memberId, requestedModel, success, status, errorMessage);
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

    private boolean modelUnavailable(String memberId, String requestedModel, LocalDateTime now) {
        String model = normalizeModel(requestedModel);
        if (memberId == null || memberId.isBlank() || model == null) {
            return false;
        }
        OciOpenaiLbMemberModelState state = memberModelStateMapper.selectOne(new LambdaQueryWrapper<OciOpenaiLbMemberModelState>()
                .eq(OciOpenaiLbMemberModelState::getMemberId, memberId)
                .eq(OciOpenaiLbMemberModelState::getModel, model));
        if (state == null
                || !"unavailable".equalsIgnoreCase(state.getStatus())
                || state.getUnavailableUntil() == null
                || !state.getUnavailableUntil().isAfter(now)) {
            return false;
        }
        Integer lastStatus = state.getLastStatus();
        return lastStatus == null
                ? isModelAvailabilityFailure(400, state.getLastError())
                : isModelAvailabilityFailure(lastStatus, state.getLastError());
    }

    private void updateModelState(String memberId, String requestedModel, boolean success, int status, String errorMessage) {
        String model = normalizeModel(requestedModel);
        if (memberId == null || memberId.isBlank() || model == null) {
            return;
        }
        if (!success && !isModelAvailabilityFailure(status, errorMessage)) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            OciOpenaiLbMemberModelState state = memberModelStateMapper.selectOne(new LambdaQueryWrapper<OciOpenaiLbMemberModelState>()
                    .eq(OciOpenaiLbMemberModelState::getMemberId, memberId)
                    .eq(OciOpenaiLbMemberModelState::getModel, model));
            if (state == null) {
                state = new OciOpenaiLbMemberModelState();
                state.setId(CommonUtils.generateId());
                state.setMemberId(memberId);
                state.setModel(model);
                state.setFailCount(0);
                state.setSuccessCount(0);
                state.setCreateTime(now);
            }
            state.setLastStatus(status);
            state.setLastCheckedAt(now);
            state.setUpdateTime(now);
            if (success) {
                state.setStatus("available");
                state.setFailCount(0);
                state.setSuccessCount((state.getSuccessCount() == null ? 0 : state.getSuccessCount()) + 1);
                state.setUnavailableUntil(null);
                state.setLastError(null);
            } else {
                int failures = (state.getFailCount() == null ? 0 : state.getFailCount()) + 1;
                state.setFailCount(failures);
                state.setLastError(trimTo(errorMessage == null || errorMessage.isBlank() ? "HTTP " + status : errorMessage, 512));
                if (failures >= Math.max(1, modelFailThreshold)) {
                    state.setStatus("unavailable");
                    state.setUnavailableUntil(now.plusHours(Math.max(1, modelUnavailableHours)));
                } else {
                    state.setStatus("suspect");
                }
            }
            if (memberModelStateMapper.selectById(state.getId()) == null) {
                memberModelStateMapper.insert(state);
            } else {
                memberModelStateMapper.updateById(state);
            }
        } catch (Exception e) {
            log.debug("Failed to update LB member model state memberId={} model={} status={} message={}",
                    memberId, model, status, e.getMessage());
        }
    }

    public static boolean isModelAvailabilityFailure(int status, String errorMessage) {
        if (status < 400) {
            return false;
        }
        String message = errorMessage == null ? "" : errorMessage.toLowerCase();
        if (message.isBlank()
                || "http 400".equals(message)
                || "http 404".equals(message)
                || "http 422".equals(message)) {
            return false;
        }
        return message.contains("model")
                && (message.contains("not found")
                || message.contains("not exist")
                || message.contains("does not exist")
                || message.contains("doesn't exist")
                || message.contains("not available")
                || message.contains("not allowed")
                || message.contains("not authorized")
                || message.contains("not supported")
                || message.contains("unsupported")
                || message.contains("no access")
                || message.contains("access denied")
                || message.contains("unknown model")
                || message.contains("invalid model"));
    }

    private static double adaptiveScore(OciOpenaiLbMember member, int current, int weight, LocalDateTime now) {
        double score = current / (double) weight;
        if ("unhealthy".equalsIgnoreCase(member.getHealthStatus())) {
            score += 1.5D;
        } else if ("cooling".equalsIgnoreCase(member.getHealthStatus())) {
            score += 1D;
        }
        if (member.getFailCount() != null && member.getFailCount() > 0) {
            score += Math.min(1.5D, member.getFailCount() * 0.15D);
        }
        Double successRate = member.getEwmaSuccessRate();
        if (successRate != null) {
            score += Math.max(0D, 1D - Math.min(1D, successRate)) * 2.5D;
        }
        Long latency = member.getEwmaLatencyMs();
        if (latency == null && member.getLastLatencyMs() != null) {
            latency = member.getLastLatencyMs().longValue();
        }
        if (latency != null && latency > 0) {
            score += Math.min(2D, latency / 60000D);
        }
        if (member.getRecoveryUntil() != null && member.getRecoveryUntil().isAfter(now)) {
            score += 2D;
        }
        return score;
    }

    private static void updateEwma(OciOpenaiLbMember member, boolean success, Long latencyMs) {
        double alpha = 0.25D;
        double sample = success ? 1D : 0D;
        Double oldRate = member.getEwmaSuccessRate();
        member.setEwmaSuccessRate(oldRate == null ? sample : clamp01(oldRate * (1D - alpha) + sample * alpha));
        if (latencyMs != null && latencyMs >= 0) {
            Long oldLatency = member.getEwmaLatencyMs();
            long next = oldLatency == null
                    ? latencyMs
                    : Math.round(oldLatency * (1D - alpha) + latencyMs * alpha);
            member.setEwmaLatencyMs(Math.max(0L, next));
        }
    }

    private static double clamp01(double value) {
        if (value < 0D) {
            return 0D;
        }
        if (value > 1D) {
            return 1D;
        }
        return value;
    }

    private void releaseInFlight(String memberId) {
        AtomicInteger current = inFlight.get(memberId);
        if (current != null) {
            current.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    public ObjectNode modelsJson() {
        LocalDateTime now = LocalDateTime.now();
        Set<String> models = new LinkedHashSet<>();
        for (OciOpenaiLbMember member : memberMapper.selectList(new LambdaQueryWrapper<OciOpenaiLbMember>()
                .eq(OciOpenaiLbMember::getEnabled, 1))) {
            OciOpenaiPortBinding binding = portBindingMapper.selectById(member.getPortBindingId());
            if (binding == null || binding.getEnabled() == null || binding.getEnabled() != 1) {
                continue;
            }
            List<String> allowedModels = OracleAiPortBindingService.decodeAllowedModels(binding.getAllowedModelsJson());
            if (!allowedModels.isEmpty()) {
                for (String model : allowedModels) {
                    if (!modelUnavailable(member.getId(), model, now)) {
                        models.add(model);
                    }
                }
                continue;
            }
            OciUser user = binding.getOciUserId() == null ? null : userMapper.selectById(binding.getOciUserId());
            if (user == null) {
                continue;
            }
            try {
                addModelIds(models, generativeOpenAiService.getModelsAsJsonCached(user, binding.getOciRegion()));
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

    public List<Map<String, Object>> recentRequests(int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit <= 0 ? 50 : limit));
        return requestLogMapper.selectList(new LambdaQueryWrapper<OciOpenaiLbRequestLog>()
                        .orderByDesc(OciOpenaiLbRequestLog::getCreateTime)
                        .last("LIMIT " + safeLimit))
                .stream()
                .map(this::requestLogRow)
                .collect(Collectors.toList());
    }

    public void recordRequestLog(RequestLogInput input) {
        if (input == null || input.requestId() == null || input.requestId().isBlank()) {
            return;
        }
        try {
            OciOpenaiLbRequestLog row = new OciOpenaiLbRequestLog();
            row.setId(CommonUtils.generateId());
            row.setRequestId(input.requestId());
            row.setLbKeyId(input.lbKeyId());
            row.setMemberId(input.memberId());
            row.setPortBindingId(input.portBindingId());
            row.setPort(input.port());
            row.setModel(trimTo(input.model(), 256));
            row.setStream(input.stream() ? 1 : 0);
            row.setEstimatedPromptTokens(Math.max(0L, input.estimatedTokens()));
            row.setStatusCode(input.statusCode());
            row.setStatus(trimTo(input.status(), 32));
            row.setErrorType(trimTo(input.errorType(), 64));
            row.setErrorMessage(trimTo(input.errorMessage(), 512));
            row.setLatencyMs(input.latencyMs() == null ? null : Math.max(0L, input.latencyMs()));
            row.setFirstChunkMs(input.firstChunkMs() == null ? null : Math.max(0L, input.firstChunkMs()));
            row.setChunkCount(input.chunkCount() == null ? 0 : Math.max(0, input.chunkCount()));
            row.setTokenCount(Math.max(0L, input.tokenCount()));
            row.setClientAborted(input.clientAborted() ? 1 : 0);
            row.setRetryCount(Math.max(0, input.retryCount()));
            row.setCreateTime(LocalDateTime.now());
            row.setUpdateTime(LocalDateTime.now());
            requestLogMapper.insert(row);
        } catch (Exception e) {
            log.debug("Failed to record OpenAI LB request log: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "${ociworker.openaiLoadBalance.requestLogCleanupCron:0 17 3 * * ?}")
    public void cleanupRequestLogs() {
        try {
            int days = Math.max(1, requestLogRetentionDays);
            int keepRows = Math.max(100, requestLogMaxRows);
            LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
            int oldDeleted = requestLogMapper.delete(new LambdaQueryWrapper<OciOpenaiLbRequestLog>()
                    .lt(OciOpenaiLbRequestLog::getCreateTime, cutoff));
            long count = requestLogMapper.selectCount(null);
            int overflowDeleted = 0;
            if (count > keepRows) {
                overflowDeleted = requestLogMapper.deleteBeyondLatest(keepRows);
            }
            if (oldDeleted > 0 || overflowDeleted > 0) {
                log.info("OpenAI LB request log cleanup: oldDeleted={} overflowDeleted={} keepRows={} days={}",
                        oldDeleted, overflowDeleted, keepRows, days);
            }
        } catch (Exception e) {
            log.debug("OpenAI LB request log cleanup failed: {}", e.getMessage());
        }
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
        return status == 429 || status == 499 || status == 502 || status == 503 || status == 504;
    }

    private HealthCheckResult localHealth(OciOpenaiLbMember member, LocalDateTime now) {
        if (member == null) {
            return null;
        }
        if (member.getEnabled() == null || member.getEnabled() != 1) {
            return new HealthCheckResult("disabled", "成员已禁用");
        }
        OciOpenaiPortBinding binding = portBindingMapper.selectById(member.getPortBindingId());
        if (binding == null) {
            return new HealthCheckResult("unhealthy", "绑定端口不存在");
        }
        if (binding.getEnabled() == null || binding.getEnabled() != 1) {
            return new HealthCheckResult("unhealthy", "中转端口已停用");
        }
        OciOpenaiKey key = binding.getOpenaiKeyId() == null ? null : openaiKeyMapper.selectById(binding.getOpenaiKeyId());
        if (key != null && key.getDisabled() != null && key.getDisabled() == 1) {
            return new HealthCheckResult("unhealthy", "成员 API Key 已禁用");
        }
        if (member.getCooldownUntil() != null && member.getCooldownUntil().isAfter(now)) {
            return new HealthCheckResult("cooling", "冷却到 " + member.getCooldownUntil());
        }
        if (member.getRecoveryUntil() != null && member.getRecoveryUntil().isAfter(now)) {
            return new HealthCheckResult("recovering", "恢复观察到 " + member.getRecoveryUntil());
        }
        if ("failed".equalsIgnoreCase(binding.getStatus())) {
            return new HealthCheckResult("unhealthy", binding.getStatusMessage() == null ? "端口启动失败" : binding.getStatusMessage());
        }
        if ("listening".equalsIgnoreCase(binding.getStatus())) {
            if (member.getFailCount() != null && member.getFailCount() > 0) {
                return new HealthCheckResult("unhealthy", member.getLastError() == null ? "最近请求失败" : member.getLastError());
            }
            return new HealthCheckResult("healthy", "端口监听中");
        }
        return new HealthCheckResult("unknown", binding.getStatus() == null ? "端口未监听" : "端口状态 " + binding.getStatus());
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
        row.put("maxConcurrency", member.getMaxConcurrency());
        row.put("rpmLimit", member.getRpmLimit());
        row.put("tpmLimit", member.getTpmLimit());
        row.put("contextLimit", member.getContextLimit());
        row.put("streamFirstChunkTimeoutSeconds", member.getStreamFirstChunkTimeoutSeconds());
        row.put("streamIdleTimeoutSeconds", member.getStreamIdleTimeoutSeconds());
        row.put("streamMaxSeconds", member.getStreamMaxSeconds());
        row.put("healthStatus", member.getHealthStatus());
        row.put("healthMessage", member.getHealthMessage());
        row.put("healthCheckedAt", member.getHealthCheckedAt());
        row.put("lastLatencyMs", member.getLastLatencyMs());
        row.put("lastStatus", member.getLastStatus());
        row.put("lastErrorType", member.getLastErrorType());
        row.put("ewmaSuccessRate", member.getEwmaSuccessRate());
        row.put("ewmaLatencyMs", member.getEwmaLatencyMs());
        row.put("recoveryUntil", member.getRecoveryUntil());
        row.put("modelStates", memberModelStates(member.getId()));
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
                row.put("keyMasked", maskTenantKeyForList(key));
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

    private List<Map<String, Object>> memberModelStates(String memberId) {
        if (memberId == null || memberId.isBlank()) {
            return List.of();
        }
        return memberModelStateMapper.selectList(new LambdaQueryWrapper<OciOpenaiLbMemberModelState>()
                        .eq(OciOpenaiLbMemberModelState::getMemberId, memberId)
                        .orderByDesc(OciOpenaiLbMemberModelState::getUpdateTime))
                .stream()
                .map(this::memberModelStateRow)
                .collect(Collectors.toList());
    }

    private Map<String, Object> memberModelStateRow(OciOpenaiLbMemberModelState state) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", state.getId());
        row.put("memberId", state.getMemberId());
        row.put("model", state.getModel());
        row.put("status", state.getStatus());
        row.put("failCount", state.getFailCount());
        row.put("successCount", state.getSuccessCount());
        row.put("unavailableUntil", state.getUnavailableUntil());
        row.put("lastStatus", state.getLastStatus());
        row.put("lastError", state.getLastError());
        row.put("lastCheckedAt", state.getLastCheckedAt());
        row.put("updateTime", state.getUpdateTime());
        return row;
    }

    private long recentRequestCount(String memberId, LocalDateTime since) {
        if (memberId == null || since == null) {
            return 0L;
        }
        return requestLogMapper.selectCount(new LambdaQueryWrapper<OciOpenaiLbRequestLog>()
                .eq(OciOpenaiLbRequestLog::getMemberId, memberId)
                .ge(OciOpenaiLbRequestLog::getCreateTime, since));
    }

    private long recentTokenCount(String memberId, LocalDateTime since) {
        long tokens = 0L;
        if (memberId == null || since == null) {
            return tokens;
        }
        List<OciOpenaiLbRequestLog> rows = requestLogMapper.selectList(new LambdaQueryWrapper<OciOpenaiLbRequestLog>()
                .eq(OciOpenaiLbRequestLog::getMemberId, memberId)
                .ge(OciOpenaiLbRequestLog::getCreateTime, since));
        for (OciOpenaiLbRequestLog row : rows) {
            long actual = row.getTokenCount() == null ? 0L : row.getTokenCount();
            long estimated = row.getEstimatedPromptTokens() == null ? 0L : row.getEstimatedPromptTokens();
            tokens += Math.max(actual, estimated);
        }
        return tokens;
    }

    private Map<String, Object> requestLogRow(OciOpenaiLbRequestLog logRow) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", logRow.getId());
        row.put("requestId", logRow.getRequestId());
        row.put("lbKeyId", logRow.getLbKeyId());
        row.put("memberId", logRow.getMemberId());
        row.put("portBindingId", logRow.getPortBindingId());
        row.put("port", logRow.getPort());
        row.put("model", logRow.getModel());
        row.put("stream", logRow.getStream() != null && logRow.getStream() == 1);
        row.put("estimatedPromptTokens", logRow.getEstimatedPromptTokens());
        row.put("statusCode", logRow.getStatusCode());
        row.put("status", logRow.getStatus());
        row.put("errorType", logRow.getErrorType());
        row.put("errorMessage", logRow.getErrorMessage());
        row.put("latencyMs", logRow.getLatencyMs());
        row.put("firstChunkMs", logRow.getFirstChunkMs());
        row.put("chunkCount", logRow.getChunkCount());
        row.put("tokenCount", logRow.getTokenCount());
        row.put("clientAborted", logRow.getClientAborted() != null && logRow.getClientAborted() == 1);
        row.put("retryCount", logRow.getRetryCount());
        row.put("createTime", logRow.getCreateTime());
        return row;
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

    private String maskTenantKeyForList(OciOpenaiKey key) {
        if (key == null) {
            return "sk-****";
        }
        if (key.getKeyEncrypted() != null && !key.getKeyEncrypted().isBlank()) {
            try {
                String plain = OciOpenaiKeyCipher.decrypt(key.getKeyEncrypted(), webPassword);
                if (plain != null && !plain.isBlank()) {
                    return OciOpenaiKeyCipher.maskForDisplay(plain);
                }
            } catch (Exception ignored) {
            }
        }
        return key.getKeyPrefix() == null ? "sk-****" : key.getKeyPrefix() + "****";
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

    private static Long normalizeLongLimit(Long value) {
        if (value == null || value <= 0) {
            return null;
        }
        return value;
    }

    private static Integer normalizeTimeout(Integer value, int min, int max) {
        if (value == null || value <= 0) {
            return null;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String trimTo(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxLen ? trimmed.substring(0, maxLen) : trimmed;
    }

    private static String normalizeModel(String model) {
        if (model == null) {
            return null;
        }
        String value = model.trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.length() > 256 ? value.substring(0, 256) : value;
    }

    public record KeyCreateResult(String id, String plainKey, String keyPrefix, String keyMasked) {}

    public record Selection(OciOpenaiLbMember member, OciOpenaiPortBinding binding) {}

    private record Candidate(OciOpenaiLbMember member, OciOpenaiPortBinding binding, double loadRate) {}

    private record HealthCheckResult(String status, String message) {}

    public record UsageStats(int requestCount, int successCount, int failureCount, long tokenCount) {}

    public record RequestLogInput(
            String requestId,
            String lbKeyId,
            String memberId,
            String portBindingId,
            Integer port,
            String model,
            boolean stream,
            long estimatedTokens,
            Integer statusCode,
            String status,
            String errorType,
            String errorMessage,
            Long latencyMs,
            Long firstChunkMs,
            Integer chunkCount,
            long tokenCount,
            boolean clientAborted,
            int retryCount) {}
}
