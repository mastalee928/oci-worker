package com.ociworker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSshBookmarkCryptoServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsKeyAndUsesUniqueAuthenticatedCiphertext() {
        WebSshBookmarkCryptoService first = createService();
        String one = first.encrypt("printf 'a;\\nb'", "row-1");
        String two = first.encrypt("printf 'a;\\nb'", "row-1");

        assertThat(one).isNotEqualTo(two);
        WebSshBookmarkCryptoService restarted = createService();
        assertThat(restarted.decryptIfEncrypted(one, "row-1")).isEqualTo("printf 'a;\\nb'");
    }

    @Test
    void bindsCiphertextToBookmarkId() {
        WebSshBookmarkCryptoService service = createService();
        String encrypted = service.encrypt("secret", "row-1");

        assertThatThrownBy(() -> service.decryptIfEncrypted(encrypted, "row-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    void rejectsFingerprintMismatch() {
        WebSshBookmarkCryptoService service = createService();

        assertThatThrownBy(() -> service.requireKeyFingerprint("wrong"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("密钥与数据库不匹配");
    }

    @Test
    void clearsInMemoryKeyWhenReloadFails() throws Exception {
        WebSshBookmarkCryptoService service = createService();
        Path keyFile = tempDir.resolve(".webssh-bookmark-aes.key");
        Files.writeString(keyFile, "not-a-valid-base64-key");

        assertThatThrownBy(service::reloadFromDisk)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法初始化");
        assertThatThrownBy(() -> service.encrypt("secret", "row-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未初始化");
    }

    private WebSshBookmarkCryptoService createService() {
        WebSshBookmarkCryptoService service = new WebSshBookmarkCryptoService();
        ReflectionTestUtils.setField(service, "keyDirPath", tempDir.toString());
        service.initialize();
        return service;
    }
}
