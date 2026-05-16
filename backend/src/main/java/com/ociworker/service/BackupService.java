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
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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
                            appendSqlLiteral(sb, val);
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

    /**
     * 生成 INSERT 可嵌入的字面值。TINYINT(1)、BIT 等列常映射为 {@link Boolean}，
     * 若统一按字符串 {@code 'true'} 写入，MySQL 在严格模式下会报
     * {@code Incorrect integer value: 'true' for column ...}。
     */
    private static void appendSqlLiteral(StringBuilder sb, Object val) {
        if (val == null) {
            sb.append("NULL");
            return;
        }
        if (val instanceof Boolean) {
            sb.append((Boolean) val ? "1" : "0");
            return;
        }
        if (val instanceof Number) {
            sb.append(val);
            return;
        }
        if (val instanceof byte[]) {
            byte[] b = (byte[]) val;
            if (b.length == 0) {
                sb.append("''");
            } else {
                sb.append("0x");
                for (byte x : b) {
                    sb.append(String.format("%02x", x));
                }
            }
            return;
        }
        if (val instanceof java.sql.Date) {
            sb.append('\'').append(((java.sql.Date) val).toString()).append('\'');
            return;
        }
        if (val instanceof java.sql.Time) {
            sb.append('\'').append(((java.sql.Time) val).toString()).append('\'');
            return;
        }
        if (val instanceof java.sql.Timestamp) {
            sb.append('\'').append(
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((java.sql.Timestamp) val)
            ).append('\'');
            return;
        }
        if (val instanceof java.time.LocalDateTime) {
            String s = val.toString().replace('T', ' ');
            if (s.length() > 19 && s.charAt(19) == '.') {
                s = s.substring(0, 19);
            }
            sb.append('\'').append(s).append('\'');
            return;
        }
        if (val instanceof java.time.LocalDate) {
            sb.append('\'').append(val).append('\'');
            return;
        }
        if (val instanceof java.time.LocalTime) {
            sb.append('\'').append(val).append('\'');
            return;
        }
        String s = val.toString();
        if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
            sb.append("true".equalsIgnoreCase(s) ? "1" : "0");
            return;
        }
        sb.append('\'')
                .append(s.replace("\\", "\\\\").replace("'", "\\'"))
                .append('\'');
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
                executable = fixLegacyBooleanStringLiterals(executable);
                stmt.execute(executable);
            }
            conn.commit();
        }
    }

    /**
     * 旧版导出曾把 TINYINT(1)/布尔列写成 'true'/'false' 字符串，恢复时报 Incorrect integer value。
     * 在不含中文等复杂字符串的前提下，将典型形态替换为 0/1；新版备份已直接导出数字，不受影响。
     */
    private static String fixLegacyBooleanStringLiterals(String sql) {
        if (!sql.toUpperCase(java.util.Locale.ROOT).contains("INSERT")) {
            return sql;
        }
        return sql.replace(", 'true',", ", 1,")
                .replace(", 'false',", ", 0,")
                .replace(", 'true')", ", 1)")
                .replace(", 'false')", ", 0)")
                .replace("('true',", "(1,")
                .replace("('false',", "(0,");
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
