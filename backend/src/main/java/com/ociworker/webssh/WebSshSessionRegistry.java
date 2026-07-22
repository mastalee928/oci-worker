package com.ociworker.webssh;

import com.jcraft.jsch.Session;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the SSH session opened by a normal WebSSH terminal. HTTP monitoring and
 * SFTP requests borrow channels from this session instead of reconnecting for
 * every request. OCI serial-console sessions never enter this registry.
 */
@Component
public class WebSshSessionRegistry {

    private static final int SESSION_ID_BYTES = 32;
    private static final String CONTROL_PREFIX = "\u001b]777;ociworker-session=";
    private static final String CONTROL_SUFFIX = "\u0007";

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public String register(Session session, String username) {
        if (session == null || !session.isConnected()) {
            throw new IllegalArgumentException("SSH session is not connected");
        }
        String id;
        do {
            byte[] bytes = new byte[SESSION_ID_BYTES];
            random.nextBytes(bytes);
            id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (entries.putIfAbsent(id, new Entry(session, username)) != null);
        return id;
    }

    public <T> T withSession(String id, SessionWork<T> work) throws Exception {
        if (id == null || id.isBlank() || work == null) {
            throw new IllegalArgumentException("SSH session is unavailable");
        }
        Entry entry = entries.get(id);
        if (entry == null || !entry.acquire()) {
            throw new IllegalArgumentException("SSH session has expired");
        }
        try {
            return work.apply(entry.session, entry.username);
        } finally {
            entry.release();
        }
    }

    public void close(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        Entry entry = entries.remove(id);
        if (entry != null) {
            entry.closeWhenIdle();
        }
    }

    static String controlMessage(String id) {
        return CONTROL_PREFIX + id + CONTROL_SUFFIX;
    }

    int size() {
        return entries.size();
    }

    @PreDestroy
    void closeAll() {
        for (String id : entries.keySet()) {
            close(id);
        }
    }

    @FunctionalInterface
    public interface SessionWork<T> {
        T apply(Session session, String username) throws Exception;
    }

    private static final class Entry {
        private final Session session;
        private final String username;
        private int borrowers;
        private boolean closing;

        private Entry(Session session, String username) {
            this.session = session;
            this.username = username == null ? "" : username;
        }

        private synchronized boolean acquire() {
            if (closing || !session.isConnected()) {
                return false;
            }
            borrowers++;
            return true;
        }

        private void release() {
            boolean disconnect = false;
            synchronized (this) {
                if (borrowers > 0) {
                    borrowers--;
                }
                if (closing && borrowers == 0) {
                    disconnect = true;
                }
            }
            if (disconnect) {
                disconnectQuietly(session);
            }
        }

        private void closeWhenIdle() {
            boolean disconnect = false;
            synchronized (this) {
                closing = true;
                if (borrowers == 0) {
                    disconnect = true;
                }
            }
            if (disconnect) {
                disconnectQuietly(session);
            }
        }
    }

    private static void disconnectQuietly(Session session) {
        if (session != null && session.isConnected()) {
            try {
                session.disconnect();
            } catch (Exception ignored) {
                // Cleanup must not mask the request or shutdown path.
            }
        }
    }
}
