package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.requests.GetInstanceRequest;
import com.oracle.bmc.model.BmcException;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciInstanceGuardMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciInstanceGuard;
import com.ociworker.model.entity.OciUser;
import com.ociworker.util.OciBmcErrorTranslator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实例守护：定时轮询已开启守护的实例，发现 STOPPED 立即自动 START。
 * 只对用户显式开启守护的实例生效，不影响手动停止其他实例。
 */
@Slf4j
@Service
public class InstanceGuardService {

    private static final int NOTIFY_AFTER_FAILURES = 3;
    private final AtomicBoolean checking = new AtomicBoolean(false);

    @Resource
    private OciInstanceGuardMapper guardMapper;
    @Resource
    private OciUserMapper userMapper;
    @Resource
    private InstanceService instanceService;
    @Resource
    private NotificationService notificationService;

    public Map<String, Object> status(String tenantId, String region, String instanceId) {
        OciInstanceGuard guard = find(requireText(tenantId, "租户配置 ID"),
                normalizeRegion(tenantId, region), requireText(instanceId, "实例 ID"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", guard != null && Boolean.TRUE.equals(guard.getEnabled()));
        data.put("lastState", guard == null ? null : guard.getLastState());
        data.put("lastCheckTime", guard == null ? null : guard.getLastCheckTime());
        data.put("lastStartTime", guard == null ? null : guard.getLastStartTime());
        data.put("startCount", guard == null || guard.getStartCount() == null ? 0 : guard.getStartCount());
        data.put("lastMessage", guard == null ? null : guard.getLastMessage());
        return data;
    }

    public Map<String, Object> save(String tenantId, String region, String instanceId,
                                    String instanceName, boolean enabled) {
        tenantId = requireText(tenantId, "租户配置 ID");
        instanceId = requireText(instanceId, "实例 ID");
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");
        String normalizedRegion = normalizeRegion(tenantId, region);

        Date now = new Date();
        OciInstanceGuard guard = find(tenantId, normalizedRegion, instanceId);
        boolean isNew = guard == null;
        if (isNew) {
            guard = new OciInstanceGuard();
            guard.setId(UUID.randomUUID().toString().replace("-", ""));
            guard.setTenantConfigId(tenantId);
            guard.setRegion(normalizedRegion);
            guard.setInstanceId(instanceId);
            guard.setStartCount(0);
            guard.setCreateTime(now);
        }
        guard.setTenantName(user.getUsername());
        if (instanceName != null && !instanceName.isBlank()) {
            guard.setInstanceName(instanceName.trim());
        }
        guard.setEnabled(enabled);
        guard.setConsecutiveFailures(0);
        guard.setLastMessage(enabled ? "守护已开启，等待下一轮检测" : "守护已关闭");
        guard.setUpdateTime(now);
        if (isNew) {
            guardMapper.insert(guard);
        } else {
            guardMapper.updateById(guard);
        }
        return status(tenantId, normalizedRegion, instanceId);
    }

    public List<OciInstanceGuard> listAll() {
        return guardMapper.selectList(new LambdaQueryWrapper<OciInstanceGuard>()
                .orderByDesc(OciInstanceGuard::getCreateTime));
    }

    @Scheduled(fixedDelayString = "${oci.instance-guard.check-interval-millis:120000}",
            initialDelay = 45_000)
    public void checkGuards() {
        if (!checking.compareAndSet(false, true)) return;
        try {
            List<OciInstanceGuard> guards = guardMapper.selectList(
                    new LambdaQueryWrapper<OciInstanceGuard>().eq(OciInstanceGuard::getEnabled, true));
            if (guards == null || guards.isEmpty()) return;

            Map<String, List<OciInstanceGuard>> byTenantRegion = new LinkedHashMap<>();
            for (OciInstanceGuard guard : guards) {
                byTenantRegion.computeIfAbsent(
                        guard.getTenantConfigId() + '|' + guard.getRegion(),
                        ignored -> new java.util.ArrayList<>()).add(guard);
            }
            for (List<OciInstanceGuard> group : byTenantRegion.values()) {
                checkTenantGroup(group);
            }
        } finally {
            checking.set(false);
        }
    }

    private void checkTenantGroup(List<OciInstanceGuard> group) {
        OciInstanceGuard first = group.get(0);
        OciUser user = userMapper.selectById(first.getTenantConfigId());
        if (user == null) {
            for (OciInstanceGuard guard : group) {
                disableGuard(guard, "租户配置已删除，守护自动关闭");
            }
            return;
        }
        try (OciClientService client = new OciClientService(toSysUser(user, first.getRegion()),
                first.getRegion())) {
            for (OciInstanceGuard guard : group) {
                try {
                    checkOne(client, user, guard);
                } catch (Exception e) {
                    recordFailure(user, guard, "检测失败: " + describe(e));
                }
            }
        } catch (Exception e) {
            log.warn("实例守护无法连接租户 {} 区域 {}: {}",
                    user.getUsername(), first.getRegion(), e.getMessage());
        }
    }

    private void checkOne(OciClientService client, OciUser user, OciInstanceGuard guard) {
        Instance instance;
        try {
            instance = client.getComputeClient().getInstance(GetInstanceRequest.builder()
                    .instanceId(guard.getInstanceId()).build()).getInstance();
        } catch (BmcException e) {
            if (e.getStatusCode() == 404) {
                disableGuard(guard, "实例已不存在，守护自动关闭");
                notify(user, guard, "实例已不存在（可能被终止或无权限访问），守护已自动关闭。");
                return;
            }
            throw e;
        }
        Instance.LifecycleState state = instance == null ? null : instance.getLifecycleState();
        String stateValue = state == null ? "UNKNOWN" : state.getValue().toUpperCase(Locale.ROOT);
        guard.setLastState(stateValue);
        guard.setLastCheckTime(new Date());

        if (state == Instance.LifecycleState.Terminated
                || state == Instance.LifecycleState.Terminating) {
            disableGuard(guard, "实例已终止，守护自动关闭");
            notify(user, guard, "实例已终止，守护已自动关闭。");
            return;
        }
        if (state != Instance.LifecycleState.Stopped) {
            guard.setConsecutiveFailures(0);
            guard.setLastMessage("实例状态 " + stateValue + "，无需处理");
            guard.setUpdateTime(new Date());
            guardMapper.updateById(guard);
            return;
        }

        try {
            instanceService.updateInstanceState(
                    guard.getTenantConfigId(), guard.getInstanceId(), "START", guard.getRegion());
            Date now = new Date();
            guard.setLastStartTime(now);
            guard.setStartCount((guard.getStartCount() == null ? 0 : guard.getStartCount()) + 1);
            guard.setConsecutiveFailures(0);
            guard.setLastMessage("检测到 STOPPED，已自动启动");
            guard.setUpdateTime(now);
            guardMapper.updateById(guard);
            log.info("实例守护自动启动: tenant={} instance={} region={}",
                    user.getUsername(), guard.getInstanceId(), guard.getRegion());
            notify(user, guard, "检测到实例处于 STOPPED，已自动执行启动。");
        } catch (Exception e) {
            recordFailure(user, guard, "自动启动失败: " + describe(e));
        }
    }

    private void recordFailure(OciUser user, OciInstanceGuard guard, String message) {
        int failures = (guard.getConsecutiveFailures() == null ? 0 : guard.getConsecutiveFailures()) + 1;
        guard.setConsecutiveFailures(failures);
        guard.setLastMessage(truncate(message));
        guard.setUpdateTime(new Date());
        guardMapper.updateById(guard);
        log.warn("实例守护处理失败({}次): tenant={} instance={} {}",
                failures, user.getUsername(), guard.getInstanceId(), message);
        if (failures == NOTIFY_AFTER_FAILURES) {
            notify(user, guard, "连续 " + failures + " 次处理失败，最近错误：" + message
                    + "\n守护仍会继续重试。");
        }
    }

    private void disableGuard(OciInstanceGuard guard, String message) {
        guard.setEnabled(false);
        guard.setLastMessage(truncate(message));
        guard.setUpdateTime(new Date());
        guardMapper.updateById(guard);
    }

    private void notify(OciUser user, OciInstanceGuard guard, String body) {
        try {
            String name = guard.getInstanceName() == null || guard.getInstanceName().isBlank()
                    ? guard.getInstanceId() : guard.getInstanceName();
            notificationService.sendMessage("【OCIWorker 实例守护】\n\n租户：" + user.getUsername()
                    + "\n实例：" + name
                    + "\n区域：" + guard.getRegion()
                    + "\n" + body);
        } catch (Exception e) {
            log.debug("实例守护通知发送失败: {}", e.getMessage());
        }
    }

    private OciInstanceGuard find(String tenantId, String region, String instanceId) {
        return guardMapper.selectOne(new LambdaQueryWrapper<OciInstanceGuard>()
                .eq(OciInstanceGuard::getTenantConfigId, tenantId)
                .eq(OciInstanceGuard::getRegion, region)
                .eq(OciInstanceGuard::getInstanceId, instanceId)
                .last("LIMIT 1"));
    }

    private String normalizeRegion(String tenantId, String region) {
        if (region != null && !region.isBlank()) return region.trim();
        OciUser user = userMapper.selectById(tenantId);
        String fallback = user == null ? null : user.getOciRegion();
        if (fallback == null || fallback.isBlank()) throw new OciException("OCI 区域不能为空");
        return fallback.trim();
    }

    private static SysUserDTO toSysUser(OciUser user, String region) {
        return SysUserDTO.builder()
                .username(user.getUsername())
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(user.getOciTenantId())
                        .userId(user.getOciUserId())
                        .fingerprint(user.getOciFingerprint())
                        .region(region)
                        .privateKeyPath(user.getOciKeyPath())
                        .build())
                .build();
    }

    private static String describe(Exception e) {
        if (e instanceof BmcException bmc) return OciBmcErrorTranslator.translate(bmc);
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new OciException(label + "不能为空");
        return value.trim();
    }
}
