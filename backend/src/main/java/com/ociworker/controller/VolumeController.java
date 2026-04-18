package com.ociworker.controller;

import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.VerifyCodeService;
import com.ociworker.service.VolumeService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/oci/volume")
public class VolumeController {

    @Resource
    private VolumeService volumeService;
    @Resource
    private VerifyCodeService verifyCodeService;

    @PostMapping("/list")
    public ResponseData<?> list(@RequestBody Map<String, String> params) {
        return ResponseData.ok(volumeService.listAllVolumes(params.get("id")));
    }

    @PostMapping("/delete")
    public ResponseData<?> delete(@RequestBody Map<String, String> params) {
        verifyCodeService.verifyCode("deleteVolume", params.get("verifyCode"));
        volumeService.deleteVolume(params.get("id"), params.get("type"), params.get("volumeId"));
        return ResponseData.ok();
    }
}
