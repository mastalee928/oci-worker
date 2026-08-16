package com.ociworker.service;

import com.oracle.bmc.logging.LoggingManagementClient;
import com.oracle.bmc.logging.model.CreateLogDetails;
import com.oracle.bmc.logging.model.CreateLogGroupDetails;
import com.oracle.bmc.logging.model.Log;
import com.oracle.bmc.logging.model.LogGroupSummary;
import com.oracle.bmc.logging.model.LogSummary;
import com.oracle.bmc.logging.model.OciService;
import com.oracle.bmc.logging.model.Configuration;
import com.oracle.bmc.logging.requests.CreateLogGroupRequest;
import com.oracle.bmc.logging.requests.CreateLogRequest;
import com.oracle.bmc.logging.requests.DeleteLogRequest;
import com.oracle.bmc.logging.requests.ListLogGroupsRequest;
import com.oracle.bmc.logging.requests.ListLogsRequest;
import com.oracle.bmc.loggingsearch.LogSearchClient;
import com.oracle.bmc.loggingsearch.model.SearchLogsDetails;
import com.oracle.bmc.loggingsearch.model.SearchResult;
import com.oracle.bmc.loggingsearch.requests.SearchLogsRequest;
import com.oracle.bmc.model.BmcException;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.ociworker.util.OciBmcErrorTranslator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VCN 流日志：按子网开关（Logging 服务 service=flowlogs），并支持按实例私网 IP
 * 查询流量记录用于安全列表排查。开启时发 TG 通知并附「关闭」按钮防止额度超支。
 */
@Slf4j
@Service
public class VcnFlowLogService {

    private static final String LOG_GROUP_NAME = "ociworker-flowlogs";
    private static final String CALLBACK_OFF = "vcnflow_off:";
    private static final int LOG_RETENTION_DAYS = 30;
    private static final long POLL_MILLIS = 2_000L;

    @Resource
    private OciUserMapper userMapper;
    @Resource
    private NotificationService notificationService;

    /** TG 关闭按钮的短令牌 → 流日志引用（内存态；重启后按钮失效，可去面板关闭）。 */
    private record FlowLogRef(String tenantId, String region, String subnetId, String subnetName) {
    }

    private final ConcurrentHashMap<String, FlowLogRef> offTokens = new ConcurrentHashMap<>();

    public Map<String, Object> status(String userId, String region, List<String> subnetIds) {
        OciUser user = requireUser(userId);
        Map<String, Object> bySubnet = new LinkedHashMap<>();
        if (subnetIds == null || subnetIds.isEmpty()) return Map.of("subnets", bySubnet);
        try (OciClientService client = new OciClientService(toSysUser(user, region), region);
             LoggingManagementClient logging = buildLoggingClient(client, region)) {
            String logGroupId = findLogGroupId(logging, user.getOciTenantId());
            Map<String, String> activeBySubnet = logGroupId == null
                    ? Map.of() : listActiveFlowLogsBySubnet(logging, logGroupId);
            for (String subnetId : subnetIds) {
                if (subnetId == null || subnetId.isBlank()) continue;
                bySubnet.put(subnetId, Map.of("enabled", activeBySubnet.containsKey(subnetId)));
            }
            return Map.of("subnets", bySubnet);
        } catch (BmcException e) {
            throw new OciException("读取流日志状态失败: " + OciBmcErrorTranslator.translate(e));
        }
    }

