package com.ociworker.util;

import java.util.Locale;

final class OracleAiGrokModelCatalog {

    private OracleAiGrokModelCatalog() {
    }

    static String classify(String model) {
        String value = normalized(model);
        if (!isGrokModel(value)) {
            return null;
        }
        if (isVoiceAgent(value)) {
            return OracleAiModelCapability.PENDING;
        }
        if (isMultiAgent(value)) {
            return OracleAiModelCapability.MULTI_AGENT;
        }
        if (isAudio(value)) {
            return OracleAiModelCapability.AUDIO;
        }
        return OracleAiModelCapability.CHAT;
    }

    static long documentedContextLimit(String model) {
        String value = normalized(model);
        if (value.startsWith("xai.grok-4.3")
                || value.startsWith("xai.grok-4.20")) {
            return 1_000_000L;
        }
        if (value.startsWith("xai.grok-4-1-fast")
                || value.startsWith("xai.grok-4-fast")) {
            return 2_000_000L;
        }
        if (value.startsWith("xai.grok-code-fast")) {
            return 256_000L;
        }
        if (value.startsWith("xai.grok-4")) {
            return 128_000L;
        }
        if (value.startsWith("xai.grok-3")) {
            return 131_072L;
        }
        return 0L;
    }

    static long documentedTpmLimit(String model) {
        String value = normalized(model);
        if (value.startsWith("xai.grok-4.3")
                || value.startsWith("xai.grok-4.20")) {
            return 200_000L;
        }
        if (value.startsWith("xai.grok-4")
                || value.startsWith("xai.grok-code-fast")) {
            return 200_000L;
        }
        if (value.startsWith("xai.grok-3")) {
            return 100_000L;
        }
        return 0L;
    }

    static boolean isGrokModel(String model) {
        return normalized(model).startsWith("xai.grok-");
    }

    private static boolean isMultiAgent(String value) {
        return value.contains("multi-agent")
                || value.contains("multiagent")
                || value.contains("multi agent");
    }

    private static boolean isAudio(String value) {
        return value.contains(".tts")
                || value.contains("-tts")
                || value.contains("grok-tts")
                || value.contains("text-to-speech")
                || value.contains("audio");
    }

    private static boolean isVoiceAgent(String value) {
        return value.contains("voice-agent") || value.contains("voiceagent") || value.contains("voice agent");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
