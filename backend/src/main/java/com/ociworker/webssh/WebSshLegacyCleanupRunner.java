package com.ociworker.webssh;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 启动时清理旧版独立 sidecar 服务与二进制（若存在）。
 */
@Slf4j
@Component
public class WebSshLegacyCleanupRunner implements ApplicationRunner {

    private static final Path LEGACY_BIN = Path.of("/opt/oci-worker/oci-webssh");
    private static final String LEGACY_UNIT = "oci-webssh.service";

    @Override
    public void run(ApplicationArguments args) {
        cleanupLegacyWebssh();
    }

    public static void cleanupLegacyWebssh() {
        try {
            runQuiet("systemctl", "stop", LEGACY_UNIT);
            runQuiet("systemctl", "disable", LEGACY_UNIT);
            if (Files.exists(LEGACY_BIN)) {
                Files.deleteIfExists(LEGACY_BIN);
                log.debug("Removed legacy binary {}", LEGACY_BIN);
            }
            Path unit = Path.of("/etc/systemd/system/" + LEGACY_UNIT);
            if (Files.exists(unit)) {
                Files.deleteIfExists(unit);
                runQuiet("systemctl", "daemon-reload");
            }
        } catch (Exception e) {
            log.debug("Legacy sidecar cleanup partial failure: {}", e.getMessage());
        }
    }

    private static void runQuiet(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor(8, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }
}
