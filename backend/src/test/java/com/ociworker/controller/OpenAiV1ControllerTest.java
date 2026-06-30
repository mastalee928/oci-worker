package com.ociworker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiV1ControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void convertsAnthropicMessagesRequestToChatCompletions() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "system":"Use tools when needed.",
                  "messages":[
                    {"role":"user","content":[{"type":"text","text":"create a file"}]},
                    {"role":"assistant","content":[{"type":"tool_use","id":"toolu_a","name":"write_file","input":{"path":"a.txt","content":"null"}}]},
                    {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_a","content":"ok"}]}
                  ],
                  "tools":[{"name":"write_file","description":"write a file","input_schema":{"type":"object"}}],
                  "max_tokens":512,
                  "stream":true
                }
                """;

        JsonNode root = MAPPER.readTree(OpenAiV1Controller.transformAnthropicMessagesToChatCompletionsJson(
                payload.getBytes()));

        assertThat(root.path("model").asText()).isEqualTo("xai.grok-4.3");
        assertThat(root.path("stream").asBoolean()).isFalse();
        assertThat(root.path("max_tokens").asInt()).isEqualTo(512);
        assertThat(root.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(root.path("messages").get(1).path("content").asText()).isEqualTo("create a file");
        assertThat(root.path("messages").get(2).path("tool_calls").get(0).path("id").asText()).isEqualTo("toolu_a");
        assertThat(root.path("messages").get(2).path("tool_calls").get(0).path("function").path("name").asText())
                .isEqualTo("write_file");
        assertThat(root.path("messages").get(2).path("tool_calls").get(0).path("function").path("arguments").asText())
                .isEqualTo("{\"path\":\"a.txt\",\"content\":\"null\"}");
        assertThat(root.path("messages").get(3).path("role").asText()).isEqualTo("tool");
        assertThat(root.path("messages").get(3).path("tool_call_id").asText()).isEqualTo("toolu_a");
        assertThat(root.path("tools").get(0).path("function").path("parameters").path("type").asText())
                .isEqualTo("object");
        assertThat(root.path("tool_choice").asText()).isEqualTo("auto");
    }

    @Test
    void convertsChatCompletionsToolCallsToAnthropicMessage() throws Exception {
        String payload = """
                {
                  "id":"chatcmpl-1",
                  "model":"xai.grok-4.3",
                  "choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_a","type":"function","function":{"name":"write_file","arguments":"{\\\"path\\\":\\\"a.txt\\\"}"}}]},"finish_reason":"tool_calls"}],
                  "usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}
                }
                """;

        JsonNode root = OpenAiV1Controller.chatCompletionToAnthropicMessage(payload, "xai.grok-4.3");

        assertThat(root.path("id").asText()).isEqualTo("chatcmpl-1");
        assertThat(root.path("type").asText()).isEqualTo("message");
        assertThat(root.path("role").asText()).isEqualTo("assistant");
        assertThat(root.path("stop_reason").asText()).isEqualTo("tool_use");
        assertThat(root.path("content").get(0).path("type").asText()).isEqualTo("tool_use");
        assertThat(root.path("content").get(0).path("id").asText()).isEqualTo("call_a");
        assertThat(root.path("content").get(0).path("name").asText()).isEqualTo("write_file");
        assertThat(root.path("content").get(0).path("input").path("path").asText()).isEqualTo("a.txt");
        assertThat(root.path("usage").path("input_tokens").asInt()).isEqualTo(3);
        assertThat(root.path("usage").path("output_tokens").asInt()).isEqualTo(5);
    }
}
