package com.ociworker.service;

import cn.hutool.core.date.DatePattern;
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
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

import static com.ociworker.config.VirtualThreadConfig.VIRTUAL_EXECUTOR;

@Slf4j
@Service
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
                    log.info("Restored task: user={}, region={}", ociUser.getUsername(), ociUser.getOciRegion());
                } catch (Exception e) {
                    log.error("Failed to restore task {}: {}", task.getId(), e.getMessage());
                    task.setStatus(TaskStatusEnum.FAILED.getStatus());
                    taskMapper.updateById(task);
                }
            }
        }
    }

    public Page<Map<String, Object>> listTasks(PageParams params) {
        Page<OciCreateTask> page = new Page<>(params.getCurrent(), params.getSize());
        LambdaQueryWrapper<OciCreateTask> wrapper = new LambdaQueryWrapper<>();
        if (params.getStatus() != null && !params.getStatus().isEmpty()) {
            wrapper.eq(OciCreateTask::getStatus, params.getStatus());
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
                           String rootPassword, String operationSystem) {
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
        task.setStatus(TaskStatusEnum.RUNNING.getStatus());
        task.setAttemptCount(0);
        task.setCreateTime(LocalDateTime.now());
        taskMapper.insert(task);

        SysUserDTO dto = buildSysUserDTO(ociUser, task);
        scheduleTask(task.getId(), dto, interval);

        String msg = String.format(CommonUtils.BEGIN_CREATE_MESSAGE_TEMPLATE,
                ociUser.getUsername(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern(DatePattern.NORM_DATETIME_PATTERN)),
                ociUser.getOciRegion(), architecture, ocpus, memory, disk, createNumbers, rootPassword);
        broadcastLog(msg);
        notificationService.sendMessage(msg);
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
        }
        log.info("Task stopped: {}", taskId);
    }

    private void scheduleTask(String taskId, SysUserDTO dto, int intervalSeconds) {
        ScheduledFuture<?> future = taskPool.scheduleWithFixedDelay(
                () -> VIRTUAL_EXECUTOR.execute(() -> executeCreate(taskId, dto)),
                0, intervalSeconds, TimeUnit.SECONDS);
        taskMap.put(taskId, future);
    }

    private void executeCreate(String taskId, SysUserDTO dto) {
        if (!runningTasks.add(taskId)) return;

        try (OciClientService client = new OciClientService(dto)) {
            InstanceDetailDTO result = client.createInstanceData();
            incrementAttempt(taskId);

            if (result.isDie()) {
                completeTask(taskId, TaskStatusEnum.FAILED);
                String msg = String.format("[CreateTask] User:[%s], Region:[%s] - Auth failed, task stopped",
                        dto.getUsername(), dto.getOciCfg().getRegion());
                broadcastLog(msg);
                notificationService.sendMessage(msg);
                return;
            }

            if (result.isNoShape()) {
                completeTask(taskId, TaskStatusEnum.FAILED);
                broadcastLog(String.format("[CreateTask] User:[%s] - Shape not available, task stopped",
                        dto.getUsername()));
                return;
            }

            if (result.isSuccess()) {
                completeTask(taskId, TaskStatusEnum.COMPLETED);
                String msg = String.format(
                        "🎉 [CreateTask] User:[%s] Instance created!\n" +
                        "Region: %s\nArch: %s\nShape: %s\n" +
                        "CPU: %s, Memory: %sGB, Disk: %sGB\n" +
                        "Public IP: %s\nPassword: %s",
                        dto.getUsername(), result.getRegion(), result.getArchitecture(),
                        result.getShape(), result.getOcpus(), result.getMemory(), result.getDisk(),
                        result.getPublicIp(), result.getRootPassword());
                broadcastLog(msg);
                notificationService.sendMessage(msg);
            }
        } catch (Exception e) {
            log.error("[CreateTask] User:[{}] - Error: {}", dto.getUsername(), e.getMessage());
        } finally {
            runningTasks.remove(taskId);
        }
    }

    private void incrementAttempt(String taskId) {
        OciCreateTask task = taskMapper.selectById(taskId);
        if (task != null) {
            task.setAttemptCount(task.getAttemptCount() + 1);
            taskMapper.updateById(task);
        }
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
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(ociUser.getOciTenantId())
                        .userId(ociUser.getOciUserId())
                        .fingerprint(ociUser.getOciFingerprint())
                        .region(ociUser.getOciRegion())
                        .privateKeyPath(ociUser.getOciKeyPath())
                        .build())
                .build();
    }

    private void broadcastLog(String message) {
        log.info(message);
        LogWebSocketHandler.broadcast(message);
    }
}
