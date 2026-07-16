package com.ociworker.service;

import com.ociworker.exception.OciException;
import com.ociworker.model.entity.ScheduledIpTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledIpDnsServiceTest {
    @Mock private CloudflareService cloudflareService;
    @Mock private AliDNSService aliDNSService;

    private ScheduledIpDnsService service;

    @BeforeEach
    void setUp() {
        service = new ScheduledIpDnsService();
        ReflectionTestUtils.setField(service, "cloudflareService", cloudflareService);
        ReflectionTestUtils.setField(service, "aliDNSService", aliDNSService);
    }

    @Test
    void cloudflareUsesTheLongestMatchingZone() {
        ScheduledIpTask task = task("CF", "api.sub.example.com");
        when(cloudflareService.listZonesPage(1, 50)).thenReturn(Map.of(
                "records", List.of(
                        Map.of("id", "root", "name", "example.com"),
                        Map.of("id", "sub", "name", "sub.example.com")),
                "totalPages", 1));
        when(cloudflareService.listDnsRecordsPage(eq("sub"), eq(1), eq(100), anyString(), eq("CNAME")))
                .thenReturn(Map.of("records", List.of()));
        when(cloudflareService.listDnsRecordsPage(eq("sub"), eq(1), eq(100), anyString(), eq("A")))
                .thenReturn(Map.of("records", List.of(Map.of(
                        "id", "record-1", "name", "api.sub.example.com", "type", "A",
                        "proxied", true, "ttl", 1))));

        ScheduledIpDnsService.DnsSyncResult result = service.sync(task, "203.0.113.10");

        assertEquals("sub", result.zoneId());
        verify(cloudflareService).updateDnsRecord(
                "sub", "record-1", "A", "api.sub.example.com", "203.0.113.10",
                true, 1, null, null);
    }

    @Test
    void cloudflareRejectsCnameAndMultipleAConflicts() {
        ScheduledIpTask task = task("CF", "api.example.com");
        when(cloudflareService.listZonesPage(1, 50)).thenReturn(Map.of(
                "records", List.of(Map.of("id", "zone", "name", "example.com")), "totalPages", 1));
        when(cloudflareService.listDnsRecordsPage(eq("zone"), eq(1), eq(100), anyString(), eq("CNAME")))
                .thenReturn(Map.of("records", List.of(Map.of(
                        "id", "cname", "name", "api.example.com", "type", "CNAME"))));
        assertThrows(OciException.class, () -> service.sync(task, "203.0.113.10"));

        when(cloudflareService.listDnsRecordsPage(eq("zone"), eq(1), eq(100), anyString(), eq("CNAME")))
                .thenReturn(Map.of("records", List.of(Map.of(
                        "id", "worker-cname", "name", "api.example.com", "type", "Worker", "rawType", "CNAME"))));
        assertThrows(OciException.class, () -> service.sync(task, "203.0.113.10"));

        when(cloudflareService.listDnsRecordsPage(eq("zone"), eq(1), eq(100), anyString(), eq("CNAME")))
                .thenReturn(Map.of("records", List.of()));
        when(cloudflareService.listDnsRecordsPage(eq("zone"), eq(1), eq(100), anyString(), eq("A")))
                .thenReturn(Map.of("records", List.of(
                        Map.of("id", "a1", "name", "api.example.com", "type", "A"),
                        Map.of("id", "a2", "name", "api.example.com", "type", "A"))));
        assertThrows(OciException.class, () -> service.sync(task, "203.0.113.10"));
    }

    @Test
    void aliDnsUsesTheLongestMatchingDomainAndCorrectRr() {
        ScheduledIpTask task = task("ALI", "api.sub.example.com");
        when(aliDNSService.listDomains(1, 100)).thenReturn(Map.of(
                "records", List.of(
                        Map.of("domainName", "example.com"),
                        Map.of("domainName", "sub.example.com")),
                "total", 2));
        when(aliDNSService.listRecords(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyInt(), anyInt())).thenReturn(Map.of("records", List.of()));
        when(aliDNSService.addRecord(any())).thenReturn(Map.of("recordId", "ali-record"));

        ScheduledIpDnsService.DnsSyncResult result = service.sync(task, "203.0.113.20");

        assertEquals("sub.example.com", result.domainName());
        assertEquals("api", result.recordName());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        verify(aliDNSService).addRecord(input.capture());
        assertEquals("sub.example.com", input.getValue().get("domainName"));
        assertEquals("api", input.getValue().get("rr"));
    }

    private static ScheduledIpTask task(String provider, String fqdn) {
        ScheduledIpTask task = new ScheduledIpTask();
        task.setDnsEnabled(true);
        task.setDnsProvider(provider);
        task.setFqdn(fqdn);
        return task;
    }
}
