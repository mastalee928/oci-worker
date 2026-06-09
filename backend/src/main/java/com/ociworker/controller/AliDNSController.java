package com.ociworker.controller;

import com.ociworker.service.AliDNSService;
import com.ociworker.model.vo.ResponseData;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/alidns")
public class AliDNSController {

    @Resource
    private AliDNSService aliDNSService;

    @GetMapping("/account/config")
    public ResponseData<?> getAccountConfig() {
        return ResponseData.ok(aliDNSService.getAccountConfigForDisplay());
    }

    @PostMapping("/account/config")
    public ResponseData<?> saveAccountConfig(@RequestBody Map<String, String> params) {
        aliDNSService.saveAccountConfig(params.get("accessKeyId"), params.get("accessKeySecret"));
        return ResponseData.ok();
    }

    @PostMapping("/account/test")
    public ResponseData<?> testAccountConfig(@RequestBody Map<String, String> params) {
        String msg = aliDNSService.testAccountConfig(params.get("accessKeyId"), params.get("accessKeySecret"));
        return ResponseData.ok(msg);
    }

    @PostMapping("/domains/list")
    public ResponseData<?> listDomains(@RequestBody Map<String, Object> params) {
        int page = params.get("page") != null ? ((Number) params.get("page")).intValue() : 1;
        int perPage = params.get("perPage") != null ? ((Number) params.get("perPage")).intValue() : 20;
        return ResponseData.ok(aliDNSService.listDomains(page, perPage));
    }
}
