package com.ociworker.service;

import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.oracle.bmc.Region;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.requests.GetTenancyRequest;
import com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.usageapi.UsageapiClient;
import com.oracle.bmc.usageapi.model.Dimension;
import com.oracle.bmc.usageapi.model.Filter;
import com.oracle.bmc.usageapi.model.RequestSummarizedUsagesDetails;
import com.oracle.bmc.usageapi.model.UsageSummary;
import com.oracle.bmc.usageapi.requests.RequestSummarizedUsagesRequest;
import com.oracle.bmc.usageapi.responses.RequestSummarizedUsagesResponse;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OCI Usage API（成本分析 / Cost Analysis），对应控制台「成本分析」。
 *
 * @see <a href="https://docs.oracle.com/en-us/iaas/Content/Billing/Concepts/costanalysisoverview.htm">Cost Analysis</a>
 */
@Slf4j
@Service
public class UsageCostService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final int MAX_USAGE_PERIOD_DAYS = 90;

    @Resource
    private OciUserMapper userMapper;

    /**
     * 按订阅 ID 汇总试用/促销期内官方消费（对齐 R-Bot：OSP + Usage API），仅 API 返回值，不推算余额。
     */
    public Map<String, Object> fetchSubscriptionUsageCost(
            OciClientService oci,
            String tenancyId,
            List<String> subscriptionIds,
            String usageStartIso,
            String fallbackRegion) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", Boolean.FALSE);
        out.put("reason", null);
        out.put("subscriptionIdUsed", null);
        out.put("attemptedSubscriptionIds", List.of());
        out.put("timeUsageStarted", null);
        out.put("timeUsageEnded", null);
        out.put("summary", null);
        out.put("byService", new ArrayList<>());

        if (StrUtil.isBlank(tenancyId)) {
            out.put("reason", "缺少 tenancy OCID");
            return out;
        }

        List<String> candidates = dedupeOcidCandidates(subscriptionIds);
        if (candidates.isEmpty()) {
            out.put("reason", "缺少订阅 OCID，无法按订阅查询 Usage 消费");
            return out;
        }

        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        LocalDate startDay = parseUsageStartDay(usageStartIso, todayUtc);
        LocalDate endDay = todayUtc.plusDays(1);
        Date timeStart = Date.from(startDay.atStartOfDay(ZoneOffset.UTC).toInstant());
        Date timeEnd = Date.from(endDay.atStartOfDay(ZoneOffset.UTC).toInstant());
        out.put("timeUsageStarted", timeStart.toInstant().toString());
        out.put("timeUsageEnded", timeEnd.toInstant().toString());

        UsageapiClient client = UsageapiClient.builder().build(oci.getProvider());
        try {
            String usageRegion = resolveTenancyHomeRegionName(
                    oci.getIdentityClient(), tenancyId, fallbackRegion);
            try {
                client.setRegion(Region.fromRegionId(usageRegion));
            } catch (Exception e) {
                client.setRegion(Region.US_ASHBURN_1);
            }

            List<String> attempted = new ArrayList<>();
            List<String> failureNotes = new ArrayList<>();
            for (String subId : candidates) {
                attempted.add(subId);
                try {
                    List<UsageSummary> totalRows = queryCost(
                            client, tenancyId, timeStart, timeEnd,
                            RequestSummarizedUsagesDetails.Granularity.Monthly,
                            List.of(), true, subscriptionFilter(subId));
                    List<UsageSummary> serviceRows = queryCost(
                            client, tenancyId, timeStart, timeEnd,
                            RequestSummarizedUsagesDetails.Granularity.Monthly,
                            List.of("service"), true, subscriptionFilter(subId));

                    BigDecimal total = sumComputedAmount(totalRows);
                    String currency = pickCurrency(totalRows, serviceRows);
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("totalConsumed", toPlain(total));
                    summary.put("currency", currency);
                    summary.put("totalConsumedLabel", formatCostLabel(total, currency));
                    out.put("summary", summary);
                    out.put("byService", aggregateByService(serviceRows, currency));
                    out.put("available", Boolean.TRUE);
                    out.put("subscriptionIdUsed", subId);
                    out.put("attemptedSubscriptionIds", attempted);
                    out.put("reason", null);
                    return out;
                } catch (BmcException e) {
                    log.warn("Usage API subscription {} failed: {}", subId, e.getMessage());
                    failureNotes.add(shortOcid(subId) + ": " + formatUsageApiError(e));
                } catch (Exception e) {
                    log.warn("Usage API subscription {} failed: {}", subId, e.getMessage());
                    failureNotes.add(shortOcid(subId) + ": " + formatUsageApiError(e));
                }
            }
            out.put("attemptedSubscriptionIds", attempted);
            out.put("reason", failureNotes.isEmpty()
                    ? "Usage API 未返回该订阅消费数据"
                    : "已尝试 " + attempted.size() + " 个订阅 OCID；" + String.join("；", failureNotes));
        } finally {
            client.close();
        }
        return out;
    }

    private static Filter subscriptionFilter(String subscriptionId) {
        return Filter.builder()
                .operator(Filter.Operator.And)
                .dimensions(List.of(
                        Dimension.builder()
                                .key("subscriptionId")
                                .value(subscriptionId.trim())
                                .build()))
                .build();
    }

    private static List<String> dedupeOcidCandidates(List<String> subscriptionIds) {
        if (subscriptionIds == null || subscriptionIds.isEmpty()) {
            return List.of();
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String id : subscriptionIds) {
            if (StrUtil.isNotBlank(id) && OspSubscriptionEnricher.isOciOcid(id)) {
                ordered.add(id.trim());
            }
        }
        return new ArrayList<>(ordered);
    }

    private static LocalDate parseUsageStartDay(String usageStartIso, LocalDate todayUtc) {
        LocalDate earliest = todayUtc.minusDays(MAX_USAGE_PERIOD_DAYS);
        if (StrUtil.isBlank(usageStartIso)) {
            return earliest;
        }
        try {
            LocalDate start = Instant.parse(usageStartIso.trim()).atZone(ZoneOffset.UTC).toLocalDate();
            if (start.isAfter(todayUtc)) {
                return todayUtc;
            }
            if (start.isBefore(earliest)) {
                return earliest;
            }
            return start;
        } catch (Exception e) {
            return earliest;
        }
    }

    private static String formatCostLabel(BigDecimal amount, String currency) {
        if (amount == null) {
            return null;
        }
        String cur = StrUtil.isNotBlank(currency) ? (" " + currency.trim()) : "";
        return toPlain(amount) + cur;
    }

    private static String shortOcid(String ocid) {
        if (StrUtil.isBlank(ocid) || ocid.length() <= 48) {
            return ocid;
        }
        return ocid.substring(0, 22) + "…" + ocid.substring(ocid.length() - 10);
    }

    /**
     * @param days 查询最近 N 天成本（1–90）
     */
    public Map<String, Object> fetchCostAnalysis(String tenantId, int days) {
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");

        int periodDays = Math.max(1, Math.min(90, days));
        String tenancyId = user.getOciTenantId();
        // Usage API 要求 UTC 时间为当日 00:00:00（不可带时分秒）
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        Date timeStart = Date.from(todayUtc.minusDays(periodDays).atStartOfDay(ZoneOffset.UTC).toInstant());
        Date timeEnd = Date.from(todayUtc.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("available", Boolean.FALSE);
        usage.put("periodDays", periodDays);
        usage.put("timeUsageStarted", timeStart.toInstant().toString());
        usage.put("timeUsageEnded", timeEnd.toInstant().toString());
        usage.put("summary", null);
        usage.put("byService", new ArrayList<>());
        usage.put("byDay", new ArrayList<>());

        SysUserDTO dto = SysUserDTO.builder()
                .username(user.getUsername())
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(user.getOciTenantId())
                        .userId(user.getOciUserId())
                        .fingerprint(user.getOciFingerprint())
                        .region(user.getOciRegion())
                        .privateKeyPath(user.getOciKeyPath())
                        .build())
                .build();

        try (OciClientService oci = new OciClientService(dto)) {
            String usageRegion = resolveTenancyHomeRegionName(oci.getIdentityClient(), tenancyId, user.getOciRegion());
            UsageapiClient client = UsageapiClient.builder().build(oci.getProvider());
            try {
                client.setRegion(Region.fromRegionId(usageRegion));
            } catch (Exception e) {
                client.setRegion(Region.US_ASHBURN_1);
            }

            try {
                List<UsageSummary> totalRows = queryCost(client, tenancyId, timeStart, timeEnd,
                        RequestSummarizedUsagesDetails.Granularity.Monthly, List.of(), true, null);
                List<UsageSummary> serviceRows = queryCost(client, tenancyId, timeStart, timeEnd,
                        RequestSummarizedUsagesDetails.Granularity.Monthly, List.of("service"), true, null);
                List<UsageSummary> dailyRows = queryCost(client, tenancyId, timeStart, timeEnd,
                        RequestSummarizedUsagesDetails.Granularity.Daily, List.of(), false, null);

                Map<String, Object> summary = new LinkedHashMap<>();
                BigDecimal total = sumComputedAmount(totalRows);
                String currency = pickCurrency(totalRows, serviceRows, dailyRows);
                summary.put("totalCost", toPlain(total));
                summary.put("currency", currency);
                usage.put("summary", summary);

                List<Map<String, Object>> byService = aggregateByService(serviceRows, currency);
                usage.put("byService", byService);

                List<Map<String, Object>> byDay = aggregateByDay(dailyRows, currency);
                usage.put("byDay", byDay);

                usage.put("available", Boolean.TRUE);
                usage.put("reason", null);
            } catch (Exception e) {
                log.warn("Usage API cost query failed for {}: {}", tenantId, e.getMessage());
                usage.put("reason", formatUsageApiError(e));
            } finally {
                client.close();
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            usage.put("reason", "初始化 Usage API 客户端失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }

        return usage;
    }

    private static List<UsageSummary> queryCost(
            UsageapiClient client,
            String tenancyId,
            Date timeStart,
            Date timeEnd,
            RequestSummarizedUsagesDetails.Granularity granularity,
            List<String> groupBy,
            boolean aggregateByTime,
            Filter filter) throws Exception {
        RequestSummarizedUsagesDetails.Builder detailsB = RequestSummarizedUsagesDetails.builder()
                .tenantId(tenancyId)
                .timeUsageStarted(timeStart)
                .timeUsageEnded(timeEnd)
                .granularity(granularity)
                .isAggregateByTime(aggregateByTime)
                .queryType(RequestSummarizedUsagesDetails.QueryType.Cost)
                .groupBy(groupBy == null || groupBy.isEmpty() ? null : groupBy);
        if (filter != null) {
            detailsB.filter(filter);
        }
        RequestSummarizedUsagesDetails details = detailsB.build();

        List<UsageSummary> items = new ArrayList<>();
        String page = null;
        do {
            RequestSummarizedUsagesRequest.Builder req = RequestSummarizedUsagesRequest.builder()
                    .requestSummarizedUsagesDetails(details)
                    .limit(1000);
            if (page != null) req.page(page);
            RequestSummarizedUsagesResponse resp = client.requestSummarizedUsages(req.build());
            if (resp.getUsageAggregation() != null && resp.getUsageAggregation().getItems() != null) {
                items.addAll(resp.getUsageAggregation().getItems());
            }
            page = resp.getOpcNextPage();
        } while (page != null && !page.isBlank());
        return items;
    }

    private static List<Map<String, Object>> aggregateByService(List<UsageSummary> rows, String defaultCurrency) {
        Map<String, BigDecimal> costByService = new LinkedHashMap<>();
        for (UsageSummary u : rows) {
            if (u == null || Boolean.TRUE.equals(u.getIsForecast())) continue;
            String svc = StrUtil.blankToDefault(u.getService(), "（未分类）");
            costByService.merge(svc, nz(u.getComputedAmount()), BigDecimal::add);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (var e : costByService.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service", e.getKey());
            row.put("cost", toPlain(e.getValue()));
            row.put("currency", defaultCurrency);
            list.add(row);
        }
        list.sort(Comparator.comparing((Map<String, Object> m) -> new BigDecimal(String.valueOf(m.get("cost")))).reversed());
        return list;
    }

    private static List<Map<String, Object>> aggregateByDay(List<UsageSummary> rows, String defaultCurrency) {
        Map<String, BigDecimal> costByDay = new LinkedHashMap<>();
        for (UsageSummary u : rows) {
            if (u == null || Boolean.TRUE.equals(u.getIsForecast())) continue;
            if (u.getTimeUsageStarted() == null) continue;
            String day = DAY_FMT.format(u.getTimeUsageStarted().toInstant());
            costByDay.merge(day, nz(u.getComputedAmount()), BigDecimal::add);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (var e : costByDay.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", e.getKey());
            row.put("cost", toPlain(e.getValue()));
            row.put("currency", defaultCurrency);
            list.add(row);
        }
        list.sort(Comparator.comparing(m -> String.valueOf(m.get("date"))));
        return list;
    }

    private static BigDecimal sumComputedAmount(List<UsageSummary> rows) {
        BigDecimal sum = BigDecimal.ZERO;
        for (UsageSummary u : rows) {
            if (u == null || Boolean.TRUE.equals(u.getIsForecast())) continue;
            sum = sum.add(nz(u.getComputedAmount()));
        }
        return sum;
    }

    private static String pickCurrency(List<UsageSummary>... lists) {
        for (List<UsageSummary> rows : lists) {
            if (rows == null) continue;
            for (UsageSummary u : rows) {
                if (u != null && StrUtil.isNotBlank(u.getCurrency())) return u.getCurrency();
            }
        }
        return null;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String toPlain(BigDecimal v) {
        if (v == null) return "0";
        return v.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** tenancy 主区域名（Usage API / Rewards 等账单类服务共用）。 */
    static String resolveTenancyHomeRegionName(IdentityClient identityClient, String tenancyId, String fallback) {
        if (identityClient == null || StrUtil.isBlank(tenancyId)) {
            return StrUtil.blankToDefault(fallback, Region.US_ASHBURN_1.getRegionId());
        }
        try {
            var tenancy = identityClient.getTenancy(GetTenancyRequest.builder().tenancyId(tenancyId).build()).getTenancy();
            String homeKey = tenancy == null ? null : tenancy.getHomeRegionKey();
            if (StrUtil.isBlank(homeKey)) return StrUtil.blankToDefault(fallback, Region.US_ASHBURN_1.getRegionId());

            var regions = identityClient.listRegionSubscriptions(
                    ListRegionSubscriptionsRequest.builder().tenancyId(tenancyId).build()).getItems();
            if (regions != null) {
                for (var r : regions) {
                    if (homeKey.equalsIgnoreCase(r.getRegionKey())) {
                        String name = r.getRegionName();
                        if (StrUtil.isNotBlank(name)) return name;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return StrUtil.blankToDefault(fallback, Region.US_ASHBURN_1.getRegionId());
    }

    static String formatUsageApiError(Exception e) {
        String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
        if (e instanceof BmcException bmc) {
            int code = bmc.getStatusCode();
            if (code == 404) {
                return "Usage API 无数据（404）";
            }
            if (code == 401 || code == 403 || msg.contains("NotAuthorized")) {
                return "Usage 权限不足（需 usage-report / 成本分析读权限）：" + msg;
            }
            return "Usage API 失败（HTTP " + code + "）：" + msg;
        }
        if (msg.contains("InvalidParameter") && msg.contains("precision")) {
            return "成本分析请求时间格式不符合 OCI 要求（已按 UTC 整日对齐，若仍失败请反馈日志）";
        }
        if (msg.contains("NotAuthorized") || msg.contains("403")) {
            return "成本分析权限不足（需 usage-report 相关读权限）：" + msg;
        }
        return "成本分析查询失败：" + msg;
    }
}
