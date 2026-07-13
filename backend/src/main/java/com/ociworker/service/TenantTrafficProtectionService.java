package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.mapper.TenantTrafficInstanceUsageMapper;
import com.ociworker.mapper.TenantTrafficActionLogMapper;
import com.ociworker.mapper.TenantTrafficProtectionMapper;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.entity.TenantTrafficInstanceUsage;
import com.ociworker.model.entity.TenantTrafficActionLog;
import com.ociworker.model.entity.TenantTrafficProtection;
import com.ociworker.util.OciBmcErrorTranslator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class TenantTrafficProtectionService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long DEFAULT_LIMIT = 10L * 1024 * 1024 * 1024 * 1024;
    private static final Duration COLLECTION_LEASE = Duration.ofMinutes(15);
    private static final Set<String> ACTIONS = Set.of("ALERT_ONLY", "STOP_ALL_RUNNING_INSTANCES");
    private static final Set<String> RUNNING = Set.of("RUNNING");
    private static final String ACCESS_SCOPE = "trafficProtectionManage";
    private final Set<String> collecting = ConcurrentHashMap.newKeySet();

    @Resource private TenantTrafficProtectionMapper protectionMapper;
    @Resource private TenantTrafficInstanceUsageMapper usageMapper;
    @Resource private TenantTrafficActionLogMapper actionLogMapper;
    @Resource private OciUserMapper userMapper;
    @Resource private TrafficService trafficService;
    @Resource private InstanceService instanceService;
    @Resource private RegionManagementService regionManagementService;
    @Resource private VerifyCodeService verifyCodeService;
    @Resource private TenantProtectionAccessService protectionAccessService;
    @Resource private NotificationService notificationService;

    public String unlock(String tenantConfigId, String verifyCode) {
        requireUser(tenantConfigId);
        verifyCodeService.verifyCode(ACCESS_SCOPE, verifyCode, tenantConfigId);
        return protectionAccessService.issue(tenantConfigId, ACCESS_SCOPE);
    }

    public Map<String, Object> overview(String tenantConfigId) {
        TenantTrafficProtection config = ensureConfig(tenantConfigId);
        resetMonthIfNeeded(config);
        List<TenantTrafficInstanceUsage> rows = usageMapper.selectList(new LambdaQueryWrapper<TenantTrafficInstanceUsage>()
                .eq(TenantTrafficInstanceUsage::getTenantConfigId, tenantConfigId)
                .eq(TenantTrafficInstanceUsage::getMonthKey, monthKey())
                .orderByDesc(TenantTrafficInstanceUsage::getBytesToNetwork));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config", config);
        out.put("usagePercent", percent(config));
        out.put("instances", rows);
        out.put("actions", actionLogMapper.selectList(new LambdaQueryWrapper<TenantTrafficActionLog>()
                .eq(TenantTrafficActionLog::getTenantConfigId, tenantConfigId)
                .orderByDesc(TenantTrafficActionLog::getCreateTime)
                .last("LIMIT 10")));
        return out;
    }

    public Map<String, Object> save(String tenantConfigId, Map<String, Object> input) {
        requireUser(tenantConfigId);
        long limitTb = number(input.get("monthlyLimitTb"), 1, 100, "月度保护额度");
        int warning = (int) number(input.get("warningPercent"), 50, 95, "预警阈值");
        String action = required(input, "exceedAction").toUpperCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) throw new OciException("不支持的超限动作");
        String accessToken = required(input, "accessToken");
        protectionAccessService.claim(accessToken, tenantConfigId, ACCESS_SCOPE);
        TenantTrafficProtection config;
        Map<String, Object> result;
        try {
            config = ensureConfig(tenantConfigId);
            config.setMonthlyLimitBytes(limitTb * 1024L * 1024 * 1024 * 1024);
            config.setWarningPercent(warning);
            config.setExceedAction(action);
            if (input.containsKey("enabled")) {
                boolean enabled = Boolean.parseBoolean(String.valueOf(input.get("enabled")));
                boolean changed = !Objects.equals(config.getEnabled(), enabled);
                config.setEnabled(enabled);
                if (changed) {
                    config.setNextCollectTime(enabled ? new Date() : null);
                    config.setLastError(null);
                    if (enabled) config.setLastWarningLevel(0);
                }
            }
            config.setUpdateTime(new Date());
            protectionMapper.updateById(config);
            result = overview(tenantConfigId);
        } catch (Exception e) {
            protectionAccessService.release(accessToken, tenantConfigId, ACCESS_SCOPE);
            log.error("保存流量保护失败: tenantConfigId={}, error={}",
                    tenantConfigId, OciBmcErrorTranslator.translate(e), e);
            throw e;
        }
        protectionAccessService.complete(accessToken, tenantConfigId, ACCESS_SCOPE);
        if (Boolean.TRUE.equals(config.getEnabled())) collectAsync(tenantConfigId, false, false);
        return result;
    }

    public Map<String, Object> setEnabled(String tenantConfigId, boolean enabled) {
        TenantTrafficProtection config = ensureConfig(tenantConfigId);
        config.setEnabled(enabled);
        config.setNextCollectTime(enabled ? new Date() : null);
        if (enabled) config.setLastWarningLevel(0);
        config.setLastError(null);
        config.setUpdateTime(new Date());
        protectionMapper.updateById(config);
        if (enabled) collectAsync(tenantConfigId, false, false);
        return overview(tenantConfigId);
    }

    public Map<String, Object> refresh(String tenantConfigId) {
        ensureConfig(tenantConfigId);
        collect(tenantConfigId, false, true, false);
        return overview(tenantConfigId);
    }

    @Scheduled(fixedDelay = 60_000)
    public void scheduleDueCollections() {
        Date now = new Date();
        List<TenantTrafficProtection> due = protectionMapper.selectList(new LambdaQueryWrapper<TenantTrafficProtection>()
                .eq(TenantTrafficProtection::getEnabled, true)
                .and(q -> q.isNull(TenantTrafficProtection::getNextCollectTime)
                        .or().le(TenantTrafficProtection::getNextCollectTime, now)));
        for (TenantTrafficProtection config : due) collectAsync(config.getTenantConfigId(), true, false);
    }

    private void collectAsync(String tenantConfigId, boolean allowStop, boolean recheck) {
        Thread.ofVirtual().name("traffic-protection-" + tenantConfigId)
                .start(() -> collect(tenantConfigId, true, allowStop, recheck));
    }

    private void collect(String tenantConfigId, boolean scheduled, boolean allowStop, boolean recheck) {
        if (!collecting.add(tenantConfigId)) return;
        String lockOwner = UUID.randomUUID().toString();
        boolean collectionClaimed = false;
        boolean triggerRecheck = false;
        try {
            TenantTrafficProtection config = ensureConfig(tenantConfigId);
            if (scheduled && !Boolean.TRUE.equals(config.getEnabled())) return;
            Date claimTime = new Date();
            if (protectionMapper.claimCollection(tenantConfigId, lockOwner, claimTime,
                    Date.from(claimTime.toInstant().plus(COLLECTION_LEASE))) != 1) return;
            collectionClaimed = true;
            resetMonthIfNeeded(config);
            OciUser user = requireUser(tenantConfigId);
            RegionResolution regionResolution = resolveSubscribedRegions(tenantConfigId, user);
            List<RegionalInstance> instances = new ArrayList<>();
            int regionFailures = 0;
            for (String region : regionResolution.regions()) {
                try {
                    extendCollectionLease(tenantConfigId, lockOwner);
                    List<Map<String, Object>> regionInstances = instanceService.listInstances(tenantConfigId, region, true);
                    if (regionInstances != null) {
                        for (Map<String, Object> instance : regionInstances) {
                            instances.add(new RegionalInstance(instance, region));
                        }
                    }
                } catch (Exception e) {
                    regionFailures++;
                    log.warn("读取区域实例失败: tenantConfigId={}, region={}", tenantConfigId, region, e);
                }
            }
            ZonedDateTime now = ZonedDateTime.now(ZONE);
            String start = now.withDayOfMonth(1).toLocalDate().atStartOfDay(ZONE).toInstant().toString();
            String end = now.toInstant().toString();
            int instanceFailures = 0;
            for (RegionalInstance regionalInstance : instances) {
                Map<String, Object> instance = regionalInstance.instance();
                String region = regionalInstance.region();
                String id = string(instance.get("instanceId"));
                if (id == null) continue;
                try {
                    extendCollectionLease(tenantConfigId, lockOwner);
                    Map<String, Object> traffic = trafficService.getTrafficData(tenantConfigId, id, 43200, region, start, end);
                    if (!usableTrafficMeasurement(traffic)) {
                        throw new OciException("Monitoring 未返回可用的 VNIC 指标点");
                    }
                    long outbound = ((Number) traffic.getOrDefault("outbound", 0L)).longValue();
                    upsertUsage(tenantConfigId, instance, region, outbound);
                } catch (Exception e) {
                    instanceFailures++;
                    log.warn("采集实例流量失败: tenantConfigId={}, instanceId={}", tenantConfigId, id, e);
                }
            }
            boolean complete = regionResolution.complete() && regionFailures == 0 && instanceFailures == 0;
            if (!complete) {
                List<String> errors = new ArrayList<>();
                if (!regionResolution.complete()) errors.add("未能确认全部订阅区域");
                if (regionFailures > 0) errors.add(regionFailures + " 个区域读取失败");
                if (instanceFailures > 0) errors.add(instanceFailures + " 台实例流量读取失败");
                config.setLastError(String.join("；", errors) + "，本次不执行自动停机");
            } else {
                config.setLastError(null);
            }
            long total = 0;
            List<TenantTrafficInstanceUsage> monthlyRows = usageMapper.selectList(
                    new LambdaQueryWrapper<TenantTrafficInstanceUsage>()
                            .eq(TenantTrafficInstanceUsage::getTenantConfigId, tenantConfigId)
                            .eq(TenantTrafficInstanceUsage::getMonthKey, monthKey()));
            for (TenantTrafficInstanceUsage row : monthlyRows) {
                total = Math.addExact(total, Math.max(0L, Objects.requireNonNullElse(row.getBytesToNetwork(), 0L)));
            }
            config.setMonthlyBytes(total);
            config.setLastCollectTime(new Date());
            config.setNextCollectTime(Date.from(Instant.now().plusSeconds(nextMinutes(config) * 60L)));
            config.setUpdateTime(new Date());
            protectionMapper.updateById(config);
            triggerRecheck = evaluate(config, complete, regionResolution.regions(), allowStop, recheck);
        } catch (Exception e) {
            log.error("租户流量保护采集失败: tenantConfigId={}", tenantConfigId, e);
            TenantTrafficProtection config = protectionMapper.selectById(tenantConfigId);
            if (config != null) {
                config.setLastError(OciBmcErrorTranslator.translate(e));
                config.setNextCollectTime(Date.from(Instant.now().plusSeconds(15 * 60L)));
                config.setUpdateTime(new Date());
                protectionMapper.updateById(config);
            }
        } finally {
            if (collectionClaimed) protectionMapper.releaseCollection(tenantConfigId, lockOwner);
            collecting.remove(tenantConfigId);
            if (triggerRecheck) collectAsync(tenantConfigId, true, true);
        }
    }

    private boolean evaluate(TenantTrafficProtection config, boolean complete, List<String> regions,
                             boolean allowStop, boolean recheck) {
        if (!Boolean.TRUE.equals(config.getEnabled())) return false;
        double pct = percent(config);
        int level = pct >= 100 ? 100 : pct >= config.getWarningPercent() ? config.getWarningPercent() : 0;
        if (level > config.getLastWarningLevel()) {
            notificationService.sendMessage("【OCIWorker 流量保护】\n租户：" + config.getTenantConfigId()
                    + "\n本月估算出站流量：" + human(config.getMonthlyBytes())
                    + " / " + human(config.getMonthlyLimitBytes()) + "（" + String.format(Locale.ROOT, "%.1f", pct) + "%）");
            config.setLastWarningLevel(level);
            protectionMapper.updateById(config);
        }
        if (pct < 100 || !complete || !allowStop || Boolean.TRUE.equals(config.getStopExecuted())
                || !"STOP_ALL_RUNNING_INSTANCES".equals(config.getExceedAction())) return false;
        if (!recheck) return true;

        List<RegionalInstance> freshInstances = new ArrayList<>();
        for (String region : regions) {
            try {
                List<Map<String, Object>> rows = instanceService.listInstances(config.getTenantConfigId(), region, true);
                if (rows != null) for (Map<String, Object> row : rows) freshInstances.add(new RegionalInstance(row, region));
            } catch (Exception e) {
                log.error("停机前重新读取实例失败，取消本次自动停机: tenantConfigId={}, region={}", config.getTenantConfigId(), region, e);
                config.setLastError("停机前重新读取实例失败，本次未执行自动停机");
                protectionMapper.updateById(config);
                return false;
            }
        }
        Date claimedAt = new Date();
        if (protectionMapper.claimStopExecution(config.getTenantConfigId(), claimedAt) != 1) return false;
        // 独立复核已覆盖本月和全部订阅区域；停机前再次刷新实例，数据库原子认领确保多进程只执行一次。
        int success = 0, failed = 0;
        List<String> affectedIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (RegionalInstance regionalInstance : freshInstances) {
            Map<String, Object> instance = regionalInstance.instance();
            if (!RUNNING.contains(String.valueOf(instance.get("state")).toUpperCase(Locale.ROOT))) continue;
            try {
                String instanceId = string(instance.get("instanceId"));
                affectedIds.add(instanceId);
                instanceService.updateInstanceState(config.getTenantConfigId(), instanceId, "STOP", regionalInstance.region());
                success++;
            } catch (Exception e) {
                failed++;
                errors.add(string(instance.get("instanceId")) + "：" + safeMessage(e));
                log.error("流量超限停止实例失败: {}", instance.get("instanceId"), e);
            }
        }
        TenantTrafficActionLog actionLog = new TenantTrafficActionLog();
        actionLog.setId(UUID.randomUUID().toString());
        actionLog.setTenantConfigId(config.getTenantConfigId());
        actionLog.setMonthKey(monthKey());
        actionLog.setAction("STOP_ALL_RUNNING_INSTANCES");
        actionLog.setEstimatedBytes(config.getMonthlyBytes());
        actionLog.setAffectedInstanceIds(String.join(",", affectedIds));
        actionLog.setSuccessCount(success);
        actionLog.setFailureCount(failed);
        actionLog.setErrorSummary(errors.isEmpty() ? null : truncate(String.join("；", errors), 1024));
        actionLog.setCreateTime(new Date());
        actionLogMapper.insert(actionLog);
        notificationService.sendMessage("【OCIWorker 流量保护已执行】\n租户：" + config.getTenantConfigId()
                + "\n停止成功：" + success + " 台\n停止失败：" + failed + " 台");
        return false;
    }

    private void upsertUsage(String tenantConfigId, Map<String, Object> instance, String region, long bytes) {
        String instanceId = string(instance.get("instanceId"));
        TenantTrafficInstanceUsage row = new TenantTrafficInstanceUsage();
        row.setId(UUID.randomUUID().toString());
        row.setTenantConfigId(tenantConfigId); row.setMonthKey(monthKey()); row.setInstanceId(instanceId);
        row.setInstanceName(string(instance.get("name"))); row.setRegion(region);
        row.setLifecycleState(string(instance.get("state")));
        row.setBytesToNetwork(Math.max(0L, bytes));
        row.setLastSeenTime(new Date());
        usageMapper.upsertMonthlyUsage(row);
    }

    private void extendCollectionLease(String tenantConfigId, String lockOwner) {
        protectionMapper.extendCollection(tenantConfigId, lockOwner, Date.from(Instant.now().plus(COLLECTION_LEASE)));
    }

    private boolean usableTrafficMeasurement(Map<String, Object> traffic) {
        if (traffic == null || numberValue(traffic.get("vnicCount")) <= 0) return false;
        Object points = traffic.get("points");
        return points instanceof Collection<?> collection && !collection.isEmpty();
    }

    private long numberValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private RegionResolution resolveSubscribedRegions(String tenantConfigId, OciUser user) {
        LinkedHashSet<String> regions = new LinkedHashSet<>();
        boolean complete = true;
        try {
            Map<String, Object> result = regionManagementService.listRegions(tenantConfigId);
            Object rawItems = result.get("items");
            if (rawItems instanceof Collection<?> items) {
                for (Object raw : items) {
                    if (!(raw instanceof Map<?, ?> item) || !Boolean.TRUE.equals(item.get("subscribed"))) continue;
                    String regionName = string(item.get("regionName"));
                    if (regionName != null && !regionName.isBlank()) regions.add(regionName);
                }
            }
            if (regions.isEmpty()) complete = false;
        } catch (Exception e) {
            complete = false;
            log.warn("读取租户订阅区域失败，使用配置区域进行降级采集: tenantConfigId={}", tenantConfigId, e);
        }
        if (regions.isEmpty() && user.getOciRegion() != null && !user.getOciRegion().isBlank()) {
            regions.add(user.getOciRegion().trim());
        }
        if (regions.isEmpty()) throw new OciException("没有可用于采集流量的区域");
        return new RegionResolution(List.copyOf(regions), complete);
    }

    private record RegionalInstance(Map<String, Object> instance, String region) {}
    private record RegionResolution(List<String> regions, boolean complete) {}

    private TenantTrafficProtection ensureConfig(String id) {
        requireUser(id);
        TenantTrafficProtection config = protectionMapper.selectById(id);
        if (config != null) return config;
        config = new TenantTrafficProtection(); config.setTenantConfigId(id); config.setEnabled(false);
        config.setMonthlyLimitBytes(DEFAULT_LIMIT); config.setWarningPercent(80); config.setExceedAction("ALERT_ONLY");
        config.setMonthKey(monthKey()); config.setMonthlyBytes(0L); config.setLastWarningLevel(0); config.setStopExecuted(false);
        config.setCreateTime(new Date()); config.setUpdateTime(new Date()); protectionMapper.insert(config); return config;
    }
    private void resetMonthIfNeeded(TenantTrafficProtection c) { if (monthKey().equals(c.getMonthKey())) return; c.setMonthKey(monthKey());c.setMonthlyBytes(0L);c.setLastWarningLevel(0);c.setStopExecuted(false);c.setStopExecutedTime(null);c.setLastError(null);protectionMapper.updateById(c); }
    private int nextMinutes(TenantTrafficProtection c) { double p=percent(c); return p>=90?5:p>=80?15:30; }
    private double percent(TenantTrafficProtection c) { return c.getMonthlyLimitBytes()==null||c.getMonthlyLimitBytes()<=0?0:(c.getMonthlyBytes()==null?0:c.getMonthlyBytes())*100.0/c.getMonthlyLimitBytes(); }
    private String monthKey(){return YearMonth.now(ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM"));}
    private OciUser requireUser(String id){OciUser u=userMapper.selectById(id);if(u==null)throw new OciException("租户配置不存在");return u;}
    private String required(Map<String,Object> m,String k){String v=m==null?null:string(m.get(k));if(v==null||v.isBlank())throw new OciException(k+"不能为空");return v;}
    private long number(Object v,long min,long max,String label){try{long n=Long.parseLong(String.valueOf(v));if(n<min||n>max)throw new Exception();return n;}catch(Exception e){throw new OciException(label+"范围必须为 "+min+"～"+max);}}
    private String string(Object v){return v==null?null:String.valueOf(v).trim();}
    private String human(Long b){double n=b==null?0:b;String[]u={"B","KB","MB","GB","TB"};int i=0;while(n>=1024&&i<u.length-1){n/=1024;i++;}return String.format(Locale.ROOT,"%.2f %s",n,u[i]);}
    private String safeMessage(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private String truncate(String value,int max){return value==null||value.length()<=max?value:value.substring(0,max);}
}
