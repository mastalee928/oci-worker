package com.ociworker.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AdaptiveLaunchConcurrencyTest {

    @Test
    void springCreatesBeanUsingDataSourceConstructor() {
        DataSource dataSource = mock(DataSource.class);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> dataSource);
            context.register(AdaptiveLaunchConcurrency.class);
            context.refresh();

            AdaptiveLaunchConcurrency bean = context.getBean(AdaptiveLaunchConcurrency.class);
            assertTrue(bean != null);
        }
    }

    @Test
    void fourCoreMachineStartsAtEightAndBurstsToTwelve() {
        AdaptiveLaunchConcurrency.LaunchLimits limits =
                AdaptiveLaunchConcurrency.calculateLimits(4, 20);

        assertEquals(8, limits.base());
        assertEquals(12, limits.burst());
    }

    @Test
    void databaseSafetyLimitCapsBothConcurrencyLevels() {
        AdaptiveLaunchConcurrency.LaunchLimits limits =
                AdaptiveLaunchConcurrency.calculateLimits(16, 6);

        assertEquals(6, limits.base());
        assertEquals(6, limits.burst());
    }

    @Test
    void attemptGateCapsDatabasePreflightConcurrency() throws Exception {
        AdaptiveLaunchConcurrency gate = new AdaptiveLaunchConcurrency(mock(DataSource.class), 8, 8);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch firstWaveEntered = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < 30; i++) {
                final String lane = "tenant-" + i + "|region";
                futures.add(executor.submit(() -> {
                    try (AdaptiveLaunchConcurrency.Permit ignored = gate.acquire(lane)) {
                        int current = active.incrementAndGet();
                        try {
                            maximum.accumulateAndGet(current, Math::max);
                            firstWaveEntered.countDown();
                            release.await();
                        } finally {
                            active.decrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }

            assertTrue(firstWaveEntered.await(2, TimeUnit.SECONDS), "首批任务未取得执行许可");
            awaitWaiting(gate, 22);
            assertEquals(8, maximum.get());
        } finally {
            release.countDown();
            for (Future<?> future : futures) {
                future.get(2, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
        }
    }

    @Test
    void rotatesWaitingTasksAcrossTenantLanes() throws Exception {
        AdaptiveLaunchConcurrency gate = new AdaptiveLaunchConcurrency(mock(DataSource.class), 1, 1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch releaseA2 = new CountDownLatch(1);
        CountDownLatch releaseA3 = new CountDownLatch(1);
        CountDownLatch releaseB1 = new CountDownLatch(1);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        try (AdaptiveLaunchConcurrency.Permit first = gate.acquire("tenant-a|region")) {
            Future<?> a2 = submit(gate, executor, "tenant-a|region", "a2", order, releaseA2);
            awaitWaiting(gate, 1);
            Future<?> a3 = submit(gate, executor, "tenant-a|region", "a3", order, releaseA3);
            awaitWaiting(gate, 2);
            Future<?> b1 = submit(gate, executor, "tenant-b|region", "b1", order, releaseB1);
            awaitWaiting(gate, 3);

            first.close();
            awaitOrderSize(order, 1);
            assertEquals("a2", order.get(0));

            releaseA2.countDown();
            awaitOrderSize(order, 2);
            assertEquals("b1", order.get(1));

            releaseB1.countDown();
            awaitOrderSize(order, 3);
            assertEquals("a3", order.get(2));

            releaseA3.countDown();
            a2.get();
            a3.get();
            b1.get();
        } finally {
            releaseA2.countDown();
            releaseA3.countDown();
            releaseB1.countDown();
            executor.shutdownNow();
        }
    }

    private static Future<?> submit(AdaptiveLaunchConcurrency gate, ExecutorService executor,
                                    String lane, String name, List<String> order, CountDownLatch release) {
        return executor.submit(() -> {
            try (AdaptiveLaunchConcurrency.Permit ignored = gate.acquire(lane)) {
                order.add(name);
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static void awaitWaiting(AdaptiveLaunchConcurrency gate, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline && gate.waitingForTest() < expected) {
            Thread.sleep(5L);
        }
        assertTrue(gate.waitingForTest() >= expected, "等待队列未达到预期大小");
    }

    private static void awaitOrderSize(List<String> order, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline && order.size() < expected) {
            Thread.sleep(5L);
        }
        assertTrue(order.size() >= expected, "任务未按时取得执行位置");
    }
}
