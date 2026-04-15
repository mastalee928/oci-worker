package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.enums.SysCfgEnum;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.model.entity.OciKv;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class NotificationService {

    public static final String TYPE_LOGIN = "login";
    public static final String TYPE_TASK_CREATE = "task_create";
    public static final String TYPE_TASK_RESULT = "task_result";
    public static final String TYPE_DAILY_REPORT = "daily_report";

    @Resource
    private OciKvMapper kvMapper;

    public void sendMessage(String notifyType, String message) {
        if (!isTypeEnabled(notifyType)) return;
        sendTelegram(message);
    }

    /** Backward-compatible: send without type check (always send) */
    public void sendMessage(String message) {
        sendTelegram(message);
    }

    private boolean isTypeEnabled(String notifyType) {
        String types = getKvValue(SysCfgEnum.TG_NOTIFY_TYPES);
        if (StrUtil.isBlank(types)) return true;
        return Set.of(types.split(",")).contains(notifyType);
    }

    private void sendTelegram(String message) {
        try {
            String botToken = getKvValue(SysCfgEnum.TG_BOT_TOKEN);
            String chatId = getKvValue(SysCfgEnum.TG_CHAT_ID);
            if (StrUtil.isBlank(botToken) || StrUtil.isBlank(chatId)) return;

            String url = String.format("https://api.telegram.org/bot%s/sendMessage", botToken);
            HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .body(JSONUtil.toJsonStr(Map.of("chat_id", chatId, "text", message)))
                    .timeout(10000)
                    .execute();
        } catch (Exception e) {
            log.warn("Failed to send Telegram message: {}", e.getMessage());
        }
    }

    public void sendTelegramHtml(String html, String copyText) {
        try {
            String botToken = getKvValue(SysCfgEnum.TG_BOT_TOKEN);
            String chatId = getKvValue(SysCfgEnum.TG_CHAT_ID);
            if (StrUtil.isBlank(botToken) || StrUtil.isBlank(chatId)) return;

            String url = String.format("https://api.telegram.org/bot%s/sendMessage", botToken);
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("chat_id", chatId);
            body.put("text", html);
            body.put("parse_mode", "HTML");
            if (StrUtil.isNotBlank(copyText)) {
                body.put("reply_markup", Map.of("inline_keyboard",
                        java.util.List.of(java.util.List.of(
                                Map.of("text", "📋 复制验证码", "callback_data", "copy_noop",
                                        "copy_text", copyText)
                        ))));
            }
            HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .body(JSONUtil.toJsonStr(body))
                    .timeout(10000)
                    .execute();
        } catch (Exception e) {
            log.warn("Failed to send Telegram HTML message: {}", e.getMessage());
        }
    }

    public String getKvValue(SysCfgEnum cfg) {
        OciKv kv = kvMapper.selectOne(
                new LambdaQueryWrapper<OciKv>()
                        .eq(OciKv::getCode, cfg.getCode())
                        .eq(OciKv::getType, cfg.getType())
                        .last("LIMIT 1"));
        return kv != null ? kv.getValue() : null;
    }

    public void saveKvValue(SysCfgEnum cfg, String value) {
        OciKv existing = kvMapper.selectOne(
                new LambdaQueryWrapper<OciKv>()
                        .eq(OciKv::getCode, cfg.getCode())
                        .eq(OciKv::getType, cfg.getType())
                        .last("LIMIT 1"));
        if (existing != null) {
            existing.setValue(value);
            kvMapper.updateById(existing);
        } else {
            OciKv kv = new OciKv();
            kv.setId(CommonUtils.generateId());
            kv.setCode(cfg.getCode());
            kv.setType(cfg.getType());
            kv.setValue(value);
            kvMapper.insert(kv);
        }
    }
}
