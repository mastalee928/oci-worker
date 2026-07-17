package com.ociworker.service;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Zero-configuration, tenant-fair concurrency controller for OCI LaunchInstance calls.
 * Due tasks are rotated by tenant/region lane instead of one busy tenant occupying the queue.
 */
@Slf4j
@Component
public class AdaptiveLaunchConcurrency {

    private static final long SCALE_UP_QUEUE_MILLIS = 1_000L;
    private static final long SCALE_DOWN_IDLE_MILLIS = 30_000L;
    private static final double CPU_HIGH = 0.90d;
    private static final double CPU_SCALE_UP_MAX = 0.75d;
    private static final double HEAP_HIGH = 0.90d;
    private static final double HEAP_SCALE_UP_MAX = 0.80d;

    private final DataSource dataSource;
    private final int baseLimit;
    private final int burstLimit;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition capacityChanged = lock.newCondition();
    private final Map<String, ArrayDeque<Waiter>> laneQueues = new LinkedHashMap<>();
    private final ArrayDeque<String> laneRotation = new ArrayDeque<>();
    private final AtomicLong lastQueueActivity = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastDatabasePressureWarning = new AtomicLong();
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    private int currentLimit;
    private int active;

    @Autowired
    public AdaptiveLaunchConcurrency(DataSource dataSource) {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        int base;
        int burst;
        if (processors <= 2) {
            base = 8;
            burst = 16;
        } else if (processors <= 4) {
            base = 16;
            burst = 32;
        } else {
            base = 32;
            burst = 64;
        }
        this.dataSource = dataSource;
        baseLimit = base;
        burstLimit = burst;
        currentLimit = baseLimit;
        log.info("OCI launch concurrency initialized: processors={} base={} burst={}",
                processors, baseLimit, burstLimit);
    }

    AdaptiveLaunchConcurrency(DataSource dataSource, int baseLimit, int burstLimit) {
        this.dataSource = dataSource;
        this.baseLimit = Math.max(1, baseLimit);
        this.burstLimit = Math.max(this.baseLimit, burstLimit);
        this.currentLimit = this.baseLimit;
    }

    public Permit acquire(String lane) throws InterruptedException {
        String normalizedLane = lane == null || lane.isBlank() ? "unknown" : lane;
        Waiter waiter = new Waiter(normalizedLane, System.currentTimeMillis());
        lock.lockInterruptibly();
        try {
            ArrayDeque<Waiter> queue = laneQueues.computeIfAbsent(normalizedLane, key -> {
                laneRotation.addLast(key);
                return new ArrayDeque<>();
            });
            queue.addLast(waiter);
            lastQueueActivity.set(System.currentTimeMillis());
            capacityChanged.signalAll();
            while (!canGrant(waiter)) {
                capacityChanged.await();
            }
            grant(waiter);
            active++;
            if (active < currentLimit && !laneRotation.isEmpty()) {
                capacityChanged.signalAll();
            }
            return new Permit(this);
        } catch (InterruptedException e) {
            removeWaiter(waiter);
            capacityChanged.signalAll();
            throw e;
        } finally {
            lock.unlock();
        }
    }

    private boolean canGrant(Waiter waiter) {
        if (active >= currentLimit || laneRotation.isEmpty()) return false;
        String nextLane = laneRotation.peekFirst();
        ArrayDeque<Waiter> queue = laneQueues.get(waiter.lane);
        return waiter.lane.equals(nextLane) && queue != null && queue.peekFirst() == waiter;
    }

    private void grant(Waiter waiter) {
        ArrayDeque<Waiter> queue = laneQueues.get(waiter.lane);
        if (queue == null || queue.pollFirst() != waiter) {
            throw new IllegalStateException("OCI launch fairness queue corrupted");
        }
        laneRotation.removeFirstOccurrence(waiter.lane);
        if (queue.isEmpty()) {
            laneQueues.remove(waiter.lane);
        } else {
            laneRotation.addLast(waiter.lane);
        }
        if (laneRotation.isEmpty()) {
            lastQueueActivity.set(System.currentTimeMillis());
        }
    }

