package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.mapper.ScheduledIpExecutionLockMapper;
import com.ociworker.mapper.ScheduledIpRunLogMapper;
import com.ociworker.mapper.ScheduledIpTaskMapper;
import com.ociworker.model.dto.ScheduledIpTaskRequest;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.entity.ScheduledIpRunLog;
import com.ociworker.model.entity.ScheduledIpTask;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ScheduledIpService {
    public static final int MIN_INTERVAL_MINUTES = 10;
    private static final int MAX_INTERVAL_MINUTES = 525_600;
    private static final int MAX_LOGS = 100;

    @Resource
    private ScheduledIpTaskMapper taskMapper;
    @Resource
    private ScheduledIpRunLogMapper runLogMapper;
    @Resource
    private ScheduledIpExecutionLockMapper executionLockMapper;
    @Resource
    private OciUserMapper userMapper;
    @Resource
    private CloudflareService cloudflareService;
    @Resource
    private AliDNSService aliDNSService;
    @Lazy
    @Resource
    private ScheduledIpRunner runner;

    public Map<String, Object> overview() {
        List<ScheduledIpTask> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<ScheduledIpTask>().orderByDesc(ScheduledIpTask::getCreateTime));
        Map<String, String> tenantCustomNames = loadTenantCustomNames(tasks);
        tasks.forEach(task -> applyTenantCustomName(task, tenantCustomNames));
        ScheduledIpRunLog latestLog = runLogMapper.selectOne(
                new LambdaQueryWrapper<ScheduledIpRunLog>()
                        .orderByDesc(ScheduledIpRunLog::getStartedAt)
                        .last("LIMIT 1"));
        ScheduledIpTask nextTask = taskMapper.selectOne(
                new LambdaQueryWrapper<ScheduledIpTask>()
                        .eq(ScheduledIpTask::getEnabled, true)
                        .isNotNull(ScheduledIpTask::getNextRunTime)
                        .orderByAsc(ScheduledIpTask::getNextRunTime)
                        .last("LIMIT 1"));
        applyTenantCustomName(nextTask, tenantCustomNames);

        long enabled = tasks.stream().filter(task -> Boolean.TRUE.equals(task.getEnabled())).count();
        long errors = tasks.stream().filter(task -> isErrorStatus(task.getLastStatus())).count();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", tasks.size());
        stats.put("enabled", enabled);
        stats.put("paused", tasks.size() - enabled);
        stats.put("errors", errors);
        stats.put("latestLog", latestLog);
        stats.put("nextTask", nextTask);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tasks", tasks);
        out.put("stats", stats);
        return out;
    }

    @Transactional
    public ScheduledIpTask create(ScheduledIpTaskRequest request) {
        ValidatedTask input = validate(request);
        Date now = new Date();
        ScheduledIpTask task = new ScheduledIpTask();
        task.setId(UUID.randomUUID().toString());
        apply(task, input);
        task.setName(limit(input.instanceName() + " 换IP", 255));
        task.setEnabled(true);
        task.setNextRunTime(Boolean.TRUE.equals(request.getFirstRunNow())
                ? now : plusMinutes(now, input.intervalMinutes()));
        task.setLastStatus("PENDING");
        task.setLastMessage("新建待执行");
        task.setConsecutiveFailures(0);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        ensureNoEnabledDuplicate(task, null);
        reserveEnabledInstance(task);
        taskMapper.insert(task);
        if (Boolean.TRUE.equals(request.getFirstRunNow())) {
            runAfterCommit(() -> runner.runAsync(task.getId(), "CREATE_IMMEDIATE"));
        }
        return task;
    }

    @Transactional
    public ScheduledIpTask update(ScheduledIpTaskRequest request) {
        String id = required(request.getId(), "任务 ID");
        ScheduledIpTask task = requireTask(id);
        ensureNotRunning(task);
        String previousInstanceKey = instanceKey(task);
        ValidatedTask input = validate(request);
        boolean instanceChanged = !Objects.equals(task.getTenantConfigId(), input.tenantConfigId())
                || !Objects.equals(task.getRegion(), input.region())
                || !Objects.equals(task.getInstanceId(), input.instanceId());
        apply(task, input);
        task.setName(limit(input.instanceName() + " 换IP", 255));
        if (instanceChanged) {
            task.setLastStatus("PENDING");
            task.setLastMessage("配置已更新，等待执行");
            task.setConsecutiveFailures(0);
            task.setLastRunTime(null);
        }
        Date now = new Date();
        task.setNextRunTime(Boolean.TRUE.equals(task.getEnabled())
                ? (Boolean.TRUE.equals(request.getFirstRunNow()) ? now : plusMinutes(now, input.intervalMinutes()))
                : null);
        task.setUpdateTime(now);
        ensureNoEnabledDuplicate(task, task.getId());
        if (Boolean.TRUE.equals(task.getEnabled())) {
            reserveEnabledInstance(task);
            String updatedInstanceKey = instanceKey(task);
            if (!previousInstanceKey.equals(updatedInstanceKey)) {
                executionLockMapper.releaseEnabledTask(previousInstanceKey, task.getId(), now);
            }
        }
        taskMapper.updateById(task);
        if (Boolean.TRUE.equals(task.getEnabled()) && Boolean.TRUE.equals(request.getFirstRunNow())) {
            runAfterCommit(() -> runner.runAsync(task.getId(), "UPDATE_IMMEDIATE"));
        }
        return task;
    }

    @Transactional
    public ScheduledIpTask copy(String id) {
        ScheduledIpTask source = requireTask(id);
        Date now = new Date();
        ScheduledIpTask copy = new ScheduledIpTask();
        copy.setId(UUID.randomUUID().toString());
        copy.setName(limit(source.getName() + " (副本)", 255));
        copy.setTenantConfigId(source.getTenantConfigId());
        OciUser sourceUser = userMapper.selectById(source.getTenantConfigId());
        if (sourceUser == null) throw new OciException("租户配置不存在");
        copy.setTenantName(required(sourceUser.getUsername(), "租户自定义名称"));
        copy.setRegion(source.getRegion());
        copy.setInstanceId(source.getInstanceId());
        copy.setInstanceName(source.getInstanceName());
        copy.setShape(source.getShape());
        copy.setCompartmentId(source.getCompartmentId());
        copy.setCurrentPublicIp(source.getCurrentPublicIp());
        copy.setEnabled(false);
        copy.setIntervalMinutes(source.getIntervalMinutes());
        copy.setLastStatus("DISABLED");
        copy.setLastMessage("已暂停");
        copy.setDnsEnabled(source.getDnsEnabled());
        copy.setDnsProvider(source.getDnsProvider());
        copy.setFqdn(source.getFqdn());
        copy.setNotifySuccess(source.getNotifySuccess());
        copy.setNotifyIpFailure(source.getNotifyIpFailure());
        copy.setNotifyDnsFailure(source.getNotifyDnsFailure());
        copy.setNotifyAutoPaused(source.getNotifyAutoPaused());
        copy.setConsecutiveFailures(0);
        copy.setCreateTime(now);
        copy.setUpdateTime(now);
        taskMapper.insert(copy);
        return copy;
    }

    @Transactional
    public ScheduledIpTask setEnabled(String id, boolean enabled) {
        ScheduledIpTask task = requireTask(id);
        ensureNotRunning(task);
        task.setEnabled(enabled);
        task.setNextRunTime(enabled ? plusMinutes(new Date(), interval(task)) : null);
        task.setLastStatus(enabled ? "PENDING" : "DISABLED");
        task.setLastMessage(enabled ? "已启用，等待执行" : "已暂停");
        task.setConsecutiveFailures(enabled ? 0 : task.getConsecutiveFailures());
        task.setUpdateTime(new Date());
        if (enabled) {
            ensureNoEnabledDuplicate(task, task.getId());
            reserveEnabledInstance(task);
        }
        taskMapper.updateById(task);
        if (!enabled) {
            executionLockMapper.releaseEnabledTask(instanceKey(task), task.getId(), new Date());
        }
        return task;
    }

    @Transactional
    public void delete(String id) {
        ScheduledIpTask task = requireTask(id);
        ensureNotRunning(task);
        runLogMapper.delete(new LambdaQueryWrapper<ScheduledIpRunLog>()
                .eq(ScheduledIpRunLog::getTaskId, id));
        taskMapper.deleteById(id);
        executionLockMapper.releaseEnabledTask(instanceKey(task), task.getId(), new Date());
    }

    public List<ScheduledIpRunLog> logs(String id) {
        requireTask(id);
        return runLogMapper.selectList(new LambdaQueryWrapper<ScheduledIpRunLog>()
                .eq(ScheduledIpRunLog::getTaskId, id)
                .orderByDesc(ScheduledIpRunLog::getStartedAt)
                .last("LIMIT " + MAX_LOGS));
    }

    public void runNow(String id) {
        requireTask(id);
        runner.runAsync(id, "MANUAL");
    }

    public void retryDns(String id) {
        ScheduledIpTask task = requireTask(id);
        if (!Boolean.TRUE.equals(task.getDnsEnabled())) throw new OciException("该任务未启用 DNS 同步");
        if (task.getCurrentPublicIp() == null || task.getCurrentPublicIp().isBlank()) {
            throw new OciException("任务没有可用于重试 DNS 的公网 IP");
        }
        runner.retryDnsAsync(id);
    }

    private void apply(ScheduledIpTask task, ValidatedTask input) {
        task.setTenantConfigId(input.tenantConfigId());
        task.setTenantName(input.tenantName());
        task.setRegion(input.region());
        task.setInstanceId(input.instanceId());
        task.setInstanceName(input.instanceName());
        task.setShape(input.shape());
        task.setCompartmentId(input.compartmentId());
        task.setCurrentPublicIp(input.currentPublicIp());
        task.setIntervalMinutes(input.intervalMinutes());
        task.setDnsEnabled(input.dnsEnabled());
        task.setDnsProvider(input.dnsProvider());
        task.setFqdn(input.fqdn());
        task.setDnsZoneId(null);
        task.setDnsDomainName(null);
        task.setDnsRecordId(null);
        task.setDnsRecordName(null);
        task.setNotifySuccess(input.notifySuccess());
        task.setNotifyIpFailure(input.notifyIpFailure());
        task.setNotifyDnsFailure(input.notifyDnsFailure());
        task.setNotifyAutoPaused(input.notifyAutoPaused());
    }

    private ValidatedTask validate(ScheduledIpTaskRequest request) {
        if (request == null) throw new OciException("任务参数不能为空");
        String tenantConfigId = required(request.getTenantConfigId(), "租户");
        OciUser user = userMapper.selectById(tenantConfigId);
        if (user == null) throw new OciException("租户配置不存在");
        String region = required(request.getRegion(), "区域").toLowerCase(Locale.ROOT);
        if (!region.matches("^[a-z0-9-]{3,64}$")) throw new OciException("区域格式不正确");
        String instanceId = required(request.getInstanceId(), "实例");
        if (!instanceId.startsWith("ocid1.instance.")) throw new OciException("实例 OCID 格式不正确");
        String instanceName = required(request.getInstanceName(), "实例名称");
        int interval = request.getIntervalMinutes() != null ? request.getIntervalMinutes() : 60;
        if (interval < MIN_INTERVAL_MINUTES || interval > MAX_INTERVAL_MINUTES) {
            throw new OciException("执行间隔必须在 10 到 525600 分钟之间");
        }
        boolean dnsEnabled = Boolean.TRUE.equals(request.getDnsEnabled());
        String provider = null;
        String fqdn = null;
        if (dnsEnabled) {
            provider = ScheduledIpDnsService.normalizeProvider(required(request.getDnsProvider(), "DNS 服务商"));
            if (!List.of(ScheduledIpDnsService.PROVIDER_CLOUDFLARE,
                    ScheduledIpDnsService.PROVIDER_ALIDNS).contains(provider)) {
                throw new OciException("不支持的 DNS 服务商");
            }
            if (ScheduledIpDnsService.PROVIDER_CLOUDFLARE.equals(provider)
                    && !cloudflareService.isConfigured()) {
                throw new OciException("Cloudflare 未配置，请先前往系统设置完成配置");
            }
            if (ScheduledIpDnsService.PROVIDER_ALIDNS.equals(provider)
                    && !aliDNSService.isConfigured()) {
                throw new OciException("阿里云 DNS 未配置，请先前往系统设置完成配置");
            }
            fqdn = ScheduledIpDnsService.normalizeFqdn(request.getFqdn());
        }
        String tenantName = required(user.getUsername(), "租户自定义名称");
        return new ValidatedTask(
                tenantConfigId, tenantName, region, instanceId, instanceName.trim(),
                trim(request.getShape()), trim(request.getCompartmentId()), trim(request.getCurrentPublicIp()),
                interval, dnsEnabled, provider, fqdn,
                Boolean.TRUE.equals(request.getNotifySuccess()),
                request.getNotifyIpFailure() == null || Boolean.TRUE.equals(request.getNotifyIpFailure()),
                request.getNotifyDnsFailure() == null || Boolean.TRUE.equals(request.getNotifyDnsFailure()),
                request.getNotifyAutoPaused() == null || Boolean.TRUE.equals(request.getNotifyAutoPaused()));
    }

    private void ensureNoEnabledDuplicate(ScheduledIpTask task, String excludeId) {
        if (!Boolean.TRUE.equals(task.getEnabled())) return;
        LambdaQueryWrapper<ScheduledIpTask> query = new LambdaQueryWrapper<ScheduledIpTask>()
                .eq(ScheduledIpTask::getTenantConfigId, task.getTenantConfigId())
                .eq(ScheduledIpTask::getRegion, task.getRegion())
                .eq(ScheduledIpTask::getInstanceId, task.getInstanceId())
                .eq(ScheduledIpTask::getEnabled, true);
        if (excludeId != null) query.ne(ScheduledIpTask::getId, excludeId);
        if (taskMapper.selectCount(query) > 0) throw new OciException("该实例已有启用中的定时换 IP 任务");
    }

    void reserveEnabledInstance(ScheduledIpTask task) {
        String key = instanceKey(task);
        Date now = new Date();
        executionLockMapper.ensureRow(key, now);
        executionLockMapper.clearStaleEnabledTask(key, now);
        if (executionLockMapper.claimEnabledTask(key, task.getId(), now) != 1) {
            throw new OciException("该实例已有启用中的定时换 IP 任务");
        }
    }

    static String instanceKey(ScheduledIpTask task) {
        return instanceKey(task.getTenantConfigId(), task.getRegion(), task.getInstanceId());
    }

    static String instanceKey(String tenantConfigId, String region, String instanceId) {
        return String.join("|", required(tenantConfigId, "租户"), required(region, "区域"), required(instanceId, "实例"));
    }

    static void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private ScheduledIpTask requireTask(String id) {
        ScheduledIpTask task = taskMapper.selectById(required(id, "任务 ID"));
        if (task == null) throw new OciException("定时换 IP 任务不存在");
        return task;
    }

    private Map<String, String> loadTenantCustomNames(List<ScheduledIpTask> tasks) {
        List<String> tenantIds = tasks.stream()
                .map(ScheduledIpTask::getTenantConfigId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, String> names = new LinkedHashMap<>();
        if (tenantIds.isEmpty()) return names;
        for (OciUser user : userMapper.selectByIds(tenantIds)) {
            if (user.getId() != null && user.getUsername() != null) {
                names.put(user.getId(), user.getUsername());
            }
        }
        return names;
    }

    private static void applyTenantCustomName(ScheduledIpTask task, Map<String, String> names) {
        if (task == null) return;
        String customName = names.get(task.getTenantConfigId());
        if (customName != null) task.setTenantName(customName);
    }

    private void ensureNotRunning(ScheduledIpTask task) {
        if (task.getLockUntil() != null && task.getLockUntil().after(new Date())) {
            throw new OciException("任务正在执行，请稍后再操作");
        }
    }

    private static boolean isErrorStatus(String status) {
        return "IP_FAILED".equals(status) || "DNS_FAILED".equals(status) || "AUTO_PAUSED".equals(status);
    }

    private static int interval(ScheduledIpTask task) {
        return task.getIntervalMinutes() != null ? Math.max(MIN_INTERVAL_MINUTES, task.getIntervalMinutes()) : 60;
    }

    private static Date plusMinutes(Date date, int minutes) {
        return Date.from(date.toInstant().plus(minutes, ChronoUnit.MINUTES));
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new OciException("请选择或填写" + label);
        return value.trim();
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private record ValidatedTask(
            String tenantConfigId,
            String tenantName,
            String region,
            String instanceId,
            String instanceName,
            String shape,
            String compartmentId,
            String currentPublicIp,
            int intervalMinutes,
            boolean dnsEnabled,
            String dnsProvider,
            String fqdn,
            boolean notifySuccess,
            boolean notifyIpFailure,
            boolean notifyDnsFailure,
            boolean notifyAutoPaused) {
    }
}
