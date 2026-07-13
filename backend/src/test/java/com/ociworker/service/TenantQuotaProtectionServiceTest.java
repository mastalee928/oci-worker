package com.ociworker.service;

import com.oracle.bmc.limits.model.Quota;
import com.oracle.bmc.limits.model.QuotaSummary;
import com.oracle.bmc.model.BmcException;
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
        assertEquals(0L, values.get("paidComputeA2"));
        assertEquals(0L, values.get("paidComputeE4"));
        assertEquals(0L, values.get("paidGpuA10"));
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

    @Test
    @SuppressWarnings("unchecked")
    void gpuA10UsesComputeCoreQuotaFamily() {
        List<String> statements = ReflectionTestUtils.invokeMethod(
                service, "buildStatements", Map.of("paidGpuA10", 0L));
        assertEquals(List.of("zero compute-core quota gpu-a10-count in tenancy"), statements);
    }

    @Test
    @SuppressWarnings("unchecked")
    void accountLimitsUseLimitsApiServiceNames() {
        List<Map<String, Object>> resources = ReflectionTestUtils.invokeMethod(service, "resourceViews", Map.of(
                "compute/standard-a1-core-count", 6L,
                "block-storage/total-storage-gb", 300L));
        assertNotNull(resources);
        Map<String, Object> a1 = resources.stream().filter(row -> "a1Ocpu".equals(row.get("key"))).findFirst().orElseThrow();
        Map<String, Object> storage = resources.stream().filter(row -> "blockStorage".equals(row.get("key"))).findFirst().orElseThrow();
        assertEquals(6L, a1.get("accountLimit"));
        assertEquals(300L, storage.get("accountLimit"));
    }

    @Test
    void legacyGpuA10StatementCanBeMigrated() throws Exception {
        Quota quota = Quota.builder()
                .statements(List.of("zero compute quota gpu-a10-count in tenancy"))
                .build();
        Object parsed = ReflectionTestUtils.invokeMethod(service, "parseManagedValues", quota);
        assertNotNull(parsed);
        Method compatible = parsed.getClass().getDeclaredMethod("compatible");
        Method values = parsed.getClass().getDeclaredMethod("values");
        compatible.setAccessible(true);
        values.setAccessible(true);
        assertEquals(true, compatible.invoke(parsed));
        assertEquals(Map.of("paidGpuA10", 0L), values.invoke(parsed));
    }

    @Test
    @SuppressWarnings("unchecked")
    void completeAccountLimitsFilterUnsupportedStaticRules() {
        Map<String, Long> requested = Map.of("a1Ocpu", 4L, "paidComputeE3", 0L);
        Object limits = newAccountLimits(Map.of("compute/standard-a1-core-count", 4L), true);

        Map<String, Long> filtered = ReflectionTestUtils.invokeMethod(
                service, "filterSupportedValues", requested, limits);

        assertEquals(Map.of("a1Ocpu", 4L), filtered);
    }

    @Test
    void oracleInvalidQuotaResponseRemovesOnlyRejectedStatement() {
        List<String> statements = new java.util.ArrayList<>(List.of(
                "set compute-core quota standard-a1-core-count to 4 in tenancy",
                "zero compute-core quota standard-e3-core-count in tenancy"));
        BmcException error = new BmcException(400, "InvalidParameter",
                "The specified quota `standard-e3-core-count` is not a valid quota name for service `compute-core`",
                "request-id");

        String removed = ReflectionTestUtils.invokeMethod(service, "removeUnsupportedStatement", statements, error);

        assertEquals("standard-e3-core-count", removed);
        assertEquals(List.of("set compute-core quota standard-a1-core-count to 4 in tenancy"), statements);
    }

    private Object newAccountLimits(Map<String, Long> values, boolean complete) {
        try {
            Class<?> type = Class.forName("com.ociworker.service.TenantQuotaProtectionService$AccountLimits");
            var constructor = type.getDeclaredConstructor(Map.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(values, complete);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
