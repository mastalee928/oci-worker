package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.ospgateway.SubscriptionServiceClient;
import com.oracle.bmc.ospgateway.requests.GetSubscriptionRequest;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从 OSP Gateway 订阅对象提取账户信息 Tab 字段（反射 + JSON 扫描）；无 API 数据则不填、不推算。
 */
@Slf4j
final class OspSubscriptionEnricher {

    private static final ObjectMapper JSON = new ObjectMapper();

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

    /** 从 OSP Gateway 原始 JSON 提取字段（优先于 SDK 对象，不推算）。 */
    static void enrichFromRawJson(JsonNode root, Map<String, Object> result) {
        if (root == null || root.isNull() || result == null) {
            return;
        }
        JsonNode sub = root;
        if (!sub.has("id") && !sub.has("planType") && !sub.has("timeStart")) {
            JsonNode inner = root.get("subscription");
            if (inner != null && !inner.isNull()) {
                sub = inner;
            } else if (root.has("items") && root.get("items").isArray() && !root.get("items").isEmpty()) {
                sub = root.get("items").get(0);
            }
        }
        scanJsonNode(sub, result);
        applySubscriptionIdentifiers(result, sub, null);
        collectOspRewardSubscriptionOcids(sub, null, result);
        reconcileAfterMerge(null, result);
    }

    /** Assigned / Rewards 的 organizationssubscription 与 OSP 计费订阅 OCID 不是同一类。 */
    static boolean isOrganizationsSubscriptionOcid(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        return value.trim().toLowerCase(Locale.ROOT).contains("organizationssubscription");
    }

    /** Rewards / Organizations 等接口需要 ocid1.*；OSP 列表里的 id 可能是订阅编号。 */
    static boolean isOciOcid(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        return value.trim().toLowerCase(Locale.ROOT).startsWith("ocid1.");
    }

    static void applySubscriptionIdentifiers(Map<String, Object> result, JsonNode sub, Object sdkObj) {
        if (result == null) {
            return;
        }
        String jsonId = sub != null && sub.hasNonNull("id") ? sub.get("id").asText() : null;
        String planNum = sub != null && sub.hasNonNull("subscriptionPlanNumber")
                ? sub.get("subscriptionPlanNumber").asText() : null;
        if (sdkObj != null) {
            if (StrUtil.isBlank(planNum)) {
                planNum = asString(tryInvoke(sdkObj, "getSubscriptionPlanNumber"));
            }
            if (StrUtil.isBlank(jsonId)) {
                jsonId = asString(tryInvoke(sdkObj, "getId"));
            }
        }
        if (StrUtil.isNotBlank(planNum)) {
            result.put("subscriptionPlanNumber", planNum.trim());
        }
        if (StrUtil.isNotBlank(jsonId)) {
            result.put("subscriptionOspRef", jsonId.trim());
            if (isOciOcid(jsonId) && !isOrganizationsSubscriptionOcid(jsonId)) {
                result.put("subscriptionOspOcid", jsonId.trim());
            }
        }
    }

