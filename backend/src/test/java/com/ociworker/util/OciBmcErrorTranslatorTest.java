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

    @Test
    void preservesSanitizedOracleDetailForInvalidQuotaStatement() {
        BmcException error = new BmcException(
                400,
                "InvalidParameter",
                "Quota statement has an invalid service name. Timestamp: 2026-07-13T00:00:00Z "
                        + "Client version: Oracle-JavaSDK/3.83.0",
                "opc-request-id");

        String message = OciBmcErrorTranslator.translateWithServiceDetail(error);

        assertThat(message).contains("请求参数无效");
        assertThat(message).contains("Quota statement has an invalid service name.");
        assertThat(message).doesNotContain("Timestamp");
        assertThat(message).doesNotContain("Client version");
    }

    @Test
    void sanitizesSdkErrorEmbeddedInBusinessMessage() {
        String raw = "列出 Identity Domain 失败: Error returned by ListDomains operation in Identity service."
                + "(401, NotAuthenticated, false) The required information to complete authentication was not provided or was incorrect. "
                + "(opc-request-id: secret) Timestamp: 2026-07-13T14:10:11Z Client version: Oracle-JavaSDK/3.83.0 "
                + "Request Endpoint: https://identity.example.com/domains Troubleshooting Tips: secret";

        String message = OciBmcErrorTranslator.sanitizeClientMessage(raw);

        assertThat(message).isEqualTo("列出 Identity Domain 失败: OCI 认证失败，请检查 API Key、指纹、租户/用户 OCID 与服务器时间。（NotAuthenticated）");
        assertThat(message).doesNotContain("Request Endpoint");
        assertThat(message).doesNotContain("opc-request-id");
    }

    @Test
    void leavesNonOciAndDeliberateQuotaDetailMessagesUntouched() {
        String ordinary = "业务参数不能为空";
        String quota = "保存 Oracle 配额保护失败：请求参数无效 Oracle 返回：Error returned by CreateQuota operation";

        assertThat(OciBmcErrorTranslator.sanitizeClientMessage(ordinary)).isEqualTo(ordinary);
        assertThat(OciBmcErrorTranslator.sanitizeClientMessage(quota)).isEqualTo(quota);
    }

    @Test
    void detectsNestedAuthenticationFailure() {
        RuntimeException error = new RuntimeException("wrapped",
                new BmcException(401, "NotAuthenticated", "credentials invalid", "opc-request-id"));

        assertThat(OciBmcErrorTranslator.isAuthenticationFailure(error)).isTrue();
        assertThat(OciBmcErrorTranslator.isAuthenticationFailure(
                new IllegalStateException("ordinary failure"))).isFalse();
    }
}
