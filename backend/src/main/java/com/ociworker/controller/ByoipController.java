package com.ociworker.controller;

import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.ByoipService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/oci/byoip")
public class ByoipController {

    @Resource
    private ByoipService byoipService;

    @PostMapping("/help")
    public ResponseData<?> help() {
        return ResponseData.ok(byoipService.getByoipHelp());
    }

    @PostMapping("/listRanges")
    public ResponseData<?> listRanges(@RequestBody Map<String, String> params) {
        return ResponseData.ok(byoipService.listByoipRanges(params.get("id"), reg(params), bool(params.get("force"))));
    }

    @PostMapping("/getRange")
    public ResponseData<?> getRange(@RequestBody Map<String, String> params) {
        return ResponseData.ok(byoipService.getByoipRange(params.get("id"), params.get("byoipRangeId"), reg(params)));
    }

    @PostMapping("/createRange")
    public ResponseData<?> createRange(@RequestBody Map<String, String> params) {
        return ResponseData.ok(byoipService.createByoipRange(
                params.get("id"),
                params.get("displayName"),
                params.get("cidrBlock"),
                params.get("ipv6CidrBlock"),
                reg(params)));
    }

    @PostMapping("/updateRange")
    public ResponseData<?> updateRange(@RequestBody Map<String, String> params) {
        return ResponseData.ok(byoipService.updateByoipRange(
                params.get("id"), params.get("byoipRangeId"), params.get("displayName"), reg(params)));
    }

    @PostMapping("/deleteRange")
    public ResponseData<?> deleteRange(@RequestBody Map<String, String> params) {
        byoipService.deleteByoipRange(params.get("id"), params.get("byoipRangeId"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/validateRange")
    public ResponseData<?> validateRange(@RequestBody Map<String, String> params) {
        byoipService.validateByoipRange(params.get("id"), params.get("byoipRangeId"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/advertiseRange")
    public ResponseData<?> advertiseRange(@RequestBody Map<String, String> params) {
        byoipService.advertiseByoipRange(params.get("id"), params.get("byoipRangeId"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/withdrawRange")
    public ResponseData<?> withdrawRange(@RequestBody Map<String, String> params) {
        byoipService.withdrawByoipRange(params.get("id"), params.get("byoipRangeId"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/changeRangeCompartment")
    public ResponseData<?> changeRangeCompartment(@RequestBody Map<String, String> params) {
        byoipService.changeByoipRangeCompartment(
                params.get("id"), params.get("byoipRangeId"), params.get("compartmentId"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/listAllocatedRanges")
    public ResponseData<?> listAllocatedRanges(@RequestBody Map<String, String> params) {
        return ResponseData.ok(byoipService.listByoipAllocatedRanges(
                params.get("id"), params.get("byoipRangeId"), reg(params), bool(params.get("force"))));
    }

    @PostMapping("/listPools")
    public ResponseData<?> listPools(@RequestBody Map<String, String> params) {
        return ResponseData.ok(byoipService.listPublicIpPools(params.get("id"), params.get("byoipRangeId"), reg(params), bool(params.get("force"))));
    }

    @PostMapping("/createPool")
    public ResponseData<?> createPool(@RequestBody Map<String, String> params) {
        return ResponseData.ok(byoipService.createPublicIpPool(params.get("id"), params.get("displayName"), reg(params)));
    }

    @PostMapping("/updatePool")
    public ResponseData<?> updatePool(@RequestBody Map<String, String> params) {
        return ResponseData.ok(byoipService.updatePublicIpPool(
                params.get("id"), params.get("publicIpPoolId"), params.get("displayName"), reg(params)));
    }

    @PostMapping("/deletePool")
    public ResponseData<?> deletePool(@RequestBody Map<String, String> params) {
        byoipService.deletePublicIpPool(params.get("id"), params.get("publicIpPoolId"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/addPoolCapacity")
    public ResponseData<?> addPoolCapacity(@RequestBody Map<String, String> params) {
        byoipService.addPublicIpPoolCapacity(
                params.get("id"),
                params.get("publicIpPoolId"),
                params.get("byoipRangeId"),
                params.get("cidrBlock"),
                reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/removePoolCapacity")
    public ResponseData<?> removePoolCapacity(@RequestBody Map<String, String> params) {
        byoipService.removePublicIpPoolCapacity(
                params.get("id"), params.get("publicIpPoolId"), params.get("cidrBlock"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/createPublicIp")
    public ResponseData<?> createPublicIp(@RequestBody Map<String, String> params) {
        return ResponseData.ok(byoipService.createByoipReservedIp(
                params.get("id"),
                params.get("displayName"),
                params.get("publicIpPoolId"),
                reg(params)));
    }

    @PostMapping("/listPublicIps")
    public ResponseData<?> listPublicIps(@RequestBody Map<String, String> params) {
        return ResponseData.ok(byoipService.listByoipPublicIps(params.get("id"), reg(params), bool(params.get("force"))));
    }

    @PostMapping("/assignIpv6ToVcn")
    public ResponseData<?> assignIpv6ToVcn(@RequestBody Map<String, String> params) {
        byoipService.assignByoipv6ToVcn(
                params.get("id"),
                params.get("vcnId"),
                params.get("byoipRangeId"),
                params.get("ipv6CidrBlock"),
                reg(params));
        return ResponseData.ok();
    }

    /** 兼容旧路径 */
    @PostMapping("/listByoipRanges")
    public ResponseData<?> listByoipRangesLegacy(@RequestBody Map<String, String> params) {
        return listRanges(params);
    }

    @PostMapping("/listPublicIpPools")
    public ResponseData<?> listPublicIpPoolsLegacy(@RequestBody Map<String, String> params) {
        return listPools(params);
    }

    @PostMapping("/createByoipReservedIp")
    public ResponseData<?> createByoipReservedIpLegacy(@RequestBody Map<String, String> params) {
        return createPublicIp(params);
    }

    private static String reg(Map<String, String> params) {
        if (params == null) return null;
        String s = params.get("region");
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean bool(String value) {
        return value != null && Boolean.parseBoolean(value);
    }
}
