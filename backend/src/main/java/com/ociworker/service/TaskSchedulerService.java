package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ociworker.enums.ArchitectureEnum;
import com.ociworker.enums.TaskStatusEnum;
import com.ociworker.enums.TaskLoginModeEnum;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.InstanceDetailDTO;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciCreateTask;
import com.ociworker.model.entity.OciKv;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.params.PageParams;
import cn.hutool.core.util.StrUtil;
import com.ociworker.util.BootVolumeVpusUtil;
import com.ociworker.util.CommonUtils;
import com.ociworker.util.OciBmcErrorTranslator;
import com.ociworker.util.OciRegionUtil;
import com.ociworker.util.ShapeFlexLimitsUtil;
import com.ociworker.util.ShapeSeriesUtil;
import com.ociworker.websocket.LogWebSocketHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.ociworker.config.VirtualThreadConfig.VIRTUAL_EXECUTOR;

@Slf4j
@Service
@DependsOn("databaseGuardService")
public class TaskSchedulerService implements SmartLifecycle {

    @Resource
    private OciCreateTaskMapper taskMapper;
    @Resource
    private OciUserMapper userMapper;
    @Resource
    private NotificationService notificationService;
    @Resource
    private OciKvMapper kvMapper;
    @Resource
    private AdaptiveLaunchConcurrency adaptiveLaunchConcurrency;
    @Resource
    private TaskSuccessAllocationService taskSuccessAllocationService;

    private final Map<String, TaskHandle> taskMap = new ConcurrentHashMap<>();
    private final Set<String> runningTasks = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, LocalDateTime> recentTaskCreateKeys = new ConcurrentHashMap<>();
    /** 本任务周期内不再尝试的可用域（停/改/恢复/完成/删除任务或服务重启后清空） */
    private final ConcurrentHashMap<String, Set<String>> taskExcludedAds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalDateTime> serviceLimitNotifyTimes = new ConcurrentHashMap<>();
    private final Set<String> serviceLimitNotifyMutedTasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean maintenanceRunning = new AtomicBoolean();
    private final ConcurrentHashMap<String, RateLimitState> rateLimitStates = new ConcurrentHashMap<>();
    /** 同一批量任务的成功通知按 1、2、… 序号发送，避免并发回调乱序或重复显示最终计数。 */
    private final ConcurrentHashMap<String, OrderedSuccessNotificationQueue> successNotificationQueues =
            new ConcurrentHashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CREATE_TASK_DEDUP_SECONDS = 5;
    private static final int SERVICE_LIMIT_NOTIFY_COOLDOWN_MINUTES = 60;
    /** 序号缺口的最大等待时间；正常并发回调会在此之前补齐，异常丢回调也不会永久堵住后续通知。 */
    private static final long SUCCESS_NOTIFICATION_GAP_WAIT_MILLIS = 3_000L;
    private static final String SERVICE_LIMIT_MUTE_KV_TYPE = "task_service_limit_mute";
    private static final String CALLBACK_SERVICE_LIMIT_STOP_REQUEST = "ctsl_stop_req|";
    private static final String CALLBACK_SERVICE_LIMIT_STOP_CONFIRM = "ctsl_stop_ok|";
    private static final String CALLBACK_SERVICE_LIMIT_MUTE = "ctsl_mute|";
    private static final String CALLBACK_SERVICE_LIMIT_CANCEL = "ctsl_cancel|";

    /** 为 SmartLifecycle：仅在上下文 refresh 完成后置 true，关闭时先于 Web 优雅停机取消开机调度 */
    private volatile boolean lifecycleRunning = false;

