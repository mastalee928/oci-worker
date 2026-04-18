package com.ociworker.service;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.ociworker.enums.SysCfgEnum;
import com.ociworker.exception.OciException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class VerifyCodeService {

    private static final long CODE_EXPIRE_MS = 5 * 60 * 1000;
    private static final Map<String, CodeEntry> codeStore = new ConcurrentHashMap<>();

    @Resource
    private NotificationService notificationService;

    private record CodeEntry(String code, long expireAt) {}

    public boolean isTgConfigured() {
        String token = notificationService.getKvValue(SysCfgEnum.TG_BOT_TOKEN);
        String chatId = notificationService.getKvValue(SysCfgEnum.TG_CHAT_ID);
        return StrUtil.isNotBlank(token) && StrUtil.isNotBlank(chatId);
    }

    public void sendCode(String action) {
        if (!isTgConfigured()) {
            throw new OciException("未绑定 Telegram，无法执行此操作。请先在系统设置中配置 TG Bot。");
        }
        String code = RandomUtil.randomNumbers(6);
        codeStore.put(action, new CodeEntry(code, System.currentTimeMillis() + CODE_EXPIRE_MS));

        String actionName = switch (action) {
            case "terminate" -> "终止实例";
            case "backup" -> "备份数据";
            case "createUser" -> "新增用户";
            case "updateUser" -> "修改用户信息";
            case "removeFromAdmin" -> "移出管理员组";
            case "clearMfa" -> "清理 MFA";
            case "disableUser" -> "禁用用户";
            case "changePassword" -> "修改登录密码";
            case "deleteVolume" -> "删除卷";
            case "deleteVcn" -> "删除 VCN 资源";
            default -> action;
        };
        String msg = String.format("【OCI Worker 安全验证】\n操作：%s\n验证码：%s\n有效期：5分钟\n\n如非本人操作，请检查账户安全。", actionName, code);
        notificationService.sendMessage(msg);
        log.info("Verification code sent for action: {}", action);
    }

    public void verifyCode(String action, String inputCode) {
        if (!isTgConfigured()) {
            throw new OciException("未绑定 Telegram，无法执行此操作");
        }
        CodeEntry entry = codeStore.get(action);
        if (entry == null) {
            throw new OciException("请先获取验证码");
        }
        if (System.currentTimeMillis() > entry.expireAt()) {
            codeStore.remove(action);
            throw new OciException("验证码已过期，请重新获取");
        }
        if (!entry.code().equals(inputCode)) {
            throw new OciException("验证码错误");
        }
        codeStore.remove(action);
    }
}
