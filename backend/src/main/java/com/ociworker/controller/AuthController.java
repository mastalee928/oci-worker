package com.ociworker.controller;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.model.entity.OciKv;
import com.ociworker.model.params.LoginParams;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.NotificationService;
import com.ociworker.service.VerifyCodeService;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

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

    private static final long TG_CODE_EXPIRE_MS = 30 * 1000;
    private static final int TG_CODE_MAX_ATTEMPTS = 3;
    private volatile String tgLoginCode;
    private volatile long tgLoginCodeExpireAt;
    private volatile long tgLoginCodeSentAt;
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

    @PostMapping("/login")
    public ResponseData<?> login(@RequestBody @Valid LoginParams params, HttpServletRequest request) {
        if (!isSetupDone()) {
            return ResponseData.error(403, "请先完成初始化设置");
        }

        String effectiveAccount = getEffectiveAccount();
        String effectivePwdHash = getEffectivePasswordHash();
        String inputPwdHash = DigestUtil.sha256Hex(params.getPassword());

        if (!effectiveAccount.equals(params.getAccount()) || !effectivePwdHash.equals(inputPwdHash)) {
            String ip = getClientIp(request);
            notificationService.sendMessage(NotificationService.TYPE_LOGIN,
                    String.format("【登录通知】⚠️ 登录失败\n账号: %s\nIP: %s\n时间: %s",
                            params.getAccount(), ip, nowStr()));
            return ResponseData.error("账号或密码错误");
        }

        String token = CommonUtils.generateToken(effectiveAccount, effectivePwdHash);
        String ip = getClientIp(request);
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

        long now = System.currentTimeMillis();
        if (tgLoginCodeSentAt > 0 && now - tgLoginCodeSentAt < 30_000) {
            long wait = (30_000 - (now - tgLoginCodeSentAt)) / 1000;
            return ResponseData.error("请求过于频繁，请 " + wait + " 秒后重试");
        }

        String numPart = RandomUtil.randomNumbers(6);
        String mixPart = RandomUtil.randomString("ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789", 11);
        String code = numPart + ":" + mixPart;
        tgLoginCode = code;
        tgLoginCodeExpireAt = now + TG_CODE_EXPIRE_MS;
        tgLoginCodeSentAt = now;
        tgLoginFailCount.set(0);

        String ip = getClientIp(request);
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

        String inputCode = params.get("code");
        if (inputCode == null || inputCode.isBlank()) {
            return ResponseData.error("请输入验证码");
        }

        String ip = getClientIp(request);

        if (tgLoginCode == null) {
            return ResponseData.error("请先获取验证码");
        }

        if (System.currentTimeMillis() > tgLoginCodeExpireAt) {
            tgLoginCode = null;
            return ResponseData.error("验证码已过期，请重新获取");
        }

        if (tgLoginFailCount.get() >= TG_CODE_MAX_ATTEMPTS) {
            tgLoginCode = null;
            notificationService.sendMessage(String.format(
                    "【登录通知】🚨 TG验证码登录被锁定\n连续错误 %d 次\nIP: %s\n时间: %s",
                    TG_CODE_MAX_ATTEMPTS, ip, nowStr()));
            return ResponseData.error("验证码错误次数过多，已失效，请重新获取");
        }

        if (!tgLoginCode.equals(inputCode)) {
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
    public ResponseData<?> changePassword(@RequestBody Map<String, String> params) {
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
        return ResponseData.ok(Map.of("token", newToken, "message", "密码修改成功"));
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip))
            ip = request.getHeader("X-Real-IP");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip))
            ip = request.getRemoteAddr();
        if (ip != null && ip.contains(","))
            ip = ip.split(",")[0].trim();
        return ip;
    }

    private String nowStr() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
