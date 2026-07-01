package com.ociworker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 处理 Telegram {@code Update}（原先由 Webhook POST 投递，现由 getUpdates 拉取后调用）。
 */
@Slf4j
@Service
public class TelegramInboundUpdateDispatcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private LoginSecurityService loginSecurityService;
    @Resource
    private NotificationService notificationService;
    @Resource
    private TelegramBotCommandService telegramBotCommandService;
    @Resource
    private TgNotifyConfigRollbackService tgNotifyConfigRollbackService;
    @Resource
    private ShapeEditTaskManager shapeEditTaskManager;
    @Resource
    private TaskSchedulerService taskSchedulerService;

    public void dispatchUpdateJson(String updateJson) {
        dispatchUpdateJson(updateJson, null);
    }

    public void dispatchUpdateJson(String updateJson, String receivingBotToken) {
        if (updateJson == null || updateJson.isBlank()) {
            return;
        }
        try {
            dispatchUpdate(MAPPER.readTree(updateJson), receivingBotToken);
        } catch (Exception e) {
            log.warn("[TG] parse update failed: {}", e.getMessage());
        }
    }

    public void dispatchUpdate(JsonNode root) {
        dispatchUpdate(root, null);
    }

    public void dispatchUpdate(JsonNode root, String receivingBotToken) {
        try {
            JsonNode cq = root.get("callback_query");
            if (cq != null && cq.has("id") && cq.has("data")) {
                String id = cq.get("id").asText();
                String data = cq.get("data").asText();
                if ("copy_noop".equals(data)) {
                    notificationService.answerTelegramCallbackQuery(id, "", false, receivingBotToken);
                } else if (!tgNotifyConfigRollbackService.tryHandleTelegramCallback(data, id, receivingBotToken)) {
                    if (!shapeEditTaskManager.tryHandleTelegramCallback(data, id, receivingBotToken)) {
                        if (!taskSchedulerService.tryHandleTelegramCallback(data, id, receivingBotToken)) {
                            loginSecurityService.handleTelegramCallback(data, id, receivingBotToken);
                        }
                    }
                }
            }
            JsonNode msg = root.get("message");
            if (msg == null) {
                msg = root.get("edited_message");
            }
            if (msg != null) {
                telegramBotCommandService.handleTelegramMessage(msg);
            }
        } catch (Exception e) {
            log.warn("[TG] dispatch update error: {}", e.getMessage());
        }
    }
}
