package com.ociworker.service;

import com.ociworker.enums.TaskStatusEnum;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciCreateTask;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskSchedulerConcurrencyTest {

    @Test
    void acquiresAttemptPermitBeforeReadingTaskFromDatabase() throws Exception {
        TaskSchedulerService service = new TaskSchedulerService();
        AdaptiveLaunchConcurrency concurrency = mock(AdaptiveLaunchConcurrency.class);
        AdaptiveLaunchConcurrency.Permit permit = mock(AdaptiveLaunchConcurrency.Permit.class);
        OciCreateTaskMapper taskMapper = mock(OciCreateTaskMapper.class);
        SysUserDTO dto = mock(SysUserDTO.class);

        OciCreateTask stoppedTask = new OciCreateTask();
        stoppedTask.setStatus(TaskStatusEnum.STOPPED.getStatus());
        when(concurrency.acquire(anyString())).thenReturn(permit);
        when(taskMapper.selectById("task-1")).thenReturn(stoppedTask);

        setField(service, "adaptiveLaunchConcurrency", concurrency);
        setField(service, "taskMapper", taskMapper);

        Method runTaskLoop = TaskSchedulerService.class.getDeclaredMethod(
                "runTaskLoop", String.class, SysUserDTO.class, int.class);
        runTaskLoop.setAccessible(true);
        runTaskLoop.invoke(service, "task-1", dto, 60);

        InOrder order = inOrder(concurrency, taskMapper);
        order.verify(concurrency).acquire("unknown|unknown");
        order.verify(taskMapper).selectById("task-1");
    }

    @Test
    void transientDatabaseFailureDoesNotSilentlyStopRunningLoop() throws Exception {
        TaskSchedulerService service = new TaskSchedulerService();
        AdaptiveLaunchConcurrency concurrency = mock(AdaptiveLaunchConcurrency.class);
        AdaptiveLaunchConcurrency.Permit permit = mock(AdaptiveLaunchConcurrency.Permit.class);
        OciCreateTaskMapper taskMapper = mock(OciCreateTaskMapper.class);
        SysUserDTO dto = mock(SysUserDTO.class);

        OciCreateTask stoppedTask = new OciCreateTask();
        stoppedTask.setStatus(TaskStatusEnum.STOPPED.getStatus());
        when(concurrency.acquire(anyString())).thenReturn(permit);
        when(taskMapper.selectById("task-2"))
                .thenThrow(new IllegalStateException("temporary database failure"))
                .thenReturn(stoppedTask);

        setField(service, "adaptiveLaunchConcurrency", concurrency);
        setField(service, "taskMapper", taskMapper);

        Method runTaskLoop = TaskSchedulerService.class.getDeclaredMethod(
                "runTaskLoop", String.class, SysUserDTO.class, int.class);
        runTaskLoop.setAccessible(true);
        runTaskLoop.invoke(service, "task-2", dto, 1);

        verify(concurrency, times(2)).acquire("unknown|unknown");
        verify(taskMapper, times(2)).selectById("task-2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void finishingOldLoopCannotRemoveReplacementSchedule() throws Exception {
        TaskSchedulerService service = new TaskSchedulerService();
        AdaptiveLaunchConcurrency concurrency = mock(AdaptiveLaunchConcurrency.class);
        AdaptiveLaunchConcurrency.Permit permit = mock(AdaptiveLaunchConcurrency.Permit.class);
        OciCreateTaskMapper taskMapper = mock(OciCreateTaskMapper.class);
        SysUserDTO dto = mock(SysUserDTO.class);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        OciCreateTask stoppedTask = new OciCreateTask();
        stoppedTask.setStatus(TaskStatusEnum.STOPPED.getStatus());
        when(concurrency.acquire(anyString())).thenReturn(permit);
        when(taskMapper.selectById("task-3")).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstStarted.countDown();
                awaitIgnoringInterrupt(releaseFirst);
            } else {
                secondStarted.countDown();
                awaitIgnoringInterrupt(releaseSecond);
            }
            return stoppedTask;
        });

        setField(service, "adaptiveLaunchConcurrency", concurrency);
        setField(service, "taskMapper", taskMapper);
        Method scheduleTask = TaskSchedulerService.class.getDeclaredMethod(
                "scheduleTask", String.class, SysUserDTO.class, int.class);
        scheduleTask.setAccessible(true);

        try {
            scheduleTask.invoke(service, "task-3", dto, 60);
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS), "旧调度未启动");
            Map<String, Object> taskMap = (Map<String, Object>) getField(service, "taskMap");
            Object firstHandle = taskMap.get("task-3");
            Thread firstRunner = (Thread) getField(firstHandle, "runner");

            scheduleTask.invoke(service, "task-3", dto, 60);
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS), "替换调度未启动");
            Object replacementHandle = taskMap.get("task-3");
            assertNotSame(firstHandle, replacementHandle);

            releaseFirst.countDown();
            firstRunner.join(2_000L);
            assertTrue(!firstRunner.isAlive(), "旧调度未结束");
            assertTrue(taskMap.get("task-3") == replacementHandle,
                    "旧调度结束时错误删除了替换调度");
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
