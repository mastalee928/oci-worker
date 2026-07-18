package com.ociworker.service;

import com.ociworker.mapper.OciLoginAuditMapper;
import com.ociworker.model.entity.OciLoginAudit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LoginAuditServiceTest {

    @Test
    void passwordAndAuthenticationHeadersNeverReachAuditStorage() {
        OciLoginAuditMapper mapper = mock(OciLoginAuditMapper.class);
        LoginAuditService service = new LoginAuditService();
        ReflectionTestUtils.setField(service, "loginAuditMapper", mapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader("Authorization", "Bearer top-secret-token");
        request.addHeader("Cookie", "ow_panel_token=cookie-secret; ow_did=device-secret");
        request.addHeader("X-Custom", "safe-value");
        request.setContentType("application/json");
        request.setContent("{\"account\":\"admin\",\"password\":\"plain-password\"}".getBytes());

        service.recordPasswordLogin("admin", "203.0.113.5", "device-secret", false, request);

        ArgumentCaptor<OciLoginAudit> captor = ArgumentCaptor.forClass(OciLoginAudit.class);
        verify(mapper).insert(captor.capture());
        OciLoginAudit row = captor.getValue();
        assertThat(row.getPasswordAttempt()).isEqualTo("密码已隐藏");
        assertThat(row.getLoginDetail())
                .contains("[REDACTED]", "safe-value")
                .doesNotContain("top-secret-token", "cookie-secret", "plain-password", "device-secret");
    }
}
