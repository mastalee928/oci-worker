package com.ociworker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OciMetaLlamaBridgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void recognizesOfficialMetaLlamaModelsFromOracleDocs() {
        assertThat(OciMetaLlamaBridge.isOfficialModel("meta.llama-3.3-70b-instruct")).isTrue();
        assertThat(OciMetaLlamaBridge.isOfficialModel("meta.llama-3.3-70b-instruct-fp8-dynamic")).isTrue();
        assertThat(OciMetaLlamaBridge.isOfficialModel("meta.llama-4-scout-17b-16e-instruct")).isTrue();
        assertThat(OciMetaLlamaBridge.isOfficialModel("meta.llama-4-maverick-17b-128e-instruct-fp8")).isTrue();

        assertThat(OciMetaLlamaBridge.isTextOnlyModel("meta.llama-3.3-70b-instruct")).isTrue();
        assertThat(OciMetaLlamaBridge.isTextOnlyModel("meta.llama-3.3-70b-instruct-fp8-dynamic")).isTrue();
        assertThat(OciMetaLlamaBridge.isMultimodalModel("meta.llama-4-scout-17b-16e-instruct")).isTrue();
        assertThat(OciMetaLlamaBridge.isMultimodalModel("meta.llama-4-maverick-17b-128e-instruct-fp8")).isTrue();
    }

    @Test
    void capsOnDemandMetaLlamaOutputTokensToOfficialLimit() {
        assertThat(OciMetaLlamaBridge.capOnDemandMaxTokens(
                "meta.llama-3.3-70b-instruct", 12000, null)).isEqualTo(4000);
        assertThat(OciMetaLlamaBridge.capOnDemandMaxTokens(
                "meta.llama-3.3-70b-instruct-fp8-dynamic", 12000, null)).isEqualTo(4000);
        assertThat(OciMetaLlamaBridge.capOnDemandMaxTokens(
                "meta.llama-4-scout-17b-16e-instruct", 12000, null)).isEqualTo(4000);
        assertThat(OciMetaLlamaBridge.capOnDemandMaxTokens(
                "meta.llama-4-maverick-17b-128e-instruct-fp8", 12000, null)).isEqualTo(4000);
    }

    @Test
    void doesNotCapUnknownFutureMetaLlamaModelsWithoutOfficialRule() {
        assertThat(OciMetaLlamaBridge.capOnDemandMaxTokens(
                "meta.llama-future-experimental", 12000, null)).isEqualTo(12000);
    }

    @Test
    void doesNotCapExplicitDedicatedServingMode() throws Exception {
        JsonNode servingMode = MAPPER.readTree("""
                {"servingType":"DEDICATED","endpointId":"ocid1.generativeaiendpoint.oc1..test"}
                """);

        assertThat(OciMetaLlamaBridge.isDedicatedServingMode(servingMode)).isTrue();
        assertThat(OciMetaLlamaBridge.capOnDemandMaxTokens(
                "meta.llama-4-maverick-17b-128e-instruct-fp8", 12000, servingMode)).isEqualTo(12000);
    }

    @Test
    void rejectsImagesForLlama33TextOnlyModels() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree("""
                {
                  "model":"meta.llama-3.3-70b-instruct",
                  "messages":[{"role":"user","content":[
                    {"type":"text","text":"look"},
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}
                  ]}]
                }
                """);

        assertThatThrownBy(() -> OciMetaLlamaBridge.validateRequest(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只支持文本输入");
    }

    @Test
    void acceptsBase64ImagesForLlama4Models() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree("""
                {
                  "model":"meta.llama-4-scout-17b-16e-instruct",
                  "messages":[{"role":"user","content":[
                    {"type":"input_text","text":"look"},
                    {"type":"input_image","image_url":"data:image/png;base64,AAAA"}
                  ]}]
                }
                """);

        OciMetaLlamaBridge.validateRequest(root);
    }

    @Test
    void acceptsAnthropicStyleBase64ImageSourceForLlama4Models() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree("""
                {
                  "model":"meta.llama-4-maverick-17b-128e-instruct-fp8",
                  "messages":[{"role":"user","content":[
                    {"type":"text","text":"look"},
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}
                  ]}]
                }
                """);

        OciMetaLlamaBridge.validateRequest(root);
    }

    @Test
    void acceptsImageSourceWithMediaTypeEvenWhenClientOmitsImageType() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree("""
                {
                  "model":"meta.llama-4-scout-17b-16e-instruct",
                  "messages":[{"role":"user","content":[
                    {"type":"text","text":"look"},
                    {"source":{"type":"base64","media_type":"image/jpeg","data":"AAAA"}}
                  ]}]
                }
                """);

        OciMetaLlamaBridge.validateRequest(root);
    }

    @Test
    void rejectsExternalImageUrlsForLlama4ModelsBecauseDocsRequireBase64ApiImages() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree("""
                {
                  "model":"meta.llama-4-maverick-17b-128e-instruct-fp8",
                  "messages":[{"role":"user","content":[
                    {"type":"text","text":"look"},
                    {"type":"image_url","image_url":{"url":"https://example.com/a.png"}}
                  ]}]
                }
                """);

        assertThatThrownBy(() -> OciMetaLlamaBridge.validateRequest(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void rejectsExternalImageSourceUrlsForLlama4Models() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree("""
                {
                  "model":"meta.llama-4-scout-17b-16e-instruct",
                  "messages":[{"role":"user","content":[
                    {"type":"text","text":"look"},
                    {"type":"image","source":{"type":"url","url":"https://example.com/a.png"}}
                  ]}]
                }
                """);

        assertThatThrownBy(() -> OciMetaLlamaBridge.validateRequest(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void rejectsMalformedDataUrlImagesForLlama4Models() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree("""
                {
                  "model":"meta.llama-4-scout-17b-16e-instruct",
                  "messages":[{"role":"user","content":[
                    {"type":"text","text":"look"},
                    {"type":"input_image","image_url":"data:text/plain;base64,hello"}
                  ]}]
                }
                """);

        assertThatThrownBy(() -> OciMetaLlamaBridge.validateRequest(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void rejectsEmptyImagePartsForLlama4Models() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree("""
                {
                  "model":"meta.llama-4-scout-17b-16e-instruct",
                  "messages":[{"role":"user","content":[
                    {"type":"text","text":"look"},
                    {"type":"image_url","image_url":{}}
                  ]}]
                }
                """);

        assertThatThrownBy(() -> OciMetaLlamaBridge.validateRequest(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void rejectsDocumentAudioAndVideoForLlama4Models() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree("""
                {
                  "model":"meta.llama-4-maverick-17b-128e-instruct-fp8",
                  "messages":[{"role":"user","content":[
                    {"type":"text","text":"summarize"},
                    {"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"AAAA"}},
                    {"type":"audio","source":{"type":"base64","media_type":"audio/mp3","data":"BBBB"}},
                    {"type":"video","source":{"type":"base64","media_type":"video/mp4","data":"CCCC"}}
                  ]}]
                }
                """);

        assertThatThrownBy(() -> OciMetaLlamaBridge.validateRequest(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只支持文本和图片输入");
    }

    @Test
    void scansClientPartsFieldForMetaLlamaMedia() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree("""
                {
                  "model":"meta.llama-3.3-70b-instruct",
                  "messages":[{"role":"user","content":"","parts":[
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}
                  ]}]
                }
                """);

        assertThatThrownBy(() -> OciMetaLlamaBridge.validateRequest(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只支持文本输入");
    }

    @Test
    void validatesResponsesInputImagePartsToo() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree("""
                {
                  "model":"meta.llama-3.3-70b-instruct-fp8-dynamic",
                  "input":[{"role":"user","content":[
                    {"type":"input_text","text":"look"},
                    {"type":"input_image","image_url":"data:image/png;base64,AAAA"}
                  ]}]
                }
                """);

        assertThatThrownBy(() -> OciMetaLlamaBridge.validateRequest(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只支持文本输入");
    }
}
