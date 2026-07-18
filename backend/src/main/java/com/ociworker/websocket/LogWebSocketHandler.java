package com.ociworker.websocket;

import com.ociworker.service.LogPersistService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class LogWebSocketHandler extends TextWebSocketHandler {

    /**
     * Maps raw session id -> wrapped (thread-safe) decorator to avoid concurrent sendMessage.
     */
    private static final Map<String, ConcurrentWebSocketSessionDecorator> SESSIONS = new ConcurrentHashMap<>();
    private static volatile LogPersistService logPersistService;

    public LogWebSocketHandler(LogPersistService persistService) {
        logPersistService = persistService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        ConcurrentWebSocketSessionDecorator decorated =
                new ConcurrentWebSocketSessionDecorator(session, 2000, 64 * 1024);
        SESSIONS.put(session.getId(), decorated);
        log.info("Log WebSocket connected: {}", session.getId());
        try {
            LogPersistService persist = logPersistService;
            if (persist != null) {
                List<String> history = persist.readLastLines(500);
                for (String line : history) {
                    decorated.sendMessage(new TextMessage(line));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to send history logs: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SESSIONS.remove(session.getId());
    }

    public static void broadcast(String message) {
        LogPersistService persist = logPersistService;
        if (persist != null) {
            persist.appendLog(message);
        }

        TextMessage textMessage = new TextMessage(message);
        for (Map.Entry<String, ConcurrentWebSocketSessionDecorator> entry : SESSIONS.entrySet()) {
            ConcurrentWebSocketSessionDecorator decorated = entry.getValue();
            if (decorated.isOpen()) {
                try {
                    decorated.sendMessage(textMessage);
                } catch (IOException e) {
                    SESSIONS.remove(entry.getKey());
                }
            } else {
                SESSIONS.remove(entry.getKey());
            }
        }
    }
}
