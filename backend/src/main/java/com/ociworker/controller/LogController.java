package com.ociworker.controller;

import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.LogPersistService;
import com.ociworker.service.WebSocketTicketService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/log")
public class LogController {

    @Resource
    private LogPersistService logPersistService;
    @Resource
    private WebSocketTicketService webSocketTicketService;
    private static final int DEFAULT_SEARCH_LIMIT = 1000;
    private static final int MAX_SEARCH_LIMIT = 3000;

    @PostMapping("/search")
    public ResponseData<?> search(@RequestBody(required = false) Map<String, Object> params) {
        String keyword = normalizeKeyword(params == null ? null : params.get("keyword"));
        int limit = normalizeLimit(params == null ? null : params.get("limit"));
        return ResponseData.ok(logPersistService.searchRecent(keyword, limit));
    }

    @PostMapping("/ws-ticket")
    public ResponseData<?> issueWebSocketTicket() {
        return ResponseData.ok(Map.of("ticket", webSocketTicketService.issue()));
    }

    private String normalizeKeyword(Object rawKeyword) {
        return rawKeyword == null ? "" : String.valueOf(rawKeyword);
    }

    private int normalizeLimit(Object rawLimit) {
        int value = DEFAULT_SEARCH_LIMIT;
        if (rawLimit instanceof Number n) {
            value = n.intValue();
        } else if (rawLimit instanceof String s && !s.isBlank()) {
            try {
                value = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                value = DEFAULT_SEARCH_LIMIT;
            }
        }
        return Math.max(1, Math.min(MAX_SEARCH_LIMIT, value));
    }
}
