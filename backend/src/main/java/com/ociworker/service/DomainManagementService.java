package com.ociworker.service;

import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
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

    @Resource
    private OciUserMapper userMapper;

    @Resource
    private VerifyCodeService verifyCodeService;

    /** token -> expireAt（验证因素 Tab 解锁后 10 分钟内可读写） */
    private static final java.util.Map<String, Long> AUTH_FACTOR_TOKENS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long AUTH_FACTOR_TOKEN_TTL_MS = 10 * 60 * 1000L;

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
     */
    private List<Map<String, Object>> listDomains(OciClientService client) {
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
        }
        return domains;
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
            var domains = listDomains(client);
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
            var domains = listDomains(client);
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
            var domains = listDomains(client);
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

    // ---------------- Audit Events (Identity Domain built-in) ----------------

    /**
     * For each domain, fetch recent login-related audit events.
     * Returns list of { domainId, displayName, logs: [...] }.
     */
    public List<Map<String, Object>> getAuditLogs(String tenantId) {
        return getAuditLogs(tenantId, 7);
    }

    /**
     * 身份域登录审计日志。
     * 注意：OCI 于 2024-12-15 弃用了 Identity Domain SCIM /admin/v1/AuditEvents 端点，
     * 所有身份域审计事件已迁移到 OCI Audit 服务。改用 AuditClient.listEvents。
     * 过滤前缀 "com.oraclecloud.identitydomain." 且只保留登录相关类别（sso / session / authentication / appaccess / signin）；
     * 再按 additionalDetails 中的 domain 字段聚合到对应域。
     */
    public List<Map<String, Object>> getAuditLogs(String tenantId, int days) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (OciClientService client = buildClient(tenantId)) {
            var domains = listDomains(client);
            if (domains.isEmpty()) throw new OciException("未找到 Identity Domain");

            int window = Math.max(1, Math.min(days, 30));
            Date endTime = new Date();
            Date startTime = Date.from(java.time.Instant.now().minus(Duration.ofDays(window)));

            List<AuditEvent> events = new ArrayList<>();
            try (AuditClient auditClient = AuditClient.builder().build(client.getProvider())) {
                String tenancyId = client.getProvider().getTenantId();
                String page = null;
                int maxEvents = 5000; // 兜底，避免历史过长导致拉取过多
                do {
                    var reqB = ListEventsRequest.builder()
                            .compartmentId(tenancyId)
                            .startTime(startTime)
                            .endTime(endTime);
                    if (page != null) reqB.page(page);
                    var resp = auditClient.listEvents(reqB.build());
                    if (resp.getItems() != null) events.addAll(resp.getItems());
                    page = resp.getOpcNextPage();
                } while (page != null && !page.isEmpty() && events.size() < maxEvents);
                log.info("OCI Audit listEvents total={} windowDays={}", events.size(), window);
            }

            // 按域聚合
            Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
            for (var d : domains) grouped.put((String) d.get("id"), new ArrayList<>());
            List<Map<String, Object>> unknown = new ArrayList<>();

            // 预建 displayName -> domainId 映射（OCI Audit 事件里通常含 domainDisplayName，有的含 domainId/domainOcid）
            Map<String, String> nameToId = new HashMap<>();
            for (var d : domains) {
                Object n = d.get("displayName");
                if (n != null) nameToId.put(String.valueOf(n).toLowerCase(Locale.ROOT), (String) d.get("id"));
            }

            for (AuditEvent ev : events) {
                String et = ev.getEventType();
                if (et == null) continue;
                String etl = et.toLowerCase(Locale.ROOT);
                // 只保留身份域相关
                if (!etl.contains("identitydomain") && !etl.contains("idcs")) continue;
                // 只保留登录类
                if (!(etl.contains("session") || etl.contains("authentication") || etl.contains("appaccess") || etl.contains("signin") || etl.contains("sso"))) continue;

                String domainId = null;
                String actorName = null;
                String principalId = null;
                String clientIp = null;
                String userAgent = null;
                String ssoApp = null;
                String ssoIdp = null;
                String ssoFactor = null;
                String msg = null;
                var data = ev.getData();
                if (data != null) {
                    var identity = data.getIdentity();
                    if (identity != null) {
                        actorName = identity.getPrincipalName();
                        principalId = identity.getPrincipalId();
                        clientIp = identity.getIpAddress();
                        userAgent = identity.getUserAgent();
                    }
                    String rid = data.getResourceId();
                    if (rid != null && rid.contains("ocid1.domain.")) {
                        domainId = rid;
                    }
                    var addl = data.getAdditionalDetails();
                    if (addl != null) {
                        if (domainId == null) {
                            Object did = addl.getOrDefault("domainOcid", addl.getOrDefault("domainId", null));
                            if (did != null && String.valueOf(did).startsWith("ocid1.domain.")) domainId = String.valueOf(did);
                        }
                        if (domainId == null) {
                            Object dn = addl.get("domainDisplayName");
                            if (dn != null) domainId = nameToId.get(String.valueOf(dn).toLowerCase(Locale.ROOT));
                        }
                        Object a = addl.get("ssoApplicationType");
                        if (a != null) ssoApp = String.valueOf(a);
                        Object ip = addl.get("ssoIdentityProvider");
                        if (ip != null) ssoIdp = String.valueOf(ip);
                        Object f = addl.get("ssoAuthFactor");
                        if (f != null) ssoFactor = String.valueOf(f);
                    }
                    if (data.getResponse() != null && data.getResponse().getMessage() != null) {
                        msg = data.getResponse().getMessage();
                    }
                    if (msg == null && data.getEventName() != null) msg = data.getEventName();
                }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("eventTime", ev.getEventTime() == null ? null : ev.getEventTime().toInstant().toString());
                row.put("eventId", et);
                row.put("actorName", actorName);
                row.put("actorType", principalId);
                row.put("actorDisplayName", actorName);
                row.put("ssoIdentityProvider", ssoIdp);
                row.put("ssoApplicationType", ssoApp);
                row.put("ssoUserAgent", userAgent);
                row.put("clientIp", clientIp);
                row.put("ssoAuthFactor", ssoFactor);
                row.put("message", msg);

                if (domainId != null && grouped.containsKey(domainId)) {
                    grouped.get(domainId).add(row);
                } else {
                    unknown.add(row);
                }
            }

            for (var d : domains) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("domainId", d.get("id"));
                entry.put("displayName", d.get("displayName"));
                entry.put("type", d.get("type"));
                List<Map<String, Object>> logs = grouped.getOrDefault((String) d.get("id"), new ArrayList<>());
                // 无法识别域归属的事件，记到 Default 域下（通常是旧事件缺少 domainDisplayName）
                if ("Default".equals(d.get("displayName")) && !unknown.isEmpty()) {
                    logs.addAll(unknown);
                    unknown.clear();
                }
                // 按时间倒序
                logs.sort((a, b) -> {
                    String ta = String.valueOf(a.getOrDefault("eventTime", ""));
                    String tb = String.valueOf(b.getOrDefault("eventTime", ""));
                    return tb.compareTo(ta);
                });
                entry.put("logs", logs);
                result.add(entry);
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("获取登录日志失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
        return result;
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
            var domains = listDomains(client);
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
            var domains = listDomains(client);
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

                List<String> targetServices = List.of("compute", "vcn", "block-storage", "load-balancer", "network-load-balancer");

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
