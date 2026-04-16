package com.ociworker.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class WebSSHProxyHandler implements WebSocketHandler {

    private static final String WEBSSH_WS = "ws://127.0.0.1:8008";
    private final Map<String, WebSocketSession> upstreamSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession clientSession) throws Exception {
        String query = clientSession.getUri() != null ? clientSession.getUri().getQuery() : "";
        String path = clientSession.getUri() != null ? clientSession.getUri().getPath() : "/term";
        String endpoint = path.replace("/webssh-api", "");
        String upstreamUrl = WEBSSH_WS + endpoint + (query != null && !query.isEmpty() ? "?" + query : "");

        StandardWebSocketClient wsClient = new StandardWebSocketClient();
        WebSocketSession upstream = wsClient.execute(new WebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) {}

            @Override
            public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
                if (clientSession.isOpen()) clientSession.sendMessage(message);
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) {
                log.debug("WebSSH upstream error: {}", exception.getMessage());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                if (clientSession.isOpen()) clientSession.close(status);
            }

            @Override
            public boolean supportsPartialMessages() { return false; }
        }, null, URI.create(upstreamUrl)).get();

        upstreamSessions.put(clientSession.getId(), upstream);
    }

    @Override
    public void handleMessage(WebSocketSession clientSession, WebSocketMessage<?> message) throws Exception {
        WebSocketSession upstream = upstreamSessions.get(clientSession.getId());
        if (upstream != null && upstream.isOpen()) upstream.sendMessage(message);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSSH client error: {}", exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession clientSession, CloseStatus status) throws Exception {
        WebSocketSession upstream = upstreamSessions.remove(clientSession.getId());
        if (upstream != null && upstream.isOpen()) upstream.close(status);
    }

    @Override
    public boolean supportsPartialMessages() { return false; }
}
