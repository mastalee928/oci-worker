package com.ociworker.webssh;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSshInputAndMetricsTest {

    @Test
    void rejectsInvalidConnectionInfoBeforeJsch() {
        assertThatThrownBy(() -> WebSshConnectInfoParser.parse("%%%"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SSH connection info is invalid");
    }

    @Test
    void malformedCpuCountersKeepDefaultMetric() {
        String raw = "===CPU_USAGE=== 999999999999999999999999 1 2 3";
        assertThat(WebSshSysInfoService.parse(raw).get("cpuUsage")).isEqualTo("0");
    }

    @Test
    void uploadProgressUsesLongCounters() {
        WebSshUploadRegistry registry = new WebSshUploadRegistry();
        registry.track("upload");
        registry.add("upload", Integer.MAX_VALUE);
        registry.add("upload", Integer.MAX_VALUE);
        assertThat(registry.peek("upload")).isEqualTo(4_294_967_294L);
    }
}
