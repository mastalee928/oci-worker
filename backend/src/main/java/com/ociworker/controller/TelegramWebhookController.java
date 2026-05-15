package com.ociworker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ociworker.service.LoginSecurityService;
import com.ociworker.service.NotificationService;
import com.ociworker.service.TelegramBotCommandService;
import com.ociworker.util.SecretCompare;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Telegram Bot Webhook：路径须与 {@code ociworker.telegram.webhook-secret} 一致；
 * 可选校验 {@code ociworker.telegram.webhook-secret-token} 与请求头 {@code X-Telegram-Bot-Api-Secret-Token}（与 setWebhook 的 {@code secret_token} 一致）。
 * <pre>
 * curl -G "https://api.telegram.org/bot&lt;TOKEN&gt;/setWebhook" \
 *   --data-urlencode "url=https://你的域名/api/tg/callback/&lt;PATH_SECRET&gt;" \
 *   --data-urlencode "secret_token=&lt;仅含A-Za-z0-9_-&gt;" \
 *   --data-urlencode "allowed_updates=[\"message\",\"callback_query\"]"
 * （须包含 message 才能接收 /start 等文字命令）
 * </pre>
@Slf4j
@RestController
@RequestMapping("/api/tg")
public class TelegramWebhookController {

    private static final String TG_SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    @Value("${ociworker.telegram.webhook-secret:}")
    private String webhookSecret;

    @Value("${ociworker.telegram.webhook-secret-token:}")
    private String webhookSecretToken;

    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private LoginSecurityService loginSecurityService;
    @Resource
    private NotificationService notificationService;
    @Resource
    private TelegramBotCommandService telegramBotCommandService;

    @PostMapping("/callback/{secret}")
    public void callback(
            @PathVariable String secret,
            @RequestBody String body,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (webhookSecret == null || webhookSecret.isBlank() || !SecretCompare.equalsUtf8(webhookSecret, secret)) {
            response.setStatus(404);
            return;
        }
        if (webhookSecretToken != null && !webhookSecretToken.isBlank()) {
            String header = request.getHeader(TG_SECRET_HEADER);
            if (!SecretCompare.equalsUtf8(webhookSecretToken, header)) {
                response.setStatus(404);
                return;
            }
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode cq = root.get("callback_query");
            if (cq != null && cq.has("id") && cq.has("data")) {
                String id = cq.get("id").asText();
                String data = cq.get("data").asText();
                if ("copy_noop".equals(data)) {
                    notificationService.answerTelegramCallbackQuery(id, "", false);
                } else {
                    loginSecurityService.handleTelegramCallback(data, id);
                }
            }
            JsonNode msg = root.get("message");
            if (msg != null) {
                telegramBotCommandService.handleTelegramMessage(msg);
            }
        } catch (Exception e) {
            log.warn("Telegram webhook parse error: {}", e.getMessage());
        }
        response.setStatus(200);
    }
}
