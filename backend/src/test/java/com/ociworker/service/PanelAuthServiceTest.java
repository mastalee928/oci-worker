package com.ociworker.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.model.entity.OciKv;
import com.ociworker.util.CommonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PanelAuthServiceTest {

    @Test
    void publishedCredentialSnapshotTakesEffectImmediatelyWithoutDatabaseRead() {
        PanelAuthService service = new PanelAuthService();
        String account = "admin-user";
        String passwordHash = DigestUtil.sha256Hex("new-password");

        service.updateCredentialSnapshot(account, passwordHash);

        assertThat(service.validateToken(CommonUtils.generateToken(account, passwordHash))).isTrue();
        assertThat(service.validateToken(CommonUtils.generateToken(account, DigestUtil.sha256Hex("old-password")))).isFalse();
    }

    @Test
    void failedBackgroundRefreshKeepsLastKnownCredentials() {
        PanelAuthService service = new PanelAuthService();
        OciKvMapper kvMapper = mock(OciKvMapper.class);
        ReflectionTestUtils.setField(service, "kvMapper", kvMapper);

        String account = "safe-admin";
        String passwordHash = DigestUtil.sha256Hex("safe-password");
        service.updateCredentialSnapshot(account, passwordHash);
        when(kvMapper.selectOne(any())).thenThrow(new IllegalStateException("database unavailable"));

        service.refreshCredentialSnapshot();

        assertThat(service.validateToken(CommonUtils.generateToken(account, passwordHash))).isTrue();
    }

    @Test
    void incompleteStoredCredentialsDoNotReplaceLastKnownSnapshot() {
        PanelAuthService service = new PanelAuthService();
        OciKvMapper kvMapper = mock(OciKvMapper.class);
        ReflectionTestUtils.setField(service, "kvMapper", kvMapper);
        OciKv storedAccount = new OciKv();
        storedAccount.setValue("partial-admin");
        when(kvMapper.selectOne(any())).thenReturn(storedAccount, null);

        String account = "safe-admin";
        String passwordHash = DigestUtil.sha256Hex("safe-password");
        service.updateCredentialSnapshot(account, passwordHash);
        service.refreshCredentialSnapshot();

        assertThat(service.validateToken(CommonUtils.generateToken(account, passwordHash))).isTrue();
    }

    @Test
    void unconfiguredSystemRejectsFormerDefaultCredentialToken() {
        PanelAuthService service = new PanelAuthService();
        OciKvMapper kvMapper = mock(OciKvMapper.class);
        ReflectionTestUtils.setField(service, "kvMapper", kvMapper);
        when(kvMapper.selectOne(any())).thenReturn(null);

        service.refreshCredentialSnapshot();

        String defaultHash = DigestUtil.sha256Hex("admin123");
        assertThat(service.isReady()).isTrue();
        assertThat(service.isConfigured()).isFalse();
        assertThat(service.validateToken(CommonUtils.generateToken("admin", defaultHash))).isFalse();
        assertThat(service.authenticate("admin", defaultHash)).isNull();
    }

    @Test
    void requestValidationDoesNotLoadDatabaseWhenSnapshotIsUnavailable() {
        PanelAuthService service = new PanelAuthService();

        assertThat(service.isReady()).isFalse();
        assertThat(service.validateToken("any-token")).isFalse();
    }

    @Test
    void modernPasswordHashAuthenticatesRawPassword() {
        PanelAuthService service = new PanelAuthService();
        String encoded = com.ociworker.util.PanelPasswordHasher.hash("modern-password");
        service.updateCredentialSnapshot("admin", encoded);

        assertThat(service.authenticate("admin", "modern-password")).isNotNull();
        assertThat(service.authenticate("admin", "wrong")).isNull();
        assertThat(service.verifyPassword("modern-password")).isTrue();
        assertThat(service.needsPasswordHashUpgrade()).isFalse();
    }
}
