package com.ociworker.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketTicketServiceTest {

    @Test
    void ticketCanBeConsumedOnlyOnce() {
        MutableClock clock = new MutableClock(1_000L);
        WebSocketTicketService service = new WebSocketTicketService(clock);
        String ticket = service.issue();

        assertThat(ticket).hasSize(43);
        assertThat(service.consume(ticket)).isTrue();
        assertThat(service.consume(ticket)).isFalse();
    }

    @Test
    void expiredTicketIsRejectedAndRemoved() {
        MutableClock clock = new MutableClock(1_000L);
        WebSocketTicketService service = new WebSocketTicketService(clock);
        String ticket = service.issue();

        clock.setMillis(31_001L);

        assertThat(service.consume(ticket)).isFalse();
        assertThat(service.consume(ticket)).isFalse();
    }

    @Test
    void malformedTicketIsRejected() {
        WebSocketTicketService service = new WebSocketTicketService();

        assertThat(service.consume(null)).isFalse();
        assertThat(service.consume("short")).isFalse();
        assertThat(service.consume("这不是一个有效的WebSocket票据________________________________")).isFalse();
    }

    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        void setMillis(long millis) {
            this.millis = millis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}
