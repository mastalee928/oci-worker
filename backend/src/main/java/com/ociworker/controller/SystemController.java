package com.ociworker.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.ociworker.enums.SysCfgEnum;
import com.ociworker.model.dto.OciProxySnapshot;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.NotificationService;
import com.ociworker.service.OciProxyConfigService;
import com.ociworker.service.SystemService;
import com.ociworker.service.VerifyCodeService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/sys")
public class SystemController {

    private static final Pattern DAILY_REPORT_TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

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

    @Value("${ociworker.telegram.webhook-secret:}")
    private String telegramWebhookSecret;

    @Value("${ociworker.telegram.webhook-secret-token:}")
    private String telegramWebhookSecretToken;

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
        String whPath = notificationService.getKvValue(SysCfgEnum.TG_WEBHOOK_PATH_SECRET);
        config.put("webhookPathSecret", maskSecret(whPath));
        config.put("webhookPathSecretKvConfigured", StrUtil.isNotBlank(whPath));
        config.put("webhookPathSecretYmlConfigured", StrUtil.isNotBlank(telegramWebhookSecret));
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
    public ResponseData<?> saveNotifyConfig(@RequestBody Map<String, String> params) {
        String pwd = params.get("password");
        if (StrUtil.isBlank(pwd)) {
            return ResponseData.error("请输入登录密码进行验证");
        }
        String inputHash = DigestUtil.sha256Hex(pwd);
        if (!inputHash.equals(authController.getEffectivePasswordHash())) {
            return ResponseData.error("密码错误");
        }

        if (params.containsKey("botToken")) {
            String v = params.get("botToken");
            if (v != null && !v.contains("****")) {
                notificationService.saveKvValue(SysCfgEnum.TG_BOT_TOKEN, v);
            }
        }
        if (params.containsKey("chatId")) {
            String v = params.get("chatId");
            if (v != null && !v.contains("****")) {
                notificationService.saveKvValue(SysCfgEnum.TG_CHAT_ID, v);
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
        if (params.containsKey("webhookPathSecret")) {
            String v = params.get("webhookPathSecret");
            if (v != null && !v.contains("****")) {
                notificationService.saveKvValue(SysCfgEnum.TG_WEBHOOK_PATH_SECRET, v.trim());
            }
        }
        return ResponseData.ok();
    }

    @PostMapping("/testNotify")
    public ResponseData<?> testNotify() {
        notificationService.sendMessage("【测试通知】🔔 Telegram 通知配置正常！");
        return ResponseData.ok();
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

    @GetMapping("/tgWebhookSnapshot")
    public ResponseData<?> tgWebhookSnapshot() {
        if (!verifyCodeService.isTgConfigured()) {
            return ResponseData.error("请先配置 Telegram Bot Token 与 Chat ID");
        }
        Map<String, Object> snap = notificationService.readTelegramWebhookSnapshot();
        snap.put("pathSecretYmlConfigured", StrUtil.isNotBlank(telegramWebhookSecret));
        snap.put("pathSecretKvConfigured", StrUtil.isNotBlank(notificationService.getKvValue(SysCfgEnum.TG_WEBHOOK_PATH_SECRET)));
        return ResponseData.ok(snap);
    }

    /**
     * 一键向 Telegram 注册 Webhook（含 message + callback_query）。
     * <p>路径密钥：环境变量优先；否则请求体；否则库中已有；若皆无则<strong>自动生成</strong>随机串并写入库。</p>
     */
    @PostMapping("/applyTgWebhook")
    public ResponseData<?> applyTgWebhook(@RequestBody Map<String, String> body) {
        String pwd = body.get("password");
        if (StrUtil.isBlank(pwd)) {
            return ResponseData.error("请输入登录密码进行验证");
        }
        String inputHash = DigestUtil.sha256Hex(pwd);
        if (!inputHash.equals(authController.getEffectivePasswordHash())) {
            return ResponseData.error("密码错误");
        }
        if (!verifyCodeService.isTgConfigured()) {
            return ResponseData.error("请先配置 Telegram Bot Token 与 Chat ID");
        }
        String publicBaseUrl = StrUtil.trimToNull(body.get("publicBaseUrl"));
        if (StrUtil.isBlank(publicBaseUrl)) {
            return ResponseData.error("请填写 Webhook 公网根地址（不要末尾斜杠）");
        }
        if (!publicBaseUrl.toLowerCase().startsWith("https://")) {
            return ResponseData.error("公网根地址须以 https:// 开头（Telegram 要求）。若只有 IP:端口，请先用隧道/域名提供 HTTPS。");
        }

        String pathSecret = StrUtil.trimToNull(telegramWebhookSecret);
        String bodyPath = StrUtil.trimToNull(body.get("webhookPathSecret"));
        if (pathSecret == null) {
            pathSecret = bodyPath;
        }
        if (pathSecret == null) {
            pathSecret = StrUtil.trimToNull(notificationService.getKvValue(SysCfgEnum.TG_WEBHOOK_PATH_SECRET));
        }
        boolean pathSecretAutoGenerated = false;
        if (StrUtil.isBlank(pathSecret)) {
            pathSecret = RandomUtil.randomString(
                    "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789", 48);
            pathSecretAutoGenerated = true;
        }

        Map<String, Object> out = notificationService.applyTelegramWebhook(
                publicBaseUrl, pathSecret, telegramWebhookSecretToken);
        if (pathSecretAutoGenerated) {
            notificationService.saveKvValue(SysCfgEnum.TG_WEBHOOK_PATH_SECRET, pathSecret);
            out.put("pathSecretAutoGenerated", true);
        } else if (StrUtil.isBlank(telegramWebhookSecret) && StrUtil.isNotBlank(bodyPath)) {
            notificationService.saveKvValue(SysCfgEnum.TG_WEBHOOK_PATH_SECRET, bodyPath);
        }
        return ResponseData.ok(out);
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
