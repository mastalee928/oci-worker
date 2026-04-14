package com.ociworker.websocket;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class WebSocketLogAppender extends AppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    protected void append(ILoggingEvent event) {
        try {
            String ts = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(event.getTimeStamp()), ZoneId.systemDefault()
            ).format(FMT);
            String msg = ts + " " + event.getLevel() + "  " + event.getFormattedMessage();
            LogWebSocketHandler.broadcast(msg);
        } catch (Exception ignored) {}
    }
}
