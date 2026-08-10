package com.ociworker.service;

import com.oracle.bmc.core.model.CapacityReportInstanceShapeConfig;
import com.oracle.bmc.core.model.CapacityReportShapeAvailability;
import com.oracle.bmc.core.model.ComputeCapacityReport;
import com.oracle.bmc.core.model.CreateCapacityReportShapeAvailabilityDetails;
import com.oracle.bmc.core.model.CreateComputeCapacityReportDetails;
import com.oracle.bmc.core.requests.CreateComputeCapacityReportRequest;
import com.oracle.bmc.identity.model.AvailabilityDomain;
import com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest;
import com.ociworker.model.dto.SysUserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A1/AMD 容量探测（CreateComputeCapacityReport，免费）。三档：
 * OFF 不探测；REFERENCE 仅展示；THROTTLE 无货时降频（每 {@link #THROTTLE_REAL_ATTEMPT_EVERY}
 * 轮仍真实尝试一次，防止报告不准错过放货）。探测失败一律降级为不干预。
 */
@Slf4j
@Service
public class CapacityProbeService {

    static final int THROTTLE_REAL_ATTEMPT_EVERY = 5;
    private static final long RESULT_CACHE_MILLIS = 45_000L;
    private static final long AD_CACHE_MILLIS = 600_000L;

    public static final String MODE_OFF = "OFF";
    public static final String MODE_REFERENCE = "REFERENCE";
    public static final String MODE_THROTTLE = "THROTTLE";

    public record AdAvailability(String availabilityDomain, String status) {
    }

    public record ProbeOutcome(boolean skip, String summary) {
    }

    private record CachedResult(long at, List<AdAvailability> results) {
    }

    private record CachedAds(long at, List<String> ads) {
    }

    private final ConcurrentHashMap<String, CachedResult> resultCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedAds> adCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> throttleSkips = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Object>> lastProbeByTask = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastSummaryByTask = new ConcurrentHashMap<>();

    public static String normalizeMode(String mode) {
        if (mode == null) return MODE_OFF;
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case MODE_REFERENCE, MODE_THROTTLE -> normalized;
            default -> MODE_OFF;
        };
    }

    public boolean isActive(String mode) {
        return !MODE_OFF.equals(normalizeMode(mode));
    }

    /**
     * 每轮尝试前调用。返回是否跳过本轮，以及需要写日志时的摘要（无变化时为 null 以免刷屏）。
     */
    public ProbeOutcome evaluate(String taskId, SysUserDTO dto, String mode,
                                 String shape, double ocpus, double memory) {
        String normalizedMode = normalizeMode(mode);
        if (MODE_OFF.equals(normalizedMode)) {
            clearTask(taskId);
            return new ProbeOutcome(false, null);
        }
        List<AdAvailability> results = probe(dto, shape, ocpus, memory);
        String statusText = summarize(results);
        boolean anyAvailable = results != null && results.stream()
                .anyMatch(item -> "AVAILABLE".equalsIgnoreCase(item.status()));

        boolean skip = false;
        String decision;
        if (results == null) {
            decision = "探测失败，按原节奏尝试";
        } else if (MODE_REFERENCE.equals(normalizedMode)) {
            decision = anyAvailable ? "检测到可用容量" : "暂无容量（仅参考，不影响节奏）";
            throttleSkips.remove(taskId);
        } else if (anyAvailable) {
            throttleSkips.remove(taskId);
            decision = "检测到可用容量，全速尝试";
        } else {
            int skips = throttleSkips.computeIfAbsent(taskId, ignored -> new AtomicInteger())
                    .incrementAndGet();
            if (skips >= THROTTLE_REAL_ATTEMPT_EVERY) {
                throttleSkips.get(taskId).set(0);
                decision = "暂无容量，进行兜底真实尝试";
            } else {
                skip = true;
                decision = "暂无容量，本轮跳过（" + skips + "/" + THROTTLE_REAL_ATTEMPT_EVERY + "）";
            }
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("time", System.currentTimeMillis());
        snapshot.put("mode", normalizedMode);
        snapshot.put("results", results == null ? List.of() : results.stream()
                .map(item -> Map.of("ad", shortAd(item.availabilityDomain()), "status", item.status()))
                .toList());
        snapshot.put("summary", statusText);
        snapshot.put("decision", decision);
        lastProbeByTask.put(taskId, snapshot);

        String logSummary = statusText + " → " + decision;
        // 只有结论变化或发生跳过/兜底时才写日志，避免仅参考模式每轮刷屏。
        String previous = lastSummaryByTask.put(taskId, logSummary);
        boolean worthLogging = skip || !logSummary.equals(previous);
        return new ProbeOutcome(skip, worthLogging ? logSummary : null);
    }

    public Map<String, Map<String, Object>> probeStatus(List<String> taskIds) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (taskIds == null) return out;
        for (String taskId : taskIds) {
            Map<String, Object> snapshot = taskId == null ? null : lastProbeByTask.get(taskId);
            if (snapshot != null) out.put(taskId, snapshot);
        }
        return out;
    }

    public void clearTask(String taskId) {
        if (taskId == null) return;
        throttleSkips.remove(taskId);
        lastProbeByTask.remove(taskId);
        lastSummaryByTask.remove(taskId);
    }

    private List<AdAvailability> probe(SysUserDTO dto, String shape, double ocpus, double memory) {
        String tenantId = dto.getOciCfg() == null ? null : dto.getOciCfg().getTenantId();
        String region = dto.getOciCfg() == null ? null : dto.getOciCfg().getRegion();
        if (tenantId == null || region == null || shape == null || shape.isBlank()) return null;
        boolean flexShape = shape.endsWith(".Flex");
        String cacheKey = tenantId + '|' + region + '|' + shape
                + (flexShape ? "|" + ocpus + '|' + memory : "");
        CachedResult cached = resultCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < RESULT_CACHE_MILLIS) {
            return cached.results();
        }
        try (OciClientService client = new OciClientService(dto)) {
            List<String> ads = availabilityDomains(client, tenantId, region);
            if (ads.isEmpty()) return null;
            List<AdAvailability> results = new ArrayList<>(ads.size());
            for (String ad : ads) {
                CreateCapacityReportShapeAvailabilityDetails.Builder shapeDetails =
                        CreateCapacityReportShapeAvailabilityDetails.builder().instanceShape(shape);
                if (flexShape) {
                    shapeDetails.instanceShapeConfig(CapacityReportInstanceShapeConfig.builder()
                            .ocpus((float) ocpus)
                            .memoryInGBs((float) memory)
                            .build());
                }
                ComputeCapacityReport report = client.getComputeClient()
                        .createComputeCapacityReport(CreateComputeCapacityReportRequest.builder()
                                .createComputeCapacityReportDetails(
                                        CreateComputeCapacityReportDetails.builder()
                                                .compartmentId(tenantId)
                                                .availabilityDomain(ad)
                                                .shapeAvailabilities(List.of(shapeDetails.build()))
                                                .build())
                                .build())
                        .getComputeCapacityReport();
                results.add(new AdAvailability(ad, reportStatus(report)));
            }
            resultCache.put(cacheKey, new CachedResult(now, List.copyOf(results)));
            return results;
        } catch (Exception e) {
            log.debug("容量探测失败 region={} shape={}: {}", region, shape, e.getMessage());
            return null;
        }
    }

    private List<String> availabilityDomains(OciClientService client, String tenantId, String region) {
        String key = tenantId + '|' + region;
        CachedAds cached = adCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < AD_CACHE_MILLIS) {
            return cached.ads();
        }
        List<String> ads = new ArrayList<>();
        for (AvailabilityDomain ad : client.getIdentityClient().listAvailabilityDomains(
                ListAvailabilityDomainsRequest.builder().compartmentId(tenantId).build()).getItems()) {
            if (ad != null && ad.getName() != null && !ad.getName().isBlank()) {
                ads.add(ad.getName());
            }
        }
        List<String> copy = List.copyOf(ads);
        adCache.put(key, new CachedAds(now, copy));
        return copy;
    }

    private static String reportStatus(ComputeCapacityReport report) {
        if (report == null || report.getShapeAvailabilities() == null
                || report.getShapeAvailabilities().isEmpty()) {
            return "UNKNOWN";
        }
        CapacityReportShapeAvailability availability = report.getShapeAvailabilities().get(0);
        return availability == null || availability.getAvailabilityStatus() == null
                ? "UNKNOWN" : availability.getAvailabilityStatus().getValue();
    }

    private static String summarize(List<AdAvailability> results) {
        if (results == null || results.isEmpty()) return "探测无结果";
        StringBuilder text = new StringBuilder();
        for (AdAvailability item : results) {
            if (text.length() > 0) text.append(" · ");
            text.append(shortAd(item.availabilityDomain())).append(' ')
                    .append("AVAILABLE".equalsIgnoreCase(item.status()) ? "有货"
                            : "OUT_OF_HOST_CAPACITY".equalsIgnoreCase(item.status()) ? "无货"
                            : item.status());
        }
        return text.toString();
    }

    private static String shortAd(String availabilityDomain) {
        if (availabilityDomain == null) return "AD";
        int index = availabilityDomain.lastIndexOf("AD-");
        return index >= 0 ? availabilityDomain.substring(index) : availabilityDomain;
    }
}
