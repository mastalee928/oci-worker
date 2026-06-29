package com.ociworker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

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

    @Test
    void normalizesDirectChatCompletionsToolHistory() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[
                    {"role":"user","content":"do it"},
                    {"role":"tool","tool_call_id":"ghost","content":"orphan"},
                    {"role":"assistant","content":null,"tool_calls":[
                      {"id":"call_a","type":"function","function":{"name":"write_file","arguments":"{}"}},
                      {"id":"call_b","type":"function","function":{"name":"write_file","arguments":"{\\\"path\\\":\\\"b.txt\\\"}"}}
                    ]},
                    {"role":"system","content":"notice between tool call and result"},
                    {"role":"tool","tool_call_id":"call_b","content":"ok"}
                  ]
                }
                """;

        JsonNode root = MAPPER.readTree(
                OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 128));
        JsonNode messages = root.path("messages");

        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(1).path("tool_calls")).hasSize(1);
        assertThat(messages.get(1).path("tool_calls").get(0).path("id").asText()).isEqualTo("call_b");
        assertThat(messages.get(2).path("role").asText()).isEqualTo("tool");
        assertThat(messages.get(2).path("tool_call_id").asText()).isEqualTo("call_b");
        assertThat(messages.get(3).path("role").asText()).isEqualTo("system");
        assertThat(messages.toString()).doesNotContain("ghost").doesNotContain("call_a");
    }

    @Test
    void convertsResponsesRequestToChatCompletions() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "instructions":"Use tools when needed.",
                  "input":"create a file",
                  "tools":[{"type":"function","name":"write_file","description":"write","parameters":{"type":"object"}}],
                  "tool_choice":{"type":"function","name":"write_file"},
                  "stream":true,
                  "max_output_tokens":512
                }
                """;

        byte[] converted = OciGenerativeOpenAiService.transformResponsesToChatCompletionsJson(payload.getBytes(), 128);

        JsonNode root = MAPPER.readTree(converted);
        assertThat(root.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(root.path("messages").get(1).path("content").asText()).isEqualTo("create a file");
        assertThat(root.path("tools").get(0).path("function").path("name").asText()).isEqualTo("write_file");
        assertThat(root.path("tool_choice").path("function").path("name").asText()).isEqualTo("write_file");
        assertThat(root.path("stream").asBoolean()).isTrue();
        assertThat(root.path("max_tokens").asInt()).isEqualTo(512);
    }

    @Test
    void convertsChatCompletionToolCallsToResponsesJson() throws Exception {
        String payload = """
                {
                  "id":"chatcmpl-1",
                  "object":"chat.completion",
                  "model":"xai.grok-4.3",
                  "choices":[{"index":0,"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_a","type":"function","function":{"name":"write_file","arguments":"{\\\"path\\\":\\\"a.txt\\\"}"}}]},"finish_reason":"tool_calls"}],
                  "usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}
                }
                """;

        String converted = OciGenerativeOpenAiService.convertChatCompletionJsonToResponsesJson(payload, "xai.grok-4.3");

        JsonNode root = MAPPER.readTree(converted);
        assertThat(root.path("object").asText()).isEqualTo("response");
        assertThat(root.path("created_at").isNumber()).isTrue();
        assertThat(root.path("error").isNull()).isTrue();
        assertThat(root.path("incomplete_details").isNull()).isTrue();
        assertThat(root.path("tools").isArray()).isTrue();
        assertThat(root.path("parallel_tool_calls").asBoolean()).isTrue();
        assertThat(root.path("metadata").isObject()).isTrue();
        assertThat(root.path("output").get(0).path("type").asText()).isEqualTo("function_call");
        assertThat(root.path("output").get(0).path("call_id").asText()).isEqualTo("call_a");
        assertThat(root.path("output").get(0).path("arguments").asText()).contains("a.txt");
        assertThat(root.path("usage").path("total_tokens").asInt()).isEqualTo(3);
    }

    @Test
    void convertsStreamingToolCallLifecycleToResponsesEvents() throws Exception {
        OciGenerativeOpenAiService.ResponsesBridgeStreamState state =
                new OciGenerativeOpenAiService.ResponsesBridgeStreamState("xai.grok-4.3");
        String chunk = """
                {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"xai.grok-4.3","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"write_file","arguments":"{\\\"path\\\":\\\"a.txt\\\"}"}}]}}]}
                """;

        String sse = OciGenerativeOpenAiService.chatChunkToResponsesSse(MAPPER.readTree(chunk), state)
                + OciGenerativeOpenAiService.finalizeResponsesBridgeStream(state);

        assertThat(sse).contains("event: response.created");
        assertThat(sse).contains("event: response.output_item.added");
        assertThat(sse).contains("event: response.completed");
        assertThat(sse).contains("\"type\":\"response.output_item.added\"");
        assertThat(sse).contains("\"type\":\"response.function_call_arguments.delta\"");
        assertThat(sse).contains("\"type\":\"response.function_call_arguments.done\"");
        assertThat(sse).contains("\"type\":\"response.output_item.done\"");
        assertThat(sse).contains("\"type\":\"response.completed\"");
        assertThat(sse).contains("\"created_at\":");
        assertThat(sse).contains("\"error\":null");
        assertThat(sse).contains("\"parallel_tool_calls\":true");
        assertThat(sse).contains("\"metadata\":{}");
        assertThat(sse).contains("\"call_id\":\"call_a\"");
        assertThat(sse).contains("\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"");
        assertThat(sse).contains("\"sequence_number\":0");
        assertThat(sse).contains("\"sequence_number\":1");
        assertThat(sse).contains("\"sequence_number\":2");
    }

    @Test
    void convertsSplitAndParallelToolCallsToResponsesEvents() throws Exception {
        OciGenerativeOpenAiService.ResponsesBridgeStreamState state =
                new OciGenerativeOpenAiService.ResponsesBridgeStreamState("xai.grok-4.3");
        String first = """
                {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"xai.grok-4.3","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"write_file","arguments":"{\\\"path\\\":"}},{"index":1,"id":"call_b","type":"function","function":{"name":"write_file","arguments":"{\\\"path\\\":\\\"b.txt\\\"}"}}]}}]}
                """;
        String second = """
                {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"xai.grok-4.3","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\\"a.txt\\\"}"}}]},"finish_reason":"tool_calls"}]}
                """;

        String sse = OciGenerativeOpenAiService.chatChunkToResponsesSse(MAPPER.readTree(first), state)
                + OciGenerativeOpenAiService.chatChunkToResponsesSse(MAPPER.readTree(second), state)
                + OciGenerativeOpenAiService.finalizeResponsesBridgeStream(state);

        assertThat(sse).contains("\"call_id\":\"call_a\"");
        assertThat(sse).contains("\"call_id\":\"call_b\"");
        assertThat(sse).contains("\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"");
        assertThat(sse).contains("\"arguments\":\"{\\\"path\\\":\\\"b.txt\\\"}\"");
        assertThat(countOccurrences(sse, "\"type\":\"response.function_call_arguments.done\"")).isEqualTo(2);
        assertThat(countOccurrences(sse, "\"type\":\"response.output_item.done\"")).isEqualTo(2);
    }

    @Test
    void convertsFunctionCallOutputBackToChatToolMessage() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "input":[
                    {"type":"function_call","call_id":"call_a","name":"write_file","arguments":"{\\\"path\\\":\\\"a.txt\\\"}"},
                    {"type":"function_call_output","call_id":"call_a","output":"ok"}
                  ],
                  "tools":[{"type":"function","name":"write_file","parameters":{"type":"object"}}]
                }
                """;

        JsonNode root = MAPPER.readTree(
                OciGenerativeOpenAiService.transformResponsesToChatCompletionsJson(payload.getBytes(), 128));

        assertThat(root.path("messages").get(0).path("role").asText()).isEqualTo("assistant");
        assertThat(root.path("messages").get(0).path("tool_calls").get(0).path("id").asText()).isEqualTo("call_a");
        assertThat(root.path("messages").get(1).path("role").asText()).isEqualTo("tool");
        assertThat(root.path("messages").get(1).path("tool_call_id").asText()).isEqualTo("call_a");
        assertThat(root.path("messages").get(1).path("content").asText()).isEqualTo("ok");
    }

    @Test
    void normalizesResponsesToolHistoryForChatCompletions() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "input":[
                    {"type":"function_call","call_id":"call_a","name":"write_file","arguments":"{\\\"path\\\":\\\"a.txt\\\"}"},
                    {"type":"message","role":"user","content":"continue after tool"},
                    {"type":"function_call_output","call_id":"call_a","output":"ok"}
                  ]
                }
                """;

        JsonNode root = MAPPER.readTree(
                OciGenerativeOpenAiService.transformResponsesToChatCompletionsJson(payload.getBytes(), 128));
        JsonNode messages = root.path("messages");

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(0).path("tool_calls").get(0).path("id").asText()).isEqualTo("call_a");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("tool");
        assertThat(messages.get(1).path("tool_call_id").asText()).isEqualTo("call_a");
        assertThat(messages.get(2).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(2).path("content").asText()).isEqualTo("continue after tool");
    }

    @Test
    void dropsOrphanAndUnansweredResponsesToolHistory() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "input":[
                    {"type":"function_call_output","call_id":"ghost","output":"orphan"},
                    {"type":"function_call","call_id":"call_a","name":"write_file","arguments":"{}"},
                    {"type":"function_call","call_id":"call_b","name":"write_file","arguments":"{\\\"path\\\":\\\"b.txt\\\"}"},
                    {"type":"function_call_output","call_id":"call_b","output":"ok"}
                  ]
                }
                """;

        JsonNode root = MAPPER.readTree(
                OciGenerativeOpenAiService.transformResponsesToChatCompletionsJson(payload.getBytes(), 128));
        JsonNode messages = root.path("messages");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(0).path("tool_calls").get(0).path("id").asText()).isEqualTo("call_b");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("tool");
        assertThat(messages.get(1).path("tool_call_id").asText()).isEqualTo("call_b");
        assertThat(messages.toString()).doesNotContain("ghost").doesNotContain("call_a");
    }

    @Test
    void convertsCustomToolCallInputAsChatToolCall() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "input":[
                    {"type":"custom_tool_call","call_id":"call_custom","name":"apply_patch","input":"*** Begin Patch"},
                    {"type":"custom_tool_call_output","call_id":"call_custom","output":"done"}
                  ]
                }
                """;

        JsonNode root = MAPPER.readTree(
                OciGenerativeOpenAiService.transformResponsesToChatCompletionsJson(payload.getBytes(), 128));
        JsonNode messages = root.path("messages");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).path("tool_calls").get(0).path("function").path("name").asText())
                .isEqualTo("apply_patch");
        assertThat(messages.get(0).path("tool_calls").get(0).path("function").path("arguments").asText())
                .isEqualTo("*** Begin Patch");
        assertThat(messages.get(1).path("tool_call_id").asText()).isEqualTo("call_custom");
    }

    @Test
    void preservesJsonToolArgumentsAndOutputWhenConvertingResponsesToChat() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "input":[
                    {"type":"function_call","call_id":"call_json","name":"write_file","arguments":{"path":"a.txt","overwrite":true}},
                    {"type":"function_call_output","call_id":"call_json","output":{"status":"ok","bytes":12}}
                  ]
                }
                """;

        JsonNode root = MAPPER.readTree(
                OciGenerativeOpenAiService.transformResponsesToChatCompletionsJson(payload.getBytes(), 128));
        JsonNode messages = root.path("messages");

        assertThat(messages.get(0).path("tool_calls").get(0).path("function").path("arguments").asText())
                .isEqualTo("{\"path\":\"a.txt\",\"overwrite\":true}");
        assertThat(messages.get(1).path("content").asText())
                .isEqualTo("{\"status\":\"ok\",\"bytes\":12}");
    }

    @Test
    void countsOnlyNewStreamingToolCalls() throws Exception {
        JsonNode firstChunkCalls = MAPPER.readTree("""
                [
                  {"index":0,"id":"call_a","type":"function","function":{"name":"write_file","arguments":"{\\\"path\\\":"}},
                  {"index":1,"id":"call_b","type":"function","function":{"name":"write_file","arguments":"{\\\"path\\\":\\\"b.txt\\\"}"}}
                ]
                """);
        JsonNode argumentOnlyChunk = MAPPER.readTree("""
                [
                  {"index":0,"function":{"arguments":"\\\"a.txt\\\"}"}}
                ]
                """);

        assertThat(OciGenerativeOpenAiService.countNewStreamingToolCalls(firstChunkCalls)).isEqualTo(2);
        assertThat(OciGenerativeOpenAiService.countNewStreamingToolCalls(argumentOnlyChunk)).isZero();
    }

    @Test
    void deduplicatesStreamingToolDiagnosticsPerRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        JsonNode firstChunkCalls = MAPPER.readTree("""
                [
                  {"index":0,"id":"call_a","type":"function","function":{"name":"write_file","arguments":"{\\\"path\\\":"}}
                ]
                """);
        JsonNode repeatedMetadataChunk = MAPPER.readTree("""
                [
                  {"index":0,"id":"call_a","type":"function","function":{"name":"write_file","arguments":"\\\"a.txt\\\"}"}}
                ]
                """);

        assertThat(OciGenerativeOpenAiService.countNewStreamingToolCalls(firstChunkCalls, request)).isEqualTo(1);
        assertThat(OciGenerativeOpenAiService.countNewStreamingToolCalls(repeatedMetadataChunk, request)).isZero();
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int from = 0;
        while (value != null && needle != null && !needle.isEmpty()) {
            int idx = value.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
        return count;
    }
}
