package com.ociworker.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OracleAiGrokModelCatalogTest {

    private static final List<GrokExpectation> OFFICIAL_XAI_MODELS = List.of(
            new GrokExpectation("xai.grok-tts", OracleAiModelCapability.AUDIO, 0L, 0L, false, false),
            new GrokExpectation("xai.grok-4.3", OracleAiModelCapability.CHAT, 1_000_000L, 200_000L, false, false),
            new GrokExpectation("xai.grok-4.20-multi-agent-0309", OracleAiModelCapability.MULTI_AGENT, 1_000_000L, 200_000L, false, false),
            new GrokExpectation("xai.grok-4.20-multi-agent", OracleAiModelCapability.MULTI_AGENT, 1_000_000L, 200_000L, false, false),
            new GrokExpectation("xai.grok-4.20-0309-reasoning", OracleAiModelCapability.CHAT, 1_000_000L, 200_000L, false, false),
            new GrokExpectation("xai.grok-4.20-reasoning", OracleAiModelCapability.CHAT, 1_000_000L, 200_000L, false, false),
            new GrokExpectation("xai.grok-4.20-0309-non-reasoning", OracleAiModelCapability.CHAT, 1_000_000L, 200_000L, false, false),
            new GrokExpectation("xai.grok-4.20-non-reasoning", OracleAiModelCapability.CHAT, 1_000_000L, 200_000L, false, false),
            new GrokExpectation("xai.grok-code-fast-1", OracleAiModelCapability.CHAT, 256_000L, 200_000L, true, false),
            new GrokExpectation("xai.grok-4-1-fast-reasoning", OracleAiModelCapability.CHAT, 2_000_000L, 200_000L, true, false),
            new GrokExpectation("xai.grok-4-1-fast-non-reasoning", OracleAiModelCapability.CHAT, 2_000_000L, 200_000L, true, false),
            new GrokExpectation("xai.grok-4-fast-reasoning", OracleAiModelCapability.CHAT, 2_000_000L, 200_000L, true, false),
            new GrokExpectation("xai.grok-4-fast-non-reasoning", OracleAiModelCapability.CHAT, 2_000_000L, 200_000L, true, false),
            new GrokExpectation("xai.grok-4", OracleAiModelCapability.CHAT, 128_000L, 200_000L, true, false),
            new GrokExpectation("xai.grok-3", OracleAiModelCapability.CHAT, 131_072L, 100_000L, true, false),
            new GrokExpectation("xai.grok-3-fast", OracleAiModelCapability.CHAT, 131_072L, 100_000L, true, false),
            new GrokExpectation("xai.grok-3-mini", OracleAiModelCapability.CHAT, 131_072L, 100_000L, true, true),
            new GrokExpectation("xai.grok-3-mini-fast", OracleAiModelCapability.CHAT, 131_072L, 100_000L, true, true)
    );

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
    void coversOfficialOracleXaiModelIndex() {
        for (GrokExpectation expectation : OFFICIAL_XAI_MODELS) {
            assertThat(OracleAiGrokModelCatalog.isOfficialModel(expectation.id()))
                    .as("official model %s", expectation.id())
                    .isTrue();
            assertThat(OracleAiGrokModelCatalog.classify(expectation.id()))
                    .as("capability for %s", expectation.id())
                    .isEqualTo(expectation.capability());
            assertThat(OracleAiGrokModelCatalog.documentedContextLimit(expectation.id()))
                    .as("context limit for %s", expectation.id())
                    .isEqualTo(expectation.contextLimit());
            assertThat(OracleAiGrokModelCatalog.documentedTpmLimit(expectation.id()))
                    .as("TPM limit for %s", expectation.id())
                    .isEqualTo(expectation.tpmLimit());
            assertThat(OracleAiGrokModelCatalog.isDeprecated(expectation.id()))
                    .as("deprecated flag for %s", expectation.id())
                    .isEqualTo(expectation.deprecated());
            assertThat(OracleAiGrokModelCatalog.supportsReasoningEffort(expectation.id()))
                    .as("reasoning_effort support for %s", expectation.id())
                    .isEqualTo(expectation.supportsReasoningEffort());
        }
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

    private record GrokExpectation(
            String id,
            String capability,
            long contextLimit,
            long tpmLimit,
            boolean deprecated,
            boolean supportsReasoningEffort) {
    }
}
