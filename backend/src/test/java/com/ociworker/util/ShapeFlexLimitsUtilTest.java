package com.ociworker.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShapeFlexLimitsUtilTest {

    @Test
    void fixesE2MicroResourcesForFullShapeName() {
        ShapeFlexLimitsUtil.FlexLimits limits =
                ShapeFlexLimitsUtil.forTaskArchitecture("VM.Standard.E2.1.Micro");

        assertThat(limits).isNotNull();
        assertThat(limits.defaultOcpus()).isEqualTo(1f);
        assertThat(limits.defaultMemoryGb()).isEqualTo(1f);
        assertThat(limits.maxOcpus()).isEqualTo(1f);
        assertThat(limits.maxMemoryGb()).isEqualTo(1f);
        assertThat(ShapeFlexLimitsUtil.normalizeOcpusAndMemory(
                "VM.Standard.E2.1.Micro", 512d, 4096d))
                .containsExactly(1d, 1d);
    }

    @Test
    void keepsLegacyAmdTaskCodeEquivalentToE2Micro() {
        assertThat(ShapeFlexLimitsUtil.forTaskArchitecture("AMD"))
                .isEqualTo(ShapeFlexLimitsUtil.forTaskArchitecture("VM.Standard.E2.1.Micro"));
        assertThat(ShapeFlexLimitsUtil.normalizeOcpusAndMemory("AMD", 2d, 8d))
                .containsExactly(1d, 1d);
    }
}
