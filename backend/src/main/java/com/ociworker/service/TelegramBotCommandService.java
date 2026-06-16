package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.enums.SysCfgEnum;
import com.ociworker.enums.TaskStatusEnum;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciCreateTask;
import com.ociworker.model.entity.OciUser;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Telegram 文本命令：/start /stop /logs /state /bans（仅处理已绑定 chat_id）。
 */
@Slf4j
@Service
public class TelegramBotCommandService {

    private static final int TG_TEXT_MAX = 3800;
    private final AtomicBoolean checkAccsRunning = new AtomicBoolean(false);

    @Resource
    private VerifyCodeService verifyCodeService;
    @Resource
    private NotificationService notificationService;
    @Resource
    private LoginSecurityService loginSecurityService;
    @Resource
    private SystemService systemService;
    @Resource
    private OciCreateTaskMapper taskMapper;
    @Resource
    private OciUserMapper userMapper;
    @Resource
    private TenantHealthCheckService tenantHealthCheckService;

    public void handleTelegramMessage(JsonNode message) {
        if (!verifyCodeService.isTgConfigured()) return;
        if (message == null || !message.has("chat") || !message.has("text")) return;

        String configuredChat = normalizeChatIdStr(notificationService.getKvValue(SysCfgEnum.TG_CHAT_ID));
        if (StrUtil.isBlank(configuredChat)) return;

        String chatId = normalizeChatIdFromMessage(message);
        if (!configuredChat.equals(chatId)) {
            String raw = message.path("text").asText("").trim();
            if (raw.startsWith("/")) {
                log.warn("[TG] 斜杠命令已送达但 chat_id 不匹配：收到 [{}]，面板配置 TG_CHAT_ID=[{}]。请用 @userinfobot 查看本对话 id 并写入系统设置。",
                        chatId, configuredChat);
            } else {
                log.debug("[TG] ignore message from chat {}", chatId);
            }
            return;
        }

        String raw = message.get("text").asText("").trim();
        if (raw.isEmpty()) return;

        String firstToken = raw.split("\\s+")[0];
        String lower = firstToken.toLowerCase();
        String cmd = lower.contains("@") ? lower.substring(0, lower.indexOf('@')) : lower;

        switch (cmd) {
            case "/start" -> handleStart();
            case "/stop" -> handleStop();
            case "/logs" -> handleLogs();
            case "/state" -> handleState();
            case "/bans" -> handleBans();
            case "/checkaccs" -> handleCheckAccs();
            default -> { /* 非命令消息忽略 */ }
        }
    }

    private void handleStart() {
        loginSecurityService.setSitePaused(false);
        notificationService.sendMessage("OCIWorker已启动");
    }

    private void handleBans() {
        loginSecurityService.sendDenylistManagementKeyboard();
    }

    private void handleStop() {
        loginSecurityService.setSitePaused(true);
        notificationService.sendMessage("已暂停全站 API 访问。");
    }

