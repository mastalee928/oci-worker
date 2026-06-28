package com.ociworker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OciGenerativeOpenAiServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void normalizesUsageOnlyChatCompletionChunk() throws Exception {
        String payload = """
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"google.gemini-2.5-pro","usage":{"prompt_tokens":7,"completion_tokens":11,"total_tokens":18}}
                """;

        String normalized = OciGenerativeOpenAiService.normalizeSseDataPayload(payload, null);

        JsonNode root = MAPPER.readTree(normalized);
        assertThat(root.get("choices")).isNotNull();
        assertThat(root.get("choices").isArray()).isTrue();
        assertThat(root.get("choices").size()).isZero();
        assertThat(root.at("/usage/total_tokens").asInt()).isEqualTo(18);
        assertThat(root.get("model").asText()).isEqualTo("google.gemini-2.5-pro");
    }

    @Test
    void leavesNormalSsePayloadUntouched() {
        String payload = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}";

        String normalized = OciGenerativeOpenAiService.normalizeSseDataPayload(payload, null);

        assertThat(normalized).isEqualTo(payload);
    }

    @Test
    void leavesDoneMarkerUntouched() {
        assertThat(OciGenerativeOpenAiService.normalizeSseDataPayload("[DONE]", null)).isEqualTo("[DONE]");
    }
}
