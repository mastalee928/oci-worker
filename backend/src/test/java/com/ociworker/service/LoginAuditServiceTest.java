package com.ociworker.service;

import com.ociworker.mapper.OciLoginAuditMapper;
import com.ociworker.model.entity.OciLoginAudit;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class LoginAuditServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void fullLoginDetailsAreEncryptedAtRestAndCanBeViewedAfterDecryption() {
        OciLoginAuditMapper mapper = mock(OciLoginAuditMapper.class);
        LoginAuditCryptoService crypto = new LoginAuditCryptoService();
        ReflectionTestUtils.setField(crypto, "keyDirPath", tempDir.toString());
        crypto.initialize();

        LoginAuditService service = new LoginAuditService();
        ReflectionTestUtils.setField(service, "loginAuditMapper", mapper);
        ReflectionTestUtils.setField(service, "loginAuditCryptoService", crypto);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader("Authorization", "Bearer top-secret-token");
        request.addHeader("Cookie", "ow_panel_token=cookie-secret; ow_did=device-secret");
        request.addHeader("X-Custom", "safe-value");
        request.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0");
        request.setContentType("application/json");
        String body = "{\"account\":\"admin\",\"password\":\"plain-password\"}";

        service.recordPasswordLogin("admin", "plain-password", "203.0.113.5", "device-secret",
                false, request, "账号或密码错误", body);

        ArgumentCaptor<OciLoginAudit> captor = ArgumentCaptor.forClass(OciLoginAudit.class);
        verify(mapper).insert(captor.capture());
        OciLoginAudit row = captor.getValue();
        assertThat(row.getPasswordAttempt())
                .startsWith("enc:v1:")
                .doesNotContain("plain-password");
        assertThat(row.getLoginDetail())
                .startsWith("enc:v1:")
                .doesNotContain("top-secret-token", "cookie-secret", "plain-password", "device-secret");
        assertThat(row.getResultMessage()).isEqualTo("账号或密码错误");
        assertThat(row.getOsName()).isEqualTo("Windows");
        assertThat(row.getBrowserName()).isEqualTo("Chrome");

        String credential = crypto.decryptIfEncrypted(row.getPasswordAttempt(), row.getId(), "passwordAttempt");
        String detail = crypto.decryptIfEncrypted(row.getLoginDetail(), row.getId(), "loginDetail");
        assertThat(credential).isEqualTo("plain-password");
        assertThat(detail).contains(
                "top-secret-token", "cookie-secret", "plain-password", "device-secret", "safe-value");
    }

    @Test
    void auditPageDecryptsOnlyForTheGuardedViewingEndpoint() {
        OciLoginAuditMapper mapper = mock(OciLoginAuditMapper.class);
        LoginAuditCryptoService crypto = new LoginAuditCryptoService();
        ReflectionTestUtils.setField(crypto, "keyDirPath", tempDir.toString());
        crypto.initialize();
        LoginAuditService service = new LoginAuditService();
        ReflectionTestUtils.setField(service, "loginAuditMapper", mapper);
        ReflectionTestUtils.setField(service, "loginAuditCryptoService", crypto);

        OciLoginAudit stored = new OciLoginAudit();
        stored.setId("audit-row-1");
        stored.setPasswordAttempt(crypto.encrypt("view-me", stored.getId(), "passwordAttempt"));
        stored.setLoginDetail(crypto.encrypt("{\"detail\":\"full\"}", stored.getId(), "loginDetail"));
        Page<OciLoginAudit> selected = new Page<>(1, 20);
        selected.setRecords(List.of(stored));
        when(mapper.selectPage(any(), any())).thenReturn(selected);

        OciLoginAudit viewed = service.pageAudits(1, 20).getRecords().getFirst();

        assertThat(viewed.getPasswordAttempt()).isEqualTo("view-me");
        assertThat(viewed.getLoginDetail()).isEqualTo("{\"detail\":\"full\"}");
    }
}
