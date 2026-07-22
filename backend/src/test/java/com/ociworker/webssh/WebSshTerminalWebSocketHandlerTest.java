package com.ociworker.webssh;

import com.jcraft.jsch.ChannelShell;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSshTerminalWebSocketHandlerTest {

    private ExecutorService ioPool;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> firstMessageFuture;
    private WebSshTerminalWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        ioPool = mock(ExecutorService.class);
        scheduler = mock(ScheduledExecutorService.class);
        firstMessageFuture = mock(ScheduledFuture.class);
        doReturn(firstMessageFuture).when(scheduler)
                .schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS));
        handler = new WebSshTerminalWebSocketHandler(ioPool, scheduler);
        ReflectionTestUtils.setField(handler, "firstMessageTimeoutSeconds", 20);
        ReflectionTestUtils.setField(handler, "maxConcurrentSessions", 64);
        ReflectionTestUtils.setField(handler, "maxInputMessageSize", 65_536);
        ReflectionTestUtils.setField(handler, "sendTimeLimitMs", 10_000);
        ReflectionTestUtils.setField(handler, "sendBufferBytes", 1_048_576);
    }

    @Test
    void decoratesAcceptedSessionAndClosesItWhenFirstMessageTimesOut() throws Exception {
        WebSocketSession ws = session("one");
        ArgumentCaptor<Runnable> timeoutTask = ArgumentCaptor.forClass(Runnable.class);

        handler.afterConnectionEstablished(ws);

        assertThat(ws.getAttributes().values())
                .anyMatch(ConcurrentWebSocketSessionDecorator.class::isInstance);
        assertThat(handler.activeSessionCount()).isEqualTo(1);
        verify(scheduler).schedule(timeoutTask.capture(), eq(20L), eq(TimeUnit.SECONDS));

        timeoutTask.getValue().run();

        verify(ws).close(org.mockito.ArgumentMatchers.argThat(
                status -> status.getCode() == CloseStatus.NORMAL.getCode()));
        verify(firstMessageFuture).cancel(false);
        assertThat(handler.activeSessionCount()).isZero();
    }

    @Test
    void rejectsConnectionBeyondGlobalSessionLimitAndReleasesSlotOnClose() throws Exception {
        ReflectionTestUtils.setField(handler, "maxConcurrentSessions", 1);
        WebSocketSession first = session("first");
        WebSocketSession rejected = session("rejected");
        WebSocketSession next = session("next");

        handler.afterConnectionEstablished(first);
        handler.afterConnectionEstablished(rejected);

        verify(rejected).close(org.mockito.ArgumentMatchers.argThat(
                status -> status.getCode() == CloseStatus.SERVICE_OVERLOAD.getCode()));
        assertThat(handler.activeSessionCount()).isEqualTo(1);

        handler.afterConnectionClosed(first, CloseStatus.NORMAL);
        handler.afterConnectionEstablished(next);

        assertThat(handler.activeSessionCount()).isEqualTo(1);
        handler.afterConnectionClosed(next, CloseStatus.NORMAL);
        assertThat(handler.activeSessionCount()).isZero();
    }

    @Test
    void cancelsTimerWhenConnectionClosesBeforeScheduleReturns() throws Exception {
        WebSocketSession ws = session("timer-race");
        doAnswer(invocation -> {
            handler.afterConnectionClosed(ws, CloseStatus.GOING_AWAY);
            return firstMessageFuture;
        }).when(scheduler).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS));

        handler.afterConnectionEstablished(ws);

        verify(firstMessageFuture).cancel(false);
        assertThat(handler.activeSessionCount()).isZero();
    }

    @Test
    void cancelsReaderWhenConnectionClosesBeforeSubmitReturns() throws Exception {
        WebSocketSession ws = session("reader-race");
        java.util.concurrent.Future<?> reader = mock(java.util.concurrent.Future.class);
        handler.afterConnectionEstablished(ws);
        doAnswer(invocation -> {
            handler.afterConnectionClosed(ws, CloseStatus.GOING_AWAY);
            return reader;
        }).when(ioPool).submit(any(Runnable.class));

        handler.handleMessage(ws, new TextMessage("e30="));

        verify(reader).cancel(true);
        assertThat(handler.activeSessionCount()).isZero();
    }

    @Test
    void rejectsOversizedInitialSshMessageBeforeStartingWorker() throws Exception {
        WebSocketSession ws = session("large-initial");
        handler.afterConnectionEstablished(ws);

        handler.handleMessage(ws, new TextMessage(
                "A".repeat(WebSshTerminalWebSocketHandler.MAX_INITIAL_MESSAGE_SIZE + 1)));

        verify(ws).close(org.mockito.ArgumentMatchers.argThat(
                status -> status.getCode() == CloseStatus.TOO_BIG_TO_PROCESS.getCode()));
        verify(ioPool, never()).submit(any(Runnable.class));
        assertThat(handler.activeSessionCount()).isZero();
    }

    @Test
    void rejectsBinaryMessageInsteadOfSilentlyIgnoringIt() throws Exception {
        WebSocketSession ws = session("binary");
        handler.afterConnectionEstablished(ws);

        handler.handleMessage(ws, new BinaryMessage(ByteBuffer.wrap(new byte[]{1, 2, 3})));

        verify(ws).close(org.mockito.ArgumentMatchers.argThat(
                status -> status.getCode() == CloseStatus.BAD_DATA.getCode()));
        assertThat(handler.activeSessionCount()).isZero();
    }

    @Test
    void rejectsOversizedUtf8TerminalInput() throws Exception {
        WebSocketSession ws = session("large-input");
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        ws.getAttributes().put("started", Boolean.TRUE);
        ws.getAttributes().put("stdin", stdin);

        handler.handleMessage(ws, new TextMessage("中".repeat(21_846)));

        verify(ws).close(org.mockito.ArgumentMatchers.argThat(
                status -> status.getCode() == CloseStatus.TOO_BIG_TO_PROCESS.getCode()));
        assertThat(stdin.size()).isZero();
    }

    @Test
    void clampsResizeToSupportedTerminalBounds() throws Exception {
        WebSocketSession ws = session("resize");
        ChannelShell shell = mock(ChannelShell.class);
        ws.getAttributes().put("started", Boolean.TRUE);
        ws.getAttributes().put("shell", shell);

        handler.handleMessage(ws, new TextMessage("resize:1:9999"));

        verify(shell).setPtySize(500, 5, 0, 0);
        assertThat(WebSshTerminalWebSocketHandler.normalizeColumns(-1)).isEqualTo(20);
        assertThat(WebSshTerminalWebSocketHandler.normalizeRows(9999)).isEqualTo(200);
    }

    @Test
    void cleansUpOnTransportErrorAndShutsDownOwnedExecutors() throws Exception {
        WebSocketSession ws = session("transport-error");
        java.util.concurrent.Future<?> reader = mock(java.util.concurrent.Future.class);
        ws.getAttributes().put("reader", reader);

        handler.handleTransportError(ws, new IllegalStateException("broken"));

        verify(reader).cancel(true);
        verify(ws).close(org.mockito.ArgumentMatchers.argThat(
                status -> status.getCode() == CloseStatus.SERVER_ERROR.getCode()));

        handler.shutdownExecutors();
        verify(scheduler).shutdownNow();
        verify(ioPool).shutdownNow();
    }

    @Test
    void closesActiveWebSocketBeforeShuttingDownExecutors() throws Exception {
        WebSocketSession ws = session("shutdown");
        handler.afterConnectionEstablished(ws);

        handler.shutdownExecutors();

        verify(ws).close(org.mockito.ArgumentMatchers.argThat(
                status -> status.getCode() == CloseStatus.SERVICE_RESTARTED.getCode()));
        verify(firstMessageFuture).cancel(false);
        assertThat(handler.activeSessionCount()).isZero();
        verify(scheduler).shutdownNow();
        verify(ioPool).shutdownNow();
    }

    private static WebSocketSession session(String id) {
        WebSocketSession ws = mock(WebSocketSession.class);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        when(ws.getAttributes()).thenReturn(attributes);
        when(ws.getId()).thenReturn(id);
        when(ws.getUri()).thenReturn(URI.create("ws://localhost/webssh-api/term?cols=150&rows=35"));
        when(ws.isOpen()).thenReturn(true);
        return ws;
    }
}
