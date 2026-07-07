package com.ociworker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OciGrokBridgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> GROK_MODELS_WITHOUT_REASONING_EFFORT = List.of(
            "xai.grok-4.3",
            "xai.grok-4.20-multi-agent-0309",
            "xai.grok-4.20-multi-agent",
            "xai.grok-4.20-0309-reasoning",
            "xai.grok-4.20-reasoning",
            "xai.grok-4.20-0309-non-reasoning",
            "xai.grok-4.20-non-reasoning",
            "xai.grok-code-fast-1",
            "xai.grok-4-1-fast-reasoning",
            "xai.grok-4-1-fast-non-reasoning",
            "xai.grok-4-fast-reasoning",
            "xai.grok-4-fast-non-reasoning",
            "xai.grok-4",
            "xai.grok-3",
            "xai.grok-3-fast"
    );

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
    void stripsReasoningControlsFromEveryOfficialGrokModelExceptGrok3MiniSeries() throws Exception {
        for (String model : GROK_MODELS_WITHOUT_REASONING_EFFORT) {
            ObjectNode root = requestWithReasoningControls(model);

            OciGrokBridge.normalizeReasoningEffort(root);

            assertThat(root.has("reasoningEffort")).as("camel field for %s", model).isFalse();
            assertThat(root.has("reasoning_effort")).as("snake field for %s", model).isFalse();
            assertThat(root.has("reasoning")).as("reasoning object for %s", model).isFalse();
        }
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

    private static ObjectNode requestWithReasoningControls(String model) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("reasoningEffort", "high");
        root.put("reasoning_effort", "high");
        ObjectNode reasoning = root.putObject("reasoning");
        reasoning.put("effort", "high");
        return root;
    }
}
