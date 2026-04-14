package com.ociworker.controller;

import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.TrafficService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/oci/traffic")
public class TrafficController {

    @Resource
    private TrafficService trafficService;

    @PostMapping("/data")
    public ResponseData<?> getData(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(trafficService.getTrafficData(
                (String) params.get("id"),
                (String) params.get("instanceId"),
                params.containsKey("minutes") ? (Integer) params.get("minutes") : 60));
    }
}
