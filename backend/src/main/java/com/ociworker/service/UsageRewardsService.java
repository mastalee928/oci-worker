package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.ociworker.util.OciBmcErrorTranslator;
import com.ociworker.util.OciRegionCatalog;
import com.oracle.bmc.Region;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.usage.RewardsClient;
import com.oracle.bmc.usage.model.MonthlyRewardSummary;
import com.oracle.bmc.usage.model.RewardCollection;
import com.oracle.bmc.usage.model.RewardDetails;
import com.oracle.bmc.usage.requests.ListRewardsRequest;
import com.oracle.bmc.usage.responses.ListRewardsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OCI Usage Rewards API（促销/试用余额），对应官方 list-rewards。
 * 仅展示 API 返回值，不做推算。
 *
 * @see <a href="https://docs.oracle.com/en-us/iaas/Content/Billing/Tasks/list-rewards-monthly-reward-summary.htm">Listing Monthly Subscription Rewards</a>
 */
@Slf4j
@Service
public class UsageRewardsService {

    /**
     * 按顺序尝试多个订阅 OCID（优先 OSP 计费订阅，其次 Organizations 订阅）。
     */
    public Map<String, Object> fetchSubscriptionRewards(
            OciClientService oci,
            String tenancyId,
            List<String> subscriptionIds,
            String fallbackRegion) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", Boolean.FALSE);
        out.put("reason", null);
        out.put("summary", null);
        out.put("periods", new ArrayList<>());
        out.put("attemptedSubscriptionIds", List.of());
        out.put("subscriptionIdUsed", null);

        if (StrUtil.isBlank(tenancyId)) {
            out.put("reason", "缺少 tenancy OCID");
            return out;
        }

        List<String> candidates = dedupeOcidCandidates(subscriptionIds);
        if (candidates.isEmpty()) {
            out.put("reason", "缺少可用于 Rewards 的订阅 OCID（OSP 编号需先解析为 ocid1.*）");
            return out;
        }

        List<String> attempted = new ArrayList<>();
        List<String> failureNotes = new ArrayList<>();
        for (String subId : candidates) {
            attempted.add(subId);
            Map<String, Object> one = fetchSubscriptionRewardsSingle(oci, tenancyId, subId, fallbackRegion);
            if (Boolean.TRUE.equals(one.get("available"))) {
                one.put("attemptedSubscriptionIds", attempted);
                one.put("subscriptionIdUsed", subId);
                return one;
            }
            Object reason = one.get("reason");
            if (reason != null && StrUtil.isNotBlank(String.valueOf(reason))) {
                failureNotes.add(shortId(subId) + ": " + reason);
            }
        }

