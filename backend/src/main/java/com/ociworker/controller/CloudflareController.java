package com.ociworker.controller;

import com.ociworker.model.entity.CfCfg;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.CloudflareService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/cf")
public class CloudflareController {

    @Resource
    private CloudflareService cloudflareService;

    @PostMapping("/cfg/list")
    public ResponseData<?> listCfg(@RequestBody Map<String, Integer> params) {
        return ResponseData.ok(cloudflareService.listCfgPage(
                params.getOrDefault("current", 1),
                params.getOrDefault("size", 10)));
    }

    @PostMapping("/cfg/add")
    public ResponseData<?> addCfg(@RequestBody CfCfg cfg) {
        cloudflareService.addCfg(cfg);
        return ResponseData.ok();
    }

    @PostMapping("/cfg/remove")
    public ResponseData<?> removeCfg(@RequestBody Map<String, String> params) {
        cloudflareService.removeCfg(params.get("id"));
        return ResponseData.ok();
    }

    @PostMapping("/dns/list")
    public ResponseData<?> listDns(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(cloudflareService.listDnsRecords(
                (String) params.get("cfgId"),
                (Integer) params.getOrDefault("page", 1),
                (Integer) params.getOrDefault("perPage", 50)));
    }

    @PostMapping("/dns/add")
    public ResponseData<?> addDns(@RequestBody Map<String, Object> params) {
        cloudflareService.addDnsRecord(
                (String) params.get("cfgId"),
                (String) params.get("type"),
                (String) params.get("name"),
                (String) params.get("content"),
                (Boolean) params.getOrDefault("proxied", false),
                (Integer) params.getOrDefault("ttl", 1));
        return ResponseData.ok();
    }

    @PostMapping("/dns/update")
    public ResponseData<?> updateDns(@RequestBody Map<String, Object> params) {
        cloudflareService.updateDnsRecord(
                (String) params.get("cfgId"),
                (String) params.get("recordId"),
                (String) params.get("type"),
                (String) params.get("name"),
                (String) params.get("content"),
                (Boolean) params.getOrDefault("proxied", false),
                (Integer) params.getOrDefault("ttl", 1));
        return ResponseData.ok();
    }

    @PostMapping("/dns/delete")
    public ResponseData<?> deleteDns(@RequestBody Map<String, String> params) {
        cloudflareService.deleteDnsRecord(params.get("cfgId"), params.get("recordId"));
        return ResponseData.ok();
    }
}
