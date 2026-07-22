package com.ociworker.webssh;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class WebSshTerminalWebSocketHandler implements WebSocketHandler {

    static final int MAX_INITIAL_MESSAGE_SIZE = 512 * 1024;
    static final int MIN_COLUMNS = 20;
    static final int MAX_COLUMNS = 500;
    static final int MIN_ROWS = 5;
    static final int MAX_ROWS = 200;

    private static final String ATTR_STARTED = "started";
    private static final String ATTR_FIRST_MESSAGE_CLAIMED = "websshFirstMessageClaimed";
    private static final String ATTR_FIRST_MESSAGE_TIMEOUT = "websshFirstMessageTimeout";
    private static final String ATTR_SEND_SESSION = "websshSendSession";
    private static final String ATTR_SLOT_STATE = "websshSlotState";
    private static final String ATTR_CLOSING = "websshClosing";
    private static final String ATTR_READER = "reader";
    private static final String ATTR_SSH_SESSION_ID = "sshSessionId";

    private final ExecutorService ioPool;
    private final ScheduledExecutorService firstMessageScheduler;
    private final AtomicInteger activeSessions = new AtomicInteger();
    private final Set<WebSocketSession> activeWebSockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    @Resource
    private WebSshSessionRegistry sessionRegistry = new WebSshSessionRegistry();

    @Value("${webssh.timeout-minutes:120}")
    private int timeoutMinutes = 120;

    @Value("${webssh.first-message-timeout-seconds:20}")
    private int firstMessageTimeoutSeconds = 20;

    @Value("${webssh.max-concurrent-sessions:64}")
    private int maxConcurrentSessions = 64;

    @Value("${webssh.max-input-message-size:65536}")
    private int maxInputMessageSize = 65_536;

    @Value("${webssh.send-time-limit-ms:10000}")
    private int sendTimeLimitMs = 10_000;

    @Value("${webssh.send-buffer-bytes:1048576}")
    private int sendBufferBytes = 1_048_576;

    public WebSshTerminalWebSocketHandler() {
        this(
                Executors.newVirtualThreadPerTaskExecutor(),
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon(true).name("webssh-first-message-timeout").factory())
        );
    }

    WebSshTerminalWebSocketHandler(ExecutorService ioPool, ScheduledExecutorService firstMessageScheduler) {
        this.ioPool = Objects.requireNonNull(ioPool, "ioPool");
        this.firstMessageScheduler = Objects.requireNonNull(firstMessageScheduler, "firstMessageScheduler");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.getAttributes().putIfAbsent(ATTR_CLOSING, new AtomicBoolean(false));
        if (shuttingDown.get()) {
            markClosing(session);
            closeWebSocket(session, CloseStatus.SERVICE_RESTARTED.withReason("WebSSH is stopping"));
            return;
        }
        if (!acquireSessionSlot(session)) {
            markClosing(session);
            closeWebSocket(session, CloseStatus.SERVICE_OVERLOAD.withReason("Too many WebSSH sessions"));
            return;
        }
        if (shuttingDown.get()) {
            closeAndCleanup(session, CloseStatus.SERVICE_RESTARTED.withReason("WebSSH is stopping"));
            return;
        }

        session.getAttributes().put(ATTR_SEND_SESSION, new ConcurrentWebSocketSessionDecorator(
                session, Math.max(1, sendTimeLimitMs), Math.max(1, sendBufferBytes)));
        AtomicBoolean firstMessageClaimed = new AtomicBoolean(false);
        session.getAttributes().put(ATTR_FIRST_MESSAGE_CLAIMED, firstMessageClaimed);

        try {
            ScheduledFuture<?> timeout = firstMessageScheduler.schedule(
                    () -> handleFirstMessageTimeout(session, firstMessageClaimed),
                    Math.max(1, firstMessageTimeoutSeconds), TimeUnit.SECONDS);
            registerFuture(session, ATTR_FIRST_MESSAGE_TIMEOUT, timeout, false);
        } catch (RejectedExecutionException e) {
            log.debug("SSH first-message timer rejected: {}", e.getMessage());
            closeAndCleanup(session, CloseStatus.SERVICE_RESTARTED.withReason("WebSSH is stopping"));
        }
    }

    @Override
    public void handleMessage(WebSocketSession ws, WebSocketMessage<?> message) throws Exception {
        if (isClosing(ws) || !ws.isOpen()) {
            return;
        }
        if (!(message instanceof TextMessage textMessage)) {
            CloseStatus status = message.getPayloadLength() > MAX_INITIAL_MESSAGE_SIZE
                    ? CloseStatus.TOO_BIG_TO_PROCESS
                    : CloseStatus.BAD_DATA;
            closeAndCleanup(ws, status.withReason("WebSSH requires text messages"));
            return;
        }
        String payload = textMessage.getPayload();
        if (ws.getAttributes().containsKey(ATTR_STARTED)) {
            if (exceedsUtf8Limit(payload, Math.max(1, maxInputMessageSize))) {
                closeAndCleanup(ws, CloseStatus.TOO_BIG_TO_PROCESS.withReason("WebSSH input is too large"));
                return;
            }
            handleTerminalInput(ws, payload);
            return;
        }

        AtomicBoolean firstMessageClaimed = firstMessageClaimed(ws);
        if (firstMessageClaimed != null && !firstMessageClaimed.compareAndSet(false, true)) {
            return;
        }
        cancelFirstMessageTimeout(ws);

        if (exceedsUtf8Limit(payload, MAX_INITIAL_MESSAGE_SIZE)) {
            closeAndCleanup(ws, CloseStatus.TOO_BIG_TO_PROCESS.withReason("SSH connection info is too large"));
            return;
        }
        ws.getAttributes().put(ATTR_STARTED, Boolean.TRUE);
        startTerminal(ws, payload);
    }

    private void startTerminal(WebSocketSession ws, String sshInfoB64) {
        int cols = normalizeColumns(parseQueryInt(ws, "cols", 150));
        int rows = normalizeRows(parseQueryInt(ws, "rows", 35));
        String closeTip = parseQuery(ws, "closeTip", "Connection timed out!");

        try {
            Future<?> readerFuture = ioPool.submit(() -> {
                Session session = null;
                ChannelShell shell = null;
                boolean sessionRegistered = false;
                boolean shellPublished = false;
                CloseStatus closeStatus = CloseStatus.NORMAL;
                try {
                    WebSshConnectInfo info = WebSshConnectInfoParser.parse(sshInfoB64);
                    session = WebSshJschSupport.openSession(info);
                    String sessionId = publishSession(ws, session, info.getUsername());
                    if (sessionId == null) {
                        return;
                    }
                    sessionRegistered = true;
                    shell = WebSshJschSupport.openShell(session, cols, rows);
                    shellPublished = publishShell(ws, shell);
                    if (!shellPublished) {
                        return;
                    }
                    sendText(ws, WebSshSessionRegistry.controlMessage(sessionId));

                    Reader stdout = new InputStreamReader(
                            WebSshJschSupport.shellOutput(shell), StandardCharsets.UTF_8);
                    char[] buf = new char[4096];
                    long deadline = System.nanoTime() + Duration.ofMinutes(Math.max(1, timeoutMinutes)).toNanos();
                    while (ws.isOpen() && shell.isConnected()) {
                        if (System.nanoTime() > deadline) {
                            sendText(ws, "\033[33m" + closeTip + "\033[0m");
                            closeStatus = CloseStatus.NORMAL.withReason("WebSSH session timed out");
                            break;
                        }
                        boolean timedOutWhileDraining = false;
                        while (stdout.ready()) {
                            if (System.nanoTime() > deadline) {
                                timedOutWhileDraining = true;
                                break;
                            }
                            int n = stdout.read(buf);
                            if (n > 0) {
                                sendText(ws, new String(buf, 0, n));
                            }
                        }
                        if (timedOutWhileDraining) {
                            sendText(ws, "\033[33m" + closeTip + "\033[0m");
                            closeStatus = CloseStatus.NORMAL.withReason("WebSSH session timed out");
                            break;
                        }
                        Thread.sleep(50);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.debug("SSH terminal error: {}", e.getMessage());
                    closeStatus = CloseStatus.NORMAL.withReason("WebSSH session ended");
                    try {
                        sendText(ws, "\033[31m" + safeMessage(e) + "\033[0m");
                    } catch (Exception sendError) {
                        log.debug("Failed to send SSH terminal error: {}", sendError.getMessage());
                    }
                } finally {
                    markClosing(ws);
                    closeSsh(ws);
                    if (!sessionRegistered) {
                        WebSshJschSupport.closeQuietly(session, shell);
                    } else if (!shellPublished) {
                        WebSshJschSupport.closeQuietly(null, shell);
                    }
                    releaseSessionSlot(ws);
                    closeWebSocket(ws, closeStatus);
                }
            });
            registerFuture(ws, ATTR_READER, readerFuture, true);
        } catch (RejectedExecutionException e) {
            log.debug("SSH terminal task rejected: {}", e.getMessage());
            closeAndCleanup(ws, CloseStatus.SERVICE_RESTARTED.withReason("WebSSH is stopping"));
        }
    }

    private void handleTerminalInput(WebSocketSession ws, String payload) throws Exception {
        if ("ping".equals(payload)) {
            return;
        }
        if (payload.startsWith("resize:")) {
            String[] parts = payload.split(":");
            if (parts.length >= 3) {
                try {
                    int rows = normalizeRows(Integer.parseInt(parts[1]));
                    int cols = normalizeColumns(Integer.parseInt(parts[2]));
                    Object shellObj = ws.getAttributes().get("shell");
                    if (shellObj instanceof ChannelShell shell) {
                        WebSshJschSupport.resizeShell(shell, cols, rows);
                    }
                } catch (NumberFormatException e) {
                    log.trace("Ignoring invalid SSH resize message: {}", payload);
                }
            }
            return;
        }
        Object stdinObj = ws.getAttributes().get("stdin");
        if (stdinObj instanceof OutputStream stdin) {
            stdin.write(payload.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }
    }

    static int normalizeColumns(int columns) {
        return clamp(columns, MIN_COLUMNS, MAX_COLUMNS);
    }

    static int normalizeRows(int rows) {
        return clamp(rows, MIN_ROWS, MAX_ROWS);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int parseQueryInt(WebSocketSession ws, String key, int def) {
        String v = parseQuery(ws, key, null);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String parseQuery(WebSocketSession ws, String key, String def) {
        if (ws.getUri() == null || ws.getUri().getQuery() == null) {
            return def;
        }
        for (String part : ws.getUri().getQuery().split("&")) {
            int i = part.indexOf('=');
            if (i > 0 && key.equals(part.substring(0, i))) {
                return part.substring(i + 1);
            }
        }
        return def;
    }

    private boolean acquireSessionSlot(WebSocketSession ws) {
        int limit = Math.max(1, maxConcurrentSessions);
        SlotState slot = new SlotState();
        ws.getAttributes().put(ATTR_SLOT_STATE, slot);
        activeWebSockets.add(ws);
        while (true) {
            int current = activeSessions.get();
            if (current >= limit) {
                slot.state.compareAndSet(SlotState.NEW, SlotState.RELEASED);
                activeWebSockets.remove(ws);
                ws.getAttributes().remove(ATTR_SLOT_STATE, slot);
                return false;
            }
            if (activeSessions.compareAndSet(current, current + 1)) {
                if (slot.state.compareAndSet(SlotState.NEW, SlotState.ACQUIRED)) {
                    return true;
                }
                activeSessions.decrementAndGet();
                activeWebSockets.remove(ws);
                return false;
            }
        }
    }

    private void releaseSessionSlot(WebSocketSession ws) {
        Object marker = ws.getAttributes().get(ATTR_SLOT_STATE);
        if (marker instanceof SlotState slot) {
            if (slot.state.compareAndSet(SlotState.NEW, SlotState.RELEASED)) {
                // The connection closed before its slot acquisition completed.
            } else if (slot.state.compareAndSet(SlotState.ACQUIRED, SlotState.RELEASED)) {
                activeSessions.decrementAndGet();
            }
        }
        activeWebSockets.remove(ws);
    }

    private static final class SlotState {
        private static final int NEW = 0;
        private static final int ACQUIRED = 1;
        private static final int RELEASED = 2;

        private final AtomicInteger state = new AtomicInteger(NEW);
    }

    private void handleFirstMessageTimeout(WebSocketSession ws, AtomicBoolean firstMessageClaimed) {
        if (!firstMessageClaimed.compareAndSet(false, true)) {
            return;
        }
        log.debug("SSH ws first message timed out: {}", ws.getId());
        closeAndCleanup(ws, CloseStatus.NORMAL.withReason("WebSSH first message timed out"));
    }

    private static AtomicBoolean firstMessageClaimed(WebSocketSession ws) {
        Object value = ws.getAttributes().get(ATTR_FIRST_MESSAGE_CLAIMED);
        return value instanceof AtomicBoolean claimed ? claimed : null;
    }

    private static boolean exceedsUtf8Limit(String value, int limit) {
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            int increment;
            if (ch <= 0x7f) {
                increment = 1;
            } else if (ch <= 0x7ff) {
                increment = 2;
            } else if (Character.isHighSurrogate(ch)
                    && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                increment = 4;
                i++;
            } else {
                increment = 3;
            }
            if (bytes > limit - increment) {
                return true;
            }
            bytes += increment;
        }
        return false;
    }

    private void closeAndCleanup(WebSocketSession ws, CloseStatus status) {
        markClosing(ws);
        cancelFirstMessageTimeout(ws);
        cancelReader(ws);
        closeSsh(ws);
        releaseSessionSlot(ws);
        closeWebSocket(ws, status);
    }

    private static void registerFuture(WebSocketSession ws, String key, Future<?> future,
                                       boolean mayInterruptIfRunning) {
        ws.getAttributes().put(key, future);
        if (isClosing(ws) && ws.getAttributes().remove(key, future)) {
            future.cancel(mayInterruptIfRunning);
        }
    }

    private static void markClosing(WebSocketSession ws) {
        Object marker = ws.getAttributes().get(ATTR_CLOSING);
        if (marker instanceof AtomicBoolean closing) {
            closing.set(true);
        } else {
            ws.getAttributes().put(ATTR_CLOSING, new AtomicBoolean(true));
        }
    }

    private static boolean isClosing(WebSocketSession ws) {
        Object marker = ws.getAttributes().get(ATTR_CLOSING);
        return marker instanceof AtomicBoolean closing && closing.get();
    }

    private static void cancelFirstMessageTimeout(WebSocketSession ws) {
        Object timeout = ws.getAttributes().remove(ATTR_FIRST_MESSAGE_TIMEOUT);
        if (timeout instanceof Future<?> future) {
            future.cancel(false);
        }
    }

    private static void cancelReader(WebSocketSession ws) {
        Object reader = ws.getAttributes().remove(ATTR_READER);
        if (reader instanceof Future<?> future) {
            future.cancel(true);
        }
    }

    private void closeSsh(WebSocketSession ws) {
        Object shellObj;
        Object sessionObj;
        Object sessionIdObj;
        synchronized (ws.getAttributes()) {
            shellObj = ws.getAttributes().remove("shell");
            sessionObj = ws.getAttributes().remove("sshSession");
            sessionIdObj = ws.getAttributes().remove(ATTR_SSH_SESSION_ID);
            ws.getAttributes().remove("stdin");
        }
        ChannelShell shell = shellObj instanceof ChannelShell s ? s : null;
        Session session = sessionObj instanceof Session s ? s : null;
        WebSshJschSupport.closeQuietly(null, shell);
        if (sessionIdObj instanceof String sessionId && !sessionId.isBlank()) {
            sessionRegistry.close(sessionId);
        } else {
            WebSshJschSupport.closeQuietly(session);
        }
    }

    private String publishSession(WebSocketSession ws, Session session, String username) {
        synchronized (ws.getAttributes()) {
            if (isClosing(ws)) {
                return null;
            }
            String sessionId = sessionRegistry.register(session, username);
            ws.getAttributes().put("sshSession", session);
            ws.getAttributes().put(ATTR_SSH_SESSION_ID, sessionId);
            if (isClosing(ws)) {
                ws.getAttributes().remove("sshSession", session);
                ws.getAttributes().remove(ATTR_SSH_SESSION_ID, sessionId);
                sessionRegistry.close(sessionId);
                return null;
            }
            return sessionId;
        }
    }

    private static boolean publishShell(WebSocketSession ws, ChannelShell shell) throws Exception {
        synchronized (ws.getAttributes()) {
            if (isClosing(ws)) {
                return false;
            }
            ws.getAttributes().put("shell", shell);
            try {
                ws.getAttributes().put("stdin", WebSshJschSupport.shellInput(shell));
                return true;
            } catch (Exception e) {
                ws.getAttributes().remove("shell", shell);
                throw e;
            }
        }
    }

    private static void sendText(WebSocketSession ws, String text) throws IOException {
        WebSocketSession sendSession = sendingSession(ws);
        if (sendSession.isOpen()) {
            sendSession.sendMessage(new TextMessage(text));
        }
    }

    private static WebSocketSession sendingSession(WebSocketSession ws) {
        Object sendSession = ws.getAttributes().get(ATTR_SEND_SESSION);
        return sendSession instanceof WebSocketSession decorated ? decorated : ws;
    }

    private static void closeWebSocket(WebSocketSession ws, CloseStatus status) {
        if (!ws.isOpen()) {
            return;
        }
        try {
            ws.close(status);
        } catch (IOException e) {
            log.debug("SSH ws close error: {}", e.getMessage());
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "WebSSH connection failed" : message;
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("SSH ws transport error: {}", exception == null ? "unknown" : exception.getMessage());
        closeAndCleanup(session, CloseStatus.SERVER_ERROR.withReason("WebSSH transport error"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        markClosing(ws);
        cancelFirstMessageTimeout(ws);
        cancelReader(ws);
        closeSsh(ws);
        releaseSessionSlot(ws);
    }

    int activeSessionCount() {
        return activeSessions.get();
    }

    @PreDestroy
    void shutdownExecutors() {
        shuttingDown.set(true);
        for (WebSocketSession ws : activeWebSockets) {
            closeAndCleanup(ws, CloseStatus.SERVICE_RESTARTED.withReason("WebSSH is stopping"));
        }
        firstMessageScheduler.shutdownNow();
        ioPool.shutdownNow();
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }
}
