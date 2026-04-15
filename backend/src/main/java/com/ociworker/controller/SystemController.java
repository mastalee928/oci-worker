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
        Map<String, String> config = new LinkedHashMap<>();
        config.put("botToken", notificationService.getKvValue(SysCfgEnum.TG_BOT_TOKEN));
        config.put("chatId", notificationService.getKvValue(SysCfgEnum.TG_CHAT_ID));
        config.put("notifyTypes", notificationService.getKvValue(SysCfgEnum.TG_NOTIFY_TYPES));
        return ResponseData.ok(config);
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
            notificationService.saveKvValue(SysCfgEnum.TG_BOT_TOKEN, params.get("botToken"));
        }
        if (params.containsKey("chatId")) {
            notificationService.saveKvValue(SysCfgEnum.TG_CHAT_ID, params.get("chatId"));
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
}
