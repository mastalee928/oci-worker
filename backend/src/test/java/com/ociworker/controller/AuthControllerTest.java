package com.ociworker.controller;

import com.ociworker.mapper.OciKvMapper;
import com.ociworker.model.params.LoginParams;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.LoginAuditService;
import com.ociworker.service.LoginSecurityService;
import com.ociworker.service.NotificationService;
import com.ociworker.service.PanelAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    @Test
    void unconfiguredCredentialSnapshotStillRequiresSetup() {
        PanelAuthService panelAuthService = mock(PanelAuthService.class);
        when(panelAuthService.ensureReady()).thenReturn(true);
        when(panelAuthService.isConfigured()).thenReturn(false);
        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "panelAuthService", panelAuthService);

        ResponseData<?> response = controller.needSetup();

        assertThat(response.getData()).isEqualTo(true);
    }

    @Test
    void unavailableCredentialSnapshotReturnsServiceUnavailable() {
        PanelAuthService panelAuthService = mock(PanelAuthService.class);
        when(panelAuthService.ensureReady()).thenReturn(false);
        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "panelAuthService", panelAuthService);

        ResponseData<?> response = controller.needSetup();

        assertThat(response.getCode()).isEqualTo(503);
    }

    @Test
    void setupWritesRunInsideTransaction() throws Exception {
        Transactional transactional = AuthController.class
                .getMethod("setup", Map.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
    }

    @Test
    void passwordLoginUsesCredentialSnapshotWithoutDatabaseRead() {
        PanelAuthService panelAuthService = mock(PanelAuthService.class);
        LoginSecurityService loginSecurityService = mock(LoginSecurityService.class);
        LoginAuditService loginAuditService = mock(LoginAuditService.class);
        NotificationService notificationService = mock(NotificationService.class);
        OciKvMapper kvMapper = mock(OciKvMapper.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(panelAuthService.isReady()).thenReturn(true);
        when(panelAuthService.isConfigured()).thenReturn(true);
        when(panelAuthService.authenticate("safe-admin", "safe-password"))
                .thenReturn(new PanelAuthService.AuthenticatedSession("safe-admin", "session-token"));
        LoginAuditService.LoginRequestSnapshot auditSnapshot =
                new LoginAuditService.LoginRequestSnapshot("ua", "{}");
        when(loginAuditService.captureRequestSnapshot(eq(request), anyString())).thenReturn(auditSnapshot);

        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "panelAuthService", panelAuthService);
        ReflectionTestUtils.setField(controller, "loginSecurityService", loginSecurityService);
        ReflectionTestUtils.setField(controller, "loginAuditService", loginAuditService);
        ReflectionTestUtils.setField(controller, "notificationService", notificationService);
        ReflectionTestUtils.setField(controller, "kvMapper", kvMapper);
        LoginParams params = new LoginParams();
        params.setAccount("safe-admin");
        params.setPassword("safe-password");

        ResponseData<?> response = controller.login(params, request);

        assertThat(response.getCode()).isZero();
        assertThat(((Map<?, ?>) response.getData()).get("token")).isEqualTo("session-token");
        verify(loginAuditService).captureRequestSnapshot(eq(request), anyString());
        verify(loginAuditService, timeout(1000)).recordPasswordLogin(
                eq("safe-admin"), eq("safe-password"), any(), any(), eq(true),
                eq(auditSnapshot), eq("登录成功"));
        verifyNoInteractions(kvMapper);
    }

    @Test
    void wrongPasswordRecordsCredentialAndFailureReason() {
        PanelAuthService panelAuthService = mock(PanelAuthService.class);
        LoginSecurityService loginSecurityService = mock(LoginSecurityService.class);
        LoginAuditService loginAuditService = mock(LoginAuditService.class);
        NotificationService notificationService = mock(NotificationService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(panelAuthService.isReady()).thenReturn(true);
        when(panelAuthService.isConfigured()).thenReturn(true);

        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "panelAuthService", panelAuthService);
        ReflectionTestUtils.setField(controller, "loginSecurityService", loginSecurityService);
        ReflectionTestUtils.setField(controller, "loginAuditService", loginAuditService);
        ReflectionTestUtils.setField(controller, "notificationService", notificationService);
        LoginParams params = new LoginParams();
        params.setAccount("attacker");
        params.setPassword("guessed-password");

        ResponseData<?> response = controller.login(params, request);

        assertThat(response.getCode()).isNotZero();
        verify(loginAuditService).recordPasswordLogin(
                eq("attacker"), eq("guessed-password"), any(), any(), eq(false), eq(request),
                eq("账号或密码错误"), anyString());
        verify(loginSecurityService).onPasswordLoginFailed("attacker", "", null);
    }

    @Test
    void rateLimitedPasswordAttemptIsStillAudited() {
        PanelAuthService panelAuthService = mock(PanelAuthService.class);
        LoginSecurityService loginSecurityService = mock(LoginSecurityService.class);
        LoginAuditService loginAuditService = mock(LoginAuditService.class);
        NotificationService notificationService = mock(NotificationService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(panelAuthService.isReady()).thenReturn(true);
        when(panelAuthService.isConfigured()).thenReturn(true);
        when(loginSecurityService.passwordLoginRetryAfterSeconds("")).thenReturn(12L);

        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "panelAuthService", panelAuthService);
        ReflectionTestUtils.setField(controller, "loginSecurityService", loginSecurityService);
        ReflectionTestUtils.setField(controller, "loginAuditService", loginAuditService);
        ReflectionTestUtils.setField(controller, "notificationService", notificationService);
        LoginParams params = new LoginParams();
        params.setAccount("attacker");
        params.setPassword("guessed-password");

        ResponseData<?> response = controller.login(params, request);

        assertThat(response.getCode()).isEqualTo(429);
        verify(loginAuditService).recordPasswordLogin(
                eq("attacker"), eq("guessed-password"), any(), any(), eq(false), eq(request),
                eq("频率限制（剩余 12 秒）"), anyString());
    }
}
