package com.ociworker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogPersistServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void readsOnlyRequestedLinesFromLargeFile() throws Exception {
        LogPersistService service = serviceWith("one\ntwo\nthree\nfour\n");

        assertThat(service.readLastLines(2)).containsExactly("three", "four");
    }

    @Test
    void preservesUtf8AndHandlesFileWithoutTrailingNewline() throws Exception {
        LogPersistService service = serviceWith("第一行\n第二行\n最后一行");

        assertThat(service.readLastLines(2)).containsExactly("第二行", "最后一行");
    }

    @Test
    void returnsAllAvailableLinesWhenLimitIsLarger() throws Exception {
        LogPersistService service = serviceWith("one\ntwo");

        assertThat(service.readLastLines(10)).containsExactly("one", "two");
    }

    @Test
    void handlesEmptyFileAndNonPositiveLimit() throws Exception {
        LogPersistService service = serviceWith("");

        assertThat(service.readLastLines(10)).isEmpty();
        assertThat(service.readLastLines(0)).isEmpty();
        assertThat(service.readLastLines(-1)).isEmpty();
    }

    @Test
    void preservesAnIntentionalEmptyLine() throws Exception {
        LogPersistService service = serviceWith("one\n\ntwo\n");

        assertThat(service.readLastLines(3)).isEqualTo(List.of("one", "", "two"));
        assertThat(service.readLastLines(2)).isEqualTo(List.of("", "two"));
    }

    private LogPersistService serviceWith(String content) throws Exception {
        Path logPath = tempDir.resolve("app-ws.log");
        Files.writeString(logPath, content, StandardCharsets.UTF_8);
        LogPersistService service = new LogPersistService();
        ReflectionTestUtils.setField(service, "logPath", logPath);
        return service;
    }
}
