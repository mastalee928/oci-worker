package com.ociworker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

final class OciMetaLlamaBridge {

    static final int ON_DEMAND_MAX_TOKENS = 4000;

    static final String LLAMA_33_70B = "meta.llama-3.3-70b-instruct";
    static final String LLAMA_33_70B_FP8 = "meta.llama-3.3-70b-instruct-fp8-dynamic";
    static final String LLAMA_4_SCOUT = "meta.llama-4-scout-17b-16e-instruct";
    static final String LLAMA_4_MAVERICK = "meta.llama-4-maverick-17b-128e-instruct-fp8";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OciMetaLlamaBridge() {
    }

    static boolean isMetaLlamaModel(String model) {
        return normalized(model).startsWith("meta.llama-");
    }

    static boolean isOfficialModel(String model) {
        String value = normalized(model);
        return LLAMA_33_70B.equals(value)
                || LLAMA_33_70B_FP8.equals(value)
                || LLAMA_4_SCOUT.equals(value)
                || LLAMA_4_MAVERICK.equals(value);
    }

    static boolean isTextOnlyModel(String model) {
        String value = normalized(model);
        return LLAMA_33_70B.equals(value) || LLAMA_33_70B_FP8.equals(value);
    }

    static boolean isMultimodalModel(String model) {
        String value = normalized(model);
        return LLAMA_4_SCOUT.equals(value) || LLAMA_4_MAVERICK.equals(value) || value.contains("llama-4-");
    }

    static int capOnDemandMaxTokens(String model, int maxTokens, JsonNode servingMode) {
        if (!hasDocumentedOnDemandOutputCap(model)) {
            return maxTokens;
        }
        if (isDedicatedServingMode(servingMode)) {
            return maxTokens;
        }
        return maxTokens > ON_DEMAND_MAX_TOKENS ? ON_DEMAND_MAX_TOKENS : maxTokens;
    }

    static boolean hasDocumentedOnDemandOutputCap(String model) {
        String value = normalized(model);
        return isOfficialModel(value)
                || value.contains("llama-4-")
                || value.contains("llama-3.3-70b-instruct");
    }

    static boolean isDedicatedServingMode(JsonNode servingMode) {
        if (servingMode == null || servingMode.isNull() || servingMode.isMissingNode()) {
            return false;
        }
        if (servingMode.isTextual()) {
            String value = normalized(servingMode.asText());
            return value.contains("dedicated") || value.contains("endpoint");
        }
        if (!servingMode.isObject()) {
            return false;
        }
        ObjectNode object = (ObjectNode) servingMode;
        String servingType = firstText(object, "servingType", "serving_type", "type");
        if (servingType != null && normalized(servingType).contains("dedicated")) {
            return true;
        }
        return hasText(object, "endpointId")
                || hasText(object, "endpoint_id")
                || hasText(object, "endpointOcid")
                || hasText(object, "endpoint_ocid");
    }

