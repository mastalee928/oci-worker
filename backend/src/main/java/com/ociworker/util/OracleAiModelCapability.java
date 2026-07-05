package com.ociworker.util;

import java.util.Locale;

/**
 * Lightweight OCI Generative AI model capability hints.
 *
 * <p>The OCI model catalog is dynamic, so this class intentionally avoids a full allowlist.
 * It only catches clear non-chat model families before they are sent to chat-style endpoints.
 */
public final class OracleAiModelCapability {

    public static final String CHAT = "chat";
    public static final String MULTI_AGENT = "multi_agent";
    public static final String EMBEDDING = "embedding";
    public static final String RERANK = "rerank";
    public static final String AUDIO = "audio";
    public static final String MODERATION = "moderation";

    private OracleAiModelCapability() {
    }

    public static String classify(String model) {
        if (model == null || model.isBlank()) {
            return CHAT;
        }
        String value = model.trim().toLowerCase(Locale.ROOT);
        if (isMultiAgent(value)) {
            return MULTI_AGENT;
        }
        if (isEmbedding(value)) {
            return EMBEDDING;
        }
        if (isRerank(value)) {
            return RERANK;
        }
        if (isAudio(value)) {
            return AUDIO;
        }
        if (isModeration(value)) {
            return MODERATION;
        }
        return CHAT;
    }

    public static String classifyAny(String... modelHints) {
        String fallback = CHAT;
        if (modelHints == null) {
            return fallback;
        }
        for (String hint : modelHints) {
            String capability = classify(hint);
            if (!CHAT.equals(capability)) {
                return capability;
            }
        }
        return fallback;
    }

    public static boolean isChatEndpointCompatible(String model) {
        String capability = classify(model);
        return CHAT.equals(capability) || MULTI_AGENT.equals(capability);
    }

    public static boolean isEmbeddingEndpointCompatible(String model) {
        if (model == null || model.isBlank()) {
            return true;
        }
        String value = model.trim().toLowerCase(Locale.ROOT);
        String capability = classify(value);
        if (EMBEDDING.equals(capability)) {
            return true;
        }
        if (MULTI_AGENT.equals(capability)
                || RERANK.equals(capability)
                || AUDIO.equals(capability)
                || MODERATION.equals(capability)) {
            return false;
        }
        return !isKnownChatGeneration(value);
    }

    public static boolean isRerankEndpointCompatible(String model) {
        if (model == null || model.isBlank()) {
            return true;
        }
        String value = model.trim().toLowerCase(Locale.ROOT);
        String capability = classify(value);
        if (RERANK.equals(capability)) {
            return true;
        }
        if (MULTI_AGENT.equals(capability)
                || EMBEDDING.equals(capability)
                || AUDIO.equals(capability)
                || MODERATION.equals(capability)) {
            return false;
        }
        return !isKnownChatGeneration(value);
    }

    public static boolean isAudioSpeechEndpointCompatible(String model) {
        if (model == null || model.isBlank()) {
            return true;
        }
        String value = model.trim().toLowerCase(Locale.ROOT);
        String capability = classify(value);
        if (AUDIO.equals(capability)) {
            return true;
        }
        if (MULTI_AGENT.equals(capability)
                || EMBEDDING.equals(capability)
                || RERANK.equals(capability)
                || MODERATION.equals(capability)) {
            return false;
        }
        return !isKnownChatGeneration(value);
    }

    public static boolean isMultiAgent(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String value = model.trim().toLowerCase(Locale.ROOT);
        return value.contains("multi-agent")
                || value.contains("multiagent")
                || value.contains("multi agent");
    }