    public Map<String, Object> toggle(String userId, String region, String subnetId,
                                      String subnetName, boolean enabled) {
        OciUser user = requireUser(userId);
        if (subnetId == null || subnetId.isBlank()) throw new OciException("子网 ID 不能为空");
        try (OciClientService client = new OciClientService(toSysUser(user, region), region);
             LoggingManagementClient logging = buildLoggingClient(client, region)) {
            if (enabled) {
                String logGroupId = ensureLogGroup(logging, user.getOciTenantId());
                Map<String, String> active = listActiveFlowLogsBySubnet(logging, logGroupId);
                if (!active.containsKey(subnetId)) {
                    createFlowLog(logging, logGroupId, subnetId, subnetName);
                }
                notifyEnabled(user, region, subnetId, subnetName);
            } else {
                String logGroupId = findLogGroupId(logging, user.getOciTenantId());
                if (logGroupId != null) {
                    String logId = listActiveFlowLogsBySubnet(logging, logGroupId).get(subnetId);
                    if (logId != null) {
                        logging.deleteLog(DeleteLogRequest.builder()
                                .logGroupId(logGroupId).logId(logId).build());
                    }
                }
            }
            return Map.of("enabled", enabled);
        } catch (BmcException e) {
            throw new OciException((enabled ? "开启" : "关闭") + "流日志失败: "
                    + OciBmcErrorTranslator.translateWithServiceDetail(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OciException("流日志操作已取消");
        }
    }

    /** 按实例私网 IP 查询流量记录（IP 为空时按 instanceId 自动解析主 VNIC 私网 IP）。 */
    public Map<String, Object> search(String userId, String region, String privateIp,
                                      String instanceId, int minutes, boolean rejectOnly) {
        OciUser user = requireUser(userId);
        String effectiveRegion = region == null || region.isBlank() ? user.getOciRegion() : region.trim();
        String ip = privateIp == null ? "" : privateIp.trim();
        int boundedMinutes = Math.max(5, Math.min(minutes <= 0 ? 60 : minutes, 14 * 24 * 60));
        String query = null;
        try (OciClientService client = new OciClientService(toSysUser(user, effectiveRegion), effectiveRegion);
             LoggingManagementClient logging = buildLoggingClient(client, effectiveRegion);
             LogSearchClient searchClient = buildSearchClient(client, effectiveRegion)) {
            if (ip.isEmpty() && instanceId != null && !instanceId.isBlank()) {
                ip = resolvePrimaryPrivateIp(client, instanceId.trim());
            }
            if (!ip.matches("[0-9a-fA-F:.]{3,45}")) throw new OciException("无法确定实例私网 IP");
            String logGroupId = findLogGroupId(logging, user.getOciTenantId());
            if (logGroupId == null) {
                return Map.of("records", List.of(), "flowLogConfigured", false, "privateIp", ip);
            }
            String scope = "\"" + user.getOciTenantId() + "/" + logGroupId + "\"";
            query = "search " + scope
                    + " | where (data.sourceAddress = '" + ip + "' or data.destinationAddress = '" + ip + "')"
                    + (rejectOnly ? " and data.action = 'REJECT'" : "")
                    + " | sort by datetime desc";
            log.debug("流日志查询语句: {}", query);
            Date end = new Date();
            Date start = new Date(end.getTime() - boundedMinutes * 60_000L);
            var response = searchClient.searchLogs(SearchLogsRequest.builder()
                    .limit(200)
                    .searchLogsDetails(SearchLogsDetails.builder()
                            .timeStart(start)
                            .timeEnd(end)
                            .searchQuery(query)
                            .isReturnFieldInfo(false)
                            .build())
                    .build());
            List<Map<String, Object>> records = new ArrayList<>();
            List<SearchResult> results = response.getSearchResponse() == null
                    || response.getSearchResponse().getResults() == null
                    ? List.of() : response.getSearchResponse().getResults();
            for (SearchResult result : results) {
                Map<String, Object> row = flattenFlowRecord(result, ip);
                if (row != null) records.add(row);
            }
            return Map.of("records", records, "flowLogConfigured", true, "privateIp", ip);
        } catch (BmcException e) {
            // 详情只进服务端日志，面板保持简洁报错。
            log.warn("流日志查询被拒 tenant={} region={} minutes={} rejectOnly={} query={} 错误: {}",
                    user.getUsername(), effectiveRegion, boundedMinutes, rejectOnly, query,
                    OciBmcErrorTranslator.translateWithServiceDetail(e));
            if (e.getStatusCode() == 400) {
                // 新建的日志组/日志在 Logging Search 索引就绪前会被 400 拒绝，属瞬态。
                throw new OciException("查询流日志失败：若流日志刚开启，"
                        + "Oracle 搜索索引可能尚未就绪，请等待几分钟后重试。");
            }
            throw new OciException("查询流日志失败: " + OciBmcErrorTranslator.translate(e));
        }
    }

    /** 查询实例所在子网（主 VNIC）的流日志状态，供实例详情页开关使用。 */
    public Map<String, Object> instanceStatus(String userId, String region, String instanceId) {
        OciUser user = requireUser(userId);
        if (instanceId == null || instanceId.isBlank()) throw new OciException("实例 ID 不能为空");
        String effectiveRegion = region == null || region.isBlank() ? user.getOciRegion() : region.trim();
        try (OciClientService client = new OciClientService(toSysUser(user, effectiveRegion), effectiveRegion);
             LoggingManagementClient logging = buildLoggingClient(client, effectiveRegion)) {
            var vnic = resolvePrimaryVnic(client, instanceId.trim());
            String subnetId = vnic.getSubnetId();
            String subnetName = null;
            try {
                var subnet = client.getVirtualNetworkClient().getSubnet(
                        com.oracle.bmc.core.requests.GetSubnetRequest.builder()
                                .subnetId(subnetId).build()).getSubnet();
                subnetName = subnet == null ? null : subnet.getDisplayName();
            } catch (Exception e) {
                log.debug("读取子网名称失败: {}", e.getMessage());
            }
            String logGroupId = findLogGroupId(logging, user.getOciTenantId());
            boolean enabled = logGroupId != null
                    && listActiveFlowLogsBySubnet(logging, logGroupId).containsKey(subnetId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("subnetId", subnetId);
            out.put("subnetName", subnetName);
            out.put("enabled", enabled);
            out.put("privateIp", vnic.getPrivateIp());
            return out;
        } catch (BmcException e) {
            throw new OciException("读取流日志状态失败: " + OciBmcErrorTranslator.translate(e));
        }
    }

    private String resolvePrimaryPrivateIp(OciClientService client, String instanceId) {
        var vnic = resolvePrimaryVnic(client, instanceId);
        if (vnic.getPrivateIp() == null) throw new OciException("无法解析实例私网 IP");
        return vnic.getPrivateIp();
    }

    private com.oracle.bmc.core.model.Vnic resolvePrimaryVnic(OciClientService client, String instanceId) {
        var instance = client.getComputeClient().getInstance(
                com.oracle.bmc.core.requests.GetInstanceRequest.builder()
                        .instanceId(instanceId).build()).getInstance();
        if (instance == null) throw new OciException("实例不存在或无权访问");
        var attachments = client.getComputeClient().listVnicAttachments(
                com.oracle.bmc.core.requests.ListVnicAttachmentsRequest.builder()
                        .compartmentId(instance.getCompartmentId())
                        .instanceId(instanceId)
                        .build()).getItems();
        com.oracle.bmc.core.model.Vnic fallback = null;
        for (var attachment : attachments == null
                ? List.<com.oracle.bmc.core.model.VnicAttachment>of() : attachments) {
            if (attachment == null || attachment.getVnicId() == null) continue;
            var vnic = client.getVirtualNetworkClient().getVnic(
                    com.oracle.bmc.core.requests.GetVnicRequest.builder()
                            .vnicId(attachment.getVnicId()).build()).getVnic();
            if (vnic == null) continue;
            if (Boolean.TRUE.equals(vnic.getIsPrimary())) return vnic;
            if (fallback == null) fallback = vnic;
        }
        if (fallback != null) return fallback;
        throw new OciException("无法解析实例网卡");
    }

    public boolean tryHandleTelegramCallback(String rawData, String callbackQueryId,
                                             String answeringBotToken) {
        if (rawData == null || !rawData.startsWith(CALLBACK_OFF)) return false;
        String token = rawData.substring(CALLBACK_OFF.length());
        FlowLogRef ref = offTokens.remove(token);
        try {
            if (ref == null) {
                notificationService.answerTelegramCallbackQuery(callbackQueryId,
                        "操作已过期或服务已重启，请到面板 VCN 页面关闭", false, answeringBotToken);
                return true;
            }
            toggle(ref.tenantId(), ref.region(), ref.subnetId(), ref.subnetName(), false);
            notificationService.answerTelegramCallbackQuery(callbackQueryId,
                    "已关闭该子网的流日志", false, answeringBotToken);
            notificationService.sendMessage("【OCIWorker 流日志】\n\n已通过 Telegram 关闭子网 "
                    + displaySubnet(ref.subnetName(), ref.subnetId()) + " 的流日志。");
        } catch (Exception e) {
            if (ref != null) offTokens.put(token, ref);
            log.warn("流日志 TG 关闭失败: {}", e.getMessage());
            notificationService.answerTelegramCallbackQuery(callbackQueryId,
                    "关闭失败，请稍后重试或到面板操作", false, answeringBotToken);
        }
        return true;
    }

    private void notifyEnabled(OciUser user, String region, String subnetId, String subnetName) {
        try {
            String token = com.ociworker.util.SecureRandomUtil.randomHex(8);
            offTokens.put(token, new FlowLogRef(user.getId(), region, subnetId, subnetName));
            notificationService.sendMessageWithInlineKeyboard(
                    "【OCIWorker 流日志】\n\n租户：" + user.getUsername()
                            + "\n区域：" + (region == null || region.isBlank()
                                    ? user.getOciRegion() : region)
                            + "\n子网：" + displaySubnet(subnetName, subnetId)
                            + "\n已开启 VCN 流日志。"
                            + "\n\n⚠️ Logging 免费额度每月 10GB（全租户共享）："
                            + "免费账户超额后日志停止写入，付费账户超额将计费。"
                            + "排查完请及时关闭。",
                    List.of(List.of(Map.of(
                            "text", "🛑 关闭此子网流日志",
                            "callback_data", CALLBACK_OFF + token))));
        } catch (Exception e) {
            log.debug("流日志开启通知发送失败: {}", e.getMessage());
        }
    }

    private static String displaySubnet(String subnetName, String subnetId) {
        if (subnetName != null && !subnetName.isBlank()) return subnetName;
        return subnetId == null || subnetId.length() < 12
                ? String.valueOf(subnetId) : "…" + subnetId.substring(subnetId.length() - 12);
    }

    private void createFlowLog(LoggingManagementClient logging, String logGroupId,
                               String subnetId, String subnetName) throws InterruptedException {
        String display = sanitizeLogName("flowlog-" + displaySubnet(subnetName, subnetId));
        logging.createLog(CreateLogRequest.builder()
                .logGroupId(logGroupId)
                .createLogDetails(CreateLogDetails.builder()
                        .displayName(display)
                        .logType(CreateLogDetails.LogType.Service)
                        .isEnabled(true)
                        .retentionDuration(LOG_RETENTION_DAYS)
                        .configuration(Configuration.builder()
                                .source(OciService.builder()
                                        .service("flowlogs")
                                        .resource(subnetId)
                                        .category("all")
                                        .build())
                                .build())
                        .build())
                .build());
        // 创建为异步；轮询直到出现 ACTIVE 记录（有限等待，超时不阻断开关结果）。
        for (int i = 0; i < 15; i++) {
            Thread.sleep(POLL_MILLIS);
            if (listActiveFlowLogsBySubnet(logging, logGroupId).containsKey(subnetId)) return;
        }
        log.info("流日志创建仍在进行中: subnet={}", subnetId);
    }

    private static String sanitizeLogName(String raw) {
        String cleaned = raw.replaceAll("[^A-Za-z0-9_.-]", "-");
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }

    private String ensureLogGroup(LoggingManagementClient logging, String compartmentId)
            throws InterruptedException {
        String existing = findLogGroupId(logging, compartmentId);
        if (existing != null) return existing;
        logging.createLogGroup(CreateLogGroupRequest.builder()
                .createLogGroupDetails(CreateLogGroupDetails.builder()
                        .compartmentId(compartmentId)
                        .displayName(LOG_GROUP_NAME)
                        .description("ociworker VCN flow logs")
                        .build())
                .build());
        for (int i = 0; i < 15; i++) {
            Thread.sleep(POLL_MILLIS);
            String created = findLogGroupId(logging, compartmentId);
            if (created != null) return created;
        }
        throw new OciException("日志组创建超时，请稍后重试");
    }

    private String findLogGroupId(LoggingManagementClient logging, String compartmentId) {
        var response = logging.listLogGroups(ListLogGroupsRequest.builder()
                .compartmentId(compartmentId)
                .displayName(LOG_GROUP_NAME)
                .build());
        List<LogGroupSummary> items = response.getItems() == null ? List.of() : response.getItems();
        for (LogGroupSummary item : items) {
            if (item != null && LOG_GROUP_NAME.equals(item.getDisplayName())) return item.getId();
        }
        return null;
    }

    /** 返回 subnetId → logId 的 ACTIVE flowlogs 映射。 */
    private Map<String, String> listActiveFlowLogsBySubnet(LoggingManagementClient logging,
                                                           String logGroupId) {
        Map<String, String> result = new LinkedHashMap<>();
        String page = null;
        do {
            var response = logging.listLogs(ListLogsRequest.builder()
                    .logGroupId(logGroupId)
                    .page(page)
                    .build());
            for (LogSummary item : response.getItems() == null
                    ? List.<LogSummary>of() : response.getItems()) {
                if (item == null || item.getLifecycleState() == null) continue;
                String state = item.getLifecycleState().getValue().toUpperCase(Locale.ROOT);
                if (!"ACTIVE".equals(state) && !"CREATING".equals(state)) continue;
                if (item.getConfiguration() == null || item.getConfiguration().getSource() == null) continue;
                if (!(item.getConfiguration().getSource() instanceof OciService source)) continue;
                if (!"flowlogs".equalsIgnoreCase(source.getService())) continue;
                if (source.getResource() != null) {
                    result.put(source.getResource(), item.getId());
                }
            }
            page = response.getOpcNextPage();
        } while (page != null);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flattenFlowRecord(SearchResult result, String selfIp) {
        if (result == null || result.getData() == null) return null;
        Object dataObj = result.getData();
        if (!(dataObj instanceof Map<?, ?> outer)) return null;
        Object logContent = outer.get("logContent");
        if (!(logContent instanceof Map<?, ?> content)) return null;
        Object inner = ((Map<String, Object>) content).get("data");
        if (!(inner instanceof Map<?, ?> flow)) return null;
        Map<String, Object> f = (Map<String, Object>) flow;
        String src = str(f.get("sourceAddress"));
        String dst = str(f.get("destinationAddress"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", str(((Map<String, Object>) content).get("time")));
        row.put("direction", selfIp.equals(src) ? "出站" : "入站");
        row.put("sourceAddress", src);
        row.put("sourcePort", f.get("sourcePort"));
        row.put("destinationAddress", dst);
        row.put("destinationPort", f.get("destinationPort"));
        row.put("protocol", protocolName(str(f.get("protocol"))));
        row.put("action", str(f.get("action")));
        row.put("bytes", f.get("bytesOut"));
        row.put("packets", f.get("packets"));
        return row;
    }

    private static String protocolName(String protocol) {
        return switch (protocol == null ? "" : protocol) {
            case "6" -> "TCP";
            case "17" -> "UDP";
            case "1" -> "ICMP";
            case "58" -> "ICMPv6";
            default -> protocol;
        };
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private OciUser requireUser(String userId) {
        if (userId == null || userId.isBlank()) throw new OciException("租户配置 ID 不能为空");
        OciUser user = userMapper.selectById(userId);
        if (user == null) throw new OciException("租户配置不存在");
        return user;
    }

    private LoggingManagementClient buildLoggingClient(OciClientService client, String region) {
        LoggingManagementClient.Builder builder = LoggingManagementClient.builder()
                .configuration(client.getClientConfiguration());
        if (client.getOciClientConfigurator() != null) {
            builder.additionalClientConfigurator(client.getOciClientConfigurator());
        }
        LoggingManagementClient logging = builder.build(client.getProvider());
        if (region != null && !region.isBlank()) logging.setRegion(region.trim());
        return logging;
    }

    private LogSearchClient buildSearchClient(OciClientService client, String region) {
        LogSearchClient.Builder builder = LogSearchClient.builder()
                .configuration(client.getClientConfiguration());
        if (client.getOciClientConfigurator() != null) {
            builder.additionalClientConfigurator(client.getOciClientConfigurator());
        }
        LogSearchClient search = builder.build(client.getProvider());
        if (region != null && !region.isBlank()) search.setRegion(region.trim());
        return search;
    }

    private static SysUserDTO toSysUser(OciUser user, String region) {
        String effectiveRegion = region == null || region.isBlank() ? user.getOciRegion() : region;
        return SysUserDTO.builder()
                .username(user.getUsername())
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(user.getOciTenantId())
                        .userId(user.getOciUserId())
                        .fingerprint(user.getOciFingerprint())
                        .region(effectiveRegion)
                        .privateKeyPath(user.getOciKeyPath())
                        .build())
                .build();
    }
}
