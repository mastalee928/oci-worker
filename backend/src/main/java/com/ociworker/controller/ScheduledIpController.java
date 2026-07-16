package com.ociworker.controller;

import com.ociworker.exception.OciException;
import com.ociworker.model.dto.ScheduledIpTaskRequest;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.ScheduledIpService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/scheduled-ip")
public class ScheduledIpController {
    @Resource
    private ScheduledIpService scheduledIpService;

    @PostMapping("/overview")
    public ResponseData<?> overview() {
        return ResponseData.ok(scheduledIpService.overview());
    }

    @PostMapping("/task/create")
    public ResponseData<?> create(@RequestBody ScheduledIpTaskRequest request) {
        return ResponseData.ok(scheduledIpService.create(request));
    }

    @PostMapping("/task/update")
    public ResponseData<?> update(@RequestBody ScheduledIpTaskRequest request) {
        return ResponseData.ok(scheduledIpService.update(request));
    }

    @PostMapping("/task/copy")
    public ResponseData<?> copy(@RequestBody Map<String, Object> request) {
        return ResponseData.ok(scheduledIpService.copy(id(request)));
    }

    @PostMapping("/task/enabled")
    public ResponseData<?> enabled(@RequestBody Map<String, Object> request) {
        Object raw = request.get("enabled");
        if (!(raw instanceof Boolean enabled)) throw new OciException("启用状态参数不正确");
        return ResponseData.ok(scheduledIpService.setEnabled(id(request), enabled));
    }

    @PostMapping("/task/delete")
    public ResponseData<?> delete(@RequestBody Map<String, Object> request) {
        scheduledIpService.delete(id(request));
        return ResponseData.ok();
    }

    @PostMapping("/task/run")
    public ResponseData<?> run(@RequestBody Map<String, Object> request) {
        scheduledIpService.runNow(id(request));
        return ResponseData.ok(Map.of("submitted", true));
    }

    @PostMapping("/task/logs")
    public ResponseData<?> logs(@RequestBody Map<String, Object> request) {
        return ResponseData.ok(scheduledIpService.logs(id(request)));
    }

    @PostMapping("/task/retry-dns")
    public ResponseData<?> retryDns(@RequestBody Map<String, Object> request) {
        scheduledIpService.retryDns(id(request));
        return ResponseData.ok(Map.of("submitted", true));
    }

    private static String id(Map<String, Object> request) {
        Object raw = request != null ? request.get("id") : null;
        if (raw == null || String.valueOf(raw).isBlank()) throw new OciException("任务 ID 不能为空");
        return String.valueOf(raw).trim();
    }
}
