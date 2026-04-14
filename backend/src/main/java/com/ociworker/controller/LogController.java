package com.ociworker.controller;

import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.LogPersistService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/log")
public class LogController {

    @Resource
    private LogPersistService logPersistService;

    @PostMapping("/search")
    public ResponseData<?> search(@RequestBody Map<String, String> params) {
        String keyword = params.get("keyword");
        List<String> all = logPersistService.readAllLines();
        if (keyword == null || keyword.isBlank()) {
            return ResponseData.ok(all);
        }
        String lowerKey = keyword.toLowerCase();
        List<String> matched = all.stream()
                .filter(line -> line.toLowerCase().contains(lowerKey))
                .toList();
        return ResponseData.ok(matched);
    }
}
