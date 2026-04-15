package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class DomainManagementService {

    @Resource
    private OciUserMapper userMapper;

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

    public Map<String, Object> getDomainSettings(String tenantId) {
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");

        Map<String, Object> result = new LinkedHashMap<>();
        try (OciClientService client = buildClient(tenantId)) {
            var domainsClient = com.oracle.bmc.identitydomains.IdentityDomainsClient.builder()
                    .build(client.getProvider());
            try {
                String domainEndpoint = getDomainEndpoint(client);
                if (domainEndpoint == null) {
                    throw new OciException("无法获取 Identity Domain Endpoint");
                }
                domainsClient.setEndpoint(domainEndpoint);

                // MFA settings
                try {
                    var mfaResp = domainsClient.listAuthenticationFactorSettings(
                            com.oracle.bmc.identitydomains.requests.ListAuthenticationFactorSettingsRequest.builder().build());
                    var items = mfaResp.getAuthenticationFactorSettings().getResources();
                    if (items != null && !items.isEmpty()) {
                        var mfa = items.get(0);
                        result.put("mfaEnabled", Boolean.TRUE.equals(mfa.getMfaEnabledCategory()));
                        result.put("mfaSettingId", mfa.getId());
                    }
                } catch (Exception e) {
                    log.warn("Failed to get MFA settings: {}", e.getMessage());
                    result.put("mfaEnabled", null);
                }

                // Password policy
                try {
                    var pwdResp = domainsClient.listPasswordPolicies(
                            com.oracle.bmc.identitydomains.requests.ListPasswordPoliciesRequest.builder().build());
                    var policies = pwdResp.getPasswordPolicies().getResources();
                    if (policies != null && !policies.isEmpty()) {
                        var policy = policies.get(0);
                        result.put("passwordExpiresAfterDays", policy.getPasswordExpiresAfter());
                        result.put("passwordPolicyId", policy.getId());
                        result.put("passwordPolicyName", policy.getName());
                    }
                } catch (Exception e) {
                    log.warn("Failed to get password policy: {}", e.getMessage());
                }
            } finally {
                domainsClient.close();
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("获取域设置失败: " + e.getMessage());
        }
        return result;
    }

    public void updateMfaSetting(String tenantId, boolean enabled) {
        try (OciClientService client = buildClient(tenantId)) {
            var domainsClient = com.oracle.bmc.identitydomains.IdentityDomainsClient.builder()
                    .build(client.getProvider());
            try {
                String domainEndpoint = getDomainEndpoint(client);
                if (domainEndpoint == null) throw new OciException("无法获取 Identity Domain Endpoint");
                domainsClient.setEndpoint(domainEndpoint);

                var mfaResp = domainsClient.listAuthenticationFactorSettings(
                        com.oracle.bmc.identitydomains.requests.ListAuthenticationFactorSettingsRequest.builder().build());
                var items = mfaResp.getAuthenticationFactorSettings().getResources();
                if (items == null || items.isEmpty()) throw new OciException("未找到 MFA 配置");

                var existing = items.get(0);

                var updated = com.oracle.bmc.identitydomains.model.AuthenticationFactorSetting.builder()
                        .mfaEnabledCategory(enabled ? "true" : "false")
                        .schemas(existing.getSchemas())
                        .build();

                domainsClient.putAuthenticationFactorSetting(
                        com.oracle.bmc.identitydomains.requests.PutAuthenticationFactorSettingRequest.builder()
                                .authenticationFactorSettingId(existing.getId())
                                .authenticationFactorSetting(updated)
                                .build());

                log.info("MFA setting updated to {} for tenant {}", enabled, tenantId);
            } finally {
                domainsClient.close();
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("更新 MFA 设置失败: " + e.getMessage());
        }
    }

    public void updatePasswordExpiry(String tenantId, int days) {
        try (OciClientService client = buildClient(tenantId)) {
            var domainsClient = com.oracle.bmc.identitydomains.IdentityDomainsClient.builder()
                    .build(client.getProvider());
            try {
                String domainEndpoint = getDomainEndpoint(client);
                if (domainEndpoint == null) throw new OciException("无法获取 Identity Domain Endpoint");
                domainsClient.setEndpoint(domainEndpoint);

                var pwdResp = domainsClient.listPasswordPolicies(
                        com.oracle.bmc.identitydomains.requests.ListPasswordPoliciesRequest.builder().build());
                var policies = pwdResp.getPasswordPolicies().getResources();
                if (policies == null || policies.isEmpty()) throw new OciException("未找到密码策略");

                var existing = policies.get(0);

                var updated = com.oracle.bmc.identitydomains.model.PasswordPolicy.builder()
                        .schemas(existing.getSchemas())
                        .passwordExpiresAfter(days)
                        .name(existing.getName())
                        .build();

                domainsClient.putPasswordPolicy(
                        com.oracle.bmc.identitydomains.requests.PutPasswordPolicyRequest.builder()
                                .passwordPolicyId(existing.getId())
                                .passwordPolicy(updated)
                                .build());

                log.info("Password expiry updated to {} days for tenant {}", days, tenantId);
            } finally {
                domainsClient.close();
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("更新密码过期策略失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> getAuditLogs(String tenantId) {
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");

        List<Map<String, Object>> logs = new ArrayList<>();
        try (OciClientService client = buildClient(tenantId)) {
            var auditClient = com.oracle.bmc.audit.AuditClient.builder()
                    .build(client.getProvider());
            try {
                java.time.Instant end = java.time.Instant.now();
                java.time.Instant start = end.minus(java.time.Duration.ofDays(7));

                var resp = auditClient.listEvents(
                        com.oracle.bmc.audit.requests.ListEventsRequest.builder()
                                .compartmentId(user.getOciTenantId())
                                .startTime(java.util.Date.from(start))
                                .endTime(java.util.Date.from(end))
                                .build());

                for (var event : resp.getItems()) {
                    var data = event.getData();
                    if (data == null) continue;
                    String eventName = data.getEventName();
                    if (eventName == null || !eventName.toLowerCase().contains("login")) continue;

                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("eventTime", event.getEventTime() != null ? event.getEventTime().toString() : null);
                    entry.put("eventName", eventName);
                    entry.put("source", data.getResourceName());

                    if (data.getIdentity() != null) {
                        entry.put("principalName", data.getIdentity().getPrincipalName());
                        entry.put("ipAddress", data.getIdentity().getIpAddress());
                    }
                    if (data.getResponse() != null) {
                        entry.put("responseStatus", data.getResponse().getStatus());
                    }
                    logs.add(entry);
                }
            } finally {
                auditClient.close();
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("获取登录日志失败: " + e.getMessage());
        }

        logs.sort((a, b) -> {
            String ta = (String) a.get("eventTime");
            String tb = (String) b.get("eventTime");
            if (ta == null || tb == null) return 0;
            return tb.compareTo(ta);
        });

        return logs;
    }

    private String getDomainEndpoint(OciClientService client) {
        try {
            var identityClient = client.getIdentityClient();
            String tenancyId = client.getProvider().getTenantId();

            var resp = identityClient.listDomains(
                    com.oracle.bmc.identity.requests.ListDomainsRequest.builder()
                            .compartmentId(tenancyId)
                            .build());

            for (var domain : resp.getItems()) {
                if ("DEFAULT".equals(domain.getType()) || domain.getIsHiddenOnLogin() == null || !domain.getIsHiddenOnLogin()) {
                    return domain.getUrl();
                }
            }
            if (!resp.getItems().isEmpty()) {
                return resp.getItems().get(0).getUrl();
            }
        } catch (Exception e) {
            log.warn("Failed to get domain endpoint: {}", e.getMessage());
        }
        return null;
    }
}
