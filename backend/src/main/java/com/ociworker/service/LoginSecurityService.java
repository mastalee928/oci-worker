package com.ociworker.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.ociworker.enums.SysCfgEnum;
import com.ociworker.util.HttpRequestUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录安全：IP/设备黑名单、全站暂停、密码失败计数与 Telegram 回调令牌。
 */
@Slf4j
@Service
public class LoginSecurityService {

    private static final long PENDING_TTL_MS = 15 * 60 * 1000L;
    /** 禁止名单管理内联按钮有效期（可慢慢点，略长于登录失败按钮） */
    private static final long DENYLIST_UI_TTL_MS = 30 * 60 * 1000L;
    private static final long FAIL_WINDOW_MS = 15 * 60 * 1000L;
    private static final int PAUSE_OFFER_THRESHOLD = 5;

    private enum PendingKind {
        BLOCK_IP, BLOCK_DEVICE, PAUSE_SITE, RESUME_SITE, IGNORE_FAILS, UNBLOCK_IP, UNBLOCK_DEVICE
    }

    private record Pending(PendingKind kind, String ip, String deviceId, long expireAt) {}

    private static final class IpFailWindow {
        final AtomicInteger count = new AtomicInteger(0);
        volatile long windowStart = System.currentTimeMillis();
        volatile boolean pauseOfferSent;
    }

    private final ConcurrentHashMap<String, Pending> pendingByToken = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IpFailWindow> ipFailWindows = new ConcurrentHashMap<>();

    @Resource
    private NotificationService notificationService;
    @Resource
    private VerifyCodeService verifyCodeService;

    public boolean isSitePaused() {
        String v = notificationService.getKvValue(SysCfgEnum.SITE_ACCESS_PAUSED);
        return "true".equalsIgnoreCase(StrUtil.trim(v));
    }

    /** TG / 管理用：设置全站 API 暂停（true=503 除白名单外）。 */
    public void setSitePaused(boolean paused) {
        notificationService.saveKvValue(SysCfgEnum.SITE_ACCESS_PAUSED, paused ? "true" : "false");
        log.warn("[LoginSecurity] site_access_paused = {}", paused);
    }

    /**
     * 当前 IP 或设备是否在禁止名单（登录接口与已通过 Bearer 鉴权后的 /api/** 请求均会校验）。
     */
    public boolean isDeniedForLogin(String ip, String deviceId) {
        if (containsIp(readIpDenylist(), normalizeIp(ip))) return true;
        if (StrUtil.isNotBlank(deviceId) && containsToken(readDeviceDenylist(), deviceId.trim())) return true;
        return false;
    }

    public boolean isLoginHardenedPath(String uri) {
        if (uri == null) return false;
        return "/api/auth/login".equals(uri)
                || "/api/auth/tgLogin".equals(uri)
                || "/api/auth/tgLoginSendCode".equals(uri);
    }

    /**
     * 全站暂停时仍须放行的路径前缀（顺序无关，仅用于前缀匹配）。
     */
    public boolean isExemptFromSitePause(String uri) {
        if (uri == null) return false;
        if (uri.startsWith("/api/auth/device")) return true;
        if (uri.startsWith("/api/auth/needSetup")) return true;
        if (uri.startsWith("/api/auth/setup")) return true;
        if (uri.startsWith("/ws/")) return true;
        if (uri.equals("/") || uri.startsWith("/assets/")) return true;
        if (uri.endsWith(".html") || uri.endsWith(".js") || uri.endsWith(".css") || uri.endsWith(".ico")) return true;
        if (uri.startsWith("/webssh/")) return true;
        if (uri.startsWith("/ip-info")) return true;
        return false;
    }

