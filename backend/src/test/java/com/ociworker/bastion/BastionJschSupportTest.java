package com.ociworker.bastion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BastionJschSupportTest {

    @Test
    void isolatesTargetHostKeysByTenantAndInstance() {
        BastionConnectionSpec first = spec("tenant-a", "instance-a");
        BastionConnectionSpec otherTenant = spec("tenant-b", "instance-a");
        BastionConnectionSpec otherInstance = spec("tenant-a", "instance-b");

        assertThat(BastionJschSupport.targetHostKeyCacheKey(first))
                .isNotEqualTo(BastionJschSupport.targetHostKeyCacheKey(otherTenant))
                .isNotEqualTo(BastionJschSupport.targetHostKeyCacheKey(otherInstance));
    }

    private static BastionConnectionSpec spec(String tenantId, String instanceId) {
        return new BastionConnectionSpec(
                tenantId, "us-phoenix-1", "session", "host.bastion.us-phoenix-1.oci.oraclecloud.com",
                22, "session-user", "key", "10.0.0.8", 22, "root", 0,
                "password", null, null, instanceId, System.currentTimeMillis() + 60_000,
                false, "SHA256:test");
    }
}
