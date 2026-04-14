package com.ociworker.controller;

import com.ociworker.model.vo.ResponseData;
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
    public ResponseData<?> terminate(@RequestBody Map<String, String> params) {
        verifyCodeService.verifyCode("terminate", params.get("verifyCode"));
        instanceService.terminateInstance(params.get("id"), params.get("instanceId"));
        return ResponseData.ok();
    }

    @PostMapping("/updateInstance")
    public ResponseData<?> updateInstance(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(instanceService.updateInstance(
                (String) params.get("id"),
                (String) params.get("instanceId"),
                (String) params.get("displayName"),
                params.get("ocpus") != null ? ((Number) params.get("ocpus")).floatValue() : null,
                params.get("memoryInGBs") != null ? ((Number) params.get("memoryInGBs")).floatValue() : null));
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
                (String) params.get("id"),
                (String) params.get("bootVolumeId"),
                params.get("sizeInGBs") != null ? ((Number) params.get("sizeInGBs")).longValue() : null,
                (String) params.get("displayName"),
                params.get("vpusPerGB") != null ? ((Number) params.get("vpusPerGB")).longValue() : null);
        return ResponseData.ok();
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
}