    public void onPasswordLoginFailed(String account, String ip, String deviceId) {
        String ipN = normalizeIp(ip);
        String dev = StrUtil.isBlank(deviceId) ? null : deviceId.trim();

        if (!verifyCodeService.isTgConfigured()) {
            notificationService.sendMessage(NotificationService.TYPE_LOGIN,
                    String.format("【登录通知】⚠️ 登录失败\n账号: %s\nIP: %s\n时间: %s",
                            account, ipN, nowStr()));
            return;
        }

        String tokIp = registerPending(new Pending(PendingKind.BLOCK_IP, ipN, null,
                System.currentTimeMillis() + PENDING_TTL_MS));
        String tokDev = null;
        if (StrUtil.isNotBlank(dev)) {
            tokDev = registerPending(new Pending(PendingKind.BLOCK_DEVICE, ipN, dev,
                    System.currentTimeMillis() + PENDING_TTL_MS));
        }

        List<List<Map<String, String>>> rows = new ArrayList<>();
        List<Map<String, String>> row1 = new ArrayList<>();
        row1.add(Map.of("text", "拉黑该IP", "callback_data", "i|" + tokIp));
        rows.add(row1);
        if (tokDev != null) {
            List<Map<String, String>> row2 = new ArrayList<>();
            row2.add(Map.of("text", "禁止该设备", "callback_data", "d|" + tokDev));
            rows.add(row2);
        }

        String text = String.format(
                "【登录通知】⚠️ 登录失败\n账号: %s\nIP: %s\n设备: %s\n时间: %s\n\n（15 分钟内有效）点击下方按钮执行操作。",
                account, ipN, dev != null ? dev : "未知", nowStr());
        notificationService.sendSecurityTextWithInlineKeyboard(text, rows);

        int n = bumpFailureCount(ipN);
        if (n == PAUSE_OFFER_THRESHOLD) {
            maybeSendPauseOffer(ipN, n);
        }
    }

    private void maybeSendPauseOffer(String ipN, int n) {
        IpFailWindow w = ipFailWindows.get(ipN);
        if (w == null) return;
        synchronized (w) {
            if (n != PAUSE_OFFER_THRESHOLD || w.pauseOfferSent) return;
            w.pauseOfferSent = true;
        }
        String tokPause = registerPending(new Pending(PendingKind.PAUSE_SITE, ipN, null,
                System.currentTimeMillis() + PENDING_TTL_MS));
        String tokIgnore = registerPending(new Pending(PendingKind.IGNORE_FAILS, ipN, null,
                System.currentTimeMillis() + PENDING_TTL_MS));
        List<List<Map<String, String>>> rows = List.of(
                List.of(
                        Map.of("text", "暂停全站访问", "callback_data", "p|" + tokPause),
                        Map.of("text", "忽略(清零计数)", "callback_data", "g|" + tokIgnore)
                )
        );
        String text = String.format(
                "【登录安全】同一 IP 在 %d 分钟内已连续密码登录失败 %d 次\nIP: %s\n\n可选择暂停整站 API 访问（仍可通过下方「恢复」与 Telegram 内联按钮解除），或仅清零计数。",
                FAIL_WINDOW_MS / 60000, PAUSE_OFFER_THRESHOLD, ipN);
        notificationService.sendSecurityTextWithInlineKeyboard(text, rows);
    }

    public void handleTelegramCallback(String rawData, String callbackQueryId) {
        handleTelegramCallback(rawData, callbackQueryId, null);
    }

