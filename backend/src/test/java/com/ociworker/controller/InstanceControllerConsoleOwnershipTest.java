package com.ociworker.controller;

import com.ociworker.exception.OciException;
import com.ociworker.service.ConsoleService;
import com.ociworker.service.PanelAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InstanceControllerConsoleOwnershipTest {

    @Test
    void createConsolePassesAuthenticatedPanelAccountToService() {
        ConsoleService consoleService = mock(ConsoleService.class);
        PanelAuthService panelAuthService = mock(PanelAuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(panelAuthService.authenticatedAccount(request, true, false)).thenReturn("panel-admin");
        when(consoleService.createConsoleConnection(
                "tenant", "instance", "us-phoenix-1", "panel-admin"))
                .thenReturn(Map.of("connectionId", "connection"));
        InstanceController controller = controller(consoleService, panelAuthService);

        controller.createConsole(Map.of(
                "id", "tenant",
                "instanceId", "instance",
                "region", "us-phoenix-1"), request);

        verify(consoleService).createConsoleConnection(
                "tenant", "instance", "us-phoenix-1", "panel-admin");
    }

    @Test
    void deleteConsolePassesAuthenticatedPanelAccountToService() {
        ConsoleService consoleService = mock(ConsoleService.class);
        PanelAuthService panelAuthService = mock(PanelAuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(panelAuthService.authenticatedAccount(request, true, false)).thenReturn("panel-admin");
        InstanceController controller = controller(consoleService, panelAuthService);

        controller.deleteConsole(Map.of(
                "id", "tenant",
                "connectionId", "connection",
                "region", "us-phoenix-1"), request);

        verify(consoleService).deleteConsoleConnection(
                "tenant", "connection", "us-phoenix-1", "panel-admin");
    }

    @Test
    void rejectsConsoleMutationWhenAuthenticatedAccountCannotBeResolved() {
        ConsoleService consoleService = mock(ConsoleService.class);
        PanelAuthService panelAuthService = mock(PanelAuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        InstanceController controller = controller(consoleService, panelAuthService);

        assertThatThrownBy(() -> controller.createConsole(Map.of(
                "id", "tenant", "instanceId", "instance"), request))
                .isInstanceOf(OciException.class)
                .hasMessageContaining("重新登录");
        verifyNoInteractions(consoleService);
    }

    private static InstanceController controller(
            ConsoleService consoleService, PanelAuthService panelAuthService) {
        InstanceController controller = new InstanceController();
        ReflectionTestUtils.setField(controller, "consoleService", consoleService);
        ReflectionTestUtils.setField(controller, "panelAuthService", panelAuthService);
        return controller;
    }
}
