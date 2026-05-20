package com.ociworker.service;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.ociworker.enums.SysCfgEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Telegram 通知 Bot/Chat 变更：新配置立即生效，旧配置暂存 15 分钟；
 * 向旧 TG 发安全提示与「拉黑该 IP / 拒绝更改」按钮，旧 Bot 短时轮询以接收回调。
 */
@Slf4j
@Service
public class TgNotifyConfigRollbackService {

    public static final long ROLLBACK_TTL_MS = 15 * 60 * 1000L;
    private static final String CALLBACK_PREFIX_REJECT = "n|";

    @Resource
    private NotificationService notificationService;
    @Resource
    private LoginSecurityService loginSecurityService;
    @Lazy
    @Resource
    private TelegramInboundUpdateDispatcher telegramInboundUpdateDispatcher;

    private volatile String pollerSessionId;
    private volatile Thread oldBotPollerThread;

    @EventListener(ApplicationReadyEvent.class)
    public void resumeOldBotPollerIfNeeded() {
        if (!hasRollbackSession() || isRollbackExpired()) {
            if (hasRollbackSession() && isRollbackExpired()) {
                clearRollbackState(true);
            }
            return;
        }
        String oldToken = notificationService.getKvValue(SysCfgEnum.TG_ROLLBACK_OLD_BOT_TOKEN);
        String sessionId = notificationService.getKvValue(SysCfgEnum.TG_ROLLBACK_SESSION_ID);
        if (StrUtil.isNotBlank(oldToken) && StrUtil.isNotBlank(sessionId)) {
            startOldBotPoller(oldToken.trim(), sessionId.trim());
            log.info("[TG rollback] resumed old-bot poller for session {}", sessionId);
        }
    }

    /**
     * 写入新 Token/Chat、暂存旧配置、向旧 TG 发带按钮的安全提示，并启动旧 Bot 轮询。
     */
    public void applyIdentityChange(
            String oldBotToken,
            String oldChatId,
            String newBotToken,
            String newChatId,
            String offenderIp,
            String offenderDeviceId) {
        clearRollbackState(false);

        String sessionId = RandomUtil.randomString("abcdef0123456789", 16);
        long expireAt = System.currentTimeMillis() + ROLLBACK_TTL_MS;

        notificationService.saveKvValue(SysCfgEnum.TG_ROLLBACK_SESSION_ID, sessionId);
        notificationService.saveKvValue(SysCfgEnum.TG_ROLLBACK_OLD_BOT_TOKEN, oldBotToken);
        notificationService.saveKvValue(SysCfgEnum.TG_ROLLBACK_OLD_CHAT_ID, oldChatId);
        notificationService.saveKvValue(SysCfgEnum.TG_ROLLBACK_EXPIRE_AT, String.valueOf(expireAt));
        notificationService.removeKvValue(SysCfgEnum.TG_ROLLBACK_UPDATES_OFFSET);

        notificationService.saveKvValue(SysCfgEnum.TG_BOT_TOKEN, newBotToken);
        notificationService.saveKvValue(SysCfgEnum.TG_CHAT_ID, newChatId);
        notificationService.resetTelegramUpdatesOffset();

        String alertText = formatIdentityChangedAlert(offenderIp, offenderDeviceId);
        List<List<Map<String, String>>> rows = buildAlertKeyboard(offenderIp, sessionId);
        notificationService.sendSecurityTextWithInlineKeyboard(oldBotToken, oldChatId, alertText, rows);

        startOldBotPoller(oldBotToken.trim(), sessionId);
        log.info("[TG rollback] identity change applied; session={} expireAt={}", sessionId, expireAt);
    }

    /**
     * @return true 表示已处理（含 n| 拒绝更改）
     */
    public boolean tryHandleTelegramCallback(String rawData, String callbackQueryId, String answeringBotToken) {
        if (rawData == null || !rawData.startsWith(CALLBACK_PREFIX_REJECT)) {
            return false;
        }
        String token = rawData.substring(CALLBACK_PREFIX_REJECT.length());
        if (token.length() > 32) {
            notificationService.answerTelegramCallbackQuery(callbackQueryId, "无效操作", false, answeringBotToken);
            return true;
        }
        if (!isRollbackSessionValid(token)) {
            notificationService.answerTelegramCallbackQuery(callbackQueryId,
                    "操作已过期（超过 15 分钟或已处理）", false, answeringBotToken);
            return true;
        }
        rejectAndRestore();
        notificationService.answerTelegramCallbackQuery(callbackQueryId,
                "已拒绝更改，Telegram 通知已恢复为原配置", false, answeringBotToken);
        return true;
    }

    @Scheduled(fixedRate = 60_000)
    public void purgeExpiredRollback() {
        if (!hasRollbackSession()) {
            return;
        }
        if (isRollbackExpired()) {
            log.info("[TG rollback] session expired, clearing staged old config");
            clearRollbackState(true);
        }
    }

