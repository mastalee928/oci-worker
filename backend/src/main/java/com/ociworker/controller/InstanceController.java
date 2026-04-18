package com.ociworker.controller;

import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.ConsoleService;
import com.ociworker.service.InstanceService;
import com.ociworker.service.VerifyCodeService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/oci/instance")
public class InstanceController {

    @Resource
    private InstanceService instanceService;
    @Resource
    private VerifyCodeService verifyCodeService;
    @Resource
    private ConsoleService consoleService;

    @PostMapping("/list")
    public ResponseData<?> list(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.listInstances(params.get("id")));
    }

    @PostMapping("/updateState")
    public ResponseData<?> updateState(@RequestBody Map<String, String> params) {
        instanceService.updateInstanceState(params.get("id"), params.get("instanceId"), params.get("action"));
        return ResponseData.ok();
    }

    @PostMapping("/terminate")
    public ResponseData<?> terminate(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("terminate", params.get("verifyCode") == null ? null : String.valueOf(params.get("verifyCode")));
        boolean preserveBootVolume = Boolean.TRUE.equals(params.get("preserveBootVolume"));
        instanceService.terminateInstance(
                params.get("id") == null ? null : String.valueOf(params.get("id")),
                params.get("instanceId") == null ? null : String.valueOf(params.get("instanceId")),
                preserveBootVolume);
        return ResponseData.ok();
    }

    @PostMapping("/updateInstance")
    public ResponseData<?> updateInstance(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(instanceService.updateInstance(
                asString(params.get("id")),
                asString(params.get("instanceId")),
                asString(params.get("displayName")),
                asFloat(params.get("ocpus")),
                asFloat(params.get("memoryInGBs"))));
    }

    @PostMapping("/shapes")
    public ResponseData<?> listShapes(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.listAvailableShapes(params.get("id")));
    }

    @PostMapping("/bootVolumes")
    public ResponseData<?> bootVolumes(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.listBootVolumesByInstance(params.get("id"), params.get("instanceId")));
    }

    @PostMapping("/updateBootVolume")
    public ResponseData<?> updateBootVolume(@RequestBody Map<String, Object> params) {
        instanceService.updateBootVolume(
                asString(params.get("id")),
                asString(params.get("bootVolumeId")),
                asLong(params.get("sizeInGBs")),
                asString(params.get("displayName")),
                asLong(params.get("vpusPerGB")));
        return ResponseData.ok();
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Float asFloat(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.floatValue();
        try { return Float.parseFloat(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    @PostMapping("/instanceDetail")
    public ResponseData<?> instanceDetail(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.getInstanceNetworkDetail(params.get("id"), params.get("instanceId")));
    }

    @PostMapping("/addIpv6")
    public ResponseData<?> addIpv6(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.addIpv6(params.get("id"), params.get("instanceId")));
    }

    @PostMapping("/createReservedIp")
    public ResponseData<?> createReservedIp(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.createReservedIp(params.get("id"), params.get("displayName")));
    }

    @PostMapping("/listReservedIps")
    public ResponseData<?> listReservedIps(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.listReservedIps(params.get("id")));
    }

    @PostMapping("/deleteReservedIp")
    public ResponseData<?> deleteReservedIp(@RequestBody Map<String, String> params) {
        instanceService.deleteReservedIp(params.get("id"), params.get("publicIpId"));
        return ResponseData.ok();
    }

    @PostMapping("/assignReservedIp")
    public ResponseData<?> assignReservedIp(@RequestBody Map<String, String> params) {
        instanceService.assignReservedIp(params.get("id"), params.get("publicIpId"), params.get("instanceId"));
        return ResponseData.ok();
    }

    @PostMapping("/unassignReservedIp")
    public ResponseData<?> unassignReservedIp(@RequestBody Map<String, String> params) {
        instanceService.unassignReservedIp(params.get("id"), params.get("publicIpId"));
        return ResponseData.ok();
    }

    @PostMapping("/createConsole")
    public ResponseData<?> createConsole(@RequestBody Map<String, String> params) {
        return ResponseData.ok(consoleService.createConsoleConnection(params.get("id"), params.get("instanceId")));
    }

    @PostMapping("/deleteConsole")
    public ResponseData<?> deleteConsole(@RequestBody Map<String, String> params) {
        consoleService.deleteConsoleConnection(params.get("id"), params.get("connectionId"));
        return ResponseData.ok();
    }
}
