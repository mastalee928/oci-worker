package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.*;
import com.oracle.bmc.identity.requests.*;
import com.oracle.bmc.identity.responses.*;
import com.oracle.bmc.identitydomains.IdentityDomainsClient;
import com.oracle.bmc.identitydomains.model.Group;
import com.oracle.bmc.identitydomains.model.Groups;
import com.oracle.bmc.identitydomains.model.Operations;
import com.oracle.bmc.identitydomains.model.PatchOp;
import com.oracle.bmc.identitydomains.requests.GetGroupRequest;
import com.oracle.bmc.identitydomains.requests.ListGroupsRequest;
import com.oracle.bmc.identitydomains.requests.PatchGroupRequest;
import com.oracle.bmc.identitydomains.responses.GetGroupResponse;
import com.oracle.bmc.identitydomains.responses.ListGroupsResponse;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.params.UserParams;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;

@Slf4j
@Service
public class UserManagementService {

    private static final String SCIM_SCHEMA_USER = "urn:ietf:params:scim:schemas:core:2.0:User";
    private static final String SCIM_SCHEMA_PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

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

    public List<Map<String, Object>> listUsers(String tenantId) {
        OciUser tenant = getTenant(tenantId);
        try (IdentityClient client = buildClient(tenant)) {
            ListUsersResponse response = client.listUsers(
                    ListUsersRequest.builder()
                            .compartmentId(tenant.getOciTenantId())
                            .build()
            );

            List<Map<String, Object>> result = new ArrayList<>();
            for (User user : response.getItems()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", user.getId());
                map.put("name", user.getName());
                map.put("email", user.getEmail());
                map.put("description", user.getDescription());
                map.put("state", user.getLifecycleState().getValue());
                map.put("timeCreated", user.getTimeCreated() != null ? user.getTimeCreated().toString() : null);

                boolean hasMfa = false;
                try {
                    var mfaResp = client.listMfaTotpDevices(
                            ListMfaTotpDevicesRequest.builder().userId(user.getId()).build());
                    hasMfa = mfaResp.getItems() != null && !mfaResp.getItems().isEmpty();
                } catch (Exception ignored) {}
                map.put("isMfaActivated", hasMfa);

                result.add(map);
            }
            return result;
        }
    }

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
        com.oracle.bmc.identitydomains.model.User.Builder ub =
                com.oracle.bmc.identitydomains.model.User.builder()
                        .schemas(List.of(SCIM_SCHEMA_USER))
                        .userName(params.getUserName())
                        .active(Boolean.TRUE)
                        .description(StrUtil.isNotBlank(params.getEmail()) ? params.getEmail() : params.getUserName());
        if (StrUtil.isNotBlank(params.getEmail())) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("value", params.getEmail());
            em.put("type", "work");
            em.put("primary", true);
            ub.emails(List.of(em));
        }
        com.oracle.bmc.identitydomains.model.User scimUser = ub.build();

        try (IdentityDomainsClient dc = IdentityDomainsClient.builder().build(oci.getProvider())) {
            dc.setEndpoint(url);
            com.oracle.bmc.identitydomains.responses.CreateUserResponse response = dc.createUser(
                    com.oracle.bmc.identitydomains.requests.CreateUserRequest.builder().user(scimUser).build());
            com.oracle.bmc.identitydomains.model.User created = response.getUser();
            log.info("Created user (Identity Domains API): {} in domain {} tenant {}",
                    created.getUserName(), domain.get("displayName"), tenant.getUsername());

            if (Boolean.TRUE.equals(params.getAddToAdminGroup()) && created.getId() != null) {
                addUserToAdministratorsGroupIdentityDomains(dc, created.getId());
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
                if (o instanceof Map<?, ?> m && m.get("value") != null) {
                    return String.valueOf(m.get("value"));
                }
            }
        }
        return fallback;
    }

    private void addUserToAdministratorsGroupIdentityDomains(IdentityDomainsClient dc, String userScimId) {
        try {
            ListGroupsResponse listResp = dc.listGroups(
                    ListGroupsRequest.builder()
                            .filter("displayName eq \"Administrators\"")
                            .count(50)
                            .build());
            Groups groups = listResp.getGroups();
            if (groups == null || groups.getResources() == null || groups.getResources().isEmpty()) {
                log.warn("Administrators group not found in identity domain, skip addToAdminGroup");
                return;
            }
            Object raw = groups.getResources().get(0);
            if (!(raw instanceof Group adminGroup)) {
                log.warn("Unexpected group resource type, skip addToAdminGroup");
                return;
            }
            String groupId = adminGroup.getId();
            if (StrUtil.isBlank(groupId)) {
                return;
            }
            GetGroupResponse getResp = dc.getGroup(GetGroupRequest.builder().groupId(groupId).build());
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
            log.info("Added user {} to Administrators in identity domain", userScimId);
        } catch (Exception e) {
            log.warn("Failed to add user to Administrators in identity domain: {}", e.getMessage());
        }
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
        OciUser tenant = getTenant(params.getTenantId());
        try (IdentityClient client = buildClient(tenant)) {
            client.createOrResetUIPassword(
                    CreateOrResetUIPasswordRequest.builder()
                            .userId(params.getUserId())
                            .build()
            );
            log.info("Password reset for user: {}", params.getUserId());
        }
    }

    public String getResetPasswordResult(UserParams params) {
        OciUser tenant = getTenant(params.getTenantId());
        try (IdentityClient client = buildClient(tenant)) {
            CreateOrResetUIPasswordResponse response = client.createOrResetUIPassword(
                    CreateOrResetUIPasswordRequest.builder()
                            .userId(params.getUserId())
                            .build()
            );
            return response.getUIPassword().getPassword();
        }
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
        OciUser tenant = getTenant(tenantId);
        try (IdentityClient client = buildClient(tenant)) {
            ListUserGroupMembershipsResponse memberships = client.listUserGroupMemberships(
                    ListUserGroupMembershipsRequest.builder()
                            .compartmentId(tenant.getOciTenantId())
                            .userId(userId)
                            .build()
            );

            List<String> groupNames = new ArrayList<>();
            for (UserGroupMembership m : memberships.getItems()) {
                try {
                    Group group = client.getGroup(
                            GetGroupRequest.builder().groupId(m.getGroupId()).build()
                    ).getGroup();
                    groupNames.add(group.getName());
                } catch (Exception ignored) {}
            }
            return groupNames;
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
