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

    @GetMapping("/account/config")
    public ResponseData<?> getAccountConfig() {
        return ResponseData.ok(cloudflareService.getAccountConfigForDisplay());
    }

    @PostMapping("/account/config")
    public ResponseData<?> saveAccountConfig(@RequestBody Map<String, String> params) {
        cloudflareService.saveAccountConfig(
                params.get("accountId"),
                params.get("apiToken"));
        return ResponseData.ok();
    }

    @PostMapping("/account/test")
    public ResponseData<?> testAccountConfig(@RequestBody Map<String, String> params) {
        String msg = cloudflareService.testAccountConfig(
                params.get("accountId"),
                params.get("apiToken"));
        return ResponseData.ok(msg);
    }

    @PostMapping("/zones/list")
    public ResponseData<?> listZones(@RequestBody Map<String, Integer> params) {
        return ResponseData.ok(cloudflareService.listZones(
                params.getOrDefault("page", 1),
                params.getOrDefault("perPage", 50)));
    }

    @PostMapping("/tunnel/list")
    public ResponseData<?> listTunnels() {
        return ResponseData.ok(cloudflareService.listTunnels());
    }

    @PostMapping("/tunnel/create")
    public ResponseData<?> createTunnel(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.createTunnel(params.get("name")));
    }

    @PostMapping("/tunnel/delete")
    public ResponseData<?> deleteTunnel(@RequestBody Map<String, String> params) {
        cloudflareService.deleteTunnel(params.get("tunnelId"));
        return ResponseData.ok();
    }

    @PostMapping("/tunnel/token")
    public ResponseData<?> getTunnelToken(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.getTunnelRunToken(params.get("tunnelId")));
    }

    @PostMapping("/tunnel/connections")
    public ResponseData<?> listTunnelConnections(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.listTunnelConnections(params.get("tunnelId")));
    }

    /** @deprecated legacy cf_cfg table */
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
        String zoneId = (String) params.get("zoneId");
        if (zoneId != null && !zoneId.isBlank()) {
            return ResponseData.ok(cloudflareService.listDnsRecords(
                    zoneId,
                    (Integer) params.getOrDefault("page", 1),
                    (Integer) params.getOrDefault("perPage", 50)));
        }
        return ResponseData.ok(cloudflareService.listDnsRecordsByCfgId(
                (String) params.get("cfgId"),
                (Integer) params.getOrDefault("page", 1),
                (Integer) params.getOrDefault("perPage", 50)));
    }

    @PostMapping("/dns/add")
    public ResponseData<?> addDns(@RequestBody Map<String, Object> params) {
        String zoneId = (String) params.get("zoneId");
        if (zoneId != null && !zoneId.isBlank()) {
            cloudflareService.addDnsRecord(
                    zoneId,
                    (String) params.get("type"),
                    (String) params.get("name"),
                    (String) params.get("content"),
                    (Boolean) params.getOrDefault("proxied", false),
                    (Integer) params.getOrDefault("ttl", 1));
        } else {
            throw new com.ociworker.exception.OciException("请提供 zoneId");
        }
        return ResponseData.ok();
    }

    @PostMapping("/dns/update")
    public ResponseData<?> updateDns(@RequestBody Map<String, Object> params) {
        cloudflareService.updateDnsRecord(
                (String) params.get("zoneId"),
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
        cloudflareService.deleteDnsRecord(params.get("zoneId"), params.get("recordId"));
        return ResponseData.ok();
    }
}
