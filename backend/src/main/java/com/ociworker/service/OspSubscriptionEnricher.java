package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.ospgateway.SubscriptionServiceClient;
import com.oracle.bmc.ospgateway.requests.GetSubscriptionRequest;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从 OSP Gateway 订阅对象提取/推导账户信息 Tab 所需字段（含反射兼容未文档化字段）。
 */
@Slf4j
final class OspSubscriptionEnricher {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Oracle 常见试用促销天数（OSP 未返回结束日时推算） */
    private static final int DEFAULT_PROMO_TRIAL_DAYS = 30;

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

        mergeFromJsonTree(sub, result);
        applyPromoTrialFallback(sub, result);
        reconcileAfterMerge(sub, result);
    }

    private static void reconcileAfterMerge(Object sub, Map<String, Object> result) {
        Date end = parseIsoDate(asString(result.get("subscriptionEndTime")));
        if (end != null && result.get("subscriptionDurationDays") == null) {
            Integer d = durationDays(tryInvoke(sub, "getTimeStart"), end);
            if (d != null) result.put("subscriptionDurationDays", d);
        }
        String status = asString(result.get("subscriptionStatus"));
        if (StrUtil.isNotBlank(status) && result.get("subscriptionStatusLabel") == null) {
            result.put("subscriptionStatusLabel", labelSubscriptionStatus(status));
        }
        String pm = asString(result.get("paymentMethod"));
        if (StrUtil.isNotBlank(pm) && result.get("paymentMethodLabel") == null) {
            result.put("paymentMethodLabel", labelPaymentMethod(pm));
        }
        Number amt = null;
        Object amountObj = result.get("subscriptionAmount");
        if (amountObj instanceof Number) {
            amt = (Number) amountObj;
        }
        if (amt != null && result.get("subscriptionAmountLabel") == null) {
            result.put("subscriptionAmountLabel", formatAmount(amt, asString(result.get("currencyCode"))));
        }
        if (StrUtil.isNotBlank(status)) {
            ResolvedStatus resolved = resolveSubscriptionStatus(sub, status, end,
                    asString(result.get("planType")), asString(result.get("upgradeState")));
            result.put("subscriptionStatus", resolved.code());
            result.put("subscriptionStatusLabel", resolved.label());
        }
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
        if (isFreeTierPlan(plan) && "PROMO".equalsIgnoreCase(upgrade)) {
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
        if ("PROMO".equalsIgnoreCase(upgradeState) || isFreeTierPlan(planType)) {
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
        if (isFreeTierPlan(plan)) return "免费套餐 (Free Tier)";
        return switch (plan.toUpperCase(Locale.ROOT)) {
            case "PAYG" -> "按量付费 (PAYG)";
            default -> plan;
        };
    }

    private static boolean isFreeTierPlan(String plan) {
        if (StrUtil.isBlank(plan)) return false;
        String p = plan.toUpperCase(Locale.ROOT).replace("_", "").replace("-", "");
        return "FREE".equals(p) || "FREETIER".equals(p);
    }

    private static void mergeFromJsonTree(Object sub, Map<String, Object> result) {
        try {
            JsonNode root = JSON.valueToTree(sub);
            scanJsonNode(root, result);
        } catch (Exception e) {
            log.debug("subscription json scan skipped: {}", e.getMessage());
        }
    }

    private static void scanJsonNode(JsonNode node, Map<String, Object> result) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                JsonNode val = e.getValue();
                String lower = key.toLowerCase(Locale.ROOT);
                if (matchesEndKey(lower)) {
                    putEndIfAbsent(result, val);
                } else if (lower.contains("paymentmethod") || "paymentType".equalsIgnoreCase(key)) {
                    putStringIfAbsent(result, "paymentMethod", textNode(val));
                } else if (lower.contains("status") && !lower.contains("upgrade")) {
                    putStringIfAbsent(result, "subscriptionStatus", textNode(val));
                } else if (isAmountKey(lower)) {
                    putAmountIfAbsent(result, val);
                } else if (lower.contains("duration") && val.isNumber()) {
                    result.putIfAbsent("subscriptionDurationDays", val.asInt());
                }
                scanJsonNode(val, result);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) scanJsonNode(child, result);
        }
    }

    private static boolean matchesEndKey(String lower) {
        return lower.equals("timeend") || lower.equals("endtime") || lower.equals("timeended")
                || lower.contains("subscriptionend") || lower.contains("promoend")
                || (lower.contains("end") && lower.contains("time"));
    }

    private static boolean isAmountKey(String lower) {
        return lower.contains("subscriptionamount") || lower.contains("promoamount")
                || lower.contains("promotionalcredit") || lower.equals("totalamount")
                || (lower.contains("amount") && !lower.contains("discount"));
    }

    private static void putEndIfAbsent(Map<String, Object> result, JsonNode val) {
        if (result.get("subscriptionEndTime") != null) return;
        String iso = parseDateIso(val);
        if (iso != null) result.put("subscriptionEndTime", iso);
    }

    private static void putAmountIfAbsent(Map<String, Object> result, JsonNode val) {
        if (result.get("subscriptionAmount") != null) return;
        if (val.isNumber()) {
            result.put("subscriptionAmount", val.numberValue());
            String cur = asString(result.get("currencyCode"));
            result.put("subscriptionAmountLabel", formatAmount(val.numberValue(), cur));
        }
    }

    private static void putStringIfAbsent(Map<String, Object> result, String key, String val) {
        if (StrUtil.isBlank(val) || result.get(key) != null) return;
        result.put(key, val);
        if ("paymentMethod".equals(key)) {
            result.put("paymentMethodLabel", labelPaymentMethod(val));
        }
        if ("subscriptionStatus".equals(key)) {
            result.put("subscriptionStatusLabel", labelSubscriptionStatus(val));
        }
    }

    private static String textNode(JsonNode val) {
        if (val == null || val.isNull()) return null;
        if (val.isTextual()) return val.asText();
        if (val.isNumber()) return val.asText();
        return null;
    }

    private static String parseDateIso(JsonNode val) {
        if (val == null || val.isNull()) return null;
        if (val.isNumber()) return formatInstant(new Date(val.asLong()));
        if (val.isTextual()) {
            try {
                return Instant.parse(val.asText()).toString();
            } catch (Exception ignored) {
                return val.asText();
            }
        }
        return null;
    }

    /** OSP 未返回结束日/额度时，按免费试用促销惯例补全（并标记 estimated） */
    private static void applyPromoTrialFallback(Object sub, Map<String, Object> result) {
        String upgrade = asString(result.get("upgradeState"));
        if (!"PROMO".equalsIgnoreCase(upgrade) && !isFreeTierPlan(asString(result.get("planType")))) {
            return;
        }
        if (result.get("paymentMethod") == null) {
            result.put("paymentMethod", "FREE_TRIAL");
            result.put("paymentMethodLabel", labelPaymentMethod("FREE_TRIAL"));
        }
        Date start = asDate(tryInvoke(sub, "getTimeStart"));
        if (result.get("subscriptionEndTime") == null && start != null) {
            Date end = Date.from(start.toInstant().plus(DEFAULT_PROMO_TRIAL_DAYS, ChronoUnit.DAYS).minus(1, ChronoUnit.SECONDS));
            result.put("subscriptionEndTime", formatInstant(end));
            result.put("subscriptionDurationDays", DEFAULT_PROMO_TRIAL_DAYS);
            result.put("subscriptionEndTimeEstimated", Boolean.TRUE);
        }
        if (result.get("subscriptionAmount") == null) {
            String currency = asString(result.get("currencyCode"));
            Number amt = defaultTrialCreditAmount(currency);
            if (amt != null) {
                result.put("subscriptionAmount", amt);
                result.put("subscriptionAmountLabel", formatAmount(amt, currency));
                result.put("subscriptionAmountEstimated", Boolean.TRUE);
            }
        }
        // 补全后刷新状态（含是否已过期）
        Date end = parseIsoDate(asString(result.get("subscriptionEndTime")));
        ResolvedStatus resolved = resolveSubscriptionStatus(sub,
                asString(result.get("subscriptionStatus")), end,
                asString(result.get("planType")), upgrade);
        result.put("subscriptionStatus", resolved.code());
        result.put("subscriptionStatusLabel", resolved.label());
        if (result.get("paymentMethod") != null && result.get("paymentMethodLabel") == null) {
            result.put("paymentMethodLabel", labelPaymentMethod(asString(result.get("paymentMethod"))));
        }
    }

    private static Number defaultTrialCreditAmount(String currency) {
        if (StrUtil.isBlank(currency)) return 300;
        return switch (currency.toUpperCase(Locale.ROOT)) {
            case "EUR" -> 250;
            case "GBP" -> 250;
            case "AUD" -> 300;
            case "USD" -> 300;
            case "JPY" -> 40000;
            case "CAD" -> 300;
            default -> 300;
        };
    }

    private static Date parseIsoDate(String iso) {
        if (StrUtil.isBlank(iso)) return null;
        try {
            return Date.from(Instant.parse(iso));
        } catch (Exception ignored) {
            return null;
        }
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
        if (v instanceof Enum<?> e) {
            Object val = tryInvoke(e, "getValue");
            if (val != null) return String.valueOf(val);
            return e.name();
        }
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
