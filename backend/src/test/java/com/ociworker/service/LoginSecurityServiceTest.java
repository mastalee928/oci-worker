package com.ociworker.service;

import com.ociworker.enums.SysCfgEnum;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginSecurityServiceTest {

    @Test
    void securityMutationsUpdateSnapshotImmediately() {
        NotificationService notificationService = mock(NotificationService.class);
        when(notificationService.getKvValue(any(SysCfgEnum.class))).thenReturn(null);
        LoginSecurityService service = new LoginSecurityService();
        ReflectionTestUtils.setField(service, "notificationService", notificationService);
        service.refreshSecuritySnapshot();

        assertThat(service.isSitePaused()).isFalse();

        service.setSitePaused(true);
        service.addIpToDenylist("203.0.113.8");
        service.addDeviceToDenylist("device-1");

        assertThat(service.isSitePaused()).isTrue();
        assertThat(service.isDeniedForLogin("203.0.113.8", null)).isTrue();
        assertThat(service.isDeniedForLogin("198.51.100.2", "device-1")).isTrue();

        service.removeIpFromDenylist("203.0.113.8");
        service.removeDeviceFromDenylist("device-1");

        assertThat(service.isDeniedForLogin("203.0.113.8", "device-1")).isFalse();
        verify(notificationService).saveKvValue(SysCfgEnum.SITE_ACCESS_PAUSED, "true");
        verify(notificationService, times(3)).getKvValue(any(SysCfgEnum.class));
    }

    @Test
    void failedBackgroundRefreshKeepsLastKnownSecuritySettings() {
        NotificationService notificationService = mock(NotificationService.class);
        when(notificationService.getKvValue(any(SysCfgEnum.class))).thenReturn(null);
        LoginSecurityService service = new LoginSecurityService();
        ReflectionTestUtils.setField(service, "notificationService", notificationService);
        service.refreshSecuritySnapshot();

        assertThat(service.isSitePaused()).isFalse();
        service.setSitePaused(true);
        when(notificationService.getKvValue(any(SysCfgEnum.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        service.refreshSecuritySnapshot();

        assertThat(service.isSitePaused()).isTrue();
    }

    @Test
    void unavailableSnapshotFailsClosedWithoutDatabaseRead() {
        NotificationService notificationService = mock(NotificationService.class);
        LoginSecurityService service = new LoginSecurityService();
        ReflectionTestUtils.setField(service, "notificationService", notificationService);

        assertThat(service.isSitePaused()).isTrue();
        assertThat(service.isDeniedForLogin("198.51.100.2", "device-1")).isTrue();
        verify(notificationService, times(0)).getKvValue(any(SysCfgEnum.class));
    }

    @Test
    void repeatedPasswordFailuresTemporarilyLockIpAndSuccessClearsIt() {
        NotificationService notificationService = mock(NotificationService.class);
        VerifyCodeService verifyCodeService = mock(VerifyCodeService.class);
        LoginSecurityService service = new LoginSecurityService();
        ReflectionTestUtils.setField(service, "notificationService", notificationService);
        ReflectionTestUtils.setField(service, "verifyCodeService", verifyCodeService);

        for (int i = 0; i < 10; i++) {
            service.onPasswordLoginFailed("admin", "203.0.113.9", null);
        }

        assertThat(service.passwordLoginRetryAfterSeconds("203.0.113.9")).isPositive();
        service.onPasswordLoginSucceeded("203.0.113.9");
        assertThat(service.passwordLoginRetryAfterSeconds("203.0.113.9")).isZero();
    }
}
