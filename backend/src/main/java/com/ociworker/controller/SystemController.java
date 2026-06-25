package com.ociworker.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.google.common.net.InetAddresses;
import com.ociworker.enums.SysCfgEnum;
import com.ociworker.model.dto.OciProxySnapshot;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.BanlistViewSessionService;
import com.ociworker.service.LoginAuditService;
import com.ociworker.service.LoginAuditViewSessionService;
import com.ociworker.service.LoginSecurityService;
import com.ociworker.service.AnnouncementPushService;
import com.ociworker.service.NotificationService;
import com.ociworker.service.OciProxyConfigService;
import com.ociworker.service.SystemService;
import com.ociworker.service.TgNotifyConfigRollbackService;
import com.ociworker.service.VerifyCodeService;
import com.ociworker.util.HttpRequestUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/sys")
public class SystemController {

    private static final Pattern DAILY_REPORT_TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final String BANLIST_SESSION_HEADER = "X-Oci-Banlist-Session";
    private static final String LOGIN_AUDIT_SESSION_HEADER = "X-Oci-Login-Audit-Session";

    @Resource
    private SystemService systemService;
    @Resource
    private NotificationService notificationService;
    @Resource
    private VerifyCodeService verifyCodeService;
    @Resource
    private AuthController authController;
    @Resource
    private OciProxyConfigService ociProxyConfigService;
    @Resource
    private LoginAuditService loginAuditService;
    @Resource
    private LoginSecurityService loginSecurityService;
    @Resource
    private BanlistViewSessionService banlistViewSessionService;
    @Resource
    private LoginAuditViewSessionService loginAuditViewSessionService;
    @Resource
    private TgNotifyConfigRollbackService tgNotifyConfigRollbackService;
    @Resource
    private AnnouncementPushService announcementPushService;

    @GetMapping("/glance")
    public ResponseData<?> glance() {
        return ResponseData.ok(systemService.getGlance());
    }

    /** 区域下拉：传 userId 时仅返回 tenancy 已订阅区；不传则返回 SDK 全集（新增租户配置用）。 */
    @GetMapping("/ociRegionOptions")
    public ResponseData<?> ociRegionOptions(@RequestParam(required = false) String userId) {
        return ResponseData.ok(systemService.listOciRegionCatalog(userId));
    }

