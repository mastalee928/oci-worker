package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ociworker.enums.ArchitectureEnum;
import com.ociworker.enums.TaskStatusEnum;
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
import java.util.concurrent.*;

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

    private final Map<String, Future<?>> taskMap = new ConcurrentHashMap<>();
    private final Set<String> runningTasks = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, LocalDateTime> recentTaskCreateKeys = new ConcurrentHashMap<>();
    /** 本任务周期内不再尝试的可用域（停/改/恢复/完成/删除任务或服务重启后清空） */
    private final ConcurrentHashMap<String, Set<String>> taskExcludedAds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalDateTime> serviceLimitNotifyTimes = new ConcurrentHashMap<>();
    private final Set<String> serviceLimitNotifyMutedTasks = ConcurrentHashMap.newKeySet();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CREATE_TASK_DEDUP_SECONDS = 5;
    private static final int SERVICE_LIMIT_NOTIFY_COOLDOWN_MINUTES = 60;
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
        for (Future<?> future : new ArrayList<>(taskMap.values())) {
            future.cancel(true);
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
        repairInconsistentRunningTasks();
        cleanExpiredTasks();

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
                        .or().like(OciCreateTask::getOperationSystem, kw);
                if (!matchedUserIds.isEmpty()) {
                    w.or().in(OciCreateTask::getUserId, matchedUserIds);
                }
            });
        }
        wrapper.orderByDesc(OciCreateTask::getCreateTime);
        Page<OciCreateTask> result = taskMapper.selectPage(page, wrapper);

        Page<Map<String, Object>> enriched = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        enriched.setRecords(result.getRecords().stream().map(task -> {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            OciUser user = userMapper.selectById(task.getUserId());
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
            map.put("operationSystem", task.getOperationSystem());
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

    public void createTask(String userId, String architecture, Double ocpus, Double memory,
                           Integer disk, Integer vpusPerGB, Integer createNumbers, Integer interval,
                           String rootPassword, String operationSystem, String customScript,
                           Boolean assignPublicIp, Boolean assignIpv6, String ociRegionOverride) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        String normalizedArchitecture = normalizeTaskArchitecture(architecture);

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
        LocalDateTime now = LocalDateTime.now();
        String dedupKey = createTaskDedupKey(userId, effectiveRegion, normalizedArchitecture, normalized[0], normalized[1],
                disk, normalizedVpusPerGB, createNumbers, interval, rootPassword, operationSystem, customScript,
                normalizedAssignPublicIp, normalizedAssignIpv6);
        if (!acquireRecentTaskCreateGuard(dedupKey, now)) {
            log.info("忽略重复开机任务创建请求：userId={} region={} shape={}（{} 秒内同参数请求）",
                    userId, effectiveRegion, normalizedArchitecture, CREATE_TASK_DEDUP_SECONDS);
            return;
        }

        boolean inserted = false;
        try {
            if (hasRecentDuplicateCreateTask(userId, effectiveRegion, normalizedArchitecture, normalized[0], normalized[1],
                    disk, normalizedVpusPerGB, createNumbers, interval, rootPassword, operationSystem, customScript,
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
            task.setCreateNumbers(createNumbers);
            task.setIntervalSeconds(interval);
            task.setRootPassword(rootPassword);
            task.setOperationSystem(operationSystem);
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
            scheduleTask(task.getId(), dto, interval);

            String series = ShapeSeriesUtil.resolveSeries(normalizedArchitecture);
            String diskConfig = BootVolumeVpusUtil.formatDiskWithVpus(disk != null ? disk : 50, task.getVpusPerGB());
            String logMsg = String.format("【开机任务】用户:[%s],区域:[%s],架构:[%s]%s,配置:[%sC/%sGB/%s],数量:[%d] - 任务已创建",
                    ociUser.getUsername(), effectiveRegion, series, targetShapeForLog(normalizedArchitecture),
                    normalized[0], normalized[1], diskConfig, createNumbers);
            broadcastLog(logMsg);

            String pwd = StrUtil.isNotBlank(rootPassword) ? tgSpoiler(rootPassword) : "随机";
            String html = "📋 <b>开机任务已创建</b>\n\n"
                    + "👤 <b>租户：</b>" + ociUser.getUsername() + "\n"
                    + "🌍 <b>区域：</b>" + effectiveRegion + "\n"
                    + "⚙️ <b>架构：</b>" + series + "\n"
                    + targetShapeLineForNotify(normalizedArchitecture)
                    + "📊 <b>配置：</b>" + normalized[0] + "C / " + normalized[1] + "GB / "
                    + diskConfig + "\n"
                    + "🔢 <b>数量：</b>" + createNumbers + "\n"
                    + "🔑 <b>密码：</b>" + pwd;
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
                                                 String operationSystem, String customScript,
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
        addNullableTextEquals(wrapper, OciCreateTask::getRootPassword, rootPassword);
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
                                             String operationSystem, String customScript,
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
                safeKeyPart(operationSystem),
                String.valueOf(Objects.hashCode(customScript)),
                String.valueOf(assignPublicIp),
                String.valueOf(assignIpv6));
    }

    private static String safeKeyPart(String value) {
        return value == null ? "" : value.trim();
    }

    public void resumeTask(String taskId) {
        OciCreateTask task = taskMapper.selectById(taskId);
        if (task == null) throw new OciException("任务不存在");
        if (TaskStatusEnum.RUNNING.getStatus().equals(task.getStatus())) {
            throw new OciException("任务已在运行中");
        }
        OciUser ociUser = userMapper.selectById(task.getUserId());
        if (ociUser == null) throw new OciException("租户配置不存在");

        task.setStatus(TaskStatusEnum.RUNNING.getStatus());
        task.setStatusTime(null);
        task.setFailureReason(null);
        taskMapper.updateById(task);

        clearTaskExcludedAds(taskId);
        clearServiceLimitNotifyState(taskId);
        SysUserDTO dto = buildSysUserDTO(ociUser, task);
        scheduleTask(task.getId(), dto, task.getIntervalSeconds());

        broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - 任务已恢复运行",
                ociUser.getUsername(), task.getOciRegion(), task.getArchitecture()));
    }

    public void updateTask(String taskId, String architecture, Double ocpus, Double memory,
                           Integer disk, Integer vpusPerGB, Integer createNumbers, Integer interval,
                           String rootPassword, String operationSystem, String customScript,
                           Boolean assignPublicIp, Boolean assignIpv6) {
        OciCreateTask task = taskMapper.selectById(taskId);
        if (task == null) throw new OciException("任务不存在");

        boolean wasRunning = TaskStatusEnum.RUNNING.getStatus().equals(task.getStatus());
        if (wasRunning) {
            Future<?> future = taskMap.get(taskId);
            if (future != null) {
                future.cancel(true);
                taskMap.remove(taskId);
            }
        }

        if (architecture != null) task.setArchitecture(normalizeTaskArchitecture(architecture));
        if (ocpus != null) task.setOcpus(ocpus);
        if (memory != null) task.setMemory(memory);
        if (disk != null) task.setDisk(disk);
        if (vpusPerGB != null) task.setVpusPerGB(BootVolumeVpusUtil.normalize(vpusPerGB));
        if (createNumbers != null) task.setCreateNumbers(createNumbers);
        if (interval != null) task.setIntervalSeconds(interval);
        if (rootPassword != null && !rootPassword.isBlank()) task.setRootPassword(rootPassword);
        if (operationSystem != null) task.setOperationSystem(operationSystem);
        if (customScript != null) task.setCustomScript(customScript);
        if (assignPublicIp != null) task.setAssignPublicIp(assignPublicIp);
        if (assignIpv6 != null) task.setAssignIpv6(assignIpv6);
        double[] normalized = ShapeFlexLimitsUtil.normalizeAndLogIfAdjusted(
                task.getArchitecture(), task.getOcpus(), task.getMemory(), "更新开机任务");
        task.setOcpus(normalized[0]);
        task.setMemory(normalized[1]);
        if (wasRunning) {
            task.setFailureReason(null);
        }
        taskMapper.updateById(task);

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
        Future<?> future = taskMap.get(taskId);
        if (future != null) {
            future.cancel(true);
            taskMap.remove(taskId);
        }
        taskMapper.deleteById(taskId);
        clearTaskExcludedAds(taskId);
        clearServiceLimitNotifyState(taskId);
    }

    public void stopTask(String taskId) {
        Future<?> future = taskMap.get(taskId);
        if (future != null) {
            future.cancel(true);
            taskMap.remove(taskId);
        }

        OciCreateTask task = taskMapper.selectById(taskId);
        if (task != null) {
            task.setStatus(TaskStatusEnum.STOPPED.getStatus());
            task.setStatusTime(LocalDateTime.now());
            taskMapper.updateById(task);
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
        Future<?> future = VIRTUAL_EXECUTOR.submit(() -> runTaskLoop(taskId, dto, delaySec));
        taskMap.put(taskId, future);
    }

    private void runTaskLoop(String taskId, SysUserDTO dto, int delaySec) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                OciCreateTask t = taskMapper.selectById(taskId);
                if (t == null) {
                    break;
                }
                if (!TaskStatusEnum.RUNNING.getStatus().equals(t.getStatus())) {
                    break;
                }
                executeCreate(taskId, dto, delaySec);
                t = taskMapper.selectById(taskId);
                if (t == null) {
                    break;
                }
                if (!TaskStatusEnum.RUNNING.getStatus().equals(t.getStatus())) {
                    break;
                }
                try {
                    Thread.sleep(delaySec * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            taskMap.remove(taskId);
        }
    }

    private void executeCreate(String taskId, SysUserDTO dto, int intervalSeconds) {
        if (!runningTasks.add(taskId)) return;
        String user = "";
        String region = "";
        String arch = "";
        try {
            OciCreateTask head = taskMapper.selectById(taskId);
            if (head == null) {
                return;
            }
            if (!TaskStatusEnum.RUNNING.getStatus().equals(head.getStatus())) {
                return;
            }
            // 多进程/多实例、或本机并发时，以库里的 success_count 为准，达目标则不再开新实例
            int headTarget = head.getCreateNumbers() != null && head.getCreateNumbers() > 0
                    ? head.getCreateNumbers() : 1;
            int headSc = head.getSuccessCount() != null ? head.getSuccessCount() : 0;
            if (headSc >= headTarget) {
                if (TaskStatusEnum.RUNNING.getStatus().equals(head.getStatus())) {
                    completeTask(taskId, TaskStatusEnum.COMPLETED);
                }
                return;
            }
            double[] launchNorm = ShapeFlexLimitsUtil.normalizeAndLogIfAdjusted(
                    head.getArchitecture(), head.getOcpus(), head.getMemory(), "执行开机任务");
            if (!Objects.equals(head.getOcpus(), launchNorm[0])
                    || !Objects.equals(head.getMemory(), launchNorm[1])) {
                head.setOcpus(launchNorm[0]);
                head.setMemory(launchNorm[1]);
                taskMapper.updateById(head);
            }
            dto.setOcpus(launchNorm[0]);
            dto.setMemory(launchNorm[1]);
            dto.setDisk(head.getDisk());
            dto.setVpusPerGB(BootVolumeVpusUtil.normalize(head.getVpusPerGB()));
            user = dto.getUsername();
            region = dto.getOciCfg().getRegion();
            arch = dto.getArchitecture();
            String series = ShapeSeriesUtil.resolveSeries(arch);
            int attempt = incrementAttempt(taskId);
            broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s],开机数量:[%d],开始执行第 [%d] 次创建实例操作...",
                    user, region, series, dto.getCreateNumbers(), attempt));

            dto.setInstanceDisplayOrdinal(headSc + 1);
            Set<String> excludedAds = taskExcludedAds.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet());
            dto.setExcludedAvailabilityDomains(new HashSet<>(excludedAds));
            try (OciClientService client = new OciClientService(dto)) {
                InstanceDetailDTO result = client.createInstanceData();

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
                    notificationService.sendHtmlWithType(NotificationService.TYPE_TASK_RESULT, html);
                    return;
                }

                if (result.isNoShape()) {
                    String shapeForReason = StrUtil.isNotBlank(result.getResolvedTargetShape())
                            ? result.getResolvedTargetShape() : arch;
                    String failureReason = "❌ Shape 不可用，任务已停止。目标 Shape：" + shapeForReason
                            + "；请切换区域、Shape 或稍后重试。";
                    completeTask(taskId, TaskStatusEnum.FAILED, failureReason);
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - %s", user, region, arch, failureReason));
                    return;
                }

                if (result.isBootVolumeQuotaExceeded()) {
                    String hint = StrUtil.isNotBlank(result.getFailureHint())
                            ? result.getFailureHint()
                            : "引导卷（启动盘）存储配额已达上限，硬盘配额用尽，创建失败";
                    String failureReason = "❌ " + hint + "。任务已停止。";
                    completeTask(taskId, TaskStatusEnum.FAILED, failureReason);
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - %s",
                            user, region, arch, failureReason));
                    return;
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
                    notificationService.sendHtmlWithType(NotificationService.TYPE_TASK_RESULT, html);
                    return;
                }

                if (result.isOutOfCapacity()) {
                    if (result.isOciServiceLimitExceeded()) {
                        notifyOciServiceLimitIfNeeded(taskId, head, user, region, series, arch, result, intervalSeconds);
                    }
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - 各可用域容量不足，[%d]秒后将重试...",
                            user, region, arch, intervalSeconds));
                    return;
                }

                if (result.isAllAdsExcludedNoShape()) {
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - 各可用域均无此 Shape，[%d]秒后将重试...",
                            user, region, arch, intervalSeconds));
                    return;
                }

                if (result.isNoPubVcn()) {
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - 未找到可用公有子网，正在尝试创建...",
                            user, region, arch));
                    return;
                }

                if (result.isSuccess()) {
                    int n = tryIncrementSuccessCount(taskId);
                    OciCreateTask t = taskMapper.selectById(taskId);
                    int targetCount = t != null && t.getCreateNumbers() != null && t.getCreateNumbers() > 0
                            ? t.getCreateNumbers() : 1;
                    int successCount = t != null && t.getSuccessCount() != null ? t.getSuccessCount() : 0;
                    if (n > 0) {
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
                                + "🔑 <b>密码：</b>" + tgSpoiler(result.getRootPassword());
                        notificationService.sendHtmlWithType(NotificationService.TYPE_TASK_RESULT, html);
                    } else {
                        // OCI 已建出实例，但行级更新因已达目标/并发被跳过
                        broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],架构:[%s] - 实例已创建(计次未增加) IP:%s（已达目标或并发争用，请在控制台核对实例）",
                                user, region, arch, result.getPublicIp()));
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
            } catch (Exception e) {
                String hint = OciClientService.describeThrowableFailure(e);
                broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - %s，[%d]秒后将重试...",
                        user, region, arch, hint, intervalSeconds));
            }
        } finally {
            runningTasks.remove(taskId);
        }
    }

    private int incrementAttempt(String taskId) {
        UpdateWrapper<OciCreateTask> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", taskId).setSql("attempt_count = COALESCE(attempt_count, 0) + 1");
        taskMapper.update(null, wrapper);
        OciCreateTask task = taskMapper.selectById(taskId);
        return task != null && task.getAttemptCount() != null ? task.getAttemptCount() : 0;
    }

    /** 仅当计次未达 create_numbers 时 +1；用于多机并发时只涨一行，避免出现 2/1 */
    private int tryIncrementSuccessCount(String taskId) {
        UpdateWrapper<OciCreateTask> w = new UpdateWrapper<>();
        w.eq("id", taskId);
        w.apply("COALESCE(success_count, 0) < COALESCE(create_numbers, 1)");
        w.setSql("success_count = COALESCE(success_count, 0) + 1");
        return taskMapper.update(null, w);
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
        taskMap.remove(taskId);
        clearTaskExcludedAds(taskId);
        OciCreateTask task = taskMapper.selectById(taskId);
        if (task != null) {
            task.setStatus(status.getStatus());
            task.setStatusTime(LocalDateTime.now());
            task.setFailureReason(failureReason);
            taskMapper.updateById(task);
        }
        clearServiceLimitNotifyState(taskId);
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
                .operationSystem(task.getOperationSystem())
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

    private void broadcastLog(String message) {
        log.info(message);
        LogWebSocketHandler.broadcast(message);
    }
}
