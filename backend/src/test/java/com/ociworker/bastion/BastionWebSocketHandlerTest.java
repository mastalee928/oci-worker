package com.ociworker.bastion;

import com.jcraft.jsch.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BastionWebSocketHandlerTest {

    private ExecutorService ioPool;
    private ScheduledExecutorService scheduler;
    private BastionWebSocketHandler handler;
    private BastionService service;

    @BeforeEach
    void setUp() {
        ioPool = Executors.newSingleThreadExecutor();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        handler = new BastionWebSocketHandler(ioPool, scheduler);
        service = mock(BastionService.class);
        ReflectionTestUtils.setField(handler, "bastionService", service);
    }

    @AfterEach
    void tearDown() {
        handler.shutdownExecutors();
    }

    @Test
    void releasesLeaseAndConnectionWhenSocketCloses() {
        WebSocketSession ws = session();
        BastionService.BastionLease lease = new BastionService.BastionLease(
                "token", spec(), new java.util.concurrent.atomic.AtomicBoolean(false));
        Session bastion = mock(Session.class);
        Session target = mock(Session.class);
        BastionJschSupport.BastionSshConnection connection =
                new BastionJschSupport.BastionSshConnection(bastion, target, 42000);
        ws.getAttributes().put(BastionWebSocketHandler.ATTR_LEASE, lease);
        ws.getAttributes().put(BastionWebSocketHandler.ATTR_CONNECTION, connection);

        handler.afterConnectionClosed(ws, CloseStatus.NORMAL);

        verify(service).release(lease);
        assertThat(ws.getAttributes()).doesNotContainKeys(
                BastionWebSocketHandler.ATTR_LEASE,
                BastionWebSocketHandler.ATTR_CONNECTION,
                BastionWebSocketHandler.ATTR_SHELL,
                BastionWebSocketHandler.ATTR_STDIN);
    }

    @Test
    void rejectsSocketWithoutHandshakeAccountBeforeAllocatingSlot() throws Exception {
        WebSocketSession ws = session();
        when(ws.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(ws);

        verify(ws).close(org.mockito.ArgumentMatchers.argThat(
                status -> status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
        assertThat(handler.activeSessionCount()).isZero();
    }

    @Test
    void forwardsOrdinaryPingTextButConsumesNamespacedControlPing() throws Exception {
        WebSocketSession ws = session();
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        ws.getAttributes().put(BastionWebSocketHandler.ATTR_STARTED, Boolean.TRUE);
        ws.getAttributes().put(BastionWebSocketHandler.ATTR_STDIN, stdin);

        handler.handleMessage(ws, new TextMessage("ping"));
        handler.handleMessage(ws, new TextMessage(
                BastionWebSocketHandler.CONTROL_PREFIX + "{\"type\":\"ping\"}"));

        assertThat(stdin.toString(StandardCharsets.UTF_8)).isEqualTo("ping");
    }

    private static WebSocketSession session() {
        WebSocketSession ws = mock(WebSocketSession.class);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        when(ws.getAttributes()).thenReturn(attributes);
        when(ws.getId()).thenReturn("bastion-test");
        when(ws.getUri()).thenReturn(URI.create("ws://localhost/webssh-api/bastion-term?cols=120&rows=30"));
        when(ws.isOpen()).thenReturn(true);
        return ws;
    }

    private static BastionConnectionSpec spec() {
        return new BastionConnectionSpec(
                "tenant", "us-phoenix-1", "session", "host", 22,
                "user", "key", "10.0.0.1", 22, "root", 0,
                "password", null, null, "instance", System.currentTimeMillis() + 60_000);
    }
}
