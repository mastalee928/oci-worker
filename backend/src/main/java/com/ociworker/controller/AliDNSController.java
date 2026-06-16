package com.ociworker.controller;

import com.ociworker.service.AliDNSService;
import com.ociworker.model.vo.ResponseData;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/alidns")
public class AliDNSController {

    @Resource
    private AliDNSService aliDNSService;

    @GetMapping("/account/config")
    public ResponseData<?> getAccountConfig() {
        return ResponseData.ok(aliDNSService.getAccountConfigForDisplay());
    }

    @PostMapping("/account/config")
    public ResponseData<?> saveAccountConfig(@RequestBody Map<String, String> params) {
        aliDNSService.saveAccountConfig(params.get("accessKeyId"), params.get("accessKeySecret"));
        return ResponseData.ok();
    }

    @PostMapping("/account/test")
    public ResponseData<?> testAccountConfig(@RequestBody Map<String, String> params) {
        String msg = aliDNSService.testAccountConfig(params.get("accessKeyId"), params.get("accessKeySecret"));
        return ResponseData.ok(msg);
    }

    @PostMapping("/domains/list")
    public ResponseData<?> listDomains(@RequestBody Map<String, Object> params) {
        int page = params.get("page") != null ? ((Number) params.get("page")).intValue() : 1;
        int perPage = params.get("perPage") != null ? ((Number) params.get("perPage")).intValue() : 20;
        return ResponseData.ok(aliDNSService.listDomains(page, perPage));
    }

    @PostMapping("/domains/dns-servers")
    public ResponseData<?> listDomainDnsServers(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(aliDNSService.listDomainDnsServers(parseString(params.get("domainName"))));
    }
    @PostMapping("/records/list")
    public ResponseData<?> listRecords(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(aliDNSService.listRecords(
                parseString(params.get("domainName")),
                parseString(params.get("keyWord")),
                parseString(params.get("searchMode")),
                parseString(params.get("rrKeyWord")),
                parseString(params.get("typeKeyWord")),
                parseString(params.get("type")),
                parseString(params.get("valueKeyWord")),
                parseString(params.get("line")),
                parseString(params.get("status")),
                parseInteger(params.get("page"), 1),
                parseInteger(params.get("perPage"), 50)));
    }

    @PostMapping("/records/add")
    public ResponseData<?> addRecord(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(aliDNSService.addRecord(params));
    }

    @PostMapping("/records/update")
    public ResponseData<?> updateRecord(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(aliDNSService.updateRecord(params));
    }

    @PostMapping("/records/delete")
    public ResponseData<?> deleteRecord(@RequestBody Map<String, Object> params) {
        aliDNSService.deleteRecord(firstNonBlank(parseString(params.get("recordId")), parseString(params.get("RecordId"))));
        return ResponseData.ok();
    }

    @PostMapping("/records/status")
    public ResponseData<?> setRecordStatus(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(aliDNSService.setRecordStatus(
                firstNonBlank(parseString(params.get("recordId")), parseString(params.get("RecordId"))),
                parseString(params.get("status"))));
    }

    @PostMapping("/lines/list")
    public ResponseData<?> listSupportLines(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(aliDNSService.listSupportLines(
                parseString(params.get("domainName")),
                parseString(params.get("domainType"))));
    }

    private String parseString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private int parseInteger(Object value, int def) {
        if (value == null) return def;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return def;
        }
    }
}

