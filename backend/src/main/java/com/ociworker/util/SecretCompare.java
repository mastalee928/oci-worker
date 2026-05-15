package com.ociworker.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * UTF-8 字节级等长比较，用于 Webhook 密钥等场景（{@link MessageDigest#isEqual}）。
 */
public final class SecretCompare {

    private SecretCompare() {}

    public static boolean equalsUtf8(String a, String b) {
        if (a == null) {
            a = "";
        }
        if (b == null) {
            b = "";
        }
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ba.length != bb.length) {
            return false;
        }
        return MessageDigest.isEqual(ba, bb);
    }
}
