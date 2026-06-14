package com.ociworker.service;

import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.audit.AuditClient;
import com.oracle.bmc.audit.model.AuditEvent;
import com.oracle.bmc.audit.requests.ListEventsRequest;
import com.oracle.bmc.identity.requests.ListDomainsRequest;
import com.oracle.bmc.identitydomains.IdentityDomainsClient;
import com.oracle.bmc.identitydomains.model.*;
import com.oracle.bmc.identitydomains.requests.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
public class DomainManagementService {

    private static final String OCI_CONSOLE_POLICY_ID = "OciConsolePolicy";
    private static final String DEFAULT_PASSWORD_POLICY_NAME = "DefaultPasswordPolicy";
    private static final String CONSENT_SCHEMA =
            "urn:ietf:params:scim:schemas:oracle:idcs:extension:ociconsolesignonpolicyconsent:Policy";
    private static final long AUDIT_QUERY_BUDGET_MS = 35_000L;
    private static final int AUDIT_READ_TIMEOUT_MS = 15_000;
    private static final int AUDIT_MAX_PAGES_PER_REGION = 8;

    @Resource
    private OciUserMapper userMapper;

    @Resource
    private VerifyCodeService verifyCodeService;

    /** token -> expireAt（验证因素 Tab 解锁后 10 分钟内可读写） */
    private static final java.util.Map<String, Long> AUTH_FACTOR_TOKENS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long AUTH_FACTOR_TOKEN_TTL_MS = 10 * 60 * 1000L;
    /** token -> expireAt（域通知 Tab 解锁后 10 分钟内可读写） */
    private static final java.util.Map<String, Long> DOMAIN_NOTIFICATION_TOKENS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DOMAIN_NOTIFICATION_TOKEN_TTL_MS = 10 * 60 * 1000L;

    /** 以 TG 验证码换取短期 accessToken */
    public String unlockAuthFactors(String inputCode) {
        if (inputCode == null || inputCode.isBlank()) throw new OciException("请输入验证码");
        verifyCodeService.verifyCode("authFactors", inputCode);
        long now = System.currentTimeMillis();
        AUTH_FACTOR_TOKENS.entrySet().removeIf(e -> e.getValue() < now);
        String token = java.util.UUID.randomUUID().toString();
        AUTH_FACTOR_TOKENS.put(token, now + AUTH_FACTOR_TOKEN_TTL_MS);
        return token;
    }

    private void requireAuthFactorToken(String token) {
        if (token == null || token.isBlank()) throw new OciException("会话未解锁，请先通过 TG 验证码解锁");
        Long exp = AUTH_FACTOR_TOKENS.get(token);
        if (exp == null) throw new OciException("会话已失效，请重新解锁");
        if (System.currentTimeMillis() > exp) {
            AUTH_FACTOR_TOKENS.remove(token);
            throw new OciException("会话已过期，请重新解锁");
        }
    }

    /** 以 TG 验证码换取域通知短期 accessToken */
    public String unlockDomainNotifications(String inputCode) {
        if (inputCode == null || inputCode.isBlank()) throw new OciException("请输入验证码");
        verifyCodeService.verifyCode("domainNotifications", inputCode);
        long now = System.currentTimeMillis();
        DOMAIN_NOTIFICATION_TOKENS.entrySet().removeIf(e -> e.getValue() < now);
        String token = java.util.UUID.randomUUID().toString();
        DOMAIN_NOTIFICATION_TOKENS.put(token, now + DOMAIN_NOTIFICATION_TOKEN_TTL_MS);
        return token;
    }

    private void requireDomainNotificationToken(String token) {
        if (token == null || token.isBlank()) throw new OciException("域通知会话未解锁，请先通过 TG 验证码解锁");
        Long exp = DOMAIN_NOTIFICATION_TOKENS.get(token);
        if (exp == null) throw new OciException("域通知会话已失效，请重新解锁");
        if (System.currentTimeMillis() > exp) {
            DOMAIN_NOTIFICATION_TOKENS.remove(token);
            throw new OciException("域通知会话已过期，请重新解锁");
        }
    }

