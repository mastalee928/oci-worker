package com.ociworker.util;

import cn.hutool.crypto.digest.DigestUtil;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** 面板密码的带盐慢哈希；兼容旧版 SHA-256，成功登录后可无感迁移。 */
public final class PanelPasswordHasher {

    private static final String PREFIX = "pbkdf2-sha256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PanelPasswordHasher() {}

    public static String hash(String password) {
        if (password == null) throw new IllegalArgumentException("Password is required");
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS, KEY_BYTES);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return PREFIX + "$" + ITERATIONS + "$" + encoder.encodeToString(salt)
                + "$" + encoder.encodeToString(derived);
    }

    public static boolean matches(String password, String stored) {
        if (password == null || stored == null || stored.isBlank()) return false;
        if (isModernHash(stored)) {
            try {
                String[] parts = stored.split("\\$", -1);
                int iterations = Integer.parseInt(parts[1]);
                if (iterations < 100_000 || iterations > 1_000_000) return false;
                Base64.Decoder decoder = Base64.getUrlDecoder();
                byte[] salt = decoder.decode(parts[2]);
                byte[] expected = decoder.decode(parts[3]);
                if (salt.length < 16 || expected.length < 32) return false;
                byte[] actual = derive(password, salt, iterations, expected.length);
                return MessageDigest.isEqual(expected, actual);
            } catch (Exception ignored) {
                return false;
            }
        }
        if (isLegacySha256(stored)) {
            byte[] expected = stored.toLowerCase().getBytes(StandardCharsets.UTF_8);
            byte[] actual = DigestUtil.sha256Hex(password).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, actual);
        }
        // 极早期版本可能直接保存明文，仅用于一次兼容登录并立即升级。
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean needsUpgrade(String stored) {
        return !isModernHash(stored);
    }

    public static boolean isModernHash(String stored) {
        return stored != null && stored.startsWith(PREFIX + "$") && stored.split("\\$", -1).length == 4;
    }

    public static boolean isLegacySha256(String stored) {
        return stored != null && stored.length() == 64 && stored.matches("[0-9a-fA-F]{64}");
    }

    private static byte[] derive(String password, byte[] salt, int iterations, int keyBytes) {
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, keyBytes * 8);
        Arrays.fill(chars, '\0');
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 password hashing failed", e);
        } finally {
            spec.clearPassword();
        }
    }
}
