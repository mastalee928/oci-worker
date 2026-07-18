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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    @Test
    void unconfiguredCredentialSnapshotStillRequiresSetup() {
        PanelAuthService panelAuthService = mock(PanelAuthService.class);
        when(panelAuthService.isReady()).thenReturn(true);
        when(panelAuthService.isConfigured()).thenReturn(false);
        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "panelAuthService", panelAuthService);

        ResponseData<?> response = controller.needSetup();

        assertThat(response.getData()).isEqualTo(true);
    }

    @Test
    void unavailableCredentialSnapshotReturnsServiceUnavailable() {
        PanelAuthService panelAuthService = mock(PanelAuthService.class);
        when(panelAuthService.isReady()).thenReturn(false);
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
        verifyNoInteractions(kvMapper);
    }
}
