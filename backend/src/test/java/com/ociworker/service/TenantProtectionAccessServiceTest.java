package com.ociworker.service;

import com.ociworker.exception.OciException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantProtectionAccessServiceTest {
    private final TenantProtectionAccessService service = new TenantProtectionAccessService();

    @Test
    void tokenIsBoundToTenantAndScopeAndCanOnlyBeConsumedOnce() {
        String token = service.issue("tenant-a", "quotaProtectionManage");
        assertThrows(OciException.class,
                () -> service.consume(token, "tenant-b", "quotaProtectionManage"));

        String validToken = service.issue("tenant-a", "quotaProtectionManage");
        assertDoesNotThrow(() -> service.consume(validToken, "tenant-a", "quotaProtectionManage"));
        assertThrows(OciException.class,
                () -> service.consume(validToken, "tenant-a", "quotaProtectionManage"));
    }
}
