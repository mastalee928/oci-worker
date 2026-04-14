package com.ociworker.controller;

import com.ociworker.model.params.CreateTaskParams;
import com.ociworker.model.params.PageParams;
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

    @PostMapping("/create")
    public ResponseData<?> create(@RequestBody @Valid CreateTaskParams params) {
        taskSchedulerService.createTask(
                params.getUserId(), params.getArchitecture(),
                params.getOcpus(), params.getMemory(), params.getDisk(),
                params.getCreateNumbers(), params.getInterval(),
                params.getRootPassword(), params.getOperationSystem());
        return ResponseData.ok();
    }

    @PostMapping("/stop")
    public ResponseData<?> stop(@RequestBody Map<String, String> params) {
        taskSchedulerService.stopTask(params.get("taskId"));
        return ResponseData.ok();
    }
}
