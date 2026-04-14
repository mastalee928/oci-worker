package com.ociworker.controller;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.model.entity.OciKv;
import com.ociworker.model.params.LoginParams;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.NotificationService;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

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

    @PostMapping("/changePassword")
    public ResponseData<?> changePassword(@RequestBody Map<String, String> params) {
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