    public void handleTelegramCallback(String rawData, String callbackQueryId, String answeringBotToken) {
        if (StrUtil.isBlank(callbackQueryId)) return;
        if (rawData == null || !rawData.contains("|")) {
            answerCallback(callbackQueryId, "无效操作", false, answeringBotToken);
            return;
        }
        int p = rawData.indexOf('|');
        String prefix = rawData.substring(0, p);
        String token = rawData.substring(p + 1);
        if (token.length() > 32) {
            answerCallback(callbackQueryId, "无效操作", false, answeringBotToken);
            return;
        }
        Pending pend = pendingByToken.get(token);
        if (pend == null || System.currentTimeMillis() > pend.expireAt) {
            answerCallback(callbackQueryId,
                    "操作已过期，请重新发送 /bans 或重新登录后再试", false, answeringBotToken);
            return;
        }
        if (!prefixMatchesKind(prefix, pend.kind)) {
            answerCallback(callbackQueryId, "无效操作", false, answeringBotToken);
            return;
        }
        if (!isPendingPayloadValid(pend)) {
            answerCallback(callbackQueryId, "数据无效", false, answeringBotToken);
            return;
        }
        pendingByToken.remove(token);
        try {
            switch (prefix) {
                case "i" -> {
                    appendIpDenylist(pend.ip);
                    answerCallback(callbackQueryId, "已拉黑 IP: " + pend.ip, false, answeringBotToken);
                    log.warn("[LoginSecurity] IP denylisted via TG: {}", pend.ip);
                }
                case "d" -> {
                    appendDeviceDenylist(pend.deviceId);
                    answerCallback(callbackQueryId, "已禁止设备: " + pend.deviceId, false, answeringBotToken);
                    log.warn("[LoginSecurity] Device denylisted via TG: {}", pend.deviceId);
                }
                case "p" -> {
                    notificationService.saveKvValue(SysCfgEnum.SITE_ACCESS_PAUSED, "true");
                    answerCallback(callbackQueryId, "全站 API 已暂停（静态页与 TG 回调仍可用）", false, answeringBotToken);
                    log.warn("[LoginSecurity] Site access paused via TG, trigger IP: {}", pend.ip);
                    sendResumeOfferAfterPause();
                }
                case "u" -> {
                    notificationService.saveKvValue(SysCfgEnum.SITE_ACCESS_PAUSED, "false");
                    answerCallback(callbackQueryId, "已恢复全站访问", false, answeringBotToken);
                    log.info("[LoginSecurity] Site access resumed via TG");
                }
                case "g" -> {
                    ipFailWindows.remove(pend.ip);
                    answerCallback(callbackQueryId, "已清零该 IP 的失败计数", false, answeringBotToken);
                }
                case "R" -> {
                    boolean ok = removeIpFromDenylist(pend.ip);
                    answerCallback(callbackQueryId,
                            ok ? "已解除 IP 禁止: " + pend.ip : "该 IP 已不在名单中", false, answeringBotToken);
                    if (ok) {
                        log.info("[LoginSecurity] IP removed from denylist via TG: {}", pend.ip);
                    }
                }
                case "r" -> {
                    boolean ok = removeDeviceFromDenylist(pend.deviceId);
                    answerCallback(callbackQueryId,
                            ok ? "已解除设备禁止" : "该设备已不在名单中", false, answeringBotToken);
                    if (ok) {
                        log.info("[LoginSecurity] Device removed from denylist via TG: {}", pend.deviceId);
                    }
                }
                default -> answerCallback(callbackQueryId, "未知操作", false, answeringBotToken);
            }
        } catch (Exception e) {
            log.warn("[LoginSecurity] Callback handling failed: {}", e.getMessage());
            answerCallback(callbackQueryId, "执行失败", true, answeringBotToken);
        }
    }

    /** 供 TG 通知配置变更安全提示等场景注册「拉黑该 IP」按钮。 */
    public String registerBlockIpCallback(String ip) {
        String ipN = normalizeIp(ip);
        if (StrUtil.isBlank(ipN)) {
            return null;
        }
        return registerPending(new Pending(PendingKind.BLOCK_IP, ipN, null,
                System.currentTimeMillis() + PENDING_TTL_MS));
    }

    private void answerCallback(String callbackQueryId, String text, boolean showAlert, String answeringBotToken) {
        notificationService.answerTelegramCallbackQuery(callbackQueryId, text, showAlert, answeringBotToken);
    }

    private static boolean isPendingPayloadValid(Pending pend) {
        return switch (pend.kind) {
            case BLOCK_IP -> StrUtil.isNotBlank(pend.ip);
            case BLOCK_DEVICE -> StrUtil.isNotBlank(pend.deviceId);
            case IGNORE_FAILS -> StrUtil.isNotBlank(pend.ip);
            case PAUSE_SITE, RESUME_SITE -> true;
            case UNBLOCK_IP -> StrUtil.isNotBlank(pend.ip);
            case UNBLOCK_DEVICE -> StrUtil.isNotBlank(pend.deviceId);
        };
    }

