package com.ociworker.websocket;

import com.ociworker.service.LogPersistService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
public class LogWebSocketHandler extends TextWebSocketHandler {

    private static final Set<WebSocketSession> SESSIONS = new CopyOnWriteArraySet<>();
    private static LogPersistService logPersistService;

    public LogWebSocketHandler(LogPersistService persistService) {
        logPersistService = persistService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SESSIONS.add(session);
        log.info("Log WebSocket connected: {}", session.getId());
        try {
            List<String> history = logPersistService.readLastLines(500);
            for (String line : history) {
                session.sendMessage(new TextMessage(line));
            }
        } catch (IOException e) {
            log.warn("Failed to send history logs: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SESSIONS.remove(session);
    }

    public static void broadcast(String message) {
        if (logPersistService != null) {
            logPersistService.appendLog(message);
        }

        TextMessage textMessage = new TextMessage(message);
        for (WebSocketSession session : SESSIONS) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(textMessage);
                } catch (IOException e) {
                    // remove broken session
                    SESSIONS.remove(session);
                }
            }
        }
    }
}
