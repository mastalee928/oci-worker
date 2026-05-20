package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.oracle.bmc.ospgateway.SubscriptionServiceClient;
import com.oracle.bmc.ospgateway.requests.GetSubscriptionRequest;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从 OSP Gateway 订阅对象提取/推导账户信息 Tab 所需字段（含反射兼容未文档化字段）。
 */
@Slf4j
final class OspSubscriptionEnricher {

    private OspSubscriptionEnricher() {}

    static Object fetchSubscriptionDetail(
            SubscriptionServiceClient client, String ospHomeRegion, String compartmentId, String subscriptionId) {
        if (client == null || StrUtil.isBlank(subscriptionId)) return null;
        try {
            return client.getSubscription(
                            GetSubscriptionRequest.builder()
                                    .subscriptionId(subscriptionId.trim())
                                    .ospHomeRegion(ospHomeRegion)
                                    .compartmentId(compartmentId)
                                    .build())
                    .getSubscription();
        } catch (Exception e) {
            log.warn("getSubscription failed for {}: {}", subscriptionId, e.getMessage());
            return null;
        }
    }

    static void enrich(Object sub, Map<String, Object> result) {
        if (sub == null || result == null) return;

        String planVal = enumValue(tryInvoke(sub, "getPlanType"));
        result.put("planType", planVal);
        result.put("planTypeLabel", labelPlanType(planVal));
        result.put("accountType", enumValue(tryInvoke(sub, "getAccountType")));
        result.put("upgradeState", enumValue(tryInvoke(sub, "getUpgradeState")));
        result.put("upgradeStateLabel", labelUpgradeState(enumValue(tryInvoke(sub, "getUpgradeState"))));
        result.put("currencyCode", asString(tryInvoke(sub, "getCurrencyCode")));
        result.put("isIntentToPay", tryInvoke(sub, "getIsIntentToPay"));
        result.put("subscriptionStartTime", formatInstant(tryInvoke(sub, "getTimeStart")));

        Date timeEnd = firstDate(sub,
                "getTimeEnd", "getTimeEnded", "getEndTime", "getSubscriptionEndTime", "getPromoEndTime");
        result.put("subscriptionEndTime", formatInstant(timeEnd));

        Integer durationDays = durationDays(tryInvoke(sub, "getTimeStart"), timeEnd);
        if (durationDays == null) {
            Object dur = tryInvoke(sub, "getDurationDays");
            if (dur == null) dur = tryInvoke(sub, "getDuration");
            durationDays = parseInt(dur);
        }
        result.put("subscriptionDurationDays", durationDays);

        String paymentMethod = resolvePaymentMethod(sub);
        result.put("paymentMethod", paymentMethod);
        result.put("paymentMethodLabel", labelPaymentMethod(paymentMethod));

        Number amount = resolveSubscriptionAmount(sub);
        result.put("subscriptionAmount", amount);
        String currency = asString(tryInvoke(sub, "getCurrencyCode"));
        result.put("subscriptionAmountLabel", formatAmount(amount, currency));

        String rawStatus = firstString(sub,
                "getStatus", "getSubscriptionStatus", "getLifecycleState", "getState");
        ResolvedStatus resolved = resolveSubscriptionStatus(sub, rawStatus, timeEnd, planVal,
                enumValue(tryInvoke(sub, "getUpgradeState")));
        result.put("subscriptionStatus", resolved.code());
        result.put("subscriptionStatusLabel", resolved.label());
        result.put("subscriptionRenewTime", null);
    }

    private static String resolvePaymentMethod(Object sub) {
        List<?> opts = asList(tryInvoke(sub, "getPaymentOptions"));
        if (opts != null) {
            for (Object opt : opts) {
                String pm = enumValue(tryInvoke(opt, "getPaymentMethod"));
                if (StrUtil.isBlank(pm) && opt != null) {
                    String simple = opt.getClass().getSimpleName();
                    if (simple.contains("FreeTrial")) pm = "FREE_TRIAL";
                    else if (simple.contains("CreditCard")) pm = "CREDIT_CARD";
                    else if (simple.contains("Paypal")) pm = "PAYPAL";
                }
                if (StrUtil.isNotBlank(pm)) return pm;
            }
        }
        Object gateway = tryInvoke(sub, "getPaymentGateway");
        if (gateway != null) {
            String pm = firstString(gateway, "getPaymentMethod", "getType", "getGatewayType");
            if (StrUtil.isNotBlank(pm)) return pm;
        }
        String plan = enumValue(tryInvoke(sub, "getPlanType"));
        String upgrade = enumValue(tryInvoke(sub, "getUpgradeState"));
        if ("FREE_TIER".equalsIgnoreCase(plan) && "PROMO".equalsIgnoreCase(upgrade)) {
            return "FREE_TRIAL";
        }
        return null;
    }

    private static Number resolveSubscriptionAmount(Object sub) {
        Number n = firstNumber(sub,
                "getSubscriptionAmount", "getPromoAmount", "getPromotionalCreditAmount",
                "getTotalAmount", "getContractValue", "getListPrice", "getAmount", "getCreditAmount");
        if (n != null) return n;
        Object gateway = tryInvoke(sub, "getPaymentGateway");
        if (gateway != null) {
            n = firstNumber(gateway, "getAmount", "getTotalAmount", "getSubscriptionAmount");
            if (n != null) return n;
        }
        return null;
    }

