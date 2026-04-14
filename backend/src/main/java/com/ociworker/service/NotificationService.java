package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.enums.SysCfgEnum;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.model.entity.OciKv;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class NotificationService {

    @Resource
    private OciKvMapper kvMapper;

    public void sendMessage(String message) {
        sendTelegram(message);
        sendDingTalk(message);
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

    private void sendDingTalk(String message) {
        try {
            String webhook = getKvValue(SysCfgEnum.DING_WEBHOOK);
            if (StrUtil.isBlank(webhook)) return;

            HttpRequest.post(webhook)
                    .header("Content-Type", "application/json")
                    .body(JSONUtil.toJsonStr(Map.of(
                            "msgtype", "text",
                            "text", Map.of("content", message))))
                    .timeout(10000)
                    .execute();
        } catch (Exception e) {
            log.warn("Failed to send DingTalk message: {}", e.getMessage());
        }
    }

    private String getKvValue(SysCfgEnum cfg) {
        OciKv kv = kvMapper.selectOne(
                new LambdaQueryWrapper<OciKv>()
                        .eq(OciKv::getCode, cfg.getCode())
                        .eq(OciKv::getType, cfg.getType())
                        .last("LIMIT 1"));
        return kv != null ? kv.getValue() : null;
    }
}
