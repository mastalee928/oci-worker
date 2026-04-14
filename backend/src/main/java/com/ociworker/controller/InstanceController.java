package com.ociworker.controller;

import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.InstanceService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/oci/instance")
public class InstanceController {

    @Resource
    private InstanceService instanceService;

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
        instanceService.terminateInstance(params.get("id"), params.get("instanceId"));
        return ResponseData.ok();
    }

    @PostMapping("/shapes")
    public ResponseData<?> listShapes(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.listAvailableShapes(params.get("id")));
    }

    @PostMapping("/bootVolumes")
    public ResponseData<?> bootVolumes(@RequestBody Map<String, String> params) {
        return ResponseData.ok(instanceService.listBootVolumesByInstance(params.get("id"), params.get("instanceId")));
    }
}
