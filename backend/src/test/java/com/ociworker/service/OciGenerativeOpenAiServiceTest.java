package com.ociworker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oracle.bmc.generativeaiinference.model.AssistantMessage;
import com.oracle.bmc.generativeaiinference.model.AudioContent;
import com.oracle.bmc.generativeaiinference.model.BaseChatRequest;
import com.oracle.bmc.generativeaiinference.model.ChatChoice;
import com.oracle.bmc.generativeaiinference.model.ChatContent;
import com.oracle.bmc.generativeaiinference.model.ChatResult;
import com.oracle.bmc.generativeaiinference.model.CohereAssistantMessageV2;
import com.oracle.bmc.generativeaiinference.model.CohereChatRequestV2;
import com.oracle.bmc.generativeaiinference.model.CohereChatResponseV2;
import com.oracle.bmc.generativeaiinference.model.CohereImageContentV2;
import com.oracle.bmc.generativeaiinference.model.CohereTextContentV2;
import com.oracle.bmc.generativeaiinference.model.CohereThinkingContentV2;
import com.oracle.bmc.generativeaiinference.model.CohereThinkingV2;
import com.oracle.bmc.generativeaiinference.model.CohereToolCallV2;
import com.oracle.bmc.generativeaiinference.model.DeveloperMessage;
import com.oracle.bmc.generativeaiinference.model.DocumentContent;
import com.oracle.bmc.generativeaiinference.model.FunctionCall;
import com.oracle.bmc.generativeaiinference.model.FunctionDefinition;
import com.oracle.bmc.generativeaiinference.model.GenericChatRequest;
import com.oracle.bmc.generativeaiinference.model.GenericChatResponse;
import com.oracle.bmc.generativeaiinference.model.ImageContent;
import com.oracle.bmc.generativeaiinference.model.TextContent;
import com.oracle.bmc.generativeaiinference.model.ToolChoiceAuto;
import com.oracle.bmc.generativeaiinference.model.ToolMessage;
import com.oracle.bmc.generativeaiinference.model.Usage;
import com.oracle.bmc.generativeaiinference.model.UserMessage;
import com.oracle.bmc.generativeaiinference.model.VideoContent;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void stripsReasoningControlsUnsupportedByOciChatCompletions() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.20-0309-reasoning",
                  "messages":[{"role":"user","content":"hello"}],
                  "reasoningEffort":"high",
                  "reasoning_effort":"high",
                  "reasoning":{"effort":"high"},
                  "tools":[{"type":"function","function":{"name":"write_file","parameters":{"type":"object"}}}],
                  "tool_choice":"auto",
                  "stream":true
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 128);

        JsonNode root = MAPPER.readTree(normalized);
        assertThat(root.has("reasoningEffort")).isFalse();
        assertThat(root.has("reasoning_effort")).isFalse();
        assertThat(root.has("reasoning")).isFalse();
        assertThat(root.path("tools").get(0).path("function").path("name").asText()).isEqualTo("write_file");
        assertThat(root.path("tool_choice").asText()).isEqualTo("auto");
        assertThat(root.path("stream").asBoolean()).isTrue();
        assertThat(root.path("model").asText()).isEqualTo("xai.grok-4.20-0309-reasoning");
    }

    @Test
    void convertsMaxCompletionTokensToMaxTokensForOciChatCompletions() throws Exception {
        String payload = """
                {
                  "model":"openai.gpt-oss-120b",
                  "messages":[{"role":"user","content":"hello"}],
                  "max_completion_tokens":32
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 128);

        JsonNode root = MAPPER.readTree(normalized);
        assertThat(root.path("max_tokens").asInt()).isEqualTo(32);
        assertThat(root.has("max_completion_tokens")).isFalse();
    }

    @Test
    void capsMetaLlamaOnDemandOutputTokensAndKeepsSupportedParameters() throws Exception {
        String payload = """
                {
                  "model":"meta.llama-4-maverick-17b-128e-instruct-fp8",
                  "messages":[{"role":"user","content":"hello"}],
                  "max_tokens":12000,
                  "top_k":40,
                  "frequency_penalty":0.2,
                  "presence_penalty":0.1,
                  "seed":1234
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 2048);

        JsonNode root = MAPPER.readTree(normalized);
        assertThat(root.path("max_tokens").asInt()).isEqualTo(4000);
        assertThat(root.path("top_k").asInt()).isEqualTo(40);
        assertThat(root.path("frequency_penalty").asDouble()).isEqualTo(0.2D);
        assertThat(root.path("presence_penalty").asDouble()).isEqualTo(0.1D);
        assertThat(root.path("seed").asInt()).isEqualTo(1234);
    }

    @Test
    void capsLlama4ScoutOnDemandOutputTokens() throws Exception {
        String payload = """
                {
                  "model":"meta.llama-4-scout-17b-16e-instruct",
                  "messages":[{"role":"user","content":"hello"}],
                  "max_tokens":12000
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 2048);

        JsonNode root = MAPPER.readTree(normalized);
        assertThat(root.path("max_tokens").asInt()).isEqualTo(4000);
    }

    @Test
    void capsLlama33StandardOnDemandOutputTokens() throws Exception {
        String payload = """
                {
                  "model":"meta.llama-3.3-70b-instruct",
                  "messages":[{"role":"user","content":"hello"}],
                  "max_tokens":12000,
                  "top_k":40,
                  "frequency_penalty":-0.2,
                  "presence_penalty":0.1,
                  "seed":1234
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 2048);

        JsonNode root = MAPPER.readTree(normalized);
        assertThat(root.path("max_tokens").asInt()).isEqualTo(4000);
        assertThat(root.path("top_k").asInt()).isEqualTo(40);
        assertThat(root.path("frequency_penalty").asDouble()).isEqualTo(-0.2D);
        assertThat(root.path("presence_penalty").asDouble()).isEqualTo(0.1D);
        assertThat(root.path("seed").asInt()).isEqualTo(1234);
    }

    @Test
    void capsLlama33Fp8DynamicOnDemandOutputTokens() throws Exception {
        String payload = """
                {
                  "model":"meta.llama-3.3-70b-instruct-fp8-dynamic",
                  "messages":[{"role":"user","content":"hello"}],
                  "max_tokens":12000
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 2048);

        JsonNode root = MAPPER.readTree(normalized);
        assertThat(root.path("max_tokens").asInt()).isEqualTo(4000);
    }

    @Test
    void capsCohereCommandAReasoningOnDemandOutputTokens() throws Exception {
        String payload = """
                {
                  "model":"cohere.command-a-reasoning",
                  "messages":[{"role":"user","content":"hello"}],
                  "max_tokens":12000,
                  "reasoning_effort":"high"
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 2048);

        JsonNode root = MAPPER.readTree(normalized);
        assertThat(root.path("max_tokens").asInt()).isEqualTo(4000);
        assertThat(root.has("reasoning_effort")).isFalse();
    }

    @Test
    void convertsOpenAiChatPayloadToCohereCommandAReasoningV2Request() throws Exception {
        ObjectNode input = (ObjectNode) MAPPER.readTree("""
                {
                  "model":"cohere.command-a-reasoning",
                  "messages":[
                    {"role":"system","content":"你是企业助手"},
                    {"role":"user","content":[
                      {"type":"text","text":"看图回答"},
                      {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA","detail":"high"}}
                    ]}
                  ],
                  "max_tokens":12000,
                  "temperature":0.2,
                  "top_k":40,
                  "top_p":0.9,
                  "frequency_penalty":0.1,
                  "presence_penalty":0.2,
                  "seed":1234,
                  "thinking":{"type":"enabled","token_budget":31000},
                  "safety_mode":"strict",
                  "tool_choice":"required",
                  "tools":[{"type":"function","function":{"name":"lookup","description":"查资料","parameters":{"type":"object","properties":{"q":{"type":"string"}}}}}]
                }
                """);

        CohereChatRequestV2 request = OciCohereChatV2Bridge.toNativeChatRequest(input);

        assertThat(request.getMaxTokens()).isEqualTo(4000);
        assertThat(request.getTemperature()).isEqualTo(0.2D);
        assertThat(request.getTopK()).isEqualTo(40);
        assertThat(request.getTopP()).isEqualTo(0.9D);
        assertThat(request.getFrequencyPenalty()).isEqualTo(0.1D);
        assertThat(request.getPresencePenalty()).isEqualTo(0.2D);
        assertThat(request.getSeed()).isEqualTo(1234);
        assertThat(request.getThinking().getType()).isEqualTo(CohereThinkingV2.Type.Enabled);
        assertThat(request.getThinking().getTokenBudget()).isEqualTo(31000);
        assertThat(request.getSafetyMode()).isEqualTo(CohereChatRequestV2.SafetyMode.Strict);
        assertThat(request.getToolsChoice()).isEqualTo(CohereChatRequestV2.ToolsChoice.Required);
        assertThat(request.getTools()).hasSize(1);
        assertThat(request.getMessages()).hasSize(2);
        assertThat(request.getMessages().get(1).getContent())
                .anyMatch(CohereTextContentV2.class::isInstance)
                .anyMatch(CohereImageContentV2.class::isInstance);
    }

    @Test
    void convertsCohereCommandAReasoningV2ResponseToOpenAiChatCompletion() throws Exception {
        ChatResult result = ChatResult.builder()
                .modelId("cohere.command-a-reasoning")
                .chatResponse(CohereChatResponseV2.builder()
                        .message(CohereAssistantMessageV2.builder()
                                .content(List.of(
                                        CohereThinkingContentV2.builder().thinking("先分析问题。").build(),
                                        CohereTextContentV2.builder().text("需要调用工具。").build()))
                                .toolCalls(List.of(CohereToolCallV2.builder()
                                        .id("call_a")
                                        .type(CohereToolCallV2.Type.Function)
                                        .function(Map.of("name", "write_file", "arguments", "{\"path\":\"a.txt\"}"))
                                        .build()))
                                .build())
                        .finishReason(CohereChatResponseV2.FinishReason.ToolCall)
                        .usage(Usage.builder().promptTokens(10).completionTokens(5).totalTokens(15).build())
                        .build())
                .build();

        JsonNode root = MAPPER.readTree(OciCohereChatV2Bridge.nativeResultToOpenAiJson(
                result, "cohere.command-a-reasoning"));

        JsonNode message = root.path("choices").get(0).path("message");
        assertThat(root.path("model").asText()).isEqualTo("cohere.command-a-reasoning");
        assertThat(root.path("choices").get(0).path("finish_reason").asText()).isEqualTo("tool_calls");
        assertThat(message.path("reasoning_content").asText()).isEqualTo("先分析问题。");
        assertThat(message.path("tool_calls").get(0).path("function").path("name").asText()).isEqualTo("write_file");
        assertThat(message.path("tool_calls").get(0).path("function").path("arguments").asText()).isEqualTo("{\"path\":\"a.txt\"}");
        assertThat(root.path("usage").path("total_tokens").asInt()).isEqualTo(15);
    }

    @Test
    void raisesSmallGeminiChatCompletionBudgetForOci() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":"只回复 OK"}],
                  "max_tokens":16,
                  "stream":true
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 128);

        JsonNode root = MAPPER.readTree(normalized);
        assertThat(root.path("max_tokens").asInt()).isEqualTo(128);
    }

    @Test
    void normalizesNullChatMessageContentBeforeProxyingToOci() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[
                    {"role":"user","content":null},
                    {"role":"assistant","content":null,"tool_calls":[{"id":"call_a","type":"function","function":{"name":"read_cpu","arguments":"{}"}}]},
                    {"role":"tool","tool_call_id":"call_a","content":null}
                  ],
                  "stream":true
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 128);

        JsonNode root = MAPPER.readTree(normalized);
        JsonNode messages = root.path("messages");
        assertThat(messages.get(0).path("content").asText()).isEmpty();
        assertThat(messages.get(1).path("content").asText()).isEmpty();
        assertThat(messages.get(1).path("tool_calls")).hasSize(1);
        assertThat(messages.get(2).path("content").asText()).isEmpty();
    }

    @Test
    void normalizesGeminiModelRoleBeforeProxyingToOci() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[
                    {"role":"user","content":"你是谁？"},
                    {"role":"model","content":"我是助手。"}
                  ],
                  "stream":true
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 128);

        JsonNode messages = MAPPER.readTree(normalized).path("messages");
        assertThat(messages.get(0).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(normalized)).isTrue();
    }

    @Test
    void normalizesNonObjectChatMessagesBeforeProxyingToOci() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[null, "hello", 7, {"role":"human","content":"继续"}],
                  "stream":true
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 128);

        JsonNode messages = MAPPER.readTree(normalized).path("messages");
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(0).path("content").asText()).isEqualTo("hello");
        assertThat(messages.get(1).path("content").asText()).isEqualTo("7");
        assertThat(messages.get(2).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(2).path("content").asText()).isEqualTo("继续");
    }

    @Test
    void treatsGeminiModelRoleToolCallsAsAssistantHistory() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[
                    {"role":"model","content":null,"tool_calls":[{"id":"call_a","type":"function","function":{"name":"read_cpu","arguments":"{}"}}]},
                    {"role":"tool","tool_call_id":"call_a","content":"Intel"}
                  ],
                  "stream":true
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 128);

        JsonNode messages = MAPPER.readTree(normalized).path("messages");
        assertThat(messages.get(0).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(0).path("tool_calls")).hasSize(1);
        assertThat(messages.get(1).path("role").asText()).isEqualTo("tool");
        assertThat(messages.get(1).path("content").asText()).isEqualTo("Intel");
    }

    @Test
    void normalizesModelRoleWhenConvertingChatCompletionsToResponses() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.20-multi-agent",
                  "messages":[
                    {"role":"user","content":"hi"},
                    {"role":"model","content":"hello"},
                    {"role":"developer","content":"stay concise"}
                  ]
                }
                """;

        JsonNode root = MAPPER.readTree(
                OciGenerativeOpenAiService.transformChatCompletionsToResponsesJson(payload.getBytes(), 128));

        JsonNode input = root.path("input");
        assertThat(input.get(0).path("role").asText()).isEqualTo("user");
        assertThat(input.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(input.get(2).path("role").asText()).isEqualTo("system");
    }

    @Test
    void doesNotRouteAllNullContentGeminiRequestToNativeBridge() {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":null}],
                  "stream":true
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isFalse();
    }

    @Test
    void routesGeminiRequestWithNullSystemAndValidUserToNativeBridge() {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[
                    {"role":"system","content":null},
                    {"role":"user","content":"你是谁？"}
                  ],
                  "stream":true
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isTrue();
    }

    @Test
    void skipsEmptyNativeMessagesBeforeCallingOciSdk() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[
                    {"role":"system","content":null},
                    {"role":"developer","content":[{"type":"text","text":""}]},
                    {"role":"user","content":"你是谁？"}
                  ],
                  "stream":true
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isTrue();

        GenericChatRequest request = OciGenerativeOpenAiService.toNativeGenericChatRequest(
                (ObjectNode) MAPPER.readTree(payload));
        assertThat(request.getMessages()).hasSize(1);
        assertThat(request.getMessages().get(0)).isInstanceOf(UserMessage.class);
        assertThat(((TextContent) request.getMessages().get(0).getContent().get(0)).getText()).isEqualTo("你是谁？");
    }

    @Test
    void usesMessageFallbackFieldsWhenGeminiContentIsEmpty() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":"","parts":[{"type":"text","text":"在？"}]}],
                  "stream":true
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isTrue();

        GenericChatRequest request = OciGenerativeOpenAiService.toNativeGenericChatRequest(
                (ObjectNode) MAPPER.readTree(payload));
        assertThat(request.getMessages()).hasSize(1);
        assertThat(((TextContent) request.getMessages().get(0).getContent().get(0)).getText()).isEqualTo("在？");
    }

    @Test
    void usesOfficialDocumentFallbackContentForNativeBridge() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":"","parts":[
                    {"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"abc"}}
                  ]}],
                  "stream":true
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isTrue();

        GenericChatRequest request = OciGenerativeOpenAiService.toNativeGenericChatRequest(
                (ObjectNode) MAPPER.readTree(payload));
        assertThat(request.getMessages().get(0).getContent().get(0)).isInstanceOf(DocumentContent.class);
        DocumentContent document = (DocumentContent) request.getMessages().get(0).getContent().get(0);
        assertThat(document.getDocumentUrl().getUrl()).isEqualTo("data:application/pdf;base64,abc");
    }

    @Test
    void preservesAssistantToolCallsWithoutEmptyTextContentForNativeBridge() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[
                    {"role":"assistant","content":null,"tool_calls":[{"id":"call_a","type":"function","function":{"name":"read_cpu","arguments":"{}"}}]},
                    {"role":"user","content":"继续"}
                  ],
                  "stream":false
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isTrue();

        GenericChatRequest request = OciGenerativeOpenAiService.toNativeGenericChatRequest(
                (ObjectNode) MAPPER.readTree(payload));
        assertThat(request.getMessages().get(0)).isInstanceOf(AssistantMessage.class);
        AssistantMessage assistant = (AssistantMessage) request.getMessages().get(0);
        assertThat(assistant.getContent()).isEmpty();
        assertThat(assistant.getToolCalls()).hasSize(1);
    }

    @Test
    void splitsParallelGeminiToolCallsIntoPairedNativeTurns() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[
                    {"role":"assistant","content":null,"tool_calls":[
                      {"id":"call_a","type":"function","function":{"name":"read_cpu","arguments":"{}"}},
                      {"id":"call_b","type":"function","function":{"name":"write_file","arguments":"{\\"path\\":\\"a.txt\\"}"}}
                    ]},
                    {"role":"tool","tool_call_id":"call_a","content":"Intel"},
                    {"role":"tool","tool_call_id":"call_b","content":"ok"}
                  ],
                  "stream":true
                }
                """;

        JsonNode messages = MAPPER.readTree(
                OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 128)).path("messages");

        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(0).path("tool_calls")).hasSize(1);
        assertThat(messages.get(0).path("tool_calls").get(0).path("id").asText()).isEqualTo("call_a");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("tool");
        assertThat(messages.get(1).path("tool_call_id").asText()).isEqualTo("call_a");
        assertThat(messages.get(2).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(2).path("tool_calls")).hasSize(1);
        assertThat(messages.get(2).path("tool_calls").get(0).path("id").asText()).isEqualTo("call_b");
        assertThat(messages.get(3).path("role").asText()).isEqualTo("tool");
        assertThat(messages.get(3).path("tool_call_id").asText()).isEqualTo("call_b");
    }

    @Test
    void splitsHermesSizedParallelGeminiToolCallsIntoStrictPairs() throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", "google.gemini-2.5-pro");
        root.put("stream", true);
        ArrayNode messages = MAPPER.createArrayNode();
        ObjectNode assistant = MAPPER.createObjectNode();
        assistant.put("role", "assistant");
        assistant.putNull("content");
        ArrayNode toolCalls = MAPPER.createArrayNode();
        for (int i = 0; i < 31; i++) {
            ObjectNode call = MAPPER.createObjectNode();
            call.put("id", "call_" + i);
            call.put("type", "function");
            ObjectNode fn = MAPPER.createObjectNode();
            fn.put("name", "tool_" + i);
            fn.put("arguments", "{}");
            call.set("function", fn);
            toolCalls.add(call);
        }
        assistant.set("tool_calls", toolCalls);
        messages.add(assistant);
        for (int i = 0; i < 31; i++) {
            ObjectNode tool = MAPPER.createObjectNode();
            tool.put("role", "tool");
            tool.put("tool_call_id", "call_" + i);
            tool.put("content", "result_" + i);
            messages.add(tool);
        }
        root.set("messages", messages);

        JsonNode normalizedMessages = MAPPER.readTree(
                OciGenerativeOpenAiService.transformChatCompletionsJson(
                        MAPPER.writeValueAsBytes(root), 128)).path("messages");

        assertThat(normalizedMessages).hasSize(62);
        for (int i = 0; i < 31; i++) {
            JsonNode assistantTurn = normalizedMessages.get(i * 2);
            JsonNode toolTurn = normalizedMessages.get(i * 2 + 1);
            assertThat(assistantTurn.path("role").asText()).isEqualTo("assistant");
            assertThat(assistantTurn.path("tool_calls")).hasSize(1);
            assertThat(assistantTurn.path("tool_calls").get(0).path("id").asText()).isEqualTo("call_" + i);
            assertThat(toolTurn.path("role").asText()).isEqualTo("tool");
            assertThat(toolTurn.path("tool_call_id").asText()).isEqualTo("call_" + i);
        }
    }

    @Test
    void keepsParallelToolCallsForNonGeminiChatModels() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[
                    {"role":"assistant","content":null,"tool_calls":[
                      {"id":"call_a","type":"function","function":{"name":"read_cpu","arguments":"{}"}},
                      {"id":"call_b","type":"function","function":{"name":"write_file","arguments":"{\\"path\\":\\"a.txt\\"}"}}
                    ]},
                    {"role":"tool","tool_call_id":"call_a","content":"Intel"},
                    {"role":"tool","tool_call_id":"call_b","content":"ok"}
                  ],
                  "stream":true
                }
                """;

        JsonNode messages = MAPPER.readTree(
                OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 128)).path("messages");

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(0).path("tool_calls")).hasSize(2);
        assertThat(messages.get(1).path("tool_call_id").asText()).isEqualTo("call_a");
        assertThat(messages.get(2).path("tool_call_id").asText()).isEqualTo("call_b");
    }

    @Test
    void preservesEmptyToolResultAsCountableNativeToolMessage() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[
                    {"role":"assistant","content":null,"tool_calls":[{"id":"call_a","type":"function","function":{"name":"read_cpu","arguments":"{}"}}]},
                    {"role":"tool","tool_call_id":"call_a","content":null}
                  ],
                  "stream":false
                }
                """;

        GenericChatRequest request = OciGenerativeOpenAiService.toNativeGenericChatRequest(
                (ObjectNode) MAPPER.readTree(payload));

        assertThat(request.getMessages()).hasSize(2);
        assertThat(request.getMessages().get(1)).isInstanceOf(ToolMessage.class);
        ToolMessage toolMessage = (ToolMessage) request.getMessages().get(1);
        assertThat(toolMessage.getToolCallId()).isEqualTo("call_a");
        assertThat(toolMessage.getContent()).hasSize(1);
        assertThat(((TextContent) toolMessage.getContent().get(0)).getText()).isEqualTo("null");
    }

    @Test
    void normalizesNullChatContentPartsBeforeProxyingToOci() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":[null, {"type":"text","text":null}]}]
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 128);

        JsonNode content = MAPPER.readTree(normalized).path("messages").get(0).path("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).isEmpty();
    }

    @Test
    void normalizesObjectTextChatContentPartsForGeminiNativeBridge() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":[{"type":"text","text":{"value":"你是谁？"}}]}],
                  "stream":true
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 128);
        JsonNode content = MAPPER.readTree(normalized).path("messages").get(0).path("content");

        assertThat(content.get(0).path("text").asText()).isEqualTo("你是谁？");
        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(normalized)).isTrue();

        GenericChatRequest request = OciGenerativeOpenAiService.toNativeGenericChatRequest(
                (ObjectNode) MAPPER.readTree(normalized));
        TextContent text = (TextContent) request.getMessages().get(0).getContent().get(0);
        assertThat(text.getText()).isEqualTo("你是谁？");
    }

    @Test
    void usesPromptFallbackWhenChatMessagesHaveNoUsableContent() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "prompt":"你是谁？",
                  "messages":[{"role":"user","content":[{"type":"text","text":""}]}],
                  "stream":true
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 128);
        JsonNode messages = MAPPER.readTree(normalized).path("messages");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).path("content").asText()).isEqualTo("你是谁？");
        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(normalized)).isTrue();
    }

    @Test
    void rewritesChatCompletionsOnlyForActualMultiAgentModelName() {
        assertThat(OciGenerativeOpenAiService.shouldRewriteChatCompletionsToResponses("xai.grok-4.3")).isFalse();
        assertThat(OciGenerativeOpenAiService.shouldRewriteChatCompletionsToResponses("xai.grok-4.20-0309-reasoning")).isFalse();
        assertThat(OciGenerativeOpenAiService.shouldRewriteChatCompletionsToResponses("xai.grok-4.20-reasoning")).isFalse();
        assertThat(OciGenerativeOpenAiService.shouldRewriteChatCompletionsToResponses("xai.grok-4.20-0309-non-reasoning")).isFalse();
        assertThat(OciGenerativeOpenAiService.shouldRewriteChatCompletionsToResponses("xai.grok-4.20-non-reasoning")).isFalse();
        assertThat(OciGenerativeOpenAiService.shouldRewriteChatCompletionsToResponses("google.gemini-2.5-pro")).isFalse();
        assertThat(OciGenerativeOpenAiService.shouldRewriteChatCompletionsToResponses("cohere.command-a-reasoning")).isFalse();
        assertThat(OciGenerativeOpenAiService.shouldRewriteChatCompletionsToResponses("cohere.command-a-03-2025")).isFalse();
        assertThat(OciGenerativeOpenAiService.shouldRewriteChatCompletionsToResponses("cohere.embed-v4.0")).isFalse();
        assertThat(OciGenerativeOpenAiService.shouldRewriteChatCompletionsToResponses("xai.grok-tts")).isFalse();
        assertThat(OciGenerativeOpenAiService.shouldRewriteChatCompletionsToResponses("oci.multi-agent-runtime")).isTrue();
    }

    @Test
    void buffersNativeSdkChatCompletionStreamsOnly() throws Exception {
        assertThat(OciGenerativeOpenAiService.shouldBufferChatCompletionStream("google.gemini-2.5-pro")).isTrue();
        assertThat(OciGenerativeOpenAiService.shouldBufferChatCompletionStream("google.gemini-2.5-flash")).isTrue();
        assertThat(OciGenerativeOpenAiService.shouldBufferChatCompletionStream("google.gemini-2.5-flash-lite")).isTrue();
        assertThat(OciGenerativeOpenAiService.shouldBufferChatCompletionStream("cohere.command-a-reasoning")).isTrue();
        assertThat(OciGenerativeOpenAiService.shouldBufferChatCompletionStream("xai.grok-4.3")).isFalse();

        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":"hi"}],
                  "stream":true,
                  "stream_options":{"include_usage":true}
                }
                """;
        JsonNode root = MAPPER.readTree(OciGenerativeOpenAiService.forceChatCompletionNonStreamJson(payload.getBytes()));

        assertThat(root.path("stream").asBoolean()).isFalse();
        assertThat(root.has("stream_options")).isFalse();
        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isTrue();
    }

    @Test
    void recognizesAudioSpeechAsBinaryOpenAiCompatibleEndpoint() {
        assertThat(OciGenerativeOpenAiService.isAudioSpeechPath("/audio/speech")).isTrue();
        assertThat(OciGenerativeOpenAiService.isAudioSpeechPath("/v1/audio/speech")).isTrue();
        assertThat(OciGenerativeOpenAiService.isAudioSpeechPath("/chat/completions")).isFalse();

        assertThat(OciGenerativeOpenAiService.shouldUseBinaryProxy("/audio/speech")).isTrue();
        assertThat(OciGenerativeOpenAiService.shouldUseBinaryProxy("/v1/audio/speech")).isTrue();
        assertThat(OciGenerativeOpenAiService.shouldUseBinaryProxy("/responses")).isFalse();
    }

    @Test
    void usesGeminiNativeChatForStreamingAndNonStreamingChatCompletionsOnly() throws Exception {
        String streamingPayload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":"hi"}],
                  "stream":true
                }
                """;
        String nonStreamingPayload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":"hi"}],
                  "stream":false
                }
                """;

        byte[] streaming = OciGenerativeOpenAiService.transformChatCompletionsJson(streamingPayload.getBytes(), 128);
        byte[] nonStreaming = OciGenerativeOpenAiService.transformChatCompletionsJson(nonStreamingPayload.getBytes(), 128);

        assertThat(OciGenerativeOpenAiService.shouldUseGeminiNativeChat("google.gemini-2.5-pro", streaming)).isTrue();
        assertThat(OciGenerativeOpenAiService.shouldUseGeminiNativeChat("google.gemini-2.5-flash", streaming)).isTrue();
        assertThat(OciGenerativeOpenAiService.shouldUseGeminiNativeChat("google.gemini-2.5-flash-lite", streaming)).isTrue();
        assertThat(OciGenerativeOpenAiService.shouldUseGeminiNativeChat("google.gemini-2.5-pro", nonStreaming)).isTrue();
        assertThat(OciGenerativeOpenAiService.shouldUseGeminiNativeChat("xai.grok-4.3", streaming)).isFalse();
        assertThat(OciGenerativeOpenAiService.shouldUseGeminiNativeChat("openai.gpt-oss-120b", streaming)).isFalse();
    }

    @Test
    void usesCohereV2NativeChatOnlyForCommandAReasoning() throws Exception {
        String payload = """
                {
                  "model":"cohere.command-a-reasoning",
                  "messages":[{"role":"user","content":"hi"}],
                  "stream":true
                }
                """;

        byte[] normalized = OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 128);

        assertThat(OciGenerativeOpenAiService.shouldUseCohereCommandAReasoningNativeChat(
                "cohere.command-a-reasoning", normalized)).isTrue();
        assertThat(OciGenerativeOpenAiService.shouldUseCohereCommandAReasoningNativeChat(
                "cohere.command-a-03-2025", normalized)).isFalse();
        assertThat(OciGenerativeOpenAiService.shouldUseCohereCommandAReasoningNativeChat(
                "google.gemini-2.5-pro", normalized)).isFalse();
    }

    @Test
    void rejectsAudioAndVideoPayloadsForCohereV2NativeChat() throws Exception {
        String audioPayload = """
                {
                  "model":"cohere.command-a-reasoning",
                  "messages":[{"role":"user","content":[
                    {"type":"input_audio","audio_url":{"url":"data:audio/wav;base64,AAAA"}}
                  ]}]
                }
                """;
        String videoPayload = """
                {
                  "model":"cohere.command-a-reasoning",
                  "messages":[{"role":"user","content":[
                    {"type":"input_video","video_url":{"url":"data:video/mp4;base64,AAAA"}}
                  ]}]
                }
                """;
        String imagePayload = """
                {
                  "model":"cohere.command-a-reasoning",
                  "messages":[{"role":"user","content":[
                    {"type":"text","text":"看图"},
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}
                  ]}]
                }
                """;

        assertThat(OciCohereChatV2Bridge.canUseNativeChat(audioPayload.getBytes())).isFalse();
        assertThat(OciCohereChatV2Bridge.canUseNativeChat(videoPayload.getBytes())).isFalse();
        assertThat(OciCohereChatV2Bridge.canUseNativeChat(imagePayload.getBytes())).isTrue();
    }

    @Test
    void convertsUnknownGeminiContentObjectsToTextForNativeBridge() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":[
                    {"type":"custom_context","payload":{"path":"a.txt","value":"null"}}
                  ]}],
                  "stream":false
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isTrue();

        GenericChatRequest request = OciGenerativeOpenAiService.toNativeGenericChatRequest(
                (ObjectNode) MAPPER.readTree(payload));
        assertThat(request.getMessages().get(0).getContent()).hasSize(1);
        assertThat(request.getMessages().get(0).getContent().get(0)).isInstanceOf(TextContent.class);
        assertThat(((TextContent) request.getMessages().get(0).getContent().get(0)).getText())
                .contains("custom_context")
                .contains("a.txt");
    }

    @Test
    void convertsPrimitiveGeminiContentPartsToTextForNativeBridge() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":[123, true]}],
                  "stream":false
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isTrue();

        GenericChatRequest request = OciGenerativeOpenAiService.toNativeGenericChatRequest(
                (ObjectNode) MAPPER.readTree(payload));
        assertThat(request.getMessages().get(0).getContent()).hasSize(2);
        assertThat(((TextContent) request.getMessages().get(0).getContent().get(0)).getText()).isEqualTo("123");
        assertThat(((TextContent) request.getMessages().get(0).getContent().get(1)).getText()).isEqualTo("true");
    }

    @Test
    void convertsSingleObjectGeminiContentForNativeBridge() throws Exception {
        String textPayload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":{"type":"text","text":"hi"}}],
                  "stream":false
                }
                """;
        String imagePayload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":{"type":"image_url","image_url":{"url":"data:image/png;base64,abc"}}}],
                  "stream":false
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(textPayload.getBytes())).isTrue();
        GenericChatRequest textRequest = OciGenerativeOpenAiService.toNativeGenericChatRequest(
                (ObjectNode) MAPPER.readTree(textPayload));
        assertThat(((TextContent) textRequest.getMessages().get(0).getContent().get(0)).getText()).isEqualTo("hi");

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(imagePayload.getBytes())).isTrue();
        GenericChatRequest imageRequest = OciGenerativeOpenAiService.toNativeGenericChatRequest(
                (ObjectNode) MAPPER.readTree(imagePayload));
        assertThat(imageRequest.getMessages().get(0).getContent().get(0)).isInstanceOf(ImageContent.class);
    }

    @Test
    void routesGeminiImageRequestsToNativeBridge() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":[
                    {"type":"text","text":"inspect"},
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,abc","detail":"high"}}
                  ]}],
                  "stream":true
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isTrue();

        GenericChatRequest request = OciGenerativeOpenAiService.toNativeGenericChatRequest(
                (ObjectNode) MAPPER.readTree(payload));
        List<ChatContent> content = request.getMessages().get(0).getContent();
        assertThat(content).hasSize(2);
        assertThat(content.get(0)).isInstanceOf(TextContent.class);
        assertThat(content.get(1)).isInstanceOf(ImageContent.class);
        ImageContent image = (ImageContent) content.get(1);
        assertThat(image.getImageUrl().getUrl()).isEqualTo("data:image/png;base64,abc");
        assertThat(image.getImageUrl().getDetail().getValue()).isEqualTo("HIGH");
    }

    @Test
    void supportsInputImageAndAnthropicSourceImagesInNativeBridge() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":[
                    {"type":"input_image","image_url":"https://example.com/a.png"},
                    {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"abcd"}}
                  ]}],
                  "stream":true
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isTrue();
        GenericChatRequest request = OciGenerativeOpenAiService.toNativeGenericChatRequest(
                (ObjectNode) MAPPER.readTree(payload));
        assertThat(request.getMessages().get(0).getContent())
                .allMatch(ImageContent.class::isInstance);
        assertThat(((ImageContent) request.getMessages().get(0).getContent().get(0)).getImageUrl().getUrl())
                .isEqualTo("https://example.com/a.png");
        assertThat(((ImageContent) request.getMessages().get(0).getContent().get(1)).getImageUrl().getUrl())
                .isEqualTo("data:image/jpeg;base64,abcd");
    }

    @Test
    void routesGeminiDocumentRequestsToNativeBridge() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":[
                    {"type":"text","text":"inspect"},
                    {"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"abc"}}
                  ]}],
                  "stream":true
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isTrue();

        GenericChatRequest request = OciGenerativeOpenAiService.toNativeGenericChatRequest(
                (ObjectNode) MAPPER.readTree(payload));
        List<ChatContent> content = request.getMessages().get(0).getContent();
        assertThat(content).hasSize(2);
        assertThat(content.get(1)).isInstanceOf(DocumentContent.class);
        DocumentContent document = (DocumentContent) content.get(1);
        assertThat(document.getDocumentUrl().getUrl()).isEqualTo("data:application/pdf;base64,abc");
    }

    @Test
    void routesSingleObjectGeminiDocumentRequestsToNativeBridge() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":{"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"abc"}}}],
                  "stream":true
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isTrue();

        GenericChatRequest request = OciGenerativeOpenAiService.toNativeGenericChatRequest(
                (ObjectNode) MAPPER.readTree(payload));
        assertThat(request.getMessages().get(0).getContent().get(0)).isInstanceOf(DocumentContent.class);
    }

    @Test
    void doesNotRouteUnsupportedOfficeDocumentRequestsToNativeBridge() {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":[
                    {"type":"document","source":{"type":"base64","media_type":"application/vnd.openxmlformats-officedocument.wordprocessingml.document","data":"abc"}}
                  ]}],
                  "stream":true
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isFalse();
    }

    @Test
    void doesNotRouteDocumentUrlWithUnsupportedExplicitMimeTypeToNativeBridge() {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":[
                    {"type":"document","source":{"type":"url","media_type":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","url":"https://example.com/quota.xlsx"}}
                  ]}],
                  "stream":true
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isFalse();
    }

    @Test
    void routesGeminiTextDocumentAudioAndVideoRequestsToNativeBridge() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":[
                    {"type":"document","source":{"type":"text","text":"hello"}},
                    {"type":"audio","source":{"type":"base64","media_type":"audio/mp3","data":"aaaa"}},
                    {"type":"video","source":{"type":"base64","media_type":"video/mp4","data":"bbbb"}}
                  ]}],
                  "stream":false
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isTrue();

        GenericChatRequest request = OciGenerativeOpenAiService.toNativeGenericChatRequest(
                (ObjectNode) MAPPER.readTree(payload));
        List<ChatContent> content = request.getMessages().get(0).getContent();
        assertThat(content).hasSize(3);
        assertThat(content.get(0)).isInstanceOf(DocumentContent.class);
        assertThat(content.get(1)).isInstanceOf(AudioContent.class);
        assertThat(content.get(2)).isInstanceOf(VideoContent.class);
        assertThat(((DocumentContent) content.get(0)).getDocumentUrl().getUrl())
                .isEqualTo("data:text/plain;base64,aGVsbG8=");
        assertThat(((AudioContent) content.get(1)).getAudioUrl().getUrl())
                .isEqualTo("data:audio/mp3;base64,aaaa");
        assertThat(((VideoContent) content.get(2)).getVideoUrl().getUrl())
                .isEqualTo("data:video/mp4;base64,bbbb");
    }

    @Test
    void doesNotRouteUnsupportedAudioAndVideoMimeTypesToNativeBridge() {
        String audioPayload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":[
                    {"type":"audio","source":{"type":"base64","media_type":"audio/m4a","data":"aaaa"}}
                  ]}]
                }
                """;
        String videoPayload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":[
                    {"type":"video","source":{"type":"base64","media_type":"video/mkv","data":"bbbb"}}
                  ]}]
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(audioPayload.getBytes())).isFalse();
        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(videoPayload.getBytes())).isFalse();
    }

    @Test
    void doesNotRouteNonUserDocumentAudioOrVideoMessagesToNativeBridge() {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[
                    {"role":"system","content":[{"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"abc"}}]},
                    {"role":"assistant","content":[{"type":"audio","source":{"type":"base64","media_type":"audio/mp3","data":"abc"}}]},
                    {"role":"developer","content":[{"type":"video","source":{"type":"base64","media_type":"video/mp4","data":"abc"}}]}
                  ],
                  "stream":true
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isFalse();
    }

    @Test
    void doesNotRouteNonUserImageMessagesToNativeBridge() {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"system","content":[
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,abc"}}
                  ]}],
                  "stream":true
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isFalse();
    }

    @Test
    void doesNotRouteNonUserSingleObjectImageMessagesToNativeBridge() {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"system","content":{"type":"image_url","image_url":{"url":"data:image/png;base64,abc"}}}],
                  "stream":true
                }
                """;

        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat(payload.getBytes())).isFalse();
    }

    @Test
    void doesNotRouteGeminiRequestsWithoutMessagesToNativeBridge() {
        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat("""
                {"model":"google.gemini-2.5-pro","stream":true}
                """.getBytes())).isFalse();
        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat("""
                {"model":"google.gemini-2.5-pro","messages":[],"stream":true}
                """.getBytes())).isFalse();
        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat("""
                {"model":"google.gemini-2.5-pro","messages":[{}],"stream":true}
                """.getBytes())).isFalse();
        assertThat(OciGenerativeOpenAiService.canUseNativeGenericChat("""
                {"model":"google.gemini-2.5-pro","messages":[null],"stream":true}
                """.getBytes())).isFalse();
    }

    @Test
    void convertsOpenAiChatRequestToNativeGenericChatRequest() throws Exception {
        ObjectNode payload = (ObjectNode) MAPPER.readTree("""
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[
                    {"role":"system","content":"You are concise."},
                    {"role":"developer","content":"Prefer direct answers."},
                    {"role":"user","content":[{"type":"text","text":"你的 CPU 型号是？"}]}
                  ],
                  "tools":[{"type":"function","function":{"name":"read_cpu","description":"read cpu","parameters":{"type":"object"}}}],
                  "tool_choice":"auto",
                  "parallel_tool_calls":true,
                  "max_tokens":128,
                  "temperature":0.2,
                  "stream":false
                }
                """);

        GenericChatRequest request = OciGenerativeOpenAiService.toNativeGenericChatRequest(payload);

        assertThat(request.getIsStream()).isFalse();
        assertThat(request.getMaxTokens()).isEqualTo(128);
        assertThat(request.getTemperature()).isEqualTo(0.2D);
        assertThat(request.getMessages()).hasSize(3);
        assertThat(request.getMessages().get(1)).isInstanceOf(DeveloperMessage.class);
        assertThat(request.getTools()).hasSize(1);
        assertThat(request.getTools().get(0)).isInstanceOf(FunctionDefinition.class);
        assertThat(((FunctionDefinition) request.getTools().get(0)).getName()).isEqualTo("read_cpu");
        assertThat(request.getToolChoice()).isInstanceOf(ToolChoiceAuto.class);
        assertThat(request.getIsParallelToolCalls()).isTrue();
    }

    @Test
    void nativeGenericChatRequestSerializesWithOciSdkDiscriminators() throws Exception {
        ObjectNode payload = (ObjectNode) MAPPER.readTree("""
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[
                    {"role":"developer","content":"Prefer direct answers."},
                    {"role":"user","content":[
                      {"type":"text","text":"hi"},
                      {"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"abc"}},
                      {"type":"audio","source":{"type":"base64","media_type":"audio/mp3","data":"aaaa"}},
                      {"type":"video","source":{"type":"base64","media_type":"video/mp4","data":"bbbb"}}
                    ]}
                  ],
                  "tools":[{"type":"function","function":{"name":"write_file","parameters":{"type":"object"}}}],
                  "tool_choice":"auto",
                  "parallel_tool_calls":true,
                  "stream":true
                }
                """);

        GenericChatRequest request = OciGenerativeOpenAiService.toNativeGenericChatRequest(payload);
        ObjectMapper sdkMapper = new ObjectMapper()
                .setFilterProvider(new com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider()
                        .setFailOnUnknownId(false));
        JsonNode root = sdkMapper.readTree(sdkMapper.writerFor(BaseChatRequest.class).writeValueAsString(request));

        assertThat(root.path("apiFormat").asText()).isEqualTo("GENERIC");
        assertThat(root.path("isStream").asBoolean()).isFalse();
        assertThat(root.path("messages").get(0).path("role").asText()).isEqualTo("DEVELOPER");
        assertThat(root.path("messages").get(1).path("role").asText()).isEqualTo("USER");
        assertThat(root.path("messages").get(1).path("content").get(0).path("type").asText()).isEqualTo("TEXT");
        assertThat(root.path("messages").get(1).path("content").get(1).path("type").asText()).isEqualTo("DOCUMENT");
        assertThat(root.path("messages").get(1).path("content").get(2).path("type").asText()).isEqualTo("AUDIO");
        assertThat(root.path("messages").get(1).path("content").get(3).path("type").asText()).isEqualTo("VIDEO");
        assertThat(root.path("tools").get(0).path("type").asText()).isEqualTo("FUNCTION");
        assertThat(root.path("tools").get(0).path("name").asText()).isEqualTo("write_file");
        assertThat(root.path("toolChoice").path("type").asText()).isEqualTo("AUTO");
        assertThat(root.path("isParallelToolCalls").asBoolean()).isTrue();
    }

    @Test
    void sanitizesUnsupportedJsonSchemaKeywordsBeforeNativeGenericChat() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "messages":[{"role":"user","content":"你的 CPU 型号是？"}],
                  "tools":[{
                    "type":"function",
                    "function":{
                      "name":"read_cpu",
                      "description":"read cpu",
                      "parameters":{
                        "$schema":"https://json-schema.org/draft/2020-12/schema",
                        "type":"object",
                        "additionalProperties":false,
                        "propertyNames":{"pattern":"^[a-z]+$"},
                        "properties":{
                          "path":{
                            "type":["string","null"],
                            "description":"file path",
                            "propertyNames":{"pattern":"^x"}
                          },
                          "meta":{
                            "type":"object",
                            "additionalProperties":{"type":"string"},
                            "propertyNames":{"pattern":"^m"}
                          },
                          "mode":{
                            "oneOf":[
                              {"type":"string","enum":["fast","safe"]},
                              {"type":"integer"}
                            ]
                          }
                        },
                        "required":["path","mode"]
                      }
                    }
                  }],
                  "tool_choice":"auto",
                  "stream":true
                }
                """;

        ObjectNode normalized = (ObjectNode) MAPPER.readTree(
                OciGenerativeOpenAiService.transformChatCompletionsJson(payload.getBytes(), 128));
        JsonNode parameters = normalized.at("/tools/0/function/parameters");

        assertThat(parameters.toString())
                .doesNotContain("$schema")
                .doesNotContain("propertyNames")
                .doesNotContain("additionalProperties")
                .doesNotContain("oneOf");
        assertThat(parameters.path("type").asText()).isEqualTo("object");
        assertThat(parameters.at("/properties/path/type").asText()).isEqualTo("string");
        assertThat(parameters.at("/properties/path/nullable").asBoolean()).isTrue();
        assertThat(parameters.at("/properties/mode/type").asText()).isEqualTo("string");
        assertThat(parameters.at("/properties/mode/enum/0").asText()).isEqualTo("fast");
        assertThat(parameters.path("required")).hasSize(2);

        GenericChatRequest request = OciGenerativeOpenAiService.toNativeGenericChatRequest(normalized);
        Object nativeParameters = ((FunctionDefinition) request.getTools().get(0)).getParameters();
        assertThat(String.valueOf(nativeParameters))
                .doesNotContain("$schema")
                .doesNotContain("propertyNames")
                .doesNotContain("additionalProperties")
                .doesNotContain("oneOf");
    }

    @Test
    void convertsNativeGenericChatResultToOpenAiChatCompletion() throws Exception {
        AssistantMessage answer = AssistantMessage.builder()
                .content(List.of(TextContent.builder().text("我的模型是 google/gemini-2.5-pro。").build()))
                .build();
        AssistantMessage toolAnswer = AssistantMessage.builder()
                .content(List.of(TextContent.builder().text("").build()))
                .toolCalls(List.of(FunctionCall.builder()
                        .id("call_a")
                        .name("read_cpu")
                        .arguments("{}")
                        .build()))
                .build();
        GenericChatResponse response = GenericChatResponse.builder()
                .choices(List.of(
                        ChatChoice.builder().index(0).message(answer).finishReason("stop").build(),
                        ChatChoice.builder().index(1).message(toolAnswer).finishReason("stop").build()))
                .usage(Usage.builder().promptTokens(3).completionTokens(5).totalTokens(8).build())
                .build();
        ChatResult result = ChatResult.builder()
                .modelId("google.gemini-2.5-pro")
                .chatResponse(response)
                .build();

        JsonNode root = MAPPER.readTree(OciGenerativeOpenAiService.nativeGenericChatResultToOpenAiJson(
                result, "google.gemini-2.5-pro"));

        assertThat(root.path("model").asText()).isEqualTo("google.gemini-2.5-pro");
        assertThat(root.path("choices").get(0).path("message").path("content").asText())
                .isEqualTo("我的模型是 google/gemini-2.5-pro。");
        assertThat(root.path("choices").get(1).path("finish_reason").asText()).isEqualTo("tool_calls");
        assertThat(root.path("choices").get(1).path("message").get("content").isNull()).isTrue();
        assertThat(root.path("choices").get(1).path("message").path("tool_calls").get(0)
                .path("function").path("name").asText()).isEqualTo("read_cpu");
        assertThat(root.path("usage").path("total_tokens").asInt()).isEqualTo(8);
    }

    @Test
    void rejectsNativeGenericChatResultWithoutChoices() {
        ChatResult result = ChatResult.builder()
                .modelId("google.gemini-2.5-pro")
                .chatResponse(GenericChatResponse.builder().choices(List.of()).build())
                .build();

        assertThatThrownBy(() -> OciGenerativeOpenAiService.nativeGenericChatResultToOpenAiJson(
                result, "google.gemini-2.5-pro"))
                .isInstanceOf(com.ociworker.exception.OciException.class)
                .hasMessageContaining("未返回 choices");
    }

    @Test
    void returnsNativeGenericChatResultWithoutVisibleOutputAsValidCompletion() throws Exception {
        ChatResult result = ChatResult.builder()
                .modelId("google.gemini-2.5-pro")
                .chatResponse(GenericChatResponse.builder()
                        .choices(List.of(ChatChoice.builder()
                                .index(0)
                                .message(AssistantMessage.builder()
                                        .content(List.of(TextContent.builder().text("").build()))
                                        .build())
                                .finishReason("stop")
                                .build()))
                        .build())
                .build();

        JsonNode root = MAPPER.readTree(OciGenerativeOpenAiService.nativeGenericChatResultToOpenAiJson(
                result, "google.gemini-2.5-pro"));

        assertThat(root.path("choices")).hasSize(1);
        assertThat(root.path("choices").get(0).path("message").path("content").asText()).isEmpty();
        assertThat(root.path("choices").get(0).path("finish_reason").asText()).isEqualTo("stop");
    }

    @Test
    void mapsEmptyNativeGenericChatMaxTokenResultToLengthCompletion() throws Exception {
        ChatResult result = ChatResult.builder()
                .modelId("google.gemini-2.5-pro")
                .chatResponse(GenericChatResponse.builder()
                        .choices(List.of(ChatChoice.builder()
                                .index(0)
                                .message(AssistantMessage.builder()
                                        .content(List.of(TextContent.builder().text("").build()))
                                        .build())
                                .finishReason("max_tokens")
                                .build()))
                        .usage(Usage.builder().promptTokens(3).completionTokens(16).totalTokens(19).build())
                        .build())
                .build();

        JsonNode root = MAPPER.readTree(OciGenerativeOpenAiService.nativeGenericChatResultToOpenAiJson(
                result, "google.gemini-2.5-pro"));

        assertThat(root.path("choices").get(0).path("message").path("content").asText()).isEmpty();
        assertThat(root.path("choices").get(0).path("finish_reason").asText()).isEqualTo("length");
        assertThat(root.path("usage").path("total_tokens").asInt()).isEqualTo(19);
    }

    @Test
    void convertsBufferedChatCompletionJsonToOpenAiSse() throws Exception {
        String payload = """
                {
                  "id":"chatcmpl-buffered",
                  "object":"chat.completion",
                  "created":123,
                  "model":"google.gemini-2.5-pro",
                  "choices":[
                    {"index":0,"message":{"role":"assistant","reasoning_content":"先确认系统信息。","content":"我的模型是 google/gemini-2.5-pro。"},"finish_reason":"stop"},
                    {"index":1,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_a","type":"function","function":{"name":"read_cpu","arguments":"{}"}}]},"finish_reason":"stop"}
                  ],
                  "usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}
                }
                """;

        String sse = OciGenerativeOpenAiService.chatCompletionJsonToSse(payload, "google.gemini-2.5-pro");

        assertThat(sse).contains("data: ");
        assertThat(sse).contains("\"delta\":{\"role\":\"assistant\"}");
        assertThat(sse).contains("\"reasoning_content\":\"先确认系统信息。\"");
        assertThat(sse).contains("我的模型是 google/gemini-2.5-pro。");
        assertThat(sse).contains("\"tool_calls\"");
        assertThat(sse).contains("\"id\":\"call_a\"");
        assertThat(sse).contains("\"index\":0");
        assertThat(sse).contains("\"finish_reason\":\"tool_calls\"");
        assertThat(sse).contains("\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":5,\"total_tokens\":8}");
        assertThat(sse).endsWith("data: [DONE]\n\n");
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
    void preservesResponsesImageInputWhenConvertedToChatCompletions() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "input":[{
                    "role":"user",
                    "content":[
                      {"type":"input_text","text":"describe this"},
                      {"type":"input_image","image_url":"data:image/png;base64,abc","detail":"high"}
                    ]
                  }],
                  "max_output_tokens":256
                }
                """;

        byte[] converted = OciGenerativeOpenAiService.transformResponsesToChatCompletionsJson(payload.getBytes(), 128);

        JsonNode root = MAPPER.readTree(converted);
        JsonNode content = root.path("messages").get(0).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).isEqualTo("describe this");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).path("image_url").path("url").asText())
                .isEqualTo("data:image/png;base64,abc");
        assertThat(content.get(1).path("image_url").path("detail").asText()).isEqualTo("high");
    }

    @Test
    void mapsResponsesStructuredOutputFormatToChatCompletions() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "input":"return json",
                  "text":{
                    "format":{
                      "type":"json_schema",
                      "name":"answer",
                      "schema":{
                        "type":"object",
                        "properties":{"ok":{"type":"boolean"}},
                        "required":["ok"],
                        "additionalProperties":false
                      },
                      "strict":true
                    }
                  }
                }
                """;

        byte[] converted = OciGenerativeOpenAiService.transformResponsesToChatCompletionsJson(payload.getBytes(), 128);

        JsonNode root = MAPPER.readTree(converted);
        assertThat(root.path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(root.path("response_format").path("json_schema").path("name").asText()).isEqualTo("answer");
        assertThat(root.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(root.path("response_format").path("json_schema").path("schema").path("properties").path("ok").path("type").asText())
                .isEqualTo("boolean");
    }

    @Test
    void raisesSmallGeminiResponsesBudgetWhenConvertedToChatCompletions() throws Exception {
        String payload = """
                {
                  "model":"google.gemini-2.5-pro",
                  "input":"只回复 OK",
                  "stream":true,
                  "max_output_tokens":16
                }
                """;

        byte[] converted = OciGenerativeOpenAiService.transformResponsesToChatCompletionsJson(payload.getBytes(), 128);

        JsonNode root = MAPPER.readTree(converted);
        assertThat(root.path("max_tokens").asInt()).isEqualTo(128);
        assertThat(root.path("stream").asBoolean()).isTrue();
    }

    @Test
    void convertsChatCompletionToolsToResponsesRequestForMultiAgent() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.20-multi-agent",
                  "messages":[{"role":"user","content":"create a file"}],
                  "tools":[{"type":"function","function":{"name":"write_file","description":"write","parameters":{"type":"object"},"strict":true}}],
                  "tool_choice":{"type":"function","function":{"name":"write_file"}},
                  "parallel_tool_calls":true,
                  "max_tokens":512,
                  "stream":true
                }
                """;

        byte[] converted = OciGenerativeOpenAiService.transformChatCompletionsToResponsesJson(payload.getBytes(), 128);

        JsonNode root = MAPPER.readTree(converted);
        assertThat(root.path("model").asText()).isEqualTo("xai.grok-4.20-multi-agent");
        assertThat(root.path("input").get(0).path("role").asText()).isEqualTo("user");
        assertThat(root.path("input").get(0).path("content").get(0).path("type").asText()).isEqualTo("input_text");
        assertThat(root.path("tools").get(0).path("type").asText()).isEqualTo("function");
        assertThat(root.path("tools").get(0).path("name").asText()).isEqualTo("write_file");
        assertThat(root.path("tools").get(0).path("parameters").path("type").asText()).isEqualTo("object");
        assertThat(root.path("tools").get(0).path("strict").asBoolean()).isTrue();
        assertThat(root.path("tool_choice").path("type").asText()).isEqualTo("function");
        assertThat(root.path("tool_choice").path("name").asText()).isEqualTo("write_file");
        assertThat(root.path("parallel_tool_calls").asBoolean()).isTrue();
        assertThat(root.path("max_output_tokens").asInt()).isEqualTo(512);
        assertThat(root.path("stream").asBoolean()).isFalse();
    }

    @Test
    void convertsChatCompletionImagesToResponsesInputImagesForMultiAgent() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.20-multi-agent",
                  "messages":[{"role":"user","content":[
                    {"type":"text","text":"describe this"},
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,abc","detail":"high"}}
                  ]}],
                  "max_tokens":512
                }
                """;

        byte[] converted = OciGenerativeOpenAiService.transformChatCompletionsToResponsesJson(payload.getBytes(), 128);

        JsonNode root = MAPPER.readTree(converted);
        JsonNode content = root.path("input").get(0).path("content");
        assertThat(content.get(0).path("type").asText()).isEqualTo("input_text");
        assertThat(content.get(0).path("text").asText()).isEqualTo("describe this");
        assertThat(content.get(1).path("type").asText()).isEqualTo("input_image");
        assertThat(content.get(1).path("image_url").asText()).isEqualTo("data:image/png;base64,abc");
        assertThat(content.get(1).path("detail").asText()).isEqualTo("high");
    }

    @Test
    void mapsChatCompletionResponseFormatToResponsesTextFormatForMultiAgent() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.20-multi-agent",
                  "messages":[{"role":"user","content":"return json"}],
                  "response_format":{
                    "type":"json_schema",
                    "json_schema":{
                      "name":"answer",
                      "schema":{
                        "type":"object",
                        "properties":{"ok":{"type":"boolean"}},
                        "required":["ok"],
                        "additionalProperties":false
                      },
                      "strict":true
                    }
                  }
                }
                """;

        byte[] converted = OciGenerativeOpenAiService.transformChatCompletionsToResponsesJson(payload.getBytes(), 128);

        JsonNode root = MAPPER.readTree(converted);
        assertThat(root.path("text").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(root.path("text").path("format").path("name").asText()).isEqualTo("answer");
        assertThat(root.path("text").path("format").path("strict").asBoolean()).isTrue();
        assertThat(root.path("text").path("format").path("schema").path("properties").path("ok").path("type").asText())
                .isEqualTo("boolean");
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
    void convertsBufferedChatCompletionJsonToResponsesSse() throws Exception {
        String payload = """
                {
                  "id":"chatcmpl-buffered",
                  "object":"chat.completion",
                  "created":123,
                  "model":"google.gemini-2.5-pro",
                  "choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],
                  "usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}
                }
                """;

        String sse = OciGenerativeOpenAiService.chatCompletionJsonToResponsesSse(
                payload, "google.gemini-2.5-pro", null);

        assertThat(sse).contains("event: response.created");
        assertThat(sse).contains("event: response.output_text.delta");
        assertThat(sse).contains("event: response.completed");
        assertThat(sse).contains("data: [DONE]");
        assertThat(sse).contains("\"model\":\"google.gemini-2.5-pro\"");
        assertThat(sse).contains("\"text\":\"OK\"");
    }

    @Test
    void convertsReasoningThenStreamingToolCallToResponsesEvents() throws Exception {
        OciGenerativeOpenAiService.ResponsesBridgeStreamState state =
                new OciGenerativeOpenAiService.ResponsesBridgeStreamState("xai.grok-4.20-0309-reasoning");
        String reasoning = """
                {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"xai.grok-4.20-0309-reasoning","choices":[{"index":0,"delta":{"reasoning_content":"need to call a tool"}}]}
                """;
        String tool = """
                {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"xai.grok-4.20-0309-reasoning","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"write_file","arguments":"{\\\"path\\\":\\\"a.txt\\\"}"}}]},"finish_reason":"tool_calls"}]}
                """;

        String sse = OciGenerativeOpenAiService.chatChunkToResponsesSse(MAPPER.readTree(reasoning), state)
                + OciGenerativeOpenAiService.chatChunkToResponsesSse(MAPPER.readTree(tool), state)
                + OciGenerativeOpenAiService.finalizeResponsesBridgeStream(state);

        assertThat(sse).contains("\"type\":\"response.reasoning_summary_text.delta\"");
        assertThat(sse).contains("\"type\":\"response.reasoning_summary_part.done\"");
        assertThat(sse).contains("\"type\":\"response.function_call_arguments.delta\"");
        assertThat(sse).contains("\"call_id\":\"call_a\"");
        assertThat(sse).contains("\"name\":\"write_file\"");
        assertThat(sse).contains("\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"");
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

    @Test
    void convertsCohereRerankRequestToOciRerankTextDetails() throws Exception {
        String payload = """
                {
                  "model":"cohere.rerank-v4.0-fast",
                  "query":"capital city",
                  "documents":[
                    "Washington, D.C. is the capital of the United States.",
                    {"title":"Nevada","text":"Carson City is the capital of Nevada."}
                  ],
                  "top_n":1,
                  "return_documents":true,
                  "max_chunks_per_doc":2,
                  "max_tokens_per_doc":256
                }
                """;

        OciGenerativeOpenAiService.RerankBridgeRequest converted =
                OciGenerativeOpenAiService.transformRerankRequestJson(
                        payload.getBytes(), "ocid1.tenancy.oc1..example");
        JsonNode root = MAPPER.readTree(converted.body());

        assertThat(root.path("input").asText()).isEqualTo("capital city");
        assertThat(root.path("compartmentId").asText()).isEqualTo("ocid1.tenancy.oc1..example");
        assertThat(root.path("servingMode").path("servingType").asText()).isEqualTo("ON_DEMAND");
        assertThat(root.path("servingMode").path("modelId").asText()).isEqualTo("cohere.rerank-v4.0-fast");
        assertThat(root.path("documents")).hasSize(2);
        assertThat(root.path("documents").get(1).asText()).isEqualTo("Carson City is the capital of Nevada.");
        assertThat(root.path("topN").asInt()).isEqualTo(1);
        assertThat(root.path("isEcho").asBoolean()).isTrue();
        assertThat(root.path("maxChunksPerDocument").asInt()).isEqualTo(2);
        assertThat(root.path("maxTokensPerDocument").asInt()).isEqualTo(256);
        assertThat(converted.returnDocuments()).isTrue();
        assertThat(converted.originalDocumentsJson()).contains("Washington, D.C.").contains("Nevada");
    }

    @Test
    void preservesNativeRerankServingMode() throws Exception {
        String payload = """
                {
                  "input":"find docs",
                  "compartmentId":"ocid1.compartment.oc1..example",
                  "servingMode":{"servingType":"DEDICATED","endpointId":"ocid1.generativeaiendpoint.oc1..example"},
                  "documents":["a","b"],
                  "topN":2
                }
                """;

        JsonNode root = MAPPER.readTree(OciGenerativeOpenAiService.transformRerankRequestJson(
                payload.getBytes(), "ocid1.tenancy.oc1..example").body());

        assertThat(root.path("servingMode").path("servingType").asText()).isEqualTo("DEDICATED");
        assertThat(root.path("servingMode").path("endpointId").asText())
                .isEqualTo("ocid1.generativeaiendpoint.oc1..example");
        assertThat(root.path("compartmentId").asText()).isEqualTo("ocid1.compartment.oc1..example");
    }

    @Test
    void doesNotKeepOriginalRerankDocumentsWhenEchoIsDisabled() throws Exception {
        String payload = """
                {
                  "model":"cohere.rerank-v4.0-fast",
                  "query":"capital city",
                  "documents":["doc zero","doc one"],
                  "return_documents":false
                }
                """;

        OciGenerativeOpenAiService.RerankBridgeRequest converted =
                OciGenerativeOpenAiService.transformRerankRequestJson(
                        payload.getBytes(), "ocid1.tenancy.oc1..example");

        assertThat(converted.returnDocuments()).isFalse();
        assertThat(converted.originalDocumentsJson()).isNull();
    }

    @Test
    void rejectsInvalidRerankRequestBoundariesBeforeCallingOci() {
        String blankDocument = """
                {
                  "model":"cohere.rerank-v4.0-fast",
                  "query":"capital city",
                  "documents":[""]
                }
                """;
        assertThatThrownBy(() -> OciGenerativeOpenAiService.transformRerankRequestJson(
                blankDocument.getBytes(), "ocid1.tenancy.oc1..example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documents[0]");

        String invalidTopN = """
                {
                  "model":"cohere.rerank-v4.0-fast",
                  "query":"capital city",
                  "documents":["doc one"],
                  "top_n":0
                }
                """;
        assertThatThrownBy(() -> OciGenerativeOpenAiService.transformRerankRequestJson(
                invalidTopN.getBytes(), "ocid1.tenancy.oc1..example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("top_n");

        String invalidMaxTokens = """
                {
                  "model":"cohere.rerank-v4.0-fast",
                  "query":"capital city",
                  "documents":["doc one"],
                  "max_tokens_per_document":0
                }
                """;
        assertThatThrownBy(() -> OciGenerativeOpenAiService.transformRerankRequestJson(
                invalidMaxTokens.getBytes(), "ocid1.tenancy.oc1..example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_tokens_per_document");
    }

    @Test
    void convertsOciRerankTextResultToCohereStyleResponse() throws Exception {
        String payload = """
                {
                  "id":"rerank-oci-1",
                  "modelId":"cohere.rerank-v4.0-fast",
                  "modelVersion":"1.0",
                  "documentRanks":[
                    {"index":1,"relevanceScore":0.91},
                    {"index":0,"relevanceScore":0.42,"document":{"text":"doc zero"}}
                  ]
                }
                """;
        String originalDocs = """
                ["doc zero", {"text":"doc one","source":"local"}]
                """;

        JsonNode root = MAPPER.readTree(OciGenerativeOpenAiService.transformRerankResponseJson(
                payload, originalDocs, true));

        assertThat(root.path("id").asText()).isEqualTo("rerank-oci-1");
        assertThat(root.path("model").asText()).isEqualTo("cohere.rerank-v4.0-fast");
        assertThat(root.path("model_version").asText()).isEqualTo("1.0");
        assertThat(root.path("results")).hasSize(2);
        assertThat(root.path("results").get(0).path("index").asInt()).isEqualTo(1);
        assertThat(root.path("results").get(0).path("relevance_score").asDouble()).isEqualTo(0.91D);
        assertThat(root.path("results").get(0).path("document").path("text").asText()).isEqualTo("doc one");
        assertThat(root.path("results").get(1).path("document").path("text").asText()).isEqualTo("doc zero");
        assertThat(root.path("meta").path("api_version").path("version").asText()).isEqualTo("2");
    }

    @Test
    void omitsRerankDocumentsWhenReturnDocumentsIsFalse() throws Exception {
        String payload = """
                {
                  "id":"rerank-oci-1",
                  "documentRanks":[
                    {"index":0,"relevanceScore":0.91,"document":{"text":"doc zero"}}
                  ]
                }
                """;

        JsonNode root = MAPPER.readTree(OciGenerativeOpenAiService.transformRerankResponseJson(
                payload, "[\"doc zero\"]", false));

        assertThat(root.path("results").get(0).has("document")).isFalse();
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
