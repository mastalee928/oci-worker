package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.ociworker.enums.SysCfgEnum;
import com.ociworker.util.SecureRandomUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Slf4j
@Service
public class WorkerInstanceSecretService {

    @Resource
    private NotificationService notificationService;

    private final Object lock = new Object();
    private volatile String cachedSecret;

    public String getSecret() {
        String cached = StrUtil.trimToNull(cachedSecret);
        if (cached != null) {
            return cached;
        }
        synchronized (lock) {
            cached = StrUtil.trimToNull(cachedSecret);
            if (cached != null) {
                return cached;
            }
            String stored = StrUtil.trimToNull(notificationService.getKvValue(SysCfgEnum.WORKER_INSTANCE_SECRET));
            if (!isValidSecret(stored)) {
                stored = createSecret();
                notificationService.saveKvValue(SysCfgEnum.WORKER_INSTANCE_SECRET, stored);
            }
            cachedSecret = stored;
            return stored;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            getSecret();
        } catch (Exception e) {
            log.warn("Failed to warm up worker instance secret: {}", e.getMessage());
        }
    }

    private static boolean isValidSecret(String secret) {
        return secret != null && secret.matches("[0-9a-f]{128}");
    }

    private static String createSecret() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            digest.update(SecureRandomUtil.randomHex(64).getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toString(System.nanoTime()).getBytes(StandardCharsets.UTF_8));
            digest.update(ManagementFactory.getRuntimeMXBean().getName().getBytes(StandardCharsets.UTF_8));
            return SecureRandomUtil.toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            return SecureRandomUtil.randomHex(64);
        }
    }
}