    private void rejectAndRestore() {
        String oldToken = notificationService.getKvValue(SysCfgEnum.TG_ROLLBACK_OLD_BOT_TOKEN);
        String oldChatId = notificationService.getKvValue(SysCfgEnum.TG_ROLLBACK_OLD_CHAT_ID);
        if (StrUtil.isBlank(oldToken) || StrUtil.isBlank(oldChatId)) {
            clearRollbackState(true);
            return;
        }
        notificationService.saveKvValue(SysCfgEnum.TG_BOT_TOKEN, oldToken.trim());
        notificationService.saveKvValue(SysCfgEnum.TG_CHAT_ID, oldChatId.trim());
        notificationService.resetTelegramUpdatesOffset();
        clearRollbackState(true);
        log.warn("[TG rollback] notify config reverted to previous bot/chat via TG reject");
    }

    private synchronized void startOldBotPoller(String oldBotToken, String sessionId) {
        stopOldBotPoller();
        pollerSessionId = sessionId;
        Thread t = Thread.ofVirtual().name("oci-tg-rollback-getUpdates").unstarted(() -> pollOldBot(oldBotToken, sessionId));
        t.setDaemon(true);
        oldBotPollerThread = t;
        t.start();
    }

    private synchronized void stopOldBotPoller() {
        pollerSessionId = null;
        Thread t = oldBotPollerThread;
        oldBotPollerThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private void pollOldBot(String oldBotToken, String sessionId) {
        while (Objects.equals(sessionId, pollerSessionId) && isRollbackSessionValid(sessionId)) {
            try {
                long offset = readRollbackOffset();
                JSONArray updates = notificationService.telegramGetUpdates(oldBotToken, offset, 25);
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
                    if (uid > 0) {
                        maxSeen = Math.max(maxSeen, uid);
                    }
                    telegramInboundUpdateDispatcher.dispatchUpdateJson(u.toString(), oldBotToken);
                }
                if (maxSeen >= 0) {
                    saveRollbackOffset(maxSeen + 1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[TG rollback] old bot getUpdates: {}", e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private List<List<Map<String, String>>> buildAlertKeyboard(String offenderIp, String sessionId) {
        List<Map<String, String>> row = new ArrayList<>();
        String blockTok = loginSecurityService.registerBlockIpCallback(offenderIp);
        if (blockTok != null) {
            row.add(Map.of("text", "拉黑该IP", "callback_data", "i|" + blockTok));
        }
        row.add(Map.of("text", "拒绝更改", "callback_data", CALLBACK_PREFIX_REJECT + sessionId));
        return List.of(row);
    }

    private static String formatIdentityChangedAlert(String ip, String deviceId) {
        String ipLine = StrUtil.isNotBlank(ip) ? ip.trim() : "—";
        String devLine = StrUtil.isNotBlank(deviceId) ? deviceId.trim() : "—";
        return "【OCI WORKER 安全提示】\n"
                + "Telegram 通知配置已更改！\n"
                + "如非本人操作，请立即处理。\n"
                + "IP: " + ipLine + "\n"
                + "设备: " + devLine + "\n\n"
                + "（15 分钟内可点「拒绝更改」恢复原配置）";
    }

    private boolean hasRollbackSession() {
        return StrUtil.isNotBlank(notificationService.getKvValue(SysCfgEnum.TG_ROLLBACK_SESSION_ID));
    }

    private boolean isRollbackExpired() {
        String exp = notificationService.getKvValue(SysCfgEnum.TG_ROLLBACK_EXPIRE_AT);
        if (StrUtil.isBlank(exp)) {
            return true;
        }
        try {
            return System.currentTimeMillis() > Long.parseLong(exp.trim());
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private boolean isRollbackSessionValid(String sessionId) {
        if (StrUtil.isBlank(sessionId) || isRollbackExpired()) {
            return false;
        }
        String stored = notificationService.getKvValue(SysCfgEnum.TG_ROLLBACK_SESSION_ID);
        return sessionId.equals(StrUtil.trimToNull(stored));
    }

    private void clearRollbackState(boolean stopPoller) {
        if (stopPoller) {
            stopOldBotPoller();
        }
        notificationService.removeKvValue(SysCfgEnum.TG_ROLLBACK_SESSION_ID);
        notificationService.removeKvValue(SysCfgEnum.TG_ROLLBACK_OLD_BOT_TOKEN);
        notificationService.removeKvValue(SysCfgEnum.TG_ROLLBACK_OLD_CHAT_ID);
        notificationService.removeKvValue(SysCfgEnum.TG_ROLLBACK_EXPIRE_AT);
        notificationService.removeKvValue(SysCfgEnum.TG_ROLLBACK_UPDATES_OFFSET);
    }

    private long readRollbackOffset() {
        String v = StrUtil.trimToNull(notificationService.getKvValue(SysCfgEnum.TG_ROLLBACK_UPDATES_OFFSET));
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void saveRollbackOffset(long nextOffset) {
        if (nextOffset <= 0) {
            return;
        }
        notificationService.saveKvValue(SysCfgEnum.TG_ROLLBACK_UPDATES_OFFSET, String.valueOf(nextOffset));
    }
}
