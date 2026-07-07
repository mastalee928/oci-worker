package com.ociworker.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OracleAiModelCapabilityTest {

    @Test
    void classifiesKnownOracleAiModelFamiliesWithoutFullAllowlist() {
        assertThat(OracleAiModelCapability.classify("xai.grok-4.3"))
                .isEqualTo(OracleAiModelCapability.CHAT);
        assertThat(OracleAiModelCapability.classify("xai.grok-4.20-0309-reasoning"))
                .isEqualTo(OracleAiModelCapability.CHAT);
        assertThat(OracleAiModelCapability.classify("xai.grok-4.20-reasoning"))
                .isEqualTo(OracleAiModelCapability.CHAT);
        assertThat(OracleAiModelCapability.classify("xai.grok-4.20-0309-non-reasoning"))
                .isEqualTo(OracleAiModelCapability.CHAT);
        assertThat(OracleAiModelCapability.classify("xai.grok-4.20-non-reasoning"))
                .isEqualTo(OracleAiModelCapability.CHAT);
        assertThat(OracleAiModelCapability.classify("xai.grok-4.20-multi-agent-0309"))
                .isEqualTo(OracleAiModelCapability.MULTI_AGENT);
        assertThat(OracleAiModelCapability.classify("google.gemini-2.5-pro"))
                .isEqualTo(OracleAiModelCapability.CHAT);
        assertThat(OracleAiModelCapability.classify("google.gemini-2.5-flash"))
                .isEqualTo(OracleAiModelCapability.CHAT);
        assertThat(OracleAiModelCapability.classify("google.gemini-2.5-flash-lite"))
                .isEqualTo(OracleAiModelCapability.CHAT);
        assertThat(OracleAiModelCapability.classify("cohere.command-a-reasoning"))
                .isEqualTo(OracleAiModelCapability.CHAT);
        assertThat(OracleAiModelCapability.classify("cohere.command-a-03-2025"))
                .isEqualTo(OracleAiModelCapability.CHAT);
        assertThat(OracleAiModelCapability.classify("cohere.command-a-vision"))
                .isEqualTo(OracleAiModelCapability.CHAT);
        assertThat(OracleAiModelCapability.classify("meta.llama-4-maverick-17b-128e-instruct-fp8"))
                .isEqualTo(OracleAiModelCapability.CHAT);
        assertThat(OracleAiModelCapability.classify("meta.llama-4-scout-17b-16e-instruct"))
                .isEqualTo(OracleAiModelCapability.CHAT);
        assertThat(OracleAiModelCapability.classify("meta.llama-3.3-70b-instruct"))
                .isEqualTo(OracleAiModelCapability.CHAT);
        assertThat(OracleAiModelCapability.classify("meta.llama-3.3-70b-instruct-fp8-dynamic"))
                .isEqualTo(OracleAiModelCapability.CHAT);
        assertThat(OracleAiModelCapability.classify("cohere.embed-v4.0"))
                .isEqualTo(OracleAiModelCapability.EMBEDDING);
        assertThat(OracleAiModelCapability.classify("cohere.rerank-v4.0-fast"))
                .isEqualTo(OracleAiModelCapability.RERANK);
        assertThat(OracleAiModelCapability.classify("xai.grok-tts"))
                .isEqualTo(OracleAiModelCapability.AUDIO);
        assertThat(OracleAiModelCapability.classify("content-moderator"))
                .isEqualTo(OracleAiModelCapability.MODERATION);
        assertThat(OracleAiModelCapability.classify("meta.llama-guard-4-12b"))
                .isEqualTo(OracleAiModelCapability.MODERATION);
    }

    @Test
    void keepsUnconfirmedVoiceAgentPendingUntilOciCapabilityIsKnown() {
        assertThat(OracleAiModelCapability.classify("xai.grok-voice-agent"))
                .isEqualTo(OracleAiModelCapability.PENDING);
    }

    @Test
    void returnsDocumentedContextAndTpmLimitsForKnownOciChatModels() {
        assertThat(OracleAiModelCapability.documentedContextLimit("xai.grok-4.3")).isEqualTo(1_000_000L);
        assertThat(OracleAiModelCapability.documentedTpmLimit("xai.grok-4.3")).isEqualTo(200_000L);
        assertThat(OracleAiModelCapability.documentedContextLimit("xai.grok-4-fast-reasoning")).isEqualTo(2_000_000L);
        assertThat(OracleAiModelCapability.documentedContextLimit("xai.grok-code-fast-1")).isEqualTo(256_000L);
        assertThat(OracleAiModelCapability.documentedContextLimit("xai.grok-3-mini-fast")).isEqualTo(131_072L);
        assertThat(OracleAiModelCapability.documentedTpmLimit("xai.grok-3-mini-fast")).isEqualTo(100_000L);
        assertThat(OracleAiModelCapability.documentedContextLimit("google.gemini-2.5-pro")).isEqualTo(1_000_000L);
        assertThat(OracleAiModelCapability.documentedContextLimit("google.gemini-2.5-flash", "ap-osaka-1")).isEqualTo(128_000L);
        assertThat(OracleAiModelCapability.documentedContextLimit("google.gemini-2.5-flash-lite", "ap-osaka-1")).isEqualTo(1_000_000L);
        assertThat(OracleAiModelCapability.documentedTpmLimit("google.gemini-2.5-flash")).isEqualTo(100_000L);
        assertThat(OracleAiModelCapability.documentedContextLimit("openai.gpt-oss-120b")).isEqualTo(128_000L);
        assertThat(OracleAiModelCapability.documentedContextLimit("meta.llama-4-maverick-17b-128e-instruct-fp8")).isEqualTo(512_000L);
        assertThat(OracleAiModelCapability.documentedContextLimit("cohere.command-a-reasoning")).isEqualTo(256_000L);
        assertThat(OracleAiModelCapability.documentedContextLimit("cohere.command-r-plus-08-2024")).isEqualTo(128_000L);
        assertThat(OracleAiModelCapability.documentedTpmLimit("cohere.command-a-reasoning")).isZero();
    }

    @Test
    void onlyChatAndMultiAgentAreAcceptedByChatStyleEndpoints() {
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("xai.grok-4.3")).isTrue();
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("xai.grok-4.20-multi-agent")).isTrue();
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("cohere.command-a-reasoning")).isTrue();
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("cohere.command-a-03-2025")).isTrue();
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("cohere.command-a-vision")).isTrue();
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("meta.llama-4-maverick-17b-128e-instruct-fp8")).isTrue();
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("meta.llama-4-scout-17b-16e-instruct")).isTrue();
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("meta.llama-3.3-70b-instruct")).isTrue();
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("meta.llama-3.3-70b-instruct-fp8-dynamic")).isTrue();
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("cohere.embed-v4.0")).isFalse();
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("cohere.rerank-v4.0-pro")).isFalse();
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("xai.grok-tts")).isFalse();
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("xai.grok-voice-agent")).isFalse();
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("meta.llama-guard-4-12b")).isFalse();
    }

    @Test
    void knownNonEmbeddingModelsAreRejectedByEmbeddingsEndpoint() {
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("cohere.embed-v4.0")).isTrue();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("cohere.embed-english-v3.0")).isTrue();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("xai.grok-4.3")).isFalse();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("cohere.command-a-reasoning")).isFalse();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("cohere.command-a-03-2025")).isFalse();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("cohere.command-a-vision")).isFalse();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("meta.llama-4-maverick-17b-128e-instruct-fp8")).isFalse();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("meta.llama-4-scout-17b-16e-instruct")).isFalse();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("meta.llama-3.3-70b-instruct")).isFalse();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("meta.llama-3.3-70b-instruct-fp8-dynamic")).isFalse();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("xai.grok-4.20-multi-agent")).isFalse();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("cohere.rerank-v4.0-fast")).isFalse();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("xai.grok-tts")).isFalse();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("xai.grok-voice-agent")).isFalse();
    }

    @Test
    void unknownModelsAreAllowedThroughEmbeddingsEndpointForFutureOciAdditions() {
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("oracle.vector-v1")).isTrue();
    }

    @Test
    void knownNonRerankModelsAreRejectedByRerankEndpoint() {
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("cohere.rerank-v4.0-fast")).isTrue();
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("xai.grok-4.3")).isFalse();
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("cohere.command-a-reasoning")).isFalse();
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("cohere.command-a-03-2025")).isFalse();
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("cohere.command-a-vision")).isFalse();
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("meta.llama-4-maverick-17b-128e-instruct-fp8")).isFalse();
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("meta.llama-4-scout-17b-16e-instruct")).isFalse();
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("meta.llama-3.3-70b-instruct")).isFalse();
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("meta.llama-3.3-70b-instruct-fp8-dynamic")).isFalse();
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("xai.grok-4.20-multi-agent")).isFalse();
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("cohere.embed-v4.0")).isFalse();
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("xai.grok-tts")).isFalse();
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("xai.grok-voice-agent")).isFalse();
    }

    @Test
    void audioSpeechEndpointAcceptsAudioModelsAndRejectsKnownWrongFamilies() {
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("xai.grok-tts")).isTrue();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("oracle.tts-v1")).isTrue();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("oracle.future-speech-v1")).isTrue();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("xai.grok-4.3")).isFalse();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("cohere.command-a-reasoning")).isFalse();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("cohere.command-a-03-2025")).isFalse();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("cohere.command-a-vision")).isFalse();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("meta.llama-4-maverick-17b-128e-instruct-fp8")).isFalse();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("meta.llama-4-scout-17b-16e-instruct")).isFalse();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("meta.llama-3.3-70b-instruct")).isFalse();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("meta.llama-3.3-70b-instruct-fp8-dynamic")).isFalse();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("xai.grok-4.20-multi-agent")).isFalse();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("cohere.embed-v4.0")).isFalse();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("cohere.rerank-v4.0-fast")).isFalse();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("content-moderator")).isFalse();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("xai.grok-voice-agent")).isFalse();
    }

    @Test
    void unknownModelsAreAllowedThroughRerankEndpointForFutureOciAdditions() {
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("oracle.semantic-ranker-v1")).isTrue();
    }

    @Test
    void explainsEmbeddingsEndpointMismatchWithoutOciRawError() {
        assertThat(OracleAiModelCapability.embeddingEndpointMismatchMessage("xai.grok-4.3"))
                .contains("聊天/生成模型")
                .contains("/v1/embeddings")
                .contains("cohere.embed-*");
    }

    @Test
    void explainsRerankEndpointMismatchWithoutOciRawError() {
        assertThat(OracleAiModelCapability.rerankEndpointMismatchMessage("xai.grok-4.3"))
                .contains("聊天/生成模型")
                .contains("/v1/rerank")
                .contains("cohere.rerank-*");
    }

    @Test
    void explainsAudioSpeechEndpointMismatchWithoutOciRawError() {
        assertThat(OracleAiModelCapability.audioSpeechEndpointMismatchMessage("xai.grok-4.3"))
                .contains("聊天/生成模型")
                .contains("/v1/audio/speech")
                .contains("xai.grok-tts");
    }

    @Test
    void explainsPendingModelCapabilityWithoutOciRawError() {
        assertThat(OracleAiModelCapability.chatEndpointMismatchMessage("xai.grok-voice-agent"))
                .contains("接口能力尚未确认")
                .contains("暂不开放");
        assertThat(OracleAiModelCapability.audioSpeechEndpointMismatchMessage("xai.grok-voice-agent"))
                .contains("接口能力尚未确认")
                .contains("/v1/audio/speech");
    }
}
