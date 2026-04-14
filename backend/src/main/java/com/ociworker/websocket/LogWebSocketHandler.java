package com.ociworker.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
public class LogWebSocketHandler extends TextWebSocketHandler {

    private static final Set<WebSocketSession> SESSIONS = new CopyOnWriteArraySet<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SESSIONS.add(session);
        log.info("Log WebSocket connected: {}", session.getId());
        try {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            session.sendMessage(new TextMessage(ts + " INFO  WebSocket 连接成功，等待日志输出..."));
        } catch (IOException ignored) {}
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SESSIONS.remove(session);
        log.info("Log WebSocket disconnected: {}", session.getId());
    }

    public static void broadcast(String message) {
        TextMessage textMessage = new TextMessage(message);
        for (WebSocketSession session : SESSIONS) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(textMessage);
                } catch (IOException e) {
                    log.error("Failed to send WebSocket message", e);
                }
            }
        }
    }
}
