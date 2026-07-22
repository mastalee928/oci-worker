package com.ociworker.webssh;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WebSshWebglFrontendContractTest {

    @Test
    void servesPinnedLocalWebglAddonWithoutExternalCdn() throws Exception {
        String html = resource("/static/webssh/index.html");
        String addon = resource("/static/webssh/static/vendor/xterm-addon-webgl.js");

        assertThat(html)
                .contains("/webssh/static/vendor/xterm-addon-webgl.js?v=0.15.0")
                .doesNotContain("jsdelivr", "unpkg", "cdnjs");
        assertThat(addon).contains("WebglAddon").hasSizeGreaterThan(90_000);
    }

    @Test
    void keepsWebglAndSessionReuseOnOrdinarySshOnly() throws Exception {
        String script = resource("/static/webssh/static/js/app.js");
        assertThat(script).contains(
                "enableWebglRenderer(session)",
                "disableWebglRenderer(session, 'context-loss')",
                "consumeSshSessionControl",
                "appendSshConnection",
                "sessionId",
                "/webssh-api/sysinfo",
                "/webssh-api/file/list",
                "/webssh-api/file/download",
                "/webssh-api/file/upload"
        );

        int consoleStart = script.indexOf("function createConsoleSession");
        int consoleEnd = script.indexOf("function writeConsoleOutput", consoleStart);
        assertThat(consoleStart).isGreaterThanOrEqualTo(0);
        assertThat(consoleEnd).isGreaterThan(consoleStart);
        assertThat(script.substring(consoleStart, consoleEnd))
                .doesNotContain("enableWebglRenderer", "xterm-addon-webgl");
    }

    private static String resource(String path) throws Exception {
        try (InputStream input = WebSshWebglFrontendContractTest.class.getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
