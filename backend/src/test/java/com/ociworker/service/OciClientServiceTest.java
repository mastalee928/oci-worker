package com.ociworker.service;

import com.oracle.bmc.model.BmcException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OciClientServiceTest {

    @Test
    void describesZeroFlexShapeQuotaInChinese() {
        BmcException error = new BmcException(
                400,
                "InvalidParameter",
                "Invalid ratio of memory in GB to OCPUs. Current ratio: 3.0. Valid ratio range: 0 - 0",
                "opc-request-id");

        String message = OciClientService.describeBmcFailure(error, "VM.Standard.A2.Flex");

        assertThat(message).isEqualTo("当前账号没有 VM.Standard.A2.Flex 的 OCPU/内存配额");
        assertThat(message).doesNotContain("Invalid ratio");
    }
}
