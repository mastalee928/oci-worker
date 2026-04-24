package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ociworker.enums.TaskStatusEnum;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.InstanceDetailDTO;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciCreateTask;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.params.PageParams;
import com.ociworker.util.CommonUtils;
import com.ociworker.websocket.LogWebSocketHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

import static com.ociworker.config.VirtualThreadConfig.VIRTUAL_EXECUTOR;

@Slf4j
@Service
@DependsOn("databaseGuardService")
public class TaskSchedulerService {

    @Resource
    private OciCreateTaskMapper taskMapper;
    @Resource
    private OciUserMapper userMapper;
    @Resource
    private NotificationService notificationService;

    private final Map<String, Future<?>> taskMap = new ConcurrentHashMap<>();
    private final Set<String> runningTasks = ConcurrentHashMap.newKeySet();
    private static final ObjectMapper JSON = new ObjectMapper();

    @PostConstruct
    public void init() {
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
                        taskMapper.updateById(task);
                        continue;
                    }
                    SysUserDTO dto = buildSysUserDTO(ociUser, task);
                    scheduleTask(task.getId(), dto, task.getIntervalSeconds());
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - 服务重启，恢复任务执行",
                            ociUser.getUsername(), ociUser.getOciRegion(), task.getArchitecture()));
                } catch (Exception e) {
                    log.error("Failed to restore task {}: {}", task.getId(), e.getMessage());
                    task.setStatus(TaskStatusEnum.FAILED.getStatus());
                    taskMapper.updateById(task);
                }
            }
        }
    }

    public boolean hasRunningTask(String userId) {
        return taskMapper.selectCount(
                new LambdaQueryWrapper<OciCreateTask>()
                        .eq(OciCreateTask::getUserId, userId)
                        .eq(OciCreateTask::getStatus, TaskStatusEnum.RUNNING.getStatus())
        ) > 0;
    }

    public Page<Map<String, Object>> listTasks(PageParams params) {
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
            map.put("architecture", task.getArchitecture());
            map.put("intervalSeconds", task.getIntervalSeconds());
            map.put("createNumbers", task.getCreateNumbers());
            map.put("operationSystem", task.getOperationSystem());
            map.put("customScript", task.getCustomScript());
            map.put("assignPublicIp", task.getAssignPublicIp() != null ? task.getAssignPublicIp() : true);
            map.put("assignIpv6", task.getAssignIpv6() != null ? task.getAssignIpv6() : false);
            map.put("status", task.getStatus());
            map.put("attemptCount", task.getAttemptCount());
            map.put("successCount", task.getSuccessCount() != null ? task.getSuccessCount() : 0);
            map.put("createTime", task.getCreateTime());
            return map;
        }).toList());
        return enriched;
    }

    public void createTask(String userId, String architecture, Double ocpus, Double memory,
                           Integer disk, Integer createNumbers, Integer interval,
                           String rootPassword, String operationSystem, String customScript,
                           Boolean assignPublicIp, Boolean assignIpv6) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        OciCreateTask task = new OciCreateTask();
        task.setId(CommonUtils.generateId());
        task.setUserId(userId);
        task.setOciRegion(ociUser.getOciRegion());
        task.setArchitecture(architecture);
        task.setOcpus(ocpus);
        task.setMemory(memory);
        task.setDisk(disk);
        task.setCreateNumbers(createNumbers);
        task.setIntervalSeconds(interval);
        task.setRootPassword(rootPassword);
        task.setOperationSystem(operationSystem);
        task.setCustomScript(customScript);
        task.setAssignPublicIp(assignPublicIp != null ? assignPublicIp : true);
        task.setAssignIpv6(assignIpv6 != null ? assignIpv6 : false);
        task.setStatus(TaskStatusEnum.RUNNING.getStatus());
        task.setAttemptCount(0);
        task.setSuccessCount(0);
        task.setCreateTime(LocalDateTime.now());
        taskMapper.insert(task);

        SysUserDTO dto = buildSysUserDTO(ociUser, task);
        scheduleTask(task.getId(), dto, interval);

        String logMsg = String.format("【开机任务】用户:[%s],区域:[%s],架构:[%s],数量:[%d] - 任务已创建",
                ociUser.getUsername(), ociUser.getOciRegion(), architecture, createNumbers);
        broadcastLog(logMsg);

        String pwd = rootPassword != null ? rootPassword : "随机";
        String html = "📋 <b>开机任务已创建</b>\n\n"
                + "👤 <b>租户：</b>" + ociUser.getUsername() + "\n"
                + "🌍 <b>区域：</b>" + ociUser.getOciRegion() + "\n"
                + "⚙️ <b>架构：</b>" + architecture + "\n"
                + "📊 <b>配置：</b>" + ocpus + "C / " + memory + "GB / " + disk + "GB\n"
                + "🔢 <b>数量：</b>" + createNumbers + "\n"
                + "🔑 <b>密码：</b><code>" + pwd + "</code>";
        notificationService.sendHtmlWithType(NotificationService.TYPE_TASK_CREATE, html);
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
        taskMapper.updateById(task);

        SysUserDTO dto = buildSysUserDTO(ociUser, task);
        scheduleTask(task.getId(), dto, task.getIntervalSeconds());

        broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - 任务已恢复运行",
                ociUser.getUsername(), ociUser.getOciRegion(), task.getArchitecture()));
    }

    public void updateTask(String taskId, String architecture, Double ocpus, Double memory,
                           Integer disk, Integer createNumbers, Integer interval,
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

        if (architecture != null) task.setArchitecture(architecture);
        if (ocpus != null) task.setOcpus(ocpus);
        if (memory != null) task.setMemory(memory);
        if (disk != null) task.setDisk(disk);
        if (createNumbers != null) task.setCreateNumbers(createNumbers);
        if (interval != null) task.setIntervalSeconds(interval);
        if (rootPassword != null && !rootPassword.isBlank()) task.setRootPassword(rootPassword);
        if (operationSystem != null) task.setOperationSystem(operationSystem);
        if (customScript != null) task.setCustomScript(customScript);
        if (assignPublicIp != null) task.setAssignPublicIp(assignPublicIp);
        if (assignIpv6 != null) task.setAssignIpv6(assignIpv6);
        taskMapper.updateById(task);

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
            taskMapper.updateById(task);
            OciUser user = userMapper.selectById(task.getUserId());
            String name = user != null ? user.getUsername() : "unknown";
            broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s] - 任务已手动停止",
                    name, task.getOciRegion()));
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
            user = dto.getUsername();
            region = dto.getOciCfg().getRegion();
            arch = dto.getArchitecture();
            int attempt = incrementAttempt(taskId);
            broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s],开机数量:[%d],开始执行第 [%d] 次创建实例操作...",
                    user, region, arch, dto.getCreateNumbers(), attempt));

            try (OciClientService client = new OciClientService(dto)) {
                InstanceDetailDTO result = client.createInstanceData();

                if (result.isDie()) {
                    completeTask(taskId, TaskStatusEnum.FAILED);
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],架构:[%s] - 认证失败(401)，任务已停止", user, region, arch));
                    String html = "❌ <b>开机任务失败</b>\n\n"
                            + "👤 <b>租户：</b>" + user + "\n"
                            + "🌍 <b>区域：</b>" + region + "\n"
                            + "⚙️ <b>架构：</b>" + arch + "\n"
                            + "📛 <b>原因：</b>认证失败 (401)，任务已停止";
                    notificationService.sendHtmlWithType(NotificationService.TYPE_TASK_RESULT, html);
                    return;
                }

                if (result.isNoShape()) {
                    completeTask(taskId, TaskStatusEnum.FAILED);
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - ❌ Shape 不可用，任务已停止", user, region, arch));
                    return;
                }

                if (result.isOutOfCapacity()) {
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - 容量不足，[%d]秒后将重试...",
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
                        int display = Math.min(successCount, targetCount);
                        broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],架构:[%s] - 实例创建成功(%d/%d)！IP:%s",
                                user, region, arch, display, targetCount, result.getPublicIp()));
                        String html = "🎉 <b>实例创建成功！</b>（" + display + "/" + targetCount + "）\n\n"
                                + "👤 <b>租户：</b>" + user + "\n"
                                + "🌍 <b>区域：</b>" + region + "\n"
                                + "⚙️ <b>架构：</b>" + arch + "\n"
                                + "💻 <b>Shape：</b>" + result.getShape() + "\n"
                                + "📊 <b>配置：</b>" + result.getOcpus() + "C / " + result.getMemory() + "GB / " + result.getDisk() + "GB\n"
                                + "🌐 <b>公网IP：</b><code>" + result.getPublicIp() + "</code>\n"
                                + "🔑 <b>密码：</b><code>" + result.getRootPassword() + "</code>";
                        notificationService.sendHtmlWithType(NotificationService.TYPE_TASK_RESULT, html);
                    } else {
                        // OCI 已建出实例，但行级更新因已达目标/并发被跳过
                        broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],架构:[%s] - 实例已创建(计次未增加) IP:%s（已达目标或并发争用，请在控制台核对实例）",
                                user, region, arch, result.getPublicIp()));
                    }
                    if (successCount >= targetCount) {
                        completeTask(taskId, TaskStatusEnum.COMPLETED);
                        broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],架构:[%s] - 已达到目标数量(%d台)，任务完成！",
                                user, region, arch, targetCount));
                    } else {
                        broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],架构:[%s] - 还需创建 %d 台，[%d]秒后继续...",
                                user, region, arch, targetCount - successCount, intervalSeconds));
                    }
                } else {
                    broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - 创建未成功，[%d]秒后将重试...",
                            user, region, arch, intervalSeconds));
                }
            } catch (Exception e) {
                broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - 错误: %s，[%d]秒后将重试...",
                        user, region, arch, e.getMessage(), intervalSeconds));
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
            item.put("publicIp", result.getPublicIp());
            item.put("privateIp", result.getPrivateIp());
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
        data.put("createNumbers", task.getCreateNumbers());
        data.put("operationSystem", task.getOperationSystem());
        data.put("customScript", task.getCustomScript());
        data.put("assignPublicIp", task.getAssignPublicIp() != null ? task.getAssignPublicIp() : true);
        data.put("assignIpv6", task.getAssignIpv6() != null ? task.getAssignIpv6() : false);
        data.put("status", task.getStatus());
        data.put("attemptCount", task.getAttemptCount());
        data.put("successCount", task.getSuccessCount() != null ? task.getSuccessCount() : 0);
        data.put("createTime", task.getCreateTime());
        data.put("rootPassword", task.getRootPassword());
        data.put("instances", parseCreatedInstances(task.getCreatedInstances()));
        return data;
    }

    private void completeTask(String taskId, TaskStatusEnum status) {
        Future<?> future = taskMap.get(taskId);
        if (future != null) {
            future.cancel(true);
            taskMap.remove(taskId);
        }
        OciCreateTask task = taskMapper.selectById(taskId);
        if (task != null) {
            task.setStatus(status.getStatus());
            taskMapper.updateById(task);
        }
    }

    private SysUserDTO buildSysUserDTO(OciUser ociUser, OciCreateTask task) {
        return SysUserDTO.builder()
                .taskId(task.getId())
                .username(ociUser.getUsername())
                .architecture(task.getArchitecture())
                .ocpus(task.getOcpus())
                .memory(task.getMemory())
                .disk(task.getDisk())
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
                        .region(ociUser.getOciRegion())
                        .privateKeyPath(ociUser.getOciKeyPath())
                        .build())
                .build();
    }

    private void cleanExpiredTasks() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        taskMapper.delete(new LambdaQueryWrapper<OciCreateTask>()
                .ne(OciCreateTask::getStatus, TaskStatusEnum.RUNNING.getStatus())
                .lt(OciCreateTask::getCreateTime, cutoff));
    }

    private void broadcastLog(String message) {
        log.info(message);
        LogWebSocketHandler.broadcast(message);
    }
}
