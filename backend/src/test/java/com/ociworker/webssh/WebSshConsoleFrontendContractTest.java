package com.ociworker.webssh;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class WebSshConsoleFrontendContractTest {

    @Test
    void keepsSerialConsoleFrontendProtocol() {
        assertThatCode(() -> {
            try (InputStream input = getClass().getResourceAsStream("/static/webssh/static/js/app.js")) {
                assertThat(input).as("WebSSH frontend app.js").isNotNull();
                String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);

                assertThat(script).contains(
                        "var CONSOLE_COLS = 80;",
                        "var CONSOLE_ROWS = 24;",
                        "'/webssh-api/console-term?cols='",
                        "ws.send(session.connectionId)"
                );
            }
        }).doesNotThrowAnyException();
    }
}
