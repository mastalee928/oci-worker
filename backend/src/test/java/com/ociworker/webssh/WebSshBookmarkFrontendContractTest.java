package com.ociworker.webssh;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WebSshBookmarkFrontendContractTest {

    @Test
    void usesAuthenticatedServerBookmarksAndMigratesLegacyCache() throws Exception {
        String script = resource("/static/webssh/static/js/app.js");

        assertThat(script).contains(
                "BM_MIGRATION_KEY",
                "'/webssh-api/bookmarks'",
                "'/webssh-api/bookmarks/migrate'",
                "'/webssh-api/bookmarks/connections'",
                "'/webssh-api/bookmarks/scripts'",
                "ensureBookmarksServer",
                "queueBookmarkMutation",
                "localStorage.removeItem(CBK)",
                "localStorage.removeItem(SBK)"
        );
        assertThat(script).doesNotContain("password: b", "privateKey: b", "saveBM(CBK, bms)", "saveBM(SBK, bms)");
    }

    @Test
    void keepsSerialConsoleRendererAndProtocolSeparate() throws Exception {
        String script = resource("/static/webssh/static/js/app.js");
        int consoleStart = script.indexOf("function connectConsoleSession");
        int consoleEnd = script.indexOf("function tryConsoleConnect", consoleStart);
        assertThat(consoleStart).isGreaterThanOrEqualTo(0);
        assertThat(consoleEnd).isGreaterThan(consoleStart);
        assertThat(script.substring(consoleStart, consoleEnd))
                .doesNotContain("enableWebglRenderer", "bookmarks/connections", "bookmarks/scripts");
        assertThat(script).contains("CONSOLE_COLS = 80", "CONSOLE_ROWS = 24", "/webssh-api/console-term");
    }

    private static String resource(String path) throws Exception {
        try (InputStream input = WebSshBookmarkFrontendContractTest.class.getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
