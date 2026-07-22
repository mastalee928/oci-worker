package com.ociworker.webssh;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WebSshSftpFrontendContractTest {

    @Test
    void keepsSftpPanelOpenWhenDirectoryRowsRerender() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/static/webssh/static/js/app.js")) {
            assertThat(input).as("WebSSH frontend app.js").isNotNull();
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(script)
                    .containsPattern("row\\.addEventListener\\('click', function \\(e\\) \\{\\s*e\\.stopPropagation\\(\\);")
                    .contains("typeof e.composedPath === 'function'", "clickedInsideSftp")
                    .containsPattern("if \\(!clickedInsideSftp[^\\n]*\\) \\{\\s*sftpPanel\\.classList\\.remove\\('open'\\);");
        }
    }
}