    private record ResolvedStatus(String code, String label) {}

    private static ResolvedStatus resolveSubscriptionStatus(
            Object sub, String rawStatus, Date timeEnd, String planType, String upgradeState) {
        if (StrUtil.isNotBlank(rawStatus)) {
            return new ResolvedStatus(rawStatus.toUpperCase(Locale.ROOT), labelSubscriptionStatus(rawStatus));
        }
        Instant now = Instant.now();
        if (timeEnd != null) {
            if (timeEnd.toInstant().isBefore(now)) {
                return new ResolvedStatus("EXPIRED", "已过期");
            }
            return new ResolvedStatus("ACTIVE", "有效");
        }
        if ("UPGRADED".equalsIgnoreCase(upgradeState)) {
            return new ResolvedStatus("ACTIVE", "已升级");
        }
        if ("ERROR".equalsIgnoreCase(upgradeState)) {
            return new ResolvedStatus("ERROR", "异常");
        }
        if ("SUBMITTED".equalsIgnoreCase(upgradeState)) {
            return new ResolvedStatus("PENDING", "处理中");
        }
        if ("PROMO".equalsIgnoreCase(upgradeState)
                || "FREE_TIER".equalsIgnoreCase(planType)
                || "FREE".equalsIgnoreCase(planType)) {
            return new ResolvedStatus("ACTIVE", "试用中");
        }
        if ("PAYG".equalsIgnoreCase(planType)) {
            return new ResolvedStatus("ACTIVE", "按量付费");
        }
        return new ResolvedStatus(null, null);
    }

    static String labelSubscriptionStatus(String status) {
        if (StrUtil.isBlank(status)) return null;
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> "有效";
            case "EXPIRED" -> "已过期";
            case "INACTIVE" -> "未激活";
            case "PENDING" -> "处理中";
            case "ERROR" -> "异常";
            default -> status;
        };
    }

    static String labelPaymentMethod(String method) {
        if (StrUtil.isBlank(method)) return null;
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "FREE_TRIAL" -> "免费试用 (FREE_TRIAL)";
            case "CREDIT_CARD" -> "信用卡";
            case "PAYPAL" -> "PayPal";
            default -> method;
        };
    }

    static String labelUpgradeState(String upgrade) {
        if (StrUtil.isBlank(upgrade)) return null;
        return switch (upgrade.toUpperCase(Locale.ROOT)) {
            case "PROMO" -> "促销/试用";
            case "SUBMITTED" -> "已提交";
            case "ERROR" -> "错误";
            case "UPGRADED" -> "已升级";
            case "UPGRADE_PENDING" -> "升级待处理";
            case "UPGRADE_COMPLETE" -> "升级完成";
            case "UPGRADE_FAILED" -> "升级失败";
            default -> upgrade;
        };
    }

    static String labelPlanType(String plan) {
        if (StrUtil.isBlank(plan)) return null;
        return switch (plan.toUpperCase(Locale.ROOT)) {
            case "FREE", "FREE_TIER" -> "免费套餐 (Free Tier)";
            case "PAYG" -> "按量付费 (PAYG)";
            default -> plan;
        };
    }

    private static String formatAmount(Number amount, String currency) {
        if (amount == null) return null;
        String cur = StrUtil.isNotBlank(currency) ? (" " + currency.trim()) : "";
        return amount + cur;
    }

    private static Integer durationDays(Object startObj, Date end) {
        if (!(startObj instanceof Date start) || end == null) return null;
        long days = ChronoUnit.DAYS.between(start.toInstant(), end.toInstant());
        return days >= 0 ? (int) days : null;
    }

    private static Date firstDate(Object target, String... getters) {
        for (String g : getters) {
            Object v = tryInvoke(target, g);
            Date d = asDate(v);
            if (d != null) return d;
        }
        return null;
    }

    private static String firstString(Object target, String... getters) {
        for (String g : getters) {
            String s = asString(tryInvoke(target, g));
            if (StrUtil.isNotBlank(s)) return s;
        }
        return null;
    }

    private static Number firstNumber(Object target, String... getters) {
        for (String g : getters) {
            Object v = tryInvoke(target, g);
            if (v instanceof Number n) return n;
        }
        return null;
    }

    private static Integer parseInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return null;
        try {
            return Integer.parseInt(String.valueOf(v).replaceAll("[^0-9]", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String formatInstant(Object v) {
        Date d = asDate(v);
        return d == null ? null : d.toInstant().toString();
    }

    private static Date asDate(Object v) {
        if (v instanceof Date d) return d;
        return null;
    }

    private static String enumValue(Object v) {
        if (v == null) return null;
        if (v instanceof Enum<?> e) return e.name();
        return String.valueOf(v);
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    @SuppressWarnings("unchecked")
    private static List<?> asList(Object v) {
        return v instanceof List<?> l ? l : null;
    }

    private static Object tryInvoke(Object target, String method) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }
}
