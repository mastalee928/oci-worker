package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.enums.SysCfgEnum;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.model.entity.OciKv;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class NotificationService {

    public static final String TYPE_LOGIN = "login";
    public static final String TYPE_TASK_CREATE = "task_create";
    public static final String TYPE_TASK_RESULT = "task_result";
    public static final String TYPE_DAILY_REPORT = "daily_report";

    @Resource
    private OciKvMapper kvMapper;
    @Lazy
    @Resource
    private OciProxyConfigService ociProxyConfigService;

    public void sendMessage(String notifyType, String message) {
        if (!isTypeEnabled(notifyType)) return;
        sendTelegram(message);
    }

    /** Backward-compatible: send without type check (always send) */
    public void sendMessage(String message) {
        sendTelegram(message);
    }

    public boolean isNotifyTypeEnabled(String notifyType) {
        if (StrUtil.isBlank(notifyType)) return false;
        String types = getKvValue(SysCfgEnum.TG_NOTIFY_TYPES);
        if (StrUtil.isBlank(types)) return true;
        for (String t : types.split(",")) {
            if (notifyType.equals(t.trim())) return true;
        }
        return false;
    }

    private boolean isTypeEnabled(String notifyType) {
        return isNotifyTypeEnabled(notifyType);
    }

    private void sendTelegram(String message) {
        try {
            String botToken = getKvValue(SysCfgEnum.TG_BOT_TOKEN);
            String chatId = getKvValue(SysCfgEnum.TG_CHAT_ID);
            if (StrUtil.isBlank(botToken) || StrUtil.isBlank(chatId)) return;

            String url = String.format("https://api.telegram.org/bot%s/sendMessage", botToken);
            HttpClient c = ociProxyConfigService.newOutboundHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            JSONUtil.toJsonStr(Map.of("chat_id", chatId, "text", message))))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            c.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("Failed to send Telegram message: {}", e.getMessage());
        }
    }

    public void sendHtmlWithType(String notifyType, String html) {
        if (!isTypeEnabled(notifyType)) return;
        sendTelegramHtml(html, null);
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
            HttpClient c = ociProxyConfigService.newOutboundHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSONUtil.toJsonStr(body)))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            c.send(req, HttpResponse.BodyHandlers.discarding());
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

    /**
     * 安全类通知：不受「通知类型」开关过滤，只要已配置 Bot 即发送（用于登录失败带操作按钮等）。
     */
    public void sendSecurityTextWithInlineKeyboard(String text, List<List<Map<String, String>>> inlineKeyboard) {
        try {
            String botToken = getKvValue(SysCfgEnum.TG_BOT_TOKEN);
            String chatId = getKvValue(SysCfgEnum.TG_CHAT_ID);
            if (StrUtil.isBlank(botToken) || StrUtil.isBlank(chatId)) return;

            String url = String.format("https://api.telegram.org/bot%s/sendMessage", botToken);
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("reply_markup", Map.of("inline_keyboard", inlineKeyboard));
            HttpClient c = ociProxyConfigService.newOutboundHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSONUtil.toJsonStr(body)))
                    .timeout(Duration.ofSeconds(15))
                    .build();
            c.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("Failed to send Telegram security keyboard message: {}", e.getMessage());
        }
    }

    public void answerTelegramCallbackQuery(String callbackQueryId, String text, boolean showAlert) {
        try {
            String botToken = getKvValue(SysCfgEnum.TG_BOT_TOKEN);
            if (StrUtil.isBlank(botToken) || StrUtil.isBlank(callbackQueryId)) return;
            String url = String.format("https://api.telegram.org/bot%s/answerCallbackQuery", botToken);
            String t = text == null ? "" : text;
            if (t.length() > 180) t = t.substring(0, 177) + "...";
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("callback_query_id", callbackQueryId);
            body.put("text", t);
            body.put("show_alert", showAlert);
            HttpClient c = ociProxyConfigService.newOutboundHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSONUtil.toJsonStr(body)))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            c.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("Failed to answer Telegram callback: {}", e.getMessage());
        }
    }

    /** 注册左侧菜单斜杠命令（Telegram setMyCommands）。 */
    public void registerTelegramBotCommands() {
        try {
            String botToken = getKvValue(SysCfgEnum.TG_BOT_TOKEN);
            if (StrUtil.isBlank(botToken)) return;
            String url = String.format("https://api.telegram.org/bot%s/setMyCommands", botToken);
            java.util.List<Map<String, String>> commands = new java.util.ArrayList<>();
            commands.add(Map.of("command", "start", "description", "启动OCIWorker"));
            commands.add(Map.of("command", "stop", "description", "暂停全站访问"));
            commands.add(Map.of("command", "logs", "description", "抢机任务"));
            commands.add(Map.of("command", "state", "description", "系统状态"));
            Map<String, Object> body = Map.of("commands", commands);
            HttpClient c = ociProxyConfigService.newOutboundHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSONUtil.toJsonStr(body)))
                    .timeout(Duration.ofSeconds(15))
                    .build();
            c.send(req, HttpResponse.BodyHandlers.discarding());
            log.info("Telegram setMyCommands registered (start/stop/logs/state)");
        } catch (Exception e) {
            log.warn("Failed to register Telegram bot commands: {}", e.getMessage());
        }
    }

    /**
     * 向 Telegram 注册 Webhook：强制 {@code allowed_updates} 含 message + callback_query（私聊斜杠命令与内联按钮均需要）。
     *
     * @param publicBaseUrl 公网访问本服务的根，如 {@code https://panel.example.com}，勿带末尾 /
     * @param pathSecret    与 {@code ociworker.telegram.webhook-secret} 一致的路径段
     * @param secretToken     与 {@code ociworker.telegram.webhook-secret-token} 一致；可空
     */
    public Map<String, Object> applyTelegramWebhook(String publicBaseUrl, String pathSecret, String secretToken) {
        String botToken = getKvValue(SysCfgEnum.TG_BOT_TOKEN);
        if (StrUtil.isBlank(botToken)) {
            throw new OciException("未配置 TG Bot Token，无法 setWebhook");
        }
        String base = StrUtil.trimToNull(publicBaseUrl);
        if (base == null) {
            throw new OciException("publicBaseUrl 不能为空（示例：https://你的域名）");
        }
        base = base.replaceAll("/+$", "");
        if (StrUtil.isBlank(pathSecret)) {
            throw new OciException("未配置 Webhook 路径密钥（TG_WEBHOOK_SECRET / ociworker.telegram.webhook-secret）");
        }
        String hookUrl = base + "/api/tg/callback/" + pathSecret;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", hookUrl);
        body.put("allowed_updates", List.of("message", "callback_query"));
        if (StrUtil.isNotBlank(StrUtil.trim(secretToken))) {
            body.put("secret_token", StrUtil.trim(secretToken));
        }

        try {
            String api = String.format("https://api.telegram.org/bot%s/setWebhook", botToken);
            HttpClient c = ociProxyConfigService.newOutboundHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create(api))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSONUtil.toJsonStr(body)))
                    .timeout(Duration.ofSeconds(20))
                    .build();
            HttpResponse<String> resp = c.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null) {
                throw new OciException("setWebhook HTTP " + resp.statusCode());
            }
            JSONObject root = JSONUtil.parseObj(resp.body());
            if (!root.getBool("ok", false)) {
                throw new OciException("setWebhook 失败: " + root.getStr("description"));
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("setWebhook 请求异常: " + e.getMessage());
        }

        registerTelegramBotCommands();
        logTelegramWebhookDiagnostics();

        JSONObject result = tryFetchWebhookInfoOrNull(botToken);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appliedUrl", hookUrl);
        if (result != null) {
            out.put("telegramUrl", result.getStr("url"));
            out.put("pendingUpdateCount", result.getInt("pending_update_count", 0));
            out.put("lastErrorMessage", result.getStr("last_error_message"));
            JSONArray allowed = result.getJSONArray("allowed_updates");
            if (allowed != null) {
                List<String> list = new ArrayList<>();
                for (int i = 0; i < allowed.size(); i++) {
                    Object el = allowed.get(i);
                    if (el != null) list.add(el.toString());
                }
                out.put("allowedUpdates", list);
            }
        }
        return out;
    }

    /**
     * 启动时拉取 getWebhookInfo：若 allowed_updates 限制了类型且不含 message，斜杠命令永远不会 POST 到本服务。
     */
    public void logTelegramWebhookDiagnostics() {
        try {
            String botToken = getKvValue(SysCfgEnum.TG_BOT_TOKEN);
            if (StrUtil.isBlank(botToken)) return;
            JSONObject result = tryFetchWebhookInfoOrNull(botToken);
            if (result != null) {
                logWebhookResultIssues(result);
            }
        } catch (Exception e) {
            log.warn("[TG] getWebhookInfo failed: {}", e.getMessage());
        }
    }

    private JSONObject tryFetchWebhookInfoOrNull(String botToken) {
        try {
            String url = String.format("https://api.telegram.org/bot%s/getWebhookInfo", botToken);
            HttpClient c = ociProxyConfigService.newOutboundHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> resp = c.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null) {
                log.warn("[TG] getWebhookInfo HTTP {}", resp.statusCode());
                return null;
            }
            JSONObject root = JSONUtil.parseObj(resp.body());
            if (!root.getBool("ok", false)) {
                log.warn("[TG] getWebhookInfo ok=false body={}", resp.body());
                return null;
            }
            return root.getJSONObject("result");
        } catch (Exception e) {
            log.warn("[TG] getWebhookInfo failed: {}", e.getMessage());
            return null;
        }
    }

    private void logWebhookResultIssues(JSONObject result) {
        String hookUrl = result.getStr("url");
        if (StrUtil.isBlank(hookUrl)) {
            log.warn("[TG] Telegram 未配置 Webhook URL（url 为空）。请 setWebhook 指向 https://你的域名/api/tg/callback/<secret>。");
        }

        String lastErr = result.getStr("last_error_message");
        if (StrUtil.isNotBlank(lastErr)) {
            log.warn("[TG] Telegram Webhook 最近错误: {}", lastErr);
        }

        JSONArray allowed = result.getJSONArray("allowed_updates");
        if (allowed != null && allowed.size() > 0) {
            boolean hasMessage = false;
            for (int i = 0; i < allowed.size(); i++) {
                Object el = allowed.get(i);
                if (el != null && "message".equalsIgnoreCase(StrUtil.trim(el.toString()))) {
                    hasMessage = true;
                    break;
                }
            }
            if (!hasMessage) {
                log.warn("[TG] 当前 allowed_updates={} 未包含 message，私聊里的 /start、/state 等不会发到本服务（登录通知是服务端主动 sendMessage，与 Webhook 无关）。请重新 setWebhook，例如 allowed_updates=[\"message\",\"callback_query\"]。",
                        allowed);
            }
        }
        log.info("[TG] getWebhookInfo url={} pending={}", hookUrl, result.getInt("pending_update_count", 0));
    }

    /** 供面板查询当前 Telegram Webhook 状态（不修改配置）。 */
    public Map<String, Object> readTelegramWebhookSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        String botToken = getKvValue(SysCfgEnum.TG_BOT_TOKEN);
        if (StrUtil.isBlank(botToken)) {
            out.put("botTokenPresent", false);
            return out;
        }
        out.put("botTokenPresent", true);
        JSONObject result = tryFetchWebhookInfoOrNull(botToken);
        if (result == null) {
            out.put("fetchOk", false);
            return out;
        }
        out.put("fetchOk", true);
        out.put("url", result.getStr("url"));
        out.put("pendingUpdateCount", result.getInt("pending_update_count", 0));
        out.put("lastErrorMessage", result.getStr("last_error_message"));
        JSONArray allowed = result.getJSONArray("allowed_updates");
        if (allowed != null) {
            List<String> list = new ArrayList<>();
            for (int i = 0; i < allowed.size(); i++) {
                Object el = allowed.get(i);
                if (el != null) list.add(el.toString());
            }
            out.put("allowedUpdates", list);
        } else {
            out.put("allowedUpdates", null);
        }
        return out;
    }
}
