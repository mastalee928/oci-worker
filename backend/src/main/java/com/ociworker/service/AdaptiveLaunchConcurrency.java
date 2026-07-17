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
    private static final long RESOURCE_PRESSURE_COOLDOWN_MILLIS = 60_000L;
    private static final double CPU_HIGH = 0.90d;
    private static final double CPU_SCALE_UP_MAX = 0.75d;
    private static final double HEAP_HIGH = 0.90d;
    private static final double HEAP_SCALE_UP_MAX = 0.80d;
    private static final int MIN_DATABASE_CONNECTION_RESERVE = 6;

    private final DataSource dataSource;
    private final int baseLimit;
    private final int burstLimit;
    private final int databaseReserve;
    private final int databaseSafeLimit;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition capacityChanged = lock.newCondition();
    private final Map<String, ArrayDeque<Waiter>> laneQueues = new LinkedHashMap<>();
    private final ArrayDeque<String> laneRotation = new ArrayDeque<>();
    private final AtomicLong lastQueueActivity = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastDatabasePressureWarning = new AtomicLong();
    private final AtomicLong lastResourcePressure = new AtomicLong(Long.MIN_VALUE);
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    private int currentLimit;
    private int active;

    @Autowired
    public AdaptiveLaunchConcurrency(DataSource dataSource) {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        DatabaseCapacity databaseCapacity = databaseCapacity(dataSource);
        LaunchLimits limits = calculateLimits(processors, databaseCapacity.safeConcurrency);
        this.dataSource = dataSource;
        databaseReserve = databaseCapacity.reserve;
        databaseSafeLimit = databaseCapacity.safeConcurrency;
        baseLimit = limits.base;
        burstLimit = limits.burst;
        currentLimit = baseLimit;
        log.info("OCI launch concurrency initialized: processors={} base={} burst={} dbMax={} dbReserve={}",
                processors, baseLimit, burstLimit, databaseCapacity.maximumPoolSize, databaseReserve);
    }

    AdaptiveLaunchConcurrency(DataSource dataSource, int baseLimit, int burstLimit) {
        this.dataSource = dataSource;
        this.baseLimit = Math.max(1, baseLimit);
        this.burstLimit = Math.max(this.baseLimit, burstLimit);
        this.databaseReserve = 0;
        this.databaseSafeLimit = this.burstLimit;
        this.currentLimit = this.baseLimit;
    }

    static LaunchLimits calculateLimits(int processors, int databaseSafeLimit) {
        int normalizedProcessors = Math.max(1, processors);
        int normalizedDatabaseLimit = Math.max(1, databaseSafeLimit);
        int cpuBase = Math.max(4, Math.min(16, normalizedProcessors * 2));
        int cpuBurst = Math.max(cpuBase,
                Math.min(24, cpuBase + Math.max(2, normalizedProcessors)));
        int base = Math.max(1, Math.min(cpuBase, normalizedDatabaseLimit));
        int burst = Math.max(base, Math.min(cpuBurst, normalizedDatabaseLimit));
        return new LaunchLimits(base, burst);
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
        long now = System.currentTimeMillis();
        double cpu = systemCpuLoad();
        double heap = heapUsage();
        DatabaseSnapshot database = databaseSnapshot();
        boolean databasePressure = database.waiting > 0
                || (database.maximumPoolSize > 0
                && database.active > databaseSafeLimit
                && database.idle < databaseReserve);
        boolean resourcePressure = cpu >= CPU_HIGH || heap >= HEAP_HIGH || databasePressure;
        if (resourcePressure) {
            lastResourcePressure.set(now);
        }
        boolean pressureCooldownElapsed = elapsedSince(now, lastResourcePressure.get())
                >= RESOURCE_PRESSURE_COOLDOWN_MILLIS;
        boolean canGrow = queue.waiting > 0 && queue.oldestWaitMillis >= SCALE_UP_QUEUE_MILLIS
                && cpu < CPU_SCALE_UP_MAX && heap < HEAP_SCALE_UP_MAX
                && !databasePressure && pressureCooldownElapsed;

        if (resourcePressure && queue.limit > baseLimit) {
            resize(baseLimit, databasePressure ? "database pressure" : "resource pressure");
        } else if (canGrow && queue.limit < burstLimit) {
            resize(Math.min(burstLimit, queue.limit + 2),
                    "queue waiting " + queue.oldestWaitMillis + "ms");
        } else {
            boolean idleLongEnough = queue.waiting == 0
                    && now - lastQueueActivity.get() >= SCALE_DOWN_IDLE_MILLIS
                    && queue.active <= Math.max(1, baseLimit / 2);
            if (queue.limit > baseLimit && idleLongEnough) {
                resize(baseLimit, "queue idle");
            }
        }

        long previousWarning = lastDatabasePressureWarning.get();
        if (databasePressure && now - previousWarning >= 30_000L
                && lastDatabasePressureWarning.compareAndSet(previousWarning, now)) {
            log.warn("Database pool pressure while OCI tasks run: dbActive={} dbIdle={} dbTotal={} dbMax={} "
                            + "dbWaiting={} launchActive={} launchWaiting={} limit={}",
                    database.active, database.idle, database.total, database.maximumPoolSize, database.waiting,
                    queue.active, queue.waiting, queue.limit);
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

    private DatabaseSnapshot databaseSnapshot() {
        try {
            HikariDataSource hikari = unwrapHikari(dataSource);
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            if (pool == null) return DatabaseSnapshot.unavailable();
            return new DatabaseSnapshot(
                    pool.getActiveConnections(),
                    pool.getIdleConnections(),
                    pool.getTotalConnections(),
                    pool.getThreadsAwaitingConnection(),
                    hikari.getMaximumPoolSize());
        } catch (Exception ignored) {
            return DatabaseSnapshot.unavailable();
        }
    }

    private static DatabaseCapacity databaseCapacity(DataSource dataSource) {
        try {
            int maximumPoolSize = Math.max(1, unwrapHikari(dataSource).getMaximumPoolSize());
            int reserve = Math.min(maximumPoolSize - 1,
                    Math.max(MIN_DATABASE_CONNECTION_RESERVE, (maximumPoolSize + 2) / 3));
            int safeConcurrency = Math.max(1, maximumPoolSize - Math.max(0, reserve));
            return new DatabaseCapacity(maximumPoolSize, Math.max(0, reserve), safeConcurrency);
        } catch (Exception ignored) {
            return new DatabaseCapacity(0, 0, Integer.MAX_VALUE);
        }
    }

    private static HikariDataSource unwrapHikari(DataSource dataSource) throws Exception {
        return dataSource instanceof HikariDataSource hikari
                ? hikari : dataSource.unwrap(HikariDataSource.class);
    }

    private static long elapsedSince(long now, long timestamp) {
        if (timestamp == Long.MIN_VALUE) return Long.MAX_VALUE;
        return Math.max(0L, now - timestamp);
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

    private record DatabaseCapacity(int maximumPoolSize, int reserve, int safeConcurrency) {
    }

    record LaunchLimits(int base, int burst) {
    }

    private record DatabaseSnapshot(int active, int idle, int total, int waiting, int maximumPoolSize) {
        private static DatabaseSnapshot unavailable() {
            return new DatabaseSnapshot(0, 0, 0, 0, 0);
        }
    }
}
