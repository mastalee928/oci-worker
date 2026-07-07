package com.ociworker.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OracleAiGrokModelCatalogTest {

    @Test
    void classifiesGrokModelFamilies() {
        assertThat(OracleAiGrokModelCatalog.classify("xai.grok-4.3"))
                .isEqualTo(OracleAiModelCapability.CHAT);
        assertThat(OracleAiGrokModelCatalog.classify("xai.grok-4.20-0309-reasoning"))
                .isEqualTo(OracleAiModelCapability.CHAT);
        assertThat(OracleAiGrokModelCatalog.classify("xai.grok-4.20-multi-agent"))
                .isEqualTo(OracleAiModelCapability.MULTI_AGENT);
        assertThat(OracleAiGrokModelCatalog.classify("xai.grok-tts"))
                .isEqualTo(OracleAiModelCapability.AUDIO);
        assertThat(OracleAiGrokModelCatalog.classify("xai.grok-voice-agent"))
                .isEqualTo(OracleAiModelCapability.PENDING);
        assertThat(OracleAiGrokModelCatalog.classify("google.gemini-2.5-pro")).isNull();
    }

    @Test
    void returnsDocumentedGrokContextLimits() {
        assertThat(OracleAiGrokModelCatalog.documentedContextLimit("xai.grok-4.3")).isEqualTo(1_000_000L);
        assertThat(OracleAiGrokModelCatalog.documentedContextLimit("xai.grok-4.20-0309-reasoning")).isEqualTo(1_000_000L);
        assertThat(OracleAiGrokModelCatalog.documentedContextLimit("xai.grok-4-fast-reasoning")).isEqualTo(2_000_000L);
        assertThat(OracleAiGrokModelCatalog.documentedContextLimit("xai.grok-4-1-fast-reasoning")).isEqualTo(2_000_000L);
        assertThat(OracleAiGrokModelCatalog.documentedContextLimit("xai.grok-code-fast-1")).isEqualTo(256_000L);
        assertThat(OracleAiGrokModelCatalog.documentedContextLimit("xai.grok-4")).isEqualTo(128_000L);
        assertThat(OracleAiGrokModelCatalog.documentedContextLimit("xai.grok-3-mini-fast")).isEqualTo(131_072L);
        assertThat(OracleAiGrokModelCatalog.documentedContextLimit("google.gemini-2.5-pro")).isZero();
    }

    @Test
    void returnsDocumentedGrokTpmLimits() {
        assertThat(OracleAiGrokModelCatalog.documentedTpmLimit("xai.grok-4.3")).isEqualTo(200_000L);
        assertThat(OracleAiGrokModelCatalog.documentedTpmLimit("xai.grok-4.20-multi-agent")).isEqualTo(200_000L);
        assertThat(OracleAiGrokModelCatalog.documentedTpmLimit("xai.grok-4-fast-reasoning")).isEqualTo(200_000L);
        assertThat(OracleAiGrokModelCatalog.documentedTpmLimit("xai.grok-code-fast-1")).isEqualTo(200_000L);
        assertThat(OracleAiGrokModelCatalog.documentedTpmLimit("xai.grok-3-mini")).isEqualTo(100_000L);
        assertThat(OracleAiGrokModelCatalog.documentedTpmLimit("google.gemini-2.5-pro")).isZero();
    }

    @Test
    void identifiesGrokModelsOnlyByOfficialPrefix() {
        assertThat(OracleAiGrokModelCatalog.isGrokModel("xai.grok-4.3")).isTrue();
        assertThat(OracleAiGrokModelCatalog.isGrokModel("grok-tts")).isFalse();
        assertThat(OracleAiGrokModelCatalog.isGrokModel("openai.gpt-oss-120b")).isFalse();
    }
}
