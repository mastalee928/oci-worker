package com.ociworker.util;

import com.oracle.bmc.model.BmcException;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;

/**
 * OCI 官方 API 错误码的中文兜底翻译。
 *
 * <p>业务模块里已有的专用提示应先判断，这里只负责通用兜底，避免把实例、
 * BYOIP、审计、区域订阅等更精确的提示覆盖掉。</p>
 */
public final class OciBmcErrorTranslator {

    private static final Map<String, String> SERVICE_CODE_MESSAGES = Map.ofEntries(
            Map.entry("cannotparserequest", "请求内容无法解析，请检查参数格式。"),
            Map.entry("invalidparameter", "请求参数无效，请检查输入内容、资源 OCID、区间和区域是否匹配。"),
            Map.entry("limitexceeded", "已达到 OCI 服务限制，请稍后重试或在 OCI 控制台申请提升限制。"),
            Map.entry("missingparameter", "缺少必要参数，请检查表单内容后重试。"),
            Map.entry("quotaexceeded", "已达到 OCI 服务配额，请删除无用资源或申请提升配额。"),
            Map.entry("relatedresourcenotauthorizedornotfound", "关联资源无权限或不存在，请确认资源、区间、区域与 IAM 权限。"),
            Map.entry("notauthenticated", "OCI 认证失败，请检查 API Key、指纹、租户/用户 OCID 与服务器时间。"),
            Map.entry("notallowed", "当前操作不被允许，请确认资源状态、账号限制或服务限制。"),
            Map.entry("notauthorized", "权限不足，请检查 OCI IAM 策略。"),
            Map.entry("signuprequired", "账号尚未开通该服务，请先在 OCI 控制台完成开通。"),
            Map.entry("notauthorizedornotfound", "权限不足或资源不存在，请确认资源、区间、区域与 IAM 权限。"),
            Map.entry("notfound", "资源不存在或已被删除，请刷新后重试。"),
            Map.entry("namespacenotfound", "对象存储命名空间不存在或尚未初始化。"),
            Map.entry("methodnotallowed", "当前请求方法不被允许。"),
            Map.entry("conflict", "资源冲突或仍被占用，请先清理依赖资源或稍后重试。"),
            Map.entry("externalserverincorrectstate", "OCI 依赖服务状态不正确，请稍后重试。"),
            Map.entry("incorrectstate", "资源当前状态不允许执行该操作，请等待状态正常后重试。"),
            Map.entry("invalidatedretrytoken", "请求重试令牌已失效，请重新发起操作。"),
            Map.entry("resourcelocked", "资源已被锁定，无法修改或删除。"),
            Map.entry("notauthorizedorresourcealreadyexists", "权限不足或资源已存在，请确认 IAM 权限和资源名称。"),
            Map.entry("noetagmatch", "资源已被其他操作更新，请刷新后重试。"),
            Map.entry("payloadtoolarge", "请求内容过大，请减少提交内容后重试。"),
            Map.entry("unprocessableentity", "请求内容无法被 OCI 处理，请检查参数与资源状态。"),
            Map.entry("toomanyrequests", "请求过于频繁，请稍后重试。"),
            Map.entry("requestheaderfieldstoolarge", "请求头过大，请减少请求头内容后重试。"),
            Map.entry("internalservererror", "OCI 服务内部错误，请稍后重试。"),
            Map.entry("methodnotimplemented", "当前 OCI 接口暂不支持该操作。"),
            Map.entry("externalserverunreachable", "OCI 依赖服务暂时不可达，请稍后重试。"),
            Map.entry("externalservertimeout", "OCI 依赖服务响应超时，请稍后重试。"),
            Map.entry("externalserverinvalidresponse", "OCI 依赖服务返回异常，请稍后重试。"),
            Map.entry("serviceunavailable", "OCI 服务暂时不可用，请稍后重试。")
    );

    private static final Map<Integer, String> STATUS_MESSAGES = Map.ofEntries(
            Map.entry(400, "请求参数有误，请检查输入内容。"),
            Map.entry(401, "OCI 认证失败，请检查 API Key、指纹、租户/用户 OCID 与服务器时间。"),
            Map.entry(403, "权限不足，请检查 OCI IAM 策略。"),
            Map.entry(404, "资源不存在或无权访问，请确认资源、区间、区域与 IAM 权限。"),
            Map.entry(405, "当前请求方法不被允许。"),
            Map.entry(409, "资源冲突或状态不允许当前操作，请刷新资源状态后重试。"),
            Map.entry(412, "资源已被其他操作更新，请刷新后重试。"),
            Map.entry(413, "请求内容过大，请减少提交内容后重试。"),
            Map.entry(422, "请求内容无法被 OCI 处理，请检查参数与资源状态。"),
            Map.entry(429, "请求过于频繁，请稍后重试。"),
            Map.entry(431, "请求头过大，请减少请求头内容后重试。"),
            Map.entry(500, "OCI 服务内部错误，请稍后重试。"),
            Map.entry(501, "当前 OCI 接口暂不支持该操作。"),
            Map.entry(503, "OCI 服务暂时不可用，请稍后重试。")
    );

    private OciBmcErrorTranslator() {
    }

