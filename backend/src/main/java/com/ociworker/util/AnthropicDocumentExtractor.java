package com.ociworker.util;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

public final class AnthropicDocumentExtractor {

    private static final int MAX_DOCUMENT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_EXTRACTED_CHARS = 80_000;
    private static final int MAX_BASE64_CHARS = ((MAX_DOCUMENT_BYTES + 2) / 3) * 4 + 16;

    private AnthropicDocumentExtractor() {
    }

    public static String extract(String sourceType, String mediaType, String fileName, String data) {
        String safeMediaType = blankToNull(mediaType);
        String safeFileName = blankToNull(fileName);
        if (sourceType == null || sourceType.isBlank()) {
            return unsupported("document", "缺少 source.type");
        }
        if ("text".equalsIgnoreCase(sourceType)) {
            return formatExtractedText(data, safeMediaType, safeFileName, false);
        }
        if ("url".equalsIgnoreCase(sourceType)) {
            return unsupported("document", "暂不抓取远程 URL 文档，请使用 base64 文档内容");
        }
        if (!"base64".equalsIgnoreCase(sourceType)) {
            return unsupported("document", "暂不支持 source.type=" + sourceType);
        }
        if (data == null || data.isBlank()) {
            return unsupported("document", "base64 内容为空");
        }
        if (data.length() > MAX_BASE64_CHARS) {
            return unsupported("document", "文件超过 " + (MAX_DOCUMENT_BYTES / 1024 / 1024) + "MB 上限");
        }
        byte[] bytes;
        try {
            bytes = decodeBase64(data);
        } catch (IllegalArgumentException e) {
            return unsupported("document", "base64 解码失败");
        }
        if (bytes.length > MAX_DOCUMENT_BYTES) {
            return unsupported("document", "文件超过 " + (MAX_DOCUMENT_BYTES / 1024 / 1024) + "MB 上限");
        }
        String directText = tryDecodeTextLike(bytes, safeMediaType, safeFileName);
        if (directText != null) {
            return formatExtractedText(directText, safeMediaType, safeFileName, directText.length() > MAX_EXTRACTED_CHARS);
        }
        return parseWithTika(bytes, safeMediaType, safeFileName);
    }

    private static byte[] decodeBase64(String data) {
        String trimmed = data.trim();
        try {
            return Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException ignored) {
            return Base64.getMimeDecoder().decode(trimmed);
        }
    }

    private static String tryDecodeTextLike(byte[] bytes, String mediaType, String fileName) {
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (type.contains("officedocument")
                || type.contains("msword")
                || type.contains("ms-excel")
                || type.contains("ms-powerpoint")
                || type.contains("opendocument")
                || type.contains("pdf")
                || type.contains("zip")
                || name.endsWith(".doc")
                || name.endsWith(".docx")
                || name.endsWith(".xls")
                || name.endsWith(".xlsx")
                || name.endsWith(".ppt")
                || name.endsWith(".pptx")
                || name.endsWith(".pdf")
                || name.endsWith(".odt")
                || name.endsWith(".ods")
                || name.endsWith(".odp")) {
            return null;
        }
        boolean textLike = type.startsWith("text/")
                || type.contains("json")
                || type.contains("xml")
                || type.contains("yaml")
                || type.contains("csv")
                || type.contains("javascript")
                || type.contains("x-sh")
                || name.endsWith(".txt")
                || name.endsWith(".md")
                || name.endsWith(".markdown")
                || name.endsWith(".csv")
                || name.endsWith(".json")
                || name.endsWith(".jsonl")
                || name.endsWith(".yaml")
                || name.endsWith(".yml")
                || name.endsWith(".xml")
                || name.endsWith(".html")
                || name.endsWith(".htm")
                || name.endsWith(".log")
                || name.endsWith(".sql")
                || name.endsWith(".ini")
                || name.endsWith(".conf")
                || name.endsWith(".properties")
                || name.endsWith(".java")
                || name.endsWith(".js")
                || name.endsWith(".ts")
                || name.endsWith(".py")
                || name.endsWith(".go")
                || name.endsWith(".sh")
                || name.endsWith(".ps1");
        if (!textLike) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String parseWithTika(byte[] bytes, String mediaType, String fileName) {
        Metadata metadata = new Metadata();
        if (mediaType != null) {
            metadata.set(Metadata.CONTENT_TYPE, mediaType);
        }
        if (fileName != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        }
        BodyContentHandler handler = new BodyContentHandler(MAX_EXTRACTED_CHARS);
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            new AutoDetectParser().parse(input, handler, metadata, new ParseContext());
        } catch (SAXException e) {
            String partial = handler.toString();
            if (partial != null && !partial.isBlank()) {
                return formatExtractedText(partial, mediaType, fileName, true);
            }
            return unsupported("document", "文本提取失败：" + e.getMessage());
        } catch (Exception e) {
            return unsupported("document", "文本提取失败：" + e.getMessage());
        }
        String text = handler.toString();
        if (text == null || text.isBlank()) {
            return unsupported("document", "未提取到可读文本，扫描版 PDF 或纯图片文档需要 OCR");
        }
        return formatExtractedText(text, mediaType, fileName, text.length() >= MAX_EXTRACTED_CHARS);
    }

    private static String formatExtractedText(String text, String mediaType, String fileName, boolean truncated) {
        String safeText = text == null ? "" : text.strip();
        if (safeText.length() > MAX_EXTRACTED_CHARS) {
            safeText = safeText.substring(0, MAX_EXTRACTED_CHARS);
            truncated = true;
        }
        if (safeText.isBlank()) {
            return unsupported("document", "文档内容为空");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[OCIworker 已提取文档文本");
        if (fileName != null && !fileName.isBlank()) {
            sb.append("，文件名：").append(fileName);
        }
        if (mediaType != null && !mediaType.isBlank()) {
            sb.append("，类型：").append(mediaType);
        }
        sb.append("]\n");
        sb.append(safeText);
        if (truncated) {
            sb.append("\n[OCIworker 提示：文档内容较长，已截断为前 ")
                    .append(MAX_EXTRACTED_CHARS)
                    .append(" 个字符。]");
        }
        return sb.toString();
    }

    private static String unsupported(String type, String reason) {
        return "[OCIworker 提示：暂无法解析 Anthropic " + type + " 内容块：" + reason + "。]";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
