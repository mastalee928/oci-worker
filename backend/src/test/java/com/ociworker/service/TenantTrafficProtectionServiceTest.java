package com.ociworker.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantTrafficProtectionServiceTest {
    private final TenantTrafficProtectionService service = new TenantTrafficProtectionService();

    @Test
    void emptyMonitoringPointsAreNotUsableForAutomaticStop() {
        Boolean noVnic = ReflectionTestUtils.invokeMethod(service, "usableTrafficMeasurement",
                Map.of("vnicCount", 0, "points", List.of()));
        Boolean noPoints = ReflectionTestUtils.invokeMethod(service, "usableTrafficMeasurement",
                Map.of("vnicCount", 1, "points", List.of()));
        assertFalse(Boolean.TRUE.equals(noVnic));
        assertFalse(Boolean.TRUE.equals(noPoints));
    }

    @Test
    void populatedMonitoringPointsAreUsable() {
        Boolean usable = ReflectionTestUtils.invokeMethod(service, "usableTrafficMeasurement",
                Map.of("vnicCount", 1, "points", List.of(Map.of("time", "2026-07-01", "outbound", 1))));
        assertTrue(Boolean.TRUE.equals(usable));
    }
}
