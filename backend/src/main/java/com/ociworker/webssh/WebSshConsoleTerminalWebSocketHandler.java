package com.ociworker.webssh;

import com.ociworker.config.WebSshAuthHandshakeInterceptor;
import com.ociworker.exception.OciException;
import com.ociworker.service.ConsoleService;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class WebSshConsoleTerminalWebSocketHandler implements WebSocketHandler {

    /** UEFI / serial console expects classic 80x24; wider grids cause \r prompt overlap. */
    private static final int DEFAULT_CONSOLE_COLS = 80;
    private static final int DEFAULT_CONSOLE_ROWS = 24;
    private static final int MIN_CONSOLE_COLS = 20;
    private static final int MAX_CONSOLE_COLS = 500;
    private static final int MIN_CONSOLE_ROWS = 5;
    private static final int MAX_CONSOLE_ROWS = 200;

    static final String ATTR_STARTED = "consoleStarted";
    static final String ATTR_FIRST_MESSAGE_CLAIMED = "consoleFirstMessageClaimed";
    static final String ATTR_CLOSING = "consoleClosing";
    static final String ATTR_READER = "consoleReader";
    static final String ATTR_PROCESS = "process";
    static final String ATTR_STDIN = "stdin";
    static final String ATTR_LEASE = "consoleLease";

    private final ExecutorService ioPool;
    private final Set<WebSocketSession> activeWebSockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    @Resource
    private ConsoleService consoleService;

    @Value("${webssh.timeout-minutes:120}")
    private int timeoutMinutes = 120;

    public WebSshConsoleTerminalWebSocketHandler() {
        this(Executors.newVirtualThreadPerTaskExecutor());
    }

    WebSshConsoleTerminalWebSocketHandler(ExecutorService ioPool) {
        this.ioPool = Objects.requireNonNull(ioPool, "ioPool");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.getAttributes().putIfAbsent(ATTR_CLOSING, new AtomicBoolean(false));
        session.getAttributes().putIfAbsent(ATTR_FIRST_MESSAGE_CLAIMED, new AtomicBoolean(false));
        if (shuttingDown.get()) {
            markClosing(session);
            closeWebSocket(session, CloseStatus.SERVICE_RESTARTED.withReason("Console service is stopping"));
            return;
        }
        activeWebSockets.add(session);
        if (shuttingDown.get()) {
            closeAndCleanup(session, CloseStatus.SERVICE_RESTARTED.withReason("Console service is stopping"));
        }
    }

    @Override
    public void handleMessage(WebSocketSession ws, WebSocketMessage<?> message) throws Exception {
        if (isClosing(ws)) {
            return;
        }
        if (!(message instanceof TextMessage textMessage)) {
            closeAndCleanup(ws, CloseStatus.BAD_DATA.withReason("Serial console requires text messages"));
            return;
        }
        String payload = textMessage.getPayload();
        if (ws.getAttributes().containsKey(ATTR_STARTED)) {
            handleConsoleInput(ws, payload);
            return;
        }

        AtomicBoolean claimed = firstMessageClaimed(ws);
        if (!claimed.compareAndSet(false, true)) {
            return;
        }
        startConsole(ws, payload.trim());
    }

    private void startConsole(WebSocketSession ws, String connectionId) {
        Object accountValue = ws.getAttributes().get(
                WebSshAuthHandshakeInterceptor.AUTHENTICATED_ACCOUNT_ATTRIBUTE);
        if (!(accountValue instanceof String ownerAccount) || ownerAccount.isBlank()) {
            rejectStart(ws, "登录状态无效，请重新登录");
            return;
        }

        ConsoleService.ConsoleLease lease;
        try {
            lease = consoleService.claimConsoleSession(connectionId, ownerAccount);
        } catch (OciException e) {
            rejectStart(ws, safeMessage(e, "控制台会话不可用，请重新创建连接"));
            return;
        } catch (Exception e) {
            log.debug("Console session claim failed: {}", e.getMessage());
            rejectStart(ws, "控制台会话不可用，请重新创建连接");
            return;
        }

        synchronized (ws.getAttributes()) {
            if (isClosing(ws)) {
                releaseLeaseQuietly(lease);
                return;
            }
            ws.getAttributes().put(ATTR_LEASE, lease);
            ws.getAttributes().put(ATTR_STARTED, Boolean.TRUE);
        }

        int cols = normalizeDimension(
                parseQueryInt(ws, "cols", DEFAULT_CONSOLE_COLS),
                MIN_CONSOLE_COLS, MAX_CONSOLE_COLS, DEFAULT_CONSOLE_COLS);
        int rows = normalizeDimension(
                parseQueryInt(ws, "rows", DEFAULT_CONSOLE_ROWS),
                MIN_CONSOLE_ROWS, MAX_CONSOLE_ROWS, DEFAULT_CONSOLE_ROWS);
        String closeTip = parseQuery(ws, "closeTip", "Connection timed out!");

        try {
            Future<?> reader = ioPool.submit(() -> runConsole(ws, lease, cols, rows, closeTip));
            registerReader(ws, reader);
        } catch (RejectedExecutionException e) {
            log.debug("Console reader task rejected: {}", e.getMessage());
            closeAndCleanup(ws, CloseStatus.SERVICE_RESTARTED.withReason("Console service is stopping"));
        }
    }

    private void runConsole(WebSocketSession ws, ConsoleService.ConsoleLease lease,
                            int cols, int rows, String closeTip) {
        PtyProcess localProcess = null;
        CloseStatus closeStatus = CloseStatus.NORMAL;
        try {
            Path script = consoleService.getOrCreateExecScript(lease);
            Map<String, String> env = new HashMap<>(System.getenv());
            env.put("TERM", "vt100");

            localProcess = new PtyProcessBuilder()
                    .setCommand(new String[]{"/bin/bash", script.toAbsolutePath().toString()})
                    .setEnvironment(env)
                    .setInitialColumns(cols)
                    .setInitialRows(rows)
                    .start();
            if (!publishProcess(ws, localProcess)) {
                destroyProcess(localProcess);
                return;
            }

            try (InputStream stdout = localProcess.getInputStream()) {
                closeStatus = pumpConsoleOutput(ws, localProcess, stdout, closeTip);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!isClosing(ws)) {
                closeStatus = CloseStatus.SERVER_ERROR.withReason("Serial console interrupted");
            }
        } catch (OciException e) {
            closeStatus = CloseStatus.SERVER_ERROR.withReason("Serial console unavailable");
            sendError(ws, safeMessage(e, "控制台会话不可用，请重新创建连接"));
        } catch (Exception e) {
            log.debug("Console terminal error: {}", e.getMessage());
            closeStatus = CloseStatus.SERVER_ERROR.withReason("Serial console failed");
            sendError(ws, "串行控制台连接失败，请重新创建连接");
        } finally {
            closeProcess(ws);
            releaseLease(ws, lease);
            markClosing(ws);
            activeWebSockets.remove(ws);
            closeWebSocket(ws, closeStatus);
        }
    }

    CloseStatus pumpConsoleOutput(WebSocketSession ws, PtyProcess process,
                                  InputStream stdout, String closeTip) throws Exception {
        byte[] buf = new byte[4096];
        long deadline = System.nanoTime()
                + Duration.ofMinutes(Math.max(1, timeoutMinutes)).toNanos();
        while (!isClosing(ws) && ws.isOpen() && process.isAlive()) {
            if (System.nanoTime() > deadline) {
                sendText(ws, "\033[33m" + closeTip + "\033[0m");
                return CloseStatus.NORMAL.withReason("Serial console timed out");
            }
            // pty4j streams do not guarantee that available() reports pending PTY bytes.
            // A blocking read is required or a live serial console can remain blank forever.
            int n = stdout.read(buf);
            if (n > 0) {
                sendConsoleOutput(ws, buf, n);
            } else if (n < 0) {
                break;
            }
        }
        return CloseStatus.NORMAL;
    }

    private void rejectStart(WebSocketSession ws, String message) {
        sendError(ws, message);
        closeAndCleanup(ws, CloseStatus.POLICY_VIOLATION.withReason("Serial console session rejected"));
    }

    private void handleConsoleInput(WebSocketSession ws, String payload) throws IOException {
        if ("ping".equals(payload)) {
            return;
        }
        if (payload.startsWith("resize:")) {
            String[] parts = payload.split(":", 4);
            if (parts.length >= 3) {
                try {
                    int rows = Integer.parseInt(parts[1]);
                    int cols = Integer.parseInt(parts[2]);
                    Object processObj = ws.getAttributes().get(ATTR_PROCESS);
                    if (rows >= MIN_CONSOLE_ROWS && rows <= MAX_CONSOLE_ROWS
                            && cols >= MIN_CONSOLE_COLS && cols <= MAX_CONSOLE_COLS
                            && processObj instanceof PtyProcess process && process.isAlive()) {
                        process.setWinSize(new WinSize(cols, rows));
                    }
                } catch (NumberFormatException e) {
                    log.trace("Ignoring malformed serial console resize payload");
                }
            }
            return;
        }
        Object stdinObj = ws.getAttributes().get(ATTR_STDIN);
        if (stdinObj instanceof OutputStream stdin) {
            stdin.write(payload.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }
    }

    private static int parseQueryInt(WebSocketSession ws, String key, int def) {
        String value = parseQuery(ws, key, null);
        if (value == null) {
            return def;
        }
        try {
            return Integer.parseInt(value);
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

    private static int normalizeDimension(int value, int min, int max, int fallback) {
        return value >= min && value <= max ? value : fallback;
    }

    private static AtomicBoolean firstMessageClaimed(WebSocketSession ws) {
        Object value = ws.getAttributes().computeIfAbsent(
                ATTR_FIRST_MESSAGE_CLAIMED, ignored -> new AtomicBoolean(false));
        return (AtomicBoolean) value;
    }

    private static boolean publishProcess(WebSocketSession ws, PtyProcess process) throws IOException {
        synchronized (ws.getAttributes()) {
            if (isClosing(ws)) {
                return false;
            }
            ws.getAttributes().put(ATTR_PROCESS, process);
            ws.getAttributes().put(ATTR_STDIN, process.getOutputStream());
            return true;
        }
    }

    private static void registerReader(WebSocketSession ws, Future<?> reader) {
        ws.getAttributes().put(ATTR_READER, reader);
        if (isClosing(ws) && ws.getAttributes().remove(ATTR_READER, reader)) {
            reader.cancel(true);
        }
    }

    private static void cancelReader(WebSocketSession ws) {
        Object reader = ws.getAttributes().remove(ATTR_READER);
        if (reader instanceof Future<?> future) {
            future.cancel(true);
        }
    }

    private static void closeProcess(WebSocketSession ws) {
        Object processObj;
        synchronized (ws.getAttributes()) {
            processObj = ws.getAttributes().remove(ATTR_PROCESS);
            ws.getAttributes().remove(ATTR_STDIN);
        }
        if (processObj instanceof PtyProcess process) {
            destroyProcess(process);
        }
    }

    private static void destroyProcess(PtyProcess process) {
        try {
            if (!process.isAlive()) {
                return;
            }
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS) && process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyForciblyQuietly(process);
        } catch (Exception e) {
            log.debug("Console process cleanup failed: {}", e.getMessage());
            destroyForciblyQuietly(process);
        }
    }

    private static void destroyForciblyQuietly(PtyProcess process) {
        try {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            log.debug("Console process force cleanup failed: {}", e.getMessage());
        }
    }

    private void releaseLease(WebSocketSession ws, ConsoleService.ConsoleLease expectedLease) {
        Object leaseValue;
        synchronized (ws.getAttributes()) {
            leaseValue = ws.getAttributes().remove(ATTR_LEASE);
        }
        if (leaseValue instanceof ConsoleService.ConsoleLease lease) {
            releaseLeaseQuietly(lease);
        }
        if (expectedLease != null && !expectedLease.equals(leaseValue)) {
            releaseLeaseQuietly(expectedLease);
        }
    }

    private void releaseLeaseQuietly(ConsoleService.ConsoleLease lease) {
        try {
            consoleService.releaseConsoleSession(lease);
        } catch (Exception e) {
            log.debug("Console lease cleanup failed: {}", e.getMessage());
        }
    }

    private void closeAndCleanup(WebSocketSession ws, CloseStatus status) {
        markClosing(ws);
        cancelReader(ws);
        closeProcess(ws);
        releaseLease(ws, null);
        activeWebSockets.remove(ws);
        closeWebSocket(ws, status);
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

    /** ISO-8859-1 keeps serial bytes 0–255 intact (UTF-8 would corrupt UEFI control sequences). */
    private static void sendConsoleOutput(WebSocketSession ws, byte[] buf, int len) throws IOException {
        if (ws.isOpen() && !isClosing(ws)) {
            synchronized (ws) {
                ws.sendMessage(new TextMessage(new String(buf, 0, len, StandardCharsets.ISO_8859_1)));
            }
        }
    }

    private static void sendText(WebSocketSession ws, String text) throws IOException {
        if (ws.isOpen() && !isClosing(ws)) {
            synchronized (ws) {
                ws.sendMessage(new TextMessage(text));
            }
        }
    }

    private static void sendError(WebSocketSession ws, String message) {
        try {
            sendText(ws, "\033[31m" + message + "\033[0m");
        } catch (Exception e) {
            log.debug("Console error message could not be sent: {}", e.getMessage());
        }
    }

    private static String safeMessage(Exception e, String fallback) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static void closeWebSocket(WebSocketSession ws, CloseStatus status) {
        if (!ws.isOpen()) {
            return;
        }
        try {
            ws.close(status);
        } catch (Exception e) {
            log.debug("Console WebSocket close failed: {}", e.getMessage());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Console ws transport error: {}",
                exception == null ? "unknown" : exception.getMessage());
        closeAndCleanup(session, CloseStatus.SERVER_ERROR.withReason("Serial console transport error"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        closeAndCleanup(ws, status);
    }

    @PreDestroy
    void shutdownExecutors() {
        shuttingDown.set(true);
        for (WebSocketSession ws : activeWebSockets) {
            closeAndCleanup(ws, CloseStatus.SERVICE_RESTARTED.withReason("Console service is stopping"));
        }
        ioPool.shutdownNow();
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }
}
