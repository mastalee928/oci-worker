package com.ociworker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

final class OciGrokBridge {

    static final String GROK_3_MINI = "xai.grok-3-mini";
    static final String GROK_3_MINI_FAST = "xai.grok-3-mini-fast";

    private OciGrokBridge() {
    }

    static boolean isGrok3MiniReasoningEffortModel(String model) {
        String value = normalized(model);
        return GROK_3_MINI.equals(value) || GROK_3_MINI_FAST.equals(value);
    }

    static void normalizeReasoningEffort(ObjectNode root) {
        if (root == null) {
            return;
        }
        if (!isGrok3MiniReasoningEffortModel(firstText(root, "model"))) {
            removeReasoningControls(root);
            return;
        }

        String effort = normalizeReasoningEffortValue(root.get("reasoning_effort"));
        if (effort == null) {
            effort = normalizeReasoningEffortValue(root.get("reasoningEffort"));
        }
        JsonNode reasoning = root.get("reasoning");
        if (effort == null && reasoning instanceof ObjectNode reasoningObject) {
            effort = normalizeReasoningEffortValue(reasoningObject.get("effort"));
        }

        root.remove("reasoningEffort");
        root.remove("reasoning");
        if (effort == null) {
            root.remove("reasoning_effort");
            return;
        }
        root.put("reasoning_effort", effort);
    }

    private static void removeReasoningControls(ObjectNode root) {
        root.remove("reasoningEffort");
        root.remove("reasoning_effort");
        root.remove("reasoning");
    }

    private static String normalizeReasoningEffortValue(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return null;
        }
        String effort = value.asText("").trim().toLowerCase(Locale.ROOT);
        if ("low".equals(effort) || "high".equals(effort)) {
            return effort;
        }
        return null;
    }

    private static String firstText(ObjectNode object, String field) {
        if (object == null || field == null) {
            return null;
        }
        JsonNode value = object.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return null;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
