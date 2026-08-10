package com.ociworker.controller;

import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.InstanceGuardService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/oci/instanceGuard")
public class InstanceGuardController {

    @Resource
    private InstanceGuardService instanceGuardService;

    @PostMapping("/status")
    public ResponseData<?> status(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceGuardService.status(
                params.get("id"), params.get("region"), params.get("instanceId")));
    }

    @PostMapping("/save")
    public ResponseData<?> save(@RequestBody Map<String, Object> params) {
        boolean enabled = Boolean.TRUE.equals(params.get("enabled"))
                || "true".equalsIgnoreCase(String.valueOf(params.get("enabled")));
        return ResponseData.ok(instanceGuardService.save(
                text(params.get("id")),
                text(params.get("region")),
                text(params.get("instanceId")),
                text(params.get("instanceName")),
                enabled,
                intValue(params.get("intervalMinutes"))));
    }

    @PostMapping("/toggle")
    public ResponseData<?> toggle(@RequestBody Map<String, Object> params) {
        boolean enabled = Boolean.TRUE.equals(params.get("enabled"))
                || "true".equalsIgnoreCase(String.valueOf(params.get("enabled")));
        return ResponseData.ok(instanceGuardService.setEnabledById(text(params.get("guardId")), enabled));
    }

    @PostMapping("/interval")
    public ResponseData<?> interval(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(instanceGuardService.setIntervalById(
                text(params.get("guardId")), intValue(params.get("intervalMinutes"))));
    }

    @PostMapping("/notify")
    public ResponseData<?> notifyMuted(@RequestBody Map<String, Object> params) {
        boolean muted = Boolean.TRUE.equals(params.get("muted"))
                || "true".equalsIgnoreCase(String.valueOf(params.get("muted")));
        return ResponseData.ok(instanceGuardService.setNotifyMutedById(
                text(params.get("guardId")), muted));
    }

    @PostMapping("/delete")
    public ResponseData<?> delete(@RequestBody Map<String, Object> params) {
        instanceGuardService.deleteById(text(params.get("guardId")));
        return ResponseData.ok();
    }

    @PostMapping("/stopCause")
    public ResponseData<?> stopCause(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(instanceGuardService.stopCause(
                text(params.get("id")),
                text(params.get("region")),
                text(params.get("instanceId"))));
    }

    @PostMapping("/list")
    public ResponseData<?> list() {
        return ResponseData.ok(instanceGuardService.listAll());
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intValue(Object value) {
        if (value == null) return null;
        try {
            return (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
