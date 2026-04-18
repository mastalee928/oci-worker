package com.ociworker.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class WebSSHProxyHandler implements WebSocketHandler {

    @Value("${webssh.upstream-ws:ws://127.0.0.1:8008}")
    private String webSshWs;

    private final Map<String, WebSocketSession> upstreamSessions = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentWebSocketSessionDecorator> clientSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession clientSession) throws Exception {
        String query = clientSession.getUri() != null ? clientSession.getUri().getQuery() : "";
        String path = clientSession.getUri() != null ? clientSession.getUri().getPath() : "/term";
        String endpoint = path.replace("/webssh-api", "");
        String upstreamUrl = webSshWs + endpoint + (query != null && !query.isEmpty() ? "?" + query : "");

        ConcurrentWebSocketSessionDecorator clientDecorator =
                new ConcurrentWebSocketSessionDecorator(clientSession, 5000, 512 * 1024);
        clientSessions.put(clientSession.getId(), clientDecorator);

        StandardWebSocketClient wsClient = new StandardWebSocketClient();
        WebSocketSession upstream;
        try {
            upstream = wsClient.execute(new WebSocketHandler() {
                @Override
                public void afterConnectionEstablished(WebSocketSession session) {}

                @Override
                public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
                    if (clientDecorator.isOpen()) clientDecorator.sendMessage(message);
                }

                @Override
                public void handleTransportError(WebSocketSession session, Throwable exception) {
                    log.debug("WebSSH upstream error: {}", exception.getMessage());
                }

                @Override
                public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                    if (clientDecorator.isOpen()) clientDecorator.close(status);
                    clientSessions.remove(clientSession.getId());
                }

                @Override
                public boolean supportsPartialMessages() { return false; }
            }, null, URI.create(upstreamUrl)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Failed to connect upstream WebSSH at {}: {}", upstreamUrl, e.getMessage());
            clientSessions.remove(clientSession.getId());
            if (clientSession.isOpen()) {
                clientSession.close(CloseStatus.SERVICE_RESTARTED.withReason("upstream_unreachable"));
            }
            return;
        }

        upstreamSessions.put(clientSession.getId(), upstream);
    }

    @Override
    public void handleMessage(WebSocketSession clientSession, WebSocketMessage<?> message) throws Exception {
        WebSocketSession upstream = upstreamSessions.get(clientSession.getId());
        if (upstream != null && upstream.isOpen()) {
            synchronized (upstream) {
                upstream.sendMessage(message);
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSSH client error: {}", exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession clientSession, CloseStatus status) throws Exception {
        clientSessions.remove(clientSession.getId());
        WebSocketSession upstream = upstreamSessions.remove(clientSession.getId());
        if (upstream != null && upstream.isOpen()) {
            try { upstream.close(status); } catch (Exception ignored) {}
        }
    }

    @Override
    public boolean supportsPartialMessages() { return false; }
}
