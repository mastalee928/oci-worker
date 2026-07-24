package com.ociworker.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class TaskSuccessNotificationOrderTest {

    @Test
    void sendsOutOfOrderCallbacksInOrdinalOrderAndDeduplicates() throws Exception {
        TaskSchedulerService service = new TaskSchedulerService();
        com.ociworker.service.NotificationService notificationService = mock(NotificationService.class);
        setField(service, "notificationService", notificationService);

        List<String> messages = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch delivered = new CountDownLatch(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        doAnswer(invocation -> {
            String message = invocation.getArgument(1, String.class);
            if ("1/2".equals(message)) {
                firstStarted.countDown();
                releaseFirst.await(2, TimeUnit.SECONDS);
            }
            messages.add(message);
            delivered.countDown();
            return null;
        }).when(notificationService).sendHtmlWithType(anyString(), anyString());

        Method enqueue = TaskSchedulerService.class.getDeclaredMethod(
                "sendTaskSuccessNotificationAsync",
                String.class, int.class, int.class, String.class, String.class);
        enqueue.setAccessible(true);

        // Simulate the second OCI callback reaching the scheduler first.
        enqueue.invoke(service, "task-order", 2, 2, NotificationService.TYPE_TASK_RESULT, "2/2");
        enqueue.invoke(service, "task-order", 1, 2, NotificationService.TYPE_TASK_RESULT, "1/2");

        assertTrue(firstStarted.await(1, TimeUnit.SECONDS), "1/2 通知未开始发送");
        // Retry both callbacks while ordinal 1 is in flight. Neither duplicate may
        // remain pending and reappear after the original notifications complete.
        enqueue.invoke(service, "task-order", 1, 2, NotificationService.TYPE_TASK_RESULT, "1/2-duplicate");
        enqueue.invoke(service, "task-order", 2, 2, NotificationService.TYPE_TASK_RESULT, "2/2-duplicate");
        releaseFirst.countDown();

        assertTrue(delivered.await(2, TimeUnit.SECONDS), "成功通知未按预期发送完");
        assertEquals(List.of("1/2", "2/2"), messages);
        assertTrue(waitForQueueEmpty(service), "重复序号残留在成功通知队列中");
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

    private static boolean waitForQueueEmpty(TaskSchedulerService service) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        do {
            Map<?, ?> queues = (Map<?, ?>) getField(service, "successNotificationQueues");
            if (queues.isEmpty()) {
                return true;
            }
            Thread.sleep(10L);
        } while (System.nanoTime() < deadline);
        return false;
    }
}
