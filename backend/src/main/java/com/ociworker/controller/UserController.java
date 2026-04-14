package com.ociworker.controller;

import com.ociworker.model.params.UserParams;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.UserManagementService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/oci/user")
public class UserController {

    @Resource
    private UserManagementService userManagementService;

    @PostMapping("/list")
    public ResponseData<?> listUsers(@RequestBody Map<String, String> params) {
        return ResponseData.ok(userManagementService.listUsers(params.get("tenantId")));
    }

    @PostMapping("/groups")
    public ResponseData<?> listGroups(@RequestBody Map<String, String> params) {
        return ResponseData.ok(userManagementService.listGroups(params.get("tenantId")));
    }

    @PostMapping("/create")
    public ResponseData<?> createUser(@RequestBody UserParams params) {
        return ResponseData.ok(userManagementService.createUser(params));
    }

    @PostMapping("/resetPassword")
    public ResponseData<?> resetPassword(@RequestBody UserParams params) {
        String newPassword = userManagementService.getResetPasswordResult(params);
        return ResponseData.ok(newPassword);
    }

    @PostMapping("/clearMfa")
    public ResponseData<?> clearMfa(@RequestBody UserParams params) {
        userManagementService.clearMfa(params);
        return ResponseData.ok("MFA 已清除");
    }

    @PostMapping("/addToAdmin")
    public ResponseData<?> addToAdmin(@RequestBody UserParams params) {
        userManagementService.addUserToGroup(params);
        return ResponseData.ok("已加入管理员组");
    }

    @PostMapping("/removeFromAdmin")
    public ResponseData<?> removeFromAdmin(@RequestBody UserParams params) {
        userManagementService.removeUserFromGroup(params);
        return ResponseData.ok("已移出管理员组");
    }

    @PostMapping("/userGroups")
    public ResponseData<?> getUserGroups(@RequestBody Map<String, String> params) {
        List<String> groups = userManagementService.getUserGroupNames(params.get("tenantId"), params.get("userId"));
        return ResponseData.ok(groups);
    }
}
