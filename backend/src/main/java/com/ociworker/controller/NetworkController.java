package com.ociworker.controller;

import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.NetworkService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/oci/network")
public class NetworkController {

    @Resource
    private NetworkService networkService;

    @PostMapping("/vcns")
    public ResponseData<?> listVcns(@RequestBody Map<String, String> params) {
        return ResponseData.ok(networkService.listVcns(params.get("id")));
    }

    @PostMapping("/securityRules")
    public ResponseData<?> listSecurityRules(@RequestBody Map<String, String> params) {
        return ResponseData.ok(networkService.listSecurityRulesByInstance(params.get("id"), params.get("instanceId")));
    }

    @PostMapping("/releaseAllPorts")
    public ResponseData<?> releaseAllPorts(@RequestBody Map<String, String> params) {
        networkService.releaseAllPortsByInstance(params.get("id"), params.get("instanceId"));
        return ResponseData.ok();
    }

    @PostMapping("/addSecurityRule")
    public ResponseData<?> addSecurityRule(@RequestBody Map<String, String> params) {
        networkService.addSecurityRule(params.get("id"), params.get("instanceId"),
                params.get("direction"), params.get("protocol"),
                params.get("source"), params.get("portMin"), params.get("portMax"),
                params.get("description"));
        return ResponseData.ok();
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/changeIp")
    public ResponseData<?> changeIp(@RequestBody Map<String, Object> params) {
        networkService.changePublicIp(
                (String) params.get("id"),
                (String) params.get("instanceId"),
                (List<String>) params.get("cidrFilters"));
        return ResponseData.ok();
    }
}