    private OciClientService buildClient(String tenantId) {
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");
        return new OciClientService(SysUserDTO.builder()
                .username(user.getUsername())
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(user.getOciTenantId())
                        .userId(user.getOciUserId())
                        .fingerprint(user.getOciFingerprint())
                        .region(user.getOciRegion())
                        .privateKeyPath(user.getOciKeyPath())
                        .build())
                .build());
    }

    /**
     * List all Identity Domains of the tenancy（含分页），按 Default → OracleIdentityCloudService → 其它 排序。
     *
     * @param suppressErrors true 时失败返回空列表（兼容旧接口）；false 时抛出 {@link OciException}
     */
    public List<Map<String, Object>> listDomains(OciClientService client, boolean suppressErrors) {
        List<Map<String, Object>> domains = new ArrayList<>();
        try {
            var identityClient = client.getIdentityClient();
            String tenancyId = client.getProvider().getTenantId();
            String page = null;
            do {
                var req = ListDomainsRequest.builder()
                        .compartmentId(tenancyId)
                        .limit(1000);
                if (page != null) req.page(page);
                var resp = identityClient.listDomains(req.build());
                for (var d : resp.getItems()) {
                    if (d.getUrl() == null) continue;
                    String state = d.getLifecycleState() == null ? null : d.getLifecycleState().getValue();
                    // 跳过删除中/已删除的域，但保留 INACTIVE（仍可查阅配置）
                    if ("DELETING".equalsIgnoreCase(state) || "DELETED".equalsIgnoreCase(state)) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", d.getId());
                    m.put("displayName", d.getDisplayName());
                    m.put("type", d.getType());
                    m.put("url", d.getUrl());
                    m.put("lifecycleState", state);
                    m.put("isHiddenOnLogin", d.getIsHiddenOnLogin());
                    domains.add(m);
                }
                page = resp.getOpcNextPage();
            } while (page != null && !page.isEmpty());
            // 排序：Default → OracleIdentityCloudService → 其它（按名字）
            domains.sort((a, b) -> domainRank(a) - domainRank(b) != 0
                    ? domainRank(a) - domainRank(b)
                    : String.valueOf(a.get("displayName")).compareToIgnoreCase(String.valueOf(b.get("displayName"))));
            if (log.isInfoEnabled()) {
                StringBuilder sb = new StringBuilder();
                for (var d : domains) sb.append("[").append(d.get("displayName")).append("/").append(d.get("type")).append("] ");
                log.info("Identity Domains found: {}", sb);
            }
        } catch (Exception e) {
            log.warn("Failed to list domains: {}", e.getMessage());
            if (!suppressErrors) {
                throw new OciException("列出 Identity Domain 失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        }
        return domains;
    }

    /**
     * 供创建用户等场景：严格列出域；失败抛错（与 Oracle IAM API 列出域行为一致）。
     */
    public List<Map<String, Object>> listIdentityDomains(String tenantId) {
        try (OciClientService c = buildClient(tenantId)) {
            return listDomains(c, false);
        }
    }

    /** 打开租户 OCI 客户端（调用方负责 try-with-resources 关闭）。 */
    public OciClientService openOciClient(String tenantId) {
        return buildClient(tenantId);
    }

    private int domainRank(Map<String, Object> d) {
        String name = String.valueOf(d.get("displayName"));
        if ("Default".equals(name)) return 0;
        if ("OracleIdentityCloudService".equalsIgnoreCase(name)) return 1;
        return 2;
    }

    private IdentityDomainsClient newDomainClient(OciClientService client, String domainUrl) {
        var c = IdentityDomainsClient.builder().build(client.getProvider());
        c.setEndpoint(domainUrl);
        return c;
    }

    // ---------------- Domain-level aggregation ----------------

    /**
     * Returns settings for all domains. Shape:
     * { domains: [ { domainId, displayName, type, mfaEnabled, consolePolicyId, passwordExpiresAfterDays, passwordPolicyId } ] }
     */
    public Map<String, Object> getDomainSettings(String tenantId) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> domainResults = new ArrayList<>();
        try (OciClientService client = buildClient(tenantId)) {
            var domains = listDomains(client, true);
            if (domains.isEmpty()) {
                throw new OciException("未找到 Identity Domain");
            }
            for (var d : domains) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("domainId", d.get("id"));
                r.put("displayName", d.get("displayName"));
                r.put("type", d.get("type"));

                IdentityDomainsClient dc = null;
                try {
                    dc = newDomainClient(client, (String) d.get("url"));
                    // ---- MFA via OciConsolePolicy ----
                    Map<String, Object> mfa = fetchOciConsolePolicy(dc);
                    r.putAll(mfa);
                    // ---- Password policy (DefaultPasswordPolicy) ----
                    Map<String, Object> pwd = fetchDefaultPasswordPolicy(dc);
                    r.putAll(pwd);
                } catch (Exception e) {
                    log.warn("Domain {} settings fetch failed: {}", d.get("displayName"), e.getMessage());
                    r.put("error", e.getMessage());
                } finally {
                    if (dc != null) { try { dc.close(); } catch (Exception ignored) {} }
                }

                domainResults.add(r);
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("获取域设置失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
        result.put("domains", domainResults);
        return result;
    }

    private Map<String, Object> fetchOciConsolePolicy(IdentityDomainsClient dc) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            var resp = dc.getPolicy(GetPolicyRequest.builder()
                    .policyId(OCI_CONSOLE_POLICY_ID).build());
            Policy p = resp.getPolicy();
            r.put("consolePolicyId", p.getId());
            r.put("consolePolicyName", p.getName());
            r.put("mfaEnabled", Boolean.TRUE.equals(p.getActive()));
            r.put("consolePolicyDescription", p.getDescription());
        } catch (Exception e) {
            // Fallback: try listing by name
            try {
                var lr = dc.listPolicies(ListPoliciesRequest.builder()
                        .filter("name eq \"" + OCI_CONSOLE_POLICY_ID + "\"")
                        .count(1).build());
                var items = lr.getPolicies().getResources();
                if (items != null && !items.isEmpty()) {
                    Policy p = items.get(0);
                    r.put("consolePolicyId", p.getId());
                    r.put("consolePolicyName", p.getName());
                    r.put("mfaEnabled", Boolean.TRUE.equals(p.getActive()));
                    r.put("consolePolicyDescription", p.getDescription());
                } else {
                    r.put("mfaEnabled", null);
                    r.put("mfaError", "未找到 Security Policy for OCI Console（该租户可能未启用 Identity Domain 新版签名策略）");
                }
            } catch (Exception ee) {
                log.warn("Fallback listPolicies failed: {}", ee.getMessage());
                r.put("mfaEnabled", null);
                r.put("mfaError", ee.getMessage());
            }
        }
        return r;
    }

    private Map<String, Object> fetchDefaultPasswordPolicy(IdentityDomainsClient dc) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            var resp = dc.listPasswordPolicies(ListPasswordPoliciesRequest.builder()
                    .filter("name eq \"" + DEFAULT_PASSWORD_POLICY_NAME + "\"")
                    .count(1).build());
            var list = resp.getPasswordPolicies().getResources();
            PasswordPolicy pp = null;
            if (list != null && !list.isEmpty()) {
                pp = list.get(0);
            } else {
                // Fallback: take first by priority
                var any = dc.listPasswordPolicies(ListPasswordPoliciesRequest.builder()
                        .sortBy("priority").sortOrder(SortOrder.Ascending).count(1).build());
                var anyItems = any.getPasswordPolicies().getResources();
                if (anyItems != null && !anyItems.isEmpty()) pp = anyItems.get(0);
            }
            if (pp != null) {
                r.put("passwordPolicyId", pp.getId());
                r.put("passwordPolicyName", pp.getName());
                r.put("passwordExpiresAfterDays", pp.getPasswordExpiresAfter());
                r.put("passwordPolicyPriority", pp.getPriority());
            }
        } catch (Exception e) {
            log.warn("fetchDefaultPasswordPolicy failed: {}", e.getMessage());
            r.put("passwordPolicyError", e.getMessage());
        }
        return r;
    }

    // ---------------- Update MFA (OciConsolePolicy active) ----------------

    public void updateMfaSetting(String tenantId, String domainId, boolean enabled) {
        try (OciClientService client = buildClient(tenantId)) {
            var domains = listDomains(client, true);
            var target = findDomain(domains, domainId);
            try (IdentityDomainsClient dc = newDomainClient(client, (String) target.get("url"))) {
                List<Operations> ops = new ArrayList<>();
                ops.add(Operations.builder()
                        .op(Operations.Op.Replace)
                        .path("active")
                        .value(enabled)
                        .build());
                // Require consent to deviate from Oracle defaults
                ops.add(Operations.builder()
                        .op(Operations.Op.Replace)
                        .path(CONSENT_SCHEMA + ":consent")
                        .value(true)
                        .build());
                ops.add(Operations.builder()
                        .op(Operations.Op.Replace)
                        .path(CONSENT_SCHEMA + ":justification")
                        .value(enabled ? "MFA enabled via oci-worker" : "MFA disabled via oci-worker")
                        .build());

                PatchOp patch = PatchOp.builder()
                        .schemas(List.of("urn:ietf:params:scim:api:messages:2.0:PatchOp"))
                        .operations(ops)
                        .build();

                dc.patchPolicy(PatchPolicyRequest.builder()
                        .policyId(OCI_CONSOLE_POLICY_ID)
                        .patchOp(patch).build());

                log.info("OciConsolePolicy active={} for tenant={} domain={}", enabled, tenantId, target.get("displayName"));
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("更新 MFA 策略失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    // ---------------- Update password expiry ----------------

    public void updatePasswordExpiry(String tenantId, String domainId, int days) {
        try (OciClientService client = buildClient(tenantId)) {
            var domains = listDomains(client, true);
            var target = findDomain(domains, domainId);
            try (IdentityDomainsClient dc = newDomainClient(client, (String) target.get("url"))) {
                var list = dc.listPasswordPolicies(ListPasswordPoliciesRequest.builder()
                        .filter("name eq \"" + DEFAULT_PASSWORD_POLICY_NAME + "\"").count(1).build())
                        .getPasswordPolicies().getResources();
                PasswordPolicy existing = null;
                if (list != null && !list.isEmpty()) existing = list.get(0);
                if (existing == null) {
                    var any = dc.listPasswordPolicies(ListPasswordPoliciesRequest.builder()
                            .sortBy("priority").sortOrder(SortOrder.Ascending).count(1).build())
                            .getPasswordPolicies().getResources();
                    if (any != null && !any.isEmpty()) existing = any.get(0);
                }
                if (existing == null) throw new OciException("未找到密码策略（DefaultPasswordPolicy）");

                List<Operations> ops = new ArrayList<>();
                ops.add(Operations.builder()
                        .op(Operations.Op.Replace)
                        .path("passwordExpiresAfter")
                        .value(days)
                        .build());
                PatchOp patch = PatchOp.builder()
                        .schemas(List.of("urn:ietf:params:scim:api:messages:2.0:PatchOp"))
                        .operations(ops).build();
                dc.patchPasswordPolicy(PatchPasswordPolicyRequest.builder()
                        .passwordPolicyId(existing.getId())
                        .patchOp(patch).build());

                log.info("passwordExpiresAfter={} days for tenant={} domain={} policy={}",
                        days, tenantId, target.get("displayName"), existing.getName());
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("更新密码策略失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    private Map<String, Object> findDomain(List<Map<String, Object>> domains, String domainId) {
        if (domains == null || domains.isEmpty()) throw new OciException("未找到 Identity Domain");
        if (domainId == null || domainId.isBlank()) {
            // Default to first DEFAULT domain, fallback to first
            for (var d : domains) if ("DEFAULT".equalsIgnoreCase(String.valueOf(d.get("type")))) return d;
            return domains.get(0);
        }
        for (var d : domains) if (domainId.equals(d.get("id"))) return d;
        throw new OciException("未找到指定 domain: " + domainId);
    }

    // ---------------- Notification Settings ----------------

    public Map<String, Object> getNotificationSettings(String tenantId, String domainId, String token) {
        requireDomainNotificationToken(token);
        try (OciClientService client = buildClient(tenantId)) {
            var domains = listDomains(client, true);
            var target = findDomain(domains, domainId);
            try (IdentityDomainsClient dc = newDomainClient(client, (String) target.get("url"))) {
                return notificationSettingToMap(target, firstNotificationSetting(dc));
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("读取域通知设置失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    public Map<String, Object> updateNotificationSettings(String tenantId, String domainId, String token, Map<String, Object> payload) {
        requireDomainNotificationToken(token);
        try (OciClientService client = buildClient(tenantId)) {
            var domains = listDomains(client, true);
            var target = findDomain(domains, domainId);
            try (IdentityDomainsClient dc = newDomainClient(client, (String) target.get("url"))) {
                NotificationSetting current = firstNotificationSetting(dc);
                NotificationSetting.Builder builder = current.toBuilder();
                int changed = 0;

                changed += replaceBoolean(payload, "notificationEnabled", current.getNotificationEnabled(), builder::notificationEnabled);
                changed += replaceBoolean(payload, "testModeEnabled", current.getTestModeEnabled(), builder::testModeEnabled);
                changed += replaceBoolean(payload,
                        "sendNotificationToOldAndNewPrimaryEmailsWhenAdminChangesPrimaryEmail",
                        current.getSendNotificationToOldAndNewPrimaryEmailsWhenAdminChangesPrimaryEmail(),
                        builder::sendNotificationToOldAndNewPrimaryEmailsWhenAdminChangesPrimaryEmail);

                if (payload != null && payload.containsKey("testRecipients")) {
                    List<String> next = normalizeStringList(payload.get("testRecipients"));
                    if (!Objects.equals(next, current.getTestRecipients())) {
                        builder.testRecipients(next);
                        changed++;
                    }
                }

                if (payload != null && payload.get("fromEmailAddress") instanceof Map<?, ?> rawFromEmail) {
                    NotificationSettingsFromEmailAddress currentEmail = current.getFromEmailAddress();
                    NotificationSettingsFromEmailAddress.Builder emailBuilder = currentEmail == null
                            ? NotificationSettingsFromEmailAddress.builder()
                            : currentEmail.toBuilder();
                    int emailChanged = 0;

                    if (rawFromEmail.containsKey("value")) {
                        String value = trimToNull(rawFromEmail.get("value"));
                        if (value == null) throw new OciException("发件人电子邮件地址不能为空");
                        if (currentEmail == null || !Objects.equals(value, currentEmail.getValue())) {
                            emailBuilder.value(value);
                            emailChanged++;
                        }
                    }
                    if (rawFromEmail.containsKey("displayName")) {
                        String displayName = trimToNull(rawFromEmail.get("displayName"));
                        if (currentEmail == null || !Objects.equals(displayName, currentEmail.getDisplayName())) {
                            emailBuilder.displayName(displayName);
                            emailChanged++;
                        }
                    }
                    if (rawFromEmail.containsKey("validate")) {
                        String validateRaw = trimToNull(rawFromEmail.get("validate"));
                        NotificationSettingsFromEmailAddress.Validate validate = validateRaw == null
                                ? null
                                : NotificationSettingsFromEmailAddress.Validate.create(validateRaw);
                        if (currentEmail == null || !Objects.equals(validate, currentEmail.getValidate())) {
                            emailBuilder.validate(validate);
                            emailChanged++;
                        }
                    }
                    if (emailChanged > 0) {
                        builder.fromEmailAddress(emailBuilder.build());
                        changed += emailChanged;
                    }
                }

                if (payload != null && payload.get("eventSettings") instanceof List<?> rawEvents) {
                    List<NotificationSettingsEventSettings> nextEvents = normalizeEventSettings(rawEvents, current.getEventSettings());
                    if (!eventSettingsEqual(nextEvents, current.getEventSettings())) {
                        builder.eventSettings(nextEvents);
                        changed++;
                    }
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("domainId", target.get("id"));
                result.put("displayName", target.get("displayName"));
                result.put("changedOps", changed);
                if (changed == 0) {
                    result.put("skipped", true);
                    return result;
                }

                var resp = dc.putNotificationSetting(PutNotificationSettingRequest.builder()
                        .notificationSettingId(current.getId())
                        .notificationSetting(builder.build())
                        .build());
                result.put("notification", notificationSettingToMap(target, resp.getNotificationSetting()));
                log.info("NotificationSetting put: tenant={} domain={} changedFields={}",
                        tenantId, target.get("displayName"), changed);
                return result;
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("更新域通知设置失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    private NotificationSetting firstNotificationSetting(IdentityDomainsClient dc) {
        var resp = dc.listNotificationSettings(ListNotificationSettingsRequest.builder().limit(1).build());
        var items = resp.getNotificationSettings() == null
                ? null : resp.getNotificationSettings().getResources();
        if (items == null || items.isEmpty()) throw new OciException("未找到 NotificationSettings");
        NotificationSetting listed = items.get(0);
        if (listed.getId() == null || listed.getId().isBlank()) return listed;
        return dc.getNotificationSetting(GetNotificationSettingRequest.builder()
                .notificationSettingId(listed.getId())
                .build()).getNotificationSetting();
    }

    private Map<String, Object> notificationSettingToMap(Map<String, Object> domain, NotificationSetting setting) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domainId", domain.get("id"));
        r.put("displayName", domain.get("displayName"));
        r.put("type", domain.get("type"));
        r.put("settingId", setting.getId());
        r.put("ocid", setting.getOcid());
        r.put("domainOcid", setting.getDomainOcid());
        r.put("compartmentOcid", setting.getCompartmentOcid());
        r.put("tenancyOcid", setting.getTenancyOcid());
        r.put("notificationEnabled", Boolean.TRUE.equals(setting.getNotificationEnabled()));
        r.put("testModeEnabled", Boolean.TRUE.equals(setting.getTestModeEnabled()));
        r.put("testRecipients", setting.getTestRecipients() == null ? List.of() : setting.getTestRecipients());
        r.put("sendNotificationToOldAndNewPrimaryEmailsWhenAdminChangesPrimaryEmail",
                Boolean.TRUE.equals(setting.getSendNotificationToOldAndNewPrimaryEmailsWhenAdminChangesPrimaryEmail()));

        Map<String, Object> email = new LinkedHashMap<>();
        var from = setting.getFromEmailAddress();
        if (from != null) {
            email.put("value", from.getValue());
            email.put("displayName", from.getDisplayName());
            email.put("validate", from.getValidate() == null ? null : from.getValidate().getValue());
            email.put("validationStatus", from.getValidationStatus() == null ? null : from.getValidationStatus().getValue());
        }
        r.put("fromEmailAddress", email);

        List<Map<String, Object>> events = new ArrayList<>();
        if (setting.getEventSettings() != null) {
            for (var e : setting.getEventSettings()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("eventId", e.getEventId());
                m.put("enabled", Boolean.TRUE.equals(e.getEnabled()));
                events.add(m);
            }
        }
        r.put("eventSettings", events);
        return r;
    }

    private int replaceBoolean(Map<String, Object> payload, String key, Boolean current,
                               java.util.function.Consumer<Boolean> setter) {
        if (payload == null || !payload.containsKey(key)) return 0;
        Boolean next = asBoolean(payload.get(key));
        if (Objects.equals(next, current)) return 0;
        setter.accept(next);
        return 1;
    }

    private Boolean asBoolean(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s) || "启用".equals(s) || "是".equals(s);
    }

    private String trimToNull(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private List<String> normalizeStringList(Object value) {
        List<String> raw = new ArrayList<>();
        if (value instanceof Collection<?> list) {
            for (var item : list) raw.add(String.valueOf(item == null ? "" : item));
        } else if (value != null) {
            raw.addAll(Arrays.asList(String.valueOf(value).split("[,，;；\\n\\r]+")));
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String item : raw) {
            String s = item == null ? "" : item.trim();
            if (!s.isEmpty()) cleaned.add(s);
        }
        return new ArrayList<>(cleaned);
    }

    private List<NotificationSettingsEventSettings> normalizeEventSettings(
            List<?> rawEvents, List<NotificationSettingsEventSettings> currentEvents) {
        Map<String, NotificationSettingsEventSettings> existing = new LinkedHashMap<>();
        if (currentEvents != null) {
            for (var e : currentEvents) {
                if (e.getEventId() != null) existing.put(e.getEventId(), e);
            }
        }
        List<NotificationSettingsEventSettings> next = new ArrayList<>();
        for (var raw : rawEvents) {
            if (!(raw instanceof Map<?, ?> m)) continue;
            String eventId = trimToNull(m.get("eventId"));
            if (eventId == null) continue;
            Boolean enabled = m.containsKey("enabled")
                    ? asBoolean(m.get("enabled"))
                    : (existing.get(eventId) == null ? null : existing.get(eventId).getEnabled());
            NotificationSettingsEventSettings.Builder b = existing.containsKey(eventId)
                    ? existing.get(eventId).toBuilder()
                    : NotificationSettingsEventSettings.builder().eventId(eventId);
            b.enabled(Boolean.TRUE.equals(enabled));
            next.add(b.build());
        }
        return next;
    }

    private boolean eventSettingsEqual(List<NotificationSettingsEventSettings> a,
                                       List<NotificationSettingsEventSettings> b) {
        if (a == null) a = List.of();
        if (b == null) b = List.of();
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            var left = a.get(i);
            var right = b.get(i);
            if (!Objects.equals(left.getEventId(), right.getEventId())) return false;
            if (!Objects.equals(Boolean.TRUE.equals(left.getEnabled()), Boolean.TRUE.equals(right.getEnabled()))) return false;
        }
        return true;
    }

    // ---------------- Audit Events (Identity Domain built-in) ----------------

    /**
     * For each domain, fetch recent login-related audit events.
     * Returns list of { domainId, displayName, logs: [...] }.
     */
    public List<Map<String, Object>> getAuditLogs(String tenantId) {
        return getAuditLogs(tenantId, 7);
    }

    /**
     * 身份域「登录」相关审计。
     * 身份域 SCIM {@code /admin/v1/AuditEvents} 新数据已由官方迁到租户级 {@link AuditClient}{@code #listEvents}；
     * 应按 {@code data.additionalDetails.eventId} 识别 SSO/控制台登录类事件（与 Oracle「从 OCI Audit 生成 IAM 报表」文档一致）。
     */
    public List<Map<String, Object>> getAuditLogs(String tenantId, int days) {
        return getAuditLogs(tenantId, days, null);
    }

    public List<Map<String, Object>> getAuditLogs(String tenantId, int days, String domainId) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (OciClientService client = buildClient(tenantId)) {
            var domains = listDomains(client, true);
            if (domains.isEmpty()) throw new OciException("未找到 Identity Domain");

            int window = Math.max(1, Math.min(days, 30));
            Date endTime = new Date();
            Date startTime = Date.from(java.time.Instant.now().minus(Duration.ofDays(window)));

            Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
            for (var d : domains) grouped.put((String) d.get("id"), new ArrayList<>());
            List<Map<String, Object>> unknown = new ArrayList<>();

            Map<String, String> nameToId = new HashMap<>();
            for (var d : domains) {
                String id = (String) d.get("id");
                putDomainAlias(nameToId, d.get("displayName"), id);
                putDomainAlias(nameToId, d.get("type"), id);
            }

            String selectedDomainId = trimToNull(domainId);
            if (selectedDomainId != null && !grouped.containsKey(selectedDomainId)) {
                selectedDomainId = null;
            }

            AuditQueryResult query = listLoginAuditEvents(
                    client, startTime, endTime, window, nameToId, selectedDomainId, domains.size());

            List<AuditEvent> events = query.events();
            for (AuditEvent ev : events) {
                String etFull = ev.getEventType();
                String scmEventId = null;
                com.oracle.bmc.audit.model.Data data = ev.getData();
                Map<String, Object> addl = data != null && data.getAdditionalDetails() != null
                        ? castStringObjectMap(data.getAdditionalDetails())
                        : null;
                if (addl != null) {
                    Object eid = addl.get("eventId");
                    if (eid != null) scmEventId = String.valueOf(eid).trim();
                }
                if (!matchesLoginAuditEvent(scmEventId, etFull)) continue;

                String domainIdFromEvent = resolveLoginLogDomainId(data, nameToId);

                String actorName = null;
                String principalId = null;
                String clientIp = null;
                String userAgent = null;
                String ssoApp = null;
                String ssoProtectedResource = null;
                String ssoIdp = null;
                String ssoFactor = null;
                String msg = null;
                if (data != null) {
                    var identity = data.getIdentity();
                    if (identity != null) {
                        actorName = identity.getPrincipalName();
                        principalId = identity.getPrincipalId();
                        clientIp = identity.getIpAddress();
                        userAgent = identity.getUserAgent();
                    }
                    if (addl != null) {
                        Object an = addl.get("actorName");
                        if (an != null && !String.valueOf(an).isBlank()) {
                            actorName = String.valueOf(an).trim();
                        }
                        Object a = firstNonBlank(addl, "ssoProtectedResource", "protectedResource");
                        if (a != null) ssoProtectedResource = String.valueOf(a);
                        Object ap = firstNonBlank(addl, "ssoApplicationType", "applicationDisplayName");
                        if (ap != null) ssoApp = String.valueOf(ap);
                        Object ip = addl.get("ssoIdentityProvider");
                        if (ip != null) ssoIdp = String.valueOf(ip);
                        Object f = addl.get("ssoAuthFactor");
                        if (f != null) ssoFactor = String.valueOf(f);
                        if ((clientIp == null || clientIp.isBlank())) {
                            Object xc = firstNonBlank(addl, "clientIp", "ipAddress");
                            if (xc != null) clientIp = String.valueOf(xc).trim();
                        }
                    }
                    if (data.getResponse() != null && data.getResponse().getMessage() != null) {
                        msg = data.getResponse().getMessage();
                    }
                    if (msg == null && data.getEventName() != null) msg = data.getEventName();
                }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("eventTime", ev.getEventTime() == null ? null : ev.getEventTime().toInstant().toString());
                row.put("eventId",
                        scmEventId != null && !scmEventId.isBlank() ? scmEventId
                                : (etFull != null ? etFull : ""));
                row.put("auditEventType", etFull);
                row.put("actorName", actorName);
                row.put("principalId", principalId);
                row.put("actorDisplayName", actorName);
                row.put("ssoIdentityProvider", ssoIdp);
                row.put("ssoApplicationType", ssoApp);
                row.put("ssoProtectedResource", ssoProtectedResource);
                row.put("ssoUserAgent", userAgent);
                row.put("clientIp", clientIp);
                row.put("ssoAuthFactor", ssoFactor);
                row.put("message", msg);

                if (domainIdFromEvent != null && grouped.containsKey(domainIdFromEvent)) {
                    grouped.get(domainIdFromEvent).add(row);
                } else {
                    unknown.add(row);
                }
            }

            int unresolvedCount = unknown.size() + query.unresolvedSkippedCount();
            if (domains.size() == 1 && !unknown.isEmpty()) {
                grouped.get((String) domains.getFirst().get("id")).addAll(unknown);
                unknown.clear();
            }
            String queryNotice = buildAuditQueryNotice(query);

            for (var d : domains) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("domainId", d.get("id"));
                entry.put("displayName", d.get("displayName"));
                entry.put("type", d.get("type"));
                List<Map<String, Object>> logs = grouped.getOrDefault((String) d.get("id"), new ArrayList<>());
                logs.sort((a, b) -> {
                    String ta = String.valueOf(a.getOrDefault("eventTime", ""));
                    String tb = String.valueOf(b.getOrDefault("eventTime", ""));
                    return tb.compareTo(ta);
                });
                entry.put("logs", logs);
                if (unresolvedCount > 0 && domains.size() > 1) {
                    appendNotice(entry, "另有 " + unresolvedCount + " 条登录日志未包含可识别的身份域字段，已避免错误归类到 Default。");
                }
                if (queryNotice != null && (selectedDomainId == null || Objects.equals(selectedDomainId, d.get("id")))) {
                    appendNotice(entry, queryNotice);
                }
                result.add(entry);
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("获取登录日志失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
        return result;
    }

    private AuditQueryResult listLoginAuditEvents(OciClientService client,
                                                  Date startTime,
                                                  Date endTime,
                                                  int windowDays,
                                                  Map<String, String> nameToId,
                                                  String targetDomainId,
                                                  int domainCount) {
        String tenancyId = client.getProvider().getTenantId();
        LinkedHashSet<String> regions = resolveAuditRegionNames(client);
        Map<String, AuditEvent> dedup = new LinkedHashMap<>();
        Set<String> unresolvedSkipped = new LinkedHashSet<>();
        ClientConfiguration auditConfig = ClientConfiguration.builder()
                .connectionTimeoutMillis(10_000)
                .readTimeoutMillis(AUDIT_READ_TIMEOUT_MS)
                .build();
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(AUDIT_QUERY_BUDGET_MS).toNanos();
        int successfulRegions = 0;
        int failedRegions = 0;
        int totalRawEvents = 0;
        int totalPages = 0;
        boolean partial = false;
        boolean stopAll = false;
        Exception firstFailure = null;

        for (String regionName : regions) {
            if (System.nanoTime() >= deadlineNanos) {
                partial = true;
                break;
            }
            int regionRawEvents = 0;
            int regionPages = 0;
            int regionMatchedEvents = 0;
            try (AuditClient auditClient = AuditClient.builder()
                    .configuration(auditConfig)
                    .build(client.getProvider())) {
                if (regionName != null && !regionName.isBlank()) {
                    auditClient.setRegion(regionName);
                }
                String page = null;
                do {
                    if (System.nanoTime() >= deadlineNanos) {
                        partial = true;
                        stopAll = true;
                        break;
                    }
                    if (regionPages >= AUDIT_MAX_PAGES_PER_REGION) {
                        partial = true;
                        break;
                    }
                    var reqB = ListEventsRequest.builder()
                            .compartmentId(tenancyId)
                            .startTime(startTime)
                            .endTime(endTime);
                    if (page != null) reqB.page(page);
                    var resp = auditClient.listEvents(reqB.build());
                    regionPages++;
                    totalPages++;
                    if (resp.getItems() != null) {
                        regionRawEvents += resp.getItems().size();
                        totalRawEvents += resp.getItems().size();
                        for (AuditEvent event : resp.getItems()) {
                            if (!matchesLoginAuditEvent(event)) continue;
                            if (targetDomainId != null) {
                                String eventDomainId = resolveLoginLogDomainId(event.getData(), nameToId);
                                if (eventDomainId == null && domainCount > 1) {
                                    unresolvedSkipped.add(auditEventKey(event));
                                    continue;
                                }
                                if (eventDomainId != null && !Objects.equals(targetDomainId, eventDomainId)) {
                                    continue;
                                }
                            }
                            String key = auditEventKey(event);
                            if (!dedup.containsKey(key)) {
                                regionMatchedEvents++;
                            }
                            dedup.putIfAbsent(key, event);
                        }
                    }
                    page = resp.getOpcNextPage();
                } while (page != null && !page.isEmpty());
                successfulRegions++;
                log.info("OCI Audit listEvents region={} pages={} rawTotal={} matchedTotal={} windowDays={}",
                        regionName, regionPages, regionRawEvents, regionMatchedEvents, windowDays);
            } catch (Exception e) {
                failedRegions++;
                if (firstFailure == null) firstFailure = e;
                log.warn("OCI Audit listEvents failed region={}: {}", regionName, e.getMessage());
            }
            if (stopAll) break;
        }

        if (successfulRegions == 0 && firstFailure != null) {
            throw new OciException("OCI Audit 查询失败: " + (firstFailure.getMessage() == null ? "未知错误" : firstFailure.getMessage()));
        }
        log.info("OCI Audit listEvents mergedTotal={} rawTotal={} pages={} regions={} windowDays={} partial={}",
                dedup.size(), totalRawEvents, totalPages, regions, windowDays, partial);
        return new AuditQueryResult(new ArrayList<>(dedup.values()), partial, totalRawEvents, totalPages,
                successfulRegions, failedRegions, unresolvedSkipped.size());
    }

    private record AuditQueryResult(List<AuditEvent> events,
                                    boolean partial,
                                    int rawEvents,
                                    int pages,
                                    int successfulRegions,
                                    int failedRegions,
                                    int unresolvedSkippedCount) {
    }

    private static String buildAuditQueryNotice(AuditQueryResult query) {
        if (query == null) return null;
        List<String> notices = new ArrayList<>();
        if (query.partial()) {
            notices.add("登录日志查询已达到时间或分页上限，仅显示已扫描到的最近部分日志；可缩短时间窗口后重试。");
        }
        if (query.failedRegions() > 0 && query.successfulRegions() > 0) {
            notices.add("部分区域的 Audit 查询失败，结果可能不完整。");
        }
        return notices.isEmpty() ? null : String.join(" ", notices);
    }

    private static void appendNotice(Map<String, Object> entry, String notice) {
        if (entry == null || notice == null || notice.isBlank()) return;
        Object current = entry.get("notice");
        if (current == null || String.valueOf(current).isBlank()) {
            entry.put("notice", notice);
        } else {
            entry.put("notice", current + " " + notice);
        }
    }

    private LinkedHashSet<String> resolveAuditRegionNames(OciClientService client) {
        LinkedHashSet<String> regions = new LinkedHashSet<>();
        String homeRegion = resolveHomeRegionName(client);
        if (homeRegion != null && !homeRegion.isBlank()) regions.add(homeRegion);
        String configuredRegion = client.getUser() == null || client.getUser().getOciCfg() == null
                ? null
                : client.getUser().getOciCfg().getRegion();
        if (configuredRegion != null && !configuredRegion.isBlank()) regions.add(configuredRegion);
        if (regions.isEmpty()) regions.add(null);
        return regions;
    }

    private String resolveHomeRegionName(OciClientService client) {
        String tenancyId = client.getProvider().getTenantId();
        try {
            var identity = client.getIdentityClient();
            var subscriptions = identity.listRegionSubscriptions(
                    com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest.builder()
                            .tenancyId(tenancyId)
                            .build()).getItems();
            if (subscriptions != null) {
                for (var sub : subscriptions) {
                    if (Boolean.TRUE.equals(sub.getIsHomeRegion()) && sub.getRegionName() != null && !sub.getRegionName().isBlank()) {
                        return sub.getRegionName();
                    }
                }
            }
            String homeKey = null;
            try {
                var tenancy = identity.getTenancy(com.oracle.bmc.identity.requests.GetTenancyRequest.builder()
                        .tenancyId(tenancyId)
                        .build()).getTenancy();
                homeKey = tenancy == null ? null : tenancy.getHomeRegionKey();
            } catch (Exception e) {
                log.warn("Failed to resolve tenancy home region key: {}", e.getMessage());
            }
            if (homeKey != null && subscriptions != null) {
                for (var sub : subscriptions) {
                    if (homeKey.equalsIgnoreCase(String.valueOf(sub.getRegionKey()))
                            && sub.getRegionName() != null && !sub.getRegionName().isBlank()) {
                        return sub.getRegionName();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve audit home region: {}", e.getMessage());
        }
        return null;
    }

    private String auditEventKey(AuditEvent ev) {
        if (ev == null) return UUID.randomUUID().toString();
        com.oracle.bmc.audit.model.Data data = ev.getData();
        String eventTime = ev.getEventTime() == null ? "" : ev.getEventTime().toInstant().toString();
        String resourceId = data == null || data.getResourceId() == null ? "" : data.getResourceId();
        String principal = "";
        if (data != null && data.getIdentity() != null && data.getIdentity().getPrincipalId() != null) {
            principal = data.getIdentity().getPrincipalId();
        }
        String eventName = data == null || data.getEventName() == null ? "" : data.getEventName();
        return eventTime + "|" + ev.getEventType() + "|" + eventName + "|" + resourceId + "|" + principal;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringObjectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : map.entrySet()) {
            if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static Object firstNonBlank(Map<String, Object> map, String k1, String k2) {
        Object v1 = map == null ? null : map.get(k1);
        if (v1 != null && String.valueOf(v1).trim().length() > 0) return v1;
        Object v2 = map == null ? null : map.get(k2);
        if (v2 != null && String.valueOf(v2).trim().length() > 0) return v2;
        return null;
    }

    private static void putDomainAlias(Map<String, String> aliases, Object rawName, String domainId) {
        if (aliases == null || domainId == null || rawName == null) return;
        String text = String.valueOf(rawName).trim();
        if (text.isEmpty()) return;
        aliases.put(text.toLowerCase(Locale.ROOT), domainId);
        aliases.put(normalizeDomainAlias(text), domainId);
    }

    private static String normalizeDomainAlias(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]+", "");
    }

    private static String findDomainIdByName(Map<String, String> nameToId, Object rawName) {
        if (nameToId == null || rawName == null) return null;
        String text = String.valueOf(rawName).trim();
        if (text.isEmpty()) return null;
        String exact = nameToId.get(text.toLowerCase(Locale.ROOT));
        if (exact != null) return exact;
        return nameToId.get(normalizeDomainAlias(text));
    }

    private static String extractDomainOcid(String value) {
        if (value == null) return null;
        int start = value.indexOf("ocid1.domain.");
        if (start < 0) return null;
        int end = value.length();
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == ',' || c == '"' || c == '\'' || c == ')' || c == ']') {
                end = i;
                break;
            }
        }
        return value.substring(start, end);
    }

    /**
     * 与 Oracle「Generate IAM Reports from OCI Audit」中登录/App 访问口径大体一致，
     * 仅保留与用户登录、控制台认证、会话、应用 SSO 浏览相关条目。
     */
    private static boolean matchesLoginAuditScmEventId(String scmEventId) {
        String s = scmEventId.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("sso.session.")
                || "sso.authentication.failure".equals(s)
                || s.startsWith("sso.app.access.")
                || s.startsWith("admin.authentication.")
                || "sso.auth.factor.initiated".equals(s);
    }

    /** 顶层 eventType（未迁出 additionalDetails.eventId 的旧形态或少数封装事件）兜底 */
    private static boolean matchesLoginAuditByLegacyEventType(String eventTypeFull) {
        if (eventTypeFull == null) return false;
        String etl = eventTypeFull.toLowerCase(Locale.ROOT);
        if (!(etl.contains("identitydomain") || etl.contains("identitydomains") || etl.contains("idcs"))) return false;
        return etl.contains("session") || etl.contains("authentication") || etl.contains("appaccess")
                || etl.contains("signin") || etl.contains("sso");
    }

    private static boolean matchesLoginAuditEvent(String scmEventIdNullable, String eventTypeFull) {
        if (scmEventIdNullable != null && !scmEventIdNullable.isBlank()) {
            return matchesLoginAuditScmEventId(scmEventIdNullable);
        }
        return matchesLoginAuditByLegacyEventType(eventTypeFull);
    }

    private static boolean matchesLoginAuditEvent(AuditEvent event) {
        if (event == null) return false;
        String scmEventId = null;
        com.oracle.bmc.audit.model.Data data = event.getData();
        Map<String, Object> addl = data != null && data.getAdditionalDetails() != null
                ? castStringObjectMap(data.getAdditionalDetails())
                : null;
        if (addl != null) {
            Object eid = addl.get("eventId");
            if (eid != null) scmEventId = String.valueOf(eid).trim();
        }
        return matchesLoginAuditEvent(scmEventId, event.getEventType());
    }

    private static String resolveLoginLogDomainId(com.oracle.bmc.audit.model.Data data, Map<String, String> nameToId) {
        if (data == null) return null;
        String rid = data.getResourceId();
        String ridDomain = extractDomainOcid(rid);
        if (ridDomain != null) return ridDomain;

        Map<String, Object> addl = castStringObjectMap(data.getAdditionalDetails());
        if (addl != null) {
            for (String key : List.of("domainOcid", "domainId", "identityDomainId", "identityDomainOcid")) {
                Object raw = addl.get(key);
                String domainOcid = extractDomainOcid(raw == null ? null : String.valueOf(raw));
                if (domainOcid != null) return domainOcid;
            }
            for (String key : List.of(
                    "domainDisplayName",
                    "domainName",
                    "identityDomainName",
                    "identityDomain",
                    "idcsTenant",
                    "tenantName",
                    "ssoIdentityProvider")) {
                String found = findDomainIdByName(nameToId, addl.get(key));
                if (found != null) return found;
            }
        }
        return findDomainIdByName(nameToId, data.getResourceName());
    }

    // ---------------- Authentication Factor Settings ----------------

    /** 因素开关在 AuthenticationFactorSetting 顶层的布尔字段名 */
    private static final Map<String, String> FACTOR_PATH = new LinkedHashMap<>();
    static {
        FACTOR_PATH.put("totp", "totpEnabled");
        FACTOR_PATH.put("push", "pushEnabled");
        FACTOR_PATH.put("sms", "smsEnabled");
        FACTOR_PATH.put("phoneCall", "phoneCallEnabled");
        FACTOR_PATH.put("email", "emailEnabled");
        FACTOR_PATH.put("securityQuestions", "securityQuestionsEnabled");
        FACTOR_PATH.put("fido", "fidoAuthenticatorEnabled");
        FACTOR_PATH.put("yubico", "yubicoOtpEnabled");
        FACTOR_PATH.put("bypassCode", "bypassCodeEnabled");
        FACTOR_PATH.put("duoSecurity", "thirdPartyFactor.duoSecurity");
    }

    /**
     * 读取所有域的 AuthenticationFactorSetting。要求已解锁。
     */
    public Map<String, Object> listAuthFactorSettings(String tenantId, String token) {
        requireAuthFactorToken(token);
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> domainResults = new ArrayList<>();
        try (OciClientService client = buildClient(tenantId)) {
            var domains = listDomains(client, true);
            if (domains.isEmpty()) throw new OciException("未找到 Identity Domain");
            for (var d : domains) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("domainId", d.get("id"));
                r.put("displayName", d.get("displayName"));
                r.put("type", d.get("type"));
                try (IdentityDomainsClient dc = newDomainClient(client, (String) d.get("url"))) {
                    AuthenticationFactorSetting s = firstAuthFactorSetting(dc);
                    r.put("settingId", s.getId());
                    Map<String, Object> factors = new LinkedHashMap<>();
                    factors.put("totp", bool(s.getTotpEnabled()));
                    factors.put("push", bool(s.getPushEnabled()));
                    factors.put("sms", bool(s.getSmsEnabled()));
                    factors.put("phoneCall", bool(s.getPhoneCallEnabled()));
                    factors.put("email", bool(s.getEmailEnabled()));
                    factors.put("securityQuestions", bool(s.getSecurityQuestionsEnabled()));
                    factors.put("fido", bool(s.getFidoAuthenticatorEnabled()));
                    factors.put("yubico", bool(s.getYubicoOtpEnabled()));
                    factors.put("bypassCode", bool(s.getBypassCodeEnabled()));
                    factors.put("duoSecurity", s.getThirdPartyFactor() != null && Boolean.TRUE.equals(s.getThirdPartyFactor().getDuoSecurity()));
                    r.put("factors", factors);

                    Map<String, Object> limits = new LinkedHashMap<>();
                    Map<String, Object> trusted = new LinkedHashMap<>();
                    int maxIncorrect = 0;
                    var er = s.getEndpointRestrictions();
                    if (er != null) {
                        limits.put("maxEnrolledDevices", er.getMaxEnrolledDevices());
                        trusted.put("enabled", bool(er.getTrustedEndpointsEnabled()));
                        trusted.put("maxTrustedEndpoints", er.getMaxTrustedEndpoints());
                        trusted.put("maxEndpointTrustDurationInDays", er.getMaxEndpointTrustDurationInDays());
                        if (er.getMaxIncorrectAttempts() != null) maxIncorrect = er.getMaxIncorrectAttempts();
                    }
                    limits.put("maxIncorrectAttempts", maxIncorrect);
                    r.put("limits", limits);
                    r.put("trustedDevice", trusted);
                } catch (Exception e) {
                    log.warn("list auth factor for domain {} failed: {}", d.get("displayName"), e.getMessage());
                    r.put("error", e.getMessage() == null ? "查询失败" : e.getMessage());
                }
                domainResults.add(r);
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("读取验证因素设置失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
        result.put("domains", domainResults);
        return result;
    }

    /**
     * 保存某一个域的 AuthenticationFactorSetting。
     * SDK 未开放 PATCH，因此使用 GET → toBuilder 修改 → PUT 整体替换。
     */
    public Map<String, Object> updateAuthFactorSettings(String tenantId, String domainId, String token,
                                                        Map<String, Object> desiredFactors,
                                                        Map<String, Object> desiredLimits,
                                                        Map<String, Object> desiredTrustedDevice) {
        requireAuthFactorToken(token);
        try (OciClientService client = buildClient(tenantId)) {
            var domains = listDomains(client, true);
            var target = findDomain(domains, domainId);
            try (IdentityDomainsClient dc = newDomainClient(client, (String) target.get("url"))) {
                AuthenticationFactorSetting current = firstAuthFactorSetting(dc);

                AuthenticationFactorSetting.Builder b = current.toBuilder();
                int changed = 0;

                // factors
                if (desiredFactors != null) {
                    for (var key : FACTOR_PATH.keySet()) {
                        if (!desiredFactors.containsKey(key)) continue;
                        boolean want = Boolean.TRUE.equals(desiredFactors.get(key));
                        boolean now = currentFactorValue(current, key);
                        if (want == now) continue;
                        changed++;
                        switch (key) {
                            case "totp": b.totpEnabled(want); break;
                            case "push": b.pushEnabled(want); break;
                            case "sms": b.smsEnabled(want); break;
                            case "phoneCall": b.phoneCallEnabled(want); break;
                            case "email": b.emailEnabled(want); break;
                            case "securityQuestions": b.securityQuestionsEnabled(want); break;
                            case "fido": b.fidoAuthenticatorEnabled(want); break;
                            case "yubico": b.yubicoOtpEnabled(want); break;
                            case "bypassCode": b.bypassCodeEnabled(want); break;
                            case "duoSecurity":
                                var tpfBase = current.getThirdPartyFactor() != null
                                        ? current.getThirdPartyFactor().toBuilder()
                                        : AuthenticationFactorSettingsThirdPartyFactor.builder();
                                b.thirdPartyFactor(tpfBase.duoSecurity(want).build());
                                break;
                            default: break;
                        }
                    }
                }

                // endpointRestrictions 子字段（limits + trustedDevice）
                var er = current.getEndpointRestrictions();
                AuthenticationFactorSettingsEndpointRestrictions.Builder erBuilder = er != null
                        ? er.toBuilder() : AuthenticationFactorSettingsEndpointRestrictions.builder();
                boolean erChanged = false;

                if (desiredLimits != null) {
                    Integer want = asInt(desiredLimits.get("maxEnrolledDevices"));
                    Integer now = er == null ? null : er.getMaxEnrolledDevices();
                    if (want != null && !java.util.Objects.equals(want, now)) {
                        erBuilder.maxEnrolledDevices(want); erChanged = true;
                    }
                    Integer wantInc = asInt(desiredLimits.get("maxIncorrectAttempts"));
                    Integer nowInc = er == null ? null : er.getMaxIncorrectAttempts();
                    if (wantInc != null && !java.util.Objects.equals(wantInc, nowInc)) {
                        erBuilder.maxIncorrectAttempts(wantInc); erChanged = true;
                    }
                }
                if (desiredTrustedDevice != null) {
                    if (desiredTrustedDevice.containsKey("enabled")) {
                        boolean want = Boolean.TRUE.equals(desiredTrustedDevice.get("enabled"));
                        boolean now = er != null && Boolean.TRUE.equals(er.getTrustedEndpointsEnabled());
                        if (want != now) { erBuilder.trustedEndpointsEnabled(want); erChanged = true; }
                    }
                    Integer wantMax = asInt(desiredTrustedDevice.get("maxTrustedEndpoints"));
                    Integer nowMax = er == null ? null : er.getMaxTrustedEndpoints();
                    if (wantMax != null && !java.util.Objects.equals(wantMax, nowMax)) {
                        erBuilder.maxTrustedEndpoints(wantMax); erChanged = true;
                    }
                    Integer wantDays = asInt(desiredTrustedDevice.get("maxEndpointTrustDurationInDays"));
                    Integer nowDays = er == null ? null : er.getMaxEndpointTrustDurationInDays();
                    if (wantDays != null && !java.util.Objects.equals(wantDays, nowDays)) {
                        erBuilder.maxEndpointTrustDurationInDays(wantDays); erChanged = true;
                    }
                }
                if (erChanged) {
                    b.endpointRestrictions(erBuilder.build());
                    changed++;
                }

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("domainId", target.get("id"));
                resp.put("displayName", target.get("displayName"));
                resp.put("changedOps", changed);
                if (changed == 0) {
                    resp.put("skipped", true);
                    return resp;
                }

                dc.putAuthenticationFactorSetting(PutAuthenticationFactorSettingRequest.builder()
                        .authenticationFactorSettingId(current.getId())
                        .authenticationFactorSetting(b.build())
                        .build());

                log.info("AuthFactorSetting put: tenant={} domain={} changedGroups={}",
                        tenantId, target.get("displayName"), changed);
                return resp;
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("更新验证因素设置失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    private boolean currentFactorValue(AuthenticationFactorSetting s, String key) {
        switch (key) {
            case "totp": return Boolean.TRUE.equals(s.getTotpEnabled());
            case "push": return Boolean.TRUE.equals(s.getPushEnabled());
            case "sms": return Boolean.TRUE.equals(s.getSmsEnabled());
            case "phoneCall": return Boolean.TRUE.equals(s.getPhoneCallEnabled());
            case "email": return Boolean.TRUE.equals(s.getEmailEnabled());
            case "securityQuestions": return Boolean.TRUE.equals(s.getSecurityQuestionsEnabled());
            case "fido": return Boolean.TRUE.equals(s.getFidoAuthenticatorEnabled());
            case "yubico": return Boolean.TRUE.equals(s.getYubicoOtpEnabled());
            case "bypassCode": return Boolean.TRUE.equals(s.getBypassCodeEnabled());
            case "duoSecurity": return s.getThirdPartyFactor() != null && Boolean.TRUE.equals(s.getThirdPartyFactor().getDuoSecurity());
            default: return false;
        }
    }

    private AuthenticationFactorSetting firstAuthFactorSetting(IdentityDomainsClient dc) {
        var resp = dc.listAuthenticationFactorSettings(ListAuthenticationFactorSettingsRequest.builder().build());
        var items = resp.getAuthenticationFactorSettings() == null
                ? null : resp.getAuthenticationFactorSettings().getResources();
        if (items == null || items.isEmpty()) throw new OciException("未找到 AuthenticationFactorSetting");
        return items.get(0);
    }

    private boolean bool(Boolean b) { return Boolean.TRUE.equals(b); }

    private Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception ignored) { return null; }
    }

    // ---------------- Quotas (unchanged) ----------------

    public List<Map<String, Object>> getServiceQuotas(String tenantId) {
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");

        List<Map<String, Object>> quotaList = new ArrayList<>();
        try (OciClientService client = buildClient(tenantId)) {
            var limitsClient = com.oracle.bmc.limits.LimitsClient.builder()
                    .build(client.getProvider());
            try {
                var servicesResp = limitsClient.listServices(
                        com.oracle.bmc.limits.requests.ListServicesRequest.builder()
                                .compartmentId(user.getOciTenantId())
                                .build());

                // 程序化名须与 ListServices/控制台一致；概述见 https://docs.oracle.com/en-us/iaas/Content/General/Concepts/servicelimits-about.htm
                // 各产品默认限制与友好名见 https://docs.oracle.com/en-us/iaas/Content/General/service-limits/default.htm
                //（未单独列 autonomous-data-warehouse 服务名，ADB/ADW 与 Database 文档/配额族为 database。subscribed-region-count 在控制台为 Service「Regions」故含 regions。）
                List<String> targetServices = Arrays.asList(
                        "compute", "vcn", "block-storage", "load-balancer", "network-load-balancer",
                        "identity", "regions", "database", "objectstorage", "file-storage", "container-engine", "generative-ai",
                        "data-science", "dns");

                for (var svc : servicesResp.getItems()) {
                    String svcName = svc.getName();
                    if (!targetServices.contains(svcName)) continue;

                    String nextPage = null;
                    do {
                        var reqBuilder = com.oracle.bmc.limits.requests.ListLimitValuesRequest.builder()
                                .compartmentId(user.getOciTenantId())
                                .serviceName(svcName);
                        if (nextPage != null) reqBuilder.page(nextPage);
                        var limitsResp = limitsClient.listLimitValues(reqBuilder.build());

                        for (var lv : limitsResp.getItems()) {
                            if (lv.getValue() == null || lv.getValue() == 0) continue;

                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("serviceName", svcName);
                            entry.put("limitName", lv.getName());
                            entry.put("availabilityDomain", lv.getAvailabilityDomain());
                            entry.put("limit", lv.getValue());

                            try {
                                var usageResp = limitsClient.getResourceAvailability(
                                        com.oracle.bmc.limits.requests.GetResourceAvailabilityRequest.builder()
                                                .compartmentId(user.getOciTenantId())
                                                .serviceName(svcName)
                                                .limitName(lv.getName())
                                                .availabilityDomain(lv.getAvailabilityDomain())
                                                .build());
                                entry.put("used", usageResp.getResourceAvailability().getUsed());
                                entry.put("available", usageResp.getResourceAvailability().getAvailable());
                            } catch (Exception ignored) {
                                entry.put("used", null);
                                entry.put("available", null);
                            }

                            quotaList.add(entry);
                        }

                        nextPage = limitsResp.getOpcNextPage();
                    } while (nextPage != null);
                }
            } finally {
                limitsClient.close();
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("获取配额信息失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }

        return quotaList;
    }
}
