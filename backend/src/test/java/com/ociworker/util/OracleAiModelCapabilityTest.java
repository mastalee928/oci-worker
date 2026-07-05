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
}
