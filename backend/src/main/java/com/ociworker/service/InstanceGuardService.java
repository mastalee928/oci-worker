package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.oracle.bmc.audit.AuditClient;
import com.oracle.bmc.audit.model.AuditEvent;
import com.oracle.bmc.audit.requests.ListEventsRequest;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.requests.GetInstanceRequest;
import com.oracle.bmc.model.BmcException;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciInstanceGuardMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciInstanceGuard;
import com.ociworker.model.entity.OciUser;
import com.ociworker.util.OciBmcErrorTranslator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实例守护：定时轮询已开启守护的实例，发现 STOPPED 立即自动 START。
 * 只对用户显式开启守护的实例生效，不影响手动停止其他实例。
 */
@Slf4j
@Service
public class InstanceGuardService {

    private static final int NOTIFY_AFTER_FAILURES = 3;
    /** START 之后的内部验收延迟：仅影响排期，不对用户暴露。 */
    private static final long VERIFY_DELAY_MILLIS = 30_000L;
    private static final String CALLBACK_MUTE = "iguard_mute:";
    private static final String CALLBACK_OFF = "iguard_off:";
    static final int DEFAULT_INTERVAL_MINUTES = 2;
    static final int MIN_INTERVAL_MINUTES = 1;
    static final int MAX_INTERVAL_MINUTES = 1440;
    private final AtomicBoolean checking = new AtomicBoolean(false);

    @Resource
    private OciInstanceGuardMapper guardMapper;
    @Resource
    private OciUserMapper userMapper;
    @Resource
    private InstanceService instanceService;
    @Resource
    private NotificationService notificationService;

    public Map<String, Object> status(String tenantId, String region, String instanceId) {
        OciInstanceGuard guard = find(requireText(tenantId, "租户配置 ID"),
                normalizeRegion(tenantId, region), requireText(instanceId, "实例 ID"));
        return toStatus(guard);
    }

