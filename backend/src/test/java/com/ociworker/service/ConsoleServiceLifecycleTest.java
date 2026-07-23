package com.ociworker.service;

import com.ociworker.exception.OciException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsoleServiceLifecycleTest {

    private final ConsoleService service = new ConsoleService();

    @TempDir
    Path tempDir;

    @AfterEach
    void stopCleanupExecutor() {
        service.shutdown();
    }

    @Test
    void rejectsClaimFromDifferentPanelAccount() {
        put(session("connection", "tenant-a", "owner-a"));

        assertThatThrownBy(() -> service.claimConsoleSession("connection", "owner-b"))
                .isInstanceOf(OciException.class)
                .hasMessageContaining("不属于当前账号");
    }

    @Test
    void rejectsDuplicateClaimAndAllowsReconnectAfterIdempotentRelease() throws Exception {
        ConsoleService.ConsoleSession session = session("connection", "tenant-a", "owner-a");
        Path script = Files.writeString(tempDir.resolve("console_exec_connection_old.sh"), "test");
        put(session);

        ConsoleService.ConsoleLease first = service.claimConsoleSession("connection", "owner-a");
        session.execScriptPath = script.toString();
        assertThatThrownBy(() -> service.claimConsoleSession("connection", "owner-a"))
                .isInstanceOf(OciException.class)
                .hasMessageContaining("其他窗口");

        service.releaseConsoleSession(first);
        service.releaseConsoleSession(first);

        assertThat(script).doesNotExist();
        ConsoleService.ConsoleLease second = service.claimConsoleSession("connection", "owner-a");
        assertThat(second.leaseId()).isNotEqualTo(first.leaseId());
    }

    @Test
    void rejectsDeleteWhenTenantOrOwnerDoesNotMatchLocalSession() {
        put(session("connection", "tenant-a", "owner-a"));

        assertThatThrownBy(() -> service.deleteConsoleConnection(
                "tenant-b", "connection", null, "owner-a"))
                .isInstanceOf(OciException.class)
                .hasMessageContaining("不属于当前租户");
        assertThatThrownBy(() -> service.deleteConsoleConnection(
                "tenant-a", "connection", null, "owner-b"))
                .isInstanceOf(OciException.class)
                .hasMessageContaining("不属于当前账号");
    }

    @Test
    void expirationSkipsLeasedSessionAndMarksOnlyIdleSession() {
        ConsoleService.ConsoleSession active = session("active", "tenant", "owner");
        active.lastTouchedAt = 1L;
        active.leaseId = "active-lease";
        ConsoleService.ConsoleSession idle = session("idle", "tenant", "owner");
        idle.lastTouchedAt = 1L;
        put(active);
        put(idle);

        List<ConsoleService.ConsoleSession> expired = service.markExpiredSessions(2L);

        assertThat(expired).containsExactly(idle);
        assertThat(active.cleanupInProgress).isFalse();
        assertThat(idle.cleanupInProgress).isTrue();
    }

    @Test
    void startupCleanupDeletesOnlyProjectGeneratedRegularScripts() throws Exception {
        ReflectionTestUtils.setField(service, "keyDirectory", tempDir);
        String generatedContent = "#!/bin/bash\nexport TERM=vt100\nexec ssh target\n";
        Path oldScript = Files.writeString(tempDir.resolve("console_exec_old-id.sh"), generatedContent);
        Path randomScript = Files.writeString(tempDir.resolve("console_exec_id_123456.sh"), generatedContent);
        Path unrelatedName = Files.writeString(tempDir.resolve("console_exec_bad name.sh"), "keep");
        Path matchingButUnrelated = Files.writeString(tempDir.resolve("console_exec_notes.sh"), "keep");
        Path unrelatedFile = Files.writeString(tempDir.resolve("notes.sh"), "keep");
        Path matchingDirectory = Files.createDirectory(tempDir.resolve("console_exec_directory.sh"));

        service.cleanupOrphanExecScripts();

        assertThat(oldScript).doesNotExist();
        assertThat(randomScript).doesNotExist();
        assertThat(unrelatedName).exists();
        assertThat(matchingButUnrelated).exists();
        assertThat(unrelatedFile).exists();
        assertThat(matchingDirectory).isDirectory();
    }

    @Test
    void createsRandomPrivateScriptPerLeaseAndDeletesItOnRelease() throws Exception {
        ReflectionTestUtils.setField(service, "keyDirectory", tempDir);
        ReflectionTestUtils.setField(service, "privateKeyPath", tempDir.resolve("console_rsa").toString());
        ConsoleService.ConsoleSession session = session("connection", "tenant", "owner");
        session.sshCommand = "ssh target.example";
        put(session);

        ConsoleService.ConsoleLease first = service.claimConsoleSession("connection", "owner");
        Path firstScript = service.getOrCreateExecScript(first);
        assertThat(firstScript.getFileName().toString())
                .startsWith("console_exec_connection_")
                .endsWith(".sh");
        assertThat(Files.readString(firstScript)).contains("export TERM=vt100", "exec ssh -i");
        service.releaseConsoleSession(first);
        assertThat(firstScript).doesNotExist();

        ConsoleService.ConsoleLease second = service.claimConsoleSession("connection", "owner");
        Path secondScript = service.getOrCreateExecScript(second);
        assertThat(secondScript).isNotEqualTo(firstScript);
    }

    private void put(ConsoleService.ConsoleSession session) {
        sessions().put(session.consoleConnectionId, session);
    }

    @SuppressWarnings("unchecked")
    private Map<String, ConsoleService.ConsoleSession> sessions() {
        return (Map<String, ConsoleService.ConsoleSession>) ReflectionTestUtils.getField(service, "activeSessions");
    }

    private static ConsoleService.ConsoleSession session(
            String connectionId, String tenantId, String ownerAccount) {
        ConsoleService.ConsoleSession session = new ConsoleService.ConsoleSession();
        session.consoleConnectionId = connectionId;
        session.instanceId = "instance";
        session.tenantId = tenantId;
        session.ownerAccount = ownerAccount;
        session.createdAt = System.currentTimeMillis();
        session.lastTouchedAt = session.createdAt;
        return session;
    }
}
