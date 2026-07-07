package com.ociworker.util;

import java.util.Map;
import java.util.Locale;

final class OracleAiGrokModelCatalog {

    private static final Map<String, ModelEntry> OFFICIAL_MODELS = Map.ofEntries(
            entry("xai.grok-tts", OracleAiModelCapability.AUDIO, 0L, 0L, false, false),
            entry("xai.grok-4.3", OracleAiModelCapability.CHAT, 1_000_000L, 200_000L, false, false),
            entry("xai.grok-4.20-multi-agent-0309", OracleAiModelCapability.MULTI_AGENT, 1_000_000L, 200_000L, false, false),
            entry("xai.grok-4.20-multi-agent", OracleAiModelCapability.MULTI_AGENT, 1_000_000L, 200_000L, false, false),
            entry("xai.grok-4.20-0309-reasoning", OracleAiModelCapability.CHAT, 1_000_000L, 200_000L, false, false),
            entry("xai.grok-4.20-reasoning", OracleAiModelCapability.CHAT, 1_000_000L, 200_000L, false, false),
            entry("xai.grok-4.20-0309-non-reasoning", OracleAiModelCapability.CHAT, 1_000_000L, 200_000L, false, false),
            entry("xai.grok-4.20-non-reasoning", OracleAiModelCapability.CHAT, 1_000_000L, 200_000L, false, false),
            entry("xai.grok-code-fast-1", OracleAiModelCapability.CHAT, 256_000L, 200_000L, true, false),
            entry("xai.grok-4-1-fast-reasoning", OracleAiModelCapability.CHAT, 2_000_000L, 200_000L, true, false),
            entry("xai.grok-4-1-fast-non-reasoning", OracleAiModelCapability.CHAT, 2_000_000L, 200_000L, true, false),
            entry("xai.grok-4-fast-reasoning", OracleAiModelCapability.CHAT, 2_000_000L, 200_000L, true, false),
            entry("xai.grok-4-fast-non-reasoning", OracleAiModelCapability.CHAT, 2_000_000L, 200_000L, true, false),
            entry("xai.grok-4", OracleAiModelCapability.CHAT, 128_000L, 200_000L, true, false),
            entry("xai.grok-3", OracleAiModelCapability.CHAT, 131_072L, 100_000L, true, false),
            entry("xai.grok-3-fast", OracleAiModelCapability.CHAT, 131_072L, 100_000L, true, false),
            entry("xai.grok-3-mini", OracleAiModelCapability.CHAT, 131_072L, 100_000L, true, true),
            entry("xai.grok-3-mini-fast", OracleAiModelCapability.CHAT, 131_072L, 100_000L, true, true)
    );

    private OracleAiGrokModelCatalog() {
    }

    static String classify(String model) {
        String value = normalized(model);
        if (!isGrokModel(value)) {
            return null;
        }
        ModelEntry entry = OFFICIAL_MODELS.get(value);
        if (entry != null) {
            return entry.capability();
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
        ModelEntry entry = OFFICIAL_MODELS.get(value);
        if (entry != null) {
            return entry.contextLimit();
        }
        return 0L;
    }

    static long documentedTpmLimit(String model) {
        String value = normalized(model);
        ModelEntry entry = OFFICIAL_MODELS.get(value);
        if (entry != null) {
            return entry.tpmLimit();
        }
        return 0L;
    }

    static boolean isOfficialModel(String model) {
        return OFFICIAL_MODELS.containsKey(normalized(model));
    }

    static boolean isDeprecated(String model) {
        ModelEntry entry = OFFICIAL_MODELS.get(normalized(model));
        return entry != null && entry.deprecated();
    }

    static boolean supportsReasoningEffort(String model) {
        ModelEntry entry = OFFICIAL_MODELS.get(normalized(model));
        return entry != null && entry.supportsReasoningEffort();
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

    private static Map.Entry<String, ModelEntry> entry(
            String id,
            String capability,
            long contextLimit,
            long tpmLimit,
            boolean deprecated,
            boolean supportsReasoningEffort) {
        return Map.entry(id, new ModelEntry(capability, contextLimit, tpmLimit, deprecated, supportsReasoningEffort));
    }

    private record ModelEntry(
            String capability,
            long contextLimit,
            long tpmLimit,
            boolean deprecated,
            boolean supportsReasoningEffort) {
    }
}
