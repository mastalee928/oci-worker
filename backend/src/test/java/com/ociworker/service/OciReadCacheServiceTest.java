package com.ociworker.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OciReadCacheServiceTest {

    @Test
    void completedSingleFlightIsRemovedEvenWhenExecutorRunsInline() throws Exception {
        OciReadCacheService cache = new OciReadCacheService();
        Executor directExecutor = Runnable::run;
        AtomicInteger loadCount = new AtomicInteger();

        String first = cache.getSingleFlight(
                "instances|tenant-inline|region-1",
                Duration.ofSeconds(20),
                true,
                System.nanoTime() + Duration.ofSeconds(1).toNanos(),
                directExecutor,
                () -> "loaded-" + loadCount.incrementAndGet());
        String second = cache.getSingleFlight(
                "instances|tenant-inline|region-1",
                Duration.ofSeconds(20),
                true,
                System.nanoTime() + Duration.ofSeconds(1).toNanos(),
                directExecutor,
                () -> "loaded-" + loadCount.incrementAndGet());

        assertThat(first).isEqualTo("loaded-1");
        assertThat(second).isEqualTo("loaded-2");
        assertThat(loadCount.get()).isEqualTo(2);
    }

    @Test
    void evictionDuringSingleFlightPreventsOldLoadFromRepopulatingCache() throws Exception {
        OciReadCacheService cache = new OciReadCacheService();
        ExecutorService loaderExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicInteger loadCount = new AtomicInteger();
        String key = "instances|tenant-evict|region-1";
        try {
            CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> {
                try {
                    return cache.getSingleFlight(
                            key,
                            Duration.ofSeconds(20),
                            false,
                            System.nanoTime() + Duration.ofSeconds(2).toNanos(),
                            loaderExecutor,
                            () -> {
                                loadCount.incrementAndGet();
                                loaderStarted.countDown();
                                try {
                                    releaseLoader.await(2, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(e);
                                }
                                return "old";
                            });
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            assertThat(loaderStarted.await(1, TimeUnit.SECONDS)).isTrue();
            cache.evict(key);
            releaseLoader.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("old");

            String refreshed = cache.getSingleFlight(
                    key,
                    Duration.ofSeconds(20),
                    false,
                    System.nanoTime() + Duration.ofSeconds(1).toNanos(),
                    loaderExecutor,
                    () -> {
                        loadCount.incrementAndGet();
                        return "new";
                    });
            assertThat(refreshed).isEqualTo("new");
            assertThat(loadCount.get()).isEqualTo(2);
        } finally {
            releaseLoader.countDown();
            loaderExecutor.shutdownNow();
        }
    }

    @Test
    void singleFlightSharesLoaderAndAppliesCallerDeadlineWhileWaiting() throws Exception {
        OciReadCacheService cache = new OciReadCacheService();
        ExecutorService loaderExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicInteger loadCount = new AtomicInteger();
        try {
            CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> {
                try {
                    return cache.getSingleFlight(
                            "instances|tenant-1|region-1",
                            Duration.ofSeconds(20),
                            false,
                            System.nanoTime() + Duration.ofSeconds(2).toNanos(),
                            loaderExecutor,
                            () -> {
                                loadCount.incrementAndGet();
                                loaderStarted.countDown();
                                try {
                                    releaseLoader.await(2, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(e);
                                }
                                return "loaded";
                            });
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            assertThat(loaderStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> cache.getSingleFlight(
                    "instances|tenant-1|region-1",
                    Duration.ofSeconds(20),
                    false,
                    System.nanoTime() + Duration.ofMillis(80).toNanos(),
                    loaderExecutor,
                    () -> "duplicate"))
                    .isInstanceOf(TimeoutException.class);

            releaseLoader.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("loaded");
            assertThat(loadCount.get()).isEqualTo(1);
        } finally {
            releaseLoader.countDown();
            loaderExecutor.shutdownNow();
        }
    }
}
