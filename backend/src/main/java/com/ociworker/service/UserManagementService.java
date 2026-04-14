package com.ociworker.service;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.*;
import com.oracle.bmc.identity.requests.*;
import com.oracle.bmc.identity.responses.*;
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

    @Resource
    private OciUserMapper userMapper;

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
            log.info("Created user: {} in tenant: {}", created.getName(), tenant.getUsername());

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

    public void resetPassword(UserParams params) {
        OciUser tenant = getTenant(params.getTenantId());
        try (IdentityClient client = buildClient(tenant)) {
            CreateOrResetUIPasswordResponse response = client.createOrResetUIPassword(
                    CreateOrResetUIPasswordRequest.builder()
                            .userId(params.getUserId())
                            .build()
            );
            log.info("Password reset for user: {}, new password: {}", params.getUserId(), response.getUIPassword().getPassword());
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