    public static String chatEndpointMismatchMessage(String model) {
        String capability = classify(model);
        String name = model == null || model.isBlank() ? "未指定模型" : model.trim();
        return switch (capability) {
            case EMBEDDING -> "模型 " + name + " 是 Embedding 模型，不属于聊天/工具调用模型，请使用 /v1/embeddings。";
            case RERANK -> "模型 " + name + " 是 Rerank 模型，不属于聊天/工具调用模型，请使用重排序接口。";
            case AUDIO -> "模型 " + name + " 是语音/音频模型，不属于聊天/工具调用模型；xai.grok-tts 请使用音频语音接口。";
            case MODERATION -> "模型 " + name + " 是安全/审核模型，不属于聊天/工具调用模型，请使用对应审核接口。";
            default -> "模型 " + name + " 不适用于当前聊天接口。";
        };
    }

    public static String embeddingEndpointMismatchMessage(String model) {
        String capability = classify(model);
        String name = model == null || model.isBlank() ? "未指定模型" : model.trim();
        return switch (capability) {
            case CHAT, MULTI_AGENT -> "模型 " + name + " 是聊天/生成模型，不能用于 /v1/embeddings，请选择 cohere.embed-* 等 Embedding 模型。";
            case RERANK -> "模型 " + name + " 是 Rerank 模型，不能用于 /v1/embeddings，请使用重排序接口。";
            case AUDIO -> "模型 " + name + " 是语音/音频模型，不能用于 /v1/embeddings。";
            case MODERATION -> "模型 " + name + " 是安全/审核模型，不能用于 /v1/embeddings。";
            default -> "模型 " + name + " 不适用于 /v1/embeddings。";
        };
    }

    public static String rerankEndpointMismatchMessage(String model) {
        String capability = classify(model);
        String name = model == null || model.isBlank() ? "未指定模型" : model.trim();
        return switch (capability) {
            case CHAT, MULTI_AGENT -> "模型 " + name + " 是聊天/生成模型，不能用于 /v1/rerank，请选择 cohere.rerank-* 等 Rerank 模型。";
            case EMBEDDING -> "模型 " + name + " 是 Embedding 模型，不能用于 /v1/rerank，请使用 /v1/embeddings。";
            case AUDIO -> "模型 " + name + " 是语音/音频模型，不能用于 /v1/rerank。";
            case MODERATION -> "模型 " + name + " 是安全/审核模型，不能用于 /v1/rerank。";
            default -> "模型 " + name + " 不适用于 /v1/rerank。";
        };
    }

    public static String audioSpeechEndpointMismatchMessage(String model) {
        String capability = classify(model);
        String name = model == null || model.isBlank() ? "未指定模型" : model.trim();
        return switch (capability) {
            case CHAT, MULTI_AGENT -> "模型 " + name + " 是聊天/生成模型，不能用于 /v1/audio/speech，请选择 xai.grok-tts。";
            case EMBEDDING -> "模型 " + name + " 是 Embedding 模型，不能用于 /v1/audio/speech。";
            case RERANK -> "模型 " + name + " 是 Rerank 模型，不能用于 /v1/audio/speech。";
            case MODERATION -> "模型 " + name + " 是安全/审核模型，不能用于 /v1/audio/speech。";
            default -> "模型 " + name + " 不适用于 /v1/audio/speech。";
        };
    }

    private static boolean isEmbedding(String value) {
        return value.contains(".embed")
                || value.contains("-embed")
                || value.contains("embedding");
    }

    private static boolean isRerank(String value) {
        return value.contains(".rerank")
                || value.contains("-rerank")
                || value.contains("rerank");
    }

    private static boolean isAudio(String value) {
        return value.contains(".tts")
                || value.contains("-tts")
                || value.contains("grok-tts")
                || value.contains("text-to-speech")
                || value.contains("audio");
    }

    private static boolean isModeration(String value) {
        return value.contains("content-moderator")
                || value.contains("moderation")
                || value.contains("moderator")
                || value.contains("llama-guard");
    }

    private static boolean isKnownChatGeneration(String value) {
        return value.startsWith("xai.grok-")
                || value.startsWith("cohere.command")
                || value.startsWith("google.gemini")
                || value.startsWith("openai.gpt-oss")
                || value.startsWith("meta.llama-")
                || value.startsWith("mistral.");
    }
}
