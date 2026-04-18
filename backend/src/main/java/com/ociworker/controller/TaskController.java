package com.ociworker.controller;

import com.ociworker.model.params.CreateTaskParams;
import com.ociworker.model.params.PageParams;
import com.ociworker.model.params.UpdateTaskParams;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.TaskSchedulerService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/oci/task")
public class TaskController {

    @Resource
    private TaskSchedulerService taskSchedulerService;

    @PostMapping("/list")
    public ResponseData<?> list(@RequestBody PageParams params) {
        return ResponseData.ok(taskSchedulerService.listTasks(params));
    }

    @PostMapping("/hasRunning")
    public ResponseData<?> hasRunning(@RequestBody Map<String, String> params) {
        return ResponseData.ok(taskSchedulerService.hasRunningTask(params.get("userId")));
    }

    @PostMapping("/create")
    public ResponseData<?> create(@RequestBody @Valid CreateTaskParams params) {
        taskSchedulerService.createTask(
                params.getUserId(), params.getArchitecture(),
                params.getOcpus(), params.getMemory(), params.getDisk(),
                params.getCreateNumbers(), params.getInterval(),
                params.getRootPassword(), params.getOperationSystem(),
                params.getCustomScript(),
                params.getAssignPublicIp(), params.getAssignIpv6());
        return ResponseData.ok();
    }

    @PostMapping("/update")
    public ResponseData<?> update(@RequestBody @Valid UpdateTaskParams params) {
        taskSchedulerService.updateTask(
                params.getTaskId(), params.getArchitecture(),
                params.getOcpus(), params.getMemory(), params.getDisk(),
                params.getCreateNumbers(), params.getInterval(),
                params.getRootPassword(), params.getOperationSystem(),
                params.getCustomScript(),
                params.getAssignPublicIp(), params.getAssignIpv6());
        return ResponseData.ok();
    }

    @PostMapping("/stop")
    public ResponseData<?> stop(@RequestBody Map<String, String> params) {
        taskSchedulerService.stopTask(params.get("taskId"));
        return ResponseData.ok();
    }

    @PostMapping("/resume")
    public ResponseData<?> resume(@RequestBody Map<String, String> params) {
        taskSchedulerService.resumeTask(params.get("taskId"));
        return ResponseData.ok();
    }

    @PostMapping("/delete")
    public ResponseData<?> delete(@RequestBody Map<String, String> params) {
        taskSchedulerService.deleteTask(params.get("taskId"));
        return ResponseData.ok();
    }

    @PostMapping("/detail")
    public ResponseData<?> detail(@RequestBody Map<String, String> params) {
        return ResponseData.ok(taskSchedulerService.getTaskDetail(params.get("taskId")));
    }

    @PostMapping("/batchStop")
    public ResponseData<?> batchStop(@RequestBody Map<String, Object> params) {
        List<String> ids = extractStringList(params, "taskIds");
        int count = 0;
        for (String id : ids) {
            try {
                taskSchedulerService.stopTask(id);
                count++;
            } catch (Exception e) {
                log.warn("batchStop failed for taskId={}: {}", id, e.getMessage());
            }
        }
        return ResponseData.ok(count);
    }

    @PostMapping("/batchResume")
    public ResponseData<?> batchResume(@RequestBody Map<String, Object> params) {
        List<String> ids = extractStringList(params, "taskIds");
        int count = 0;
        for (String id : ids) {
            try {
                taskSchedulerService.resumeTask(id);
                count++;
            } catch (Exception e) {
                log.warn("batchResume failed for taskId={}: {}", id, e.getMessage());
            }
        }
        return ResponseData.ok(count);
    }

    private List<String> extractStringList(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof List<?> list) || list.isEmpty()) return Collections.emptyList();
        List<String> ids = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o != null) ids.add(String.valueOf(o));
        }
        return ids;
    }
}
