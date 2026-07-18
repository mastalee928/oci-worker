package com.ociworker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAuditCryptoServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void usesUniqueIvAndPersistsKeyAcrossRestart() {
        LoginAuditCryptoService first = createService();
        String one = first.encrypt("same-secret", "row-1", "passwordAttempt");
        String two = first.encrypt("same-secret", "row-1", "passwordAttempt");
        assertThat(one).isNotEqualTo(two);

        LoginAuditCryptoService restarted = createService();
        assertThat(restarted.decryptIfEncrypted(one, "row-1", "passwordAttempt"))
                .isEqualTo("same-secret");
    }

    @Test
    void bindsCiphertextToRecordAndField() {
        LoginAuditCryptoService service = createService();
        String encrypted = service.encrypt("secret", "row-1", "passwordAttempt");

        assertThatThrownBy(() -> service.decryptIfEncrypted(encrypted, "row-2", "passwordAttempt"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.decryptIfEncrypted(encrypted, "row-1", "loginDetail"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void supportsMaximumLengthUnicodeLoginCredential() {
        LoginAuditCryptoService service = createService();
        String credential = "密".repeat(256);

        String encrypted = service.encrypt(credential, "row-unicode", "passwordAttempt");

        assertThat(encrypted.length()).isGreaterThan(512);
        assertThat(service.decryptIfEncrypted(encrypted, "row-unicode", "passwordAttempt"))
                .isEqualTo(credential);
    }

    @Test
    void fingerprintMismatchDisablesFurtherEncryption() {
        LoginAuditCryptoService service = createService();

        assertThatThrownBy(() -> service.requireKeyFingerprint("wrong-fingerprint"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("密钥与数据库不匹配");
        assertThatThrownBy(() -> service.encrypt("new-secret", "row", "passwordAttempt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未初始化");
    }

    private LoginAuditCryptoService createService() {
        LoginAuditCryptoService service = new LoginAuditCryptoService();
        ReflectionTestUtils.setField(service, "keyDirPath", tempDir.toString());
        service.initialize();
        return service;
    }
}
