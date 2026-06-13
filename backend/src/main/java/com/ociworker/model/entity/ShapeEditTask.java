package com.ociworker.model.entity;

import com.ociworker.model.dto.ShapeEditTaskStatus;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;

@Getter
public class ShapeEditTask {
    public enum Status {
        PENDING,
        RUNNING,
        PAUSED,
        SUCCESS,
        FAILED,
        STOPPED
    }

    private final String taskId;
    private final String tenantId;
    private final String instanceId;
    private final String region;
    private final String targetShape;
    private final Float targetOcpus;
    private final Float targetMemoryInGBs;
    private final int maxRetries;
    private final long retryIntervalMillis;
    private final Callable<Map<String, Object>> operation;
    private final Object pauseMonitor = new Object();
    private final Instant createdAt = Instant.now();
    private volatile Instant updatedAt = createdAt;
    private volatile Instant finishedAt;
    private volatile Status status = Status.PENDING;
    private volatile String message = "检测到缺货，将在后台自动重试";
    private volatile int retryCount;
    private volatile boolean pauseRequested;
    private volatile boolean stopRequested;
    private volatile Thread thread;
    private volatile Map<String, Object> result;

    public ShapeEditTask(String taskId,
                         String tenantId,
                         String instanceId,
                         String region,
                         String targetShape,
                         Float targetOcpus,
                         Float targetMemoryInGBs,
                         int maxRetries,
                         long retryIntervalMillis,
                         Callable<Map<String, Object>> operation) {
        this.taskId = taskId;
        this.tenantId = tenantId;
        this.instanceId = instanceId;
        this.region = region;
        this.targetShape = targetShape;
        this.targetOcpus = targetOcpus;
        this.targetMemoryInGBs = targetMemoryInGBs;
        this.maxRetries = maxRetries;
        this.retryIntervalMillis = retryIntervalMillis;
        this.operation = operation;
    }

    public void bindThread(Thread thread) {
        this.thread = thread;
    }

    public void markRunning(String message) {
        this.status = Status.RUNNING;
        this.message = message;
        touch();
    }

    public void markWaiting(String message) {
        this.status = pauseRequested ? Status.PAUSED : Status.PENDING;
        this.message = message;
        touch();
    }

    public void markSuccess(Map<String, Object> result) {
        this.result = result;
        this.status = Status.SUCCESS;
        this.message = "形状变更成功";
        finish();
    }

    public void markFailed(String message) {
        this.status = Status.FAILED;
        this.message = message;
        finish();
    }

    public void markStopped(String message) {
        this.status = Status.STOPPED;
        this.message = message;
        finish();
    }

    public void incrementRetryCount() {
        this.retryCount++;
        touch();
    }

    public void pause() {
        if (isTerminal()) return;
        this.pauseRequested = true;
        this.status = Status.PAUSED;
        this.message = "已暂停";
        touch();
    }

    public void resume() {
        if (isTerminal()) return;
        synchronized (pauseMonitor) {
            this.pauseRequested = false;
            this.status = Status.PENDING;
            this.message = "已恢复，等待下一次重试";
            touch();
            pauseMonitor.notifyAll();
        }
    }

    public void stop() {
        if (isTerminal()) return;
        this.stopRequested = true;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
        synchronized (pauseMonitor) {
            pauseMonitor.notifyAll();
        }
        markStopped("已停止");
    }

    public boolean awaitIfPaused() throws InterruptedException {
        synchronized (pauseMonitor) {
            while (pauseRequested && !stopRequested) {
                status = Status.PAUSED;
                message = "已暂停";
                touch();
                pauseMonitor.wait();
            }
        }
        return stopRequested;
    }

    public boolean isTerminal() {
        return status == Status.SUCCESS || status == Status.FAILED || status == Status.STOPPED;
    }

    public ShapeEditTaskStatus toStatus() {
        return ShapeEditTaskStatus.builder()
                .taskId(taskId)
                .tenantId(tenantId)
                .instanceId(instanceId)
                .region(region)
                .status(status)
                .message(message)
                .retryCount(retryCount)
                .maxRetries(maxRetries)
                .pending(status == Status.PENDING || status == Status.RUNNING || status == Status.PAUSED)
                .paused(status == Status.PAUSED)
                .stopped(status == Status.STOPPED)
                .terminal(isTerminal())
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .finishedAt(finishedAt)
                .result(result)
                .build();
    }

    private void finish() {
        this.finishedAt = Instant.now();
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
