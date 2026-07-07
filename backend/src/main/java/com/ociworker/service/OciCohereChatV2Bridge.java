package com.ociworker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ociworker.exception.OciException;
import com.ociworker.util.CommonUtils;
import com.oracle.bmc.generativeaiinference.model.BaseChatResponse;
import com.oracle.bmc.generativeaiinference.model.ChatResult;
import com.oracle.bmc.generativeaiinference.model.CitationOptionsV2;
import com.oracle.bmc.generativeaiinference.model.CohereAssistantMessageV2;
import com.oracle.bmc.generativeaiinference.model.CohereChatRequestV2;
import com.oracle.bmc.generativeaiinference.model.CohereChatResponseV2;
import com.oracle.bmc.generativeaiinference.model.CohereContentV2;
import com.oracle.bmc.generativeaiinference.model.CohereDocumentContentV2;
import com.oracle.bmc.generativeaiinference.model.CohereImageContentV2;
import com.oracle.bmc.generativeaiinference.model.CohereImageUrlV2;
import com.oracle.bmc.generativeaiinference.model.CohereMessageV2;
import com.oracle.bmc.generativeaiinference.model.CohereResponseFormat;
import com.oracle.bmc.generativeaiinference.model.CohereResponseJsonFormat;
import com.oracle.bmc.generativeaiinference.model.CohereResponseTextFormat;
import com.oracle.bmc.generativeaiinference.model.CohereSystemMessageV2;
import com.oracle.bmc.generativeaiinference.model.CohereTextContentV2;
import com.oracle.bmc.generativeaiinference.model.CohereThinkingContentV2;
import com.oracle.bmc.generativeaiinference.model.CohereThinkingV2;
import com.oracle.bmc.generativeaiinference.model.CohereToolCallV2;
import com.oracle.bmc.generativeaiinference.model.CohereToolMessageV2;
import com.oracle.bmc.generativeaiinference.model.CohereToolV2;
import com.oracle.bmc.generativeaiinference.model.CohereUserMessageV2;
import com.oracle.bmc.generativeaiinference.model.Function;
import com.oracle.bmc.generativeaiinference.model.ImageUrl;
import com.oracle.bmc.generativeaiinference.model.StreamOptions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class OciCohereChatV2Bridge {

    static final int COMMAND_A_REASONING_ON_DEMAND_MAX_TOKENS = 4000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OciCohereChatV2Bridge() {
    }

    static boolean isCommandAReasoningModel(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String value = model.trim().toLowerCase(Locale.ROOT);
        return value.equals("cohere.command-a-reasoning");
    }

    static boolean shouldUseNativeChat(String model, byte[] body) {
        return isCommandAReasoningModel(model) && canUseNativeChat(body);
    }

    static int capOnDemandMaxTokens(String model, int value) {
        if (isCommandAReasoningModel(model) && value > COMMAND_A_REASONING_ON_DEMAND_MAX_TOKENS) {
            return COMMAND_A_REASONING_ON_DEMAND_MAX_TOKENS;
        }
        return value;
    }

    static boolean canUseNativeChat(byte[] input) {
        if (input == null || input.length == 0) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(input);
            if (!(root instanceof ObjectNode object)) {
                return false;
            }
            JsonNode messages = object.get("messages");
            if (messages == null || !messages.isArray() || messages.isEmpty()) {
                return false;
            }
            boolean hasUsableMessage = false;
            for (JsonNode message : messages) {
                if (!(message instanceof ObjectNode messageObject)) {
                    return false;
                }
                String role = OciGenerativeOpenAiService.normalizeChatRole(
                        OciGenerativeOpenAiService.textOrNull(messageObject, "role"));
                JsonNode nativeContent = OciGenerativeOpenAiService.nativeMessageContent(messageObject);
                if (hasNativePayload(messageObject)) {
                    hasUsableMessage = true;
                }
                if (!isNativeContent(nativeContent)) {
                    return false;
                }
                if (!"user".equals(role) && hasNativeMediaContent(nativeContent)) {
                    return false;
                }
            }
            return hasUsableMessage;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean hasNativePayload(ObjectNode message) {
        if (message == null) {
            return false;
        }
        JsonNode toolCalls = message.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
            return true;
        }
        return hasNativePayloadContent(OciGenerativeOpenAiService.nativeMessageContent(message));
    }

    private static boolean hasNativePayloadContent(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return false;
        }
        if (content.isTextual()) {
            return !content.asText().isBlank();
        }
        if (content.isNumber() || content.isBoolean()) {
            return true;
        }
        if (content instanceof ObjectNode object) {
            return hasNativePayloadObject(object);
        }
        if (!content.isArray()) {
            return false;
        }
        for (JsonNode part : content) {
            if (hasNativePayloadContent(part)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNativePayloadObject(ObjectNode object) {
        if (object == null || object.isEmpty()) {
            return false;
        }
        String type = OciGenerativeOpenAiService.nativeContentObjectType(object);
        if (OciGenerativeOpenAiService.isTextLikeNativeContentObject(object, type)) {
            String text = OciGenerativeOpenAiService.chatTextPartText(object);
            return text != null && !text.isBlank();
        }
        if (OciGenerativeOpenAiService.isImageLikeNativeContentObject(object, type)) {
            return OciGenerativeOpenAiService.nativeImageUrl(object) != null;
        }
        if (OciGenerativeOpenAiService.isDocumentLikeNativeContentObject(object, type)) {
            return OciGenerativeOpenAiService.firstExisting(object, "document", "file", "source") != null;
        }
        if (OciGenerativeOpenAiService.isAudioLikeNativeContentObject(object, type)
                || OciGenerativeOpenAiService.isVideoLikeNativeContentObject(object, type)) {
            return false;
        }
        return !OciGenerativeOpenAiService.isUnsupportedNativeContentObject(object);
    }

    private static boolean isNativeContent(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return true;
        }
        if (content.isTextual() || content.isNumber() || content.isBoolean()) {
            return true;
        }
        if (content instanceof ObjectNode object) {
            return isNativeContentObject(object);
        }
        if (!content.isArray()) {
            return false;
        }
        for (JsonNode part : content) {
            if (!isNativeContent(part)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNativeContentObject(ObjectNode object) {
        if (object == null) {
            return false;
        }
        String type = OciGenerativeOpenAiService.nativeContentObjectType(object);
        if (OciGenerativeOpenAiService.isAudioLikeNativeContentObject(object, type)
                || OciGenerativeOpenAiService.isVideoLikeNativeContentObject(object, type)) {
            return false;
        }
        if (OciGenerativeOpenAiService.isTextLikeNativeContentObject(object, type)
                || OciGenerativeOpenAiService.isImageLikeNativeContentObject(object, type)
                || OciGenerativeOpenAiService.isDocumentLikeNativeContentObject(object, type)) {
            return true;
        }
        return !OciGenerativeOpenAiService.isUnsupportedNativeContentObject(object);
    }

    private static boolean hasNativeMediaContent(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return false;
        }
        if (content instanceof ObjectNode object) {
            String type = OciGenerativeOpenAiService.nativeContentObjectType(object);
            return OciGenerativeOpenAiService.isImageLikeNativeContentObject(object, type)
                    || OciGenerativeOpenAiService.isDocumentLikeNativeContentObject(object, type);
        }
        if (content.isArray()) {
            for (JsonNode part : content) {
                if (hasNativeMediaContent(part)) {
                    return true;
                }
            }
        }
        return false;
    }

    static CohereChatRequestV2 toNativeChatRequest(ObjectNode input) {
        CohereChatRequestV2.Builder builder = CohereChatRequestV2.builder()
                .messages(toMessages(input.get("messages")))
                .isStream(false);
        Integer maxTokens = OciGenerativeOpenAiService.firstInteger(input, "max_tokens", "maxTokens");
        if (maxTokens != null && maxTokens > 0) {
            builder.maxTokens(Math.min(maxTokens, COMMAND_A_REASONING_ON_DEMAND_MAX_TOKENS));
        }
        Double temperature = OciGenerativeOpenAiService.firstDouble(input, "temperature");
        if (temperature != null) {
            builder.temperature(temperature);
        }
        Integer topK = OciGenerativeOpenAiService.firstInteger(input, "top_k", "topK");
        if (topK != null) {
            builder.topK(topK);
        }
        Double topP = OciGenerativeOpenAiService.firstDouble(input, "top_p", "topP");
        if (topP != null) {
            builder.topP(topP);
        }
        Double frequencyPenalty = OciGenerativeOpenAiService.firstDouble(input, "frequency_penalty", "frequencyPenalty");
        if (frequencyPenalty != null) {
            builder.frequencyPenalty(frequencyPenalty);
        }
        Double presencePenalty = OciGenerativeOpenAiService.firstDouble(input, "presence_penalty", "presencePenalty");
        if (presencePenalty != null) {
            builder.presencePenalty(presencePenalty);
        }
        Integer seed = OciGenerativeOpenAiService.firstInteger(input, "seed");
        if (seed != null) {
            builder.seed(seed);
        }
        List<String> stop = OciGenerativeOpenAiService.stringList(input.get("stop"));
        if (!stop.isEmpty()) {
            builder.stopSequences(stop);
        }
        List<Object> documents = objectList(OciGenerativeOpenAiService.firstExisting(input, "documents", "document"));
        if (!documents.isEmpty()) {
            builder.documents(documents);
        }
        CitationOptionsV2 citationOptions = toCitationOptions(
                OciGenerativeOpenAiService.firstExisting(input, "citation_options", "citationOptions"));
        if (citationOptions != null) {
            builder.citationOptions(citationOptions);
        }
        CohereThinkingV2 thinking = toThinking(input.get("thinking"));
        if (thinking != null) {
            builder.thinking(thinking);
        }
        CohereResponseFormat responseFormat = toResponseFormat(
                OciGenerativeOpenAiService.firstExisting(input, "response_format", "responseFormat"));
        if (responseFormat != null) {
            builder.responseFormat(responseFormat);
        }
        CohereChatRequestV2.SafetyMode safetyMode = toSafetyMode(
                OciGenerativeOpenAiService.firstText(input, "safety_mode", "safetyMode"));
        if (safetyMode != null) {
            builder.safetyMode(safetyMode);
        }
        Boolean strictTools = OciGenerativeOpenAiService.firstBoolean(
                input, "strict_tools", "strictTools", "is_strict_tools_enabled", "isStrictToolsEnabled");
        if (strictTools != null) {
            builder.isStrictToolsEnabled(strictTools);
        }
        Boolean logProbs = OciGenerativeOpenAiService.firstBoolean(
                input, "logprobs", "is_log_probs_enabled", "isLogProbsEnabled");
        if (logProbs != null) {
            builder.isLogProbsEnabled(logProbs);
        }
        Boolean searchQueriesOnly = OciGenerativeOpenAiService.firstBoolean(
                input, "search_queries_only", "searchQueriesOnly", "is_search_queries_only", "isSearchQueriesOnly");
        if (searchQueriesOnly != null) {
            builder.isSearchQueriesOnly(searchQueriesOnly);
        }
        StreamOptions streamOptions = toStreamOptions(
                OciGenerativeOpenAiService.firstExisting(input, "stream_options", "streamOptions"));
        if (streamOptions != null) {
            builder.streamOptions(streamOptions);
        }
        Integer priority = OciGenerativeOpenAiService.firstInteger(input, "priority");
        if (priority != null) {
            builder.priority(priority);
        }
        Boolean rawPrompting = OciGenerativeOpenAiService.firstBoolean(
                input, "raw_prompting", "is_raw_prompting", "isRawPrompting");
        if (rawPrompting != null) {
            builder.isRawPrompting(rawPrompting);
        }
        List<CohereToolV2> tools = toToolDefinitions(input.get("tools"));
        if (!tools.isEmpty()) {
            builder.tools(tools);
        }
        CohereChatRequestV2.ToolsChoice toolsChoice = toToolsChoice(input.get("tool_choice"));
        if (toolsChoice != null) {
            builder.toolsChoice(toolsChoice);
        }
        return builder.build();
    }

    private static CohereThinkingV2 toThinking(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        CohereThinkingV2.Builder builder = CohereThinkingV2.builder();
        boolean hasValue = false;
        if (node.isBoolean()) {
            builder.type(node.asBoolean() ? CohereThinkingV2.Type.Enabled : CohereThinkingV2.Type.Disabled);
            hasValue = true;
        } else if (node.isTextual()) {
            String value = node.asText("").trim().toLowerCase(Locale.ROOT);
            if ("disabled".equals(value) || "false".equals(value) || "off".equals(value)) {
                builder.type(CohereThinkingV2.Type.Disabled);
                hasValue = true;
            } else if ("enabled".equals(value) || "true".equals(value) || "on".equals(value)) {
                builder.type(CohereThinkingV2.Type.Enabled);
                hasValue = true;
            }
        } else if (node instanceof ObjectNode object) {
            String type = OciGenerativeOpenAiService.firstText(object, "type");
            if (type != null && !type.isBlank()) {
                String value = type.trim().toLowerCase(Locale.ROOT);
                if ("disabled".equals(value) || "false".equals(value) || "off".equals(value)) {
                    builder.type(CohereThinkingV2.Type.Disabled);
                    hasValue = true;
                } else if ("enabled".equals(value) || "true".equals(value) || "on".equals(value)) {
                    builder.type(CohereThinkingV2.Type.Enabled);
                    hasValue = true;
                }
            }
            Integer budget = OciGenerativeOpenAiService.firstInteger(object, "token_budget", "tokenBudget");
            if (budget != null && budget > 0) {
                builder.tokenBudget(budget);
                hasValue = true;
            }
        }
        return hasValue ? builder.build() : null;
    }

    private static List<Object> objectList(JsonNode node) {
        List<Object> out = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return out;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item != null && !item.isNull() && !item.isMissingNode()) {
                    out.add(MAPPER.convertValue(item, Object.class));
                }
            }
            return out;
        }
        out.add(MAPPER.convertValue(node, Object.class));
        return out;
    }

    private static CitationOptionsV2 toCitationOptions(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String mode = null;
        if (node.isTextual()) {
            mode = node.asText("");
        } else if (node instanceof ObjectNode object) {
            mode = OciGenerativeOpenAiService.firstText(object, "mode");
        }
        if (mode == null || mode.isBlank()) {
            return null;
        }
        CitationOptionsV2.Mode value = switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "fast" -> CitationOptionsV2.Mode.Fast;
            case "accurate" -> CitationOptionsV2.Mode.Accurate;
            case "off", "none", "disabled" -> CitationOptionsV2.Mode.Off;
            default -> null;
        };
        return value == null ? null : CitationOptionsV2.builder().mode(value).build();
    }

    private static CohereResponseFormat toResponseFormat(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String type = null;
        JsonNode schema = null;
        if (node.isTextual()) {
            type = node.asText("");
        } else if (node instanceof ObjectNode object) {
            type = OciGenerativeOpenAiService.firstText(object, "type");
            schema = OciGenerativeOpenAiService.firstExisting(object, "schema", "json_schema", "jsonSchema");
            if (schema instanceof ObjectNode schemaObject) {
                JsonNode nestedSchema = OciGenerativeOpenAiService.firstExisting(schemaObject, "schema");
                schema = nestedSchema == null ? schema : nestedSchema;
            }
        }
        if (type == null || type.isBlank()) {
            return null;
        }
        return switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "text" -> CohereResponseTextFormat.builder().build();
            case "json", "json_object", "json_schema" -> {
                CohereResponseJsonFormat.Builder builder = CohereResponseJsonFormat.builder();
                if (schema != null && !schema.isNull() && !schema.isMissingNode()) {
                    builder.schema(MAPPER.convertValue(schema, Object.class));
                }
                yield builder.build();
            }
            default -> null;
        };
    }

    private static StreamOptions toStreamOptions(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node instanceof ObjectNode object) {
            Boolean includeUsage = OciGenerativeOpenAiService.firstBoolean(object, "include_usage", "isIncludeUsage");
            return includeUsage == null ? null : StreamOptions.builder().isIncludeUsage(includeUsage).build();
        }
        return null;
    }

    private static CohereChatRequestV2.SafetyMode toSafetyMode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "contextual" -> CohereChatRequestV2.SafetyMode.Contextual;
            case "strict" -> CohereChatRequestV2.SafetyMode.Strict;
            case "off", "none", "disabled" -> CohereChatRequestV2.SafetyMode.Off;
            default -> null;
        };
    }

    private static CohereChatRequestV2.ToolsChoice toToolsChoice(JsonNode toolChoice) {
        if (toolChoice == null || toolChoice.isNull() || toolChoice.isMissingNode()) {
            return null;
        }
        String value = null;
        if (toolChoice.isTextual()) {
            value = toolChoice.asText("");
        } else if (toolChoice instanceof ObjectNode object) {
            value = OciGenerativeOpenAiService.firstText(object, "type");
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "none" -> CohereChatRequestV2.ToolsChoice.None;
            case "required", "any" -> CohereChatRequestV2.ToolsChoice.Required;
            default -> null;
        };
    }

    private static List<CohereMessageV2> toMessages(JsonNode messagesNode) {
        List<CohereMessageV2> out = new ArrayList<>();
        if (messagesNode != null && messagesNode.isArray()) {
            for (JsonNode item : messagesNode) {
                if (!(item instanceof ObjectNode message)) {
                    continue;
                }
                String role = OciGenerativeOpenAiService.normalizeChatRole(
                        OciGenerativeOpenAiService.textOrNull(message, "role"));
                List<CohereContentV2> content = toContent(OciGenerativeOpenAiService.nativeMessageContent(message));
                switch (role) {
                    case "system", "developer" -> {
                        if (!hasUsableContent(content)) {
                            continue;
                        }
                        out.add(CohereSystemMessageV2.builder().content(content).build());
                    }
                    case "assistant" -> {
                        List<CohereToolCallV2> toolCalls = toToolCalls(message.get("tool_calls"));
                        if (!hasUsableContent(content) && toolCalls.isEmpty()) {
                            continue;
                        }
                        CohereAssistantMessageV2.Builder builder = CohereAssistantMessageV2.builder().content(content);
                        String reasoning = OciGenerativeOpenAiService.textOrNull(message, "reasoning_content");
                        if (reasoning != null && !reasoning.isBlank()) {
                            builder.toolPlan(reasoning);
                        }
                        if (!toolCalls.isEmpty()) {
                            builder.toolCalls(toolCalls);
                        }
                        out.add(builder.build());
                    }
                    case "tool" -> {
                        String toolCallId = OciGenerativeOpenAiService.textOrNull(message, "tool_call_id");
                        if (!hasUsableContent(content)) {
                            if (toolCallId == null || toolCallId.isBlank()) {
                                continue;
                            }
                            content = List.of(CohereTextContentV2.builder().text("null").build());
                        }
                        CohereToolMessageV2.Builder builder = CohereToolMessageV2.builder().content(content);
                        if (toolCallId != null && !toolCallId.isBlank()) {
                            builder.toolCallId(toolCallId);
                        }
                        out.add(builder.build());
                    }
                    default -> {
                        if (!hasUsableContent(content)) {
                            continue;
                        }
                        out.add(CohereUserMessageV2.builder().content(content).build());
                    }
                }
            }
        }
        if (out.isEmpty()) {
            out.add(CohereUserMessageV2.builder()
                    .content(List.of(CohereTextContentV2.builder().text(" ").build()))
                    .build());
        }
        return out;
    }

    private static List<CohereContentV2> toContent(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return List.of();
        }
        if (content.isTextual()) {
            String text = content.asText();
            return text == null || text.isBlank()
                    ? List.of()
                    : List.of(CohereTextContentV2.builder().text(text).build());
        }
        if (content.isNumber() || content.isBoolean()) {
            return List.of(CohereTextContentV2.builder().text(content.asText()).build());
        }
        if (content instanceof ObjectNode object) {
            CohereContentV2 part = toContentPart(object);
            return hasUsableContent(part) ? List.of(part) : List.of();
        }
        if (!content.isArray()) {
            return List.of(CohereTextContentV2.builder().text(content.toString()).build());
        }
        List<CohereContentV2> out = new ArrayList<>();
        for (JsonNode part : content) {
            if (part == null || part.isNull()) {
                continue;
            }
            CohereContentV2 converted = toContentPart(part);
            if (hasUsableContent(converted)) {
                out.add(converted);
            }
        }
        return out;
    }

    private static CohereContentV2 toContentPart(JsonNode part) {
        if (part == null || part.isNull()) {
            return CohereTextContentV2.builder().text("").build();
        }
        if (part.isTextual() || part.isNumber() || part.isBoolean()) {
            return CohereTextContentV2.builder().text(part.asText()).build();
        }
        if (!(part instanceof ObjectNode object)) {
            return CohereTextContentV2.builder().text(part.toString()).build();
        }
        if (OciGenerativeOpenAiService.isImageLikeNativeContentObject(
                object, OciGenerativeOpenAiService.nativeContentObjectType(object))) {
            CohereImageUrlV2 imageUrl = imageUrl(object);
            if (imageUrl != null) {
                return CohereImageContentV2.builder().imageUrl(imageUrl).build();
            }
        }
        String type = OciGenerativeOpenAiService.nativeContentObjectType(object);
        if (OciGenerativeOpenAiService.isDocumentLikeNativeContentObject(object, type)) {
            JsonNode document = OciGenerativeOpenAiService.firstExisting(object, "document", "file", "source");
            if (document != null && !document.isNull() && !document.isMissingNode()) {
                return CohereDocumentContentV2.builder().document(MAPPER.convertValue(document, Object.class)).build();
            }
        }
        String text = OciGenerativeOpenAiService.chatTextPartText(object);
        return CohereTextContentV2.builder().text(text == null ? object.toString() : text).build();
    }

    private static CohereImageUrlV2 imageUrl(ObjectNode object) {
        ImageUrl imageUrl = OciGenerativeOpenAiService.nativeImageUrl(object);
        if (imageUrl == null || imageUrl.getUrl() == null || imageUrl.getUrl().isBlank()) {
            return null;
        }
        CohereImageUrlV2.Builder builder = CohereImageUrlV2.builder().url(imageUrl.getUrl());
        if (imageUrl.getDetail() != null) {
            builder.detail(CohereImageUrlV2.Detail.create(imageUrl.getDetail().getValue()));
        }
        return builder.build();
    }

    private static boolean hasUsableContent(List<CohereContentV2> content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        for (CohereContentV2 item : content) {
            if (hasUsableContent(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUsableContent(CohereContentV2 content) {
        if (content == null) {
            return false;
        }
        if (content instanceof CohereTextContentV2 textContent) {
            String text = textContent.getText();
            return text != null && !text.isBlank();
        }
        if (content instanceof CohereThinkingContentV2 thinkingContent) {
            String thinking = thinkingContent.getThinking();
            return thinking != null && !thinking.isBlank();
        }
        if (content instanceof CohereImageContentV2 imageContent) {
            return imageContent.getImageUrl() != null
                    && imageContent.getImageUrl().getUrl() != null
                    && !imageContent.getImageUrl().getUrl().isBlank();
        }
        return true;
    }

    private static List<CohereToolV2> toToolDefinitions(JsonNode toolsNode) {
        List<CohereToolV2> out = new ArrayList<>();
        if (toolsNode == null || !toolsNode.isArray()) {
            return out;
        }
        for (JsonNode item : toolsNode) {
            if (!(item instanceof ObjectNode tool)) {
                continue;
            }
            JsonNode fnNode = tool.get("function");
            ObjectNode fn = fnNode instanceof ObjectNode functionObject ? functionObject : tool;
            String type = OciGenerativeOpenAiService.firstNonBlank(
                    OciGenerativeOpenAiService.textOrNull(tool, "type"), "function");
            if (!"function".equalsIgnoreCase(type)) {
                continue;
            }
            String name = OciGenerativeOpenAiService.textOrNull(fn, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            Function.Builder function = Function.builder().name(name);
            String description = OciGenerativeOpenAiService.textOrNull(fn, "description");
            if (description != null && !description.isBlank()) {
                function.description(description);
            }
            JsonNode parameters = fn.get("parameters");
            if (parameters != null && !parameters.isNull() && !parameters.isMissingNode()) {
                JsonNode sanitized = OciGenerativeOpenAiService.sanitizeOciToolParameters(parameters);
                function.parameters(MAPPER.convertValue(sanitized == null ? parameters : sanitized, Object.class));
            }
            out.add(CohereToolV2.builder()
                    .type(CohereToolV2.Type.Function)
                    .function(function.build())
                    .build());
        }
        return out;
    }

    private static List<CohereToolCallV2> toToolCalls(JsonNode toolCallsNode) {
        List<CohereToolCallV2> out = new ArrayList<>();
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return out;
        }
        for (JsonNode item : toolCallsNode) {
            if (!(item instanceof ObjectNode call)) {
                continue;
            }
            JsonNode fnNode = call.get("function");
            ObjectNode fn = fnNode instanceof ObjectNode functionObject ? functionObject : MAPPER.createObjectNode();
            String name = OciGenerativeOpenAiService.firstNonBlank(
                    OciGenerativeOpenAiService.textOrNull(fn, "name"),
                    OciGenerativeOpenAiService.textOrNull(call, "name"),
                    "tool");
            String arguments = OciGenerativeOpenAiService.firstNonBlank(
                    OciGenerativeOpenAiService.textOrNull(fn, "arguments"),
                    OciGenerativeOpenAiService.textOrNull(call, "arguments"),
                    "{}");
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", name);
            function.put("arguments", arguments);
            out.add(CohereToolCallV2.builder()
                    .id(OciGenerativeOpenAiService.firstNonBlank(
                            OciGenerativeOpenAiService.textOrNull(call, "id"), "call_" + CommonUtils.generateId()))
                    .type(CohereToolCallV2.Type.Function)
                    .function(function)
                    .build());
        }
        return out;
    }

    static String nativeResultToOpenAiJson(ChatResult result, String modelHint) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "chatcmpl-" + CommonUtils.generateId());
        root.put("object", "chat.completion");
        root.put("created", Instant.now().getEpochSecond());
        root.put("model", OciGenerativeOpenAiService.firstNonBlank(result == null ? null : result.getModelId(), modelHint, ""));
        BaseChatResponse response = result == null ? null : result.getChatResponse();
        if (!(response instanceof CohereChatResponseV2 cohere)) {
            throw new OciException("Cohere V2 Chat 未返回可转换的对话结果");
        }
        if (cohere.getErrorMessage() != null && !cohere.getErrorMessage().isBlank()) {
            throw new OciException("Cohere V2 Chat 返回错误: " + cohere.getErrorMessage());
        }
        CohereAssistantMessageV2 message = cohere.getMessage();
        if (message == null) {
            throw new OciException("Cohere V2 Chat 未返回 message");
        }
        ObjectNode openAiMessage = messageToOpenAiMessage(message);
        if (!OciGenerativeOpenAiService.hasVisibleChatCompletionMessage(openAiMessage)) {
            throw new OciException("Cohere V2 Chat 返回空内容");
        }
        ArrayNode choices = MAPPER.createArrayNode();
        ObjectNode choice = MAPPER.createObjectNode();
        choice.put("index", 0);
        choice.set("message", openAiMessage);
        boolean hasToolCalls = openAiMessage.path("tool_calls").isArray() && !openAiMessage.path("tool_calls").isEmpty();
        choice.put("finish_reason", OciGenerativeOpenAiService.normalizeNativeFinishReason(
                cohere.getFinishReason() == null ? null : cohere.getFinishReason().getValue(),
                hasToolCalls));
        choices.add(choice);
        root.set("choices", choices);
        root.set("usage", OciGenerativeOpenAiService.nativeUsageToOpenAiUsage(cohere.getUsage()));
        return MAPPER.writeValueAsString(root);
    }

    private static ObjectNode messageToOpenAiMessage(CohereAssistantMessageV2 message) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("role", "assistant");
        String text = contentText(message == null ? null : message.getContent(), false);
        String thinking = contentText(message == null ? null : message.getContent(), true);
        List<CohereToolCallV2> toolCalls = message == null ? null : message.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            out.putNull("content");
        } else {
            out.put("content", text == null ? "" : text);
        }
        if (thinking != null && !thinking.isBlank()) {
            out.put("reasoning_content", thinking);
        } else if (message != null && message.getToolPlan() != null && !message.getToolPlan().isBlank()) {
            out.put("reasoning_content", message.getToolPlan());
        }
        ArrayNode calls = toolCallsToOpenAi(toolCalls);
        if (!calls.isEmpty()) {
            out.set("tool_calls", calls);
        }
        return out;
    }

    private static String contentText(List<CohereContentV2> content, boolean thinkingOnly) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CohereContentV2 item : content) {
            String text = null;
            if (thinkingOnly) {
                if (item instanceof CohereThinkingContentV2 thinking) {
                    text = thinking.getThinking();
                }
            } else if (item instanceof CohereTextContentV2 textContent) {
                text = textContent.getText();
            }
            if (text == null || text.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text);
        }
        return sb.toString();
    }

    private static ArrayNode toolCallsToOpenAi(List<CohereToolCallV2> toolCalls) {
        ArrayNode calls = MAPPER.createArrayNode();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return calls;
        }
        for (CohereToolCallV2 toolCall : toolCalls) {
            if (toolCall == null || toolCall.getType() != CohereToolCallV2.Type.Function) {
                continue;
            }
            ObjectNode call = MAPPER.createObjectNode();
            call.put("id", OciGenerativeOpenAiService.firstNonBlank(toolCall.getId(), "call_" + CommonUtils.generateId()));
            call.put("type", "function");
            ObjectNode fn = MAPPER.createObjectNode();
            JsonNode function = MAPPER.convertValue(toolCall.getFunction(), JsonNode.class);
            String name = function instanceof ObjectNode object
                    ? OciGenerativeOpenAiService.firstNonBlank(
                    OciGenerativeOpenAiService.firstText(object, "name"),
                    OciGenerativeOpenAiService.firstText(object, "functionName"),
                    "tool")
                    : "tool";
            JsonNode argumentsNode = function instanceof ObjectNode object
                    ? OciGenerativeOpenAiService.firstExisting(object, "arguments", "parameters")
                    : null;
            String arguments;
            if (argumentsNode == null || argumentsNode.isNull() || argumentsNode.isMissingNode()) {
                arguments = "{}";
            } else if (argumentsNode.isTextual()) {
                arguments = OciGenerativeOpenAiService.firstNonBlank(argumentsNode.asText(), "{}");
            } else {
                try {
                    arguments = MAPPER.writeValueAsString(argumentsNode);
                } catch (Exception ignored) {
                    arguments = "{}";
                }
            }
            fn.put("name", name);
            fn.put("arguments", arguments);
            call.set("function", fn);
            calls.add(call);
        }
        return calls;
    }
}
