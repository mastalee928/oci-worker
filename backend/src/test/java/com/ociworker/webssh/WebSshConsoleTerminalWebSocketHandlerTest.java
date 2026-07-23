package com.ociworker.webssh;

import com.ociworker.config.WebSshAuthHandshakeInterceptor;
import com.ociworker.exception.OciException;
import com.ociworker.service.ConsoleService;
import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSshConsoleTerminalWebSocketHandlerTest {

    private final WebSshConsoleTerminalWebSocketHandler handler =
            new WebSshConsoleTerminalWebSocketHandler();

    @AfterEach
    void stopHandlerExecutor() {
        ExecutorService ioPool = (ExecutorService) ReflectionTestUtils.getField(handler, "ioPool");
        if (ioPool != null) {
            ioPool.shutdownNow();
        }
    }

    @Test
    void keepsClassicSerialConsoleDimensions() throws Exception {
        assertThat(staticInt("DEFAULT_CONSOLE_COLS")).isEqualTo(80);
        assertThat(staticInt("DEFAULT_CONSOLE_ROWS")).isEqualTo(24);
    }

    @Test
    void appliesValidSerialConsoleResize() throws Exception {
        PtyProcess process = mock(PtyProcess.class);
        when(process.isAlive()).thenReturn(true);
        WebSocketSession ws = session(Map.of(
                WebSshConsoleTerminalWebSocketHandler.ATTR_STARTED, Boolean.TRUE,
                WebSshConsoleTerminalWebSocketHandler.ATTR_PROCESS, process
        ));

        handler.handleMessage(ws, new TextMessage("resize:30:100"));

        ArgumentCaptor<WinSize> size = ArgumentCaptor.forClass(WinSize.class);
        verify(process).setWinSize(size.capture());
        assertThat(size.getValue().getColumns()).isEqualTo(100);
        assertThat(size.getValue().getRows()).isEqualTo(30);
    }

    @Test
    void ignoresMalformedSerialConsoleResize() throws Exception {
        PtyProcess process = mock(PtyProcess.class);
        WebSocketSession ws = session(Map.of(
                WebSshConsoleTerminalWebSocketHandler.ATTR_STARTED, Boolean.TRUE,
                WebSshConsoleTerminalWebSocketHandler.ATTR_PROCESS, process
        ));

        handler.handleMessage(ws, new TextMessage("resize:not-a-number:100"));

        verify(process, never()).setWinSize(any());
    }

    @Test
    void ignoresOutOfRangeSerialConsoleResize() throws Exception {
        PtyProcess process = mock(PtyProcess.class);
        when(process.isAlive()).thenReturn(true);
        WebSocketSession ws = session(Map.of(
                WebSshConsoleTerminalWebSocketHandler.ATTR_STARTED, Boolean.TRUE,
                WebSshConsoleTerminalWebSocketHandler.ATTR_PROCESS, process
        ));

        handler.handleMessage(ws, new TextMessage("resize:0:100000"));

        verify(process, never()).setWinSize(any());
    }

    @Test
    void closesReaderPtyAndLeaseWhenSerialConsoleWebSocketCloses() throws Exception {
        ConsoleService service = mock(ConsoleService.class);
        ReflectionTestUtils.setField(handler, "consoleService", service);
        ConsoleService.ConsoleLease lease = new ConsoleService.ConsoleLease("connection", "lease");
        Future<?> reader = mock(Future.class);
        PtyProcess process = mock(PtyProcess.class);
        when(process.isAlive()).thenReturn(true);
        WebSocketSession ws = session(Map.of(
                WebSshConsoleTerminalWebSocketHandler.ATTR_READER, reader,
                WebSshConsoleTerminalWebSocketHandler.ATTR_PROCESS, process,
                WebSshConsoleTerminalWebSocketHandler.ATTR_LEASE, lease
        ));

        handler.afterConnectionClosed(ws, CloseStatus.NORMAL);

        verify(reader).cancel(true);
        verify(process).destroy();
        verify(process).waitFor(3, TimeUnit.SECONDS);
        verify(process).destroyForcibly();
        verify(service).releaseConsoleSession(lease);
        assertThat(ws.getAttributes()).doesNotContainKeys(
                WebSshConsoleTerminalWebSocketHandler.ATTR_READER,
                WebSshConsoleTerminalWebSocketHandler.ATTR_PROCESS,
                WebSshConsoleTerminalWebSocketHandler.ATTR_STDIN,
                WebSshConsoleTerminalWebSocketHandler.ATTR_LEASE);
    }

    @Test
    void rejectsFirstMessageWithoutAuthenticatedAccount() throws Exception {
        ConsoleService service = mock(ConsoleService.class);
        ReflectionTestUtils.setField(handler, "consoleService", service);
        WebSocketSession ws = session(Map.of());
        when(ws.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(ws);

        handler.handleMessage(ws, new TextMessage("connection-id"));

        verify(service, never()).claimConsoleSession(anyString(), anyString());
        verify(ws).sendMessage(any(TextMessage.class));
        verify(ws).close(any(CloseStatus.class));
    }

    @Test
    void claimsWithHandshakeAccountAndReleasesWhenStartupFails() throws Exception {
        ConsoleService service = mock(ConsoleService.class);
        ReflectionTestUtils.setField(handler, "consoleService", service);
        ConsoleService.ConsoleLease lease = new ConsoleService.ConsoleLease("connection-id", "lease-id");
        when(service.claimConsoleSession("connection-id", "panel-admin")).thenReturn(lease);
        when(service.getOrCreateExecScript(lease)).thenThrow(new OciException("会话已失效"));
        WebSocketSession ws = session(Map.of(
                WebSshAuthHandshakeInterceptor.AUTHENTICATED_ACCOUNT_ATTRIBUTE, "panel-admin"
        ));
        when(ws.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(ws);

        handler.handleMessage(ws, new TextMessage("connection-id"));

        verify(service).claimConsoleSession("connection-id", "panel-admin");
        verify(service, timeout(2_000)).releaseConsoleSession(lease);
        verify(ws, timeout(2_000)).close(any(CloseStatus.class));
    }

    @Test
    void transportErrorReleasesLeaseAndClosesSocket() throws Exception {
        ConsoleService service = mock(ConsoleService.class);
        ReflectionTestUtils.setField(handler, "consoleService", service);
        ConsoleService.ConsoleLease lease = new ConsoleService.ConsoleLease("connection", "lease");
        WebSocketSession ws = session(Map.of(
                WebSshConsoleTerminalWebSocketHandler.ATTR_LEASE, lease
        ));
        when(ws.isOpen()).thenReturn(true);

        handler.handleTransportError(ws, new IOException("network reset"));

        verify(service).releaseConsoleSession(lease);
        verify(ws).close(any(CloseStatus.class));
    }

    @Test
    void shutdownClosesActiveSerialConsoleWebSockets() throws Exception {
        ConsoleService service = mock(ConsoleService.class);
        ReflectionTestUtils.setField(handler, "consoleService", service);
        WebSocketSession ws = session(Map.of());
        when(ws.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(ws);

        handler.shutdownExecutors();

        verify(ws).close(any(CloseStatus.class));
        assertThat(((ExecutorService) ReflectionTestUtils.getField(handler, "ioPool")).isShutdown()).isTrue();
    }

    @Test
    void preservesSerialBytesWithIso88591() throws Exception {
        WebSocketSession ws = session(Map.of());
        when(ws.isOpen()).thenReturn(true);
        byte[] bytes = new byte[]{0x1b, (byte) 0x80, (byte) 0xff};

        Method sendOutput = WebSshConsoleTerminalWebSocketHandler.class.getDeclaredMethod(
                "sendConsoleOutput", WebSocketSession.class, byte[].class, int.class);
        sendOutput.setAccessible(true);
        sendOutput.invoke(null, ws, bytes, bytes.length);

        ArgumentCaptor<TextMessage> message = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(message.capture());
        assertThat(message.getValue().getPayload().getBytes(StandardCharsets.ISO_8859_1))
                .containsExactly(bytes);
    }

    private static int staticInt(String fieldName) throws Exception {
        Field field = WebSshConsoleTerminalWebSocketHandler.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static WebSocketSession session(Map<String, Object> initialAttributes) {
        WebSocketSession ws = mock(WebSocketSession.class);
        Map<String, Object> attributes = new ConcurrentHashMap<>(initialAttributes);
        when(ws.getAttributes()).thenReturn(attributes);
        return ws;
    }
}
