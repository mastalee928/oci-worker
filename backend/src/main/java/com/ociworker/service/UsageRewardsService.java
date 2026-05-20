package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
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
import java.util.List;
import java.util.Map;

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
     * @return 含 available、reason、summary、periods；失败时 available=false 且带 reason
     */
    public Map<String, Object> fetchSubscriptionRewards(
            OciClientService oci, String tenancyId, String subscriptionId, String fallbackRegion) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", Boolean.FALSE);
        out.put("reason", null);
        out.put("summary", null);
        out.put("periods", new ArrayList<>());

        if (StrUtil.isBlank(tenancyId)) {
            out.put("reason", "缺少 tenancy OCID");
            return out;
        }
        if (StrUtil.isBlank(subscriptionId)) {
            out.put("reason", "缺少订阅 ID，无法查询促销余额");
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
                client.setRegion(Region.fromRegionId(region));
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
        return "促销余额查询失败（HTTP " + code + "）：" + msg;
    }
}
