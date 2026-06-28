package com.ociworker.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Small in-memory cache for expensive OCI read APIs.
 * It stores only normalized read results, never SDK clients or credentials.
 */
@Service
public class OciReadCacheService {

    private static final int MAX_ENTRIES = 1_000;
    private static final int MAX_GENERATION_ENTRIES = MAX_ENTRIES * 2;
    private static final Object NULL_VALUE = new Object();

    private final ConcurrentHashMap<String, CacheEntry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> generations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> activeLoads = new ConcurrentHashMap<>();

    public <T> T get(String key, Duration ttl, boolean force, Supplier<T> loader) {
        Objects.requireNonNull(key, "cache key must not be null");
        Objects.requireNonNull(ttl, "cache ttl must not be null");
        Objects.requireNonNull(loader, "cache loader must not be null");

        long now = System.nanoTime();
        if (!force) {
            CacheEntry hit = entries.get(key);
            if (hit != null && hit.isFresh(now)) {
                hit.touch(now);
                return value(hit);
            }
        }

        Object lock = locks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            now = System.nanoTime();
            if (!force) {
                CacheEntry hit = entries.get(key);
                if (hit != null && hit.isFresh(now)) {
                    hit.touch(now);
                    return value(hit);
                }
            }

            incrementActiveLoad(key);
            try {
                long generationAtStart = generationOf(key);
                T loaded = loader.get();
                long loadedAt = System.nanoTime();
                if (generationOf(key) == generationAtStart) {
                    long expiresAt = loadedAt + Math.max(1L, ttl.toNanos());
                    entries.put(key, new CacheEntry(loaded == null ? NULL_VALUE : loaded, expiresAt, loadedAt));
                    pruneIfNeeded();
                } else if (!entries.containsKey(key)) {
                    locks.remove(key, lock);
                }
                return loaded;
            } catch (RuntimeException | Error e) {
                if (!entries.containsKey(key)) {
                    locks.remove(key, lock);
                }
                throw e;
            } finally {
                decrementActiveLoad(key);
                pruneGenerationsIfNeeded();
            }
        }
    }

    public void evict(String key) {
        if (key != null) {
            bumpGeneration(key);
            entries.remove(key);
            locks.remove(key);
        }
    }

    public void evictByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return;
        }
        List<String> keys = new ArrayList<>(entries.keySet());
        for (String key : locks.keySet()) {
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }
        for (String key : keys) {
            if (key.startsWith(prefix)) {
                bumpGeneration(key);
                entries.remove(key);
                locks.remove(key);
            }
        }
    }

    public static String key(String namespace, Object... parts) {
        StringBuilder sb = new StringBuilder(namespace == null ? "" : namespace);
        if (parts != null) {
            for (Object part : parts) {
                sb.append('|').append(escape(part));
            }
        }
        return sb.toString();
    }

    private static String escape(Object part) {
        if (part == null) {
            return "";
        }
        return String.valueOf(part).replace("\\", "\\\\").replace("|", "\\|");
    }

    private long generationOf(String key) {
        return generations.getOrDefault(key, 0L);
    }

    private void bumpGeneration(String key) {
        generations.merge(key, 1L, Long::sum);
    }

    @SuppressWarnings("unchecked")
    private static <T> T value(CacheEntry entry) {
        Object value = entry.value;
        return value == NULL_VALUE ? null : (T) value;
    }

    private void pruneIfNeeded() {
        int size = entries.size();
        if (size <= MAX_ENTRIES) {
            pruneGenerationsIfNeeded();
            return;
        }

        long now = System.nanoTime();
        for (var item : new ArrayList<>(entries.entrySet())) {
            if (!item.getValue().isFresh(now)) {
                entries.remove(item.getKey(), item.getValue());
                locks.remove(item.getKey());
            }
        }

        int overflow = entries.size() - MAX_ENTRIES;
        if (overflow <= 0) {
            return;
        }

        List<String> oldestKeys = entries.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().lastAccessNanos))
                .limit(overflow)
                .map(java.util.Map.Entry::getKey)
                .toList();
        for (String key : oldestKeys) {
            entries.remove(key);
            locks.remove(key);
        }
        pruneGenerationsIfNeeded();
    }

    private void pruneGenerationsIfNeeded() {
        if (generations.size() <= MAX_GENERATION_ENTRIES) {
            return;
        }
        for (String key : new ArrayList<>(generations.keySet())) {
            AtomicInteger active = activeLoads.get(key);
            if (active == null || active.get() <= 0) {
                generations.remove(key);
                activeLoads.remove(key, active);
            }
            if (generations.size() <= MAX_ENTRIES) {
                break;
            }
        }
    }

    private void incrementActiveLoad(String key) {
        activeLoads.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
    }

    private void decrementActiveLoad(String key) {
        AtomicInteger active = activeLoads.get(key);
        if (active != null && active.decrementAndGet() <= 0) {
            activeLoads.remove(key, active);
        }
    }

    private static final class CacheEntry {
        private final Object value;
        private final long expiresAtNanos;
        private volatile long lastAccessNanos;

        private CacheEntry(Object value, long expiresAtNanos, long lastAccessNanos) {
            this.value = value;
            this.expiresAtNanos = expiresAtNanos;
            this.lastAccessNanos = lastAccessNanos;
        }

        private boolean isFresh(long nowNanos) {
            return nowNanos < expiresAtNanos;
        }

        private void touch(long nowNanos) {
            lastAccessNanos = nowNanos;
        }
    }
}
