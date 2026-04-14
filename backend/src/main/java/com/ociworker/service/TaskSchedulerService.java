package com.ociworker.service;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

    private final Map<String, ScheduledFuture<?>> taskMap = new ConcurrentHashMap<>();
    private final Set<String> runningTasks = ConcurrentHashMap.newKeySet();
    private final ScheduledThreadPoolExecutor taskPool = new ScheduledThreadPoolExecutor(
            Math.min(4, Runtime.getRuntime().availableProcessors()),
            ThreadFactoryBuilder.create().setNamePrefix("oci-task-").build());

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
            wrapper.and(w -> w
                    .like(OciCreateTask::getOciRegion, kw)
                    .or().like(OciCreateTask::getArchitecture, kw)
                    .or().like(OciCreateTask::getOperationSystem, kw)
                    .or().inSql(OciCreateTask::getUserId,
                            "SELECT id FROM oci_user WHERE username LIKE '%" + kw.replace("'", "") + "%'"));
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
            map.put("status", task.getStatus());
            map.put("attemptCount", task.getAttemptCount());
            map.put("createTime", task.getCreateTime());
            return map;
        }).toList());
        return enriched;
    }

    public void createTask(String userId, String architecture, Double ocpus, Double memory,
                           Integer disk, Integer createNumbers, Integer interval,
                           String rootPassword, String operationSystem, String customScript) {
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
        task.setStatus(TaskStatusEnum.RUNNING.getStatus());
        task.setAttemptCount(0);
        task.setCreateTime(LocalDateTime.now());
        taskMapper.insert(task);

        SysUserDTO dto = buildSysUserDTO(ociUser, task);
        scheduleTask(task.getId(), dto, interval);

        String msg = String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s],开机数量:[%d],CPU:[%s],内存:[%sGB],磁盘:[%sGB],root密码:[%s] - 任务已创建",
                ociUser.getUsername(), ociUser.getOciRegion(), architecture, createNumbers,
                ocpus, memory, disk, rootPassword != null ? rootPassword : "随机");
        broadcastLog(msg);
        notificationService.sendMessage(NotificationService.TYPE_TASK_CREATE, msg);
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

    public void deleteTask(String taskId) {
        ScheduledFuture<?> future = taskMap.get(taskId);
        if (future != null) {
            future.cancel(false);
            taskMap.remove(taskId);
        }
        taskMapper.deleteById(taskId);
    }

    public void stopTask(String taskId) {
        ScheduledFuture<?> future = taskMap.get(taskId);
        if (future != null) {
            future.cancel(false);
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

    private void scheduleTask(String taskId, SysUserDTO dto, int intervalSeconds) {
        ScheduledFuture<?> future = taskPool.scheduleWithFixedDelay(
                () -> VIRTUAL_EXECUTOR.execute(() -> executeCreate(taskId, dto, intervalSeconds)),
                0, intervalSeconds, TimeUnit.SECONDS);
        taskMap.put(taskId, future);
    }

    private void executeCreate(String taskId, SysUserDTO dto, int intervalSeconds) {
        if (!runningTasks.add(taskId)) return;

        String user = dto.getUsername();
        String region = dto.getOciCfg().getRegion();
        String arch = dto.getArchitecture();
        int attempt = incrementAttempt(taskId);

        broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s],开机数量:[%d],开始执行第 [%d] 次创建实例操作...",
                user, region, arch, dto.getCreateNumbers(), attempt));

        try (OciClientService client = new OciClientService(dto)) {
            InstanceDetailDTO result = client.createInstanceData();

            if (result.isDie()) {
                completeTask(taskId, TaskStatusEnum.FAILED);
                String msg = String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - ❌ 认证失败(401)，任务已停止", user, region, arch);
                broadcastLog(msg);
                notificationService.sendMessage(NotificationService.TYPE_TASK_RESULT, msg);
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
                completeTask(taskId, TaskStatusEnum.COMPLETED);
                String msg = String.format(
                        "【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - 🎉 实例创建成功！\n" +
                        "  Shape: %s | CPU: %s | 内存: %sGB | 磁盘: %sGB\n" +
                        "  公网IP: %s\n" +
                        "  Root密码: %s",
                        user, region, arch, result.getShape(), result.getOcpus(),
                        result.getMemory(), result.getDisk(), result.getPublicIp(), result.getRootPassword());
                broadcastLog(msg);
                notificationService.sendMessage(NotificationService.TYPE_TASK_RESULT, msg);
            } else {
                broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - 创建未成功，[%d]秒后将重试...",
                        user, region, arch, intervalSeconds));
            }
        } catch (Exception e) {
            broadcastLog(String.format("【开机任务】用户:[%s],区域:[%s],系统架构:[%s] - 错误: %s，[%d]秒后将重试...",
                    user, region, arch, e.getMessage(), intervalSeconds));
        } finally {
            runningTasks.remove(taskId);
        }
    }

    private int incrementAttempt(String taskId) {
        OciCreateTask task = taskMapper.selectById(taskId);
        if (task != null) {
            task.setAttemptCount(task.getAttemptCount() + 1);
            taskMapper.updateById(task);
            return task.getAttemptCount();
        }
        return 0;
    }

    private void completeTask(String taskId, TaskStatusEnum status) {
        ScheduledFuture<?> future = taskMap.get(taskId);
        if (future != null) {
            future.cancel(false);
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