    private Map<String, Object> toStatus(OciInstanceGuard guard) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", guard != null && Boolean.TRUE.equals(guard.getEnabled()));
        data.put("intervalMinutes", guard == null || guard.getIntervalMinutes() == null
                ? DEFAULT_INTERVAL_MINUTES : guard.getIntervalMinutes());
        data.put("lastState", guard == null ? null : guard.getLastState());
        data.put("lastCheckTime", guard == null ? null : guard.getLastCheckTime());
        data.put("lastStartTime", guard == null ? null : guard.getLastStartTime());
        data.put("startCount", guard == null || guard.getStartCount() == null ? 0 : guard.getStartCount());
        data.put("lastMessage", guard == null ? null : guard.getLastMessage());
        return data;
    }

    public Map<String, Object> save(String tenantId, String region, String instanceId,
                                    String instanceName, boolean enabled, Integer intervalMinutes) {
        tenantId = requireText(tenantId, "租户配置 ID");
        instanceId = requireText(instanceId, "实例 ID");
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");
        String normalizedRegion = normalizeRegion(tenantId, region);

        Date now = new Date();
        OciInstanceGuard guard = find(tenantId, normalizedRegion, instanceId);
        boolean isNew = guard == null;
        if (isNew) {
            guard = new OciInstanceGuard();
            guard.setId(UUID.randomUUID().toString().replace("-", ""));
            guard.setTenantConfigId(tenantId);
            guard.setRegion(normalizedRegion);
            guard.setInstanceId(instanceId);
            guard.setStartCount(0);
            guard.setCreateTime(now);
        }
        guard.setTenantName(user.getUsername());
        if (instanceName != null && !instanceName.isBlank()) {
            guard.setInstanceName(instanceName.trim());
        }
        if (intervalMinutes != null) {
            guard.setIntervalMinutes(clampInterval(intervalMinutes));
        } else if (guard.getIntervalMinutes() == null) {
            guard.setIntervalMinutes(DEFAULT_INTERVAL_MINUTES);
        }
        guard.setEnabled(enabled);
        // 开启后立刻进入下一个调度节拍检测一次。
        guard.setNextCheckTime(enabled ? now : null);
        guard.setConsecutiveFailures(0);
        guard.setNotifyMuted(false);
        guard.setLastMessage(enabled ? "守护已开启，等待下一轮检测" : "守护已关闭");
        guard.setUpdateTime(now);
        if (isNew) {
            guardMapper.insert(guard);
        } else {
            guardMapper.updateById(guard);
        }
        return status(tenantId, normalizedRegion, instanceId);
    }

    public List<OciInstanceGuard> listAll() {
        return guardMapper.selectList(new LambdaQueryWrapper<OciInstanceGuard>()
                .orderByDesc(OciInstanceGuard::getCreateTime));
    }

    public Map<String, Object> setEnabledById(String guardId, boolean enabled) {
        OciInstanceGuard guard = requireGuard(guardId);
        guard.setEnabled(enabled);
        guard.setNextCheckTime(enabled ? new Date() : null);
        guard.setConsecutiveFailures(0);
        if (enabled) guard.setNotifyMuted(false);
        guard.setLastMessage(enabled ? "守护已开启，等待下一轮检测" : "守护已关闭");
        guard.setUpdateTime(new Date());
        guardMapper.updateById(guard);
        return toStatus(guard);
    }

    public Map<String, Object> setNotifyMutedById(String guardId, boolean muted) {
        OciInstanceGuard guard = requireGuard(guardId);
        guard.setNotifyMuted(muted);
        guard.setUpdateTime(new Date());
        guardMapper.updateById(guard);
        return toStatus(guard);
    }

    public Map<String, Object> setIntervalById(String guardId, Integer intervalMinutes) {
        if (intervalMinutes == null) throw new OciException("检测间隔不能为空");
        OciInstanceGuard guard = requireGuard(guardId);
        guard.setIntervalMinutes(clampInterval(intervalMinutes));
        // 间隔变化立即生效：下一节拍按新间隔重新排期。
        if (Boolean.TRUE.equals(guard.getEnabled())) guard.setNextCheckTime(new Date());
        guard.setUpdateTime(new Date());
        guardMapper.updateById(guard);
        return toStatus(guard);
    }

    public void deleteById(String guardId) {
        guardMapper.deleteById(requireText(guardId, "守护记录 ID"));
    }

    /** 独立的停机原因查询：任何实例可用，不要求存在守护记录。 */
    public Map<String, Object> stopCause(String tenantId, String region, String instanceId) {
        tenantId = requireText(tenantId, "租户配置 ID");
        instanceId = requireText(instanceId, "实例 ID");
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");
        String normalizedRegion = normalizeRegion(tenantId, region);
        try (OciClientService client = new OciClientService(toSysUser(user, normalizedRegion),
                normalizedRegion)) {
            Instance instance = client.getComputeClient().getInstance(GetInstanceRequest.builder()
                    .instanceId(instanceId).build()).getInstance();
            if (instance == null) throw new OciException("实例不存在或无权访问");
            String cause = queryStopCause(client, instance.getCompartmentId(),
                    instanceId, normalizedRegion, 7, 20);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("instanceId", instanceId);
            out.put("state", instance.getLifecycleState() == null
                    ? "UNKNOWN" : instance.getLifecycleState().getValue());
            out.put("found", cause != null);
            out.put("cause", cause != null ? cause
                    : "近 7 天审计日志中未找到停机操作记录（可能超出查询窗口，或为更早之前停止）");
            return out;
        } catch (BmcException e) {
            throw new OciException("查询停机原因失败: " + OciBmcErrorTranslator.translate(e));
        }
    }

    /**
     * 从 OCI Audit 日志定位最近一次针对该实例的停止/终止操作并给出可读描述。
     * 找不到明确记录时返回 null。
     */
    private String queryStopCause(OciClientService client, String compartmentId,
                                  String instanceId, String region, int days, int maxPages) {
        try (AuditClient audit = buildAuditClient(client, region)) {
            Date end = new Date();
            Date start = new Date(end.getTime() - days * 86_400_000L);
            AuditEvent best = null;
            String page = null;
            int pages = 0;
            do {
                var response = audit.listEvents(ListEventsRequest.builder()
                        .compartmentId(compartmentId)
                        .startTime(start)
                        .endTime(end)
                        .page(page)
                        .build());
                for (AuditEvent event : response.getItems() == null
                        ? List.<AuditEvent>of() : response.getItems()) {
                    if (!isStopAuditEvent(event, instanceId)) continue;
                    if (best == null || (event.getEventTime() != null && best.getEventTime() != null
                            && event.getEventTime().after(best.getEventTime()))) {
                        best = event;
                    }
                }
                page = response.getOpcNextPage();
                pages++;
            } while (page != null && pages < maxPages);
            return best == null ? null : describeStopEvent(best);
        } catch (Exception e) {
            log.debug("停机原因查询失败 instance={}: {}", instanceId, e.getMessage());
            return null;
        }
    }

    private static boolean isStopAuditEvent(AuditEvent event, String instanceId) {
        if (event == null || event.getData() == null) return false;
        var data = event.getData();
        if (!instanceId.equals(data.getResourceId())) return false;
        String eventName = data.getEventName() == null ? "" : data.getEventName();
        if ("TerminateInstance".equalsIgnoreCase(eventName)) return true;
        if (!"InstanceAction".equalsIgnoreCase(eventName)) return false;
        try {
            var request = data.getRequest();
            Map<String, List<String>> parameters = request == null ? null : request.getParameters();
            if (parameters != null) {
                for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
                    if (!"action".equalsIgnoreCase(entry.getKey())) continue;
                    for (String value : entry.getValue() == null ? List.<String>of() : entry.getValue()) {
                        if (value != null && value.toUpperCase(Locale.ROOT).contains("STOP")) {
                            return true;
                        }
                    }
                    return false;
                }
            }
        } catch (Exception ignored) {
            // 参数结构异常时不误报为停机事件。
        }
        return false;
    }

    private static String describeStopEvent(AuditEvent event) {
        var data = event.getData();
        String eventName = data.getEventName() == null ? "" : data.getEventName();
        String principal = data.getIdentity() == null ? null
                : trimToNullStatic(data.getIdentity().getPrincipalName());
        String ip = data.getIdentity() == null ? null
                : trimToNullStatic(data.getIdentity().getIpAddress());
        String when = event.getEventTime() == null ? "未知时间"
                : new java.text.SimpleDateFormat("MM-dd HH:mm").format(event.getEventTime());
        String verb = "TerminateInstance".equalsIgnoreCase(eventName) ? "终止" : "停止";
        if (principal == null) {
            return "Oracle 系统于 " + when + " " + verb + "（疑似 Always Free 闲置回收或平台维护）";
        }
        return "用户 " + principal + (ip == null ? "" : "（IP " + ip + "）")
                + " 于 " + when + " 手动" + verb;
    }

    private static String trimToNullStatic(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String extractStopCause(String message) {
        if (message == null) return null;
        int index = message.indexOf("｜原因：");
        if (index < 0) return null;
        String cause = message.substring(index + "｜原因：".length()).trim();
        return cause.isBlank() ? null : cause;
    }

    private AuditClient buildAuditClient(OciClientService client, String region) {
        AuditClient.Builder builder = AuditClient.builder()
                .configuration(client.getClientConfiguration());
        if (client.getOciClientConfigurator() != null) {
            builder.additionalClientConfigurator(client.getOciClientConfigurator());
        }
        AuditClient audit = builder.build(client.getProvider());
        audit.setRegion(region);
        return audit;
    }

    private OciInstanceGuard requireGuard(String guardId) {
        OciInstanceGuard guard = guardMapper.selectById(requireText(guardId, "守护记录 ID"));
        if (guard == null) throw new OciException("守护记录不存在");
        return guard;
    }

    static int clampInterval(int minutes) {
        return Math.max(MIN_INTERVAL_MINUTES, Math.min(MAX_INTERVAL_MINUTES, minutes));
    }

    @Scheduled(fixedDelayString = "${oci.instance-guard.check-interval-millis:30000}",
            initialDelay = 45_000)
    public void checkGuards() {
        if (!checking.compareAndSet(false, true)) return;
        try {
            Date now = new Date();
            List<OciInstanceGuard> guards = guardMapper.selectList(
                    new LambdaQueryWrapper<OciInstanceGuard>()
                            .eq(OciInstanceGuard::getEnabled, true)
                            .and(w -> w.isNull(OciInstanceGuard::getNextCheckTime)
                                    .or().le(OciInstanceGuard::getNextCheckTime, now)));
            if (guards == null || guards.isEmpty()) return;

            Map<String, List<OciInstanceGuard>> byTenantRegion = new LinkedHashMap<>();
            for (OciInstanceGuard guard : guards) {
                byTenantRegion.computeIfAbsent(
                        guard.getTenantConfigId() + '|' + guard.getRegion(),
                        ignored -> new java.util.ArrayList<>()).add(guard);
            }
            for (List<OciInstanceGuard> group : byTenantRegion.values()) {
                checkTenantGroup(group);
            }
        } finally {
            checking.set(false);
        }
    }

    private void checkTenantGroup(List<OciInstanceGuard> group) {
        OciInstanceGuard first = group.get(0);
        OciUser user = userMapper.selectById(first.getTenantConfigId());
        if (user == null) {
            for (OciInstanceGuard guard : group) {
                disableGuard(guard, "租户配置已删除，守护自动关闭");
            }
            return;
        }
        try (OciClientService client = new OciClientService(toSysUser(user, first.getRegion()),
                first.getRegion())) {
            for (OciInstanceGuard guard : group) {
                try {
                    checkOne(client, user, guard);
                } catch (Exception e) {
                    recordFailure(user, guard, "检测失败: " + describe(e), false);
                }
            }
        } catch (Exception e) {
            log.warn("实例守护无法连接租户 {} 区域 {}: {}",
                    user.getUsername(), first.getRegion(), e.getMessage());
        }
    }

    private void checkOne(OciClientService client, OciUser user, OciInstanceGuard guard) {
        // 先按本条守护自己的间隔排期下一次检测，任何持久化路径都会带上它。
        guard.setNextCheckTime(new Date(System.currentTimeMillis() + intervalMillis(guard)));
        Instance instance;
        try {
            instance = client.getComputeClient().getInstance(GetInstanceRequest.builder()
                    .instanceId(guard.getInstanceId()).build()).getInstance();
        } catch (BmcException e) {
            if (e.getStatusCode() == 404) {
                disableGuard(guard, "实例已不存在，守护自动关闭");
                notify(user, guard, "实例已不存在（可能被终止或无权限访问），守护已自动关闭。");
                return;
            }
            throw e;
        }
        Instance.LifecycleState state = instance == null ? null : instance.getLifecycleState();
        String stateValue = state == null ? "UNKNOWN" : state.getValue().toUpperCase(Locale.ROOT);
        guard.setLastState(stateValue);
        guard.setLastCheckTime(new Date());

        if (state == Instance.LifecycleState.Terminated
                || state == Instance.LifecycleState.Terminating) {
            disableGuard(guard, "实例已终止，守护自动关闭");
            notify(user, guard, "实例已终止，守护已自动关闭。");
            return;
        }
        if (state != Instance.LifecycleState.Stopped) {
            guard.setConsecutiveFailures(0);
            // 自动启动后的确认：确认到已在启动/运行才补发成功通知。
            String previousMessage = guard.getLastMessage();
            boolean pendingStartNotify = previousMessage != null
                    && previousMessage.startsWith("检测到 STOPPED，已自动启动");
            if (recentlyStarted(guard)
                    && (state == Instance.LifecycleState.Running
                        || state == Instance.LifecycleState.Starting)) {
                guard.setLastMessage("自动启动成功，当前状态 " + stateValue);
                guard.setUpdateTime(new Date());
                guardMapper.updateById(guard);
                if (pendingStartNotify) {
                    String cause = extractStopCause(previousMessage);
                    notify(user, guard, "检测到实例处于 STOPPED"
                            + (cause == null ? "" : "（" + cause + "）")
                            + "，已自动执行启动，当前状态 " + stateValue + "。");
                }
            } else {
                guard.setLastMessage("实例状态 " + stateValue + "，无需处理");
                guard.setUpdateTime(new Date());
                guardMapper.updateById(guard);
            }
            return;
        }

        boolean restartLoop = recentlyStarted(guard);
        // 停机原因来自 Audit 日志（免费）；反复停机场景原因已知，不重复查询。
        String stopCause = restartLoop ? null : queryStopCause(client, instance.getCompartmentId(),
                guard.getInstanceId(), guard.getRegion(), 3, 10);
        try {
            instanceService.updateInstanceState(
                    guard.getTenantConfigId(), guard.getInstanceId(), "START", guard.getRegion());
            Date now = new Date();
            guard.setLastStartTime(now);
            guard.setStartCount((guard.getStartCount() == null ? 0 : guard.getStartCount()) + 1);
            guard.setConsecutiveFailures(0);
            guard.setLastMessage(restartLoop ? "再次检测到 STOPPED，已重新启动"
                    : "检测到 STOPPED，已自动启动"
                            + (stopCause == null ? "" : "｜原因：" + stopCause));
            // 内部确认排期：正常场景稍后确认启动结果并补发成功通知；
            // 刚启动过又停的（疑似反复停机）回到用户设置的间隔，避免刷屏。
            guard.setNextCheckTime(restartLoop
                    ? new Date(now.getTime() + intervalMillis(guard))
                    : new Date(now.getTime() + VERIFY_DELAY_MILLIS));
            guard.setUpdateTime(now);
            guardMapper.updateById(guard);
            log.info("实例守护自动启动: tenant={} instance={} region={}",
                    user.getUsername(), guard.getInstanceId(), guard.getRegion());
            if (restartLoop) {
                notifyWithGuardButtons(user, guard,
                        "实例启动后再次停止，已重新启动。若持续发生请检查实例系统日志。");
            }
        } catch (Exception e) {
            recordFailure(user, guard, "自动启动失败: " + describe(e), true);
        }
    }

    private boolean recentlyStarted(OciInstanceGuard guard) {
        return guard.getLastStartTime() != null
                && System.currentTimeMillis() - guard.getLastStartTime().getTime()
                        < Math.max(VERIFY_DELAY_MILLIS * 4, 120_000L);
    }

    private long intervalMillis(OciInstanceGuard guard) {
        int interval = guard.getIntervalMinutes() == null
                ? DEFAULT_INTERVAL_MINUTES : clampInterval(guard.getIntervalMinutes());
        return interval * 60_000L;
    }

    private void recordFailure(OciUser user, OciInstanceGuard guard, String message,
                               boolean notifyImmediately) {
        int failures = (guard.getConsecutiveFailures() == null ? 0 : guard.getConsecutiveFailures()) + 1;
        guard.setConsecutiveFailures(failures);
        guard.setLastMessage(truncate(message));
        guard.setUpdateTime(new Date());
        guardMapper.updateById(guard);
        log.warn("实例守护处理失败({}次): tenant={} instance={} {}",
                failures, user.getUsername(), guard.getInstanceId(), message);
        // 启动失败第一次就通知（用户需要马上知道具体错误）；之后每 3 次提醒一次防刷屏。
        boolean shouldNotify = notifyImmediately
                ? failures == 1 || failures % NOTIFY_AFTER_FAILURES == 0
                : failures == NOTIFY_AFTER_FAILURES;
        if (shouldNotify) {
            notifyWithGuardButtons(user, guard, "自动处理失败（连续第 " + failures + " 次）：" + message
                    + "\n守护会按设定间隔继续重试。");
        }
    }

    private void disableGuard(OciInstanceGuard guard, String message) {
        guard.setEnabled(false);
        guard.setLastMessage(truncate(message));
        guard.setUpdateTime(new Date());
        guardMapper.updateById(guard);
    }

    private void notify(OciUser user, OciInstanceGuard guard, String body) {
        if (Boolean.TRUE.equals(guard.getNotifyMuted())) return;
        try {
            notificationService.sendMessage(buildNotifyText(user, guard, body));
        } catch (Exception e) {
            log.debug("实例守护通知发送失败: {}", e.getMessage());
        }
    }

    /** 失败类通知：附带「停止通知 / 停止检测」按钮，均只作用于该实例。 */
    private void notifyWithGuardButtons(OciUser user, OciInstanceGuard guard, String body) {
        if (Boolean.TRUE.equals(guard.getNotifyMuted())) return;
        try {
            notificationService.sendMessageWithInlineKeyboard(
                    buildNotifyText(user, guard, body),
                    List.of(List.of(
                            Map.of("text", "🔕 停止通知", "callback_data", CALLBACK_MUTE + guard.getId()),
                            Map.of("text", "⏹ 停止检测", "callback_data", CALLBACK_OFF + guard.getId()))));
        } catch (Exception e) {
            log.debug("实例守护通知发送失败: {}", e.getMessage());
        }
    }

    private static String buildNotifyText(OciUser user, OciInstanceGuard guard, String body) {
        return "【OCIWorker 实例守护】\n\n租户：" + user.getUsername()
                + "\n实例：" + guardDisplayName(guard)
                + "\n区域：" + guard.getRegion()
                + "\n" + body;
    }

    private static String guardDisplayName(OciInstanceGuard guard) {
        return guard.getInstanceName() == null || guard.getInstanceName().isBlank()
                ? guard.getInstanceId() : guard.getInstanceName();
    }

    public boolean tryHandleTelegramCallback(String rawData, String callbackQueryId,
                                             String answeringBotToken) {
        if (rawData == null || !rawData.startsWith("iguard_")) return false;
        try {
            if (rawData.startsWith(CALLBACK_MUTE)) {
                OciInstanceGuard guard = guardMapper.selectById(
                        rawData.substring(CALLBACK_MUTE.length()));
                if (guard == null) {
                    answerCallback(callbackQueryId, "守护记录不存在或已删除", answeringBotToken);
                    return true;
                }
                guard.setNotifyMuted(true);
                guard.setUpdateTime(new Date());
                guardMapper.updateById(guard);
                answerCallback(callbackQueryId, "已停止该实例的守护通知", answeringBotToken);
                notificationService.sendMessage("【OCIWorker 实例守护】\n\n已停止实例 "
                        + guardDisplayName(guard) + " 的守护通知，检测和自动启动仍在继续。"
                        + "\n可在「实例守护」页面恢复通知。");
                return true;
            }
            if (rawData.startsWith(CALLBACK_OFF)) {
                OciInstanceGuard guard = guardMapper.selectById(
                        rawData.substring(CALLBACK_OFF.length()));
                if (guard == null) {
                    answerCallback(callbackQueryId, "守护记录不存在或已删除", answeringBotToken);
                    return true;
                }
                guard.setEnabled(false);
                guard.setNextCheckTime(null);
                guard.setLastMessage("已通过 Telegram 停止检测");
                guard.setUpdateTime(new Date());
                guardMapper.updateById(guard);
                answerCallback(callbackQueryId, "已停止检测该实例", answeringBotToken);
                notificationService.sendMessage("【OCIWorker 实例守护】\n\n已停止实例 "
                        + guardDisplayName(guard) + " 的守护检测。"
                        + "\n可在「实例守护」页面重新开启。");
                return true;
            }
            answerCallback(callbackQueryId, "无效操作", answeringBotToken);
            return true;
        } catch (Exception e) {
            log.warn("实例守护 TG 回调处理失败: {}", e.getMessage());
            answerCallback(callbackQueryId, "处理失败，请稍后重试", answeringBotToken);
            return true;
        }
    }

    private void answerCallback(String callbackQueryId, String text, String botToken) {
        try {
            notificationService.answerTelegramCallbackQuery(callbackQueryId, text, false, botToken);
        } catch (Exception e) {
            log.debug("实例守护回调应答失败: {}", e.getMessage());
        }
    }

    private OciInstanceGuard find(String tenantId, String region, String instanceId) {
        return guardMapper.selectOne(new LambdaQueryWrapper<OciInstanceGuard>()
                .eq(OciInstanceGuard::getTenantConfigId, tenantId)
                .eq(OciInstanceGuard::getRegion, region)
                .eq(OciInstanceGuard::getInstanceId, instanceId)
                .last("LIMIT 1"));
    }

    private String normalizeRegion(String tenantId, String region) {
        if (region != null && !region.isBlank()) return region.trim();
        OciUser user = userMapper.selectById(tenantId);
        String fallback = user == null ? null : user.getOciRegion();
        if (fallback == null || fallback.isBlank()) throw new OciException("OCI 区域不能为空");
        return fallback.trim();
    }

    private static SysUserDTO toSysUser(OciUser user, String region) {
        return SysUserDTO.builder()
                .username(user.getUsername())
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(user.getOciTenantId())
                        .userId(user.getOciUserId())
                        .fingerprint(user.getOciFingerprint())
                        .region(region)
                        .privateKeyPath(user.getOciKeyPath())
                        .build())
                .build();
    }

    private static String describe(Exception e) {
        if (e instanceof BmcException bmc) return OciBmcErrorTranslator.translate(bmc);
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new OciException(label + "不能为空");
        return value.trim();
    }
}
