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
        Object minutesRaw = params == null ? null : params.get("minutes");
        int minutes = 60;
        if (minutesRaw instanceof Number n) {
            minutes = n.intValue();
        } else if (minutesRaw != null) {
            try { minutes = Integer.parseInt(String.valueOf(minutesRaw)); } catch (NumberFormatException ignored) {}
        }
        return ResponseData.ok(trafficService.getTrafficData(
                params == null ? null : (String) params.get("id"),
                params == null ? null : (String) params.get("instanceId"),
                minutes));
    }
}
