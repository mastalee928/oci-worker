package com.ociworker.service;

import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.mapper.WebSshConnectionBookmarkMapper;
import com.ociworker.mapper.WebSshScriptBookmarkMapper;
import com.ociworker.model.entity.WebSshConnectionBookmark;
import com.ociworker.model.entity.WebSshScriptBookmark;
import com.ociworker.webssh.WebSshBookmarkDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSshBookmarkServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    WebSshConnectionBookmarkMapper connectionMapper;
    @Mock
    WebSshScriptBookmarkMapper scriptMapper;
    @Mock
    OciKvMapper kvMapper;

    private WebSshBookmarkCryptoService cryptoService;
    private WebSshBookmarkService service;

    @BeforeEach
    void setUp() {
        cryptoService = new WebSshBookmarkCryptoService();
        ReflectionTestUtils.setField(cryptoService, "keyDirPath", tempDir.toString());
        cryptoService.initialize();
        service = new WebSshBookmarkService(connectionMapper, scriptMapper, kvMapper, cryptoService);

        lenient().when(connectionMapper.selectList(any())).thenReturn(List.of());
        lenient().when(scriptMapper.selectList(any())).thenReturn(List.of());
        lenient().when(connectionMapper.selectCount(any())).thenReturn(0L);
        lenient().when(scriptMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void encryptsScriptBeforeDatabaseInsert() {
        service.saveScript(new WebSshBookmarkDto.ScriptInput("deploy", "echo one;\necho two"));

        ArgumentCaptor<WebSshScriptBookmark> captor = ArgumentCaptor.forClass(WebSshScriptBookmark.class);
        verify(scriptMapper).insert(captor.capture());
        WebSshScriptBookmark row = captor.getValue();
        assertThat(row.getCommandEncrypted()).startsWith("enc:v1:").doesNotContain("echo one");
        assertThat(cryptoService.decryptIfEncrypted(row.getCommandEncrypted(), row.getId()))
                .isEqualTo("echo one;\necho two");
    }

    @Test
    void normalizesConnectionAndStoresNoCredentialFields() {
        service.saveConnection(new WebSshBookmarkDto.ConnectionInput(" EXAMPLE.COM ", 22, "Root", "KEY"));

        ArgumentCaptor<WebSshConnectionBookmark> captor = ArgumentCaptor.forClass(WebSshConnectionBookmark.class);
        verify(connectionMapper).insert(captor.capture());
        WebSshConnectionBookmark row = captor.getValue();
        assertThat(row.getHostname()).isEqualTo("example.com");
        assertThat(row.getUsername()).isEqualTo("Root");
        assertThat(row.getAuthType()).isEqualTo("key");
        assertThat(WebSshConnectionBookmark.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("password", "privateKey", "proxyPassword");
    }

    @Test
    void migrationDeduplicatesIdenticalScripts() {
        WebSshBookmarkDto.ScriptInput script = new WebSshBookmarkDto.ScriptInput("same", "printf 'x'");

        service.migrate(new WebSshBookmarkDto.MigrationRequest(List.of(), List.of(script, script)));

        verify(scriptMapper).insert(any(WebSshScriptBookmark.class));
    }

    @Test
    void rejectsInvalidPortsAndOversizedCommands() {
        assertThatThrownBy(() -> service.saveConnection(
                new WebSshBookmarkDto.ConnectionInput("example.com", 70000, "root", "password")))
                .isInstanceOf(OciException.class)
                .hasMessageContaining("端口");

        assertThatThrownBy(() -> service.saveScript(
                new WebSshBookmarkDto.ScriptInput("too-long", "x".repeat(16_385))))
                .isInstanceOf(OciException.class)
                .hasMessageContaining("16384");
    }

    @Test
    void rejectsWhitespaceInUsernameBeforePersistence() {
        assertThatThrownBy(() -> service.saveConnection(
                new WebSshBookmarkDto.ConnectionInput("example.com", 22, "root user", "password")))
                .isInstanceOf(OciException.class)
                .hasMessageContaining("用户名");
    }
}
