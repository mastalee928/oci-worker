package com.ociworker.service;

import com.oracle.bmc.limits.model.Quota;
import com.oracle.bmc.limits.model.QuotaSummary;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenantQuotaProtectionServiceTest {
    private final TenantQuotaProtectionService service = new TenantQuotaProtectionService();

    @Test
    @SuppressWarnings("unchecked")
    void basicProfileDoesNotWritePaidLoadBalancerRules() {
        Map<String, Long> values = ReflectionTestUtils.invokeMethod(service, "resolveValues", "BASIC", Map.of());
        assertNotNull(values);
        assertEquals(4L, values.get("a1Ocpu"));
        assertFalse(values.containsKey("paidLoadBalancer10"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void strictProfileBlocksPaidLoadBalancerRules() {
        Map<String, Long> values = ReflectionTestUtils.invokeMethod(service, "resolveValues", "STRICT", Map.of());
        assertNotNull(values);
        assertEquals(0L, values.get("paidLoadBalancer10"));
        assertEquals(0L, values.get("reservedPublicIp"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void customProfileOnlyKeepsSubmittedResources() {
        Map<String, Long> values = ReflectionTestUtils.invokeMethod(
                service, "resolveValues", "CUSTOM", Map.of("vcn", 1));
        assertEquals(Map.of("vcn", 1L), values);
    }

    @Test
    void unknownStatementMarksManagedPolicyAsIncompatible() throws Exception {
        Quota quota = Quota.builder().statements(List.of("set compute quota unknown-limit to 1 in tenancy")).build();
        Object parsed = ReflectionTestUtils.invokeMethod(service, "parseManagedValues", quota);
        assertNotNull(parsed);
        Method compatible = parsed.getClass().getDeclaredMethod("compatible");
        compatible.setAccessible(true);
        assertEquals(false, compatible.invoke(parsed));
    }

    @Test
    void sameNameWithoutManagedTagIsNotAdopted() {
        QuotaSummary summary = QuotaSummary.builder()
                .id("quota-id")
                .name("OCIWorker-Free-Tier-Protection")
                .freeformTags(Map.of())
                .lifecycleState(QuotaSummary.LifecycleState.Active)
                .build();

        Object managed = ReflectionTestUtils.invokeMethod(service, "findManaged", List.of(summary));
        Object conflict = ReflectionTestUtils.invokeMethod(service, "findNameConflict", List.of(summary));
        assertNull(managed);
        assertSame(summary, conflict);
    }
}
