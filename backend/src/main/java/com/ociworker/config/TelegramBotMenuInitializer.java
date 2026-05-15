package com.ociworker.config;

import com.ociworker.service.NotificationService;
import com.ociworker.service.VerifyCodeService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 应用就绪后向 Telegram 注册斜杠命令菜单（需已配置 Bot）。
 */
@Slf4j
@Component
public class TelegramBotMenuInitializer {

    @Resource
    private VerifyCodeService verifyCodeService;
    @Resource
    private NotificationService notificationService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!verifyCodeService.isTgConfigured()) {
            return;
        }
        try {
            notificationService.registerTelegramBotCommands();
        } catch (Exception e) {
            log.warn("Telegram setMyCommands skipped: {}", e.getMessage());
        }
    }
}
