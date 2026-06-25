package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ociworker.enums.SysCfgEnum;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciAnnouncementPushBatchMapper;
import com.ociworker.mapper.OciAnnouncementRecordMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciAnnouncementPushBatch;
import com.ociworker.model.entity.OciAnnouncementRecord;
import com.ociworker.model.entity.OciUser;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AnnouncementPushService {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int SCAN_LIMIT = 50;
    private static final int SCAN_CONCURRENCY = 5;

    @Resource
    private NotificationService notificationService;
    @Resource
    private AnnouncementService announcementService;
    @Resource
    private OciUserMapper userMapper;
    @Resource
    private OciAnnouncementRecordMapper recordMapper;
    @Resource
    private OciAnnouncementPushBatchMapper batchMapper;

    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile LocalDateTime scanStartedAt;
    private volatile LocalDateTime lastScanAt;
    private volatile String lastScanStatus = "IDLE";
    private volatile String lastScanError = "";
    private volatile int lastSuccessTenants = 0;
    private volatile int lastFailedTenants = 0;

    public Map<String, Object> getConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", bool(SysCfgEnum.ANNOUNCEMENT_PUSH_ENABLED, false));
        m.put("mode", str(SysCfgEnum.ANNOUNCEMENT_PUSH_MODE, "IMPORTANT"));
        m.put("frequencyMinutes", intVal(SysCfgEnum.ANNOUNCEMENT_PUSH_FREQUENCY_MINUTES, 30, 15, 180));
        m.put("tenantScopeMode", str(SysCfgEnum.ANNOUNCEMENT_PUSH_TENANT_SCOPE_MODE, "ALL"));
        m.put("selectedTenantIds", csv(SysCfgEnum.ANNOUNCEMENT_PUSH_SELECTED_TENANT_IDS));
        m.put("excludedTenantIds", csv(SysCfgEnum.ANNOUNCEMENT_PUSH_EXCLUDED_TENANT_IDS));
        m.put("selectedGroups", jsonArray(SysCfgEnum.ANNOUNCEMENT_PUSH_SELECTED_GROUPS));
        m.put("recordRetentionDays", intVal(SysCfgEnum.ANNOUNCEMENT_PUSH_RECORD_RETENTION_DAYS, 90, 30, 180));
        m.put("batchRetentionDays", intVal(SysCfgEnum.ANNOUNCEMENT_PUSH_BATCH_RETENTION_DAYS, 30, 7, 60));
        m.put("baselineDone", bool(SysCfgEnum.ANNOUNCEMENT_PUSH_BASELINE_DONE, false));
        m.put("lastScanAt", str(SysCfgEnum.ANNOUNCEMENT_PUSH_LAST_SCAN_AT, ""));
        m.put("status", getStatus());
        return m;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> saveConfig(Map<String, Object> params) {
        if (params == null) params = Map.of();
        if (params.containsKey("enabled")) {
            boolean enabled = boolObj(params.get("enabled"));
            notificationService.saveKvValue(SysCfgEnum.ANNOUNCEMENT_PUSH_ENABLED, enabled ? "true" : "false");
            if (enabled) ensureAnnouncementNotifyTypeEnabled();
        }
        if (params.containsKey("mode")) {
            String mode = normalizeMode(String.valueOf(params.get("mode")));
            notificationService.saveKvValue(SysCfgEnum.ANNOUNCEMENT_PUSH_MODE, mode);
        }
        if (params.containsKey("frequencyMinutes")) {
            notificationService.saveKvValue(SysCfgEnum.ANNOUNCEMENT_PUSH_FREQUENCY_MINUTES,
                    String.valueOf(clampInt(params.get("frequencyMinutes"), 30, 15, 180)));
        }
        if (params.containsKey("tenantScopeMode")) {
            String mode = String.valueOf(params.get("tenantScopeMode"));
            if (!Set.of("ALL", "GROUPS", "CUSTOM").contains(mode)) mode = "ALL";
            notificationService.saveKvValue(SysCfgEnum.ANNOUNCEMENT_PUSH_TENANT_SCOPE_MODE, mode);
        }
        if (params.containsKey("selectedTenantIds")) {
            notificationService.saveKvValue(SysCfgEnum.ANNOUNCEMENT_PUSH_SELECTED_TENANT_IDS, joinIds(params.get("selectedTenantIds")));
        }
        if (params.containsKey("excludedTenantIds")) {
            notificationService.saveKvValue(SysCfgEnum.ANNOUNCEMENT_PUSH_EXCLUDED_TENANT_IDS, joinIds(params.get("excludedTenantIds")));
        }
        if (params.containsKey("selectedGroups")) {
            Object raw = params.get("selectedGroups");
            notificationService.saveKvValue(SysCfgEnum.ANNOUNCEMENT_PUSH_SELECTED_GROUPS,
                    raw == null ? "[]" : JSONUtil.toJsonStr(raw));
        }
        if (params.containsKey("recordRetentionDays")) {
            notificationService.saveKvValue(SysCfgEnum.ANNOUNCEMENT_PUSH_RECORD_RETENTION_DAYS,
                    String.valueOf(clampInt(params.get("recordRetentionDays"), 90, 30, 180)));
        }
        if (params.containsKey("batchRetentionDays")) {
            notificationService.saveKvValue(SysCfgEnum.ANNOUNCEMENT_PUSH_BATCH_RETENTION_DAYS,
                    String.valueOf(clampInt(params.get("batchRetentionDays"), 30, 7, 60)));
        }
        return getConfig();
    }

    public Map<String, Object> tenantTree() {
        List<OciUser> users = userMapper.selectList(new LambdaQueryWrapper<OciUser>()
                .orderByAsc(OciUser::getGroupLevel1)
                .orderByAsc(OciUser::getGroupLevel2)
                .orderByAsc(OciUser::getUsername));
        List<Map<String, Object>> items = users.stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("tenantName", u.getTenantName());
            m.put("region", u.getOciRegion());
            m.put("tenancyTail", tail(u.getOciTenantId()));
            m.put("groupLevel1", StrUtil.blankToDefault(u.getGroupLevel1(), "未分组"));
            m.put("groupLevel2", StrUtil.blankToDefault(u.getGroupLevel2(), ""));
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", items.size());
        return out;
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        int freq = intVal(SysCfgEnum.ANNOUNCEMENT_PUSH_FREQUENCY_MINUTES, 30, 15, 180);
        LocalDateTime persistedLast = parseLocalDateTime(notificationService.getKvValue(SysCfgEnum.ANNOUNCEMENT_PUSH_LAST_SCAN_AT));
        LocalDateTime effectiveLast = lastScanAt != null ? lastScanAt : persistedLast;
        m.put("scanning", scanning.get());
        m.put("status", lastScanStatus);
        m.put("scanStartedAt", scanStartedAt);
        m.put("lastScanAt", effectiveLast);
        m.put("nextScanAt", effectiveLast == null ? null : effectiveLast.plusMinutes(freq));
        m.put("successTenants", lastSuccessTenants);
        m.put("failedTenants", lastFailedTenants);
        m.put("lastError", lastScanError);
        return m;
    }

    public void triggerManualScan() {
        startScanAsync(true);
    }

    @Scheduled(cron = "17 * * * * ?")
    public void tick() {
        if (!bool(SysCfgEnum.ANNOUNCEMENT_PUSH_ENABLED, false)) return;
        if (scanning.get()) return;
        int freq = intVal(SysCfgEnum.ANNOUNCEMENT_PUSH_FREQUENCY_MINUTES, 30, 15, 180);
        LocalDateTime last = parseLocalDateTime(notificationService.getKvValue(SysCfgEnum.ANNOUNCEMENT_PUSH_LAST_SCAN_AT));
        if (last == null || last.plusMinutes(freq).isBefore(LocalDateTime.now())) {
            startScanAsync(false);
        }
    }

    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanup() {
        cleanupOldData();
    }

    private void startScanAsync(boolean manual) {
        if (!scanning.compareAndSet(false, true)) {
            throw new OciException("云公告扫描正在进行中");
        }
        CompletableFuture.runAsync(() -> {
            try {
                runScan(manual);
            } catch (Exception e) {
                lastScanStatus = "FAILED";
                lastScanError = shortMsg(e);
                log.warn("announcement scan failed: {}", e.getMessage());
            } finally {
                lastScanAt = LocalDateTime.now();
                notificationService.saveKvValue(SysCfgEnum.ANNOUNCEMENT_PUSH_LAST_SCAN_AT, lastScanAt.toString());
                scanning.set(false);
            }
        });
    }

    private void runScan(boolean manual) {
        scanStartedAt = LocalDateTime.now();
        lastScanStatus = "RUNNING";
        lastScanError = "";
        lastSuccessTenants = 0;
        lastFailedTenants = 0;

        List<OciUser> tenants = selectedTenants();
        if (tenants.isEmpty()) {
            lastScanStatus = "IDLE";
            lastScanError = "没有可扫描的租户";
            return;
        }

        boolean baselineDone = bool(SysCfgEnum.ANNOUNCEMENT_PUSH_BASELINE_DONE, false);
        List<String> errors = new ArrayList<>();
        Semaphore permits = new Semaphore(SCAN_CONCURRENCY);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = tenants.stream()
                    .map(user -> CompletableFuture.runAsync(() -> {
                        boolean acquired = false;
                        try {
                            permits.acquire();
                            acquired = true;
                            scanTenant(user, baselineDone);
                            synchronized (this) { lastSuccessTenants++; }
                        } catch (Exception e) {
                            synchronized (this) {
                                lastFailedTenants++;
                                if (errors.size() < 8) errors.add(displayTenantName(user) + ": " + shortMsg(e));
                            }
                        } finally {
                            if (acquired) permits.release();
                        }
                    }, executor))
                    .toList();
            futures.forEach(CompletableFuture::join);
        }

        if (!baselineDone) {
            notificationService.saveKvValue(SysCfgEnum.ANNOUNCEMENT_PUSH_BASELINE_DONE, "true");
            lastScanStatus = errors.isEmpty() ? "BASELINE" : "PARTIAL";
            lastScanError = errors.isEmpty() ? "" : String.join("; ", errors);
            cleanupOldData();
            return;
        }

        List<OciAnnouncementRecord> pendingRecords = recordMapper.selectList(new LambdaQueryWrapper<OciAnnouncementRecord>()
                .eq(OciAnnouncementRecord::getPushed, false)
                .eq(OciAnnouncementRecord::getIgnored, false)
                .orderByDesc(OciAnnouncementRecord::getTimeCreated)
                .last("LIMIT 500"));
        List<OciAnnouncementRecord> pushCandidates = pendingRecords.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getIgnored()))
                .filter(this::shouldPushByMode)
                .toList();
        if (!pushCandidates.isEmpty() && canSendAnnouncementPush()) {
            pushAggregated(pushCandidates);
        }

        lastScanStatus = errors.isEmpty() ? "SUCCESS" : "PARTIAL";
        lastScanError = errors.isEmpty() ? "" : String.join("; ", errors);
        cleanupOldData();
    }

    private void scanTenant(OciUser user, boolean baselineDone) {
        List<Map<String, Object>> rows = announcementService.listLatestActiveAnnouncements(user, SCAN_LIMIT);
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> row : rows) {
            String announcementId = strObj(row.get("id"));
            if (StrUtil.isBlank(announcementId)) continue;
            OciAnnouncementRecord existing = recordMapper.selectOne(new LambdaQueryWrapper<OciAnnouncementRecord>()
                    .eq(OciAnnouncementRecord::getTenantId, user.getId())
                    .eq(OciAnnouncementRecord::getAnnouncementId, announcementId)
                    .last("LIMIT 1"));
            if (existing != null) {
                existing.setLastSeenAt(now);
                existing.setTenantName(displayTenantName(user));
                existing.setSummary(strObj(row.get("summary")));
                existing.setAnnouncementType(strObj(row.get("announcementType")));
                existing.setServicesJson(JSONUtil.toJsonStr(row.get("services")));
                existing.setAffectedRegionsJson(JSONUtil.toJsonStr(row.get("affectedRegions")));
                recordMapper.updateById(existing);
                continue;
            }
            OciAnnouncementRecord rec = toRecord(user, row, now);
            if (!baselineDone) {
                rec.setPushed(true);
                rec.setPushedBatchId("BASELINE");
                rec.setPushedAt(now);
            }
            recordMapper.insert(rec);
        }
    }

    private OciAnnouncementRecord toRecord(OciUser user, Map<String, Object> row, LocalDateTime now) {
        OciAnnouncementRecord rec = new OciAnnouncementRecord();
        rec.setId(CommonUtils.generateId());
        rec.setTenantId(user.getId());
        rec.setTenantName(displayTenantName(user));
        rec.setAnnouncementId(strObj(row.get("id")));
        rec.setChainId(strObj(row.get("chainId")));
        rec.setSummary(strObj(row.get("summary")));
        rec.setAnnouncementType(strObj(row.get("announcementType")));
        rec.setServicesJson(JSONUtil.toJsonStr(row.get("services")));
        rec.setAffectedRegionsJson(JSONUtil.toJsonStr(row.get("affectedRegions")));
        rec.setTimeCreated(toLocalDateTime(row.get("timeCreated")));
        rec.setTimeUpdated(toLocalDateTime(row.get("timeUpdated")));
        rec.setTimeOneTitle(strObj(row.get("timeOneTitle")));
        rec.setTimeOneType(strObj(row.get("timeOneType")));
        rec.setTimeOneValue(toLocalDateTime(row.get("timeOneValue")));
        rec.setTimeTwoTitle(strObj(row.get("timeTwoTitle")));
        rec.setTimeTwoType(strObj(row.get("timeTwoType")));
        rec.setTimeTwoValue(toLocalDateTime(row.get("timeTwoValue")));
        rec.setAggregateKey(aggregateKey(rec));
        rec.setPushed(false);
        rec.setIgnored(false);
        rec.setReadFlag(false);
        rec.setFirstSeenAt(now);
        rec.setLastSeenAt(now);
        return rec;
    }

    private void pushAggregated(List<OciAnnouncementRecord> records) {
        Map<String, List<OciAnnouncementRecord>> groups = records.stream()
                .collect(Collectors.groupingBy(OciAnnouncementRecord::getAggregateKey, LinkedHashMap::new, Collectors.toList()));
        String batchId = "AN" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String message = buildTelegramMessage(batchId, groups);
        OciAnnouncementPushBatch batch = new OciAnnouncementPushBatch();
        batch.setId(CommonUtils.generateId());
        batch.setBatchId(batchId);
        batch.setPushedAt(LocalDateTime.now());
        batch.setAnnouncementCount(groups.size());
        batch.setTenantCount((int) records.stream().map(OciAnnouncementRecord::getTenantId).distinct().count());
        batch.setMessagePreview(message);
        batch.setStatus("SUCCESS");
        batch.setCreateTime(LocalDateTime.now());
        try {
            notificationService.sendHtmlWithType(NotificationService.TYPE_ANNOUNCEMENT, escapeHtml(message));
        } catch (Exception e) {
            batch.setStatus("FAILED");
            batch.setErrorMessage(shortMsg(e));
        }
        batchMapper.insert(batch);
        LocalDateTime pushedAt = LocalDateTime.now();
        for (OciAnnouncementRecord r : records) {
            r.setPushed(true);
            r.setPushedBatchId(batchId);
            r.setPushedAt(pushedAt);
            recordMapper.updateById(r);
        }
    }

    private String buildTelegramMessage(String batchId, Map<String, List<OciAnnouncementRecord>> groups) {
        int tenantCount = groups.values().stream()
                .flatMap(List::stream)
                .map(OciAnnouncementRecord::getTenantId)
                .collect(Collectors.toSet()).size();
        StringBuilder sb = new StringBuilder();
        sb.append("【OCI 云公告】发现 ").append(groups.size()).append(" 条新公告\n");
        sb.append("批次：").append(batchId).append("\n");
        sb.append("影响租户：").append(tenantCount).append(" 个\n\n");
        int i = 1;
        for (List<OciAnnouncementRecord> rs : groups.values()) {
            if (i > 5) {
                sb.append("还有 ").append(groups.size() - 5).append(" 条，请到面板查看详情\n");
                break;
            }
            OciAnnouncementRecord r = rs.get(0);
            sb.append(i++).append(". ").append(nullText(r.getAnnouncementType()));
            String services = joinJsonArray(r.getServicesJson(), 2);
            if (StrUtil.isNotBlank(services)) sb.append(" / ").append(services);
            sb.append("\n");
            sb.append(shortLine(r.getSummary(), 90)).append("\n");
            String timeLine = formatWindow(r);
            if (StrUtil.isNotBlank(timeLine)) sb.append(timeLine).append("\n");
            sb.append("影响租户：").append(joinTenantNames(rs, 3)).append("\n\n");
        }
        sb.append("请到面板查看详情");
        return sb.toString();
    }

    public Map<String, Object> inbox(int page, int size, String keyword) {
        List<OciAnnouncementRecord> rows = recordMapper.selectList(new LambdaQueryWrapper<OciAnnouncementRecord>()
                .like(StrUtil.isNotBlank(keyword), OciAnnouncementRecord::getSummary, keyword)
                .orderByDesc(OciAnnouncementRecord::getTimeCreated)
                .last("LIMIT 5000"));
        Map<String, List<OciAnnouncementRecord>> grouped = rows.stream()
                .collect(Collectors.groupingBy(OciAnnouncementRecord::getAggregateKey, LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> items = grouped.values().stream()
                .map(this::toInboxRow)
                .sorted((a, b) -> compareNullable((LocalDateTime) b.get("timeCreated"), (LocalDateTime) a.get("timeCreated")))
                .toList();
        return pageList(items, page, size);
    }

    public Map<String, Object> inboxDetail(String aggregateKey) {
        List<OciAnnouncementRecord> records = recordMapper.selectList(new LambdaQueryWrapper<OciAnnouncementRecord>()
                .eq(OciAnnouncementRecord::getAggregateKey, aggregateKey)
                .orderByAsc(OciAnnouncementRecord::getTenantName));
        Map<String, Object> out = records.isEmpty() ? new LinkedHashMap<>() : toInboxRow(records);
        out.put("tenants", records.stream().map(this::toTenantImpactRow).toList());
        if (!records.isEmpty()) {
            OciAnnouncementRecord first = records.get(0);
            try {
                out.put("liveDetail", announcementService.getAnnouncementDetail(first.getTenantId(), first.getAnnouncementId()));
            } catch (Exception e) {
                out.put("liveDetailError", shortMsg(e));
            }
        }
        return out;
    }

    public void markAggregate(String aggregateKey, String action) {
        List<OciAnnouncementRecord> records = recordMapper.selectList(new LambdaQueryWrapper<OciAnnouncementRecord>()
                .eq(OciAnnouncementRecord::getAggregateKey, aggregateKey));
        for (OciAnnouncementRecord r : records) {
            if ("read".equals(action)) r.setReadFlag(true);
            if ("ignore".equals(action)) r.setIgnored(true);
            if ("unignore".equals(action)) r.setIgnored(false);
            recordMapper.updateById(r);
        }
    }

    public Map<String, Object> batches(int page, int size) {
        Page<OciAnnouncementPushBatch> p = batchMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(size, 1), 100)),
                new LambdaQueryWrapper<OciAnnouncementPushBatch>().orderByDesc(OciAnnouncementPushBatch::getCreateTime));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", p.getRecords());
        out.put("total", p.getTotal());
        out.put("current", p.getCurrent());
        out.put("size", p.getSize());
        return out;
    }

    private Map<String, Object> toInboxRow(List<OciAnnouncementRecord> records) {
        OciAnnouncementRecord r = records.get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("aggregateKey", r.getAggregateKey());
        m.put("announcementId", r.getAnnouncementId());
        m.put("tenantId", r.getTenantId());
        m.put("summary", r.getSummary());
        m.put("announcementType", r.getAnnouncementType());
        m.put("services", parseJsonArray(r.getServicesJson()));
        m.put("affectedRegions", parseJsonArray(r.getAffectedRegionsJson()));
        m.put("timeCreated", r.getTimeCreated());
        m.put("timeUpdated", r.getTimeUpdated());
        m.put("timeOneTitle", r.getTimeOneTitle());
        m.put("timeOneValue", r.getTimeOneValue());
        m.put("timeTwoTitle", r.getTimeTwoTitle());
        m.put("timeTwoValue", r.getTimeTwoValue());
        m.put("timeWindowText", formatWindow(r));
        m.put("tenantCount", records.stream().map(OciAnnouncementRecord::getTenantId).distinct().count());
        m.put("read", records.stream().allMatch(x -> Boolean.TRUE.equals(x.getReadFlag())));
        m.put("ignored", records.stream().allMatch(x -> Boolean.TRUE.equals(x.getIgnored())));
        m.put("pushed", records.stream().anyMatch(x -> Boolean.TRUE.equals(x.getPushed())));
        m.put("pushedBatchId", records.stream().map(OciAnnouncementRecord::getPushedBatchId).filter(StrUtil::isNotBlank).findFirst().orElse(""));
        m.put("tenantPreview", joinTenantNames(records, 5));
        return m;
    }

    private Map<String, Object> toTenantImpactRow(OciAnnouncementRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", r.getTenantId());
        m.put("tenantName", r.getTenantName());
        m.put("announcementId", r.getAnnouncementId());
        m.put("read", Boolean.TRUE.equals(r.getReadFlag()));
        m.put("ignored", Boolean.TRUE.equals(r.getIgnored()));
        return m;
    }

    private List<OciUser> selectedTenants() {
        List<OciUser> all = userMapper.selectList(new LambdaQueryWrapper<OciUser>().orderByAsc(OciUser::getUsername));
        String mode = str(SysCfgEnum.ANNOUNCEMENT_PUSH_TENANT_SCOPE_MODE, "ALL");
        Set<String> selectedIds = new LinkedHashSet<>(csv(SysCfgEnum.ANNOUNCEMENT_PUSH_SELECTED_TENANT_IDS));
        Set<String> excludedIds = new LinkedHashSet<>(csv(SysCfgEnum.ANNOUNCEMENT_PUSH_EXCLUDED_TENANT_IDS));
        JSONArray groups = jsonArray(SysCfgEnum.ANNOUNCEMENT_PUSH_SELECTED_GROUPS);
        return all.stream()
                .filter(u -> switch (mode) {
                    case "CUSTOM" -> selectedIds.contains(u.getId());
                    case "GROUPS" -> groupMatches(groups, u);
                    default -> true;
                })
                .filter(u -> !excludedIds.contains(u.getId()))
                .toList();
    }

    private static boolean groupMatches(JSONArray groups, OciUser user) {
        if (groups == null || groups.isEmpty()) return false;
        String l1 = StrUtil.blankToDefault(user.getGroupLevel1(), "未分组");
        String l2 = StrUtil.blankToDefault(user.getGroupLevel2(), "");
        for (Object obj : groups) {
            if (!(obj instanceof Map<?, ?> m)) continue;
            String level = strObj(m.get("level"));
            String g1 = StrUtil.blankToDefault(strObj(m.get("groupLevel1")), "未分组");
            String g2 = StrUtil.blankToDefault(strObj(m.get("groupLevel2")), "");
            if ("1".equals(level) && Objects.equals(l1, g1)) return true;
            if ("2".equals(level) && Objects.equals(l1, g1) && Objects.equals(l2, g2)) return true;
        }
        return false;
    }

    private boolean shouldPushByMode(OciAnnouncementRecord r) {
        String mode = str(SysCfgEnum.ANNOUNCEMENT_PUSH_MODE, "IMPORTANT");
        return !"IMPORTANT".equals(mode) || isImportant(r.getAnnouncementType());
    }

    private boolean canSendAnnouncementPush() {
        return notificationService.isNotifyTypeEnabled(NotificationService.TYPE_ANNOUNCEMENT)
                && StrUtil.isNotBlank(notificationService.getKvValue(SysCfgEnum.TG_BOT_TOKEN))
                && StrUtil.isNotBlank(notificationService.getKvValue(SysCfgEnum.TG_CHAT_ID));
    }

    private void ensureAnnouncementNotifyTypeEnabled() {
        String raw = notificationService.getKvValue(SysCfgEnum.TG_NOTIFY_TYPES);
        if (StrUtil.isBlank(raw)) return;
        Set<String> types = java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (types.add(NotificationService.TYPE_ANNOUNCEMENT)) {
            notificationService.saveKvValue(SysCfgEnum.TG_NOTIFY_TYPES, String.join(",", types));
        }
    }

    private static boolean isImportant(String type) {
        String t = StrUtil.nullToEmpty(type).toLowerCase(Locale.ROOT);
        return t.contains("action") || t.contains("required") || t.contains("emergency") || t.contains("planned") || t.contains("maintenance");
    }

    private void cleanupOldData() {
        int recordDays = intVal(SysCfgEnum.ANNOUNCEMENT_PUSH_RECORD_RETENTION_DAYS, 90, 30, 180);
        int batchDays = intVal(SysCfgEnum.ANNOUNCEMENT_PUSH_BATCH_RETENTION_DAYS, 30, 7, 60);
        recordMapper.delete(new LambdaQueryWrapper<OciAnnouncementRecord>()
                .lt(OciAnnouncementRecord::getFirstSeenAt, LocalDateTime.now().minusDays(recordDays)));
        batchMapper.delete(new LambdaQueryWrapper<OciAnnouncementPushBatch>()
                .lt(OciAnnouncementPushBatch::getCreateTime, LocalDateTime.now().minusDays(batchDays)));
    }

    private static Map<String, Object> pageList(List<Map<String, Object>> items, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int from = Math.min((safePage - 1) * safeSize, items.size());
        int to = Math.min(from + safeSize, items.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", items.subList(from, to));
        out.put("total", items.size());
        out.put("current", safePage);
        out.put("size", safeSize);
        return out;
    }

    private String aggregateKey(OciAnnouncementRecord r) {
        if (StrUtil.isNotBlank(r.getChainId())) return "chain:" + r.getChainId();
        String raw = StrUtil.nullToEmpty(r.getSummary()).trim() + "|" + Objects.toString(r.getTimeCreated(), "");
        return "summary:" + DigestUtil.md5Hex(raw);
    }

    private String formatWindow(OciAnnouncementRecord r) {
        if (r.getTimeOneValue() == null && r.getTimeTwoValue() == null) return "";
        StringBuilder sb = new StringBuilder();
        if (r.getTimeOneValue() != null && r.getTimeTwoValue() != null) {
            sb.append("UTC：").append(fmtUtc(r.getTimeOneValue())).append(" - ").append(DISPLAY_FMT.format(r.getTimeTwoValue())).append("\n");
            sb.append("中国时间：").append(fmtCn(r.getTimeOneValue())).append(" - ").append(DISPLAY_FMT.format(r.getTimeTwoValue().atZone(UTC).withZoneSameInstant(CN_ZONE)));
        } else {
            LocalDateTime t = r.getTimeOneValue() != null ? r.getTimeOneValue() : r.getTimeTwoValue();
            sb.append("UTC：").append(fmtUtc(t)).append("\n");
            sb.append("中国时间：").append(fmtCn(t));
        }
        return sb.toString();
    }

    private static String fmtUtc(LocalDateTime t) {
        return t == null ? "" : DISPLAY_FMT.format(t);
    }

    private static String fmtCn(LocalDateTime t) {
        return t == null ? "" : DISPLAY_FMT.format(t.atZone(UTC).withZoneSameInstant(CN_ZONE));
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime ldt) return ldt;
        if (value instanceof Date d) return LocalDateTime.ofInstant(d.toInstant(), UTC);
        if (value instanceof Instant i) return LocalDateTime.ofInstant(i, UTC);
        if (value == null) return null;
        try {
            return LocalDateTime.ofInstant(Instant.parse(String.valueOf(value)), UTC);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static LocalDateTime parseLocalDateTime(String value) {
        if (StrUtil.isBlank(value)) return null;
        try {
            return LocalDateTime.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int compareNullable(LocalDateTime a, LocalDateTime b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    private String str(SysCfgEnum key, String def) {
        String v = notificationService.getKvValue(key);
        return StrUtil.isBlank(v) ? def : v.trim();
    }

    private boolean bool(SysCfgEnum key, boolean def) {
        String v = notificationService.getKvValue(key);
        return StrUtil.isBlank(v) ? def : Boolean.parseBoolean(v.trim());
    }

    private int intVal(SysCfgEnum key, int def, int min, int max) {
        return clampInt(notificationService.getKvValue(key), def, min, max);
    }

    private static int clampInt(Object raw, int def, int min, int max) {
        int v = def;
        try {
            if (raw instanceof Number n) v = n.intValue();
            else if (raw != null) v = Integer.parseInt(String.valueOf(raw));
        } catch (Exception ignored) {}
        return Math.max(min, Math.min(max, v));
    }

    private List<String> csv(SysCfgEnum key) {
        String v = notificationService.getKvValue(key);
        if (StrUtil.isBlank(v)) return List.of();
        return java.util.Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private JSONArray jsonArray(SysCfgEnum key) {
        String v = notificationService.getKvValue(key);
        if (StrUtil.isBlank(v)) return new JSONArray();
        try {
            return JSONUtil.parseArray(v);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static String joinIds(Object raw) {
        if (raw instanceof Iterable<?> it) {
            List<String> ids = new ArrayList<>();
            for (Object o : it) {
                if (o != null && StrUtil.isNotBlank(String.valueOf(o))) ids.add(String.valueOf(o).trim());
            }
            return String.join(",", ids);
        }
        return raw == null ? "" : String.valueOf(raw);
    }

    private static boolean boolObj(Object value) {
        if (value instanceof Boolean b) return b;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static String normalizeMode(String raw) {
        return "ALL".equals(raw) ? "ALL" : "IMPORTANT";
    }

    private static String displayTenantName(OciUser user) {
        if (user == null) return "-";
        if (StrUtil.isNotBlank(user.getTenantName())) return user.getTenantName();
        if (StrUtil.isNotBlank(user.getUsername())) return user.getUsername();
        return user.getId();
    }

    private static String joinTenantNames(List<OciAnnouncementRecord> records, int limit) {
        List<String> names = records.stream()
                .map(OciAnnouncementRecord::getTenantName)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .limit(limit)
                .toList();
        long total = records.stream().map(OciAnnouncementRecord::getTenantId).distinct().count();
        String joined = String.join("、", names);
        if (total > limit) joined += " 等 " + total + " 个";
        return joined;
    }

    private static String joinJsonArray(String json, int limit) {
        List<String> arr = parseJsonArray(json);
        return arr.stream().limit(limit).collect(Collectors.joining("、"));
    }

    private static List<String> parseJsonArray(String json) {
        if (StrUtil.isBlank(json)) return List.of();
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            List<String> out = new ArrayList<>();
            for (Object o : arr) {
                if (o != null && StrUtil.isNotBlank(String.valueOf(o))) out.add(String.valueOf(o));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String nullText(String s) {
        return StrUtil.blankToDefault(s, "公告");
    }

    private static String shortLine(String s, int max) {
        if (s == null) return "-";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "...";
    }

    private static String shortMsg(Throwable e) {
        String msg = e == null ? "未知错误" : e.getMessage();
        if (StrUtil.isBlank(msg) && e != null && e.getCause() != null) msg = e.getCause().getMessage();
        msg = StrUtil.blankToDefault(msg, e == null ? "未知错误" : e.getClass().getSimpleName());
        msg = msg.replace('\n', ' ').replace('\r', ' ').trim();
        return msg.length() > 160 ? msg.substring(0, 160) : msg;
    }

    private static String strObj(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String tail(String value) {
        if (StrUtil.isBlank(value)) return "";
        String v = value.trim();
        return v.length() <= 8 ? v : v.substring(v.length() - 8);
    }

    private static String escapeHtml(String text) {
        return StrUtil.nullToEmpty(text)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
