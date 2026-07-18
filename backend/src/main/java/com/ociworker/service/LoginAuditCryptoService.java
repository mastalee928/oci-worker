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

/**
 * 登录审计敏感字段加密。密钥自动生成在 OCI keys 目录中，随系统加密备份保存，
 * 但不会进入数据库或普通数据库备份。
 */
@Slf4j
@Service
public class LoginAuditCryptoService {

    private static final String PREFIX = "enc:v1:";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String KEY_FILE_NAME = ".login-audit-aes.key";

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${oci-cfg.key-dir-path:./keys}")
    private String keyDirPath;

    private volatile SecretKey secretKey;

    @PostConstruct
    public synchronized void initialize() {
        Path keyFile = resolveKeyFile();
        try {
            Files.createDirectories(keyFile.getParent());
            if (!Files.exists(keyFile)) {
                createKeyFile(keyFile);
            }
            byte[] keyBytes = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
            if (keyBytes.length != KEY_BYTES) {
                throw new IllegalStateException("登录审计密钥长度无效，拒绝自动覆盖现有密钥");
            }
            secretKey = new SecretKeySpec(keyBytes, "AES");
            restrictPermissions(keyFile);
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化登录审计加密密钥 " + keyFile + ": " + e.getMessage(), e);
        }
    }

    /** 系统恢复备份并替换 keys 目录后，立即加载备份中的审计密钥。 */
    public synchronized void reloadFromDisk() {
        initialize();
    }

    public String encrypt(String plainText, String recordId, String fieldName) {
        if (plainText == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(recordId, fieldName));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(cipherText, 0, payload, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("登录审计加密失败", e);
        }
    }

    public String decryptIfEncrypted(String storedValue, String recordId, String fieldName) {
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
            cipher.updateAAD(aad(recordId, fieldName));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("登录审计解密失败", e);
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
            throw new IllegalStateException("无法计算登录审计密钥指纹", e);
        }
    }

    public void requireReady() {
        requireKey();
    }

    /** 防止密钥文件意外丢失后自动生成新密钥，造成同一表内出现无法区分的混合密文。 */
    public synchronized void requireKeyFingerprint(String expectedFingerprint) {
        String actual = currentKeyFingerprint();
        if (expectedFingerprint == null || !MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII),
                expectedFingerprint.trim().getBytes(StandardCharsets.US_ASCII))) {
            secretKey = null;
            throw new IllegalStateException("登录审计密钥与数据库不匹配，请恢复 keys 目录中的原密钥");
        }
    }

    private SecretKey requireKey() {
        SecretKey key = secretKey;
        if (key == null) {
            throw new IllegalStateException("登录审计加密密钥尚未初始化");
        }
        return key;
    }

    private byte[] aad(String recordId, String fieldName) {
        String value = "ociworker-login-audit-v1:" + recordId + ":" + fieldName;
        return value.getBytes(StandardCharsets.UTF_8);
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
        Path tempFile = Files.createTempFile(keyFile.getParent(), ".login-audit-key-", ".tmp");
        try {
            Files.writeString(tempFile, encoded, StandardCharsets.US_ASCII,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            restrictPermissions(tempFile);
            try {
                Files.move(tempFile, keyFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, keyFile);
            }
            log.info("已生成登录审计 AES-256-GCM 密钥: {}", keyFile);
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
