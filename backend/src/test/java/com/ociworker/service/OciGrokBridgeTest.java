package com.ociworker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OciGrokBridgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void identifiesGrok3MiniReasoningEffortModels() {
        assertThat(OciGrokBridge.isGrok3MiniReasoningEffortModel("xai.grok-3-mini")).isTrue();
        assertThat(OciGrokBridge.isGrok3MiniReasoningEffortModel("xai.grok-3-mini-fast")).isTrue();
        assertThat(OciGrokBridge.isGrok3MiniReasoningEffortModel("xai.grok-4.3")).isFalse();
    }

    @Test
    void normalizesOfficialGrok3MiniReasoningEffort() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree("""
                {
                  "model":"xai.grok-3-mini-fast",
                  "reasoningEffort":"HIGH",
                  "reasoning":{"effort":"low"}
                }
                """);

        OciGrokBridge.normalizeReasoningEffort(root);

        assertThat(root.path("reasoning_effort").asText()).isEqualTo("high");
        assertThat(root.has("reasoningEffort")).isFalse();
        assertThat(root.has("reasoning")).isFalse();
    }

    @Test
    void stripsUnsupportedGrok4ReasoningControls() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree("""
                {
                  "model":"xai.grok-4.20-0309-reasoning",
                  "reasoningEffort":"high",
                  "reasoning_effort":"high",
                  "reasoning":{"effort":"high"}
                }
                """);

        OciGrokBridge.normalizeReasoningEffort(root);

        assertThat(root.has("reasoningEffort")).isFalse();
        assertThat(root.has("reasoning_effort")).isFalse();
        assertThat(root.has("reasoning")).isFalse();
    }

    @Test
    void stripsInvalidGrok3MiniReasoningEffort() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree("""
                {
                  "model":"xai.grok-3-mini",
                  "reasoning_effort":"medium"
                }
                """);

        OciGrokBridge.normalizeReasoningEffort(root);

        assertThat(root.has("reasoning_effort")).isFalse();
    }
}
