package com.ociworker.controller;

import com.ociworker.model.params.UserParams;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.DomainManagementService;
import com.ociworker.service.UserManagementService;
import com.ociworker.service.VerifyCodeService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/oci/identity")
public class UserController {

    @Resource
    private UserManagementService userManagementService;

    @Resource
    private DomainManagementService domainManagementService;

    @Resource
    private VerifyCodeService verifyCodeService;

    @PostMapping("/list")
    public ResponseData<?> listUsers(@RequestBody Map<String, Object> params) {
        String tenantId = params.get("tenantId") == null ? null : String.valueOf(params.get("tenantId"));
        String domainId = params.get("domainId") == null ? null : String.valueOf(params.get("domainId"));
        String keyword = params.get("keyword") == null ? null : String.valueOf(params.get("keyword"));
        int current = parsePositiveInt(params.get("current"), 1);
        int size = parsePositiveInt(params.get("size"), 10);
        return ResponseData.ok(userManagementService.listUsers(tenantId, domainId, keyword, current, size));
    }

    /**
     * 列出租户下 Identity Domain（Oracle IAM API ListDomains），供创建用户时选择目标域。
     */
    @PostMapping("/domains")
    public ResponseData<?> listIdentityDomains(@RequestBody Map<String, String> params) {
        return ResponseData.ok(domainManagementService.listIdentityDomains(params.get("tenantId")));
    }

    @PostMapping("/groups")
    public ResponseData<?> listGroups(@RequestBody Map<String, String> params) {
        return ResponseData.ok(userManagementService.listGroups(params.get("tenantId")));
    }

    @PostMapping("/domainGroups")
    public ResponseData<?> listDomainGroups(@RequestBody Map<String, String> params) {
        return ResponseData.ok(userManagementService.listDomainGroups(
                params.get("tenantId"), params.get("domainId")));
    }

    @PostMapping("/create")
    public ResponseData<?> createUser(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("createUser", (String) params.get("verifyCode"));
        UserParams up = new UserParams();
        up.setTenantId((String) params.get("tenantId"));
        up.setUserName((String) params.get("userName"));
        up.setEmail((String) params.get("email"));
        up.setAddToAdminGroup(Boolean.TRUE.equals(params.get("addToAdminGroup")));
        up.setGroupIds(parseGroupIds(params.get("groupIds")));
        Object domainId = params.get("domainId");
        if (domainId != null) {
            up.setDomainId(String.valueOf(domainId));
        }
        return ResponseData.ok(userManagementService.createUser(up));
    }

    @PostMapping("/resetPassword")
    public ResponseData<?> resetPassword(@RequestBody UserParams params) {
        String newPassword = userManagementService.getResetPasswordResult(params);
        return ResponseData.ok(newPassword);
    }

    @PostMapping("/clearMfa")
    public ResponseData<?> clearMfa(@RequestBody Map<String, String> params) {
        verifyCodeService.verifyCode("clearMfa", params.get("verifyCode"));
        UserParams up = buildUserParams(params);
        userManagementService.clearMfa(up);
        return ResponseData.ok("MFA 已清除");
    }

    @PostMapping("/addToAdmin")
    public ResponseData<?> addToAdmin(@RequestBody UserParams params) {
        userManagementService.addUserToGroup(params);
        return ResponseData.ok("已加入管理员组");
    }

    @PostMapping("/removeFromAdmin")
    public ResponseData<?> removeFromAdmin(@RequestBody Map<String, String> params) {
        verifyCodeService.verifyCode("removeFromAdmin", params.get("verifyCode"));
        UserParams up = buildUserParams(params);
        userManagementService.removeUserFromGroup(up);
        return ResponseData.ok("已移出管理员组");
    }

    @PostMapping("/userGroups")
    public ResponseData<?> getUserGroups(@RequestBody Map<String, String> params) {
        return ResponseData.ok(userManagementService.getUserGroups(buildUserParams(params)));
    }

    @PostMapping("/updateUser")
    public ResponseData<?> updateUser(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("updateUser", (String) params.get("verifyCode"));
        UserParams up = new UserParams();
        up.setTenantId((String) params.get("tenantId"));
        up.setUserId((String) params.get("userId"));
        up.setDomainId((String) params.get("domainId"));
        up.setScimId((String) params.get("scimId"));
        up.setUserName((String) params.get("userName"));
        up.setEmail((String) params.get("email"));
        up.setGroupIds(parseGroupIds(params.get("groupIds")));
        userManagementService.updateUser(up);
        if (params.containsKey("groupIds")) {
            userManagementService.syncUserGroups(up);
        }
        return ResponseData.ok();
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseGroupIds(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> ids = new java.util.ArrayList<>();
        for (Object o : list) {
            if (o != null && !String.valueOf(o).isBlank()) {
                ids.add(String.valueOf(o).trim());
            }
        }
        return ids;
    }

    private static int parsePositiveInt(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(String.valueOf(raw));
            return value > 0 ? value : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @PostMapping("/updateUserState")
    public ResponseData<?> updateUserState(@RequestBody Map<String, Object> params) {
        boolean blocked = Boolean.TRUE.equals(params.get("blocked"));
        if (blocked) {
            verifyCodeService.verifyCode("disableUser", (String) params.get("verifyCode"));
        }
        UserParams up = new UserParams();
        up.setTenantId((String) params.get("tenantId"));
        up.setUserId((String) params.get("userId"));
        up.setDomainId((String) params.get("domainId"));
        up.setScimId((String) params.get("scimId"));
        userManagementService.updateUserState(up, blocked);
        return ResponseData.ok();
    }

    @PostMapping("/listMfaDevices")
    public ResponseData<?> listMfaDevices(@RequestBody Map<String, String> params) {
        return ResponseData.ok(userManagementService.listMfaDevices(buildUserParams(params)));
    }

    @PostMapping("/userCapabilities")
    public ResponseData<?> getUserCapabilities(@RequestBody Map<String, String> params) {
        return ResponseData.ok(userManagementService.getUserCapabilities(buildUserParams(params)));
    }

    @PostMapping("/updateUserCapabilities")
    public ResponseData<?> updateUserCapabilities(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("updateUserCapabilities", (String) params.get("verifyCode"));
        UserParams up = new UserParams();
        up.setTenantId((String) params.get("tenantId"));
        up.setUserId((String) params.get("userId"));
        up.setDomainId((String) params.get("domainId"));
        up.setScimId((String) params.get("scimId"));
        up.setCapabilities(UserManagementService.parseCapabilitiesMap(params.get("capabilities")));
        userManagementService.updateUserCapabilities(up);
        return ResponseData.ok();
    }

    private static UserParams buildUserParams(Map<String, String> params) {
        UserParams up = new UserParams();
        up.setTenantId(params.get("tenantId"));
        up.setUserId(params.get("userId"));
        up.setDomainId(params.get("domainId"));
        up.setScimId(params.get("scimId"));
        return up;
    }
}
