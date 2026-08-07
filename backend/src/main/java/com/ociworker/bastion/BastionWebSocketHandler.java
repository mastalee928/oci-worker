package com.ociworker.bastion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.ChannelShell;
import com.ociworker.config.WebSshAuthHandshakeInterceptor;
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

/** Dedicated terminal transport for OCI Bastion two-hop SSH sessions. */
@Slf4j
@Component
public class BastionWebSocketHandler implements WebSocketHandler {

    static final int MAX_TOKEN_MESSAGE_SIZE = 512;
    static final int MIN_COLUMNS = 20;
    static final int MAX_COLUMNS = 500;
    static final int MIN_ROWS = 5;
    static final int MAX_ROWS = 200;
    static final String CONTROL_PREFIX = "\u001eociworker-bastion:";

    static final String ATTR_STARTED = "bastionStarted";
    static final String ATTR_READER = "bastionReader";
    static final String ATTR_LEASE = "bastionLease";
    static final String ATTR_CONNECTION = "bastionConnection";
    static final String ATTR_SHELL = "bastionShell";
    static final String ATTR_STDIN = "bastionStdin";

    private static final String ATTR_FIRST_MESSAGE_CLAIMED = "bastionFirstMessageClaimed";
    private static final String ATTR_FIRST_MESSAGE_TIMEOUT = "bastionFirstMessageTimeout";
    private static final String ATTR_SEND_SESSION = "bastionSendSession";
    private static final String ATTR_SLOT_STATE = "bastionSlotState";
    private static final String ATTR_CLOSING = "bastionClosing";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExecutorService ioPool;
    private final ScheduledExecutorService firstMessageScheduler;
    private final AtomicInteger activeSessions = new AtomicInteger();
    private final Set<WebSocketSession> activeWebSockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    @Resource
    private BastionService bastionService;

    @Value("${oci.bastion.websocket-timeout-minutes:120}")
    private int timeoutMinutes = 120;

    @Value("${oci.bastion.first-message-timeout-seconds:20}")
    private int firstMessageTimeoutSeconds = 20;

    @Value("${oci.bastion.max-concurrent-sessions:32}")
    private int maxConcurrentSessions = 32;

    @Value("${oci.bastion.max-input-message-size:65536}")
    private int maxInputMessageSize = 65_536;

    @Value("${oci.bastion.send-time-limit-ms:10000}")
    private int sendTimeLimitMs = 10_000;

    @Value("${oci.bastion.send-buffer-bytes:1048576}")
    private int sendBufferBytes = 1_048_576;

