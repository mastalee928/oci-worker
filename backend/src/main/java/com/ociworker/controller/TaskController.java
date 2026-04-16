package com.ociworker.controller;

import com.ociworker.model.params.CreateTaskParams;
import com.ociworker.model.params.PageParams;
import com.ociworker.model.params.UpdateTaskParams;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.TaskSchedulerService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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

    @SuppressWarnings("unchecked")
    @PostMapping("/batchStop")
    public ResponseData<?> batchStop(@RequestBody Map<String, Object> params) {
        java.util.List<String> ids = (java.util.List<String>) params.get("taskIds");
        int count = 0;
        for (String id : ids) {
            try { taskSchedulerService.stopTask(id); count++; } catch (Exception ignored) {}
        }
        return ResponseData.ok(count);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/batchResume")
    public ResponseData<?> batchResume(@RequestBody Map<String, Object> params) {
        java.util.List<String> ids = (java.util.List<String>) params.get("taskIds");
        int count = 0;
        for (String id : ids) {
            try { taskSchedulerService.resumeTask(id); count++; } catch (Exception ignored) {}
        }
        return ResponseData.ok(count);
    }
}