    @PostConstruct
    public void init() {
        // 多实例时代旧数据可能出现 success>目标 仍 RUNNING；在恢复任务前先收口，避免再调度
        repairInconsistentRunningTasks();
        List<OciCreateTask> runningTaskList = taskMapper.selectList(
                new LambdaQueryWrapper<OciCreateTask>()
                        .eq(OciCreateTask::getStatus, TaskStatusEnum.RUNNING.getStatus()));

        if (!runningTaskList.isEmpty()) {
            log.info("Restoring {} running tasks from database...", runningTaskList.size());
            for (OciCreateTask task : runningTaskList) {
                try {
                    OciUser ociUser = userMapper.selectById(task.getUserId());
                    if (ociUser == null) {
                        task.setStatus(TaskStatusEnum.FAILED.getStatus());
                        task.setStatusTime(LocalDateTime.now());
                        task.setFailureReason("❌ 租户配置不存在，服务重启后无法恢复任务。");
                        taskMapper.updateById(task);
                        clearServiceLimitNotifyState(task.getId());
                        continue;
                    }
                    SysUserDTO dto = buildSysUserDTO(ociUser, task);
                    scheduleTask(task.getId(), dto, task.getIntervalSeconds());
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - 服务重启，恢复任务执行",
                            ociUser.getUsername(), ociUser.getOciRegion(), task.getArchitecture()));
                } catch (Exception e) {
                    log.error("Failed to restore task {}: {}", task.getId(), e.getMessage());
                    task.setStatus(TaskStatusEnum.FAILED.getStatus());
                    task.setStatusTime(LocalDateTime.now());
                    task.setFailureReason("❌ 服务重启后恢复任务失败：" + e.getMessage());
                    taskMapper.updateById(task);
                    clearServiceLimitNotifyState(task.getId());
                }
            }
        }
    }

    @Override
    public void start() {
        lifecycleRunning = true;
    }

    @Override
    public void stop() {
        cancelAllBootTasksForShutdown();
        lifecycleRunning = false;
    }

    @Override
    public boolean isRunning() {
        return lifecycleRunning;
    }

    /**
     * 高于 Web 优雅停机阶段，保证 SIGTERM/停服时先取消开机虚拟线程，避免 Tomcat 已停后仍发起 OCI 调用。
     */
    @Override
    public int getPhase() {
        return WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE + 1024;
    }

    /**
     * 仅中断调度循环，不修改库中 RUNNING；下次进程启动时 {@link #init()} 仍会按库恢复任务。
     */
    private void cancelAllBootTasksForShutdown() {
        if (taskMap.isEmpty()) {
            return;
        }
        int n = taskMap.size();
        for (TaskHandle handle : new ArrayList<>(taskMap.values())) {
            handle.cancel();
        }
        taskMap.clear();
        log.info("【开机任务】应用关闭，已取消 {} 个调度中的虚拟线程（库中 RUNNING 未改，重启后将恢复）", n);
    }

    public boolean hasRunningTask(String userId) {
        return taskMapper.selectCount(
                new LambdaQueryWrapper<OciCreateTask>()
                        .eq(OciCreateTask::getUserId, userId)
                        .eq(OciCreateTask::getStatus, TaskStatusEnum.RUNNING.getStatus())
        ) > 0;
    }

    public Page<Map<String, Object>> listTasks(PageParams params) {
        Page<OciCreateTask> page = new Page<>(params.getCurrent(), params.getSize());
        LambdaQueryWrapper<OciCreateTask> wrapper = new LambdaQueryWrapper<>();
        if (params.getStatus() != null && !params.getStatus().isEmpty()) {
            wrapper.eq(OciCreateTask::getStatus, params.getStatus());
        }
        if (params.getKeyword() != null && !params.getKeyword().isBlank()) {
            String kw = params.getKeyword();
            List<OciUser> matchedUsers = userMapper.selectList(
                    new LambdaQueryWrapper<OciUser>().like(OciUser::getUsername, kw));
            List<String> matchedUserIds = matchedUsers.stream().map(OciUser::getId).toList();
            wrapper.and(w -> {
                w.like(OciCreateTask::getOciRegion, kw)
                        .or().like(OciCreateTask::getArchitecture, kw)
                        .or().like(OciCreateTask::getOperationSystem, kw)
                        .or().like(OciCreateTask::getInstanceName, kw);
                if (!matchedUserIds.isEmpty()) {
                    w.or().in(OciCreateTask::getUserId, matchedUserIds);
                }
            });
        }
        wrapper.orderByDesc(OciCreateTask::getCreateTime);
        Page<OciCreateTask> result = taskMapper.selectPage(page, wrapper);

        List<String> userIds = result.getRecords().stream()
                .map(OciCreateTask::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, OciUser> usersById = userIds.isEmpty()
                ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(java.util.stream.Collectors.toMap(OciUser::getId, u -> u, (a, b) -> a));
        Page<Map<String, Object>> enriched = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        enriched.setRecords(result.getRecords().stream().map(task -> {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            OciUser user = usersById.get(task.getUserId());
            map.put("id", task.getId());
            map.put("userId", task.getUserId());
            map.put("username", user != null ? user.getUsername() : "unknown");
            map.put("ociRegion", task.getOciRegion());
            map.put("ocpus", task.getOcpus());
            map.put("memory", task.getMemory());
            map.put("disk", task.getDisk());
            map.put("vpusPerGB", BootVolumeVpusUtil.normalize(task.getVpusPerGB()));
            map.put("architecture", task.getArchitecture());
            map.put("intervalSeconds", task.getIntervalSeconds());
            map.put("createNumbers", task.getCreateNumbers());
            map.put("loginMode", normalizeLoginMode(task.getLoginMode()));
            map.put("sshPublicKey", task.getSshPublicKey());
            map.put("operationSystem", task.getOperationSystem());
            map.put("instanceName", task.getInstanceName());
            map.put("customScript", task.getCustomScript());
            map.put("assignPublicIp", task.getAssignPublicIp() != null ? task.getAssignPublicIp() : true);
            map.put("assignIpv6", task.getAssignIpv6() != null ? task.getAssignIpv6() : false);
            map.put("status", task.getStatus());
            map.put("statusTime", task.getStatusTime());
            map.put("attemptCount", task.getAttemptCount());
            int scL = task.getSuccessCount() != null ? task.getSuccessCount() : 0;
            int tgtL = task.getCreateNumbers() != null && task.getCreateNumbers() > 0 ? task.getCreateNumbers() : 1;
            map.put("successCount", scL);
            int recL = parseCreatedInstances(task.getCreatedInstances()).size();
            map.put("recordedInstanceCount", recL);
            map.put("progressOverTarget", scL > tgtL || recL > tgtL);
            map.put("createTime", task.getCreateTime());
            return map;
        }).toList());
        return enriched;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 30_000)
    public void runTaskMaintenance() {
        if (!maintenanceRunning.compareAndSet(false, true)) return;
        try {
            repairInconsistentRunningTasks();
            cleanExpiredTasks();
            long staleBefore = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10);
            rateLimitStates.entrySet().removeIf(entry -> entry.getValue().untilEpochMillis() < staleBefore);
        } catch (Exception e) {
            log.warn("开机任务后台维护失败: {}", e.getMessage());
        } finally {
            maintenanceRunning.set(false);
        }
    }

    public void createTask(String userId, String architecture, Double ocpus, Double memory,
                           Integer disk, Integer vpusPerGB, Integer createNumbers, Integer interval,
                           String rootPassword, String loginMode, String sshPublicKey,
                           String operationSystem, String instanceName, String customScript,
                           Boolean assignPublicIp, Boolean assignIpv6, String ociRegionOverride) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        String normalizedArchitecture = normalizeTaskArchitecture(architecture);
        int normalizedCreateNumbers = normalizeCreateNumbers(createNumbers);
        int normalizedInterval = normalizeTaskInterval(interval);
        TaskLoginModeEnum normalizedLoginMode = TaskLoginModeEnum.of(loginMode);
        String normalizedRootPassword = normalizedLoginMode == TaskLoginModeEnum.SSH_PUBLIC_KEY ? null : StrUtil.trimToNull(rootPassword);
        String dedupRootPassword = normalizedRootPassword;
        if (normalizedLoginMode == TaskLoginModeEnum.PASSWORD && StrUtil.isBlank(normalizedRootPassword)) {
            normalizedRootPassword = randomRootPassword();
        }
        String normalizedSshPublicKey = normalizedLoginMode == TaskLoginModeEnum.SSH_PUBLIC_KEY ? StrUtil.trimToNull(sshPublicKey) : null;
        if (normalizedLoginMode == TaskLoginModeEnum.SSH_PUBLIC_KEY && StrUtil.isBlank(normalizedSshPublicKey)) {
            throw new OciException("请先配置我的公钥");
        }

        String effectiveRegion = StrUtil.trimToNull(ociRegionOverride);
        if (effectiveRegion == null) {
            effectiveRegion = OciRegionUtil.publicRegionId(ociUser.getOciRegion());
        } else {
            effectiveRegion = OciRegionUtil.publicRegionId(effectiveRegion);
        }

        double[] normalized = ShapeFlexLimitsUtil.normalizeAndLogIfAdjusted(
                normalizedArchitecture, ocpus, memory, "创建开机任务");
        int normalizedVpusPerGB = BootVolumeVpusUtil.normalize(vpusPerGB);
        boolean normalizedAssignPublicIp = assignPublicIp != null ? assignPublicIp : true;
        boolean normalizedAssignIpv6 = assignIpv6 != null ? assignIpv6 : false;
        String normalizedInstanceName = normalizeInstanceName(instanceName, normalizedCreateNumbers);
        LocalDateTime now = LocalDateTime.now();
        String dedupKey = createTaskDedupKey(userId, effectiveRegion, normalizedArchitecture, normalized[0], normalized[1],
                disk, normalizedVpusPerGB, normalizedCreateNumbers, normalizedInterval, dedupRootPassword,
                normalizedLoginMode.name(), normalizedSshPublicKey, operationSystem, normalizedInstanceName, customScript,
                normalizedAssignPublicIp, normalizedAssignIpv6);
        if (!acquireRecentTaskCreateGuard(dedupKey, now)) {
            log.info("忽略重复开机任务创建请求：userId={} region={} shape={}（{} 秒内同参数请求）",
                    userId, effectiveRegion, normalizedArchitecture, CREATE_TASK_DEDUP_SECONDS);
            return;
        }

        boolean inserted = false;
        try {
            if (hasRecentDuplicateCreateTask(userId, effectiveRegion, normalizedArchitecture, normalized[0], normalized[1],
                    disk, normalizedVpusPerGB, normalizedCreateNumbers, normalizedInterval, normalizedRootPassword,
                    normalizedLoginMode.name(), normalizedSshPublicKey, operationSystem, normalizedInstanceName, customScript,
                    normalizedAssignPublicIp, normalizedAssignIpv6, now.minusSeconds(CREATE_TASK_DEDUP_SECONDS))) {
                log.info("忽略重复开机任务创建请求：userId={} region={} shape={}（数据库已有同参数任务）",
                        userId, effectiveRegion, normalizedArchitecture);
                return;
            }

            OciCreateTask task = new OciCreateTask();
            task.setId(CommonUtils.generateId());
            task.setUserId(userId);
            task.setOciRegion(effectiveRegion);
            task.setArchitecture(normalizedArchitecture);
            task.setOcpus(normalized[0]);
            task.setMemory(normalized[1]);
            task.setDisk(disk);
            task.setVpusPerGB(normalizedVpusPerGB);
            task.setCreateNumbers(normalizedCreateNumbers);
            task.setIntervalSeconds(normalizedInterval);
            task.setRootPassword(normalizedRootPassword);
            task.setLoginMode(normalizedLoginMode.name());
            task.setSshPublicKey(normalizedSshPublicKey);
            task.setOperationSystem(operationSystem);
            task.setInstanceName(normalizedInstanceName);
            task.setCustomScript(customScript);
            task.setAssignPublicIp(normalizedAssignPublicIp);
            task.setAssignIpv6(normalizedAssignIpv6);
            task.setStatus(TaskStatusEnum.RUNNING.getStatus());
            task.setAttemptCount(0);
            task.setSuccessCount(0);
            task.setFailureReason(null);
            task.setCreateTime(now);
            taskMapper.insert(task);
            inserted = true;

            clearTaskExcludedAds(task.getId());
            SysUserDTO dto = buildSysUserDTO(ociUser, task);
            scheduleTask(task.getId(), dto, normalizedInterval);

            String series = ShapeSeriesUtil.resolveSeries(normalizedArchitecture);
            String diskConfig = BootVolumeVpusUtil.formatDiskWithVpus(disk != null ? disk : 50, task.getVpusPerGB());
            String logMsg = String.format("【开机任务】用户:[%s],区域:[%s],架构:[%s]%s,配置:[%sC/%sGB/%s],数量:[%d] - 任务已创建",
                    ociUser.getUsername(), effectiveRegion, series, targetShapeForLog(normalizedArchitecture),
                    normalized[0], normalized[1], diskConfig, normalizedCreateNumbers);
            broadcastLog(logMsg);

            String loginLine = buildNotifyLoginLine(task);
            String html = "📋 <b>开机任务已创建</b>\n\n"
                    + "👤 <b>租户：</b>" + ociUser.getUsername() + "\n"
                    + "🌍 <b>区域：</b>" + effectiveRegion + "\n"
                    + "⚙️ <b>架构：</b>" + series + "\n"
                    + targetShapeLineForNotify(normalizedArchitecture)
                    + "📊 <b>配置：</b>" + normalized[0] + "C / " + normalized[1] + "GB / "
                    + diskConfig + "\n"
                    + "🔢 <b>数量：</b>" + normalizedCreateNumbers + "\n"
                    + loginLine;
            notificationService.sendHtmlWithType(NotificationService.TYPE_TASK_CREATE, html);
        } catch (RuntimeException e) {
            if (!inserted) {
                recentTaskCreateKeys.remove(dedupKey, now);
            }
            throw e;
        }
    }

    private boolean acquireRecentTaskCreateGuard(String key, LocalDateTime now) {
        LocalDateTime cutoff = now.minusSeconds(CREATE_TASK_DEDUP_SECONDS);
        recentTaskCreateKeys.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        while (true) {
            LocalDateTime existing = recentTaskCreateKeys.putIfAbsent(key, now);
            if (existing == null) {
                return true;
            }
            if (!existing.isBefore(cutoff)) {
                return false;
            }
            if (recentTaskCreateKeys.replace(key, existing, now)) {
                return true;
            }
        }
    }

    private boolean hasRecentDuplicateCreateTask(String userId, String region, String architecture,
                                                 double ocpus, double memory, Integer disk, Integer vpusPerGB,
                                                 Integer createNumbers, Integer interval, String rootPassword,
                                                 String loginMode, String sshPublicKey,
                                                 String operationSystem, String instanceName, String customScript,
                                                 boolean assignPublicIp, boolean assignIpv6,
                                                 LocalDateTime since) {
        LambdaQueryWrapper<OciCreateTask> wrapper = new LambdaQueryWrapper<OciCreateTask>()
                .eq(OciCreateTask::getUserId, userId)
                .eq(OciCreateTask::getOciRegion, region)
                .eq(OciCreateTask::getArchitecture, architecture)
                .eq(OciCreateTask::getOcpus, ocpus)
                .eq(OciCreateTask::getMemory, memory)
                .eq(OciCreateTask::getDisk, disk)
                .eq(OciCreateTask::getVpusPerGB, vpusPerGB)
                .eq(OciCreateTask::getCreateNumbers, createNumbers)
                .eq(OciCreateTask::getIntervalSeconds, interval)
                .eq(OciCreateTask::getOperationSystem, operationSystem)
                .eq(OciCreateTask::getAssignPublicIp, assignPublicIp)
                .eq(OciCreateTask::getAssignIpv6, assignIpv6)
                .eq(OciCreateTask::getStatus, TaskStatusEnum.RUNNING.getStatus())
                .ge(OciCreateTask::getCreateTime, since);
        if (TaskLoginModeEnum.isSshPublicKey(loginMode)) {
            wrapper.eq(OciCreateTask::getLoginMode, TaskLoginModeEnum.SSH_PUBLIC_KEY.name());
        } else {
            wrapper.and(w -> w.isNull(OciCreateTask::getLoginMode)
                    .or().eq(OciCreateTask::getLoginMode, "")
                    .or().eq(OciCreateTask::getLoginMode, TaskLoginModeEnum.PASSWORD.name()));
        }
        addNullableTextEquals(wrapper, OciCreateTask::getRootPassword, rootPassword);
        addNullableTextEquals(wrapper, OciCreateTask::getSshPublicKey, sshPublicKey);
        addNullableTextEquals(wrapper, OciCreateTask::getInstanceName, instanceName);
        addNullableTextEquals(wrapper, OciCreateTask::getCustomScript, customScript);
        return taskMapper.selectCount(wrapper) > 0;
    }

    private static void addNullableTextEquals(LambdaQueryWrapper<OciCreateTask> wrapper,
                                              com.baomidou.mybatisplus.core.toolkit.support.SFunction<OciCreateTask, String> column,
                                              String value) {
        if (StrUtil.isBlank(value)) {
            wrapper.and(w -> w.isNull(column).or().eq(column, ""));
        } else {
            wrapper.eq(column, value);
        }
    }

    private static String createTaskDedupKey(String userId, String region, String architecture,
                                             double ocpus, double memory, Integer disk, Integer vpusPerGB,
                                             Integer createNumbers, Integer interval, String rootPassword,
                                             String loginMode, String sshPublicKey,
                                             String operationSystem, String instanceName, String customScript,
                                             boolean assignPublicIp, boolean assignIpv6) {
        return String.join("\u001F",
                safeKeyPart(userId),
                safeKeyPart(region),
                safeKeyPart(architecture),
                String.format(Locale.ROOT, "%.4f", ocpus),
                String.format(Locale.ROOT, "%.4f", memory),
                String.valueOf(disk),
                String.valueOf(vpusPerGB),
                String.valueOf(createNumbers),
                String.valueOf(interval),
                String.valueOf(Objects.hashCode(rootPassword)),
                safeKeyPart(normalizeLoginMode(loginMode)),
                String.valueOf(Objects.hashCode(sshPublicKey)),
                safeKeyPart(operationSystem),
                safeKeyPart(instanceName),
                String.valueOf(Objects.hashCode(customScript)),
                String.valueOf(assignPublicIp),
                String.valueOf(assignIpv6));
    }

    private static String safeKeyPart(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeInstanceName(String value, Integer createNumbers) {
        String name = StrUtil.trimToNull(value);
        int target = createNumbers != null && createNumbers > 0 ? createNumbers : 1;
        int suffixLength = target > 1 ? 1 + String.valueOf(target).length() : 0;
        if (name != null && name.length() + suffixLength > 255) {
            throw new OciException(target > 1
                    ? "批量创建时实例名称需预留序号后缀，最多 " + (255 - suffixLength) + " 个字符"
                    : "实例名称不能超过 255 个字符");
        }
        return name;
    }

    private static int normalizeCreateNumbers(Integer value) {
        if (value == null || value < 1 || value > 500) {
            throw new OciException("开机数量必须在 1～500 之间");
        }
        return value;
    }

    private static int normalizeTaskInterval(Integer value) {
        if (value == null || value < 1 || value > 600) {
            throw new OciException("重试间隔必须在 1～600 秒之间");
        }
        return value;
    }

    private static String randomRootPassword() {
        final String chars = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$%";
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder pwd = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            pwd.append(chars.charAt(random.nextInt(chars.length())));
        }
        return pwd.toString();
    }

    private static String normalizeLoginMode(String loginMode) {
        return TaskLoginModeEnum.of(loginMode).name();
    }

    private static boolean isSshPublicKeyMode(OciCreateTask task) {
        return task != null && TaskLoginModeEnum.isSshPublicKey(task.getLoginMode());
    }

    private static void constrainExpectedStatus(UpdateWrapper<OciCreateTask> update, String expectedStatus) {
        if (expectedStatus == null) {
            update.isNull("status");
        } else {
            update.eq("status", expectedStatus);
        }
    }

    private static String buildNotifyLoginLine(OciCreateTask task) {
        if (isSshPublicKeyMode(task)) {
            return "🔐 <b>登录方式：</b>SSH 公钥（用户：root）";
        }
        String pwd = StrUtil.isNotBlank(task != null ? task.getRootPassword() : null)
                ? tgSpoiler(task.getRootPassword())
                : "随机";
        return "🔑 <b>密码：</b>" + pwd;
    }

    public void resumeTask(String taskId) {
        OciCreateTask task = taskMapper.selectById(taskId);
        if (task == null) throw new OciException("任务不存在");
        if (TaskStatusEnum.RUNNING.getStatus().equals(task.getStatus())) {
            throw new OciException("任务已在运行中");
        }
        String expectedStatus = task.getStatus();
        OciUser ociUser = userMapper.selectById(task.getUserId());
        if (ociUser == null) throw new OciException("租户配置不存在");

        task.setStatus(TaskStatusEnum.RUNNING.getStatus());
        task.setStatusTime(null);
        task.setFailureReason(null);
        UpdateWrapper<OciCreateTask> resume = new UpdateWrapper<>();
        resume.eq("id", taskId);
        constrainExpectedStatus(resume, expectedStatus);
        resume.set("status", TaskStatusEnum.RUNNING.getStatus())
                .set("status_time", null)
                .set("failure_reason", null);
        if (taskMapper.update(null, resume) == 0) {
            throw new OciException("任务状态已变化，请刷新后重试");
        }

        clearTaskExcludedAds(taskId);
        clearServiceLimitNotifyState(taskId);
        SysUserDTO dto = buildSysUserDTO(ociUser, task);
        scheduleTask(task.getId(), dto, task.getIntervalSeconds());

        broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - 任务已恢复运行",
                ociUser.getUsername(), task.getOciRegion(), task.getArchitecture()));
    }

    public void updateTask(String taskId, String architecture, Double ocpus, Double memory,
                           Integer disk, Integer vpusPerGB, Integer createNumbers, Integer interval,
                           String rootPassword, String loginMode, String sshPublicKey,
                           String operationSystem, String instanceName, String customScript,
                           Boolean assignPublicIp, Boolean assignIpv6) {
        OciCreateTask task = taskMapper.selectById(taskId);
        if (task == null) throw new OciException("任务不存在");

        String expectedStatus = task.getStatus();
        boolean wasRunning = TaskStatusEnum.RUNNING.getStatus().equals(task.getStatus());
        if (architecture != null) task.setArchitecture(normalizeTaskArchitecture(architecture));
        if (ocpus != null) task.setOcpus(ocpus);
        if (memory != null) task.setMemory(memory);
        if (disk != null) task.setDisk(disk);
        if (vpusPerGB != null) task.setVpusPerGB(BootVolumeVpusUtil.normalize(vpusPerGB));
        if (createNumbers != null) task.setCreateNumbers(normalizeCreateNumbers(createNumbers));
        if (interval != null) task.setIntervalSeconds(normalizeTaskInterval(interval));
        if (loginMode != null) {
            TaskLoginModeEnum normalizedLoginMode = TaskLoginModeEnum.of(loginMode);
            task.setLoginMode(normalizedLoginMode.name());
            if (normalizedLoginMode == TaskLoginModeEnum.SSH_PUBLIC_KEY) {
                String normalizedKey = StrUtil.trimToNull(sshPublicKey);
                if (StrUtil.isBlank(normalizedKey)) {
                    throw new OciException("请先配置我的公钥");
                }
                task.setRootPassword(null);
                task.setSshPublicKey(normalizedKey);
            } else {
                task.setSshPublicKey(null);
                if (rootPassword != null && !rootPassword.isBlank()) {
                    task.setRootPassword(rootPassword);
                }
            }
        } else if (rootPassword != null && !rootPassword.isBlank()) {
            task.setRootPassword(rootPassword);
        }
        if (operationSystem != null) task.setOperationSystem(operationSystem);
        if (instanceName != null) task.setInstanceName(StrUtil.trimToNull(instanceName));
        if (customScript != null) task.setCustomScript(customScript);
        if (assignPublicIp != null) task.setAssignPublicIp(assignPublicIp);
        if (assignIpv6 != null) task.setAssignIpv6(assignIpv6);
        task.setInstanceName(normalizeInstanceName(task.getInstanceName(), task.getCreateNumbers()));
        double[] normalized = ShapeFlexLimitsUtil.normalizeAndLogIfAdjusted(
                task.getArchitecture(), task.getOcpus(), task.getMemory(), "更新开机任务");
        task.setOcpus(normalized[0]);
        task.setMemory(normalized[1]);
        if (wasRunning) {
            task.setFailureReason(null);
        }
        UpdateWrapper<OciCreateTask> update = new UpdateWrapper<>();
        update.eq("id", taskId);
        constrainExpectedStatus(update, expectedStatus);
        update.set("architecture", task.getArchitecture())
                .set("ocpus", task.getOcpus())
                .set("memory", task.getMemory())
                .set("disk", task.getDisk())
                .set("vpus_per_gb", task.getVpusPerGB())
                .set("create_numbers", task.getCreateNumbers())
                .set("interval_seconds", task.getIntervalSeconds())
                .set("root_password", task.getRootPassword())
                .set("login_mode", task.getLoginMode())
                .set("ssh_public_key", task.getSshPublicKey())
                .set("operation_system", task.getOperationSystem())
                .set("instance_name", task.getInstanceName())
                .set("custom_script", task.getCustomScript())
                .set("assign_public_ip", task.getAssignPublicIp())
                .set("assign_ipv6", task.getAssignIpv6());
        if (wasRunning) {
            update.set("failure_reason", null);
        }
        if (taskMapper.update(null, update) == 0) {
            throw new OciException("任务状态已变化，请刷新后重试");
        }

        clearTaskExcludedAds(taskId);
        clearServiceLimitNotifyState(taskId);
        if (wasRunning) {
            OciUser ociUser = userMapper.selectById(task.getUserId());
            if (ociUser != null) {
                SysUserDTO dto = buildSysUserDTO(ociUser, task);
                scheduleTask(task.getId(), dto, task.getIntervalSeconds());
            }
        }

        OciUser user = userMapper.selectById(task.getUserId());
        String name = user != null ? user.getUsername() : "unknown";
        broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s] - 任务已编辑%s",
                name, task.getOciRegion(), wasRunning ? "（自动重启调度）" : ""));
    }

    public void deleteTask(String taskId) {
        taskMapper.deleteById(taskId);
        cancelTaskSchedule(taskId);
        clearTaskExcludedAds(taskId);
        clearServiceLimitNotifyState(taskId);
    }

    public void stopTask(String taskId) {
        OciCreateTask task = taskMapper.selectById(taskId);
        if (task == null) {
            cancelTaskSchedule(taskId);
            clearTaskExcludedAds(taskId);
            clearServiceLimitNotifyState(taskId);
            return;
        }
        if (!TaskStatusEnum.RUNNING.getStatus().equals(task.getStatus())) {
            cancelTaskSchedule(taskId);
            clearTaskExcludedAds(taskId);
            clearServiceLimitNotifyState(taskId);
            return;
        }
        UpdateWrapper<OciCreateTask> stop = new UpdateWrapper<>();
        stop.eq("id", taskId)
                .eq("status", TaskStatusEnum.RUNNING.getStatus())
                .set("status", TaskStatusEnum.STOPPED.getStatus())
                .set("status_time", LocalDateTime.now());
        int updated = taskMapper.update(null, stop);
        cancelTaskSchedule(taskId);
        if (updated > 0) {
            OciUser user = userMapper.selectById(task.getUserId());
            String name = user != null ? user.getUsername() : "unknown";
            broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s] - 任务已手动停止",
                    name, task.getOciRegion()));
        }
        clearTaskExcludedAds(taskId);
        clearServiceLimitNotifyState(taskId);
    }

    public boolean tryHandleTelegramCallback(String rawData, String callbackQueryId, String answeringBotToken) {
        if (StrUtil.isBlank(rawData) || !rawData.startsWith("ctsl_")) {
            return false;
        }
        try {
            if (rawData.startsWith(CALLBACK_SERVICE_LIMIT_STOP_REQUEST)) {
                String taskId = callbackTaskId(rawData, CALLBACK_SERVICE_LIMIT_STOP_REQUEST);
                if (taskId == null) {
                    answerTaskCallback(callbackQueryId, "无效任务", false, answeringBotToken);
                    return true;
                }
                OciCreateTask task = taskMapper.selectById(taskId);
                if (task == null) {
                    answerTaskCallback(callbackQueryId, "任务不存在", false, answeringBotToken);
                    return true;
                }
                if (!TaskStatusEnum.RUNNING.getStatus().equals(task.getStatus())) {
                    answerTaskCallback(callbackQueryId, "任务当前不是运行中", false, answeringBotToken);
                    return true;
                }
                notificationService.sendHtmlWithTypeAndInlineKeyboard(
                        NotificationService.TYPE_TASK_RESULT,
                        "<b>确认停止开机任务？</b>\n\n任务ID: <code>" + html(taskId) + "</code>\n"
                                + "停止后任务不会继续自动重试。",
                        List.of(List.of(
                                Map.of("text", "确认停止", "callback_data", CALLBACK_SERVICE_LIMIT_STOP_CONFIRM + taskId),
                                Map.of("text", "取消", "callback_data", CALLBACK_SERVICE_LIMIT_CANCEL + taskId))));
                answerTaskCallback(callbackQueryId, "请在新消息中确认是否停止任务", false, answeringBotToken);
                return true;
            }
            if (rawData.startsWith(CALLBACK_SERVICE_LIMIT_STOP_CONFIRM)) {
                String taskId = callbackTaskId(rawData, CALLBACK_SERVICE_LIMIT_STOP_CONFIRM);
                if (taskId == null) {
                    answerTaskCallback(callbackQueryId, "无效任务", false, answeringBotToken);
                    return true;
                }
                OciCreateTask task = taskMapper.selectById(taskId);
                if (task == null) {
                    answerTaskCallback(callbackQueryId, "任务不存在", false, answeringBotToken);
                    return true;
                }
                if (!TaskStatusEnum.RUNNING.getStatus().equals(task.getStatus())) {
                    answerTaskCallback(callbackQueryId, "任务当前不是运行中", false, answeringBotToken);
                    return true;
                }
                stopTask(taskId);
                answerTaskCallback(callbackQueryId, "已停止开机任务", false, answeringBotToken);
                return true;
            }
            if (rawData.startsWith(CALLBACK_SERVICE_LIMIT_MUTE)) {
                String taskId = callbackTaskId(rawData, CALLBACK_SERVICE_LIMIT_MUTE);
                if (taskId == null) {
                    answerTaskCallback(callbackQueryId, "无效任务", false, answeringBotToken);
                    return true;
                }
                persistServiceLimitNotifyMute(taskId);
                answerTaskCallback(callbackQueryId, "已对当前任务关闭服务限制提醒", false, answeringBotToken);
                return true;
            }
            if (rawData.startsWith(CALLBACK_SERVICE_LIMIT_CANCEL)) {
                answerTaskCallback(callbackQueryId, "已取消", false, answeringBotToken);
                return true;
            }
            answerTaskCallback(callbackQueryId, "未知操作", false, answeringBotToken);
            return true;
        } catch (Exception e) {
            log.warn("开机任务 TG 回调处理失败: {}", e.getMessage());
            answerTaskCallback(callbackQueryId, "执行失败", true, answeringBotToken);
            return true;
        }
    }

    private static String callbackTaskId(String rawData, String prefix) {
        String taskId = rawData.substring(prefix.length()).trim();
        if (taskId.isEmpty() || taskId.length() > 64) {
            return null;
        }
        return taskId;
    }

    private void clearTaskExcludedAds(String taskId) {
        if (taskId != null) {
            taskExcludedAds.remove(taskId);
        }
    }

    private void clearServiceLimitNotifyState(String taskId) {
        if (taskId != null) {
            serviceLimitNotifyTimes.remove(taskId);
            serviceLimitNotifyMutedTasks.remove(taskId);
            kvMapper.delete(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getCode, taskId)
                    .eq(OciKv::getType, SERVICE_LIMIT_MUTE_KV_TYPE));
        }
    }

    /**
     * 每任务独立虚拟线程循环：<strong>首次无延迟</strong>立即跑 {@link #executeCreate}，一次尝试完全结束后
     * 再 {@link Thread#sleep} 间隔秒，再下一次。避免 {@link java.util.concurrent.ScheduledThreadPoolExecutor} 仅数个工作线程
     * 与 {@code scheduleWithFixedDelay} 排队导致「建任务后先空等一个间隔才第一次开机」。
     */
    private void scheduleTask(String taskId, SysUserDTO dto, int intervalSeconds) {
        int delaySec = Math.max(1, intervalSeconds);
        TaskHandle handle = new TaskHandle();
        TaskHandle previous = taskMap.put(taskId, handle);
        if (previous != null) {
            previous.cancel();
        }
        try {
            Future<?> future = VIRTUAL_EXECUTOR.submit(() -> {
                handle.attachRunner(Thread.currentThread());
                try {
                    if (!handle.isCancelled()) {
                        runTaskLoop(taskId, dto, delaySec, handle);
                    }
                } finally {
                    handle.detachRunner();
                    taskMap.remove(taskId, handle);
                }
            });
            handle.attachFuture(future);
        } catch (RuntimeException e) {
            taskMap.remove(taskId, handle);
            handle.cancel();
            throw e;
        }
    }

    private void cancelTaskSchedule(String taskId) {
        TaskHandle handle = taskMap.remove(taskId);
        if (handle != null) {
            handle.cancel();
        }
    }

    private void runTaskLoop(String taskId, SysUserDTO dto, int delaySec) {
        runTaskLoop(taskId, dto, delaySec, null);
    }

    private void runTaskLoop(String taskId, SysUserDTO dto, int delaySec, TaskHandle handle) {
        String rateLimitLane = rateLimitLane(dto);
        while (!Thread.currentThread().isInterrupted() && !isSuperseded(taskId, handle)) {
            try {
                RateLimitState observedRateLimitWindow = waitForRateLimitWindow(rateLimitLane);
                AttemptOutcome outcome;
                try (AdaptiveLaunchConcurrency.Permit ignored = adaptiveLaunchConcurrency.acquire(rateLimitLane)) {
                    // The permit must cover the database preflight as well as the OCI request. If all task loops
                    // wake at the same interval, querying before the gate can consume the whole JDBC pool and
                    // starve panel requests even though the actual LaunchInstance calls are limited.
                    OciCreateTask task = taskMapper.selectById(taskId);
                    if (task == null || !TaskStatusEnum.RUNNING.getStatus().equals(task.getStatus())) {
                        break;
                    }
                    outcome = executeCreate(taskId, dto, delaySec, task, handle);
                }
                if (outcome == AttemptOutcome.TERMINAL) {
                    break;
                }
                int sleepSeconds = outcome == AttemptOutcome.RATE_LIMITED
                        ? registerRateLimit(rateLimitLane)
                        : delaySec;
                if (outcome != AttemptOutcome.RATE_LIMITED && observedRateLimitWindow != null) {
                    clearRateLimit(rateLimitLane, observedRateLimitWindow);
                }
                try {
                    Thread.sleep(sleepSeconds * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                int retrySeconds = Math.min(5, delaySec);
                log.warn("Boot task {} preflight failed; retrying in {}s: {}",
                        taskId, retrySeconds, e.getMessage());
                try {
                    Thread.sleep(retrySeconds * 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private boolean isSuperseded(String taskId, TaskHandle handle) {
        return handle != null && (handle.isCancelled() || taskMap.get(taskId) != handle);
    }

    private AttemptOutcome executeCreate(String taskId, SysUserDTO dto, int intervalSeconds,
                                         OciCreateTask head, TaskHandle handle) {
        if (isSuperseded(taskId, handle)) return AttemptOutcome.TERMINAL;
        if (!runningTasks.add(taskId)) return AttemptOutcome.NORMAL;
        String user = "";
        String region = "";
        String arch = "";
        try {
            // 多进程/多实例、或本机并发时，以库里的 success_count 为准，达目标则不再开新实例
            int headTarget = head.getCreateNumbers() != null && head.getCreateNumbers() > 0
                    ? head.getCreateNumbers() : 1;
            int headSc = head.getSuccessCount() != null ? head.getSuccessCount() : 0;
            if (headSc >= headTarget) {
                if (!isSuperseded(taskId, handle)
                        && TaskStatusEnum.RUNNING.getStatus().equals(head.getStatus())) {
                    completeTask(taskId, TaskStatusEnum.COMPLETED);
                }
                return AttemptOutcome.TERMINAL;
            }
            Double storedOcpus = head.getOcpus();
            Double storedMemory = head.getMemory();
            double[] launchNorm = ShapeFlexLimitsUtil.normalizeAndLogIfAdjusted(
                    head.getArchitecture(), storedOcpus, storedMemory, "执行开机任务");
            if (!Objects.equals(storedOcpus, launchNorm[0])
                    || !Objects.equals(storedMemory, launchNorm[1])) {
                head.setOcpus(launchNorm[0]);
                head.setMemory(launchNorm[1]);
                UpdateWrapper<OciCreateTask> normalize = new UpdateWrapper<>();
                normalize.eq("id", taskId)
                        .eq("status", TaskStatusEnum.RUNNING.getStatus());
                if (storedOcpus == null) normalize.isNull("ocpus");
                else normalize.eq("ocpus", storedOcpus);
                if (storedMemory == null) normalize.isNull("memory");
                else normalize.eq("memory", storedMemory);
                normalize.set("ocpus", launchNorm[0])
                        .set("memory", launchNorm[1]);
                taskMapper.update(null, normalize);
            }
            dto.setOcpus(launchNorm[0]);
            dto.setMemory(launchNorm[1]);
            dto.setDisk(head.getDisk());
            dto.setVpusPerGB(BootVolumeVpusUtil.normalize(head.getVpusPerGB()));
            user = dto.getUsername();
            region = dto.getOciCfg().getRegion();
            arch = dto.getArchitecture();
            String series = ShapeSeriesUtil.resolveSeries(arch);
            if (isSuperseded(taskId, handle)) return AttemptOutcome.TERMINAL;
            int attempt = incrementAttempt(taskId);
            if (attempt < 0) return AttemptOutcome.TERMINAL;
            broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s],开机数量:[%d],开始执行第 [%d] 次创建实例操作...",
                    user, region, series, dto.getCreateNumbers(), attempt));

            dto.setInstanceDisplayOrdinal(headSc + 1);
            Set<String> excludedAds = taskExcludedAds.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet());
            dto.setExcludedAvailabilityDomains(new HashSet<>(excludedAds));
            InstanceDetailDTO result;
            try (OciClientService client = new OciClientService(dto)) {
                if (Thread.currentThread().isInterrupted()) {
                    return AttemptOutcome.TERMINAL;
                }
                result = client.createInstanceData();
            }

            if (isSuperseded(taskId, handle) && !result.isSuccess()) {
                return AttemptOutcome.TERMINAL;
            }

            try {
                applyAdExcludedNoShapeBroadcast(taskId, user, region, arch, result, excludedAds);

                if (result.isDie()) {
                    String failureReason = "❌ 认证失败 (401)，任务已停止。请检查该租户 API Key、Fingerprint、私钥和权限是否仍有效。";
                    completeTask(taskId, TaskStatusEnum.FAILED, failureReason);
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],架构:[%s] - 认证失败(401)，任务已停止", user, region, series));
                    String html = "❌ <b>开机任务失败</b>\n\n"
                            + "👤 <b>租户：</b>" + user + "\n"
                            + "🌍 <b>区域：</b>" + region + "\n"
                            + "⚙️ <b>架构：</b>" + series + "\n"
                            + targetShapeLineForNotify(arch)
                            + "📛 <b>原因：</b>认证失败 (401)，任务已停止";
                    sendTaskNotificationAsync(NotificationService.TYPE_TASK_RESULT, html);
                    return AttemptOutcome.TERMINAL;
                }

                if (result.isRateLimited()) {
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],架构:[%s] - OCI 请求被限流(429)，将按租户/区域独立退避",
                            user, region, series));
                    return AttemptOutcome.RATE_LIMITED;
                }

                if (result.isNoShape()) {
                    String shapeForReason = StrUtil.isNotBlank(result.getResolvedTargetShape())
                            ? result.getResolvedTargetShape() : arch;
                    String failureReason = "❌ Shape 不可用，任务已停止。目标 Shape：" + shapeForReason
                            + "；请切换区域、Shape 或稍后重试。";
                    completeTask(taskId, TaskStatusEnum.FAILED, failureReason);
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - %s", user, region, arch, failureReason));
                    return AttemptOutcome.TERMINAL;
                }

                if (result.isBootVolumeQuotaExceeded()) {
                    String hint = StrUtil.isNotBlank(result.getFailureHint())
                            ? result.getFailureHint()
                            : "引导卷（启动盘）存储配额已达上限，硬盘配额用尽，创建失败";
                    String failureReason = "❌ " + hint + "。任务已停止。";
                    completeTask(taskId, TaskStatusEnum.FAILED, failureReason);
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - %s",
                            user, region, arch, failureReason));
                    return AttemptOutcome.TERMINAL;
                }

                if (result.isUnrecoverableLaunchFailure()) {
                    String hint = StrUtil.isNotBlank(result.getFailureHint())
                            ? result.getFailureHint()
                            : "账户或配额限制导致实例无法创建";
                    String stopReason = hint + "。任务已停止。";
                    String failureReason = "❌ " + stopReason;
                    completeTask(taskId, TaskStatusEnum.FAILED, failureReason);
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - %s",
                            user, region, arch, failureReason));
                    String shapeForNotify = StrUtil.isNotBlank(result.getResolvedTargetShape())
                            ? result.getResolvedTargetShape() : arch;
                    String html = "❌ <b>开机任务失败</b>\n\n"
                            + "👤 <b>租户：</b>" + user + "\n"
                            + "🌍 <b>区域：</b>" + region + "\n"
                            + "⚙️ <b>架构：</b>" + series + "\n"
                            + targetShapeLineForNotify(shapeForNotify)
                            + "📛 <b>原因：</b>" + stopReason;
                    sendTaskNotificationAsync(NotificationService.TYPE_TASK_RESULT, html);
                    return AttemptOutcome.TERMINAL;
                }

                if (result.isOutOfCapacity()) {
                    if (result.isOciServiceLimitExceeded()) {
                        scheduleOciServiceLimitNotification(
                                taskId, head, user, region, series, arch, result, intervalSeconds);
                    }
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - 各可用域容量不足，[%d]秒后将重试...",
                            user, region, arch, intervalSeconds));
                    return AttemptOutcome.NORMAL;
                }

                if (result.isAllAdsExcludedNoShape()) {
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - 各可用域均无此 Shape，[%d]秒后将重试...",
                            user, region, arch, intervalSeconds));
                    return AttemptOutcome.NORMAL;
                }

                if (result.isNoPubVcn()) {
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - 未找到可用公有子网，正在尝试创建...",
                            user, region, arch));
                    return AttemptOutcome.NORMAL;
                }

                if (result.isSuccess()) {
                    // 分配成功序号必须与读取当前计数在同一行锁事务内完成。
                    // 不能再“自增后普通查询”，否则并发回调都会读到最后的 2/2。
                    TaskSuccessAllocationService.Allocation allocation =
                            taskSuccessAllocationService.allocate(taskId);
                    OciCreateTask t = allocation.task();
                    int targetCount = allocation.targetCount();
                    int successCount = allocation.successCount();
                    boolean taskStillRunning = allocation.taskStillRunning();
                    if (allocation.allocated()) {
                        appendCreatedInstance(taskId, result);
                        String shapeName = StrUtil.isNotBlank(result.getShape()) ? result.getShape() : arch;
                        String successSeries = ShapeSeriesUtil.resolveSeries(shapeName);
                        String resultDiskConfig = BootVolumeVpusUtil.formatDiskWithVpus(
                                result.getDisk() != null ? result.getDisk() : (dto.getDisk() != null ? dto.getDisk() : 50),
                                result.getVpusPerGB() != null ? result.getVpusPerGB() : BootVolumeVpusUtil.normalize(dto.getVpusPerGB()));
                        broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],架构:[%s],Shape:[%s],配置:[%sC/%sGB/%s] - 实例创建成功(%d/%d)！IP:%s%s",
                                user, region, successSeries, shapeName, result.getOcpus(), result.getMemory(), resultDiskConfig,
                                successCount, targetCount, result.getPublicIp(),
                                StrUtil.isNotBlank(result.getIpv6Address()) ? " IPv6:" + result.getIpv6Address() : ""));
                        String html = "🎉 <b>实例创建成功！</b>（" + successCount + "/" + targetCount + "）\n\n"
                                + "👤 <b>租户：</b>" + user + "\n"
                                + "🌍 <b>区域：</b>" + region + "\n"
                                + "⚙️ <b>架构：</b>" + successSeries + "\n"
                                + "💻 <b>Shape：</b><code>" + shapeName + "</code>\n"
                                + "📊 <b>配置：</b>" + result.getOcpus() + "C / " + result.getMemory() + "GB / " + resultDiskConfig + "\n"
                                + "🌐 <b>公网IP：</b><code>" + result.getPublicIp() + "</code>\n"
                                + (StrUtil.isNotBlank(result.getIpv6Address())
                                ? "🌐 <b>IPv6：</b><code>" + result.getIpv6Address() + "</code>\n" : "")
                                + buildNotifyLoginLine(t != null ? t : head);
                        sendTaskSuccessNotificationAsync(taskId, allocation.ordinal(), targetCount,
                                NotificationService.TYPE_TASK_RESULT, html);
                    } else {
                        // OCI 已建出实例，但行级更新因已达目标/并发被跳过
                        broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],架构:[%s] - 实例已创建(计次未增加) IP:%s（已达目标或并发争用，请在控制台核对实例）",
                                user, region, arch, result.getPublicIp()));
                    }
                    if (!taskStillRunning) {
                        return AttemptOutcome.TERMINAL;
                    }
                    if (successCount >= targetCount) {
                        completeTask(taskId, TaskStatusEnum.COMPLETED);
                        if (successCount > targetCount) {
                            broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],架构:[%s] - 任务已结束。⚠ 成功数(%d) 已超过目标(%d) 台，多开的实例可能产生费用，请至 OCI 与实例页核对。",
                                    user, region, arch, successCount, targetCount));
                        } else {
                            broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],架构:[%s] - 已达到目标数量(%d台)，任务完成！",
                                    user, region, arch, targetCount));
                        }
                        return AttemptOutcome.TERMINAL;
                    } else {
                        int need = Math.max(0, targetCount - successCount);
                        broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],架构:[%s] - 还需创建 %d 台，[%d]秒后继续...",
                                user, region, arch, need, intervalSeconds));
                    }
                } else {
                    String hint = StrUtil.isNotBlank(result.getFailureHint())
                            ? result.getFailureHint()
                            : "创建未成功";
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - %s，[%d]秒后将重试...",
                            user, region, arch, hint, intervalSeconds));
                }
                return AttemptOutcome.NORMAL;
            } catch (Exception e) {
                if (isSuperseded(taskId, handle)) {
                    return AttemptOutcome.TERMINAL;
                }
                String hint = OciClientService.describeThrowableFailure(e);
                broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - %s，[%d]秒后将重试...",
                        user, region, arch, hint, intervalSeconds));
                return OciClientService.isRateLimited(e)
                        ? AttemptOutcome.RATE_LIMITED : AttemptOutcome.NORMAL;
            }
        } catch (Exception e) {
            if (isSuperseded(taskId, handle)) {
                return AttemptOutcome.TERMINAL;
            }
            if (OciBmcErrorTranslator.isAuthenticationFailure(e)) {
                String series = ShapeSeriesUtil.resolveSeries(arch);
                String failureReason = "❌ 认证失败 (401)，任务已停止。请检查该租户 API Key、Fingerprint、私钥和权限是否仍有效。";
                completeTask(taskId, TaskStatusEnum.FAILED, failureReason);
                broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],架构:[%s] - 认证失败(401)，任务已停止",
                        user, region, series));
                String html = "❌ <b>开机任务失败</b>\n\n"
                        + "👤 <b>租户：</b>" + user + "\n"
                        + "🌍 <b>区域：</b>" + region + "\n"
                        + "⚙️ <b>架构：</b>" + series + "\n"
                        + targetShapeLineForNotify(arch)
                        + "📛 <b>原因：</b>认证失败 (401)，任务已停止";
                sendTaskNotificationAsync(NotificationService.TYPE_TASK_RESULT, html);
                return AttemptOutcome.TERMINAL;
            }
            String hint = OciClientService.describeThrowableFailure(e);
            broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - %s，[%d]秒后将重试...",
                    user, region, arch, hint, intervalSeconds));
            return OciClientService.isRateLimited(e)
                    ? AttemptOutcome.RATE_LIMITED : AttemptOutcome.NORMAL;
        } finally {
            runningTasks.remove(taskId);
        }
    }

    private static String rateLimitLane(SysUserDTO dto) {
        String tenantId = dto != null && dto.getOciCfg() != null && dto.getOciCfg().getTenantId() != null
                ? dto.getOciCfg().getTenantId() : "unknown";
        String region = dto != null && dto.getOciCfg() != null && dto.getOciCfg().getRegion() != null
                ? dto.getOciCfg().getRegion() : "unknown";
        return tenantId + "|" + region;
    }

    private RateLimitState waitForRateLimitWindow(String lane) throws InterruptedException {
        RateLimitState state = rateLimitStates.get(lane);
        if (state == null) return null;
        long waitMillis = state.untilEpochMillis() - System.currentTimeMillis();
        if (waitMillis > 0) {
            Thread.sleep(waitMillis);
        }
        return state;
    }

    private int registerRateLimit(String lane) {
        RateLimitState state = rateLimitStates.compute(lane, (key, current) -> {
            long now = System.currentTimeMillis();
            if (current != null && current.untilEpochMillis() > now) {
                return current;
            }
            int failures = current == null ? 1 : Math.min(6, current.consecutiveFailures() + 1);
            int delaySeconds = Math.min(60, 3 * (1 << Math.min(5, failures - 1)));
            return new RateLimitState(failures, now + delaySeconds * 1000L, delaySeconds);
        });
        return state.delaySeconds();
    }

    private void clearRateLimit(String lane, RateLimitState observed) {
        rateLimitStates.remove(lane, observed);
    }

    private enum AttemptOutcome {
        NORMAL,
        RATE_LIMITED,
        TERMINAL
    }

    private record RateLimitState(int consecutiveFailures, long untilEpochMillis, int delaySeconds) {
    }

    private int incrementAttempt(String taskId) {
        UpdateWrapper<OciCreateTask> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", taskId)
                .eq("status", TaskStatusEnum.RUNNING.getStatus())
                .setSql("attempt_count = COALESCE(attempt_count, 0) + 1");
        if (taskMapper.update(null, wrapper) == 0) {
            return -1;
        }
        OciCreateTask task = taskMapper.selectById(taskId);
        return task != null && task.getAttemptCount() != null ? task.getAttemptCount() : 0;
    }

    private synchronized void appendCreatedInstance(String taskId, InstanceDetailDTO result) {
        try {
            OciCreateTask task = taskMapper.selectById(taskId);
            if (task == null) return;

            List<Map<String, Object>> list = parseCreatedInstances(task.getCreatedInstances());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instanceId", result.getInstanceId());
            item.put("instanceName", result.getInstanceName());
            item.put("shape", result.getShape());
            item.put("ocpus", result.getOcpus());
            item.put("memory", result.getMemory());
            item.put("disk", result.getDisk());
            item.put("vpusPerGB", result.getVpusPerGB());
            item.put("publicIp", result.getPublicIp());
            item.put("privateIp", result.getPrivateIp());
            item.put("loginMode", normalizeLoginMode(task.getLoginMode()));
            item.put("loginUser", "root");
            if (StrUtil.isNotBlank(result.getIpv6Address())) {
                item.put("ipv6Address", result.getIpv6Address());
            }
            item.put("image", result.getImage());
            item.put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            list.add(item);

            UpdateWrapper<OciCreateTask> wrapper = new UpdateWrapper<>();
            wrapper.eq("id", taskId).set("created_instances", JSON.writeValueAsString(list));
            taskMapper.update(null, wrapper);
        } catch (Exception e) {
            log.warn("Failed to append created instance record for task {}: {}", taskId, e.getMessage());
        }
    }

    private List<Map<String, Object>> parseCreatedInstances(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse created_instances: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public Map<String, Object> getTaskDetail(String taskId) {
        OciCreateTask task = taskMapper.selectById(taskId);
        if (task == null) throw new OciException("任务不存在");
        OciUser user = userMapper.selectById(task.getUserId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", task.getId());
        data.put("userId", task.getUserId());
        data.put("username", user != null ? user.getUsername() : "unknown");
        data.put("ociRegion", task.getOciRegion());
        data.put("architecture", task.getArchitecture());
        data.put("ocpus", task.getOcpus());
        data.put("memory", task.getMemory());
        data.put("disk", task.getDisk());
        data.put("vpusPerGB", BootVolumeVpusUtil.normalize(task.getVpusPerGB()));
        data.put("createNumbers", task.getCreateNumbers());
        data.put("operationSystem", task.getOperationSystem());
        data.put("customScript", task.getCustomScript());
        data.put("assignPublicIp", task.getAssignPublicIp() != null ? task.getAssignPublicIp() : true);
        data.put("assignIpv6", task.getAssignIpv6() != null ? task.getAssignIpv6() : false);
        data.put("loginMode", normalizeLoginMode(task.getLoginMode()));
        data.put("sshPublicKey", task.getSshPublicKey());
        data.put("status", task.getStatus());
        data.put("statusTime", task.getStatusTime());
        data.put("attemptCount", task.getAttemptCount());
        int scD = task.getSuccessCount() != null ? task.getSuccessCount() : 0;
        int tgtD = task.getCreateNumbers() != null && task.getCreateNumbers() > 0 ? task.getCreateNumbers() : 1;
        data.put("successCount", scD);
        List<Map<String, Object>> inst = parseCreatedInstances(task.getCreatedInstances());
        int recD = inst.size();
        data.put("recordedInstanceCount", recD);
        data.put("progressOverTarget", scD > tgtD || recD > tgtD);
        data.put("createTime", task.getCreateTime());
        data.put("failureReason", task.getFailureReason());
        data.put("rootPassword", task.getRootPassword());
        data.put("instances", inst);
        return data;
    }

    private void completeTask(String taskId, TaskStatusEnum status) {
        completeTask(taskId, status, null);
    }

    private void completeTask(String taskId, TaskStatusEnum status, String failureReason) {
        UpdateWrapper<OciCreateTask> update = new UpdateWrapper<>();
        update.eq("id", taskId)
                .eq("status", TaskStatusEnum.RUNNING.getStatus())
                .set("status", status.getStatus())
                .set("status_time", LocalDateTime.now())
                .set("failure_reason", failureReason);
        taskMapper.update(null, update);
        cancelTaskSchedule(taskId);
        clearTaskExcludedAds(taskId);
        clearServiceLimitNotifyState(taskId);
    }

    private void sendTaskNotificationAsync(String type, String html) {
        submitTaskNotification(() -> notificationService.sendHtmlWithType(type, html));
    }

    /**
     * 成功消息使用任务内的序号队列。OCI 回调完成顺序可能与数据库分配顺序不同，
     * 因此不能直接把两个 send 操作提交给虚拟线程池。
     */
    private void sendTaskSuccessNotificationAsync(String taskId, int ordinal, int targetCount,
                                                   String type, String html) {
        if (StrUtil.isBlank(taskId) || ordinal <= 0) {
            sendTaskNotificationAsync(type, html);
            return;
        }
        OrderedSuccessNotificationQueue queue = successNotificationQueues.computeIfAbsent(
                taskId, OrderedSuccessNotificationQueue::new);
        queue.enqueue(ordinal, targetCount, () -> notificationService.sendHtmlWithType(type, html));
    }

    private void scheduleOciServiceLimitNotification(
            String taskId, OciCreateTask task, String username, String region,
            String series, String architecture, InstanceDetailDTO result, int intervalSeconds) {
        submitTaskNotification(() -> notifyOciServiceLimitIfNeeded(
                taskId, task, username, region, series, architecture, result, intervalSeconds));
    }

    private void submitTaskNotification(Runnable action) {
        try {
            VIRTUAL_EXECUTOR.submit(() -> {
                try {
                    action.run();
                } catch (Exception e) {
                    log.warn("Task notification failed: {}", e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            log.debug("Task notification skipped during shutdown");
        }
    }

    /**
     * A small per-task ordered dispatcher.  It is intentionally in-memory: the
     * authoritative ordinal is allocated in MySQL, while this queue only prevents
     * same-process messages from overtaking one another or hammering Telegram in
     * parallel.  A short gap watchdog handles a process/thread failure without
     * blocking later messages forever.
     */
    private final class OrderedSuccessNotificationQueue {
        private final String taskId;
        private final Object monitor = new Object();
        private final TreeMap<Integer, Runnable> pending = new TreeMap<>();
        private int nextOrdinal = 1;
        private int targetCount = 1;
        private boolean inFlight;
        private int inFlightOrdinal;
        private boolean gapWatchdogScheduled;

        private OrderedSuccessNotificationQueue(String taskId) {
            this.taskId = taskId;
        }

        private void enqueue(int ordinal, int target, Runnable action) {
            if (ordinal <= 0 || action == null) {
                return;
            }
            synchronized (monitor) {
                targetCount = Math.max(targetCount, Math.max(1, target));
                // A duplicate callback must never send the same ordinal twice.
                if (ordinal < nextOrdinal || ordinal == inFlightOrdinal || pending.containsKey(ordinal)) {
                    return;
                }
                pending.put(ordinal, action);
            }
            dispatchNext();
        }

        private void dispatchNext() {
            Runnable action;
            int ordinal = 0;
            boolean scheduleGapWatchdog = false;
            synchronized (monitor) {
                if (inFlight) {
                    return;
                }
                action = pending.remove(nextOrdinal);
                if (action == null) {
                    if (!pending.isEmpty() && !gapWatchdogScheduled) {
                        gapWatchdogScheduled = true;
                        scheduleGapWatchdog = true;
                    }
                    // The missing ordinal may still be completing on another worker;
                    // let the watchdog decide whether to advance the gap.
                } else {
                    ordinal = nextOrdinal;
                    inFlight = true;
                    inFlightOrdinal = ordinal;
                }
            }

            if (scheduleGapWatchdog) {
                scheduleGapWatchdog();
                return;
            }
            if (action == null) {
                return;
            }
            submit(ordinal, action);
        }

        private void scheduleGapWatchdog() {
            try {
                VIRTUAL_EXECUTOR.submit(() -> {
                    try {
                        Thread.sleep(SUCCESS_NOTIFICATION_GAP_WAIT_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    boolean advanced = false;
                    synchronized (monitor) {
                        gapWatchdogScheduled = false;
                        if (!inFlight && !pending.isEmpty() && !pending.containsKey(nextOrdinal)) {
                            int firstPending = pending.firstKey();
                            log.warn("Task {} success notification ordinal {} did not arrive; "
                                            + "sending pending ordinal {} after {} ms",
                                    taskId, nextOrdinal, firstPending,
                                    SUCCESS_NOTIFICATION_GAP_WAIT_MILLIS);
                            nextOrdinal = firstPending;
                            advanced = true;
                        }
                    }
                    if (advanced) {
                        dispatchNext();
                    }
                });
            } catch (RejectedExecutionException e) {
                synchronized (monitor) {
                    gapWatchdogScheduled = false;
                }
                log.debug("Success notification gap watchdog skipped during shutdown");
            }
        }

        private void submit(int ordinal, Runnable action) {
            try {
                VIRTUAL_EXECUTOR.submit(() -> {
                    try {
                        action.run();
                    } catch (Exception e) {
                        log.warn("Task {} success notification {} failed: {}", taskId, ordinal, e.getMessage());
                    } finally {
                        complete(ordinal);
                    }
                });
            } catch (RejectedExecutionException e) {
                log.debug("Task {} success notification {} skipped during shutdown", taskId, ordinal);
                complete(ordinal);
            }
        }

        private void complete(int ordinal) {
            boolean remove;
            synchronized (monitor) {
                inFlight = false;
                inFlightOrdinal = 0;
                nextOrdinal = Math.max(nextOrdinal, ordinal + 1);
                remove = ordinal >= targetCount && pending.isEmpty();
            }
            if (remove) {
                successNotificationQueues.remove(taskId, this);
            } else {
                dispatchNext();
            }
        }
    }

    private void notifyOciServiceLimitIfNeeded(String taskId, OciCreateTask task, String username, String region,
                                               String series, String arch, InstanceDetailDTO result,
                                               int intervalSeconds) {
        if (StrUtil.isBlank(taskId) || isServiceLimitNotifyMuted(taskId)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime last = serviceLimitNotifyTimes.get(taskId);
        if (last != null && last.plusMinutes(SERVICE_LIMIT_NOTIFY_COOLDOWN_MINUTES).isAfter(now)) {
            return;
        }
        serviceLimitNotifyTimes.put(taskId, now);

        int successCount = task.getSuccessCount() != null ? task.getSuccessCount() : 0;
        int targetCount = task.getCreateNumbers() != null && task.getCreateNumbers() > 0 ? task.getCreateNumbers() : 1;
        String shapeLine = StrUtil.isNotBlank(result.getResolvedTargetShape()) ? result.getResolvedTargetShape() : arch;
        String hint = StrUtil.isNotBlank(result.getFailureHint()) ? result.getFailureHint() : "已触发 OCI 服务限制，创建失败";
        String diskConfig = BootVolumeVpusUtil.formatDiskWithVpus(
                task.getDisk() != null ? task.getDisk() : 50,
                BootVolumeVpusUtil.normalize(task.getVpusPerGB()));

        String html = "<b>开机任务遇到 OCI 服务限制，仍在重试</b>\n\n"
                + "👤 <b>租户：</b>" + html(username) + "\n"
                + "🌍 <b>区域：</b><code>" + html(region) + "</code>\n"
                + "⚙️ <b>架构：</b>" + html(series) + "\n"
                + "💻 <b>Shape：</b><code>" + html(shapeLine) + "</code>\n"
                + "📊 <b>配置：</b>" + html(task.getOcpus()) + "C / " + html(task.getMemory()) + "GB / "
                + html(diskConfig) + "\n"
                + "📈 <b>进度：</b>" + successCount + " / " + targetCount + "\n"
                + "⏱ <b>重试间隔：</b>" + intervalSeconds + " 秒\n"
                + "📛 <b>OCI 提示：</b>" + html(hint) + "\n"
                + "ℹ️ <b>说明：</b>任务未停止，将继续按当前间隔重试。";
        notificationService.sendHtmlWithTypeAndInlineKeyboard(
                NotificationService.TYPE_TASK_RESULT,
                html,
                List.of(List.of(
                        Map.of("text", "停止任务", "callback_data", CALLBACK_SERVICE_LIMIT_STOP_REQUEST + taskId),
                        Map.of("text", "不再提醒", "callback_data", CALLBACK_SERVICE_LIMIT_MUTE + taskId))));
    }

    private boolean isServiceLimitNotifyMuted(String taskId) {
        if (StrUtil.isBlank(taskId)) {
            return false;
        }
        if (serviceLimitNotifyMutedTasks.contains(taskId)) {
            return true;
        }
        OciKv existing = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getCode, taskId)
                .eq(OciKv::getType, SERVICE_LIMIT_MUTE_KV_TYPE)
                .last("LIMIT 1"));
        if (existing != null) {
            serviceLimitNotifyMutedTasks.add(taskId);
            return true;
        }
        return false;
    }

    private void persistServiceLimitNotifyMute(String taskId) {
        if (StrUtil.isBlank(taskId)) {
            return;
        }
        OciKv existing = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getCode, taskId)
                .eq(OciKv::getType, SERVICE_LIMIT_MUTE_KV_TYPE)
                .last("LIMIT 1"));
        if (existing != null) {
            existing.setValue("1");
            kvMapper.updateById(existing);
            serviceLimitNotifyMutedTasks.add(taskId);
            return;
        }
        OciKv kv = new OciKv();
        kv.setId(CommonUtils.generateId());
        kv.setCode(taskId);
        kv.setType(SERVICE_LIMIT_MUTE_KV_TYPE);
        kv.setValue("1");
        kv.setCreateTime(LocalDateTime.now());
        kvMapper.insert(kv);
        serviceLimitNotifyMutedTasks.add(taskId);
    }

    private void applyAdExcludedNoShapeBroadcast(String taskId, String user, String region, String arch,
                                                  InstanceDetailDTO result, Set<String> excludedAds) {
        if (result.getAdsExcludedNoShape() == null || result.getAdsExcludedNoShape().isEmpty()) {
            return;
        }
        String shapeLine = StrUtil.isNotBlank(result.getResolvedTargetShape())
                ? result.getResolvedTargetShape() : arch;
        for (String adName : result.getAdsExcludedNoShape()) {
            if (excludedAds.add(adName)) {
                broadcastLog(String.format(
                        "【开机任务】用户:[%s],区域:[%s],系统架构:[%s],可用域:[%s] - 当前可用域无此 Shape",
                        user, region, shapeLine, formatAdForLog(adName)));
            }
        }
    }

    private static String formatAdForLog(String adName) {
        if (StrUtil.isBlank(adName)) {
            return "?";
        }
        int idx = adName.lastIndexOf("AD-");
        return idx >= 0 ? adName.substring(idx) : adName;
    }

    private SysUserDTO buildSysUserDTO(OciUser ociUser, OciCreateTask task) {
        double[] normalized = ShapeFlexLimitsUtil.normalizeOcpusAndMemory(
                task.getArchitecture(), task.getOcpus(), task.getMemory());
        return SysUserDTO.builder()
                .taskId(task.getId())
                .username(ociUser.getUsername())
                .architecture(task.getArchitecture())
                .ocpus(normalized[0])
                .memory(normalized[1])
                .disk(task.getDisk())
                .vpusPerGB(BootVolumeVpusUtil.normalize(task.getVpusPerGB()))
                .createNumbers(task.getCreateNumbers())
                .rootPassword(task.getRootPassword())
                .loginMode(normalizeLoginMode(task.getLoginMode()))
                .sshPublicKey(task.getSshPublicKey())
                .operationSystem(task.getOperationSystem())
                .instanceName(task.getInstanceName())
                .customScript(task.getCustomScript())
                .assignPublicIp(task.getAssignPublicIp() != null ? task.getAssignPublicIp() : true)
                .assignIpv6(task.getAssignIpv6() != null ? task.getAssignIpv6() : false)
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(ociUser.getOciTenantId())
                        .userId(ociUser.getOciUserId())
                        .fingerprint(ociUser.getOciFingerprint())
                        .region(task.getOciRegion())
                        .privateKeyPath(ociUser.getOciKeyPath())
                        .build())
                .build();
    }

    private static String normalizeTaskArchitecture(String architecture) {
        if (StrUtil.isBlank(architecture)) {
            return ArchitectureEnum.getShape("ARM");
        }
        String raw = architecture.trim();
        if (ShapeSeriesUtil.isFullShapeName(raw)) {
            return raw;
        }
        if ("ARM".equalsIgnoreCase(raw) || "AMD".equalsIgnoreCase(raw)) {
            return ArchitectureEnum.getShape(raw);
        }
        if ("Ampere".equalsIgnoreCase(raw)) {
            return ArchitectureEnum.getShape("ARM");
        }
        throw new OciException("未知实例 Shape，请重新选择 Shape 后再创建开机任务");
    }

    private void cleanExpiredTasks() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(15);
        taskMapper.delete(new LambdaQueryWrapper<OciCreateTask>()
                .in(OciCreateTask::getStatus,
                        TaskStatusEnum.COMPLETED.getStatus(),
                        TaskStatusEnum.FAILED.getStatus())
                .and(w -> w.lt(OciCreateTask::getStatusTime, cutoff)
                        .or(q -> q.isNull(OciCreateTask::getStatusTime)
                                .lt(OciCreateTask::getCreateTime, cutoff))));
    }

    /**
     * 将「计次已达标或已超开」但仍为 RUNNING 的任务收口为 COMPLETED。不修改 success_count，保留真实计次与计费可核对。
     */
    private void repairInconsistentRunningTasks() {
        List<OciCreateTask> running = taskMapper.selectList(
                new LambdaQueryWrapper<OciCreateTask>()
                        .eq(OciCreateTask::getStatus, TaskStatusEnum.RUNNING.getStatus()));
        for (OciCreateTask t : running) {
            int target = t.getCreateNumbers() != null && t.getCreateNumbers() > 0 ? t.getCreateNumbers() : 1;
            int sc = t.getSuccessCount() != null ? t.getSuccessCount() : 0;
            if (sc < target) {
                continue;
            }
            try {
                log.info("修复开机任务: id={} 进度{}/{} -> 已完成（计次不裁剪）", t.getId(), sc, target);
                completeTask(t.getId(), TaskStatusEnum.COMPLETED);
            } catch (Exception e) {
                log.warn("repairInconsistentRunningTasks id={}: {}", t.getId(), e.getMessage());
            }
        }
    }

    /** TG 通知：任务存的是完整 Shape 时补一行，与「架构」系列区分 */
    private static String targetShapeLineForNotify(String shapeOrArchitecture) {
        if (ShapeSeriesUtil.isFullShapeName(shapeOrArchitecture)) {
            return "💻 <b>Shape：</b><code>" + shapeOrArchitecture.trim() + "</code>\n";
        }
        return "";
    }

    private void answerTaskCallback(String callbackQueryId, String text, boolean showAlert, String answeringBotToken) {
        notificationService.answerTelegramCallbackQuery(callbackQueryId, text, showAlert, answeringBotToken);
    }

    private static String html(Object value) {
        if (value == null) {
            return "-";
        }
        return String.valueOf(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String tgSpoiler(Object value) {
        return "<tg-spoiler>" + html(value) + "</tg-spoiler>";
    }

    private static String targetShapeForLog(String shapeOrArchitecture) {
        if (ShapeSeriesUtil.isFullShapeName(shapeOrArchitecture)) {
            return ",Shape:[" + shapeOrArchitecture.trim() + "]";
        }
        return "";
    }

    private static final class TaskHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Future<?> future;
        private volatile Thread runner;

        private void attachFuture(Future<?> future) {
            this.future = future;
            if (cancelled.get()) {
                future.cancel(true);
            }
        }

        private void attachRunner(Thread runner) {
            this.runner = runner;
            if (cancelled.get()) {
                runner.interrupt();
            }
        }

        private void detachRunner() {
            runner = null;
        }

        private boolean isCancelled() {
            return cancelled.get();
        }

        private void cancel() {
            cancelled.set(true);
            Thread runningThread = runner;
            Future<?> runningFuture = future;
            if (runningThread != null && runningThread != Thread.currentThread()) {
                runningThread.interrupt();
            }
            if (runningFuture != null && runningThread != Thread.currentThread()) {
                runningFuture.cancel(true);
            }
        }
    }

    private void broadcastLog(String message) {
        log.info(message);
        LogWebSocketHandler.broadcast(message);
    }
}
