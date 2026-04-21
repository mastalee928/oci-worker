package com.ociworker.controller;

import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.StorageService;
import com.ociworker.service.VerifyCodeService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/oci/storage")
public class StorageController {

    @Resource
    private StorageService storageService;
    @Resource
    private VerifyCodeService verifyCodeService;

    /** 当前租户在 OCI 已订阅的区域（非 SDK 枚举的全部公有区域） */
    @PostMapping("/regions")
    public ResponseData<?> regions(@RequestBody Map<String, String> params) {
        return ResponseData.ok(storageService.listSubscribedRegionIds(params.get("id")));
    }

    @PostMapping("/compartments")
    public ResponseData<?> compartments(@RequestBody Map<String, String> params) {
        return ResponseData.ok(storageService.listCompartments(params.get("id"), params.get("region")));
    }

    /** 块存储：一次拉取当前 Region 下各资源列表（含挂载摘要） */
    @PostMapping("/block/aggregate")
    public ResponseData<?> blockAggregate(@RequestBody Map<String, String> params) {
        return ResponseData.ok(storageService.blockAggregate(
                params.get("id"),
                params.get("region"),
                params.get("compartmentId"),
                params.get("sections")));
    }

    /** 对象存储：命名空间、桶、专用端点 */
    @PostMapping("/object/aggregate")
    public ResponseData<?> objectAggregate(@RequestBody Map<String, String> params) {
        return ResponseData.ok(storageService.objectAggregate(
                params.get("id"),
                params.get("region"),
                params.get("compartmentId")));
    }

    @PostMapping("/delete")
    public ResponseData<?> delete(@RequestBody Map<String, String> params) {
        verifyCodeService.verifyCode("deleteStorage", params.get("verifyCode"));
        storageService.deleteResource(
                params.get("id"),
                params.get("region"),
                params.get("resourceType"),
                params.get("resourceId"),
                params.get("namespace"),
                params.get("bucketName"));
        return ResponseData.ok();
    }

    /** 保存桶策略（非删除，但需要 TG 验证码） */
    @PostMapping("/object/bucketPolicy")
    public ResponseData<?> putBucketPolicy(@RequestBody Map<String, String> params) {
        verifyCodeService.verifyCode("editBucketPolicy", params.get("verifyCode"));
        storageService.putBucketPolicy(
                params.get("id"),
                params.get("region"),
                params.get("namespace"),
                params.get("bucketName"),
                params.get("policy"));
        return ResponseData.ok();
    }

    @PostMapping("/mutate")
    public ResponseData<?> mutate(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(storageService.mutate(params));
    }
}
