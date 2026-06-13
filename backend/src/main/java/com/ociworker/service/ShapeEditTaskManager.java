package com.ociworker.service;

import com.ociworker.exception.OciException;
import com.ociworker.model.dto.ShapeEditTaskStatus;
import com.ociworker.model.entity.ShapeEditTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ShapeEditTaskManager {
    private static final int MAX_RETRIES = 480;
    private static final long RETRY_INTERVAL_MILLIS = 20_000L;
    private static final Duration TERMINAL_TTL = Duration.ofMinutes(5);

    private final ConcurrentHashMap<String, ShapeEditTask> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> instanceTaskIndex = new ConcurrentHashMap<>();

    public synchronized ShapeEditTaskStatus startTask(String tenantId,
                                                      String instanceId,
                                                      String region,
                                                      Callable<Map<String, Object>> operation) {
        cleanupTerminalTasks();
        String instanceKey = instanceKey(tenantId, instanceId, region);
        String existingTaskId = instanceTaskIndex.get(instanceKey);
        if (existingTaskId != null) {
            ShapeEditTask existing = tasks.get(existingTaskId);
            if (existing != null && !existing.isTerminal()) {
                return existing.toStatus();
            }
            instanceTaskIndex.remove(instanceKey, existingTaskId);
        }

        String taskId = UUID.randomUUID().toString();
        ShapeEditTask task = new ShapeEditTask(
                taskId,
                tenantId,
                instanceId,
                region,
                MAX_RETRIES,
                RETRY_INTERVAL_MILLIS,
                operation);
        String activeTaskId = instanceTaskIndex.putIfAbsent(instanceKey, taskId);
        if (activeTaskId != null) {
            ShapeEditTask active = tasks.get(activeTaskId);
            if (active != null && !active.isTerminal()) {
                return active.toStatus();
            }
            instanceTaskIndex.remove(instanceKey, activeTaskId);
            activeTaskId = instanceTaskIndex.putIfAbsent(instanceKey, taskId);
            if (activeTaskId != null) {
                ShapeEditTask current = tasks.get(activeTaskId);
                if (current != null) {
                    return current.toStatus();
                }
            }
        }

        tasks.put(taskId, task);
        Thread worker = new Thread(() -> runTask(task, instanceKey), "shape-edit-task-" + taskId);
        worker.setDaemon(true);
        task.bindThread(worker);
        worker.start();
        return task.toStatus();
    }

    public ShapeEditTaskStatus getStatus(String taskId) {
        cleanupTerminalTasks();
        ShapeEditTask task = tasks.get(taskId);
        if (task == null) {
            throw new OciException("形状编辑任务不存在或已结束");
        }
        return task.toStatus();
    }

    public ShapeEditTaskStatus pause(String taskId) {
        ShapeEditTask task = taskOrThrow(taskId);
        task.pause();
        return task.toStatus();
    }

    public ShapeEditTaskStatus resume(String taskId) {
        ShapeEditTask task = taskOrThrow(taskId);
        task.resume();
        return task.toStatus();
    }

    public ShapeEditTaskStatus stop(String taskId) {
        ShapeEditTask task = taskOrThrow(taskId);
        task.stop();
        return task.toStatus();
    }

    public static boolean isOutOfStock(Throwable e) {
        if (e == null) return false;
        String msg = e.getMessage();
        String serviceCode = "";
        if (e instanceof com.oracle.bmc.model.BmcException bmcException && bmcException.getServiceCode() != null) {
            serviceCode = bmcException.getServiceCode();
        }
        String text = ((serviceCode == null ? "" : serviceCode) + " " + (msg == null ? "" : msg))
                .toLowerCase(Locale.ROOT);
        return text.contains("outofhostcapacity")
                || text.contains("out of host capacity")
                || text.contains("out of capacity")
                || text.contains("insufficient capacity")
                || text.contains("capacity is not available")
                || text.contains("no available host")
                || text.contains("缺货")
                || text.contains("容量不足");
    }

    private void runTask(ShapeEditTask task, String instanceKey) {
        try {
            while (!task.isStopRequested() && task.getRetryCount() < task.getMaxRetries()) {
                if (sleepBeforeRetry(task)) {
                    task.markStopped("已停止");
                    return;
                }
                if (task.awaitIfPaused()) {
                    task.markStopped("已停止");
                    return;
                }

                task.incrementRetryCount();
                task.markRunning("重试中 (第 " + task.getRetryCount() + " 次)");
                try {
                    Map<String, Object> result = task.getOperation().call();
                    if (task.isStopRequested() || task.isTerminal()) {
                        return;
                    }
                    task.markSuccess(result);
                    return;
                } catch (Throwable e) {
                    if (task.isStopRequested() || task.isTerminal()) {
                        return;
                    }
                    if (!isOutOfStock(e)) {
                        task.markFailed("失败: " + briefMessage(e));
                        return;
                    }
                    task.markWaiting("仍然缺货，等待下一次重试 (第 " + task.getRetryCount() + " 次)");
                    log.info("Shape edit task {} out of stock on retry {}/{}: {}",
                            task.getTaskId(), task.getRetryCount(), task.getMaxRetries(), briefMessage(e));
                }
            }
            if (!task.isTerminal()) {
                task.markStopped("重试超时，已自动停止");
            }
        } catch (InterruptedException e) {
            if (!task.isTerminal()) {
                task.markStopped("已停止");
            }
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            if (!task.isTerminal()) {
                task.markFailed("失败: " + briefMessage(e));
            }
        } finally {
            instanceTaskIndex.remove(instanceKey, task.getTaskId());
        }
    }

    private boolean sleepBeforeRetry(ShapeEditTask task) throws InterruptedException {
        long slept = 0L;
        while (slept < task.getRetryIntervalMillis()) {
            if (task.isStopRequested()) {
                return true;
            }
            if (task.awaitIfPaused()) {
                return true;
            }
            long step = Math.min(1000L, task.getRetryIntervalMillis() - slept);
            task.markWaiting("等待中，" + ((task.getRetryIntervalMillis() - slept + 999L) / 1000L) + " 秒后重试");
            Thread.sleep(step);
            slept += step;
        }
        return task.isStopRequested();
    }

    private ShapeEditTask taskOrThrow(String taskId) {
        cleanupTerminalTasks();
        ShapeEditTask task = tasks.get(taskId);
        if (task == null) {
            throw new OciException("形状编辑任务不存在或已结束");
        }
        return task;
    }

    private void cleanupTerminalTasks() {
        Instant now = Instant.now();
        tasks.forEach((taskId, task) -> {
            Instant finishedAt = task.getFinishedAt();
            if (task.isTerminal() && finishedAt != null
                    && Duration.between(finishedAt, now).compareTo(TERMINAL_TTL) > 0) {
                tasks.remove(taskId, task);
            }
        });
    }

    private static String instanceKey(String tenantId, String instanceId, String region) {
        return safe(tenantId) + "|" + safe(region) + "|" + safe(instanceId);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String briefMessage(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return msg.length() > 180 ? msg.substring(0, 180) + "..." : msg;
    }
}
