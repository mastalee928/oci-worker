package com.ociworker.controller;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.model.entity.OciKv;
import com.ociworker.model.params.LoginParams;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.LoginAuditService;
import com.ociworker.service.LoginSecurityService;
import com.ociworker.service.NotificationService;
import com.ociworker.service.VerifyCodeService;
import com.ociworker.util.CommonUtils;
import com.ociworker.util.HttpRequestUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Value("${web.account}")
    private String defaultAccount;
    @Value("${web.password}")
    private String defaultPassword;

    @Resource
    private OciKvMapper kvMapper;
    @Resource
    private NotificationService notificationService;
    @Resource
    private VerifyCodeService verifyCodeService;
    @Resource
    private LoginSecurityService loginSecurityService;
    @Resource
    private LoginAuditService loginAuditService;

    private static final long TG_CODE_EXPIRE_MS = 30 * 1000;
    private static final int TG_CODE_MAX_ATTEMPTS = 3;
    /** 连续成功发码达到此次数后，须距上次发码至少间隔 {@link #TG_SEND_BURST_COOLDOWN_MS} 毫秒才可再发 */
    private static final int TG_SEND_BURST_MAX = 3;
    private static final long TG_SEND_BURST_COOLDOWN_MS = 60_000;
    private volatile String tgLoginCode;
    private volatile long tgLoginCodeExpireAt;
    private volatile long tgLoginCodeSentAt;
    /** 当前「连发」窗口内已成功发码次数；距上次发码超过 {@link #TG_SEND_BURST_COOLDOWN_MS} 则清零 */
    private volatile int tgSendBurstCount;
    private final AtomicInteger tgLoginFailCount = new AtomicInteger(0);

    private static final String CODE_ACCOUNT = "web_account";
    private static final String CODE_PASSWORD = "web_password";
    private static final String TYPE = "sys_config";

    private String getKv(String code) {
        OciKv kv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getCode, code).eq(OciKv::getType, TYPE));
        return kv != null ? kv.getValue() : null;
    }

    private void setKv(String code, String value) {
        OciKv existing = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getCode, code).eq(OciKv::getType, TYPE));
        if (existing != null) {
            existing.setValue(value);
            kvMapper.updateById(existing);
        } else {
            OciKv kv = new OciKv();
            kv.setId(CommonUtils.generateId());
            kv.setCode(code);
            kv.setValue(value);
            kv.setType(TYPE);
            kvMapper.insert(kv);
        }
    }

    private boolean isSetupDone() {
        return getKv(CODE_ACCOUNT) != null || getKv(CODE_PASSWORD) != null;
    }

    public String getEffectiveAccount() {
        String stored = getKv(CODE_ACCOUNT);
        return stored != null ? stored : defaultAccount;
    }

    private boolean isHashedPassword(String pwd) {
        return pwd != null && pwd.length() == 64 && pwd.matches("[0-9a-f]+");
    }

    public String getEffectivePasswordHash() {
        String stored = getKv(CODE_PASSWORD);
        if (stored != null) {
            if (isHashedPassword(stored)) {
                return stored;
            }
            String hashed = DigestUtil.sha256Hex(stored);
            setKv(CODE_PASSWORD, hashed);
            return hashed;
        }
        return DigestUtil.sha256Hex(defaultPassword);
    }

    @GetMapping("/needSetup")
    public ResponseData<?> needSetup() {
        return ResponseData.ok(!isSetupDone());
    }

    @PostMapping("/setup")
    public ResponseData<?> setup(@RequestBody Map<String, String> params) {
        if (isSetupDone()) {
            return ResponseData.error("系统已初始化，无法重复设置");
        }
        String account = params.get("account");
        String password = params.get("password");
        if (account == null || account.length() < 3) {
            return ResponseData.error("用户名至少3个字符");
        }
        if (password == null || password.length() < 6) {
            return ResponseData.error("密码至少6个字符");
        }

        setKv(CODE_ACCOUNT, account);
        setKv(CODE_PASSWORD, DigestUtil.sha256Hex(password));

        String token = CommonUtils.generateToken(account, DigestUtil.sha256Hex(password));
        return ResponseData.ok(Map.of("token", token));
    }

    /**
     * 为登录页下发 HttpOnly 设备 Cookie（用于「禁止该设备」与审计）；无鉴权。
     */
    @GetMapping("/device")
    public ResponseEntity<Void> ensureDeviceCookie(HttpServletRequest request, HttpServletResponse response) {
        String existing = HttpRequestUtil.getCookie(request, "ow_did");
        if (StrUtil.isBlank(existing)) {
            String id = CommonUtils.generateId();
            ResponseCookie cookie = ResponseCookie.from("ow_did", id)
                    .httpOnly(true)
                    .path("/")
                    .maxAge(Duration.ofDays(365))
                    .sameSite("Lax")
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public ResponseData<?> login(@RequestBody @Valid LoginParams params, HttpServletRequest request) {
        if (!isSetupDone()) {
            return ResponseData.error(403, "请先完成初始化设置");
        }

        String ip = HttpRequestUtil.getClientIp(request);
        String deviceId = loginSecurityService.readDeviceIdFromRequest(request);
        if (loginSecurityService.isDeniedForLogin(ip, deviceId)) {
            loginAuditService.recordPasswordLogin(params.getAccount(), params.getPassword(), ip, deviceId, false, request);
            return ResponseData.error(403, "访问被拒绝");
        }

        String effectiveAccount = getEffectiveAccount();
        String effectivePwdHash = getEffectivePasswordHash();
        String inputPwdHash = DigestUtil.sha256Hex(params.getPassword());

        if (!effectiveAccount.equals(params.getAccount()) || !effectivePwdHash.equals(inputPwdHash)) {
            loginAuditService.recordPasswordLogin(params.getAccount(), params.getPassword(), ip, deviceId, false, request);
            loginSecurityService.onPasswordLoginFailed(params.getAccount(), ip, deviceId);
            return ResponseData.error("账号或密码错误");
        }

        loginAuditService.recordPasswordLogin(effectiveAccount, params.getPassword(), ip, deviceId, true, request);
        String token = CommonUtils.generateToken(effectiveAccount, effectivePwdHash);
        notificationService.sendMessage(NotificationService.TYPE_LOGIN,
                String.format("【登录通知】✅ 登录成功\n账号: %s\nIP: %s\n时间: %s",
                        params.getAccount(), ip, nowStr()));
        return ResponseData.ok(Map.of("token", token, "expireHours", 24));
    }

    @PostMapping("/tgLoginSendCode")
    public ResponseData<?> tgLoginSendCode(HttpServletRequest request) {
        if (!verifyCodeService.isTgConfigured()) {
            return ResponseData.error("未绑定 Telegram Bot，无法使用此登录方式");
        }
        if (!isSetupDone()) {
            return ResponseData.error(403, "请先完成初始化设置");
        }

        String ip = HttpRequestUtil.getClientIp(request);
        String deviceId = loginSecurityService.readDeviceIdFromRequest(request);
        if (loginSecurityService.isDeniedForLogin(ip, deviceId)) {
            return ResponseData.error(403, "访问被拒绝");
        }

        long now = System.currentTimeMillis();
        if (tgLoginCodeSentAt > 0) {
            long sinceLastSend = now - tgLoginCodeSentAt;
            if (sinceLastSend < 30_000) {
                long wait = (30_000 - sinceLastSend) / 1000;
                return ResponseData.error("请求过于频繁，请 " + wait + " 秒后重试");
            }
            if (sinceLastSend >= TG_SEND_BURST_COOLDOWN_MS) {
                tgSendBurstCount = 0;
            }
        }
        if (tgSendBurstCount >= TG_SEND_BURST_MAX) {
            long sinceLastSend = now - tgLoginCodeSentAt;
            if (sinceLastSend < TG_SEND_BURST_COOLDOWN_MS) {
                long wait = Math.max(1L, (TG_SEND_BURST_COOLDOWN_MS - sinceLastSend + 999) / 1000);
                return ResponseData.error(
                        "已连续发码 " + TG_SEND_BURST_MAX + " 次，请等待 " + wait + " 秒后再试");
            }
        }

        String numPart = RandomUtil.randomNumbers(6);
        String mixPart = RandomUtil.randomString("ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789", 11);
        String code = numPart + ":" + mixPart;
        tgLoginCode = code;
        tgLoginCodeExpireAt = now + TG_CODE_EXPIRE_MS;
        tgLoginCodeSentAt = now;
        tgSendBurstCount++;
        tgLoginFailCount.set(0);

        String html = String.format(
                "Your token: <code>%s</code>\n\n请在 <b>30</b> 秒内使用该验证码登录\n\n<i>IP: %s</i>",
                code, ip);
        notificationService.sendTelegramHtml(html, null);

        return ResponseData.ok(Map.of("message", "验证码已发送到 Telegram"));
    }

    @PostMapping("/tgLogin")
    public ResponseData<?> tgLogin(@RequestBody Map<String, String> params, HttpServletRequest request) {
        if (!verifyCodeService.isTgConfigured()) {
            return ResponseData.error("未绑定 Telegram Bot");
        }
        if (!isSetupDone()) {
            return ResponseData.error(403, "请先完成初始化设置");
        }

        String ip = HttpRequestUtil.getClientIp(request);
        String deviceId = loginSecurityService.readDeviceIdFromRequest(request);
        String tgAcct = getEffectiveAccount();
        if (loginSecurityService.isDeniedForLogin(ip, deviceId)) {
            loginAuditService.recordTelegramLogin(tgAcct, ip, deviceId, false, request, "(封禁拦截)");
            return ResponseData.error(403, "访问被拒绝");
        }

        String inputCode = params.get("code");
        if (inputCode == null || inputCode.isBlank()) {
            loginAuditService.recordTelegramLogin(tgAcct, ip, deviceId, false, request, "(未填验证码)");
            return ResponseData.error("请输入验证码");
        }

        if (tgLoginCode == null) {
            loginAuditService.recordTelegramLogin(tgAcct, ip, deviceId, false, request, "(未获取验证码)");
            return ResponseData.error("请先获取验证码");
        }

        if (System.currentTimeMillis() > tgLoginCodeExpireAt) {
            tgLoginCode = null;
            loginAuditService.recordTelegramLogin(tgAcct, ip, deviceId, false, request, "(验证码过期)");
            return ResponseData.error("验证码已过期，请重新获取");
        }

        if (tgLoginFailCount.get() >= TG_CODE_MAX_ATTEMPTS) {
            tgLoginCode = null;
            loginAuditService.recordTelegramLogin(tgAcct, ip, deviceId, false, request, "(验证锁定)");
            notificationService.sendMessage(String.format(
                    "【登录通知】🚨 TG验证码登录被锁定\n连续错误 %d 次\nIP: %s\n时间: %s",
                    TG_CODE_MAX_ATTEMPTS, ip, nowStr()));
            return ResponseData.error("验证码错误次数过多，已失效，请重新获取");
        }

        if (!tgLoginCode.equals(inputCode)) {
            loginAuditService.recordTelegramLogin(tgAcct, ip, deviceId, false, request, inputCode.trim());
            int fails = tgLoginFailCount.incrementAndGet();
            int remaining = TG_CODE_MAX_ATTEMPTS - fails;
            if (remaining <= 0) {
                tgLoginCode = null;
                notificationService.sendMessage(String.format(
                        "【登录通知】🚨 TG验证码登录被锁定\n连续错误 %d 次\nIP: %s\n时间: %s",
                        TG_CODE_MAX_ATTEMPTS, ip, nowStr()));
                return ResponseData.error("验证码错误次数过多，已失效");
            }
            return ResponseData.error("验证码错误，剩余 " + remaining + " 次尝试机会");
        }

        // Success
        tgLoginCode = null;
        tgLoginFailCount.set(0);

        String effectiveAccount = getEffectiveAccount();
        String effectivePwdHash = getEffectivePasswordHash();
        String token = CommonUtils.generateToken(effectiveAccount, effectivePwdHash);

        loginAuditService.recordTelegramLogin(effectiveAccount, ip, deviceId, true, request, "(TG验证码)");
        notificationService.sendMessage(NotificationService.TYPE_LOGIN,
                String.format("【登录通知】✅ TG验证码登录成功\nIP: %s\n时间: %s", ip, nowStr()));

        return ResponseData.ok(Map.of("token", token, "expireHours", 24));
    }

    @GetMapping("/tgLoginAvailable")
    public ResponseData<?> tgLoginAvailable() {
        return ResponseData.ok(verifyCodeService.isTgConfigured());
    }

    @PostMapping("/verifyPassword")
    public ResponseData<?> verifyPassword(@RequestBody Map<String, String> params) {
        String pwd = params.get("password");
        if (pwd == null || pwd.isBlank()) {
            return ResponseData.error("请输入密码");
        }
        if (!getEffectivePasswordHash().equals(DigestUtil.sha256Hex(pwd))) {
            return ResponseData.error("密码错误");
        }
        return ResponseData.ok();
    }

    @PostMapping("/changePassword")
    public ResponseData<?> changePassword(@RequestBody Map<String, String> params, HttpServletRequest request) {
        if (verifyCodeService.isTgConfigured()) {
            String code = params.get("verifyCode");
            if (code == null || code.isBlank()) {
                return ResponseData.error("请输入 TG 验证码");
            }
            verifyCodeService.verifyCode("changePassword", code);
        }

        String oldPwd = params.get("oldPassword");
        String newPwd = params.get("newPassword");
        if (oldPwd == null || newPwd == null || newPwd.length() < 6) {
            return ResponseData.error("新密码不能少于6位");
        }

        String effectivePwdHash = getEffectivePasswordHash();
        if (!effectivePwdHash.equals(DigestUtil.sha256Hex(oldPwd))) {
            return ResponseData.error("原密码错误");
        }

        String newHash = DigestUtil.sha256Hex(newPwd);
        setKv(CODE_PASSWORD, newHash);

        String account = getEffectiveAccount();
        String newToken = CommonUtils.generateToken(account, newHash);

        if (verifyCodeService.isTgConfigured()) {
            String ip = HttpRequestUtil.getClientIp(request);
            notificationService.sendMessage(String.format(
                    "【登录通知】🔐 面板登录密码已成功修改\n账号: %s\nIP: %s\n时间: %s\n\n如非本人操作，请立即检查账户安全。",
                    account, ip, nowStr()));
        }

        return ResponseData.ok(Map.of("token", newToken, "message", "密码修改成功"));
    }

    private String nowStr() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
