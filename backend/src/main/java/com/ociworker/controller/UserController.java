package com.ociworker.controller;

import com.ociworker.model.params.UserParams;
import com.ociworker.model.vo.ResponseData;
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
    private VerifyCodeService verifyCodeService;

    @PostMapping("/list")
    public ResponseData<?> listUsers(@RequestBody Map<String, String> params) {
        return ResponseData.ok(userManagementService.listUsers(params.get("tenantId")));
    }

    @PostMapping("/groups")
    public ResponseData<?> listGroups(@RequestBody Map<String, String> params) {
        return ResponseData.ok(userManagementService.listGroups(params.get("tenantId")));
    }

    @PostMapping("/create")
    public ResponseData<?> createUser(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("createUser", (String) params.get("verifyCode"));
        UserParams up = new UserParams();
        up.setTenantId((String) params.get("tenantId"));
        up.setUserName((String) params.get("userName"));
        up.setEmail((String) params.get("email"));
        up.setAddToAdminGroup(Boolean.TRUE.equals(params.get("addToAdminGroup")));
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
        UserParams up = new UserParams();
        up.setTenantId(params.get("tenantId"));
        up.setUserId(params.get("userId"));
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
        UserParams up = new UserParams();
        up.setTenantId(params.get("tenantId"));
        up.setUserId(params.get("userId"));
        userManagementService.removeUserFromGroup(up);
        return ResponseData.ok("已移出管理员组");
    }

    @PostMapping("/userGroups")
    public ResponseData<?> getUserGroups(@RequestBody Map<String, String> params) {
        List<String> groups = userManagementService.getUserGroupNames(params.get("tenantId"), params.get("userId"));
        return ResponseData.ok(groups);
    }

    @PostMapping("/updateUser")
    public ResponseData<?> updateUser(@RequestBody Map<String, String> params) {
        verifyCodeService.verifyCode("updateUser", params.get("verifyCode"));
        UserParams up = new UserParams();
        up.setTenantId(params.get("tenantId"));
        up.setUserId(params.get("userId"));
        up.setUserName(params.get("userName"));
        up.setEmail(params.get("email"));
        userManagementService.updateUser(up);
        return ResponseData.ok();
    }

    @PostMapping("/updateUserState")
    public ResponseData<?> updateUserState(@RequestBody Map<String, Object> params) {
        boolean blocked = Boolean.TRUE.equals(params.get("blocked"));
        if (blocked) {
            verifyCodeService.verifyCode("disableUser", (String) params.get("verifyCode"));
        }
        userManagementService.updateUserState(
                (String) params.get("tenantId"),
                (String) params.get("userId"),
                blocked);
        return ResponseData.ok();
    }

    @PostMapping("/listMfaDevices")
    public ResponseData<?> listMfaDevices(@RequestBody Map<String, String> params) {
        return ResponseData.ok(userManagementService.listMfaDevices(params.get("tenantId"), params.get("userId")));
    }
}