    static void validateRequestJson(byte[] input) {
        if (input == null || input.length == 0) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(input);
            if (!(root instanceof ObjectNode object)) {
                return;
            }
            validateRequest(object);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception ignored) {
        }
    }

    static void validateRequest(ObjectNode root) {
        if (root == null) {
            return;
        }
        String model = firstNonBlank(
                firstText(root, "model"),
                firstText(root, "modelId"),
                firstText(root, "model_id"),
                servingModeModelId(root.get("servingMode")));
        if (!isMetaLlamaModel(model)) {
            return;
        }
        MediaScan scan = scanRequestMedia(root);
        if (!scan.hasMedia()) {
            return;
        }
        if (isTextOnlyModel(model) && (scan.hasImage || scan.hasUnsupportedMediaInput)) {
            throw new IllegalArgumentException("模型 " + model + " 按 OCI 文档只支持文本输入，不能包含图片、文件、音频或视频内容。");
        }
        if (isMultimodalModel(model) && scan.hasUnsupportedMediaInput) {
            throw new IllegalArgumentException("模型 " + model + " 按 OCI 文档只支持文本和图片输入，不能包含文件、音频或视频内容。");
        }
        if (isMultimodalModel(model) && scan.hasInvalidImageInput) {
            throw new IllegalArgumentException("模型 " + model + " 的 API 图片输入需要有效的 base64/data URL，不能使用外部图片 URL 或空图片。");
        }
    }

    private static MediaScan scanRequestMedia(ObjectNode root) {
        MediaScan scan = new MediaScan();
        JsonNode messages = root.get("messages");
        if (messages != null && messages.isArray()) {
            for (JsonNode message : messages) {
                if (message instanceof ObjectNode object) {
                    scanContentForMedia(object.get("content"), scan);
                    scanContentForMedia(object.get("parts"), scan);
                }
            }
        }
        scanContentForMedia(root.get("input"), scan);
        return scan;
    }

    private static void scanContentForMedia(JsonNode node, MediaScan scan) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                scanContentForMedia(item, scan);
            }
            return;
        }
        if (!(node instanceof ObjectNode object)) {
            return;
        }
        if (isImageContentObject(object)) {
            scan.hasImage = true;
            String url = imageUrlValue(object);
            if (!isValidBase64ImageInput(url)) {
                scan.hasInvalidImageInput = true;
            }
        } else if (isUnsupportedMediaContentObject(object)) {
            scan.hasUnsupportedMediaInput = true;
        }
        JsonNode nested = object.get("content");
        if (nested != null && nested != node) {
            scanContentForMedia(nested, scan);
        }
        JsonNode parts = object.get("parts");
        if (parts != null && parts != node) {
            scanContentForMedia(parts, scan);
        }
    }

    private static boolean isImageContentObject(ObjectNode object) {
        String type = normalized(firstText(object, "type"));
        return type.contains("image")
                || object.has("image_url")
                || object.has("imageUrl")
                || object.has("image")
                || sourceMediaTypeStartsWith(object.get("source"), "image/");
    }

    private static boolean isUnsupportedMediaContentObject(ObjectNode object) {
        String type = normalized(firstText(object, "type"));
        if (type.contains("document")
                || type.contains("audio")
                || type.contains("video")
                || type.equals("file")
                || type.equals("input_file")) {
            return true;
        }
        JsonNode source = object.get("source");
        return sourceMediaTypeStartsWith(source, "application/")
                || sourceMediaTypeStartsWith(source, "text/")
                || sourceMediaTypeStartsWith(source, "audio/")
                || sourceMediaTypeStartsWith(source, "video/");
    }

    private static String imageUrlValue(ObjectNode object) {
        JsonNode node = firstExisting(object, "image_url", "imageUrl", "image", "source");
        String value = imageNodeValue(node);
        if (value != null) {
            return value;
        }
        return firstNonBlank(
                firstText(object, "url"),
                firstText(object, "uri"),
                firstText(object, "data"),
                firstText(object, "file_data"));
    }

    private static String imageNodeValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node instanceof ObjectNode object) {
            return firstNonBlank(
                    firstText(object, "url"),
                    firstText(object, "uri"),
                    firstText(object, "data"),
                    firstText(object, "file_data"));
        }
        return null;
    }

    private static boolean isValidBase64ImageInput(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:")) {
            int comma = trimmed.indexOf(',');
            if (comma < 0 || !lower.substring(0, comma).contains(";base64")) {
                return false;
            }
            String metadata = lower.substring(0, comma);
            String payload = trimmed.substring(comma + 1).trim();
            return metadata.startsWith("data:image/") && looksLikeBase64Payload(payload);
        }
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("oci://")) {
            return false;
        }
        return looksLikeBase64Payload(trimmed);
    }

    private static boolean looksLikeBase64Payload(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String compact = value.replaceAll("\\s+", "");
        return !compact.isBlank() && compact.matches("[A-Za-z0-9+/]+={0,2}");
    }

    private static boolean sourceMediaTypeStartsWith(JsonNode source, String prefix) {
        if (!(source instanceof ObjectNode object) || prefix == null) {
            return false;
        }
        String mediaType = firstNonBlank(
                firstText(object, "media_type"),
                firstText(object, "mime_type"),
                firstText(object, "content_type"));
        return mediaType != null && normalized(mediaType).startsWith(prefix);
    }

    private static JsonNode firstExisting(ObjectNode object, String... fields) {
        if (object == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            if (field == null) {
                continue;
            }
            JsonNode value = object.get(field);
            if (value != null && !value.isNull() && !value.isMissingNode()) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasText(ObjectNode object, String field) {
        String value = firstText(object, field);
        return value != null && !value.isBlank();
    }

    private static String servingModeModelId(JsonNode servingMode) {
        if (servingMode instanceof ObjectNode object) {
            return firstText(object, "modelId", "model_id");
        }
        return null;
    }

    private static String firstText(ObjectNode object, String... fields) {
        if (object == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            if (field == null) {
                continue;
            }
            JsonNode value = object.get(field);
            if (value == null || value.isNull() || value.isMissingNode()) {
                continue;
            }
            if (value.isTextual()) {
                return value.asText();
            }
            if (value.isNumber() || value.isBoolean()) {
                return value.asText();
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static class MediaScan {
        boolean hasImage;
        boolean hasInvalidImageInput;
        boolean hasUnsupportedMediaInput;

        boolean hasMedia() {
            return hasImage || hasUnsupportedMediaInput;
        }
    }
}
