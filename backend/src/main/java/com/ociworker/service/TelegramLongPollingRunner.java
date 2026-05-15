package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.ociworker.enums.SysCfgEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Telegram Bot 使用 {@code getUpdates} 长轮询接收 {@code message} / {@code callback_query}，不再使用 Webhook。
 * <p>单虚拟线程循环；换 Bot Token 时会再次 {@code deleteWebhook} 并依赖 {@link NotificationService#resetTelegramUpdatesOffset()} 重置游标。</p>
 */
@Slf4j
@Component
public class TelegramLongPollingRunner {

    private static final int LONG_POLL_TIMEOUT_SEC = 25;

    @Resource
    private VerifyCodeService verifyCodeService;
    @Resource
    private NotificationService notificationService;
    @Resource
    private TelegramInboundUpdateDispatcher telegramInboundUpdateDispatcher;

    private volatile String lastWebhookCleanupToken;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread t = Thread.ofVirtual().name("oci-tg-getUpdates").unstarted(this::runForever);
        t.setDaemon(true);
        t.start();
        log.info("[TG] getUpdates long-poll thread started");
    }

    private void runForever() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (!verifyCodeService.isTgConfigured()) {
                    Thread.sleep(5000);
                    continue;
                }
                String token = notificationService.getKvValue(SysCfgEnum.TG_BOT_TOKEN);
                if (StrUtil.isBlank(token)) {
                    Thread.sleep(5000);
                    continue;
                }

                if (!token.equals(lastWebhookCleanupToken)) {
                    boolean cleared = notificationService.telegramDeleteWebhook(token);
                    lastWebhookCleanupToken = token;
                    log.info("[TG] deleteWebhook for getUpdates mode: ok={}", cleared);
                }

                long nextOffset = notificationService.getTelegramUpdatesNextOffset();
                JSONArray updates = notificationService.telegramGetUpdates(token, nextOffset, LONG_POLL_TIMEOUT_SEC);
                if (updates == null) {
                    Thread.sleep(2000);
                    continue;
                }

                long maxSeen = -1L;
                for (int i = 0; i < updates.size(); i++) {
                    JSONObject u = updates.getJSONObject(i);
                    if (u == null) {
                        continue;
                    }
                    Long uidObj = u.getLong("update_id");
                    long uid = uidObj == null ? 0L : uidObj;
                        maxSeen = Math.max(maxSeen, uid);
                    }
                    telegramInboundUpdateDispatcher.dispatchUpdateJson(u.toString());
                }
                if (maxSeen >= 0) {
                    notificationService.saveTelegramUpdatesNextOffset(maxSeen + 1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[TG] getUpdates loop: {}", e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