    private void handleLogs() {
        List<OciCreateTask> list = taskMapper.selectList(
                new LambdaQueryWrapper<OciCreateTask>()
                        .eq(OciCreateTask::getStatus, TaskStatusEnum.RUNNING.getStatus())
                        .orderByDesc(OciCreateTask::getCreateTime));
        if (list.isEmpty()) {
            notificationService.sendMessage("当前无运行中的开机任务。");
            return;
        }
        Set<String> userIds = list.stream()
                .map(OciCreateTask::getUserId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, String> idToName = new HashMap<>();
        if (!userIds.isEmpty()) {
            List<OciUser> users = userMapper.selectList(
                    new LambdaQueryWrapper<OciUser>().in(OciUser::getId, userIds));
            for (OciUser u : users) {
                idToName.put(u.getId(), StrUtil.blankToDefault(u.getUsername(), u.getId()));
            }
        }

        int tenantN = userIds.size();
        StringBuilder sb = new StringBuilder();
        sb.append("当前有 ").append(tenantN).append(" 个租户正在开机（").append(list.size()).append(" 个运行中任务）。\n\n");
        for (String uid : userIds) {
            sb.append("· ").append(idToName.getOrDefault(uid, uid)).append('\n');
        }
        String out = sb.toString().trim();
        if (out.length() > TG_TEXT_MAX) {
            out = out.substring(0, TG_TEXT_MAX) + "\n…";
        }
        notificationService.sendMessage(out);
    }

    private void handleState() {
        Map<String, Object> g = systemService.getGlance();
        boolean paused = loginSecurityService.isSitePaused();
        String norm = paused ? "全站已暂停" : "全站运行中";

        long tenants = 0L;
        Object tc = g.get("tenantCount");
        if (tc instanceof Number n) tenants = n.longValue();

        long tasks = 0L;
        Object rc = g.get("runningTaskCount");
        if (rc instanceof Number n) tasks = n.longValue();

        String cpu = g.get("cpuUsage") != null ? String.valueOf(g.get("cpuUsage")) : "—";
        String mem = g.get("memoryUsage") != null ? String.valueOf(g.get("memoryUsage")) : "—";

        String msg = String.format(
                "状态：%s\n租户：%d\n运行中任务：%d\nCPU：%s%%\n内存：%s%%",
                norm, tenants, tasks, cpu, mem);
        notificationService.sendMessage(msg);
    }

    private void handleCheckAccs() {
        if (!checkAccsRunning.compareAndSet(false, true)) {
            notificationService.sendMessage("已有账户测活正在进行，请稍后再试。");
            return;
        }
        long total = userMapper.selectCount(null);
        notificationService.sendMessage("开始账户测活：共 " + total + " 个账户，检测中...");
        Thread.ofVirtual().name("tenant-health-check-", 0).start(() -> {
            try {
                TenantHealthCheckService.TenantHealthSummary health = tenantHealthCheckService.checkAllTenants();
                sendLongMessage(formatHealthSummary(health));
            } catch (Exception e) {
                log.warn("[TG] /checkaccs failed: {}", e.getMessage());
                notificationService.sendMessage("账户测活失败：" + shortReason(e));
            } finally {
                checkAccsRunning.set(false);
            }
        });
    }

    private String formatHealthSummary(TenantHealthCheckService.TenantHealthSummary health) {
        List<TenantHealthCheckService.TenantHealthResult> invalid = health.invalid();
        List<TenantHealthCheckService.TenantHealthResult> failed = health.failed();
        StringBuilder sb = new StringBuilder();
        sb.append("账户测活完成\n");
        sb.append("总数：").append(health.total()).append('\n');
        sb.append("正常：").append(health.okCount()).append('\n');
        sb.append("失效：").append(invalid.size()).append('\n');
        sb.append("检测失败：").append(failed.size()).append('\n');
        if (!invalid.isEmpty()) {
            sb.append("\n失效列表：\n");
            appendResults(sb, invalid);
        }
        if (!failed.isEmpty()) {
            sb.append("\n检测失败列表：\n");
            appendResults(sb, failed);
        }
        if (invalid.isEmpty() && failed.isEmpty()) {
            sb.append("\n所有账户配置正常");
        }
        return sb.toString().trim();
    }

    private static void appendResults(StringBuilder sb, List<TenantHealthCheckService.TenantHealthResult> results) {
        for (TenantHealthCheckService.TenantHealthResult result : results) {
            sb.append("· ").append(result.name());
            if (StrUtil.isNotBlank(result.reason())) {
                sb.append("：").append(result.reason());
            }
            sb.append('\n');
        }
    }

    private void sendLongMessage(String message) {
        if (StrUtil.isBlank(message)) {
            return;
        }
        int start = 0;
        while (start < message.length()) {
            int end = Math.min(start + TG_TEXT_MAX, message.length());
            if (end < message.length()) {
                int newline = message.lastIndexOf('\n', end);
                if (newline > start + 1000) {
                    end = newline;
                }
            }
            notificationService.sendMessage(message.substring(start, end).trim());
            start = end;
            while (start < message.length() && message.charAt(start) == '\n') {
                start++;
            }
        }
    }

    private static String shortReason(Throwable e) {
        String msg = e == null ? null : e.getMessage();
        if ((msg == null || msg.isBlank()) && e != null && e.getCause() != null) {
            msg = e.getCause().getMessage();
        }
        if (msg == null || msg.isBlank()) {
            msg = e == null ? "未知错误" : e.getClass().getSimpleName();
        }
        msg = msg.replace('\n', ' ').replace('\r', ' ').trim();
        return msg.length() > 120 ? msg.substring(0, 120) : msg;
    }

    /** 与 Telegram JSON 中 chat.id（数字或字符串）对齐，避免与面板里填的纯数字字符串不一致。 */
    private static String normalizeChatIdFromMessage(JsonNode message) {
        JsonNode id = message.path("chat").path("id");
        return normalizeChatIdNode(id);
    }

    private static String normalizeChatIdNode(JsonNode id) {
        if (id == null || id.isMissingNode() || id.isNull()) return "";
        if (id.isNumber()) {
            if (id.isIntegralNumber()) return Long.toString(id.longValue());
            return id.asText("");
        }
        return StrUtil.trim(id.asText(""));
    }

    private static String normalizeChatIdStr(String s) {
        if (StrUtil.isBlank(s)) return "";
        String t = StrUtil.trim(s);
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }
}
