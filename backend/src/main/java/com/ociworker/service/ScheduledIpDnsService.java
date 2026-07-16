package com.ociworker.service;

import com.ociworker.exception.OciException;
import com.ociworker.model.entity.ScheduledIpTask;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ScheduledIpDnsService {
    public static final String PROVIDER_CLOUDFLARE = "CF";
    public static final String PROVIDER_ALIDNS = "ALI";

    @Resource
    private CloudflareService cloudflareService;
    @Resource
    private AliDNSService aliDNSService;

    public DnsSyncResult sync(ScheduledIpTask task, String publicIp) {
        if (task == null || !Boolean.TRUE.equals(task.getDnsEnabled())) {
            return new DnsSyncResult(null, null, null, null, null, "未配置 DNS 同步");
        }
        if (publicIp == null || publicIp.isBlank()) throw new OciException("没有可用于 DNS 同步的公网 IP");
        String provider = normalizeProvider(task.getDnsProvider());
        String fqdn = normalizeFqdn(task.getFqdn());
        return switch (provider) {
            case PROVIDER_CLOUDFLARE -> syncCloudflare(fqdn, publicIp.trim());
            case PROVIDER_ALIDNS -> syncAliDns(fqdn, publicIp.trim());
            default -> throw new OciException("不支持的 DNS 服务商");
        };
    }

    private DnsSyncResult syncCloudflare(String fqdn, String publicIp) {
        Map<String, Object> zone = findCloudflareZone(fqdn);
        String zoneId = string(zone.get("id"));
        String zoneName = string(zone.get("name"));
        if (zoneId == null || zoneName == null) throw new OciException("未找到对应的 Cloudflare Zone");

        List<Map<String, Object>> cnameRecords = exactCloudflareRecords(zoneId, fqdn, "CNAME");
        if (!cnameRecords.isEmpty()) throw new OciException("域名已存在 CNAME 记录，不能自动覆盖");
        List<Map<String, Object>> aRecords = exactCloudflareRecords(zoneId, fqdn, "A");
        if (aRecords.size() > 1) throw new OciException("域名存在多条 A 记录，请先手动处理冲突");

        String recordId;
        if (aRecords.isEmpty()) {
            cloudflareService.addDnsRecord(zoneId, "A", fqdn, publicIp, false, 1, null, null);
            List<Map<String, Object>> created = exactCloudflareRecords(zoneId, fqdn, "A");
            recordId = created.size() == 1 ? string(created.get(0).get("id")) : null;
        } else {
            Map<String, Object> record = aRecords.get(0);
            recordId = string(record.get("id"));
            if (recordId == null) throw new OciException("Cloudflare A 记录缺少记录 ID");
            Boolean proxied = record.get("proxied") instanceof Boolean b ? b : false;
            Integer ttl = number(record.get("ttl"), 1);
            cloudflareService.updateDnsRecord(zoneId, recordId, "A", fqdn, publicIp,
                    proxied, ttl, null, string(record.get("comment")));
        }
        return new DnsSyncResult(zoneId, zoneName, null, recordId, fqdn,
                "Cloudflare DNS 已更新 " + fqdn);
    }

    private Map<String, Object> findCloudflareZone(String fqdn) {
        List<Map<String, Object>> zones = new ArrayList<>();
        int page = 1;
        int totalPages = 1;
        do {
            Map<String, Object> result = cloudflareService.listZonesPage(page, 50);
            zones.addAll(records(result));
            totalPages = Math.max(1, number(result.get("totalPages"), 1));
            page++;
        } while (page <= totalPages);
        return zones.stream()
                .filter(zone -> domainMatches(fqdn, string(zone.get("name"))))
                .max(Comparator.comparingInt(zone -> string(zone.get("name")).length()))
                .orElseThrow(() -> new OciException("未找到对应的 Cloudflare Zone"));
    }

    private List<Map<String, Object>> exactCloudflareRecords(String zoneId, String fqdn, String type) {
        Map<String, Object> result = cloudflareService.listDnsRecordsPage(zoneId, 1, 100, fqdn, type);
        return records(result).stream()
                .filter(row -> fqdn.equalsIgnoreCase(string(row.get("name"))))
                .filter(row -> {
                    String rawType = string(row.get("rawType"));
                    return type.equalsIgnoreCase(rawType != null ? rawType : string(row.get("type")));
                })
                .toList();
    }

    private DnsSyncResult syncAliDns(String fqdn, String publicIp) {
        String domainName = findAliDomain(fqdn);
        String rr = fqdn.equalsIgnoreCase(domainName)
                ? "@"
                : fqdn.substring(0, fqdn.length() - domainName.length() - 1);
        List<Map<String, Object>> cnameRecords = exactAliRecords(domainName, rr, "CNAME");
        if (!cnameRecords.isEmpty()) throw new OciException("域名已存在 CNAME 记录，不能自动覆盖");
        List<Map<String, Object>> aRecords = exactAliRecords(domainName, rr, "A");
        if (aRecords.size() > 1) throw new OciException("域名存在多条 A 记录，请先手动处理冲突");

        String recordId;
        if (aRecords.isEmpty()) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("domainName", domainName);
            input.put("rr", rr);
            input.put("type", "A");
            input.put("value", publicIp);
            input.put("line", "default");
            input.put("ttl", 600);
            recordId = string(aliDNSService.addRecord(input).get("recordId"));
        } else {
            Map<String, Object> record = aRecords.get(0);
            recordId = string(record.get("recordId"));
            if (recordId == null) throw new OciException("阿里云 DNS A 记录缺少记录 ID");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("recordId", recordId);
            input.put("rr", rr);
            input.put("type", "A");
            input.put("value", publicIp);
            input.put("line", string(record.get("line")));
            input.put("ttl", number(record.get("ttl"), 600));
            aliDNSService.updateRecord(input);
        }
        return new DnsSyncResult(null, null, domainName, recordId, rr,
                "阿里云 DNS 已更新 " + fqdn);
    }

    private String findAliDomain(String fqdn) {
        List<Map<String, Object>> domains = new ArrayList<>();
        int page = 1;
        int total = Integer.MAX_VALUE;
        while (domains.size() < total) {
            Map<String, Object> result = aliDNSService.listDomains(page, 100);
            List<Map<String, Object>> rows = records(result);
            domains.addAll(rows);
            total = number(result.get("total"), domains.size());
            if (rows.isEmpty() || rows.size() < 100) break;
            page++;
        }
        return domains.stream()
                .map(row -> string(row.get("domainName")))
                .filter(domain -> domainMatches(fqdn, domain))
                .max(Comparator.comparingInt(String::length))
                .orElseThrow(() -> new OciException("未找到对应的阿里云 DNS 主域"));
    }

    private List<Map<String, Object>> exactAliRecords(String domainName, String rr, String type) {
        Map<String, Object> result = aliDNSService.listRecords(
                domainName, null, "ADVANCED", rr, type, type,
                null, null, null, 1, 100);
        return records(result).stream()
                .filter(row -> rr.equalsIgnoreCase(string(row.get("rr"))))
                .filter(row -> type.equalsIgnoreCase(string(row.get("type"))))
                .toList();
    }

    public static String normalizeProvider(String provider) {
        if (provider == null) return null;
        String value = provider.trim().toUpperCase(Locale.ROOT);
        if ("CLOUDFLARE".equals(value)) return PROVIDER_CLOUDFLARE;
        if ("ALIDNS".equals(value)) return PROVIDER_ALIDNS;
        return value;
    }

    public static String normalizeFqdn(String fqdn) {
        if (fqdn == null || fqdn.isBlank()) throw new OciException("请填写完整域名");
        String value = fqdn.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.length() > 253 || !value.matches("(?i)^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")) {
            throw new OciException("完整域名格式不正确");
        }
        return value;
    }

    private static boolean domainMatches(String fqdn, String domain) {
        if (fqdn == null || domain == null || domain.isBlank()) return false;
        String normalized = domain.trim().toLowerCase(Locale.ROOT);
        return fqdn.equals(normalized) || fqdn.endsWith("." + normalized);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(Map<String, Object> result) {
        Object raw = result != null ? result.get("records") : null;
        if (!(raw instanceof Collection<?> collection)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : collection) {
            if (item instanceof Map<?, ?> map) out.add((Map<String, Object>) map);
        }
        return out;
    }

    private static String string(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static int number(Object value, int defaultValue) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public record DnsSyncResult(
            String zoneId,
            String zoneName,
            String domainName,
            String recordId,
            String recordName,
            String message) {
    }
}
