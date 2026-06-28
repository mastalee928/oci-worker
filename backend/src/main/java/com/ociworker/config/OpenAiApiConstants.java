package com.ociworker.config;

/**
 * OpenAI 兼容网关中 {@link jakarta.servlet.http.HttpServletRequest} 上使用的属性名。
 */
public final class OpenAiApiConstants {

    public static final String ATTR_TENANT_USER_ID = "ociworker.openai.ociUserId";
    public static final String ATTR_OCI_REGION = "ociworker.openai.ociRegion";
    public static final String ATTR_OPENAI_KEY_ID = "ociworker.openai.keyId";
    public static final String ATTR_PORT_BINDING_ID = "ociworker.openai.portBindingId";
    public static final String ATTR_DEFAULT_MAX_TOKENS = "ociworker.openai.defaultMaxTokens";
    public static final String ATTR_ALLOWED_MODELS_JSON = "ociworker.openai.allowedModelsJson";
    public static final String ATTR_LB_REQUEST = "ociworker.openai.lbRequest";
    public static final String ATTR_LB_KEY_ID = "ociworker.openai.lbKeyId";
    public static final String ATTR_LB_MEMBER_ID = "ociworker.openai.lbMemberId";
    public static final String ATTR_LB_REQUEST_ID = "ociworker.openai.lbRequestId";
    public static final String ATTR_CLIENT_ABORTED = "ociworker.openai.clientAborted";
    public static final String ATTR_USAGE_TOKENS = "ociworker.openai.usageTokens";
    public static final String ATTR_UPSTREAM_STATUS = "ociworker.openai.upstreamStatus";
    public static final String ATTR_STREAM_FIRST_CHUNK_MS = "ociworker.openai.streamFirstChunkMs";
    public static final String ATTR_STREAM_CHUNK_COUNT = "ociworker.openai.streamChunkCount";
    public static final String ATTR_STREAM_TIMEOUT_TYPE = "ociworker.openai.streamTimeoutType";
    public static final String ATTR_STREAM_ESTIMATED_TOKENS = "ociworker.openai.streamEstimatedTokens";
    public static final String ATTR_STREAM_FIRST_CHUNK_TIMEOUT_SECONDS = "ociworker.openai.streamFirstChunkTimeoutSeconds";
    public static final String ATTR_STREAM_IDLE_TIMEOUT_SECONDS = "ociworker.openai.streamIdleTimeoutSeconds";
    public static final String ATTR_STREAM_MAX_SECONDS = "ociworker.openai.streamMaxSeconds";
    public static final String ATTR_GENERATIVE_OPENAI_PROJECT = "ociworker.openai.generativeOpenaiProject";
    public static final String ATTR_GENERATIVE_CONVERSATION_STORE_ID = "ociworker.openai.generativeConversationStoreId";

    private OpenAiApiConstants() {
    }
}