    public BastionWebSocketHandler() {
        this(
                Executors.newVirtualThreadPerTaskExecutor(),
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon(true).name("bastion-first-message-timeout").factory())
        );
    }

    BastionWebSocketHandler(ExecutorService ioPool, ScheduledExecutorService firstMessageScheduler) {
        this.ioPool = Objects.requireNonNull(ioPool, "ioPool");
        this.firstMessageScheduler = Objects.requireNonNull(firstMessageScheduler, "firstMessageScheduler");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.getAttributes().putIfAbsent(ATTR_CLOSING, new AtomicBoolean(false));
        if (!hasAuthenticatedAccount(session)) {
            markClosing(session);
            closeWebSocket(session, CloseStatus.POLICY_VIOLATION.withReason("Authentication is required"));
            return;
        }
        if (shuttingDown.get()) {
            markClosing(session);
            closeWebSocket(session, CloseStatus.SERVICE_RESTARTED.withReason("Bastion SSH is stopping"));
            return;
        }
        if (!acquireSessionSlot(session)) {
            markClosing(session);
            closeWebSocket(session, CloseStatus.SERVICE_OVERLOAD.withReason("Too many Bastion SSH sessions"));
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
            closeAndCleanup(session, CloseStatus.SERVICE_RESTARTED.withReason("Bastion SSH is stopping"));
        }
    }

    @Override
    public void handleMessage(WebSocketSession ws, WebSocketMessage<?> message) throws Exception {
        if (isClosing(ws) || !ws.isOpen()) return;
        if (!(message instanceof TextMessage textMessage)) {
            CloseStatus status = message.getPayloadLength() > MAX_TOKEN_MESSAGE_SIZE
                    ? CloseStatus.TOO_BIG_TO_PROCESS : CloseStatus.BAD_DATA;
            closeAndCleanup(ws, status.withReason("Bastion SSH requires text messages"));
            return;
        }

        String payload = textMessage.getPayload();
        if (ws.getAttributes().containsKey(ATTR_STARTED)) {
            if (exceedsUtf8Limit(payload, Math.max(1, maxInputMessageSize))) {
                closeAndCleanup(ws, CloseStatus.TOO_BIG_TO_PROCESS.withReason("Bastion SSH input is too large"));
                return;
            }
            handleTerminalInput(ws, payload);
            return;
        }

        AtomicBoolean claimed = firstMessageClaimed(ws);
        if (claimed != null && !claimed.compareAndSet(false, true)) return;
        cancelFirstMessageTimeout(ws);
        if (exceedsUtf8Limit(payload, MAX_TOKEN_MESSAGE_SIZE)) {
            closeAndCleanup(ws, CloseStatus.TOO_BIG_TO_PROCESS.withReason("Bastion token is too large"));
            return;
        }
        ws.getAttributes().put(ATTR_STARTED, Boolean.TRUE);
        startTerminal(ws, payload.trim());
    }

    private void startTerminal(WebSocketSession ws, String token) {
        int cols = normalizeColumns(parseQueryInt(ws, "cols", 150));
        int rows = normalizeRows(parseQueryInt(ws, "rows", 35));
        String account = authenticatedAccount(ws);

        try {
            Future<?> reader = ioPool.submit(() -> {
                BastionService.BastionLease unownedLease = null;
                BastionJschSupport.BastionSshConnection unownedConnection = null;
                CloseStatus closeStatus = CloseStatus.NORMAL;
                try {
                    unownedLease = bastionService.claim(token, account);
                    BastionConnectionSpec spec = unownedLease.spec();
                    if (!publishLease(ws, unownedLease)) return;
                    unownedLease = null;

                    unownedConnection = BastionJschSupport.open(spec);
                    if (!publishConnection(ws, unownedConnection)) return;
                    BastionJschSupport.BastionSshConnection connection = unownedConnection;
                    unownedConnection = null;

                    BastionJschSupport.ShellChannel shellChannel = BastionJschSupport.openShell(
                            connection.targetSession(), cols, rows);
                    connection.targetShell(shellChannel.shell());
                    if (!publishShell(ws, connection, shellChannel.shell())) return;
                    sendText(ws, controlMessage("ready"));

                    Reader stdout = new InputStreamReader(shellChannel.stdout(), StandardCharsets.UTF_8);
                    char[] buffer = new char[4096];
                    long deadline = System.nanoTime()
                            + Duration.ofMinutes(Math.max(1, timeoutMinutes)).toNanos();
                    while (ws.isOpen() && shellChannel.shell().isConnected()) {
                        if (System.nanoTime() > deadline) {
                            sendText(ws, "\033[33mBastion SSH session timed out.\033[0m");
                            break;
                        }
                        while (stdout.ready()) {
                            int count = stdout.read(buffer);
                            if (count > 0) sendText(ws, new String(buffer, 0, count));
                        }
                        Thread.sleep(50);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.debug("Bastion terminal error: {}", e.getMessage());
                    closeStatus = CloseStatus.NORMAL.withReason("Bastion SSH session ended");
                    try {
                        sendText(ws, "\033[31m" + safeMessage(e) + "\033[0m");
                    } catch (Exception sendError) {
                        log.debug("Failed to send Bastion SSH error: {}", sendError.getMessage());
                    }
                } finally {
                    markClosing(ws);
                    closeResources(ws);
                    BastionJschSupport.closeQuietly(unownedConnection);
                    bastionService.release(unownedLease);
                    releaseSessionSlot(ws);
                    closeWebSocket(ws, closeStatus);
                }
            });
            registerFuture(ws, ATTR_READER, reader, true);
        } catch (RejectedExecutionException e) {
            closeAndCleanup(ws, CloseStatus.SERVICE_RESTARTED.withReason("Bastion SSH is stopping"));
        }
    }

    private void handleTerminalInput(WebSocketSession ws, String payload) throws Exception {
        if (payload.startsWith(CONTROL_PREFIX)) {
            handleControlMessage(ws, payload.substring(CONTROL_PREFIX.length()));
            return;
        }
        Object stdin = ws.getAttributes().get(ATTR_STDIN);
        if (stdin instanceof OutputStream output) {
            output.write(payload.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private void handleControlMessage(WebSocketSession ws, String payload) {
        try {
            JsonNode message = JSON.readTree(payload);
            if (message == null || !message.isObject()) return;
            String type = message.path("type").asText("");
            if ("ping".equals(type)) return;
            if (!"resize".equals(type)) return;
            int rows = normalizeRows(message.path("rows").asInt(35));
            int cols = normalizeColumns(message.path("cols").asInt(150));
            Object shell = ws.getAttributes().get(ATTR_SHELL);
            if (shell instanceof ChannelShell channelShell) {
                BastionJschSupport.resizeShell(channelShell, cols, rows);
            }
        } catch (Exception e) {
            log.trace("Ignoring invalid Bastion control message", e);
        }
    }

    private static String controlMessage(String type) {
        return CONTROL_PREFIX + "{\"type\":\"" + type + "\"}";
    }

    private boolean publishLease(WebSocketSession ws, BastionService.BastionLease lease) {
        synchronized (ws.getAttributes()) {
            if (isClosing(ws)) return false;
            ws.getAttributes().put(ATTR_LEASE, lease);
            if (isClosing(ws)) {
                ws.getAttributes().remove(ATTR_LEASE, lease);
                bastionService.release(lease);
                return false;
            }
            return true;
        }
    }

    private static boolean publishConnection(WebSocketSession ws,
                                             BastionJschSupport.BastionSshConnection connection) {
        synchronized (ws.getAttributes()) {
            if (isClosing(ws)) return false;
            ws.getAttributes().put(ATTR_CONNECTION, connection);
            if (isClosing(ws)) {
                ws.getAttributes().remove(ATTR_CONNECTION, connection);
                BastionJschSupport.closeQuietly(connection);
                return false;
            }
            return true;
        }
    }

    private static boolean publishShell(WebSocketSession ws,
                                        BastionJschSupport.BastionSshConnection connection,
                                        ChannelShell shell) throws IOException {
        synchronized (ws.getAttributes()) {
            if (isClosing(ws) || ws.getAttributes().get(ATTR_CONNECTION) != connection) return false;
            ws.getAttributes().put(ATTR_SHELL, shell);
            ws.getAttributes().put(ATTR_STDIN, BastionJschSupport.shellInput(shell));
            return true;
        }
    }

    private void closeAndCleanup(WebSocketSession ws, CloseStatus status) {
        markClosing(ws);
        cancelFirstMessageTimeout(ws);
        cancelReader(ws);
        closeResources(ws);
        releaseSessionSlot(ws);
        closeWebSocket(ws, status);
    }

    private void closeResources(WebSocketSession ws) {
        Object connection;
        Object lease;
        synchronized (ws.getAttributes()) {
            ws.getAttributes().remove(ATTR_STDIN);
            ws.getAttributes().remove(ATTR_SHELL);
            connection = ws.getAttributes().remove(ATTR_CONNECTION);
            lease = ws.getAttributes().remove(ATTR_LEASE);
        }
        if (connection instanceof BastionJschSupport.BastionSshConnection sshConnection) {
            BastionJschSupport.closeQuietly(sshConnection);
        }
        if (lease instanceof BastionService.BastionLease bastionLease) {
            bastionService.release(bastionLease);
        }
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
                if (slot.state.compareAndSet(SlotState.NEW, SlotState.ACQUIRED)) return true;
                activeSessions.decrementAndGet();
                activeWebSockets.remove(ws);
                return false;
            }
        }
    }

    private void releaseSessionSlot(WebSocketSession ws) {
        Object marker = ws.getAttributes().get(ATTR_SLOT_STATE);
        if (marker instanceof SlotState slot
                && slot.state.compareAndSet(SlotState.ACQUIRED, SlotState.RELEASED)) {
            activeSessions.decrementAndGet();
        }
        activeWebSockets.remove(ws);
    }

    private void handleFirstMessageTimeout(WebSocketSession ws, AtomicBoolean claimed) {
        if (!claimed.compareAndSet(false, true)) return;
        closeAndCleanup(ws, CloseStatus.NORMAL.withReason("Bastion token timed out"));
    }

    private static void registerFuture(WebSocketSession ws, String key, Future<?> future,
                                       boolean mayInterruptIfRunning) {
        ws.getAttributes().put(key, future);
        if (isClosing(ws) && ws.getAttributes().remove(key, future)) {
            future.cancel(mayInterruptIfRunning);
        }
    }

    private static AtomicBoolean firstMessageClaimed(WebSocketSession ws) {
        Object value = ws.getAttributes().get(ATTR_FIRST_MESSAGE_CLAIMED);
        return value instanceof AtomicBoolean claimed ? claimed : null;
    }

    private static void cancelFirstMessageTimeout(WebSocketSession ws) {
        Object timeout = ws.getAttributes().remove(ATTR_FIRST_MESSAGE_TIMEOUT);
        if (timeout instanceof Future<?> future) future.cancel(false);
    }

    private static void cancelReader(WebSocketSession ws) {
        Object reader = ws.getAttributes().remove(ATTR_READER);
        if (reader instanceof Future<?> future) future.cancel(true);
    }

    private static void markClosing(WebSocketSession ws) {
        Object marker = ws.getAttributes().get(ATTR_CLOSING);
        if (marker instanceof AtomicBoolean closing) closing.set(true);
        else ws.getAttributes().put(ATTR_CLOSING, new AtomicBoolean(true));
    }

    private static boolean isClosing(WebSocketSession ws) {
        Object marker = ws.getAttributes().get(ATTR_CLOSING);
        return marker instanceof AtomicBoolean closing && closing.get();
    }

    private static boolean hasAuthenticatedAccount(WebSocketSession ws) {
        return !authenticatedAccount(ws).isBlank();
    }

    private static String authenticatedAccount(WebSocketSession ws) {
        Object account = ws.getAttributes().get(
                WebSshAuthHandshakeInterceptor.AUTHENTICATED_ACCOUNT_ATTRIBUTE);
        return account == null ? "" : String.valueOf(account).trim();
    }

    static int normalizeColumns(int value) {
        return Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, value));
    }

    static int normalizeRows(int value) {
        return Math.max(MIN_ROWS, Math.min(MAX_ROWS, value));
    }

    private static int parseQueryInt(WebSocketSession ws, String key, int fallback) {
        if (ws.getUri() == null || ws.getUri().getQuery() == null) return fallback;
        for (String part : ws.getUri().getQuery().split("&")) {
            int separator = part.indexOf('=');
            if (separator <= 0 || !key.equals(part.substring(0, separator))) continue;
            try {
                return Integer.parseInt(part.substring(separator + 1));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean exceedsUtf8Limit(String value, int limit) {
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            int increment;
            if (ch <= 0x7f) increment = 1;
            else if (ch <= 0x7ff) increment = 2;
            else if (Character.isHighSurrogate(ch)
                    && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                increment = 4;
                i++;
            } else increment = 3;
            if (bytes > limit - increment) return true;
            bytes += increment;
        }
        return false;
    }

    private static void sendText(WebSocketSession ws, String text) throws IOException {
        WebSocketSession target = sendingSession(ws);
        if (target.isOpen()) target.sendMessage(new TextMessage(text));
    }

    private static WebSocketSession sendingSession(WebSocketSession ws) {
        Object decorated = ws.getAttributes().get(ATTR_SEND_SESSION);
        return decorated instanceof WebSocketSession target ? target : ws;
    }

    private static void closeWebSocket(WebSocketSession ws, CloseStatus status) {
        if (!ws.isOpen()) return;
        try {
            ws.close(status);
        } catch (IOException e) {
            log.debug("Bastion ws close error: {}", e.getMessage());
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "Bastion SSH connection failed" : message;
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Bastion ws transport error: {}",
                exception == null ? "unknown" : exception.getMessage());
        closeAndCleanup(session, CloseStatus.SERVER_ERROR.withReason("Bastion SSH transport error"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        markClosing(session);
        cancelFirstMessageTimeout(session);
        cancelReader(session);
        closeResources(session);
        releaseSessionSlot(session);
    }

    int activeSessionCount() {
        return activeSessions.get();
    }

    @PreDestroy
    void shutdownExecutors() {
        shuttingDown.set(true);
        for (WebSocketSession ws : activeWebSockets) {
            closeAndCleanup(ws, CloseStatus.SERVICE_RESTARTED.withReason("Bastion SSH is stopping"));
        }
        firstMessageScheduler.shutdownNow();
        ioPool.shutdownNow();
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    private static final class SlotState {
        private static final int NEW = 0;
        private static final int ACQUIRED = 1;
        private static final int RELEASED = 2;

        private final AtomicInteger state = new AtomicInteger(NEW);
    }
}
