package com.ociworker.webssh;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
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
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Slf4j
@Component
public class WebSshTerminalWebSocketHandler implements WebSocketHandler {

    private final ExecutorService ioPool = Executors.newVirtualThreadPerTaskExecutor();

    @Value("${webssh.timeout-minutes:120}")
    private int timeoutMinutes;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // wait for first message in handleMessage
    }

    @Override
    public void handleMessage(WebSocketSession ws, WebSocketMessage<?> message) throws Exception {
        if (!(message instanceof TextMessage textMessage)) {
            return;
        }
        String payload = textMessage.getPayload();
        if (ws.getAttributes().containsKey("started")) {
            handleTerminalInput(ws, payload);
            return;
        }
        startTerminal(ws, payload);
    }

    private void startTerminal(WebSocketSession ws, String sshInfoB64) {
        int cols = parseQueryInt(ws, "cols", 150);
        int rows = parseQueryInt(ws, "rows", 35);
        String closeTip = parseQuery(ws, "closeTip", "Connection timed out!");

        ws.getAttributes().put("started", Boolean.TRUE);
        Future<?> readerFuture = ioPool.submit(() -> {
            Session session = null;
            ChannelShell shell = null;
            try {
                WebSshConnectInfo info = WebSshConnectInfoParser.parse(sshInfoB64);
                session = WebSshJschSupport.openSession(info);
                shell = WebSshJschSupport.openShell(session, cols, rows);
                ws.getAttributes().put("shell", shell);
                ws.getAttributes().put("sshSession", session);
                ws.getAttributes().put("stdin", WebSshJschSupport.shellInput(shell));

                InputStream stdout = WebSshJschSupport.shellOutput(shell);
                byte[] buf = new byte[4096];
                long deadline = System.nanoTime() + Duration.ofMinutes(timeoutMinutes).toNanos();
                while (ws.isOpen() && shell.isConnected()) {
                    if (System.nanoTime() > deadline) {
                        sendText(ws, "\033[33m" + closeTip + "\033[0m");
                        break;
                    }
                    while (stdout.available() > 0) {
                        int n = stdout.read(buf);
                        if (n > 0) {
                            sendText(ws, new String(buf, 0, n, StandardCharsets.UTF_8));
                        }
                    }
                    Thread.sleep(50);
                }
            } catch (Exception e) {
                log.debug("WebSSH terminal error: {}", e.getMessage());
                try {
                    sendText(ws, "\033[31m" + e.getMessage() + "\033[0m");
                } catch (Exception ignored) {
                }
            } finally {
                closeSsh(ws);
            }
        });
        ws.getAttributes().put("reader", readerFuture);
    }

    private void handleTerminalInput(WebSocketSession ws, String payload) throws Exception {
        if ("ping".equals(payload)) {
            return;
        }
        if (payload.startsWith("resize:")) {
            String[] parts = payload.split(":");
            if (parts.length >= 3) {
                int rows = Integer.parseInt(parts[1]);
                int cols = Integer.parseInt(parts[2]);
                Object shellObj = ws.getAttributes().get("shell");
                if (shellObj instanceof ChannelShell shell) {
                    WebSshJschSupport.resizeShell(shell, cols, rows);
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

    private void closeSsh(WebSocketSession ws) {
        Object shellObj = ws.getAttributes().remove("shell");
        Object sessionObj = ws.getAttributes().remove("sshSession");
        ws.getAttributes().remove("stdin");
        ChannelShell shell = shellObj instanceof ChannelShell s ? s : null;
        Session session = sessionObj instanceof Session s ? s : null;
        WebSshJschSupport.closeQuietly(session, shell);
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
        log.debug("WebSSH ws transport error: {}", exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        Object f = ws.getAttributes().remove("reader");
        if (f instanceof Future<?> future) {
            future.cancel(true);
        }
        closeSsh(ws);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }
}
