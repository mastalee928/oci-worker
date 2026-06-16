package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.*;
import com.oracle.bmc.identity.requests.*;
import com.oracle.bmc.identity.responses.*;
import com.oracle.bmc.identitydomains.IdentityDomainsClient;
import com.oracle.bmc.identitydomains.model.Groups;
import com.oracle.bmc.identitydomains.model.Operations;
import com.oracle.bmc.identitydomains.model.PatchOp;
import com.oracle.bmc.identitydomains.model.UserEmails;
import com.oracle.bmc.identitydomains.model.UserName;
import com.oracle.bmc.identitydomains.model.UserPasswordChanger;
import com.oracle.bmc.identitydomains.requests.PatchGroupRequest;
import com.oracle.bmc.identitydomains.requests.PutUserPasswordChangerRequest;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.params.UserParams;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class UserManagementService {

    private static final String SCIM_SCHEMA_USER = "urn:ietf:params:scim:schemas:core:2.0:User";
    private static final String SCIM_SCHEMA_PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    private static final String SCHEMA_USER_PASSWORD_CHANGER =
            "urn:ietf:params:scim:schemas:oracle:idcs:UserPasswordChanger";
    private static final String SCIM_USER_LIST_ATTRIBUTES = String.join(",",
            "id",
            "ocid",
            "userName",
            "displayName",
            "emails",
            "description",
            "active",
            "meta",
            "urn:ietf:params:scim:schemas:oracle:idcs:extension:userState:User:lastSuccessfulLoginDate",
            "urn:ietf:params:scim:schemas:oracle:idcs:extension:userState:User:previousSuccessfulLoginDate",
            "urn:ietf:params:scim:schemas:oracle:idcs:extension:mfa:User:mfaStatus");

    public static final List<String> CAPABILITY_KEYS = List.of(
            "canUseConsolePassword",
            "canUseApiKeys",
            "canUseAuthTokens",
            "canUseSmtpCredentials",
            "canUseDbCredentials",
            "canUseCustomerSecretKeys",
            "canUseOAuth2ClientCredentials");

    @Resource
    private OciUserMapper userMapper;

    @Resource
    private DomainManagementService domainManagementService;

    private IdentityClient buildClient(OciUser tenant) {
        SimpleAuthenticationDetailsProvider provider = SimpleAuthenticationDetailsProvider.builder()
                .tenantId(tenant.getOciTenantId())
                .userId(tenant.getOciUserId())
                .fingerprint(tenant.getOciFingerprint())
                .privateKeySupplier(() -> {
                    try (FileInputStream fis = new FileInputStream(tenant.getOciKeyPath());
                         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        return new ByteArrayInputStream(baos.toByteArray());
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to read private key: " + e.getMessage());
                    }
                })
                .region(Region.valueOf(tenant.getOciRegion()))
                .build();

        return IdentityClient.builder().build(provider);
    }

    private OciUser getTenant(String tenantId) {
        OciUser tenant = userMapper.selectById(tenantId);
        if (tenant == null) throw new OciException("租户不存在");
        return tenant;
    }

    public Map<String, Object> listUsers(String tenantId, String domainId, String keyword, int current, int size) {
        OciUser tenant = getTenant(tenantId);
        int page = Math.max(1, current);
        int pageSize = Math.min(Math.max(1, size), 100);
        String normalizedDomainId = StrUtil.trimToNull(domainId);
        if ("__all".equals(normalizedDomainId)) {
            normalizedDomainId = null;
        }
        String normalizedKeyword = StrUtil.trimToNull(keyword);

        List<Map<String, Object>> domains = List.of();
        List<Map<String, Object>> records = List.of();
        int total = 0;
        try (OciClientService oci = domainManagementService.openOciClient(tenant.getId())) {
            domains = domainManagementService.listDomains(oci, true);
            if (!domains.isEmpty()) {
                DomainUserPage domainPage = listDomainUsersPaged(
                        oci, domains, normalizedDomainId, normalizedKeyword, page, pageSize);
                records = domainPage.records();
                total = domainPage.total();
            }
        } catch (Exception e) {
            log.warn("Failed to list users from identity domains: {}", e.getMessage());
        }

        if (domains.isEmpty()) {
            List<Map<String, Object>> classicUsers = listClassicUsers(tenant, normalizedKeyword);
            total = classicUsers.size();
            int from = Math.min((page - 1) * pageSize, total);
            int to = Math.min(from + pageSize, total);
            records = classicUsers.subList(from, to);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", records);
        out.put("total", total);
        out.put("current", page);
        out.put("size", pageSize);
        out.put("domains", domains);
        out.put("domainId", normalizedDomainId);
        out.put("keyword", normalizedKeyword);
        return out;
    }

    private List<Map<String, Object>> listClassicUsers(OciUser tenant, String keyword) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (IdentityClient client = buildClient(tenant)) {
            ListUsersResponse response = client.listUsers(
                    ListUsersRequest.builder()
                            .compartmentId(tenant.getOciTenantId())
                            .build()
            );

            for (User user : response.getItems()) {
                if (!classicUserMatches(user, keyword)) {
                    continue;
                }
                Map<String, Object> map = classicUserToMap(user);
                boolean hasMfa = false;
                try {
                    var mfaResp = client.listMfaTotpDevices(
                            ListMfaTotpDevicesRequest.builder().userId(user.getId()).build());
                    hasMfa = mfaResp.getItems() != null && !mfaResp.getItems().isEmpty();
                } catch (Exception ignored) {}
                map.put("isMfaActivated", hasMfa);

                result.add(map);
            }
        }
        return result;
    }

    private boolean classicUserMatches(User user, String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return true;
        }
        String q = keyword.toLowerCase(Locale.ROOT);
        return containsIgnoreCase(user.getName(), q)
                || containsIgnoreCase(user.getEmail(), q)
                || containsIgnoreCase(user.getDescription(), q);
    }

    private boolean containsIgnoreCase(String value, String lowerKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerKeyword);
    }

    private Map<String, Object> classicUserToMap(User user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("rowKey", "classic:" + user.getId());
        map.put("name", user.getName());
        map.put("email", user.getEmail());
        map.put("description", user.getDescription());
        map.put("state", user.getLifecycleState() == null ? null : user.getLifecycleState().getValue());
        map.put("domainId", null);
        map.put("domainName", "Classic IAM");
        map.put("domainType", "CLASSIC");
        map.put("lastSuccessfulLoginTime", user.getLastSuccessfulLoginTime() != null
                ? user.getLastSuccessfulLoginTime().toString()
                : null);
        map.put("previousSuccessfulLoginTime", user.getPreviousSuccessfulLoginTime() != null
                ? user.getPreviousSuccessfulLoginTime().toString()
                : null);
        map.put("timeCreated", user.getTimeCreated() != null ? user.getTimeCreated().toString() : null);
        return map;
    }

    private DomainUserPage listDomainUsersPaged(
            OciClientService oci,
            List<Map<String, Object>> domains,
            String domainId,
            String keyword,
            int current,
            int size) {
        List<Map<String, Object>> result = new ArrayList<>();
        int total = 0;
        int offset = (current - 1) * size;
        int remaining = size;
        String filter = buildUserFilter(keyword);
        for (Map<String, Object> domain : domains) {
            if (StrUtil.isNotBlank(domainId) && !domainId.equals(String.valueOf(domain.get("id")))) {
                continue;
            }
            String url = (String) domain.get("url");
            if (StrUtil.isBlank(url)) {
                continue;
            }
            try (IdentityDomainsClient dc = IdentityDomainsClient.builder().build(oci.getProvider())) {
                dc.setEndpoint(url);
                DomainUserPage countPage = queryDomainUsers(dc, domain, filter, 1, 1);
                int domainTotal = countPage.total();
                total += domainTotal;
                if (remaining <= 0) {
                    continue;
                }
                if (offset >= domainTotal) {
                    offset -= domainTotal;
                    continue;
                }
                int startIndex = offset + 1;
                int count = Math.min(remaining, domainTotal - offset);
                DomainUserPage page = queryDomainUsers(dc, domain, filter, startIndex, count);
                result.addAll(page.records());
                remaining -= page.records().size();
                offset = 0;
            } catch (Exception e) {
                log.warn("Failed to list users in identity domain {}: {}",
                        domain.get("displayName"), e.getMessage());
            }
        }
        return new DomainUserPage(total, result);
    }

    private DomainUserPage queryDomainUsers(
            IdentityDomainsClient dc,
            Map<String, Object> domain,
            String filter,
            int startIndex,
            int count) {
        var builder = com.oracle.bmc.identitydomains.requests.ListUsersRequest.builder()
                .attributes(SCIM_USER_LIST_ATTRIBUTES)
                .startIndex(startIndex)
                .count(count);
        if (StrUtil.isNotBlank(filter)) {
            builder.filter(filter);
        }
        com.oracle.bmc.identitydomains.responses.ListUsersResponse response = dc.listUsers(builder.build());
        com.oracle.bmc.identitydomains.model.Users users = response.getUsers();
        int total = users == null || users.getTotalResults() == null ? 0 : users.getTotalResults();
        List<Map<String, Object>> records = new ArrayList<>();
        if (users != null && users.getResources() != null) {
            Set<String> seenRowKeys = new LinkedHashSet<>();
            for (com.oracle.bmc.identitydomains.model.User user : users.getResources()) {
                Map<String, Object> map = domainUserToMap(user, domain);
                String rowKey = map.get("rowKey") == null ? null : String.valueOf(map.get("rowKey"));
                if (StrUtil.isBlank(rowKey) || !seenRowKeys.add(rowKey)) {
                    continue;
                }
                records.add(map);
            }
        }
        return new DomainUserPage(total, records);
    }

    private String buildUserFilter(String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return null;
        }
        String q = escapeScimFilterValue(keyword.trim());
        return "(userName co \"" + q + "\" or displayName co \"" + q + "\" or emails.value co \"" + q + "\")";
    }

    private String escapeScimFilterValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Map<String, Object> domainUserToMap(
            com.oracle.bmc.identitydomains.model.User user,
            Map<String, Object> domain) {
        Map<String, Object> map = new LinkedHashMap<>();
        String id = StrUtil.isNotBlank(user.getOcid()) ? user.getOcid() : user.getId();
        String domainId = domain.get("id") == null ? String.valueOf(domain.get("displayName")) : String.valueOf(domain.get("id"));
        map.put("id", id);
        map.put("rowKey", domainId + ":" + StrUtil.blankToDefault(user.getId(), id));
        map.put("scimId", user.getId());
        map.put("name", StrUtil.blankToDefault(user.getUserName(), user.getDisplayName()));
        map.put("email", firstEmailValue(user, null));
        map.put("description", user.getDescription());
        map.put("state", Boolean.FALSE.equals(user.getActive()) ? "INACTIVE" : "ACTIVE");
        map.put("domainId", domainId);
        map.put("domainName", domain.get("displayName"));
        map.put("domainType", domain.get("type"));
        var userState = user.getUrnIetfParamsScimSchemasOracleIdcsExtensionUserStateUser();
        map.put("lastSuccessfulLoginTime", userState != null ? userState.getLastSuccessfulLoginDate() : null);
        map.put("previousSuccessfulLoginTime", userState != null ? userState.getPreviousSuccessfulLoginDate() : null);
        map.put("timeCreated", user.getMeta() != null ? user.getMeta().getCreated() : null);
        var mfa = user.getUrnIetfParamsScimSchemasOracleIdcsExtensionMfaUser();
        String mfaStatus = mfa == null || mfa.getMfaStatus() == null ? null : mfa.getMfaStatus().getValue();
        map.put("isMfaActivated", "Enrolled".equalsIgnoreCase(mfaStatus));
        return map;
    }

    private record DomainUserPage(int total, List<Map<String, Object>> records) {}

    public Map<String, Object> createUser(UserParams params) {
        OciUser tenant = getTenant(params.getTenantId());
        String domainId = StrUtil.trimToNull(params.getDomainId());
        if (domainId == null) {
            return createUserViaIamApi(tenant, params);
        }
        try (OciClientService oci = domainManagementService.openOciClient(tenant.getId())) {
            List<Map<String, Object>> domains = domainManagementService.listDomains(oci, false);
            Map<String, Object> selected = null;
            for (Map<String, Object> d : domains) {
                if (domainId.equals(d.get("id"))) {
                    selected = d;
                    break;
                }
            }
            if (selected == null) {
                throw new OciException("未找到指定的 Identity Domain，请刷新域列表后重试");
            }
            if (isDefaultDomainForClassicIam(selected)) {
                return createUserViaIamApi(tenant, params);
            }
            return createUserViaIdentityDomainsApi(oci, selected, params, tenant);
        }
    }

    /**
     * 使用 IAM Service API（20160918）在 Default identity domain 中创建用户，参见 Oracle 文档：
     * Identity Domains 环境下该 API 仅作用于 Default 域。
     */
    private Map<String, Object> createUserViaIamApi(OciUser tenant, UserParams params) {
        try (IdentityClient client = buildClient(tenant)) {
            CreateUserDetails.Builder builder = CreateUserDetails.builder()
                    .compartmentId(tenant.getOciTenantId())
                    .name(params.getUserName())
                    .description(params.getEmail() != null ? params.getEmail() : params.getUserName())
                    .email(params.getEmail());

            CreateUserResponse response = client.createUser(
                    CreateUserRequest.builder()
                            .createUserDetails(builder.build())
                            .build()
            );

            User created = response.getUser();
            log.info("Created user (IAM API): {} in tenant: {}", created.getName(), tenant.getUsername());

            if (Boolean.TRUE.equals(params.getAddToAdminGroup())) {
                addToAdminGroup(client, tenant.getOciTenantId(), created.getId());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", created.getId());
            result.put("name", created.getName());
            result.put("email", created.getEmail());
            result.put("state", created.getLifecycleState().getValue());
            return result;
        }
    }

    private static boolean isDefaultDomainForClassicIam(Map<String, Object> domain) {
        return "Default".equals(String.valueOf(domain.get("displayName")));
    }

    /**
     * 在非 Default 的 identity domain 中通过 IAM Identity Domains API（SCIM）创建用户。
     */
    private Map<String, Object> createUserViaIdentityDomainsApi(
            OciClientService oci,
            Map<String, Object> domain,
            UserParams params,
            OciUser tenant) {
        String url = (String) domain.get("url");
        if (StrUtil.isBlank(url)) {
            throw new OciException("该 Identity Domain 缺少 URL，无法创建用户");
        }
        // Identity Domains SCIM 要求 User 资源包含 name（与 userName 不同）
        String loginName = StrUtil.blankToDefault(StrUtil.trim(params.getUserName()), "User");
        UserName scimName = UserName.builder()
                .formatted(loginName)
                .givenName(loginName)
                .familyName(loginName)
                .build();
        com.oracle.bmc.identitydomains.model.User.Builder ub =
                com.oracle.bmc.identitydomains.model.User.builder()
                        .schemas(List.of(SCIM_SCHEMA_USER))
                        .userName(loginName)
                        .name(scimName)
                        .active(Boolean.TRUE)
                        .description(StrUtil.isNotBlank(params.getEmail()) ? params.getEmail() : loginName);
        if (StrUtil.isNotBlank(params.getEmail())) {
            ub.emails(List.of(
                    UserEmails.builder()
                            .value(params.getEmail())
                            .type(UserEmails.Type.Work)
                            .primary(true)
                            .build()
            ));
        }
        com.oracle.bmc.identitydomains.model.User scimUser = ub.build();

        try (IdentityDomainsClient dc = IdentityDomainsClient.builder().build(oci.getProvider())) {
            dc.setEndpoint(url);
            com.oracle.bmc.identitydomains.responses.CreateUserResponse response = dc.createUser(
                    com.oracle.bmc.identitydomains.requests.CreateUserRequest.builder().user(scimUser).build());
            com.oracle.bmc.identitydomains.model.User created = response.getUser();
            log.info("Created user (Identity Domains API): {} in domain {} tenant {}",
                    created.getUserName(), domain.get("displayName"), tenant.getUsername());

            if (created.getId() != null) {
                applyIdentityDomainGroupAssignments(dc, created.getId(), params);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            String id = StrUtil.isNotBlank(created.getOcid()) ? created.getOcid() : created.getId();
            result.put("id", id);
            result.put("name", created.getUserName());
            result.put("email", firstEmailValue(created, params.getEmail()));
            result.put("state", Boolean.FALSE.equals(created.getActive()) ? "INACTIVE" : "ACTIVE");
            return result;
        }
    }

    private static String firstEmailValue(com.oracle.bmc.identitydomains.model.User u, String fallback) {
        if (u.getEmails() != null) {
            for (Object o : u.getEmails()) {
                if (o instanceof UserEmails ue && StrUtil.isNotBlank(ue.getValue())) {
                    return ue.getValue();
                }
                if (o instanceof Map<?, ?> m && m.get("value") != null) {
                    return String.valueOf(m.get("value"));
                }
            }
        }
        return fallback;
    }

    private void applyIdentityDomainGroupAssignments(
            IdentityDomainsClient dc, String userScimId, UserParams params) {
        List<String> groupIds = params.getGroupIds();
        if (groupIds != null && !groupIds.isEmpty()) {
            for (String groupId : groupIds) {
                if (StrUtil.isNotBlank(groupId)) {
                    addUserToGroupIdentityDomains(dc, userScimId, groupId.trim());
                }
            }
            return;
        }
        if (Boolean.TRUE.equals(params.getAddToAdminGroup())) {
            addUserToAdministratorsGroupIdentityDomains(dc, userScimId);
        }
    }

    private void addUserToAdministratorsGroupIdentityDomains(IdentityDomainsClient dc, String userScimId) {
        try {
            com.oracle.bmc.identitydomains.responses.ListGroupsResponse listResp = dc.listGroups(
                    com.oracle.bmc.identitydomains.requests.ListGroupsRequest.builder()
                            .filter("displayName eq \"Administrators\"")
                            .count(50)
                            .build());
            Groups groups = listResp.getGroups();
            if (groups == null || groups.getResources() == null || groups.getResources().isEmpty()) {
                log.warn("Administrators group not found in identity domain, skip addToAdminGroup");
                return;
            }
            Object raw = groups.getResources().get(0);
            if (!(raw instanceof com.oracle.bmc.identitydomains.model.Group adminGroup)) {
                log.warn("Unexpected group resource type, skip addToAdminGroup");
                return;
            }
            String groupId = adminGroup.getId();
            if (StrUtil.isBlank(groupId)) {
                return;
            }
            addUserToGroupIdentityDomains(dc, userScimId, groupId);
        } catch (Exception e) {
            log.warn("Failed to add user to Administrators in identity domain: {}", e.getMessage());
        }
    }

    private void addUserToGroupIdentityDomains(IdentityDomainsClient dc, String userScimId, String groupId) {
        try {
            com.oracle.bmc.identitydomains.responses.GetGroupResponse getResp = dc.getGroup(
                    com.oracle.bmc.identitydomains.requests.GetGroupRequest.builder().groupId(groupId).build());
            String ifMatch = headerValueIgnoreCase(getResp, "etag");
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("value", userScimId);
            member.put("type", "User");
            PatchOp patchOp = PatchOp.builder()
                    .schemas(List.of(SCIM_SCHEMA_PATCH_OP))
                    .operations(List.of(
                            Operations.builder()
                                    .op(Operations.Op.Add)
                                    .path("members")
                                    .value(List.of(member))
                                    .build()
                    ))
                    .build();
            PatchGroupRequest.Builder pr = PatchGroupRequest.builder()
                    .groupId(groupId)
                    .patchOp(patchOp);
            if (StrUtil.isNotBlank(ifMatch)) {
                pr.ifMatch(ifMatch);
            }
            dc.patchGroup(pr.build());
            log.info("Added user {} to identity domain group {}", userScimId, groupId);
        } catch (Exception e) {
            log.warn("Failed to add user {} to identity domain group {}: {}", userScimId, groupId, e.getMessage());
        }
    }

    private static boolean isHiddenDomainGroupName(String name) {
        if (StrUtil.isBlank(name)) {
            return false;
        }
        String n = name.trim().toLowerCase();
        return "all domain users".equals(n);
    }

    /**
     * Default 域返回经典 IAM 组；其它 Identity Domain 返回该域 SCIM 组列表。
     */
    public List<Map<String, Object>> listDomainGroups(String tenantId, String domainId) {
        if (StrUtil.isBlank(domainId)) {
            return listGroupsFiltered(tenantId);
        }
        try (OciClientService oci = domainManagementService.openOciClient(tenantId)) {
            List<Map<String, Object>> domains = domainManagementService.listDomains(oci, false);
            Map<String, Object> selected = null;
            for (Map<String, Object> d : domains) {
                if (domainId.equals(d.get("id"))) {
                    selected = d;
                    break;
                }
            }
            if (selected == null) {
                throw new OciException("未找到指定的 Identity Domain");
            }
            if (isDefaultDomainForClassicIam(selected)) {
                return listGroupsFiltered(tenantId);
            }
            String url = (String) selected.get("url");
            if (StrUtil.isBlank(url)) {
                throw new OciException("该 Identity Domain 缺少 URL，无法列出组");
            }
            List<Map<String, Object>> result = new ArrayList<>();
            try (IdentityDomainsClient dc = IdentityDomainsClient.builder().build(oci.getProvider())) {
                dc.setEndpoint(url);
                int startIndex = 1;
                final int pageSize = 100;
                while (true) {
                    com.oracle.bmc.identitydomains.responses.ListGroupsResponse listResp = dc.listGroups(
                            com.oracle.bmc.identitydomains.requests.ListGroupsRequest.builder()
                                    .count(pageSize)
                                    .startIndex(startIndex)
                                    .build());
                    Groups wrapper = listResp.getGroups();
                    if (wrapper == null || wrapper.getResources() == null || wrapper.getResources().isEmpty()) {
                        break;
                    }
                    for (Object raw : wrapper.getResources()) {
                        if (!(raw instanceof com.oracle.bmc.identitydomains.model.Group g)) {
                            continue;
                        }
                        String name = g.getDisplayName() != null ? g.getDisplayName() : g.getId();
                        if (isHiddenDomainGroupName(name)) {
                            continue;
                        }
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("id", g.getId());
                        map.put("name", name);
                        map.put("description", null);
                        result.add(map);
                    }
                    if (wrapper.getResources().size() < pageSize) {
                        break;
                    }
                    startIndex += pageSize;
                }
            }
            return result;
        }
    }

    private List<Map<String, Object>> listGroupsFiltered(String tenantId) {
        List<Map<String, Object>> all = listGroups(tenantId);
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> g : all) {
            String name = g.get("name") == null ? null : String.valueOf(g.get("name"));
            if (!isHiddenDomainGroupName(name)) {
                filtered.add(g);
            }
        }
        return filtered;
    }

    private static String headerValueIgnoreCase(com.oracle.bmc.responses.BmcResponse resp, String name) {
        if (resp.getHeaders() == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> e : resp.getHeaders().entrySet()) {
            if (name.equalsIgnoreCase(e.getKey()) && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    public void resetPassword(UserParams params) {
        resetPasswordWithResult(params);
    }

    public String getResetPasswordResult(UserParams params) {
        return resetPasswordWithResult(params);
    }

    /**
     * 先尝试经典 IAM {@code CreateOrResetUIPassword}；若返回 404/400（身份域租户常见），再在各 Identity Domain 内
     * 按 OCID 查找用户并用 {@code PutUserPasswordChanger} 设置随机密码（与 Oracle 推荐域内 API 一致）。
     */
    private String resetPasswordWithResult(UserParams params) {
        OciUser tenant = getTenant(params.getTenantId());
        String userId = params.getUserId();
        if (StrUtil.isBlank(userId)) {
            throw new OciException("userId 不能为空");
        }
        try (IdentityClient client = buildClient(tenant)) {
            CreateOrResetUIPasswordResponse response = client.createOrResetUIPassword(
                    CreateOrResetUIPasswordRequest.builder().userId(userId).build());
            if (response.getUIPassword() != null && StrUtil.isNotBlank(response.getUIPassword().getPassword())) {
                log.info("Password reset (classic IAM) for user: {}", userId);
                return response.getUIPassword().getPassword();
            }
        } catch (BmcException e) {
            int code = e.getStatusCode();
            if (code != 404 && code != 400) {
                throw e;
            }
            log.info("Classic CreateOrResetUIPassword returned {}, trying Identity Domains API for user {}", code, userId);
        }
        return resetPasswordViaIdentityDomains(tenant, userId);
    }

    private static String generateAdminResetPassword() {
        String chars = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        ThreadLocalRandom r = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String resetPasswordViaIdentityDomains(OciUser tenant, String classicUserOcid) {
        String newPassword = generateAdminResetPassword();
        try (OciClientService oci = domainManagementService.openOciClient(tenant.getId())) {
            List<Map<String, Object>> domains = domainManagementService.listDomains(oci, false);
            if (domains.isEmpty()) {
                throw new OciException("未找到 Identity Domain，无法通过域 API 重置密码");
            }
            UserPasswordChanger body = UserPasswordChanger.builder()
                    .schemas(List.of(SCHEMA_USER_PASSWORD_CHANGER))
                    .password(newPassword)
                    .bypassNotification(true)
                    .build();

            for (Map<String, Object> d : domains) {
                String url = (String) d.get("url");
                if (StrUtil.isBlank(url)) {
                    continue;
                }
                String domainLabel = String.valueOf(d.get("displayName"));
                try (IdentityDomainsClient dc = IdentityDomainsClient.builder().build(oci.getProvider())) {
                    dc.setEndpoint(url);
                    com.oracle.bmc.identitydomains.responses.ListUsersResponse listResp = dc.listUsers(
                            com.oracle.bmc.identitydomains.requests.ListUsersRequest.builder()
                                    .filter("ocid eq \"" + classicUserOcid + "\"")
                                    .attributes("id,ocid,userName")
                                    .count(10)
                                    .build());
                    com.oracle.bmc.identitydomains.model.Users wrapper = listResp.getUsers();
                    if (wrapper == null || wrapper.getResources() == null || wrapper.getResources().isEmpty()) {
                        continue;
                    }
                    Object raw = wrapper.getResources().get(0);
                    if (!(raw instanceof com.oracle.bmc.identitydomains.model.User domainUser)) {
                        continue;
                    }
                    String scimUserId = domainUser.getId();
                    if (StrUtil.isBlank(scimUserId)) {
                        continue;
                    }
                    try {
                        dc.putUserPasswordChanger(
                                PutUserPasswordChangerRequest.builder()
                                        .userPasswordChangerId(scimUserId)
                                        .userPasswordChanger(body)
                                        .build());
                    } catch (BmcException ex) {
                        int sc = ex.getStatusCode();
                        if (sc == 401 || sc == 403) {
                            throw new OciException(
                                    "重置密码失败：API Key 对应用户在域「" + domainLabel
                                            + "」中权限不足。请在 OCI 控制台：身份与安全 → 域 → 该域 → 管理员 → "
                                            + "将「用户管理员(User Administrator)」授予当前 API Key 所属用户后重试。");
                        }
                        throw ex;
                    }
                    log.info("Password reset (Identity Domains) domain={} userOcid={}", domainLabel, classicUserOcid);
                    return newPassword;
                } catch (BmcException ex) {
                    if (ex.getStatusCode() == 404) {
                        log.debug("User OCID not in domain {}: {}", domainLabel, ex.getMessage());
                        continue;
                    }
                    throw ex;
                }
            }
        }
        throw new OciException("在任一 Identity Domain 中未找到该用户（OCID），或无法完成密码重置: " + classicUserOcid);
    }

    public void clearMfa(UserParams params) {
        OciUser tenant = getTenant(params.getTenantId());
        try (IdentityClient client = buildClient(tenant)) {
            ListMfaTotpDevicesResponse mfaResponse = client.listMfaTotpDevices(
                    ListMfaTotpDevicesRequest.builder()
                            .userId(params.getUserId())
                            .build()
            );
            for (MfaTotpDeviceSummary device : mfaResponse.getItems()) {
                client.deleteMfaTotpDevice(
                        DeleteMfaTotpDeviceRequest.builder()
                                .userId(params.getUserId())
                                .mfaTotpDeviceId(device.getId())
                                .build()
                );
                log.info("Deleted MFA device: {} for user: {}", device.getId(), params.getUserId());
            }
        }
    }

    public List<Map<String, Object>> listGroups(String tenantId) {
        OciUser tenant = getTenant(tenantId);
        try (IdentityClient client = buildClient(tenant)) {
            ListGroupsResponse response = client.listGroups(
                    ListGroupsRequest.builder()
                            .compartmentId(tenant.getOciTenantId())
                            .build()
            );
            List<Map<String, Object>> result = new ArrayList<>();
            for (Group group : response.getItems()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", group.getId());
                map.put("name", group.getName());
                map.put("description", group.getDescription());
                map.put("state", group.getLifecycleState().getValue());
                result.add(map);
            }
            return result;
        }
    }

    public void addUserToGroup(UserParams params) {
        OciUser tenant = getTenant(params.getTenantId());
        try (IdentityClient client = buildClient(tenant)) {
            String adminGroupId = findAdminGroupId(client, tenant.getOciTenantId());
            if (adminGroupId == null) throw new OciException("未找到管理员组");

            client.addUserToGroup(
                    AddUserToGroupRequest.builder()
                            .addUserToGroupDetails(AddUserToGroupDetails.builder()
                                    .userId(params.getUserId())
                                    .groupId(adminGroupId)
                                    .build())
                            .build()
            );
            log.info("Added user {} to admin group", params.getUserId());
        }
    }

    public void removeUserFromGroup(UserParams params) {
        OciUser tenant = getTenant(params.getTenantId());
        try (IdentityClient client = buildClient(tenant)) {
            String adminGroupId = findAdminGroupId(client, tenant.getOciTenantId());
            if (adminGroupId == null) throw new OciException("未找到管理员组");

            ListUserGroupMembershipsResponse memberships = client.listUserGroupMemberships(
                    ListUserGroupMembershipsRequest.builder()
                            .compartmentId(tenant.getOciTenantId())
                            .userId(params.getUserId())
                            .groupId(adminGroupId)
                            .build()
            );

            for (UserGroupMembership membership : memberships.getItems()) {
                client.removeUserFromGroup(
                        RemoveUserFromGroupRequest.builder()
                                .userGroupMembershipId(membership.getId())
                                .build()
                );
                log.info("Removed user {} from admin group", params.getUserId());
            }
        }
    }

    public List<String> getUserGroupNames(String tenantId, String userId) {
        return getUserGroups(tenantId, userId).stream()
                .map(g -> g.get("name") == null ? null : String.valueOf(g.get("name")))
                .filter(StrUtil::isNotBlank)
                .toList();
    }

    public List<Map<String, Object>> getUserGroups(String tenantId, String userId) {
        OciUser tenant = getTenant(tenantId);
        try (IdentityClient client = buildClient(tenant)) {
            ListUserGroupMembershipsResponse memberships = client.listUserGroupMemberships(
                    ListUserGroupMembershipsRequest.builder()
                            .compartmentId(tenant.getOciTenantId())
                            .userId(userId)
                            .build()
            );

            List<Map<String, Object>> result = new ArrayList<>();
            if (memberships.getItems() == null) {
                return result;
            }
            for (UserGroupMembership m : memberships.getItems()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("membershipId", m.getId());
                map.put("groupId", m.getGroupId());
                String name = m.getGroupId();
                try {
                    Group group = client.getGroup(
                            GetGroupRequest.builder().groupId(m.getGroupId()).build()
                    ).getGroup();
                    if (group != null && StrUtil.isNotBlank(group.getName())) {
                        name = group.getName();
                    }
                } catch (Exception ignored) {
                }
                map.put("name", name);
                result.add(map);
            }
            return result;
        }
    }

    public void syncUserGroups(String tenantId, String userId, List<String> targetGroupIds) {
        OciUser tenant = getTenant(tenantId);
        LinkedHashSet<String> target = new LinkedHashSet<>();
        if (targetGroupIds != null) {
            for (String id : targetGroupIds) {
                if (StrUtil.isNotBlank(id)) {
                    target.add(id.trim());
                }
            }
        }
        try (IdentityClient client = buildClient(tenant)) {
            String compartmentId = tenant.getOciTenantId();
            ListUserGroupMembershipsResponse memberships = client.listUserGroupMemberships(
                    ListUserGroupMembershipsRequest.builder()
                            .compartmentId(compartmentId)
                            .userId(userId)
                            .build()
            );

            Set<String> currentGroupIds = new LinkedHashSet<>();
            Map<String, String> membershipByGroupId = new LinkedHashMap<>();
            if (memberships.getItems() != null) {
                for (UserGroupMembership m : memberships.getItems()) {
                    if (m.getGroupId() != null) {
                        currentGroupIds.add(m.getGroupId());
                        membershipByGroupId.put(m.getGroupId(), m.getId());
                    }
                }
            }

            for (String groupId : currentGroupIds) {
                if (!target.contains(groupId)) {
                    String membershipId = membershipByGroupId.get(groupId);
                    if (StrUtil.isNotBlank(membershipId)) {
                        client.removeUserFromGroup(
                                RemoveUserFromGroupRequest.builder()
                                        .userGroupMembershipId(membershipId)
                                        .build());
                        log.info("Removed user {} from group {}", userId, groupId);
                    }
                }
            }

            for (String groupId : target) {
                if (!currentGroupIds.contains(groupId)) {
                    client.addUserToGroup(
                            AddUserToGroupRequest.builder()
                                    .addUserToGroupDetails(AddUserToGroupDetails.builder()
                                            .userId(userId)
                                            .groupId(groupId)
                                            .build())
                                    .build());
                    log.info("Added user {} to group {}", userId, groupId);
                }
            }
        }
    }

    private void addToAdminGroup(IdentityClient client, String compartmentId, String userId) {
        String adminGroupId = findAdminGroupId(client, compartmentId);
        if (adminGroupId == null) {
            log.warn("Admin group not found, skipping group assignment");
            return;
        }
        try {
            client.addUserToGroup(
                    AddUserToGroupRequest.builder()
                            .addUserToGroupDetails(AddUserToGroupDetails.builder()
                                    .userId(userId)
                                    .groupId(adminGroupId)
                                    .build())
                            .build()
            );
        } catch (Exception e) {
            log.warn("Failed to add user to admin group: {}", e.getMessage());
        }
    }

    public void updateUser(UserParams params) {
        OciUser tenant = getTenant(params.getTenantId());
        try (IdentityClient client = buildClient(tenant)) {
            UpdateUserDetails.Builder builder = UpdateUserDetails.builder();
            if (StrUtil.isNotBlank(params.getEmail())) builder.email(params.getEmail());
            if (StrUtil.isNotBlank(params.getUserName())) builder.description(params.getUserName());

            client.updateUser(UpdateUserRequest.builder()
                    .userId(params.getUserId())
                    .updateUserDetails(builder.build())
                    .build());
            log.info("Updated user: {}", params.getUserId());
        }
    }

    public Map<String, Object> getUserCapabilities(String tenantId, String userId) {
        OciUser tenant = getTenant(tenantId);
        try (IdentityClient client = buildClient(tenant)) {
            User user = client.getUser(GetUserRequest.builder().userId(userId).build()).getUser();
            return capabilitiesToMap(user == null ? null : user.getCapabilities());
        }
    }

    public void updateUserCapabilities(UserParams params) {
        OciUser tenant = getTenant(params.getTenantId());
        Map<String, Boolean> caps = params.getCapabilities();
        if (caps == null || caps.isEmpty()) {
            throw new OciException("请至少指定一项用户权限");
        }
        UpdateUserCapabilitiesDetails.Builder builder = UpdateUserCapabilitiesDetails.builder();
        if (caps.containsKey("canUseConsolePassword")) {
            builder.canUseConsolePassword(caps.get("canUseConsolePassword"));
        }
        if (caps.containsKey("canUseApiKeys")) {
            builder.canUseApiKeys(caps.get("canUseApiKeys"));
        }
        if (caps.containsKey("canUseAuthTokens")) {
            builder.canUseAuthTokens(caps.get("canUseAuthTokens"));
        }
        if (caps.containsKey("canUseSmtpCredentials")) {
            builder.canUseSmtpCredentials(caps.get("canUseSmtpCredentials"));
        }
        if (caps.containsKey("canUseDbCredentials")) {
            builder.canUseDBCredentials(caps.get("canUseDbCredentials"));
        }
        if (caps.containsKey("canUseCustomerSecretKeys")) {
            builder.canUseCustomerSecretKeys(caps.get("canUseCustomerSecretKeys"));
        }
        if (caps.containsKey("canUseOAuth2ClientCredentials")) {
            builder.canUseOAuth2ClientCredentials(caps.get("canUseOAuth2ClientCredentials"));
        }
        try (IdentityClient client = buildClient(tenant)) {
            client.updateUserCapabilities(UpdateUserCapabilitiesRequest.builder()
                    .userId(params.getUserId())
                    .updateUserCapabilitiesDetails(builder.build())
                    .build());
            log.info("Updated user capabilities: {}", params.getUserId());
        }
    }

    private static Map<String, Object> capabilitiesToMap(UserCapabilities caps) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : CAPABILITY_KEYS) {
            map.put(key, capabilityValue(caps, key));
        }
        return map;
    }

    private static boolean capabilityValue(UserCapabilities caps, String key) {
        if (caps == null) {
            return false;
        }
        Boolean v = switch (key) {
            case "canUseConsolePassword" -> caps.getCanUseConsolePassword();
            case "canUseApiKeys" -> caps.getCanUseApiKeys();
            case "canUseAuthTokens" -> caps.getCanUseAuthTokens();
            case "canUseSmtpCredentials" -> caps.getCanUseSmtpCredentials();
            case "canUseDbCredentials" -> caps.getCanUseDbCredentials();
            case "canUseCustomerSecretKeys" -> caps.getCanUseCustomerSecretKeys();
            case "canUseOAuth2ClientCredentials" -> caps.getCanUseOAuth2ClientCredentials();
            default -> null;
        };
        return Boolean.TRUE.equals(v);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Boolean> parseCapabilitiesMap(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String key : CAPABILITY_KEYS) {
            if (m.containsKey(key)) {
                out.put(key, Boolean.TRUE.equals(m.get(key)));
            }
        }
        return out;
    }

    public void updateUserState(String tenantId, String userId, boolean blocked) {
        OciUser tenant = getTenant(tenantId);
        try (IdentityClient client = buildClient(tenant)) {
            client.updateUserState(UpdateUserStateRequest.builder()
                    .userId(userId)
                    .updateStateDetails(UpdateStateDetails.builder()
                            .blocked(blocked)
                            .build())
                    .build());
            log.info("User {} state updated, blocked={}", userId, blocked);
        }
    }

    public List<Map<String, Object>> listMfaDevices(String tenantId, String userId) {
        OciUser tenant = getTenant(tenantId);
        try (IdentityClient client = buildClient(tenant)) {
            ListMfaTotpDevicesResponse response = client.listMfaTotpDevices(
                    ListMfaTotpDevicesRequest.builder().userId(userId).build());
            List<Map<String, Object>> result = new ArrayList<>();
            for (MfaTotpDeviceSummary device : response.getItems()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", device.getId());
                map.put("state", device.getLifecycleState().getValue());
                map.put("isActivated", device.getIsActivated());
                map.put("timeCreated", device.getTimeCreated() != null ? device.getTimeCreated().toString() : null);
                result.add(map);
            }
            return result;
        }
    }

    private String findAdminGroupId(IdentityClient client, String compartmentId) {
        ListGroupsResponse response = client.listGroups(
                ListGroupsRequest.builder()
                        .compartmentId(compartmentId)
                        .build()
        );
        for (Group group : response.getItems()) {
            if ("Administrators".equalsIgnoreCase(group.getName())) {
                return group.getId();
            }
        }
        return null;
    }
}
