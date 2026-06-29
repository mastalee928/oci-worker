package com.ociworker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
    void leavesToolCallSsePayloadUntouched() {
        String payload = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"write_file\",\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}]},\"finish_reason\":null}]}";

        String normalized = OciGenerativeOpenAiService.normalizeSseDataPayload(payload, null);

        assertThat(normalized).isEqualTo(payload);
    }

    @Test
    void addsAssistantRoleToToolCallSsePayload() throws Exception {
        String payload = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"write_file\",\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}]},\"finish_reason\":null}]}";

        String normalized = OciGenerativeOpenAiService.normalizeSseDataPayload(payload, null);

        JsonNode root = MAPPER.readTree(normalized);
        assertThat(root.at("/choices/0/delta/role").asText()).isEqualTo("assistant");
        assertThat(root.at("/choices/0/delta/tool_calls/0/function/name").asText()).isEqualTo("write_file");
    }

    @Test
    void leavesDoneMarkerUntouched() {
        assertThat(OciGenerativeOpenAiService.normalizeSseDataPayload("[DONE]", null)).isEqualTo("[DONE]");
    }

    @Test
    void convertsResponsesStyleToolsForChatCompletions() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[{"role":"user","content":"create file"}],
                  "tools":[{"type":"function","name":"write_file","description":"write a file","parameters":{"type":"object"}}],
                  "tool_choice":{"type":"function","name":"write_file"}
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 128);

        JsonNode root = MAPPER.readTree(normalized);
        ArrayNode tools = (ArrayNode) root.get("tools");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).path("type").asText()).isEqualTo("function");
        assertThat(tools.get(0).path("function").path("name").asText()).isEqualTo("write_file");
        assertThat(tools.get(0).path("function").path("parameters").path("type").asText()).isEqualTo("object");
        assertThat(root.path("tool_choice").path("function").path("name").asText()).isEqualTo("write_file");
    }

    @Test
    void keepsChatCompletionsToolSchemaUntouched() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[{"role":"user","content":"create file"}],
                  "tools":[{"type":"function","function":{"name":"write_file","parameters":{"type":"object"}}}],
                  "tool_choice":"auto"
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 128);

        JsonNode root = MAPPER.readTree(normalized);
        assertThat(root.path("tools").get(0).path("function").path("name").asText()).isEqualTo("write_file");
        assertThat(root.path("tool_choice").asText()).isEqualTo("auto");
    }
}