    private void removeWaiter(Waiter waiter) {
        ArrayDeque<Waiter> queue = laneQueues.get(waiter.lane);
        if (queue == null) return;
        queue.remove(waiter);
        if (queue.isEmpty()) {
            laneQueues.remove(waiter.lane);
            laneRotation.removeFirstOccurrence(waiter.lane);
        }
    }

    private void release() {
        lock.lock();
        try {
            if (active > 0) active--;
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Scheduled(fixedDelay = 2_000, initialDelay = 10_000)
    public void tune() {
        QueueSnapshot queue = queueSnapshot();
        double cpu = systemCpuLoad();
        double heap = heapUsage();
        int databaseWaiting = databaseWaitingThreads();
        boolean resourcePressure = cpu >= CPU_HIGH || heap >= HEAP_HIGH || databaseWaiting > 0;
        boolean canGrow = queue.waiting > 0 && queue.oldestWaitMillis >= SCALE_UP_QUEUE_MILLIS
                && cpu < CPU_SCALE_UP_MAX && heap < HEAP_SCALE_UP_MAX && databaseWaiting == 0;

        if (canGrow && queue.limit < burstLimit) {
            resize(Math.min(burstLimit, queue.limit + Math.max(2, queue.limit / 4)),
                    "queue waiting " + queue.oldestWaitMillis + "ms");
        } else {
            boolean idleLongEnough = queue.waiting == 0
                    && System.currentTimeMillis() - lastQueueActivity.get() >= SCALE_DOWN_IDLE_MILLIS
                    && queue.active <= Math.max(1, baseLimit / 2);
            if (queue.limit > baseLimit && (resourcePressure || idleLongEnough)) {
                resize(Math.max(baseLimit, queue.limit - Math.max(2, queue.limit / 4)),
                        resourcePressure ? "resource pressure" : "queue idle");
            }
        }

        long now = System.currentTimeMillis();
        long previousWarning = lastDatabasePressureWarning.get();
        if (databaseWaiting > 0 && now - previousWarning >= 30_000L
                && lastDatabasePressureWarning.compareAndSet(previousWarning, now)) {
            log.warn("Database pool pressure while OCI tasks run: dbWaiting={} launchActive={} launchWaiting={} limit={}",
                    databaseWaiting, queue.active, queue.waiting, queue.limit);
        }
    }

    private QueueSnapshot queueSnapshot() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            int waiting = 0;
            long oldest = 0L;
            for (ArrayDeque<Waiter> queue : laneQueues.values()) {
                waiting += queue.size();
                for (Waiter waiter : queue) {
                    oldest = Math.max(oldest, now - waiter.startedAt);
                }
            }
            return new QueueSnapshot(active, waiting, oldest, currentLimit);
        } finally {
            lock.unlock();
        }
    }

    int waitingForTest() {
        return queueSnapshot().waiting;
    }

    private void resize(int next, String reason) {
        lock.lock();
        try {
            if (next == currentLimit) return;
            int previous = currentLimit;
            currentLimit = next;
            capacityChanged.signalAll();
            log.info("OCI launch concurrency adjusted: {} -> {} ({})", previous, next, reason);
        } finally {
            lock.unlock();
        }
    }

    private int databaseWaitingThreads() {
        try {
            HikariDataSource hikari = dataSource instanceof HikariDataSource h
                    ? h : dataSource.unwrap(HikariDataSource.class);
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            return pool != null ? pool.getThreadsAwaitingConnection() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private double heapUsage() {
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        return heap.getMax() > 0 ? (double) heap.getUsed() / heap.getMax() : 0d;
    }

    private static double systemCpuLoad() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
            double value = sun.getCpuLoad();
            return value >= 0 ? value : 0d;
        }
        return 0d;
    }

    public static final class Permit implements AutoCloseable {
        private final AdaptiveLaunchConcurrency owner;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(AdaptiveLaunchConcurrency owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release();
            }
        }
    }

    private static final class Waiter {
        private final String lane;
        private final long startedAt;

        private Waiter(String lane, long startedAt) {
            this.lane = lane;
            this.startedAt = startedAt;
        }
    }

    private record QueueSnapshot(int active, int waiting, long oldestWaitMillis, int limit) {
    }
}