    /** 从 OSP 原始 JSON / SDK 收集可能用于 Usage Rewards 的计费订阅 OCID（不含 organizationssubscription）。 */
    static void collectOspRewardSubscriptionOcids(JsonNode raw, Object sdkObj, Map<String, Object> result) {
        if (result == null) {
            return;
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (raw != null && !raw.isNull()) {
            collectOcidsFromJsonNode(raw, ids);
        }
        collectOcidsFromSdkObject(sdkObj, ids);
        Object ospOcid = result.get("subscriptionOspOcid");
        if (ospOcid != null && isOciOcid(String.valueOf(ospOcid))) {
            ids.add(String.valueOf(ospOcid).trim());
        }
        if (!ids.isEmpty()) {
            result.put("ospRewardSubscriptionOcids", new ArrayList<>(ids));
        }
    }

    private static void collectOcidsFromJsonNode(JsonNode node, Set<String> ids) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String t = node.asText();
            if (isOciOcid(t) && !isOrganizationsSubscriptionOcid(t)) {
                ids.add(t.trim());
            }
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                collectOcidsFromJsonNode(entry.getValue(), ids);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectOcidsFromJsonNode(child, ids);
            }
        }
    }

    private static void collectOcidsFromSdkObject(Object sdkObj, Set<String> ids) {
        if (sdkObj == null) {
            return;
        }
        for (String getter : List.of(
                "getId", "getSubscriptionId", "getClassicSubscriptionId", "getBillingSubscriptionId")) {
            String v = asString(tryInvoke(sdkObj, getter));
            if (isOciOcid(v) && !isOrganizationsSubscriptionOcid(v)) {
                ids.add(v.trim());
            }
        }
    }

    static void enrich(Object sub, Map<String, Object> result) {
        if (sub == null || result == null) return;

        String planVal = enumValue(tryInvoke(sub, "getPlanType"));
        putIfAbsent(result, "planType", planVal);
        putIfAbsent(result, "planTypeLabel", labelPlanType(planVal));
        putIfAbsent(result, "accountType", enumValue(tryInvoke(sub, "getAccountType")));
        String upgrade = enumValue(tryInvoke(sub, "getUpgradeState"));
        putIfAbsent(result, "upgradeState", upgrade);
        putIfAbsent(result, "upgradeStateLabel", labelUpgradeState(upgrade));
        putIfAbsent(result, "currencyCode", asString(tryInvoke(sub, "getCurrencyCode")));
        putIfAbsent(result, "isIntentToPay", tryInvoke(sub, "getIsIntentToPay"));
        putIfAbsent(result, "subscriptionStartTime", formatInstant(tryInvoke(sub, "getTimeStart")));

        Date timeEnd = firstDate(sub,
                "getTimeEnd", "getTimeEnded", "getEndTime", "getSubscriptionEndTime", "getPromoEndTime");
        putIfAbsent(result, "subscriptionEndTime", formatInstant(timeEnd));

        Integer durationDays = durationDays(tryInvoke(sub, "getTimeStart"), timeEnd);
        if (durationDays == null) {
            Object dur = tryInvoke(sub, "getDurationDays");
            if (dur == null) dur = tryInvoke(sub, "getDuration");
            durationDays = parseInt(dur);
        }
        putIfAbsent(result, "subscriptionDurationDays", durationDays);

        String paymentMethod = resolvePaymentMethod(sub);
        putIfAbsent(result, "paymentMethod", paymentMethod);
        putIfAbsent(result, "paymentMethodLabel", labelPaymentMethod(paymentMethod));

        Number amount = resolveSubscriptionAmount(sub);
        putIfAbsent(result, "subscriptionAmount", amount);
        String currency = asString(tryInvoke(sub, "getCurrencyCode"));
        if (result.get("subscriptionAmountLabel") == null) {
            putIfAbsent(result, "subscriptionAmountLabel", formatAmount(amount, currency));
        }

        String rawStatus = firstString(sub,
                "getStatus", "getSubscriptionStatus", "getLifecycleState", "getState");
        if (result.get("subscriptionStatus") == null) {
            ResolvedStatus resolved = resolveSubscriptionStatus(rawStatus, timeEnd);
            putIfAbsent(result, "subscriptionStatus", resolved.code());
            putIfAbsent(result, "subscriptionStatusLabel", resolved.label());
        }

        mergeFromJsonTree(sub, result);
        try {
            applySubscriptionIdentifiers(result, JSON.valueToTree(sub), sub);
            collectOspRewardSubscriptionOcids(JSON.valueToTree(sub), sub, result);
        } catch (Exception ignored) {
            applySubscriptionIdentifiers(result, null, sub);
            collectOspRewardSubscriptionOcids(null, sub, result);
        }
        reconcileAfterMerge(sub, result);
    }

    private static void putIfAbsent(Map<String, Object> result, String key, Object value) {
        if (value == null || result == null) {
            return;
        }
        if (value instanceof String s && StrUtil.isBlank(s)) {
            return;
        }
        result.putIfAbsent(key, value);
    }

    private static void reconcileAfterMerge(Object sub, Map<String, Object> result) {
        Date end = parseIsoDate(asString(result.get("subscriptionEndTime")));
        if (end != null && result.get("subscriptionDurationDays") == null) {
            Date start = parseIsoDate(asString(result.get("subscriptionStartTime")));
            if (start == null && sub != null) {
                start = asDate(tryInvoke(sub, "getTimeStart"));
            }
            Integer d = durationDays(start, end);
            if (d != null) {
                result.putIfAbsent("subscriptionDurationDays", d);
            }
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
        if (StrUtil.isBlank(status)) {
            ResolvedStatus resolved = resolveSubscriptionStatus(null, end);
            if (resolved.code() != null) {
                result.putIfAbsent("subscriptionStatus", resolved.code());
                result.putIfAbsent("subscriptionStatusLabel", resolved.label());
            }
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

    /** 仅使用 API/JSON 中的 status；若无 status 仅有结束日，则按结束日判断已过期/有效（结束日本身须来自 OSP）。 */
    private static ResolvedStatus resolveSubscriptionStatus(String rawStatus, Date timeEnd) {
        if (StrUtil.isNotBlank(rawStatus)) {
            return new ResolvedStatus(rawStatus.toUpperCase(Locale.ROOT), labelSubscriptionStatus(rawStatus));
        }
        if (timeEnd != null) {
            if (timeEnd.toInstant().isBefore(Instant.now())) {
                return new ResolvedStatus("EXPIRED", "已过期");
            }
            return new ResolvedStatus("ACTIVE", "有效");
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
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                String key = e.getKey();
                JsonNode val = e.getValue();
                String lower = key.toLowerCase(Locale.ROOT);
                if (matchesEndKey(lower)) {
                    putEndIfAbsent(result, val);
                } else if (matchesStartKey(lower)) {
                    putStartIfAbsent(result, val);
                } else if (lower.equals("subscriptionplannumber")) {
                    putStringIfAbsent(result, "subscriptionPlanNumber", textNode(val));
                } else if (lower.equals("plantype")) {
                    putStringIfAbsent(result, "planType", textNode(val));
                    putStringIfAbsent(result, "planTypeLabel", labelPlanType(textNode(val)));
                } else if (lower.equals("upgradestate")) {
                    putStringIfAbsent(result, "upgradeState", textNode(val));
                    putStringIfAbsent(result, "upgradeStateLabel", labelUpgradeState(textNode(val)));
                } else if (lower.contains("paymentmethod") || "paymenttype".equals(lower)) {
                    putStringIfAbsent(result, "paymentMethod", textNode(val));
                } else if (isSubscriptionStatusKey(lower)) {
                    putStringIfAbsent(result, "subscriptionStatus", textNode(val));
                } else if (isAmountKey(lower)) {
                    putAmountIfAbsent(result, val);
                } else if (lower.contains("duration")) {
                    putDurationIfAbsent(result, val);
                } else if (lower.equals("currencycode")) {
                    putStringIfAbsent(result, "currencyCode", textNode(val));
                } else if (lower.equals("isintenttopay")) {
                    if (val.isBoolean()) {
                        result.putIfAbsent("isIntentToPay", val.asBoolean());
                    }
                } else if (lower.contains("renew") && lower.contains("time")) {
                    putEndIfAbsent(result, val, "subscriptionRenewTime");
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
                || (lower.contains("end") && lower.contains("time") && !lower.contains("renew"));
    }

    private static boolean matchesStartKey(String lower) {
        return lower.equals("timestart") || lower.equals("starttime")
                || (lower.contains("start") && lower.contains("time") && !lower.contains("restart"));
    }

    private static boolean isSubscriptionStatusKey(String lower) {
        if (lower.contains("upgrade")) {
            return false;
        }
        return lower.equals("status") || lower.equals("subscriptionstatus")
                || lower.equals("lifecyclestate") || lower.equals("state");
    }

    private static void putStartIfAbsent(Map<String, Object> result, JsonNode val) {
        if (result.get("subscriptionStartTime") != null) {
            return;
        }
        String iso = parseDateIso(val);
        if (iso != null) {
            result.put("subscriptionStartTime", iso);
        }
    }

    private static void putDurationIfAbsent(Map<String, Object> result, JsonNode val) {
        if (result.get("subscriptionDurationDays") != null) {
            return;
        }
        if (val.isNumber()) {
            result.put("subscriptionDurationDays", val.asInt());
            return;
        }
        String text = textNode(val);
        if (StrUtil.isNotBlank(text)) {
            Integer days = parseDurationDaysFromApiText(text);
            if (days != null) {
                result.put("subscriptionDurationDays", days);
            }
        }
    }

    /** 解析 API 返回的持续时间文案（如 "30 DAY"），非推算。 */
    private static Integer parseDurationDaysFromApiText(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        String t = text.trim().toUpperCase(Locale.ROOT);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*DAY").matcher(t);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return parseInt(text);
    }

    private static void putEndIfAbsent(Map<String, Object> result, JsonNode val, String targetKey) {
        if (result.get(targetKey) != null) {
            return;
        }
        String iso = parseDateIso(val);
        if (iso != null) {
            result.put(targetKey, iso);
        }
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
        Date start = asDate(startObj);
        return durationDays(start, end);
    }

    private static Integer durationDays(Date start, Date end) {
        if (start == null || end == null) {
            return null;
        }
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
