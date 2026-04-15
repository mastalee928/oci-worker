package com.ociworker.controller;

import com.ociworker.model.params.IdListParams;
import com.ociworker.model.params.IdParams;
import com.ociworker.model.params.PageParams;
import com.ociworker.model.params.TenantParams;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.TenantService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/oci/user")
public class TenantController {

    @Resource
    private TenantService tenantService;

    @PostMapping("/list")
    public ResponseData<?> list(@RequestBody PageParams params) {
        return ResponseData.ok(tenantService.list(params));
    }

    @PostMapping("/add")
    public ResponseData<?> add(@RequestBody @Valid TenantParams params) {
        tenantService.add(params);
        return ResponseData.ok();
    }

    @PostMapping("/update")
    public ResponseData<?> update(@RequestBody @Valid TenantParams params) {
        tenantService.update(params);
        return ResponseData.ok();
    }

    @PostMapping("/remove")
    public ResponseData<?> remove(@RequestBody @Valid IdListParams params) {
        tenantService.remove(params);
        return ResponseData.ok();
    }

    @PostMapping("/details")
    public ResponseData<?> details(@RequestBody @Valid IdParams params) {
        return ResponseData.ok(tenantService.getById(params.getId()));
    }

    @PostMapping("/fullInfo")
    public ResponseData<?> fullInfo(@RequestBody java.util.Map<String, String> params) {
        return ResponseData.ok(tenantService.getTenantFullInfo(params.get("id")));
    }

    @PostMapping("/refreshPlanType")
    public ResponseData<?> refreshPlanType(@RequestBody java.util.Map<String, String> params) {
        tenantService.refreshPlanType(params.get("id"));
        return ResponseData.ok();
    }

    @PostMapping("/uploadKey")
    public ResponseData<?> uploadKey(@RequestParam("file") MultipartFile file) throws Exception {
        return ResponseData.ok(tenantService.uploadKey(file));
    }
}
