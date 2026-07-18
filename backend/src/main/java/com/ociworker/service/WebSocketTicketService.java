package com.ociworker.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WebSocketTicketService {

    private static final long TICKET_TTL_MILLIS = 30_000L;
    private static final int TICKET_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Long> tickets = new ConcurrentHashMap<>();
    private final Clock clock;

    public WebSocketTicketService() {
        this(Clock.systemUTC());
    }

    WebSocketTicketService(Clock clock) {
        this.clock = clock;
    }

    /** 签发仅用于一次 WebSocket 握手的短效票据，不暴露面板长效 Token。 */
    public String issue() {
        cleanupExpired();
        byte[] random = new byte[TICKET_BYTES];
        secureRandom.nextBytes(random);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        tickets.put(hash(ticket), clock.millis() + TICKET_TTL_MILLIS);
        return ticket;
    }

    /** 原子消费票据：无论成功或过期，同一票据都不能再次使用。 */
    public boolean consume(String ticket) {
        if (!isValidFormat(ticket)) {
            return false;
        }
        Long expiresAt = tickets.remove(hash(ticket));
        return expiresAt != null && expiresAt >= clock.millis();
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void cleanupExpired() {
        long now = clock.millis();
        tickets.forEach((key, expiresAt) -> {
            if (expiresAt < now) tickets.remove(key, expiresAt);
        });
    }

    private static boolean isValidFormat(String ticket) {
        if (ticket == null || ticket.length() != 43) return false;
        for (int i = 0; i < ticket.length(); i++) {
            char c = ticket.charAt(i);
            boolean valid = c >= 'a' && c <= 'z'
                    || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9'
                    || c == '-' || c == '_';
            if (!valid) return false;
        }
        return true;
    }

    private static String hash(String ticket) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(ticket.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
