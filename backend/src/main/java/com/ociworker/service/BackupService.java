package com.ociworker.service;

import com.ociworker.exception.OciException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class BackupService {

    @Value("${oci-cfg.key-dir-path}")
    private String keyDirPath;

    public byte[] createBackup(String password) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path tempDir = Files.createTempDirectory("oci-worker-backup-");
            String zipPath = tempDir.resolve("oci-worker-backup-" + timestamp + ".zip").toString();

            ZipParameters params = new ZipParameters();
            params.setCompressionLevel(CompressionLevel.NORMAL);
            params.setEncryptFiles(true);
            params.setEncryptionMethod(EncryptionMethod.AES);

            try (ZipFile zipFile = new ZipFile(zipPath, password.toCharArray())) {
                File dbFile = new File("oci-worker.db");
                if (dbFile.exists()) {
                    zipFile.addFile(dbFile, params);
                }

                File keysDir = new File(keyDirPath);
                if (keysDir.exists() && keysDir.isDirectory()) {
                    zipFile.addFolder(keysDir, params);
                }
            }

            byte[] data = Files.readAllBytes(Path.of(zipPath));
            Files.deleteIfExists(Path.of(zipPath));
            Files.deleteIfExists(tempDir);
            return data;
        } catch (Exception e) {
            throw new OciException("创建备份失败: " + e.getMessage());
        }
    }

    public void restoreBackup(byte[] data, String password) {
        try {
            Path tempFile = Files.createTempFile("oci-worker-restore-", ".zip");
            Files.write(tempFile, data);

            try (ZipFile zipFile = new ZipFile(tempFile.toFile(), password.toCharArray())) {
                zipFile.extractAll(".");
            }

            Files.deleteIfExists(tempFile);
            log.info("Backup restored successfully");
        } catch (Exception e) {
            throw new OciException("恢复备份失败: " + e.getMessage());
        }
    }
}
