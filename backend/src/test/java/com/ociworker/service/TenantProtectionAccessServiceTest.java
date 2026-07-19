package com.ociworker.service;

import com.ociworker.exception.OciException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantProtectionAccessServiceTest {
    private final TenantProtectionAccessService service = new TenantProtectionAccessService();

    @Test
    void tokenIsBoundToTenantAndScope() {
        String token = service.issue("tenant-a", "quotaProtectionManage");
        assertThrows(OciException.class,
                () -> service.claim(token, "tenant-b", "quotaProtectionManage"));
    }

    @Test
    void claimedTokenRejectsConcurrentUseAndCompletesOnce() {
        String token = service.issue("tenant-a", "quotaProtectionManage");
        assertDoesNotThrow(() -> service.claim(token, "tenant-a", "quotaProtectionManage"));
        assertThrows(OciException.class,
                () -> service.claim(token, "tenant-a", "quotaProtectionManage"));
        service.complete(token, "tenant-a", "quotaProtectionManage");
        assertThrows(OciException.class,
                () -> service.claim(token, "tenant-a", "quotaProtectionManage"));
    }

    @Test
    void failedOperationCanReleaseTokenForRetry() {
        String token = service.issue("tenant-a", "quotaProtectionManage");
        service.claim(token, "tenant-a", "quotaProtectionManage");
        service.release(token, "tenant-a", "quotaProtectionManage");
        assertDoesNotThrow(() -> service.claim(token, "tenant-a", "quotaProtectionManage"));
    }

    @Test
    void revokedTokenCannotBeUsed() {
        String token = service.issue("tenant-a|instance-a", "updateFaultDomain");
        service.revoke(token, "tenant-a|instance-a", "updateFaultDomain");
        assertThrows(OciException.class,
                () -> service.claim(token, "tenant-a|instance-a", "updateFaultDomain"));
    }

    @Test
    void revokeCannotRemoveTokenForAnotherTarget() {
        String token = service.issue("tenant-a|instance-a", "updateFaultDomain");
        service.revoke(token, "tenant-a|instance-b", "updateFaultDomain");
        assertDoesNotThrow(() -> service.claim(token, "tenant-a|instance-a", "updateFaultDomain"));
    }
}
