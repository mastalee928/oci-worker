package com.ociworker.util;

import com.oracle.bmc.model.BmcException;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class OciBmcErrorTranslatorTest {

    @Test
    void translatesOfficialServiceCode() {
        BmcException error = new BmcException(
                429,
                "TooManyRequests",
                "TooManyRequests",
                "opc-request-id");

        String message = OciBmcErrorTranslator.translate(error);

        assertThat(message).isEqualTo("请求过于频繁，请稍后重试。（TooManyRequests）");
    }

    @Test
    void translatesAttachedVolumeMessageBeforeGenericConflict() {
        BmcException error = new BmcException(
                409,
                "Conflict",
                "Volume ocid1.bootvolume.oc1..xxx may not be deleted while attached to an Instance.",
                "opc-request-id");

        String message = OciBmcErrorTranslator.translate(error);

        assertThat(message).isEqualTo("卷仍挂载在实例上，无法删除。请先分离后再删除。");
    }

    @Test
    void stripsSdkTroubleshootingNoiseWhenFallingBackToRawMessage() {
        BmcException error = new BmcException(
                499,
                "UnknownCode",
                "Unknown failure happened. Timestamp: 2026-06-24T00:00:00Z Client version: Oracle-JavaSDK/3.83.0",
                "opc-request-id");

        String message = OciBmcErrorTranslator.translate(error);

        assertThat(message).isEqualTo("Unknown failure happened.");
        assertThat(message).doesNotContain("Client version");
    }

    @Test
    void translatesNestedSocketTimeoutForBackgroundLogs() {
        RuntimeException error = new RuntimeException(
                "jakarta.ws.rs.ProcessingException",
                new SocketTimeoutException("Read timed out"));

        String message = OciBmcErrorTranslator.translate(error);

        assertThat(message).isEqualTo("OCI 接口读取超时，请稍后自动重试。");
    }

    @Test
    void cleansNegativeStatusSdkPrefix() {
        BmcException error = new BmcException(
                -1,
                "",
                "jakarta.ws.rs.ProcessingException: java.net.SocketTimeoutException: Read timed out",
                "opc-request-id");

        String message = OciBmcErrorTranslator.translate(error);

        assertThat(message).isEqualTo("OCI 接口读取超时，请稍后自动重试。");
    }
}
