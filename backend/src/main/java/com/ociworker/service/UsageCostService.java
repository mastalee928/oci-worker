package com.ociworker.service;

import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.oracle.bmc.Region;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.requests.GetTenancyRequest;
import com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest;
import com.oracle.bmc.usageapi.UsageapiClient;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OCI Usage API（成本分析 / Cost Analysis），对应控制台「成本分析」。
 *
 * @see <a href="https://docs.oracle.com/en-us/iaas/Content/Billing/Concepts/costanalysisoverview.htm">Cost Analysis</a>
 */
@Slf4j
@Service
public class UsageCostService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    @Resource
    private OciUserMapper userMapper;

    /**
     * @param days 查询最近 N 天成本（1–90）
     */
    public Map<String, Object> fetchCostAnalysis(String tenantId, int days) {
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");

        int periodDays = Math.max(1, Math.min(90, days));
        String tenancyId = user.getOciTenantId();
        Instant endInst = Instant.now();
        Instant startInst = endInst.minusSeconds((long) periodDays * 86400L);
        Date timeEnd = Date.from(endInst);
        Date timeStart = Date.from(startInst);

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
            String usageRegion = resolveUsageApiRegion(oci.getIdentityClient(), tenancyId, user.getOciRegion());
            UsageapiClient client = UsageapiClient.builder().build(oci.getProvider());
            try {
                client.setRegion(Region.fromRegionId(usageRegion));
            } catch (Exception e) {
                client.setRegion(Region.US_ASHBURN_1);
            }

            try {
                List<UsageSummary> totalRows = queryCost(client, tenancyId, timeStart, timeEnd,
                        RequestSummarizedUsagesDetails.Granularity.Monthly, List.of(), true);
                List<UsageSummary> serviceRows = queryCost(client, tenancyId, timeStart, timeEnd,
                        RequestSummarizedUsagesDetails.Granularity.Monthly, List.of("service"), true);
                List<UsageSummary> dailyRows = queryCost(client, tenancyId, timeStart, timeEnd,
                        RequestSummarizedUsagesDetails.Granularity.Daily, List.of(), false);

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
                usage.put("reason", "成本分析接口不可用/权限不足（需 usage-report 相关读权限）："
                        + (e.getMessage() == null ? "未知错误" : e.getMessage()));
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
            boolean aggregateByTime) throws Exception {
        RequestSummarizedUsagesDetails details = RequestSummarizedUsagesDetails.builder()
                .tenantId(tenancyId)
                .timeUsageStarted(timeStart)
                .timeUsageEnded(timeEnd)
                .granularity(granularity)
                .isAggregateByTime(aggregateByTime)
                .queryType(RequestSummarizedUsagesDetails.QueryType.Cost)
                .groupBy(groupBy == null || groupBy.isEmpty() ? null : groupBy)
                .build();

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

    private static String resolveUsageApiRegion(IdentityClient identityClient, String tenancyId, String fallback) {
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
}
