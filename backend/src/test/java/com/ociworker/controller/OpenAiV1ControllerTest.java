package com.ociworker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    void convertsAnthropicForcedToolChoiceToChatCompletions() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[{"role":"user","content":[{"type":"text","text":"say hello"}]}],
                  "tools":[{"name":"write_file","description":"write a file","input_schema":{"type":"object"}}],
                  "tool_choice":{"type":"tool","name":"write_file"}
                }
                """;

        JsonNode root = MAPPER.readTree(OpenAiV1Controller.transformAnthropicMessagesToChatCompletionsJson(
                payload.getBytes()));

        assertThat(root.path("tool_choice").path("type").asText()).isEqualTo("function");
        assertThat(root.path("tool_choice").path("function").path("name").asText()).isEqualTo("write_file");
    }

    @Test
    void convertsAnthropicAnyAndNoneToolChoiceToChatCompletions() throws Exception {
        String anyPayload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[{"role":"user","content":[{"type":"text","text":"use a tool"}]}],
                  "tools":[{"name":"write_file","input_schema":{"type":"object"}}],
                  "tool_choice":{"type":"any"}
                }
                """;
        String nonePayload = anyPayload.replace("\"any\"", "\"none\"");

        JsonNode anyRoot = MAPPER.readTree(OpenAiV1Controller.transformAnthropicMessagesToChatCompletionsJson(
                anyPayload.getBytes()));
        JsonNode noneRoot = MAPPER.readTree(OpenAiV1Controller.transformAnthropicMessagesToChatCompletionsJson(
                nonePayload.getBytes()));

        assertThat(anyRoot.path("tool_choice").asText()).isEqualTo("required");
        assertThat(noneRoot.path("tool_choice").asText()).isEqualTo("none");
    }

    @Test
    void convertsAnthropicSystemArrayAndToolResultArray() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "system":[{"type":"text","text":"Use tools."},{"type":"text","text":"Be concise.","cache_control":{"type":"ephemeral"}}],
                  "messages":[
                    {"role":"assistant","content":[{"type":"tool_use","id":"toolu_a","name":"read_file","input":{"path":"a.txt"}}]},
                    {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_a","content":[{"type":"text","text":"line 1"},{"type":"text","text":"line 2"}]}]}
                  ],
                  "tools":[{"name":"read_file","input_schema":{"type":"object"}}]
                }
                """;

        JsonNode root = MAPPER.readTree(OpenAiV1Controller.transformAnthropicMessagesToChatCompletionsJson(
                payload.getBytes()));

        assertThat(root.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(root.path("messages").get(0).path("content").asText()).isEqualTo("Use tools.\nBe concise.");
        assertThat(root.path("messages").get(2).path("role").asText()).isEqualTo("tool");
        assertThat(root.path("messages").get(2).path("content").asText()).isEqualTo("line 1\nline 2");
    }

    @Test
    void convertsAnthropicImageContentToOpenAiImageUrl() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[{"role":"user","content":[
                    {"type":"text","text":"inspect this"},
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":"abc"}}
                  ]}]
                }
                """;

        JsonNode root = MAPPER.readTree(OpenAiV1Controller.transformAnthropicMessagesToChatCompletionsJson(
                payload.getBytes()));

        JsonNode content = root.path("messages").get(0).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).isEqualTo("inspect this");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).path("image_url").path("url").asText())
                .isEqualTo("data:image/png;base64,abc");
    }

    @Test
    void extractsTextPlainAnthropicDocument() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[{"role":"user","content":[
                    {"type":"text","text":"inspect this"},
                    {"type":"document","source":{"type":"base64","media_type":"text/plain","filename":"note.txt","data":"%s"}}
                  ]}]
                }
                """.formatted(base64("OCIWORKER_TXT_DOC_MARKER"));

        JsonNode root = MAPPER.readTree(OpenAiV1Controller.transformAnthropicMessagesToChatCompletionsJson(
                payload.getBytes()));

        assertThat(root.path("messages").get(0).path("content").asText())
                .contains("inspect this")
                .contains("OCIWORKER_TXT_DOC_MARKER")
                .contains("已提取文档文本");
    }

    @Test
    void extractsJsonAnthropicDocument() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[{"role":"user","content":[
                    {"type":"document","source":{"type":"base64","media_type":"application/json","filename":"data.json","data":"%s"}}
                  ]}]
                }
                """.formatted(base64("{\"marker\":\"OCIWORKER_JSON_DOC_MARKER\"}"));

        JsonNode root = MAPPER.readTree(OpenAiV1Controller.transformAnthropicMessagesToChatCompletionsJson(
                payload.getBytes()));

        assertThat(root.path("messages").get(0).path("content").asText())
                .contains("OCIWORKER_JSON_DOC_MARKER");
    }

    @Test
    void extractsCodeDocumentByFileNameWhenMediaTypeMissing() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[{"role":"user","content":[
                    {"type":"document","source":{"type":"base64","filename":"App.vue","data":"%s"}}
                  ]}]
                }
                """.formatted(base64("<template>OCIWORKER_VUE_DOC_MARKER</template>"));

        JsonNode root = MAPPER.readTree(OpenAiV1Controller.transformAnthropicMessagesToChatCompletionsJson(
                payload.getBytes()));

        assertThat(root.path("messages").get(0).path("content").asText())
                .contains("OCIWORKER_VUE_DOC_MARKER");
    }

    @Test
    void extractsCfgDocumentByFileNameWhenMediaTypeMissing() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[{"role":"user","content":[
                    {"type":"document","source":{"type":"base64","filename":"amdpreseed.cfg","data":"%s"}}
                  ]}]
                }
                """.formatted(base64("OCIWORKER_CFG_DOC_MARKER=true"));

        JsonNode root = MAPPER.readTree(OpenAiV1Controller.transformAnthropicMessagesToChatCompletionsJson(
                payload.getBytes()));

        assertThat(root.path("messages").get(0).path("content").asText())
                .contains("OCIWORKER_CFG_DOC_MARKER");
    }

    @Test
    void extractsEnvAndDockerfileByFileNameWhenMediaTypeMissing() throws Exception {
        String envPayload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[{"role":"user","content":[
                    {"type":"document","source":{"type":"base64","filename":".env","data":"%s"}}
                  ]}]
                }
                """.formatted(base64("OCIWORKER_ENV_DOC_MARKER=true"));
        String dockerPayload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[{"role":"user","content":[
                    {"type":"document","source":{"type":"base64","filename":"Dockerfile","data":"%s"}}
                  ]}]
                }
                """.formatted(base64("RUN echo OCIWORKER_DOCKERFILE_DOC_MARKER"));

        JsonNode envRoot = MAPPER.readTree(OpenAiV1Controller.transformAnthropicMessagesToChatCompletionsJson(
                envPayload.getBytes()));
        JsonNode dockerRoot = MAPPER.readTree(OpenAiV1Controller.transformAnthropicMessagesToChatCompletionsJson(
                dockerPayload.getBytes()));

        assertThat(envRoot.path("messages").get(0).path("content").asText())
                .contains("OCIWORKER_ENV_DOC_MARKER");
        assertThat(dockerRoot.path("messages").get(0).path("content").asText())
                .contains("OCIWORKER_DOCKERFILE_DOC_MARKER");
    }

    @Test
    void rejectsLocalRemoteUrlAnthropicDocument() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[{"role":"user","content":[
                    {"type":"document","source":{"type":"url","media_type":"text/plain","url":"http://127.0.0.1/private.txt"}}
                  ]}]
                }
                """;

        JsonNode root = MAPPER.readTree(OpenAiV1Controller.transformAnthropicMessagesToChatCompletionsJson(
                payload.getBytes()));

        assertThat(root.path("messages").get(0).path("content").asText())
                .contains("禁止访问内网或本机地址")
                .doesNotContain("private.txt");
    }

    @Test
    void rejectsNonHttpRemoteUrlAnthropicDocument() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[{"role":"user","content":[
                    {"type":"document","source":{"type":"url","media_type":"text/plain","url":"file:///etc/passwd"}}
                  ]}]
                }
                """;

        JsonNode root = MAPPER.readTree(OpenAiV1Controller.transformAnthropicMessagesToChatCompletionsJson(
                payload.getBytes()));

        assertThat(root.path("messages").get(0).path("content").asText())
                .contains("暂不支持非 http/https URL");
    }

    @Test
    void extractsDocxAnthropicDocument() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[{"role":"user","content":[
                    {"type":"document","source":{"type":"base64","media_type":"application/vnd.openxmlformats-officedocument.wordprocessingml.document","filename":"note.docx","data":"%s"}}
                  ]}]
                }
                """.formatted(base64Zip(docxEntries("OCIWORKER_DOCX_DOC_MARKER")));

        JsonNode root = MAPPER.readTree(OpenAiV1Controller.transformAnthropicMessagesToChatCompletionsJson(
                payload.getBytes()));

        assertThat(root.path("messages").get(0).path("content").asText())
                .contains("OCIWORKER_DOCX_DOC_MARKER");
    }

    @Test
    void extractsXlsxAnthropicDocument() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[{"role":"user","content":[
                    {"type":"document","source":{"type":"base64","media_type":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","filename":"quota.xlsx","data":"%s"}}
                  ]}]
                }
                """.formatted(base64Zip(xlsxEntries("OCIWORKER_XLSX_DOC_MARKER")));

        JsonNode root = MAPPER.readTree(OpenAiV1Controller.transformAnthropicMessagesToChatCompletionsJson(
                payload.getBytes()));

        assertThat(root.path("messages").get(0).path("content").asText())
                .contains("OCIWORKER_XLSX_DOC_MARKER");
    }

    @Test
    void extractsSqliteDatabaseSummary() throws Exception {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "messages":[{"role":"user","content":[
                    {"type":"document","source":{"type":"base64","media_type":"application/vnd.sqlite3","filename":"app.sqlite","data":"%s"}}
                  ]}]
                }
                """.formatted(base64SqliteDatabase());

        JsonNode root = MAPPER.readTree(OpenAiV1Controller.transformAnthropicMessagesToChatCompletionsJson(
                payload.getBytes()));

        assertThat(root.path("messages").get(0).path("content").asText())
                .contains("SQLite 数据库只读摘要")
                .contains("CREATE TABLE users")
                .contains("name TEXT")
                .contains("OCIWORKER_SQLITE_MARKER");
    }

    @Test
    void countTokensEstimateDoesNotIncludeMaxTokens() {
        String payload = """
                {
                  "model":"xai.grok-4.3",
                  "max_tokens":4096,
                  "messages":[{"role":"user","content":[{"type":"text","text":"short prompt"}]}]
                }
                """;

        long inputTokens = OpenAiV1Controller.estimateInputTokens(payload.getBytes(), "application/json");

        assertThat(inputTokens).isLessThan(200);
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

    @Test
    void mapsChatLengthFinishReasonToAnthropicMaxTokens() throws Exception {
        String payload = """
                {
                  "id":"chatcmpl-1",
                  "model":"xai.grok-4.3",
                  "choices":[{"message":{"role":"assistant","content":"partial"},"finish_reason":"length"}]
                }
                """;

        JsonNode root = OpenAiV1Controller.chatCompletionToAnthropicMessage(payload, "xai.grok-4.3");

        assertThat(root.path("stop_reason").asText()).isEqualTo("max_tokens");
    }

    @Test
    void treatsToolCallPayloadAsToolUseEvenWhenFinishReasonMissing() throws Exception {
        String payload = """
                {
                  "id":"chatcmpl-1",
                  "model":"xai.grok-4.3",
                  "choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_a","type":"function","function":{"name":"write_file","arguments":"{}"}}]}}]
                }
                """;

        JsonNode root = OpenAiV1Controller.chatCompletionToAnthropicMessage(payload, "xai.grok-4.3");

        assertThat(root.path("stop_reason").asText()).isEqualTo("tool_use");
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static String base64SqliteDatabase() throws Exception {
        Path db = Files.createTempFile("ociworker-test-", ".sqlite");
        try {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, remark TEXT)");
                stmt.execute("INSERT INTO users (name, remark) VALUES ('alice', 'OCIWORKER_SQLITE_MARKER')");
            }
            return Base64.getEncoder().encodeToString(Files.readAllBytes(db));
        } finally {
            Files.deleteIfExists(db);
        }
    }

    private static Map<String, String> docxEntries(String marker) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """);
        entries.put("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
                """);
        entries.put("word/document.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>%s</w:t></w:r></w:p></w:body>
                </w:document>
                """.formatted(marker));
        return entries;
    }

    private static Map<String, String> xlsxEntries(String marker) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>
                """);
        entries.put("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>
                """);
        entries.put("xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>
                </workbook>
                """);
        entries.put("xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                </Relationships>
                """);
        entries.put("xl/worksheets/sheet1.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>%s</t></is></c></row></sheetData>
                </worksheet>
                """.formatted(marker));
        return entries;
    }
}
