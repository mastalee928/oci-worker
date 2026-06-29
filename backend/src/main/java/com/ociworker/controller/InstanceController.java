package com.ociworker.controller;

import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.ConsoleService;
import com.ociworker.service.InstanceService;
import com.ociworker.service.ShapeEditTaskManager;
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
    @Resource
    private ShapeEditTaskManager shapeEditTaskManager;

    @PostMapping("/list")
    public ResponseData<?> list(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.listInstances(params.get("id"), regStr(params), bool(params.get("force"))));
    }

    @PostMapping("/updateState")
    public ResponseData<?> updateState(@RequestBody Map<String, String> params) {
        instanceService.updateInstanceState(params.get("id"), params.get("instanceId"), params.get("action"), regStr(params));
        return ResponseData.ok();
    }

    @PostMapping("/terminate")
    public ResponseData<?> terminate(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("terminate", params.get("verifyCode") == null ? null : String.valueOf(params.get("verifyCode")));
        boolean preserveBootVolume = Boolean.TRUE.equals(params.get("preserveBootVolume"));
        instanceService.terminateInstance(
                params.get("id") == null ? null : String.valueOf(params.get("id")),
                params.get("instanceId") == null ? null : String.valueOf(params.get("instanceId")),
                preserveBootVolume,
                regObj(params));
        return ResponseData.ok();
    }

    @PostMapping("/updateInstance")
    public ResponseData<?> updateInstance(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(instanceService.updateInstance(
                asString(params.get("id")),
                asString(params.get("instanceId")),
                asString(params.get("displayName")),
                asString(params.get("shape")),
                asFloat(params.get("ocpus")),
                asFloat(params.get("memoryInGBs")),
                regObj(params)));
    }

    @GetMapping("/shapeEditTask/{taskId}")
    public ResponseData<?> shapeEditTask(@PathVariable String taskId) {
        return ResponseData.ok(shapeEditTaskManager.getStatus(taskId));
    }

    @PostMapping("/shapeEditTask/{taskId}/pause")
    public ResponseData<?> pauseShapeEditTask(@PathVariable String taskId) {
        return ResponseData.ok(shapeEditTaskManager.pause(taskId));
    }

    @PostMapping("/shapeEditTask/{taskId}/resume")
    public ResponseData<?> resumeShapeEditTask(@PathVariable String taskId) {
        return ResponseData.ok(shapeEditTaskManager.resume(taskId));
    }

    @PostMapping("/shapeEditTask/{taskId}/stop")
    public ResponseData<?> stopShapeEditTask(@PathVariable String taskId) {
        return ResponseData.ok(shapeEditTaskManager.stop(taskId));
    }

    @PostMapping("/shapes")
    public ResponseData<?> listShapes(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(instanceService.listAvailableShapes(
                asString(params.get("id")),
                regObj(params),
                bool(params.get("force"))));
    }

    @PostMapping("/shapesForInstance")
    public ResponseData<?> shapesForInstance(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(instanceService.listShapesForInstance(
                asString(params.get("id")),
                asString(params.get("instanceId")),
                regObj(params),
                bool(params.get("force"))));
    }

    @PostMapping("/forceA2ToA1")
    public ResponseData<?> forceA2ToA1(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.forceA2FlexToA1Flex(
                params.get("id"), params.get("instanceId"), regStr(params)));
    }

    @PostMapping("/bootVolumes")
    public ResponseData<?> bootVolumes(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.listBootVolumesByInstance(
                params.get("id"), params.get("instanceId"), regStr(params), bool(params.get("force"))));
    }

    @PostMapping("/updateBootVolume")
    public ResponseData<?> updateBootVolume(@RequestBody Map<String, Object> params) {
        instanceService.updateBootVolume(
                asString(params.get("id")),
                asString(params.get("bootVolumeId")),
                asLong(params.get("sizeInGBs")),
                asString(params.get("displayName")),
                asLong(params.get("vpusPerGB")),
                regObj(params));
        return ResponseData.ok();
    }

    @PostMapping("/blockVolumes")
    public ResponseData<?> blockVolumes(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.listBlockVolumesByInstance(
                params.get("id"), params.get("instanceId"), regStr(params), bool(params.get("force"))));
    }

    @PostMapping("/externalBootVolumes")
    public ResponseData<?> externalBootVolumes(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.listExternalBootVolumesForInstance(
                params.get("id"), params.get("instanceId"), regStr(params), bool(params.get("force"))));
    }

    @PostMapping("/unattachedBlockVolumes")
    public ResponseData<?> unattachedBlockVolumes(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.listUnattachedBlockVolumesForInstance(
                params.get("id"), params.get("instanceId"), regStr(params), bool(params.get("force"))));
    }

    @PostMapping("/createBlockVolumeAndAttach")
    public ResponseData<?> createBlockVolumeAndAttach(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(instanceService.createBlockVolumeAndAttach(
                asString(params.get("id")),
                asString(params.get("instanceId")),
                asString(params.get("displayName")),
                asLong(params.get("sizeInGBs")),
                asLong(params.get("vpusPerGB")),
                asString(params.get("device")),
                asString(params.get("attachmentType")),
                regObj(params)));
    }

    @PostMapping("/attachBlockVolume")
    public ResponseData<?> attachBlockVolume(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(instanceService.attachBlockVolume(
                asString(params.get("id")),
                asString(params.get("instanceId")),
                asString(params.get("volumeId")),
                asString(params.get("device")),
                asString(params.get("attachmentType")),
                regObj(params)));
    }

    @PostMapping("/attachExternalBootVolume")
    public ResponseData<?> attachExternalBootVolume(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(instanceService.attachExternalBootVolume(
                asString(params.get("id")),
                asString(params.get("instanceId")),
                asString(params.get("bootVolumeId")),
                asString(params.get("attachmentType")),
                regObj(params)));
    }

    @PostMapping("/detachBlockVolume")
    public ResponseData<?> detachBlockVolume(@RequestBody Map<String, Object> params) {
        instanceService.detachBlockVolume(
                asString(params.get("id")),
                asString(params.get("volumeAttachmentId")),
                regObj(params));
        return ResponseData.ok();
    }

    @PostMapping("/detachExternalBootVolume")
    public ResponseData<?> detachExternalBootVolume(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("detachBootVolume", asString(params.get("verifyCode")));
        instanceService.detachExternalBootVolume(
                asString(params.get("id")),
                asString(params.get("instanceId")),
                asString(params.get("bootVolumeAttachmentId")),
                regObj(params));
        return ResponseData.ok();
    }

    @PostMapping("/updateBlockVolume")
    public ResponseData<?> updateBlockVolume(@RequestBody Map<String, Object> params) {
        instanceService.updateBlockVolume(
                asString(params.get("id")),
                asString(params.get("volumeId")),
                asLong(params.get("sizeInGBs")),
                asString(params.get("displayName")),
                asLong(params.get("vpusPerGB")),
                regObj(params));
        return ResponseData.ok();
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static boolean bool(Object v) {
        if (v instanceof Boolean b) return b;
        return v != null && Boolean.parseBoolean(String.valueOf(v));
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
        return ResponseData.ok(instanceService.getInstanceNetworkDetail(
                params.get("id"), params.get("instanceId"), regStr(params), compStr(params), bool(params.get("force"))));
    }

    @PostMapping("/addIpv6")
    public ResponseData<?> addIpv6(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.addIpv6(
                params.get("id"), params.get("instanceId"), params.get("vnicId"), regStr(params), compStr(params)));
    }

    @PostMapping("/removeIpv6")
    public ResponseData<?> removeIpv6(@RequestBody Map<String, String> params) {
        instanceService.removeIpv6(params.get("id"), params.get("ipv6Id"), regStr(params));
        return ResponseData.ok();
    }

    @PostMapping("/createReservedIp")
    public ResponseData<?> createReservedIp(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.createReservedIp(params.get("id"), params.get("displayName"), regStr(params)));
    }

    @PostMapping("/listReservedIps")
    public ResponseData<?> listReservedIps(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.listReservedIps(params.get("id"), regStr(params), bool(params.get("force"))));
    }

    @PostMapping("/deleteReservedIp")
    public ResponseData<?> deleteReservedIp(@RequestBody Map<String, String> params) {
        instanceService.deleteReservedIp(params.get("id"), params.get("publicIpId"), regStr(params));
        return ResponseData.ok();
    }

    @PostMapping("/assignReservedIp")
    public ResponseData<?> assignReservedIp(@RequestBody Map<String, String> params) {
        instanceService.assignReservedIp(
                params.get("id"), params.get("publicIpId"), params.get("instanceId"), regStr(params), compStr(params));
        return ResponseData.ok();
    }

    @PostMapping("/unassignReservedIp")
    public ResponseData<?> unassignReservedIp(@RequestBody Map<String, String> params) {
        instanceService.unassignReservedIp(params.get("id"), params.get("publicIpId"), regStr(params));
        return ResponseData.ok();
    }

    @PostMapping("/createConsole")
    public ResponseData<?> createConsole(@RequestBody Map<String, String> params) {
        return ResponseData.ok(consoleService.createConsoleConnection(params.get("id"), params.get("instanceId"), regStr(params)));
    }

    @PostMapping("/deleteConsole")
    public ResponseData<?> deleteConsole(@RequestBody Map<String, String> params) {
        consoleService.deleteConsoleConnection(params.get("id"), params.get("connectionId"), regStr(params));
        return ResponseData.ok();
    }

    private static String regStr(Map<String, String> params) {
        if (params == null) return null;
        String s = params.get("region");
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static String compStr(Map<String, String> params) {
        if (params == null) return null;
        String s = params.get("compartmentId");
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static String regObj(Map<String, Object> params) {
        if (params == null) return null;
        Object v = params.get("region");
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
