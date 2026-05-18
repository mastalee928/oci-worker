package com.ociworker.util;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import cn.hutool.core.util.StrUtil;

import java.nio.charset.StandardCharsets;

/**
 * 面板内可逆保存 OpenAI 网关 sk 密钥（AES，密钥材料来自 web.password）。
 */
public final class OciOpenaiKeyCipher {

    private static final String KEY_SALT = "ociworker-openai-key-v1:";

    private OciOpenaiKeyCipher() {}

    public static String encrypt(String plain, String webPassword) {
        if (StrUtil.isBlank(plain)) {
            return null;
        }
        return getAes(webPassword).encryptBase64(plain);
    }

    public static String decrypt(String encryptedBase64, String webPassword) {
        if (StrUtil.isBlank(encryptedBase64)) {
            return null;
        }
        return getAes(webPassword).decryptStr(encryptedBase64);
    }

    /** 列表展示：sk-abcd****9f0a */
    public static String maskForDisplay(String plain) {
        if (StrUtil.isBlank(plain)) {
            return "sk-****";
        }
        String k = plain.trim();
        if (k.regionMatches(true, 0, "sk-", 0, 3) && k.length() >= 11) {
            return k.substring(0, 7) + "****" + k.substring(k.length() - 4);
        }
        if (k.length() >= 8) {
            return k.substring(0, 4) + "****" + k.substring(k.length() - 4);
        }
        return "sk-****";
    }

    private static AES getAes(String webPassword) {
        String material = KEY_SALT + (webPassword == null ? "" : webPassword);
        byte[] key = SecureUtil.sha256().digest(material.getBytes(StandardCharsets.UTF_8));
        return SecureUtil.aes(key);
    }
}
