package com.ociworker.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;

public final class SecureRandomUtil {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SecureRandomUtil() {
    }

    public static String randomDigits(int length) {
        if (length <= 0) {
            return "";
        }
        if (length == 6) {
            return String.format(Locale.ROOT, "%06d", SECURE_RANDOM.nextInt(1_000_000));
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('0' + SECURE_RANDOM.nextInt(10)));
        }
        return sb.toString();
    }

    public static String randomDigits(int length, String instanceSecret) {
        if (instanceSecret == null || instanceSecret.isBlank()) {
            return randomDigits(length);
        }
        if (length != 6) {
            return randomDigits(length);
        }
        try {
            byte[] random = new byte[32];
            SECURE_RANDOM.nextBytes(random);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(random);
            digest.update(instanceSecret.getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toString(System.nanoTime()).getBytes(StandardCharsets.UTF_8));
            byte[] out = digest.digest();
            int value = ((out[0] & 0xff) << 24)
                    | ((out[1] & 0xff) << 16)
                    | ((out[2] & 0xff) << 8)
                    | (out[3] & 0xff);
            value = value & 0x7fffffff;
            return String.format(Locale.ROOT, "%06d", value % 1_000_000);
        } catch (NoSuchAlgorithmException e) {
            return randomDigits(length);
        }
    }

    public static String randomString(String alphabet, int length) {
        if (alphabet == null || alphabet.isEmpty() || length <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(SECURE_RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    public static String randomHex(int bytes) {
        if (bytes <= 0) {
            return "";
        }
        byte[] buf = new byte[bytes];
        SECURE_RANDOM.nextBytes(buf);
        return toHex(buf);
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(out);
    }
}
