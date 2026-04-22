package com.ociworker.controller;

import com.ociworker.model.params.IdListParams;
import com.ociworker.model.params.IdParams;
import com.ociworker.model.params.PageParams;
import com.ociworker.model.params.TenantParams;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.DomainManagementService;
import com.ociworker.service.TenantService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/oci/user")
public class TenantController {

    @Resource
    private TenantService tenantService;
    @Resource
    private DomainManagementService domainManagementService;

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

    @PostMapping("/billingSummary")
    public ResponseData<?> billingSummary(@RequestBody java.util.Map<String, Object> params) {
        String id = params == null ? null : String.valueOf(params.get("id"));
        Object limits = params == null ? null : params.get("limits");
        return ResponseData.ok(tenantService.getTenantBillingSummary(id, limits));
    }

    @PostMapping("/invoicePdf")
    public ResponseEntity<byte[]> invoicePdf(@RequestBody java.util.Map<String, String> params) {
        String id = params == null ? null : params.get("id");
        String invoiceId = params == null ? null : params.get("invoiceId");
        String fileName = params == null ? null : params.get("fileName");
        byte[] pdf = tenantService.downloadInvoicePdf(id, invoiceId);
        String safeName = (fileName == null || fileName.isBlank()) ? ("invoice-" + (invoiceId == null ? "unknown" : invoiceId) + ".pdf") : fileName;
        String encoded = URLEncoder.encode(safeName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(pdf);
    }

    @PostMapping("/uploadKey")
    public ResponseData<?> uploadKey(@RequestParam("file") MultipartFile file) throws Exception {
        return ResponseData.ok(tenantService.uploadKey(file));
    }

    @PostMapping("/domainSettings")
    public ResponseData<?> domainSettings(@RequestBody java.util.Map<String, String> params) {
        return ResponseData.ok(domainManagementService.getDomainSettings(params.get("id")));
    }

    @PostMapping("/updateMfa")
    public ResponseData<?> updateMfa(@RequestBody java.util.Map<String, Object> params) {
        domainManagementService.updateMfaSetting(
                (String) params.get("id"),
                (String) params.get("domainId"),
                Boolean.TRUE.equals(params.get("enabled")));
        return ResponseData.ok();
    }

    @PostMapping("/updatePasswordExpiry")
    public ResponseData<?> updatePasswordExpiry(@RequestBody java.util.Map<String, Object> params) {
        Object daysRaw = params == null ? null : params.get("days");
        if (daysRaw == null) {
            return ResponseData.error("days 不能为空");
        }
        int days;
        if (daysRaw instanceof Number n) {
            days = n.intValue();
        } else {
            try { days = Integer.parseInt(String.valueOf(daysRaw)); }
            catch (NumberFormatException e) { return ResponseData.error("days 格式非法"); }
        }
        domainManagementService.updatePasswordExpiry((String) params.get("id"), (String) params.get("domainId"), days);
        return ResponseData.ok();
    }

    @PostMapping("/auditLogs")
    public ResponseData<?> auditLogs(@RequestBody java.util.Map<String, Object> params) {
        int days = 7;
        Object raw = params == null ? null : params.get("days");
        if (raw instanceof Number n) days = n.intValue();
        else if (raw != null) {
            try { days = Integer.parseInt(String.valueOf(raw)); } catch (Exception ignored) {}
        }
        String id = params == null ? null : String.valueOf(params.get("id"));
        return ResponseData.ok(domainManagementService.getAuditLogs(id, days));
    }

    @PostMapping("/quotas")
    public ResponseData<?> quotas(@RequestBody java.util.Map<String, String> params) {
        return ResponseData.ok(domainManagementService.getServiceQuotas(params.get("id")));
    }

    @PostMapping("/authFactorsUnlock")
    public ResponseData<?> authFactorsUnlock(@RequestBody java.util.Map<String, String> params) {
        String code = params == null ? null : params.get("verifyCode");
        String token = domainManagementService.unlockAuthFactors(code);
        return ResponseData.ok(java.util.Map.of("accessToken", token));
    }

    @PostMapping("/authFactors")
    public ResponseData<?> authFactors(@RequestBody java.util.Map<String, String> params) {
        return ResponseData.ok(domainManagementService.listAuthFactorSettings(
                params.get("id"), params.get("accessToken")));
    }

    @PostMapping("/updateAuthFactors")
    @SuppressWarnings("unchecked")
    public ResponseData<?> updateAuthFactors(@RequestBody java.util.Map<String, Object> params) {
        return ResponseData.ok(domainManagementService.updateAuthFactorSettings(
                (String) params.get("id"),
                (String) params.get("domainId"),
                (String) params.get("accessToken"),
                (java.util.Map<String, Object>) params.get("factors"),
                (java.util.Map<String, Object>) params.get("limits"),
                (java.util.Map<String, Object>) params.get("trustedDevice")));
    }

    @GetMapping("/groups")
    public ResponseData<?> groups() {
        return ResponseData.ok(tenantService.getDistinctGroups());
    }

    @PostMapping("/saveGroupOrder")
    public ResponseData<?> saveGroupOrder(@RequestBody java.util.Map<String, Object> params) {
        Object raw = params == null ? null : params.get("order");
        java.util.List<String> order = new java.util.ArrayList<>();
        if (raw instanceof java.util.List<?> list) {
            for (Object o : list) {
                if (o != null) order.add(String.valueOf(o));
            }
        }
        tenantService.saveGroupOrder(order);
        return ResponseData.ok();
    }

    @PostMapping("/createGroup")
    public ResponseData<?> createGroup(@RequestBody java.util.Map<String, String> params) {
        tenantService.createGroup(params.get("name"), params.get("level"), params.get("parent"));
        return ResponseData.ok();
    }

    @PostMapping("/renameGroup")
    public ResponseData<?> renameGroup(@RequestBody java.util.Map<String, String> params) {
        tenantService.renameGroup(params.get("oldName"), params.get("newName"), params.get("level"));
        return ResponseData.ok();
    }

    @PostMapping("/deleteGroup")
    public ResponseData<?> deleteGroup(@RequestBody java.util.Map<String, String> params) {
        tenantService.deleteGroup(params.get("name"), params.get("level"));
        return ResponseData.ok();
    }
}
