package com.ociworker.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.ociworker.enums.SysCfgEnum;
import com.ociworker.model.dto.OciProxySnapshot;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.NotificationService;
import com.ociworker.service.OciProxyConfigService;
import com.ociworker.service.SystemService;
import com.ociworker.service.VerifyCodeService;
import jakarta.annotation.Resource;
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

    @GetMapping("/glance")
    public ResponseData<?> glance() {
        return ResponseData.ok(systemService.getGlance());
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
