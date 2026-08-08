package com.ociworker.webssh;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WebSshBastionFrontendContractTest {

    @Test
    void exposesBastionBootstrapWithoutChangingSerialEndpoint() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/static/webssh/static/js/app.js")) {
            assertThat(input).isNotNull();
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(script).contains(
                    "function parseBastionParams()",
                    "'/webssh-api/bastion-term?cols='",
                    "ws.send(session.bastionToken || '')",
                    "var BASTION_CONTROL_PREFIX = '\\u001eociworker-bastion:';",
                    "ws.send(bastionControl('ping'))",
                    "if (got && isCurrentConnection() && ws.readyState === 1) ws.send(data);",
                    "if (got && isCurrentConnection() && ws.readyState === 1 && session.term)",
                    "function parseDirectParams()",
                    "function tryDirectPrefill()",
                    "if (!bootstrapped) tryDirectPrefill();",
                    "function parseConsoleParams()",
                    "'/webssh-api/console-term?cols='",
                    "var CONSOLE_COLS = 80;",
                    "var CONSOLE_ROWS = 24;");
        }
    }

    @Test
    void mapsNetworkFailuresToTheNetworkStepWithoutFakeCompletion() throws Exception {
        String composable = Files.readString(Path.of(
                "..", "frontend", "src", "composables", "useBastionSshConnect.ts"));

        assertThat(composable).contains(
                "raw.includes('目标 vcn')",
                "raw.includes('可用子网')",
                "raw.includes('路由')",
                "connectionStep.value >= 2");
        assertThat(composable).doesNotContain("connectionStep.value >= 3");
    }
}
