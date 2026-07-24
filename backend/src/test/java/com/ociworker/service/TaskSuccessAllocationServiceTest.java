package com.ociworker.service;

import com.ociworker.enums.TaskStatusEnum;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.ociworker.model.entity.OciCreateTask;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskSuccessAllocationServiceTest {

    @Test
    void allocatesDifferentOrdinalsFromTheLockedTaskSnapshot() throws Exception {
        OciCreateTaskMapper mapper = mock(OciCreateTaskMapper.class);
        TaskSuccessAllocationService service = new TaskSuccessAllocationService();
        setField(service, "taskMapper", mapper);

        OciCreateTask task = new OciCreateTask();
        task.setId("task-1");
        task.setStatus(TaskStatusEnum.RUNNING.getStatus());
        task.setCreateNumbers(2);
        task.setSuccessCount(0);
        when(mapper.selectByIdForUpdate(anyString())).thenReturn(task);
        when(mapper.update(any(), any())).thenReturn(1);

        TaskSuccessAllocationService.Allocation first = service.allocate("task-1");
        TaskSuccessAllocationService.Allocation second = service.allocate("task-1");

        assertTrue(first.allocated());
        assertEquals(1, first.ordinal());
        assertEquals(1, first.successCount());
        assertTrue(second.allocated());
        assertEquals(2, second.ordinal());
        assertEquals(2, second.successCount());
        assertEquals(2, task.getSuccessCount());
    }

    @Test
    void refusesAllocationAfterTargetWithoutChangingTheCount() throws Exception {
        OciCreateTaskMapper mapper = mock(OciCreateTaskMapper.class);
        TaskSuccessAllocationService service = new TaskSuccessAllocationService();
        setField(service, "taskMapper", mapper);

        OciCreateTask task = new OciCreateTask();
        task.setId("task-2");
        task.setStatus(TaskStatusEnum.COMPLETED.getStatus());
        task.setCreateNumbers(2);
        task.setSuccessCount(2);
        when(mapper.selectByIdForUpdate("task-2")).thenReturn(task);

        TaskSuccessAllocationService.Allocation result = service.allocate("task-2");

        assertFalse(result.allocated());
        assertEquals(0, result.ordinal());
        assertEquals(2, result.successCount());
        assertFalse(result.taskStillRunning());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
