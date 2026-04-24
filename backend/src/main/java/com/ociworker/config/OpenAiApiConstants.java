package com.ociworker.config;

/**
 * OpenAI 兼容网关中 {@link jakarta.servlet.http.HttpServletRequest} 上使用的属性名。
 */
public final class OpenAiApiConstants {

    public static final String ATTR_TENANT_USER_ID = "ociworker.openai.ociUserId";
    public static final String ATTR_OPENAI_KEY_ID = "ociworker.openai.keyId";

    private OpenAiApiConstants() {
    }
}
