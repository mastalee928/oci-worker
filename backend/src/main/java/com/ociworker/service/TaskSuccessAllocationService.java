package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ociworker.enums.TaskStatusEnum;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.ociworker.model.entity.OciCreateTask;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates the ordinal shown in a successful create notification.
 *
 * <p>The count and the ordinal are the same value, but the important detail is that
 * the value is read while holding the task row lock.  A separate "increment, then
 * select" sequence lets two workers both observe the final count and emit duplicate
 * messages such as 2/2.</p>
 */
@Service
public class TaskSuccessAllocationService {

    @Resource
    private OciCreateTaskMapper taskMapper;

    /**
     * Atomically reserve one success slot for a task.  The update deliberately does
     * not require RUNNING: an OCI request can finish while a stop/complete operation
     * races with it, and the already-created instance must remain auditable.  The
     * existing upper-bound rule is retained so we never hide or manufacture an
     * additional success count.
     */
    @Transactional(rollbackFor = Exception.class)
    public Allocation allocate(String taskId) {
        OciCreateTask task = taskMapper.selectByIdForUpdate(taskId);
        if (task == null) {
            return Allocation.notAllocated(null, 0, 1, false);
        }

        int targetCount = normalizedTarget(task.getCreateNumbers());
        int currentCount = normalizedSuccess(task.getSuccessCount());
        boolean taskStillRunning = TaskStatusEnum.RUNNING.getStatus().equals(task.getStatus());
        if (currentCount >= targetCount) {
            return Allocation.notAllocated(task, currentCount, targetCount, taskStillRunning);
        }

        int ordinal = currentCount + 1;
        UpdateWrapper<OciCreateTask> update = new UpdateWrapper<>();
        update.eq("id", taskId)
                // Keep a defensive compare-and-set in addition to the row lock.  It
                // protects this code if a different data access path ever updates
                // the row without taking the same lock.
                .apply("COALESCE(success_count, 0) = {0}", currentCount)
                .set("success_count", ordinal);
        if (taskMapper.update(null, update) != 1) {
            throw new IllegalStateException("无法分配实例成功序号，任务计数已被并发修改");
        }

        task.setSuccessCount(ordinal);
        return Allocation.allocated(task, ordinal, targetCount, taskStillRunning);
    }

    private static int normalizedTarget(Integer value) {
        return value != null && value > 0 ? value : 1;
    }

    private static int normalizedSuccess(Integer value) {
        return value != null && value > 0 ? value : 0;
    }

    public record Allocation(
            boolean allocated,
            int ordinal,
            int successCount,
            int targetCount,
            boolean taskStillRunning,
            OciCreateTask task) {

        static Allocation allocated(OciCreateTask task, int ordinal, int targetCount,
                                     boolean taskStillRunning) {
            return new Allocation(true, ordinal, ordinal, targetCount, taskStillRunning, task);
        }

        static Allocation notAllocated(OciCreateTask task, int successCount, int targetCount,
                                       boolean taskStillRunning) {
            return new Allocation(false, 0, successCount, targetCount, taskStillRunning, task);
        }
    }
}
