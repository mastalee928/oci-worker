package com.ociworker.controller;

import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.VcnFlowLogService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/oci/flowlog")
public class VcnFlowLogController {

    @Resource
    private VcnFlowLogService vcnFlowLogService;

    @PostMapping("/status")
    public ResponseData<?> status(@RequestBody Map<String, Object> params) {
        List<String> subnetIds = new ArrayList<>();
        if (params.get("subnetIds") instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) subnetIds.add(String.valueOf(item));
            }
        }
        return ResponseData.ok(vcnFlowLogService.status(
                text(params.get("id")), text(params.get("region")), subnetIds));
    }

    @PostMapping("/toggle")
    public ResponseData<?> toggle(@RequestBody Map<String, Object> params) {
        boolean enabled = Boolean.TRUE.equals(params.get("enabled"))
                || "true".equalsIgnoreCase(String.valueOf(params.get("enabled")));
        return ResponseData.ok(vcnFlowLogService.toggle(
                text(params.get("id")), text(params.get("region")),
                text(params.get("subnetId")), text(params.get("subnetName")), enabled));
    }

    @PostMapping("/instanceStatus")
    public ResponseData<?> instanceStatus(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnFlowLogService.instanceStatus(
                text(params.get("id")), text(params.get("region")), text(params.get("instanceId"))));
    }

    @PostMapping("/search")
    public ResponseData<?> search(@RequestBody Map<String, Object> params) {
        int minutes = 60;
        try {
            minutes = (int) Double.parseDouble(String.valueOf(params.get("minutes")));
        } catch (NumberFormatException ignored) {
            // 使用默认时间范围。
        }
        boolean rejectOnly = Boolean.TRUE.equals(params.get("rejectOnly"))
                || "true".equalsIgnoreCase(String.valueOf(params.get("rejectOnly")));
        return ResponseData.ok(vcnFlowLogService.search(
                text(params.get("id")), text(params.get("region")),
                text(params.get("privateIp")), text(params.get("instanceId")),
                minutes, rejectOnly));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
