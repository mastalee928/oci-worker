package com.ociworker.webssh;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class WebSshUploadProgressWebSocketHandler implements WebSocketHandler {

    private static final long POLL_INTERVAL_MS = 300;
    private static final long WAIT_FOR_UPLOAD_SECONDS = 30;

    private final WebSshUploadRegistry uploadRegistry;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("webssh-upload-progress").factory());
    private final ConcurrentHashMap<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private final Set<WebSocketSession> activeSessions = ConcurrentHashMap.newKeySet();

    public WebSshUploadProgressWebSocketHandler(WebSshUploadRegistry uploadRegistry) {
        this.uploadRegistry = uploadRegistry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String id = parseQuery(session, "id");
        if (!isValidUploadId(id)) {
            close(session, CloseStatus.BAD_DATA.withReason("Invalid upload id"));
            return;
        }

        activeSessions.add(session);
        ProgressState state = new ProgressState(id, System.nanoTime()
                + TimeUnit.SECONDS.toNanos(WAIT_FOR_UPLOAD_SECONDS));
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> pushProgress(session, state), 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        tasks.put(session.getId(), future);
        if (!session.isOpen()) {
            cleanup(session, false, CloseStatus.NORMAL);
        }
    }

    private void pushProgress(WebSocketSession session, ProgressState state) {
        if (!session.isOpen()) {
            cleanup(session, false, CloseStatus.NORMAL);
            return;
        }
        try {
            Long total = uploadRegistry.peek(state.uploadId);
            if (total != null) {
                state.seenUpload = true;
                session.sendMessage(new TextMessage(String.valueOf(total)));
                return;
            }
            if (state.seenUpload) {
                cleanup(session, true, CloseStatus.NORMAL);
            } else if (System.nanoTime() >= state.waitDeadlineNanos) {
                cleanup(session, true, CloseStatus.NORMAL.withReason("Upload not started"));
            }
        } catch (Exception e) {
            log.debug("Upload progress send failed: {}", e.getMessage());
            cleanup(session, true, CloseStatus.SERVER_ERROR.withReason("Upload progress failed"));
        }
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        cleanup(session, true, CloseStatus.BAD_DATA.withReason("Upload progress is server-only"));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Upload progress transport error: {}",
                exception == null ? "unknown" : exception.getMessage());
        cleanup(session, true, CloseStatus.SERVER_ERROR.withReason("Upload progress transport error"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(session, false, status);
    }

    private void cleanup(WebSocketSession session, boolean closeSession, CloseStatus status) {
        ScheduledFuture<?> future = tasks.remove(session.getId());
        if (future != null) {
            future.cancel(false);
        }
        activeSessions.remove(session);
        if (closeSession) {
            close(session, status);
        }
    }

    private static void close(WebSocketSession session, CloseStatus status) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("Upload progress ws close failed: {}", e.getMessage());
        }
    }

    private static boolean isValidUploadId(String id) {
        return id != null && id.length() <= 128 && id.matches("[A-Za-z0-9_-]+");
    }

    private static String parseQuery(WebSocketSession ws, String key) {
        if (ws.getUri() == null || ws.getUri().getQuery() == null) {
            return null;
        }
        for (String part : ws.getUri().getQuery().split("&")) {
            int i = part.indexOf('=');
            if (i > 0 && key.equals(part.substring(0, i))) {
                return URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @PreDestroy
    void shutdown() {
        for (WebSocketSession session : activeSessions) {
            cleanup(session, true, CloseStatus.SERVICE_RESTARTED.withReason("WebSSH is stopping"));
        }
        scheduler.shutdownNow();
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    private static final class ProgressState {
        private final String uploadId;
        private final long waitDeadlineNanos;
        private boolean seenUpload;

        private ProgressState(String uploadId, long waitDeadlineNanos) {
            this.uploadId = uploadId;
            this.waitDeadlineNanos = waitDeadlineNanos;
        }
    }
}