    public static String translate(BmcException e) {
        if (e == null) {
            return "OCI 调用失败（无详细信息）";
        }

        String raw = e.getMessage() == null ? "" : e.getMessage();
        String knownMessage = translateKnownMessage(raw);
        if (!knownMessage.isEmpty()) {
            return knownMessage;
        }

        String serviceCode = e.getServiceCode();
        String byServiceCode = translateServiceCode(serviceCode);
        if (!byServiceCode.isEmpty()) {
            return appendServiceCode(byServiceCode, serviceCode);
        }

        String byMessageServiceCode = translateServiceCodeFromMessage(raw);
        if (!byMessageServiceCode.isEmpty()) {
            return byMessageServiceCode;
        }

        String byStatus = STATUS_MESSAGES.getOrDefault(e.getStatusCode(), "");
        if (!byStatus.isEmpty()) {
            return byStatus;
        }

        String cleaned = cleanSdkMessage(raw);
        if (!cleaned.isEmpty()) {
            return cleaned.length() > 180 ? cleaned.substring(0, 180) + "..." : cleaned;
        }
        return "OCI 调用失败（HTTP " + e.getStatusCode() + "）";
    }

    public static String translate(Throwable e) {
        if (e == null) {
            return "OCI 调用失败（无详细信息）";
        }
        BmcException bmc = findBmcException(e);
        if (bmc != null) {
            return translate(bmc);
        }
        String raw = combinedMessage(e);
        String knownMessage = translateKnownMessage(raw);
        if (!knownMessage.isEmpty()) {
            return knownMessage;
        }
        String byMessageServiceCode = translateServiceCodeFromMessage(raw);
        if (!byMessageServiceCode.isEmpty()) {
            return byMessageServiceCode;
        }
        String cleaned = cleanSdkMessage(raw);
        if (!cleaned.isEmpty()) {
            return cleaned.length() > 180 ? cleaned.substring(0, 180) + "..." : cleaned;
        }
        return e.getClass().getSimpleName();
    }

    private static String translateKnownMessage(String raw) {
        String text = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (text.contains("read timed out") || text.contains("sockettimeoutexception")) {
            return "OCI 接口读取超时，请稍后自动重试。";
        }
        if (text.contains("connect timed out")) {
            return "OCI 接口连接超时，请稍后自动重试。";
        }
        if (text.contains("connection reset")) {
            return "OCI 连接被重置，请稍后自动重试。";
        }
        if (text.contains("the required information to complete authentication was not provided or was incorrect")) {
            return "OCI 认证失败，请检查 API Key、指纹、租户/用户 OCID 与服务器时间。";
        }
        if (text.contains("is disabled and will not accept any action requests")) {
            return "实例已被禁用，不会接受任何操作请求。请联系客户支持以重新启用。";
        }
        if (text.contains("cannot be resized in update_pending state")
                || text.contains("must be in state available")
                || text.contains("update_pending")) {
            return "引导卷正在更新中，请等待状态变为 AVAILABLE（可用）后再操作。";
        }
        if (text.contains("may not be deleted while attached to an instance")) {
            return "卷仍挂载在实例上，无法删除。请先分离后再删除。";
        }
        if (text.contains("out of host capacity") || text.contains("no available host")) {
            return "主机容量不足，请稍后重试，或更换区域、可用域、故障域或 Shape。";
        }
        if (text.contains("maximum number of allowed subscribed regions")
                || text.contains("tenantcapacityexceeded")) {
            return "您已超出允许订阅区域的最大数量。请参阅“限制、配额和使用情况”页面了解更多详情。";
        }
        if (text.contains("invalid ratio of memory in gb to ocpus")) {
            return "当前 Shape 的 OCPU/内存比例无效，或账号没有对应 OCPU/内存配额。";
        }
        if (text.contains("nat gateway limit per vcn reached")) {
            return "已达到每个 VCN 的 NAT 网关数量限制";
        }
        return "";
    }

    private static String translateServiceCode(String serviceCode) {
        if (serviceCode == null || serviceCode.isBlank()) {
            return "";
        }
        return SERVICE_CODE_MESSAGES.getOrDefault(serviceCode.trim().toLowerCase(Locale.ROOT), "");
    }

    private static String translateServiceCodeFromMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return SERVICE_CODE_MESSAGES.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed())
                .filter(entry -> lower.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
    }

    private static String appendServiceCode(String message, String serviceCode) {
        if (serviceCode == null || serviceCode.isBlank()) {
            return message;
        }
        return message + "（" + serviceCode.trim() + "）";
    }

    private static BmcException findBmcException(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof BmcException bmc) {
                return bmc;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private static String combinedMessage(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && !msg.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(": ");
                }
                sb.append(msg.trim());
            }
            cur = cur.getCause();
        }
        if (!sb.isEmpty()) {
            return sb.toString();
        }
        return e.getClass().getSimpleName();
    }

    private static String cleanSdkMessage(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        s = s.replaceAll("(?s)\\s*Timestamp:.*$", "");
        s = s.replaceAll("(?s)\\s*Client version:.*$", "");
        s = s.replaceAll("(?s)\\s*Request Endpoint:.*$", "");
        s = s.replaceAll("(?s)\\s*Additional Properties in error response:.*$", "");
        s = s.replaceAll("(?s)\\s*Troubleshooting Tips:.*$", "");
        s = s.replaceAll("\\s*\\(opc-request-id:.*?\\)", "");
        s = s.replaceFirst("^\\(-?\\d+,\\s*[^,()]*,\\s*(?:true|false)\\)\\s*", "");
        return s.trim();
    }
}
