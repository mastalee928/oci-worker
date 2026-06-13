package com.ociworker.model.dto;

import com.ociworker.model.entity.ShapeEditTask;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShapeEditTaskStatus {
    private String taskId;
    private String instanceId;
    private String tenantId;
    private String region;
    private ShapeEditTask.Status status;
    private String message;
    private int retryCount;
    private int maxRetries;
    private boolean pending;
    private boolean paused;
    private boolean stopped;
    private boolean terminal;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant finishedAt;
    private Map<String, Object> result;
}
