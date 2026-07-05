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
    void keepsUnconfirmedVoiceAgentAsChatUntilOciCapabilityIsKnown() {
        assertThat(OracleAiModelCapability.classify("xai.grok-voice-agent"))
                .isEqualTo(OracleAiModelCapability.CHAT);
    }

    @Test
    void onlyChatAndMultiAgentAreAcceptedByChatStyleEndpoints() {
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("xai.grok-4.3")).isTrue();
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("xai.grok-4.20-multi-agent")).isTrue();
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("cohere.embed-v4.0")).isFalse();
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("cohere.rerank-v4.0-pro")).isFalse();
        assertThat(OracleAiModelCapability.isChatEndpointCompatible("xai.grok-tts")).isFalse();
    }

    @Test
    void knownNonEmbeddingModelsAreRejectedByEmbeddingsEndpoint() {
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("cohere.embed-v4.0")).isTrue();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("cohere.embed-english-v3.0")).isTrue();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("xai.grok-4.3")).isFalse();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("xai.grok-4.20-multi-agent")).isFalse();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("cohere.rerank-v4.0-fast")).isFalse();
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("xai.grok-tts")).isFalse();
    }

    @Test
    void unknownModelsAreAllowedThroughEmbeddingsEndpointForFutureOciAdditions() {
        assertThat(OracleAiModelCapability.isEmbeddingEndpointCompatible("oracle.vector-v1")).isTrue();
    }

    @Test
    void knownNonRerankModelsAreRejectedByRerankEndpoint() {
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("cohere.rerank-v4.0-fast")).isTrue();
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("xai.grok-4.3")).isFalse();
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("xai.grok-4.20-multi-agent")).isFalse();
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("cohere.embed-v4.0")).isFalse();
        assertThat(OracleAiModelCapability.isRerankEndpointCompatible("xai.grok-tts")).isFalse();
    }

    @Test
    void audioSpeechEndpointAcceptsAudioModelsAndRejectsKnownWrongFamilies() {
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("xai.grok-tts")).isTrue();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("oracle.tts-v1")).isTrue();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("oracle.future-speech-v1")).isTrue();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("xai.grok-4.3")).isFalse();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("xai.grok-4.20-multi-agent")).isFalse();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("cohere.embed-v4.0")).isFalse();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("cohere.rerank-v4.0-fast")).isFalse();
        assertThat(OracleAiModelCapability.isAudioSpeechEndpointCompatible("content-moderator")).isFalse();
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
}