    private static boolean prefixMatchesKind(String prefix, PendingKind kind) {
        return switch (prefix) {
            case "i" -> kind == PendingKind.BLOCK_IP;
            case "d" -> kind == PendingKind.BLOCK_DEVICE;
            case "p" -> kind == PendingKind.PAUSE_SITE;
            case "u" -> kind == PendingKind.RESUME_SITE;
            case "g" -> kind == PendingKind.IGNORE_FAILS;
            case "R" -> kind == PendingKind.UNBLOCK_IP;
            case "r" -> kind == PendingKind.UNBLOCK_DEVICE;
            default -> false;
        };
    }

    private void sendResumeOfferAfterPause() {
        String tok = registerPending(new Pending(PendingKind.RESUME_SITE, null, null,
                System.currentTimeMillis() + PENDING_TTL_MS));
        List<List<Map<String, String>>> rows = List.of(
                List.of(Map.of("text", "恢复全站访问", "callback_data", "u|" + tok))
        );
        notificationService.sendSecurityTextWithInlineKeyboard(
                "【登录安全】全站 API 已暂停。\n若误操作或风险解除，请点击恢复。", rows);
    }

    private String registerPending(Pending pending) {
        for (int i = 0; i < 12; i++) {
            String token = RandomUtil.randomString("abcdef0123456789", 16);
            if (pendingByToken.putIfAbsent(token, pending) == null) {
                return token;
            }
        }
        String token = IdUtil.fastSimpleUUID();
        pendingByToken.put(token, pending);
        return token;
    }

    private int bumpFailureCount(String ipN) {
        IpFailWindow w = ipFailWindows.computeIfAbsent(ipN, k -> new IpFailWindow());
        synchronized (w) {
            long now = System.currentTimeMillis();
            if (now - w.windowStart > FAIL_WINDOW_MS) {
                w.count.set(0);
                w.pauseOfferSent = false;
                w.windowStart = now;
            }
            return w.count.incrementAndGet();
        }
    }

    @Scheduled(fixedRate = 120_000)
    public void purgeExpiredPending() {
        long now = System.currentTimeMillis();
        pendingByToken.entrySet().removeIf(e -> e.getValue().expireAt < now);
    }

    private String nowStr() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String normalizeIp(String ip) {
        if (ip == null) return "";
        return ip.trim();
    }

    private Set<String> readIpDenylist() {
        return parseCommaSet(notificationService.getKvValue(SysCfgEnum.LOGIN_IP_DENYLIST));
    }

    private Set<String> readDeviceDenylist() {
        return parseCommaSet(notificationService.getKvValue(SysCfgEnum.LOGIN_DEVICE_DENYLIST));
    }

    private static Set<String> parseCommaSet(String raw) {
        Set<String> s = new LinkedHashSet<>();
        if (StrUtil.isBlank(raw)) return s;
        for (String p : raw.split(",")) {
            if (StrUtil.isNotBlank(p)) s.add(p.trim());
        }
        return s;
    }

    private static boolean containsIp(Set<String> set, String ip) {
        return ip != null && set.contains(ip);
    }

    private static boolean containsToken(Set<String> set, String id) {
        return id != null && set.contains(id);
    }

    private void appendIpDenylist(String ip) {
        if (StrUtil.isBlank(ip)) return;
        Set<String> s = readIpDenylist();
        s.add(ip.trim());
        notificationService.saveKvValue(SysCfgEnum.LOGIN_IP_DENYLIST, String.join(",", s));
    }

    private void appendDeviceDenylist(String deviceId) {
        if (StrUtil.isBlank(deviceId)) return;
        Set<String> s = readDeviceDenylist();
        s.add(deviceId.trim());
        notificationService.saveKvValue(SysCfgEnum.LOGIN_DEVICE_DENYLIST, String.join(",", s));
    }

