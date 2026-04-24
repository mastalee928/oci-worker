package com.ociworker.service;

import com.ociworker.exception.OciException;
import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class BackupService {

    @Value("${oci-cfg.key-dir-path}")
    private String keyDirPath;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUser;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    private final DataSource dataSource;

    public BackupService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private static final String[] TABLES = {"oci_user", "oci_create_task", "oci_kv", "cf_cfg", "ip_data"};

    public byte[] createBackup(String password) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path tempDir = Files.createTempDirectory("oci-worker-backup-");
            Path sqlDumpFile = tempDir.resolve("oci-worker-dump.sql");

            String sqlDump = exportDatabase();
            Files.writeString(sqlDumpFile, sqlDump);

            String zipPath = tempDir.resolve("oci-worker-backup-" + timestamp + ".zip").toString();

            ZipParameters params = new ZipParameters();
            params.setCompressionLevel(CompressionLevel.NORMAL);
            params.setEncryptFiles(true);
            params.setEncryptionMethod(EncryptionMethod.AES);

            try (ZipFile zipFile = new ZipFile(zipPath, password.toCharArray())) {
                zipFile.addFile(sqlDumpFile.toFile(), params);

                File keysDir = new File(keyDirPath);
                if (keysDir.exists() && keysDir.isDirectory()) {
                    zipFile.addFolder(keysDir, params);
                }
            }

            byte[] data = Files.readAllBytes(Path.of(zipPath));

            Files.deleteIfExists(sqlDumpFile);
            Files.deleteIfExists(Path.of(zipPath));
            Files.deleteIfExists(tempDir);
            return data;
        } catch (Exception e) {
            throw new OciException("创建备份失败: " + e.getMessage());
        }
    }

    public void restoreBackup(byte[] data, String password) {
        try {
            Path tempDir = Files.createTempDirectory("oci-worker-restore-");
            Path tempFile = tempDir.resolve("restore.zip");
            Files.write(tempFile, data);

            try (ZipFile zipFile = new ZipFile(tempFile.toFile(), password.toCharArray())) {
                zipFile.extractAll(tempDir.toString());
            }

            Path sqlDumpFile = tempDir.resolve("oci-worker-dump.sql");
            if (Files.exists(sqlDumpFile)) {
                String sql = Files.readString(sqlDumpFile);
                importDatabase(sql.replace("\r\n", "\n").replace("\r", "\n"));
            }

            Path keysSource = tempDir.resolve("keys");
            if (Files.exists(keysSource)) {
                File keysTarget = new File(keyDirPath);
                if (!keysTarget.exists()) {
                    keysTarget.mkdirs();
                }
                copyDirectory(keysSource, keysTarget.toPath());
            }

            deleteDirectory(tempDir);
            log.info("Backup restored successfully");
        } catch (Exception e) {
            throw new OciException("恢复备份失败: " + e.getMessage());
        }
    }

    private String exportDatabase() throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("-- OCI Worker Database Backup\n");
        sb.append("-- Generated: ").append(LocalDateTime.now()).append("\n\n");
        sb.append("SET FOREIGN_KEY_CHECKS=0;\n\n");

        try (Connection conn = dataSource.getConnection()) {
            for (String table : TABLES) {
                sb.append("-- Table: ").append(table).append("\n");
                sb.append("DELETE FROM `").append(table).append("`;\n");

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT * FROM " + table)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();

                    while (rs.next()) {
                        sb.append("INSERT INTO `").append(table).append("` VALUES (");
                        for (int i = 1; i <= colCount; i++) {
                            if (i > 1) sb.append(", ");
                            Object val = rs.getObject(i);
                            if (val == null) {
                                sb.append("NULL");
                            } else {
                                sb.append("'").append(val.toString().replace("'", "\\'")).append("'");
                            }
                        }
                        sb.append(");\n");
                    }
                }
                sb.append("\n");
            }
        }

        sb.append("SET FOREIGN_KEY_CHECKS=1;\n");
        return sb.toString();
    }

    private void importDatabase(String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            for (String chunk : sql.split(";\n")) {
                // 不能整段用 startsWith("--") 跳过：导出里「-- Table: x」与「DELETE」在同一段时，会把 DELETE 一并丢掉，导致只执行 INSERT → 主键重复
                String executable = stripSqlComments(chunk);
                if (executable.isEmpty()) {
                    continue;
                }
                stmt.execute(executable);
            }
            conn.commit();
        }
    }

    /** 去掉块内以 -- 开头的注释行，保留 SET/DELETE/INSERT 等可执行语句 */
    private static String stripSqlComments(String chunk) {
        StringBuilder out = new StringBuilder();
        for (String line : chunk.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("--")) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line.trim());
        }
        return out.toString().trim();
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            walk.forEach(src -> {
                try {
                    Path dest = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
            }
        }
    }
}
