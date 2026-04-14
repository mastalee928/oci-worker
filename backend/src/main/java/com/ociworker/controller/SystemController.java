package com.ociworker.controller;

import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.SystemService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sys")
public class SystemController {

    @Resource
    private SystemService systemService;

    @GetMapping("/glance")
    public ResponseData<?> glance() {
        return ResponseData.ok(systemService.getGlance());
    }
}
