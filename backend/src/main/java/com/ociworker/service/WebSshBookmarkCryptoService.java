package com.ociworker.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-GCM protection for user-created WebSSH script commands. */
@Slf4j
@Service
public class WebSshBookmarkCryptoService {

    private static final String PREFIX = "enc:v1:";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String KEY_FILE_NAME = ".webssh-bookmark-aes.key";

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${oci-cfg.key-dir-path:./keys}")
    private String keyDirPath;

    private volatile SecretKey secretKey;

    @PostConstruct
    public synchronized void initialize() {
        // 任何重载失败都必须 fail closed，不能继续沿用可能已与磁盘不一致的旧密钥。
        secretKey = null;
        Path keyFile = resolveKeyFile();
        try {
            Files.createDirectories(keyFile.getParent());
            if (!Files.exists(keyFile)) {
                createKeyFile(keyFile);
            }
            byte[] keyBytes = Base64.getDecoder().decode(
                    Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
            if (keyBytes.length != KEY_BYTES) {
                throw new IllegalStateException("WebSSH 书签密钥长度无效，拒绝覆盖现有密钥");
            }
            secretKey = new SecretKeySpec(keyBytes, "AES");
            restrictPermissions(keyFile);
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化 WebSSH 书签加密密钥 " + keyFile + ": "
                    + e.getMessage(), e);
        }
    }

    /** ZIP 恢复替换 keys 目录后立即载入备份密钥。 */
    public synchronized void reloadFromDisk() {
        initialize();
    }

    public void requireReady() {
        requireKey();
    }

    public String encrypt(String plainText, String recordId) {
        if (plainText == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(recordId));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(cipherText, 0, payload, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("WebSSH 书签加密失败", e);
        }
    }

    /** 兼容未来/历史明文行；新写入内容始终是密文。 */
    public String decryptIfEncrypted(String storedValue, String recordId) {
        if (storedValue == null || !isEncrypted(storedValue)) {
            return storedValue;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(storedValue.substring(PREFIX.length()));
            if (payload.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文长度无效");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] cipherText = new byte[payload.length - IV_BYTES];
            System.arraycopy(payload, 0, iv, 0, iv.length);
            System.arraycopy(payload, iv.length, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(recordId));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("WebSSH 书签解密失败", e);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public String currentKeyFingerprint() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(requireKey().getEncoded());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法计算 WebSSH 书签密钥指纹", e);
        }
    }

    public void requireKeyFingerprint(String expectedFingerprint) {
        String actual = currentKeyFingerprint();
        if (expectedFingerprint == null || !MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII),
                expectedFingerprint.trim().getBytes(StandardCharsets.US_ASCII))) {
            secretKey = null;
            throw new IllegalStateException("WebSSH 书签密钥与数据库不匹配，请恢复 keys 目录中的原密钥");
        }
    }

    private SecretKey requireKey() {
        SecretKey key = secretKey;
        if (key == null) {
            throw new IllegalStateException("WebSSH 书签加密密钥尚未初始化");
        }
        return key;
    }

    private byte[] aad(String recordId) {
        return ("ociworker-webssh-bookmark-v1:" + recordId + ":command")
                .getBytes(StandardCharsets.UTF_8);
    }

    private Path resolveKeyFile() {
        Path dir = Path.of(keyDirPath == null || keyDirPath.isBlank() ? "./keys" : keyDirPath.trim());
        if (!dir.isAbsolute()) {
            dir = Path.of(System.getProperty("user.dir")).resolve(dir);
        }
        return dir.normalize().resolve(KEY_FILE_NAME);
    }

    private void createKeyFile(Path keyFile) throws IOException {
        byte[] keyBytes = new byte[KEY_BYTES];
        secureRandom.nextBytes(keyBytes);
        String encoded = Base64.getEncoder().encodeToString(keyBytes);
        Path tempFile = Files.createTempFile(keyFile.getParent(), ".webssh-bookmark-key-", ".tmp");
        try {
            Files.writeString(tempFile, encoded, StandardCharsets.US_ASCII,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            restrictPermissions(tempFile);
            try {
                Files.move(tempFile, keyFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, keyFile);
            }
            log.info("已生成 WebSSH 书签 AES-256-GCM 密钥: {}", keyFile);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            Files.deleteIfExists(tempFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 等不支持 POSIX 权限的平台沿用文件系统 ACL。
        }
    }
}
