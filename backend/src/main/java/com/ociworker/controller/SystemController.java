package com.ociworker.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.ociworker.enums.SysCfgEnum;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.NotificationService;
import com.ociworker.service.SystemService;
import com.ociworker.service.VerifyCodeService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/sys")
public class SystemController {

    @Resource
    private SystemService systemService;
    @Resource
    private NotificationService notificationService;
    @Resource
    private VerifyCodeService verifyCodeService;
    @Resource
    private AuthController authController;

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
}
