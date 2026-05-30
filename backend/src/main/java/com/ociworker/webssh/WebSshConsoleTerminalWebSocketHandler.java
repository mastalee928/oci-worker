package com.ociworker.webssh;

import com.ociworker.exception.OciException;
import com.ociworker.service.ConsoleService;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Slf4j
@Component
public class WebSshConsoleTerminalWebSocketHandler implements WebSocketHandler {

    /** UEFI / serial console expects classic 80x24; wider grids cause \\r prompt overlap. */
    private static final int DEFAULT_CONSOLE_COLS = 80;
    private static final int DEFAULT_CONSOLE_ROWS = 24;

    private final ExecutorService ioPool = Executors.newVirtualThreadPerTaskExecutor();

    @Resource
    private ConsoleService consoleService;

    @Value("${webssh.timeout-minutes:120}")
    private int timeoutMinutes;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // wait for first message
    }

    @Override
    public void handleMessage(WebSocketSession ws, WebSocketMessage<?> message) throws Exception {
        if (!(message instanceof TextMessage textMessage)) {
            return;
        }
        String payload = textMessage.getPayload();
        if (ws.getAttributes().containsKey("started")) {
            handleConsoleInput(ws, payload);
            return;
        }
        startConsole(ws, payload.trim());
    }

    private void startConsole(WebSocketSession ws, String connectionId) {
        int cols = parseQueryInt(ws, "cols", DEFAULT_CONSOLE_COLS);
        int rows = parseQueryInt(ws, "rows", DEFAULT_CONSOLE_ROWS);
        String closeTip = parseQuery(ws, "closeTip", "Connection timed out!");

        ws.getAttributes().put("started", Boolean.TRUE);
        Future<?> readerFuture = ioPool.submit(() -> {
            PtyProcess process = null;
            try {
                Path script = consoleService.getOrCreateExecScript(connectionId);
                Map<String, String> env = new HashMap<>(System.getenv());
                env.put("TERM", "vt100");

                process = new PtyProcessBuilder()
                        .setCommand(new String[]{"/bin/bash", script.toAbsolutePath().toString()})
                        .setEnvironment(env)
                        .setInitialColumns(cols)
                        .setInitialRows(rows)
                        .start();

                ws.getAttributes().put("process", process);
                ws.getAttributes().put("stdin", process.getOutputStream());

                InputStream stdout = process.getInputStream();
                byte[] buf = new byte[4096];
                long deadline = System.nanoTime() + Duration.ofMinutes(timeoutMinutes).toNanos();
                while (ws.isOpen() && process.isAlive()) {
                    if (System.nanoTime() > deadline) {
                        sendText(ws, "\033[33m" + closeTip + "\033[0m");
                        break;
                    }
                    int n = stdout.read(buf);
                    if (n > 0) {
                        sendConsoleOutput(ws, buf, n);
                    } else if (n < 0) {
                        break;
                    }
                }
            } catch (OciException e) {
                log.debug("OCI console error: {}", e.getMessage());
                try {
                    sendText(ws, "\033[31m" + e.getMessage() + "\033[0m");
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                log.debug("Console terminal error: {}", e.getMessage());
                try {
                    sendText(ws, "\033[31m" + e.getMessage() + "\033[0m");
                } catch (Exception ignored) {
                }
            } finally {
                closeProcess(ws);
            }
        });
        ws.getAttributes().put("reader", readerFuture);
    }

    private void handleConsoleInput(WebSocketSession ws, String payload) throws Exception {
        if ("ping".equals(payload)) {
            return;
        }
        if (payload.startsWith("resize:")) {
            String[] parts = payload.split(":");
            if (parts.length >= 3) {
                int rows = Integer.parseInt(parts[1]);
                int cols = Integer.parseInt(parts[2]);
                Object processObj = ws.getAttributes().get("process");
                if (processObj instanceof PtyProcess process && process.isAlive()) {
                    process.setWinSize(new WinSize(cols, rows));
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

    private void closeProcess(WebSocketSession ws) {
        Object processObj = ws.getAttributes().remove("process");
        ws.getAttributes().remove("stdin");
        if (processObj instanceof PtyProcess process) {
            try {
                if (process.isAlive()) {
                    process.destroy();
                    process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.debug("Console process cleanup: {}", e.getMessage());
            }
        }
    }

    /** ISO-8859-1 keeps serial bytes 0–255 intact (UTF-8 would corrupt UEFI control sequences). */
    private static void sendConsoleOutput(WebSocketSession ws, byte[] buf, int len) throws Exception {
        if (ws.isOpen()) {
            synchronized (ws) {
                ws.sendMessage(new TextMessage(new String(buf, 0, len, StandardCharsets.ISO_8859_1)));
            }
        }
    }

    private static void sendText(WebSocketSession ws, String text) throws Exception {
        if (ws.isOpen()) {
            synchronized (ws) {
                ws.sendMessage(new TextMessage(text));
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Console ws transport error: {}", exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        Object f = ws.getAttributes().remove("reader");
        if (f instanceof Future<?> future) {
            future.cancel(true);
        }
        closeProcess(ws);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }
}
