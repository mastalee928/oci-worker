package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.mapper.ScheduledIpExecutionLockMapper;
import com.ociworker.mapper.ScheduledIpRunLogMapper;
import com.ociworker.mapper.ScheduledIpTaskMapper;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.entity.ScheduledIpRunLog;
import com.ociworker.model.entity.ScheduledIpTask;
import com.ociworker.util.OciBmcErrorTranslator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class ScheduledIpRunner {
    private static final Duration EXECUTION_LEASE = Duration.ofMinutes(10);
    private static final Duration NOTIFY_COOLDOWN = Duration.ofMinutes(60);
    private static final Duration LOG_RETENTION = Duration.ofDays(90);
    private static final int AUTO_PAUSE_FAILURES = 3;
    private static final Set<String> activeTasks = ConcurrentHashMap.newKeySet();
    private static final Set<String> activeInstances = ConcurrentHashMap.newKeySet();

    @Resource
    private ScheduledIpTaskMapper taskMapper;
    @Resource
    private ScheduledIpRunLogMapper runLogMapper;
    @Resource
    private ScheduledIpExecutionLockMapper executionLockMapper;
    @Resource
    private OciUserMapper userMapper;
    @Resource
    private NetworkService networkService;
    @Resource
    private ScheduledIpDnsService dnsService;
    @Resource
    private NotificationService notificationService;

    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void scheduleDueTasks() {
        Date now = new Date();
        List<ScheduledIpTask> due = taskMapper.selectList(new LambdaQueryWrapper<ScheduledIpTask>()
                .eq(ScheduledIpTask::getEnabled, true)
                .isNotNull(ScheduledIpTask::getNextRunTime)
                .le(ScheduledIpTask::getNextRunTime, now)
                .orderByAsc(ScheduledIpTask::getNextRunTime)
                .last("LIMIT 50"));
        for (ScheduledIpTask task : due) runAsync(task.getId(), "SCHEDULED");
    }

    @Scheduled(cron = "0 17 3 * * *")
    public void cleanupOldRunLogs() {
        Date cutoff = Date.from(Instant.now().minus(LOG_RETENTION));
        int deleted = runLogMapper.deleteOlderThan(cutoff);
        if (deleted > 0) log.info("清理定时换 IP 历史日志: deleted={}", deleted);
    }

    public void runAsync(String taskId, String triggerType) {
        Thread.ofVirtual().name("scheduled-ip-" + taskId).start(() -> execute(taskId, triggerType));
    }

    public void retryDnsAsync(String taskId) {
        Thread.ofVirtual().name("scheduled-ip-dns-" + taskId).start(() -> retryDns(taskId));
    }

    void execute(String taskId, String triggerType) {
        if (!activeTasks.add(taskId)) return;
        String owner = UUID.randomUUID().toString();
        ScheduledIpTask task = null;
        String instanceLock = null;
        ScheduledIpRunLog runLog = null;
        boolean taskClaimed = false;
        boolean instanceClaimed = false;
        boolean localInstanceClaimed = false;
        try {
            task = taskMapper.selectById(taskId);
            if (task == null || ("SCHEDULED".equals(triggerType) && !Boolean.TRUE.equals(task.getEnabled()))) return;
            Date now = new Date();
            Date lockUntil = Date.from(now.toInstant().plus(EXECUTION_LEASE));
            taskClaimed = taskMapper.claimExecution(taskId, owner, now, lockUntil) == 1;
            if (!taskClaimed) return;
            task.setLockOwner(owner);
            task.setLockUntil(lockUntil);

            instanceLock = instanceKey(task);
            executionLockMapper.ensureRow(instanceLock, now);
            instanceClaimed = executionLockMapper.claimExecution(instanceLock, owner, now, lockUntil) == 1;
            if (!instanceClaimed) return;
            localInstanceClaimed = activeInstances.add(instanceLock);
            if (!localInstanceClaimed) return;

            runLog = beginLog(task, triggerType);
            task.setLastStatus("RUNNING");
            task.setLastMessage("正在更换公网 IP");
            task.setNextRunTime(Boolean.TRUE.equals(task.getEnabled()) ? plusMinutes(now, interval(task)) : null);
            task.setUpdateTime(now);
            if (taskMapper.updateById(task) != 1) {
                throw new OciException("任务状态初始化保存未生效");
            }

            NetworkService.ChangePublicIpResult result;
            try {
                if (Boolean.TRUE.equals(task.getEnabled())) reserveEnabledInstance(task);
                OciUser user = userMapper.selectById(task.getTenantConfigId());
                if (user == null) throw new AutoPauseException("租户配置已不存在");
                task.setTenantName(user.getUsername());
                result = networkService.changePublicIpForScheduledTask(
                        task.getTenantConfigId(), task.getInstanceId(), task.getRegion(), task.getCompartmentId());
            } catch (Exception error) {
                handleIpFailure(task, runLog, error);
                return;
            }

            Date finished = new Date();
            task.setCurrentPublicIp(result.newIp());
            task.setCompartmentId(result.compartmentId());
            task.setLastRunTime(finished);
            task.setConsecutiveFailures(0);

            String message;
            String pendingNotification = null;
            boolean notifySuccess = false;
            if (Boolean.TRUE.equals(task.getDnsEnabled())) {
                try {
                    ScheduledIpDnsService.DnsSyncResult dns = dnsService.sync(task, result.newIp());
                    applyDnsSnapshot(task, dns);
                    task.setLastStatus("SUCCESS");
                    message = "换 IP + DNS 成功";
                    runLog.setStatus("SUCCESS");
                    runLog.setDnsStatus("SUCCESS");
                    runLog.setDnsMessage(dns.message());
                    notifySuccess = Boolean.TRUE.equals(task.getNotifySuccess());
                } catch (Exception dnsError) {
                    String dnsMessage = shortMessage(dnsError);
                    task.setLastStatus("DNS_FAILED");
                    message = "换 IP 成功，DNS 同步失败：" + dnsMessage;
                    runLog.setStatus("PARTIAL_FAILED");
                    runLog.setDnsStatus("FAILED");
                    runLog.setDnsMessage(dnsMessage);
                    if (Boolean.TRUE.equals(task.getNotifyDnsFailure())) {
                        pendingNotification = prepareNotification(
                                task, "DNS:" + dnsMessage,
                                dnsFailureMessage(task, result.newIp(), dnsMessage));
                    }
                }
            } else {
                task.setLastStatus("SUCCESS");
                message = "换 IP 成功（未配 DNS）";
                runLog.setStatus("SUCCESS");
                runLog.setDnsStatus("SKIPPED");
                notifySuccess = Boolean.TRUE.equals(task.getNotifySuccess());
            }
            task.setLastMessage(limit(message, 1024));
            ScheduledIpTask latest = taskMapper.selectById(taskId);
            boolean enabled = latest != null && Boolean.TRUE.equals(latest.getEnabled());
            task.setEnabled(enabled);
            task.setNextRunTime(enabled ? plusMinutes(finished, interval(task)) : null);
            task.setUpdateTime(finished);
            try {
                if (taskMapper.updateById(task) != 1) {
                    throw new OciException("任务记录不存在或状态保存未生效");
                }
            } catch (Exception persistenceError) {
                handlePostChangePersistenceFailure(task, runLog, result, persistenceError);
                return;
            }

            runLog.setOldIp(result.oldIp());
            runLog.setNewIp(result.newIp());
            runLog.setMessage(limit(message, 1024));
            runLog.setFinishedAt(finished);
            persistRunLogSafely(runLog);
            if (pendingNotification != null) notificationService.sendMessage(pendingNotification);
            if (notifySuccess) sendSuccess(task, result, Boolean.TRUE.equals(task.getDnsEnabled()));
            log.info("【定时换 IP】租户:[{}],区域:[{}],实例:[{}] - {}",
                    task.getTenantName(), task.getRegion(), task.getInstanceName(), message);
        } catch (Exception unexpected) {
            String message = shortMessage(unexpected);
            log.error("【定时换 IP】任务执行流程异常: taskId={}, message={}", taskId, message, unexpected);
            if (runLog != null) {
                runLog.setStatus("FAILED");
                runLog.setDnsStatus("SKIPPED");
                runLog.setMessage(limit("任务执行流程异常：" + message, 1024));
                runLog.setFinishedAt(new Date());
                persistRunLogSafely(runLog);
            }
        } finally {
            if (localInstanceClaimed && instanceLock != null) activeInstances.remove(instanceLock);
            releaseInstanceExecutionSafely(instanceClaimed, instanceLock, owner);
            releaseTaskExecutionSafely(taskClaimed, taskId, owner);
            activeTasks.remove(taskId);
        }
    }

    private void retryDns(String taskId) {
        if (!activeTasks.add(taskId)) return;
        String owner = UUID.randomUUID().toString();
        boolean taskClaimed = false;
        boolean instanceClaimed = false;
        boolean localInstanceClaimed = false;
        String instanceLock = null;
        boolean dnsSucceeded = false;
        ScheduledIpRunLog runLog = null;
        try {
            ScheduledIpTask task = taskMapper.selectById(taskId);
            if (task == null) return;
            OciUser user = userMapper.selectById(task.getTenantConfigId());
            if (user != null) task.setTenantName(user.getUsername());
            Date now = new Date();
            Date lockUntil = Date.from(now.toInstant().plus(EXECUTION_LEASE));
            taskClaimed = taskMapper.claimExecution(taskId, owner, now, lockUntil) == 1;
            if (!taskClaimed) return;
            task.setLockOwner(owner);
            task.setLockUntil(lockUntil);
            instanceLock = instanceKey(task);
            executionLockMapper.ensureRow(instanceLock, now);
            instanceClaimed = executionLockMapper.claimExecution(instanceLock, owner, now, lockUntil) == 1;
            if (!instanceClaimed) return;
            localInstanceClaimed = activeInstances.add(instanceLock);
            if (!localInstanceClaimed) return;
            runLog = beginLog(task, "DNS_RETRY");
            task.setLastStatus("RUNNING");
            task.setLastMessage("正在重新解析 DNS");
            task.setUpdateTime(now);
            if (taskMapper.updateById(task) != 1) {
                throw new OciException("DNS 重试状态初始化保存未生效");
            }
            ScheduledIpDnsService.DnsSyncResult dns = dnsService.sync(task, task.getCurrentPublicIp());
            dnsSucceeded = true;
            applyDnsSnapshot(task, dns);
            task.setLastStatus("SUCCESS");
            task.setLastMessage("DNS 重新解析成功");
            task.setUpdateTime(new Date());
            if (taskMapper.updateById(task) != 1) {
                throw new OciException("DNS 已更新，但任务状态保存未生效");
            }
            runLog.setStatus("SUCCESS");
            runLog.setDnsStatus("SUCCESS");
            runLog.setNewIp(task.getCurrentPublicIp());
            runLog.setMessage("DNS 重新解析成功");
            runLog.setDnsMessage(dns.message());
            runLog.setFinishedAt(new Date());
            persistRunLogSafely(runLog);
        } catch (Exception error) {
            String message = shortMessage(error);
            if (runLog != null) {
                runLog.setStatus(dnsSucceeded ? "PARTIAL_FAILED" : "FAILED");
                runLog.setDnsStatus(dnsSucceeded ? "SUCCESS" : "FAILED");
                runLog.setMessage(dnsSucceeded
                        ? "DNS 已更新，但任务状态保存失败：" + message
                        : "DNS 重新解析失败：" + message);
                runLog.setDnsMessage(message);
                runLog.setFinishedAt(new Date());
                persistRunLogSafely(runLog);
            }
            ScheduledIpTask task = taskMapper.selectById(taskId);
            if (task != null && !dnsSucceeded) {
                task.setLastStatus("DNS_FAILED");
                task.setLastMessage(limit("DNS 重新解析失败：" + message, 1024));
                task.setUpdateTime(new Date());
                String pendingNotification = null;
                if (Boolean.TRUE.equals(task.getNotifyDnsFailure())) {
                    pendingNotification = prepareNotification(
                            task, "DNS:" + message,
                            dnsFailureMessage(task, task.getCurrentPublicIp(), message));
                }
                taskMapper.updateById(task);
                if (pendingNotification != null) notificationService.sendMessage(pendingNotification);
            }
        } finally {
            if (localInstanceClaimed && instanceLock != null) activeInstances.remove(instanceLock);
            releaseInstanceExecutionSafely(instanceClaimed, instanceLock, owner);
            releaseTaskExecutionSafely(taskClaimed, taskId, owner);
            activeTasks.remove(taskId);
        }
    }

    private void releaseInstanceExecutionSafely(boolean claimed, String instanceKey, String owner) {
        if (!claimed || instanceKey == null) return;
        try {
            executionLockMapper.releaseExecution(instanceKey, owner, new Date());
        } catch (Exception error) {
            log.error("释放定时换 IP 实例锁失败: instanceKey={}", instanceKey, error);
        }
    }

    private void releaseTaskExecutionSafely(boolean claimed, String taskId, String owner) {
        if (!claimed) return;
        try {
            taskMapper.releaseExecution(taskId, owner, new Date());
        } catch (Exception error) {
            log.error("释放定时换 IP 任务锁失败: taskId={}", taskId, error);
        }
    }

    private void handleIpFailure(ScheduledIpTask task, ScheduledIpRunLog runLog, Exception error) {
        if (task == null) return;
        String message = shortMessage(error);
        boolean immediatePause = error instanceof AutoPauseException || shouldPauseImmediately(message);
        int failures = (task.getConsecutiveFailures() == null ? 0 : task.getConsecutiveFailures()) + 1;
        boolean autoPaused = immediatePause || failures >= AUTO_PAUSE_FAILURES;
        Date now = new Date();
        task.setConsecutiveFailures(failures);
        task.setLastRunTime(now);
        task.setEnabled(!autoPaused && Boolean.TRUE.equals(task.getEnabled()));
        task.setNextRunTime(task.getEnabled() ? plusMinutes(now, interval(task)) : null);
        task.setLastStatus(autoPaused ? "AUTO_PAUSED" : "IP_FAILED");
        task.setLastMessage(limit(autoPaused ? "任务已自动暂停：" + message : "换 IP 失败：" + message, 1024));
        task.setUpdateTime(now);
        String pendingNotification;
        if (runLog != null) {
            runLog.setStatus(autoPaused ? "AUTO_PAUSED" : "FAILED");
            runLog.setDnsStatus("SKIPPED");
            runLog.setMessage(task.getLastMessage());
            runLog.setFinishedAt(now);
            persistRunLogSafely(runLog);
        }
        if (autoPaused) {
            if (Boolean.TRUE.equals(task.getNotifyAutoPaused()) || Boolean.TRUE.equals(task.getNotifyIpFailure())) {
                pendingNotification = prepareNotification(task, "AUTO_PAUSED:" + message, autoPauseMessage(task, message));
            } else pendingNotification = null;
        } else if (Boolean.TRUE.equals(task.getNotifyIpFailure())) {
            pendingNotification = prepareNotification(task, "IP:" + message, ipFailureMessage(task, message));
        } else pendingNotification = null;
        boolean persisted = taskMapper.updateById(task) == 1;
        if (autoPaused && persisted) {
            executionLockMapper.releaseEnabledTask(instanceKey(task), task.getId(), now);
        }
        if (pendingNotification != null) notificationService.sendMessage(pendingNotification);
        log.warn("【定时换 IP】租户:[{}],区域:[{}],实例:[{}] - {}",
                task.getTenantName(), task.getRegion(), task.getInstanceName(), task.getLastMessage());
    }

    private void handlePostChangePersistenceFailure(
            ScheduledIpTask task,
            ScheduledIpRunLog runLog,
            NetworkService.ChangePublicIpResult result,
            Exception error) {
        String detail = shortMessage(error);
        String message = "公网 IP 已更换，但任务状态保存失败：" + detail;
        if (runLog != null) {
            runLog.setStatus("PARTIAL_FAILED");
            runLog.setOldIp(result.oldIp());
            runLog.setNewIp(result.newIp());
            if (runLog.getDnsStatus() == null || "PENDING".equals(runLog.getDnsStatus())) {
                runLog.setDnsStatus("SKIPPED");
            }
            runLog.setMessage(limit(message, 1024));
            runLog.setFinishedAt(new Date());
            persistRunLogSafely(runLog);
        }
        log.error("【定时换 IP】公网 IP 已更换但状态保存失败: taskId={}, oldIp={}, newIp={}, detail={}",
                task.getId(), result.oldIp(), result.newIp(), detail, error);
    }

    private void reserveEnabledInstance(ScheduledIpTask task) {
        String key = instanceKey(task);
        Date now = new Date();
        executionLockMapper.ensureRow(key, now);
        executionLockMapper.clearStaleEnabledTask(key, now);
        if (executionLockMapper.claimEnabledTask(key, task.getId(), now) != 1) {
            throw new AutoPauseException("该实例存在其它启用中的定时换 IP 任务");
        }
    }

    private void persistRunLogSafely(ScheduledIpRunLog runLog) {
        if (runLog == null) return;
        try {
            runLogMapper.updateById(runLog);
        } catch (Exception error) {
            log.error("保存定时换 IP 执行日志失败: taskId={}, runId={}",
                    runLog.getTaskId(), runLog.getRunId(), error);
        }
    }

    private ScheduledIpRunLog beginLog(ScheduledIpTask task, String triggerType) {
        ScheduledIpRunLog logRow = new ScheduledIpRunLog();
        logRow.setId(UUID.randomUUID().toString());
        logRow.setTaskId(task.getId());
        logRow.setRunId(UUID.randomUUID().toString());
        logRow.setTriggerType(triggerType);
        logRow.setStatus("RUNNING");
        logRow.setDnsStatus("PENDING");
        logRow.setStartedAt(new Date());
        if (runLogMapper.insert(logRow) != 1) {
            throw new OciException("执行日志初始化保存未生效");
        }
        return logRow;
    }

    private void applyDnsSnapshot(ScheduledIpTask task, ScheduledIpDnsService.DnsSyncResult dns) {
        task.setDnsZoneId(dns.zoneId());
        task.setDnsDomainName(dns.domainName() != null ? dns.domainName() : dns.zoneName());
        task.setDnsRecordId(dns.recordId());
        task.setDnsRecordName(dns.recordName());
    }

    private void sendSuccess(ScheduledIpTask task, NetworkService.ChangePublicIpResult result, boolean dnsUpdated) {
        notificationService.sendMessage("【OCIWorker 定时换 IP】\n\n任务：" + task.getName()
                + "\n租户：" + safe(task.getTenantName())
                + "\n区域：" + task.getRegion()
                + "\n实例：" + task.getInstanceName()
                + "\n结果：" + (dnsUpdated ? "换 IP 与 DNS 同步成功" : "换 IP 成功")
                + "\n原 IP：" + safe(result.oldIp())
                + "\n当前 IP：" + safe(result.newIp()));
    }

    private String ipFailureMessage(ScheduledIpTask task, String reason) {
        return "【OCIWorker 定时换 IP】\n\n任务：" + task.getName()
                + "\n租户：" + safe(task.getTenantName())
                + "\n区域：" + task.getRegion()
                + "\n实例：" + task.getInstanceName()
                + "\n结果：换 IP 失败"
                + "\n原因：" + reason;
    }

    private String dnsFailureMessage(ScheduledIpTask task, String currentIp, String reason) {
        return "【OCIWorker 定时换 IP】\n\n任务：" + task.getName()
                + "\n租户：" + safe(task.getTenantName())
                + "\n区域：" + task.getRegion()
                + "\n结果：换 IP 成功，DNS 同步失败"
                + "\n当前 IP：" + safe(currentIp)
                + "\n域名：" + safe(task.getFqdn())
                + "\n原因：" + reason
                + "\n\n公网 IP 已成功更换，本次不会再次换 IP。"
                + "\n请在定时换 IP 任务中重新解析 DNS。";
    }

    private String autoPauseMessage(ScheduledIpTask task, String reason) {
        return "【OCIWorker 定时换 IP】\n\n任务：" + task.getName()
                + "\n租户：" + safe(task.getTenantName())
                + "\n区域：" + task.getRegion()
                + "\n实例：" + task.getInstanceName()
                + "\n结果：任务已自动暂停"
                + "\n原因：" + reason;
    }

    private String prepareNotification(ScheduledIpTask task, String key, String message) {
        String normalizedKey = limit(key, 255);
        Date lastTime = task.getLastNotifyTime();
        if (normalizedKey.equals(task.getLastNotifyKey()) && lastTime != null
                && lastTime.toInstant().plus(NOTIFY_COOLDOWN).isAfter(Instant.now())) return null;
        task.setLastNotifyKey(normalizedKey);
        task.setLastNotifyTime(new Date());
        return message;
    }

    private static boolean shouldPauseImmediately(String message) {
        if (message == null) return false;
        return message.contains("租户配置不存在")
                || message.contains("未找到实例的主 VNIC")
                || message.contains("实例不存在");
    }

    private static String shortMessage(Throwable error) {
        String translated = OciBmcErrorTranslator.translate(error);
        if (translated == null || translated.isBlank()) translated = error.getMessage();
        if (translated == null || translated.isBlank()) translated = "未知错误";
        return limit(translated.replaceAll("\\s+", " ").trim(), 500);
    }

    private static int interval(ScheduledIpTask task) {
        return task.getIntervalMinutes() == null
                ? 60 : Math.max(ScheduledIpService.MIN_INTERVAL_MINUTES, task.getIntervalMinutes());
    }

    private static Date plusMinutes(Date date, int minutes) {
        return Date.from(date.toInstant().plus(minutes, ChronoUnit.MINUTES));
    }

    private static String instanceKey(ScheduledIpTask task) {
        return ScheduledIpService.instanceKey(task);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private static final class AutoPauseException extends OciException {
        private AutoPauseException(String message) {
            super(message);
        }
    }
}
