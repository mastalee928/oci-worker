package com.ociworker.webssh;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class WebSshUploadRegistry {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public void track(String id) {
        if (id != null && !id.isBlank()) {
            counters.put(id, new AtomicLong(0));
        }
    }

    public void add(String id, int bytes) {
        if (id == null) {
            return;
        }
        AtomicLong c = counters.get(id);
        if (c != null) {
            c.addAndGet(bytes);
        }
    }

    public long getAndRemove(String id) {
        AtomicLong c = counters.remove(id);
        return c != null ? c.get() : -1;
    }

    public Long peek(String id) {
        AtomicLong c = counters.get(id);
        return c != null ? c.get() : null;
    }

    public void remove(String id) {
        if (id != null) {
            counters.remove(id);
        }
    }
}