        out.put("attemptedSubscriptionIds", attempted);
        out.put("reason", buildMultiAttemptReason(failureNotes));
        return out;
    }

    /**
     * @return 含 available、reason、summary、periods；失败时 available=false 且带 reason
     */
    public Map<String, Object> fetchSubscriptionRewards(
            OciClientService oci, String tenancyId, String subscriptionId, String fallbackRegion) {
        List<String> ids = StrUtil.isBlank(subscriptionId) ? List.of() : List.of(subscriptionId.trim());
        return fetchSubscriptionRewards(oci, tenancyId, ids, fallbackRegion);
    }

    private Map<String, Object> fetchSubscriptionRewardsSingle(
            OciClientService oci, String tenancyId, String subscriptionId, String fallbackRegion) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", Boolean.FALSE);
        out.put("reason", null);
        out.put("summary", null);
        out.put("periods", new ArrayList<>());

        if (StrUtil.isBlank(subscriptionId)) {
            out.put("reason", "缺少订阅 ID，无法查询促销余额");
            return out;
        }
        if (!OspSubscriptionEnricher.isOciOcid(subscriptionId)) {
            out.put("reason", "订阅引用「" + subscriptionId.trim()
                    + "」不是 OCID（需 ocid1.*）；Usage Rewards 不接受 OSP 订阅编号");
            return out;
        }

        var rewardsB = RewardsClient.builder();
        OciProxyConfigService pxy = OciProxyConfigService.instance();
        if (pxy == null || !pxy.ociUsesExplicitClientProxy()) {
            rewardsB = rewardsB.additionalClientConfigurator(OciProxyConfigService.ociSdkJerseyDirectConfigurator());
        }

        try (RewardsClient client = rewardsB.build(oci.getProvider())) {
            String region = UsageCostService.resolveTenancyHomeRegionName(
                    oci.getIdentityClient(), tenancyId, fallbackRegion);
            try {
                client.setRegion(OciRegionCatalog.resolveRegion(region));
            } catch (Exception e) {
                client.setRegion(Region.US_ASHBURN_1);
            }

            ListRewardsResponse resp = client.listRewards(
                    ListRewardsRequest.builder()
                            .tenancyId(tenancyId.trim())
                            .subscriptionId(subscriptionId.trim())
                            .build());

            RewardCollection col = resp == null ? null : resp.getRewardCollection();
            if (col == null) {
                out.put("reason", "促销余额接口返回为空");
                return out;
            }

            out.put("available", Boolean.TRUE);
            if (col.getSummary() != null) {
                out.put("summary", mapSummary(col.getSummary()));
            }
            List<Map<String, Object>> periods = new ArrayList<>();
            if (col.getItems() != null) {
                for (MonthlyRewardSummary item : col.getItems()) {
                    if (item != null) {
                        periods.add(mapPeriod(item));
                    }
                }
            }
            out.put("periods", periods);
            if (periods.isEmpty() && col.getSummary() == null) {
                out.put("available", Boolean.FALSE);
                out.put("reason", "无促销余额记录");
            }
        } catch (BmcException e) {
            log.warn("listRewards failed: {}", e.getMessage());
            out.put("reason", formatRewardsError(e));
        } catch (Exception e) {
            log.warn("listRewards failed: {}", e.getMessage());
            out.put("reason", "促销余额查询失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
        return out;
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

    private static String shortId(String ocid) {
        if (StrUtil.isBlank(ocid) || ocid.length() <= 48) {
            return ocid;
        }
        return ocid.substring(0, 24) + "…" + ocid.substring(ocid.length() - 12);
    }

    private static String buildMultiAttemptReason(List<String> failureNotes) {
        if (failureNotes == null || failureNotes.isEmpty()) {
            return "促销余额接口均无数据";
        }
        if (failureNotes.size() == 1) {
            return failureNotes.get(0);
        }
        return "已依次尝试 " + failureNotes.size() + " 个订阅 OCID，均未返回促销余额；"
                + String.join("；", failureNotes);
    }

    private static Map<String, Object> mapSummary(RewardDetails s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenancyId", s.getTenancyId());
        m.put("subscriptionId", s.getSubscriptionId());
        m.put("currency", s.getCurrency());
        m.put("rewardsRate", s.getRewardsRate());
        m.put("totalRewardsAvailable", s.getTotalRewardsAvailable());
        m.put("redemptionCode", s.getRedemptionCode());
        m.put("totalRewardsAvailableLabel", formatRewardAmount(s.getTotalRewardsAvailable(), s.getCurrency()));
        return m;
    }

    private static Map<String, Object> mapPeriod(MonthlyRewardSummary item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("availableRewards", item.getAvailableRewards());
        m.put("earnedRewards", item.getEarnedRewards());
        m.put("redeemedRewards", item.getRedeemedRewards());
        m.put("isManual", item.getIsManual());
        m.put("timeRewardsExpired", formatInstant(item.getTimeRewardsExpired()));
        m.put("timeRewardsEarned", formatInstant(item.getTimeRewardsEarned()));
        m.put("timeUsageStarted", formatInstant(item.getTimeUsageStarted()));
        m.put("timeUsageEnded", formatInstant(item.getTimeUsageEnded()));
        m.put("usageAmount", item.getUsageAmount());
        m.put("eligibleUsageAmount", item.getEligibleUsageAmount());
        m.put("ineligibleUsageAmount", item.getIneligibleUsageAmount());
        m.put("usagePeriodKey", item.getUsagePeriodKey());
        m.put("availableRewardsLabel", formatRewardAmount(item.getAvailableRewards(), null));
        m.put("earnedRewardsLabel", formatRewardAmount(item.getEarnedRewards(), null));
        m.put("redeemedRewardsLabel", formatRewardAmount(item.getRedeemedRewards(), null));
        return m;
    }

    static String formatRewardAmount(Number amount, String currency) {
        if (amount == null) {
            return null;
        }
        String cur = StrUtil.isNotBlank(currency) ? (" " + currency.trim()) : "";
        return amount + cur;
    }

    private static String formatInstant(Date d) {
        return d == null ? null : d.toInstant().toString();
    }

    private static String formatRewardsError(BmcException e) {
        String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
        int code = e.getStatusCode();
        if (code == 404) {
            return "促销余额接口无数据（404）";
        }
        if (code == 401 || code == 403 || msg.contains("NotAuthorized")) {
            return "促销余额权限不足（需 usage / rewards 相关读权限）：" + msg;
        }
        return "促销余额查询失败（HTTP " + code + "）：" + OciBmcErrorTranslator.translate(e);
    }
}