    @GetMapping("/notifyConfig")
    public ResponseData<?> getNotifyConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        String botToken = notificationService.getKvValue(SysCfgEnum.TG_BOT_TOKEN);
        String chatId = notificationService.getKvValue(SysCfgEnum.TG_CHAT_ID);
        config.put("botToken", maskSecret(botToken));
        config.put("chatId", maskSecret(chatId));
        config.put("botTokenConfigured", botToken != null && !botToken.isBlank());
        config.put("chatIdConfigured", chatId != null && !chatId.isBlank());
        config.put("notifyTypes", notificationService.getKvValue(SysCfgEnum.TG_NOTIFY_TYPES));
        String dailyTime = notificationService.getKvValue(SysCfgEnum.TG_DAILY_REPORT_TIME);
        config.put("dailyReportTime", (dailyTime == null || dailyTime.isBlank()) ? "09:00" : dailyTime.trim());
        config.put("tgInboundMode", "getUpdates");
        config.put("tgUpdatesOffsetConfigured",
                StrUtil.isNotBlank(notificationService.getKvValue(SysCfgEnum.TG_UPDATES_NEXT_OFFSET)));
        return ResponseData.ok(config);
    }

    private String maskSecret(String value) {
        if (value == null || value.isBlank()) return "";
        int len = value.length();
        if (len <= 4) return "****";
        if (len <= 10) return value.substring(0, 2) + "****" + value.substring(len - 2);
        return value.substring(0, 4) + "********" + value.substring(len - 4);
    }

    @PostMapping("/notifyConfig")
    public ResponseData<?> saveNotifyConfig(@RequestBody Map<String, String> params, HttpServletRequest request) {
        String oldToken = notificationService.getKvValue(SysCfgEnum.TG_BOT_TOKEN);
        String oldChatId = notificationService.getKvValue(SysCfgEnum.TG_CHAT_ID);
        boolean tokenWillChange = willTgSecretChange(params.get("botToken"), oldToken);
        boolean chatWillChange = willTgSecretChange(params.get("chatId"), oldChatId);

        if (verifyCodeService.isTgConfigured()) {
            String code = params.get("verifyCode");
            if (StrUtil.isBlank(code)) {
                return ResponseData.error("请先获取 Telegram 验证码");
            }
            verifyCodeService.verifyCode("notifyConfig", code);
        } else {
            String pwd = params.get("password");
            if (StrUtil.isBlank(pwd)) {
                return ResponseData.error("请输入登录密码进行验证");
            }
            String inputHash = DigestUtil.sha256Hex(pwd);
            if (!inputHash.equals(authController.getEffectivePasswordHash())) {
                return ResponseData.error("密码错误");
            }
        }

        boolean identityRollback = (tokenWillChange || chatWillChange)
                && StrUtil.isNotBlank(oldToken)
                && StrUtil.isNotBlank(oldChatId);

        if (identityRollback) {
            String ip = HttpRequestUtil.getClientIp(request);
            String deviceId = loginSecurityService.readDeviceIdFromRequest(request);
            String newToken = resolveIncomingSecret(params.get("botToken"), oldToken);
            String newChatId = resolveIncomingSecret(params.get("chatId"), oldChatId);
            tgNotifyConfigRollbackService.applyIdentityChange(
                    oldToken.trim(), oldChatId.trim(), newToken, newChatId, ip, deviceId);
        } else {
            if (params.containsKey("botToken")) {
                String v = params.get("botToken");
                if (v != null && !v.contains("****")) {
                    notificationService.saveKvValue(SysCfgEnum.TG_BOT_TOKEN, v);
                    notificationService.resetTelegramUpdatesOffset();
                }
            }
            if (params.containsKey("chatId")) {
                String v = params.get("chatId");
                if (v != null && !v.contains("****")) {
                    notificationService.saveKvValue(SysCfgEnum.TG_CHAT_ID, v);
                }
            }
        }
        if (params.containsKey("notifyTypes")) {
            notificationService.saveKvValue(SysCfgEnum.TG_NOTIFY_TYPES, params.get("notifyTypes"));
        }
        if (params.containsKey("dailyReportTime")) {
            String t = params.get("dailyReportTime");
            if (t != null && !t.isBlank()) {
                t = t.trim();
                if (!DAILY_REPORT_TIME.matcher(t).matches()) {
                    return ResponseData.error("每日播报时间须为 24 小时制 HH:mm（如 09:00、14:30）");
                }
                notificationService.saveKvValue(SysCfgEnum.TG_DAILY_REPORT_TIME, t);
            }
        }
        return ResponseData.ok();
    }

    private static String resolveIncomingSecret(String incoming, String current) {
        if (incoming == null || incoming.contains("****")) {
            return StrUtil.trimToEmpty(current);
        }
        return incoming.trim();
    }

    /** 请求体携带非脱敏新值且与库中不同时，视为 Bot Token / Chat ID 将变更。 */
    private static boolean willTgSecretChange(String incoming, String current) {
        if (incoming == null || incoming.contains("****")) {
            return false;
        }
        return !Objects.equals(StrUtil.trim(incoming), StrUtil.trimToEmpty(current));
    }

    @PostMapping("/testNotify")
    public ResponseData<?> testNotify() {
        notificationService.sendMessage("【测试通知】🔔 Telegram 通知配置正常！");
        return ResponseData.ok();
    }

    @GetMapping("/announcementPush/config")
    public ResponseData<?> getAnnouncementPushConfig() {
        return ResponseData.ok(announcementPushService.getConfig());
    }

    @PostMapping("/announcementPush/config")
    public ResponseData<?> saveAnnouncementPushConfig(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(announcementPushService.saveConfig(params));
    }

    @GetMapping("/announcementPush/tenants")
    public ResponseData<?> getAnnouncementPushTenants() {
        return ResponseData.ok(announcementPushService.tenantTree());
    }

    @GetMapping("/announcementPush/status")
    public ResponseData<?> getAnnouncementPushStatus() {
        return ResponseData.ok(announcementPushService.getStatus());
    }

    @PostMapping("/announcementPush/scan")
    public ResponseData<?> triggerAnnouncementPushScan() {
        announcementPushService.triggerManualScan();
        return ResponseData.ok("已开始扫描");
    }

    @GetMapping("/announcementPush/inbox")
    public ResponseData<?> getAnnouncementPushInbox(@RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "10") int size,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String startAt,
                                                    @RequestParam(required = false) String endAt,
                                                    @RequestParam(required = false) String eventTypes) {
        return ResponseData.ok(announcementPushService.inbox(page, size, keyword, startAt, endAt, eventTypes));
    }

    @PostMapping("/announcementPush/inbox/detail")
    public ResponseData<?> getAnnouncementPushInboxDetail(@RequestBody Map<String, String> params) {
        return ResponseData.ok(announcementPushService.inboxDetail(params == null ? "" : params.get("aggregateKey")));
    }

    @PostMapping("/announcementPush/inbox/mark")
    public ResponseData<?> markAnnouncementPushInbox(@RequestBody Map<String, String> params) {
        String aggregateKey = params == null ? "" : params.get("aggregateKey");
        String action = params == null ? "" : params.get("action");
        announcementPushService.markAggregate(aggregateKey, action);
        return ResponseData.ok();
    }

    @GetMapping("/announcementPush/batches")
    public ResponseData<?> getAnnouncementPushBatches(@RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "10") int size) {
        return ResponseData.ok(announcementPushService.batches(page, size));
    }

    @PostMapping("/sendVerifyCode")
    public ResponseData<?> sendVerifyCode(@RequestBody Map<String, String> params) {
        verifyCodeService.sendCode(params.get("action"));
        return ResponseData.ok();
    }

    @GetMapping("/tgStatus")
    public ResponseData<?> tgStatus() {
        return ResponseData.ok(Map.of("configured", verifyCodeService.isTgConfigured()));
    }

    /** 查看登录统计前：校验 TG 验证码（action=loginAudit），下发短期会话 ID。 */
    @PostMapping("/loginAudit/unlock")
    public ResponseData<?> loginAuditUnlock(@RequestBody Map<String, String> body) {
        verifyCodeService.verifyCode("loginAudit", body.get("verifyCode"));
        String sid = loginAuditViewSessionService.issue();
        return ResponseData.ok(Map.of("loginAuditSession", sid));
    }

    @GetMapping("/loginAudit")
    public ResponseData<?> loginAudit(
            @RequestHeader(value = LOGIN_AUDIT_SESSION_HEADER, required = false) String loginAuditSession,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        ResponseData<?> gate = requireLoginAuditViewSession(loginAuditSession);
        if (gate != null) {
            return gate;
        }
        return ResponseData.ok(loginAuditService.pageAudits(page, Math.min(size, 100)));
    }

    @GetMapping("/banlist")
    public ResponseData<?> banlist(
            @RequestHeader(value = BANLIST_SESSION_HEADER, required = false) String banlistSession) {
        ResponseData<?> gate = requireBanlistViewSession(banlistSession);
        if (gate != null) {
            return gate;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ips", loginSecurityService.listBannedIps());
        m.put("devices", loginSecurityService.listBannedDevices());
        return ResponseData.ok(m);
    }

    /** 进入封禁列表页前：校验 TG 下发的 6 位验证码（与 sendVerifyCode action=banlist 配套），并下发短期会话 ID。 */
    @PostMapping("/banlist/unlock")
    public ResponseData<?> banlistUnlock(@RequestBody Map<String, String> body) {
        verifyCodeService.verifyCode("banlist", body.get("verifyCode"));
        String sid = banlistViewSessionService.issue();
        return ResponseData.ok(Map.of("banlistSession", sid));
    }

    /**
     * 根据内容自动识别：合法 IPv4/IPv6 字面值则封禁 IP，否则按设备码封禁。
     */
    @PostMapping("/banlist/add")
    public ResponseData<?> banlistAdd(
            @RequestHeader(value = BANLIST_SESSION_HEADER, required = false) String banlistSession,
            @RequestBody Map<String, String> body) {
        ResponseData<?> gate = requireBanlistViewSession(banlistSession);
        if (gate != null) {
            return gate;
        }
        String value = StrUtil.trimToNull(body.get("value"));
        if (value == null) {
            return ResponseData.error("请输入 IP 或设备码");
        }
        if (InetAddresses.isInetAddress(value)) {
            loginSecurityService.addIpToDenylist(value);
        } else {
            loginSecurityService.addDeviceToDenylist(value);
        }
        return ResponseData.ok();
    }

    @PostMapping("/banlist/addIp")
    public ResponseData<?> banlistAddIp(
            @RequestHeader(value = BANLIST_SESSION_HEADER, required = false) String banlistSession,
            @RequestBody Map<String, String> body) {
        ResponseData<?> gate = requireBanlistViewSession(banlistSession);
        if (gate != null) {
            return gate;
        }
        String ip = StrUtil.trimToNull(body.get("ip"));
        if (ip == null) {
            return ResponseData.error("请输入 IP");
        }
        loginSecurityService.addIpToDenylist(ip);
        return ResponseData.ok();
    }

    @PostMapping("/banlist/addDevice")
    public ResponseData<?> banlistAddDevice(
            @RequestHeader(value = BANLIST_SESSION_HEADER, required = false) String banlistSession,
            @RequestBody Map<String, String> body) {
        ResponseData<?> gate = requireBanlistViewSession(banlistSession);
        if (gate != null) {
            return gate;
        }
        String did = StrUtil.trimToNull(body.get("deviceId"));
        if (did == null) {
            return ResponseData.error("请输入设备码");
        }
        loginSecurityService.addDeviceToDenylist(did);
        return ResponseData.ok();
    }

    @PostMapping("/banlist/removeIp")
    public ResponseData<?> banlistRemoveIp(
            @RequestHeader(value = BANLIST_SESSION_HEADER, required = false) String banlistSession,
            @RequestBody Map<String, String> body) {
        ResponseData<?> gate = requireBanlistViewSession(banlistSession);
        if (gate != null) {
            return gate;
        }
        String ip = StrUtil.trimToNull(body.get("ip"));
        if (ip == null) {
            return ResponseData.error("缺少 ip");
        }
        loginSecurityService.removeIpFromDenylist(ip);
        return ResponseData.ok();
    }

    @PostMapping("/banlist/removeDevice")
    public ResponseData<?> banlistRemoveDevice(
            @RequestHeader(value = BANLIST_SESSION_HEADER, required = false) String banlistSession,
            @RequestBody Map<String, String> body) {
        ResponseData<?> gate = requireBanlistViewSession(banlistSession);
        if (gate != null) {
            return gate;
        }
        String did = StrUtil.trimToNull(body.get("deviceId"));
        if (did == null) {
            return ResponseData.error("缺少 deviceId");
        }
        loginSecurityService.removeDeviceFromDenylist(did);
        return ResponseData.ok();
    }

    /** @return null 表示校验通过 */
    private ResponseData<?> requireBanlistViewSession(String sessionId) {
        if (!banlistViewSessionService.isValid(sessionId)) {
            return ResponseData.error(403, "请先通过 Telegram 验证进入封禁列表");
        }
        return null;
    }

    /** @return null 表示校验通过 */
    private ResponseData<?> requireLoginAuditViewSession(String sessionId) {
        if (!loginAuditViewSessionService.isValid(sessionId)) {
            return ResponseData.error(403, "请先通过 Telegram 验证查看登录统计");
        }
        return null;
    }

    @GetMapping("/checkUpdate")
    public ResponseData<?> checkUpdate() {
        return ResponseData.ok(systemService.checkUpdate());
    }

    @PostMapping("/performUpdate")
    public ResponseData<?> performUpdate() {
        systemService.performUpdate();
        return ResponseData.ok("更新已启动，服务将在几秒后重启");
    }

    @GetMapping("/ociProxy")
    public ResponseData<?> getOciProxy() {
        OciProxySnapshot s = ociProxyConfigService.snapshot();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.enabled());
        m.put("proxyType", s.type());
        m.put("host", s.host() == null ? "" : s.host());
        m.put("port", s.port() > 0 ? s.port() : null);
        String u = s.proxyUser();
        m.put("username", (u == null || u.isBlank()) ? "" : maskSecret(u));
        m.put("passwordConfigured", s.proxyPass() != null && !s.proxyPass().isBlank());
        m.put("password", s.proxyPass() == null || s.proxyPass().isBlank() ? "" : maskSecret(s.proxyPass()));
        m.put("fullUrl", s.fullUrl() == null || s.fullUrl().isBlank() ? "" : maskUrlForDisplay(s.fullUrl()));
        m.put("fullUrlConfigured", s.fullUrl() != null && !s.fullUrl().isBlank());
        return ResponseData.ok(m);
    }

    private String maskUrlForDisplay(String url) {
        if (url == null || url.isBlank()) return "";
        if (url.contains("@")) {
            return url.replaceAll("://([^/]+)@", "://****@");
        }
        return url.length() > 48 ? url.substring(0, 24) + "…" : url;
    }

    @PostMapping("/ociProxy")
    public ResponseData<?> saveOciProxy(@RequestBody Map<String, String> params) {
        OciProxySnapshot cur = ociProxyConfigService.snapshot();
        boolean en = "true".equalsIgnoreCase(nvl(params.get("enabled")))
                || "1".equals(nvl(params.get("enabled")));
        String type = nvl(params.get("proxyType"));
        String host = nvl(params.get("host"));
        int port = 0;
        String ps = params.get("port");
        if (ps != null && !ps.isBlank()) {
            try {
                port = Integer.parseInt(ps.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        String user = resolveMasked(params.get("username"), cur.proxyUser());
        String pass = resolveMasked(params.get("password"), cur.proxyPass());
        String full = resolveMasked(params.get("fullUrl"), cur.fullUrl());
        OciProxySnapshot snap = OciProxySnapshot.fromForm(en, type, host, port, user, pass, full);
        ociProxyConfigService.persistAndReload(snap);
        return ResponseData.ok();
    }

    @PostMapping("/ociProxy/test")
    public ResponseData<?> testOciProxy(@RequestBody Map<String, String> params) {
        OciProxySnapshot cur = ociProxyConfigService.snapshot();
        boolean en = "true".equalsIgnoreCase(nvl(params.get("enabled")))
                || "1".equals(nvl(params.get("enabled")));
        String type = nvl(params.get("proxyType"));
        String host = nvl(params.get("host"));
        int port = 0;
        String ps = params.get("port");
        if (ps != null && !ps.isBlank()) {
            try {
                port = Integer.parseInt(ps.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        String user = resolveMasked(params.get("username"), cur.proxyUser());
        String pass = resolveMasked(params.get("password"), cur.proxyPass());
        String full = resolveMasked(params.get("fullUrl"), cur.fullUrl());
        OciProxySnapshot test = OciProxySnapshot.fromForm(en, type, host, port, user, pass, full);
        String msg = ociProxyConfigService.testWithParams(test);
        return ResponseData.ok(msg);
    }

    private String nvl(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * 若前端回传为脱敏占位（含 ****），保留数据库原值；否则采用新值（可为空以清空密码等）。
     */
    private String resolveMasked(String fromClient, String existing) {
        if (fromClient != null && fromClient.contains("****")
                && existing != null && !existing.isBlank()) {
            return existing;
        }
        return nvl(fromClient);
    }
}