    /**
     * 向已配置的 Chat 发送当前 IP/设备禁止名单，并为每项附上「解除」内联按钮（短 token，避免 callback_data 超长）。
     */
    public void sendDenylistManagementKeyboard() {
        long exp = System.currentTimeMillis() + DENYLIST_UI_TTL_MS;
        List<String> ips = new ArrayList<>(readIpDenylist());
        List<String> devs = new ArrayList<>(readDeviceDenylist());

        StringBuilder text = new StringBuilder();
        text.append("【禁止名单】点下方按钮解除对应项（30 分钟内有效）。\n");
        text.append("IP：").append(ips.size()).append(" 条；设备：").append(devs.size()).append(" 条。\n");

        if (ips.isEmpty() && devs.isEmpty()) {
            text.append("\n当前无禁止的 IP 与设备。");
            notificationService.sendMessage(text.toString());
            return;
        }

        final int capIp = 40;
        final int capDev = 40;
        List<List<Map<String, String>>> rows = new ArrayList<>();
        int ipShown = 0;
        for (String ip : ips) {
            if (ipShown >= capIp) {
                break;
            }
            String tok = registerPending(new Pending(PendingKind.UNBLOCK_IP, ip, null, exp));
            rows.add(List.of(Map.of(
                    "text", "解除IP " + shortenForTelegramButton(ip, 48),
                    "callback_data", "R|" + tok)));
            ipShown++;
        }
        int devShown = 0;
        for (String did : devs) {
            if (devShown >= capDev) {
                break;
            }
            String tok = registerPending(new Pending(PendingKind.UNBLOCK_DEVICE, null, did, exp));
            rows.add(List.of(Map.of(
                    "text", "解除设备 " + shortenForTelegramButton(did, 44),
                    "callback_data", "r|" + tok)));
            devShown++;
        }
        if (ips.size() > capIp) {
            text.append("\n⚠ IP 较多，仅生成前 ").append(capIp).append(" 条的解除按钮；解除后可再发 /bans。");
        }
        if (devs.size() > capDev) {
            text.append("\n⚠ 设备较多，仅生成前 ").append(capDev).append(" 条的解除按钮；解除后可再发 /bans。");
        }

        notificationService.sendSecurityTextWithInlineKeyboard(text.toString(), rows);
    }

    private static String shortenForTelegramButton(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, Math.max(0, maxLen - 1)) + "…";
    }

    /** 从 IP 禁止名单移除；返回是否确实移除了一条。 */
    public boolean removeIpFromDenylist(String ip) {
        if (StrUtil.isBlank(ip)) {
            return false;
        }
        Set<String> s = readIpDenylist();
        if (!s.remove(normalizeIp(ip))) {
            return false;
        }
        notificationService.saveKvValue(SysCfgEnum.LOGIN_IP_DENYLIST, s.isEmpty() ? "" : String.join(",", s));
        return true;
    }

    /** 从设备禁止名单移除；返回是否确实移除了一条。 */
    public boolean removeDeviceFromDenylist(String deviceId) {
        if (StrUtil.isBlank(deviceId)) {
            return false;
        }
        Set<String> s = readDeviceDenylist();
        if (!s.remove(deviceId.trim())) {
            return false;
        }
        notificationService.saveKvValue(SysCfgEnum.LOGIN_DEVICE_DENYLIST, s.isEmpty() ? "" : String.join(",", s));
        return true;
    }

    public List<String> listBannedIps() {
        return new ArrayList<>(readIpDenylist());
    }

    public List<String> listBannedDevices() {
        return new ArrayList<>(readDeviceDenylist());
    }

    public void addIpToDenylist(String ip) {
        appendIpDenylist(ip);
    }

    public void addDeviceToDenylist(String deviceId) {
        appendDeviceDenylist(deviceId);
    }

    public String readDeviceIdFromRequest(HttpServletRequest request) {
        return HttpRequestUtil.getCookie(request, "ow_did");
    }
}
