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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final String[] TABLES = {
            "oci_user",
            "oci_create_task",
            "oci_kv",
            "cf_cfg",
            "ip_data",
            "oci_openai_key",
            "oci_openai_port_binding",
            "oci_login_audit",
            "oci_openai_lb_key",
            "oci_openai_lb_member",
            "oci_openai_lb_usage_window",
            "oci_openai_lb_request_log",
            "oci_openai_lb_member_model_state"
    };

    private static final Pattern LEGACY_INSERT_PATTERN = Pattern.compile(
            "(?is)^INSERT\\s+INTO\\s+`?([a-zA-Z0-9_]+)`?\\s+VALUES\\s*(\\(.*\\))$"
    );

    private static final Map<String, Map<Integer, List<String>>> LEGACY_INSERT_COLUMNS = buildLegacyInsertColumns();

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

                if (!tableExists(conn, table)) {
                    sb.append("-- WARN: table ").append(table).append(" does not exist, skipped\n\n");
                    continue;
                }

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT * FROM `" + table + "`")) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    String columns = buildColumnList(meta);

                    while (rs.next()) {
                        sb.append("INSERT INTO `").append(table).append("` (").append(columns).append(") VALUES (");
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
                executable = addColumnsToLegacyInsert(conn, executable);
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

    private static String buildColumnList(ResultSetMetaData meta) throws SQLException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (i > 1) {
                sb.append(", ");
            }
            sb.append('`').append(meta.getColumnName(i).replace("`", "``")).append('`');
        }
        return sb.toString();
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(conn.getCatalog(), null, table, new String[]{"TABLE"})) {
            if (rs.next()) {
                return true;
            }
        }
        try (ResultSet rs = conn.getMetaData().getTables(conn.getCatalog(), null, table.toUpperCase(Locale.ROOT), new String[]{"TABLE"})) {
            if (rs.next()) {
                return true;
            }
        }
        try (ResultSet rs = conn.getMetaData().getTables(conn.getCatalog(), null, table.toLowerCase(Locale.ROOT), new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private String addColumnsToLegacyInsert(Connection conn, String sql) throws SQLException {
        Matcher matcher = LEGACY_INSERT_PATTERN.matcher(sql.trim());
        if (!matcher.matches()) {
            return sql;
        }

        String table = matcher.group(1);
        String values = matcher.group(2).trim();
        int valueCount = countSqlValues(values);
        if (valueCount <= 0) {
            return sql;
        }

        List<String> columns = findLegacyColumns(table, valueCount);
        if (columns == null) {
            List<String> currentColumns = getCurrentColumns(conn, table);
            if (currentColumns.size() == valueCount) {
                columns = currentColumns;
            }
        }
        if (columns == null) {
            throw new SQLException("旧备份兼容失败: 表 " + table + " 的备份字段数为 " + valueCount + "，当前版本无法识别该历史结构");
        }
        return "INSERT INTO `" + table + "` (" + quoteColumns(columns) + ") VALUES " + values;
    }

    private static List<String> findLegacyColumns(String table, int valueCount) {
        Map<Integer, List<String>> byCount = LEGACY_INSERT_COLUMNS.get(table.toLowerCase(Locale.ROOT));
        return byCount == null ? null : byCount.get(valueCount);
    }

    private static String quoteColumns(List<String> columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('`').append(columns.get(i).replace("`", "``")).append('`');
        }
        return sb.toString();
    }

    private static List<String> getCurrentColumns(Connection conn, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM `" + table + "` LIMIT 0")) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                columns.add(meta.getColumnName(i));
            }
        }
        return columns;
    }

    private static int countSqlValues(String values) {
        String s = values.trim();
        if (!s.startsWith("(") || !s.endsWith(")")) {
            return -1;
        }

        int count = 1;
        boolean inQuote = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inQuote) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '\'') {
                    inQuote = false;
                }
                continue;
            }
            if (ch == '\'') {
                inQuote = true;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else if (ch == ',' && depth == 1) {
                count++;
            }
        }
        return count;
    }

    private static Map<String, Map<Integer, List<String>>> buildLegacyInsertColumns() {
        Map<String, Map<Integer, List<String>>> map = new HashMap<>();

        addLegacyColumns(map, "oci_user", "id", "username", "tenant_name", "tenant_create_time",
                "oci_tenant_id", "oci_user_id", "oci_fingerprint", "oci_region", "oci_key_path",
                "plan_type", "create_time");
        addLegacyColumns(map, "oci_user", "id", "username", "tenant_name", "tenant_create_time",
                "oci_tenant_id", "oci_user_id", "oci_fingerprint", "oci_region", "oci_key_path",
                "plan_type", "generative_openai_project", "generative_conversation_store_id", "create_time");
        addLegacyColumns(map, "oci_user", "id", "username", "tenant_name", "tenant_create_time",
                "oci_tenant_id", "oci_user_id", "oci_fingerprint", "oci_region", "oci_key_path",
                "plan_type", "group_level1", "group_level2", "generative_openai_project",
                "generative_conversation_store_id", "create_time");

        addLegacyColumns(map, "oci_create_task", "id", "user_id", "oci_region", "ocpus", "memory",
                "disk", "architecture", "interval_seconds", "create_numbers", "root_password",
                "operation_system", "status", "attempt_count", "create_time");
        addLegacyColumns(map, "oci_create_task", "id", "user_id", "oci_region", "ocpus", "memory",
                "disk", "architecture", "interval_seconds", "create_numbers", "root_password",
                "operation_system", "custom_script", "status", "attempt_count", "create_time");
        addLegacyColumns(map, "oci_create_task", "id", "user_id", "oci_region", "ocpus", "memory",
                "disk", "architecture", "interval_seconds", "create_numbers", "root_password",
                "operation_system", "custom_script", "status", "attempt_count", "success_count",
                "created_instances", "create_time");
        addLegacyColumns(map, "oci_create_task", "id", "user_id", "oci_region", "ocpus", "memory",
                "disk", "vpus_per_gb", "architecture", "interval_seconds", "create_numbers",
                "root_password", "operation_system", "custom_script", "status", "attempt_count",
                "success_count", "created_instances", "failure_reason", "create_time");
        addLegacyColumns(map, "oci_create_task", "id", "user_id", "oci_region", "ocpus", "memory",
                "disk", "vpus_per_gb", "architecture", "interval_seconds", "create_numbers",
                "root_password", "operation_system", "custom_script", "assign_public_ip", "assign_ipv6",
                "status", "attempt_count", "success_count", "created_instances", "failure_reason", "create_time");
        addLegacyColumns(map, "oci_create_task", "id", "user_id", "oci_region", "ocpus", "memory",
                "disk", "vpus_per_gb", "architecture", "interval_seconds", "create_numbers",
                "root_password", "login_mode", "ssh_public_key", "operation_system", "custom_script",
                "assign_public_ip", "assign_ipv6",
                "status", "status_time", "attempt_count", "success_count", "created_instances",
                "failure_reason", "create_time");

        addLegacyColumns(map, "oci_login_audit", "id", "account", "password_attempt", "ip", "success",
                "device_id", "os_name", "browser_name", "login_channel", "user_agent", "create_time");
        addLegacyColumns(map, "oci_login_audit", "id", "account", "password_attempt", "ip", "success",
                "device_id", "os_name", "browser_name", "login_channel", "user_agent", "login_detail", "create_time");

        addLegacyColumns(map, "oci_openai_key", "id", "oci_user_id", "key_hash", "key_prefix",
                "name", "disabled", "create_time", "last_used");
        addLegacyColumns(map, "oci_openai_key", "id", "oci_user_id", "key_hash", "key_prefix",
                "key_encrypted", "name", "disabled", "create_time", "last_used");

        addLegacyColumns(map, "oci_openai_port_binding", "id", "name", "port", "oci_user_id",
                "openai_key_id", "enabled", "create_time");
        addLegacyColumns(map, "oci_openai_port_binding", "id", "name", "port", "oci_user_id",
                "oci_region", "openai_key_id", "enabled", "create_time");
        addLegacyColumns(map, "oci_openai_port_binding", "id", "name", "port", "oci_user_id",
                "oci_region", "openai_key_id", "default_max_tokens", "allowed_models_json", "enabled",
                "status", "status_message", "create_time", "update_time", "last_used");

        addLegacyColumns(map, "oci_openai_lb_member", "id", "port_binding_id", "weight", "enabled",
                "fail_count", "cooldown_until", "last_error", "request_limit5h", "request_limit7d",
                "last_used", "create_time", "update_time");
        addLegacyColumns(map, "oci_openai_lb_member", "id", "port_binding_id", "weight", "enabled",
                "fail_count", "cooldown_until", "last_error", "request_limit5h", "request_limit7d",
                "max_concurrency", "rpm_limit", "tpm_limit", "context_limit",
                "stream_first_chunk_timeout_seconds", "stream_idle_timeout_seconds", "stream_max_seconds",
                "health_status", "health_message", "health_checked_at", "last_latency_ms", "last_status",
                "last_error_type", "ewma_success_rate", "ewma_latency_ms", "recovery_until", "last_used",
                "create_time", "update_time");

        addLegacyColumns(map, "oci_openai_lb_key", "id", "key_hash", "key_prefix", "key_encrypted",
                "name", "disabled", "create_time", "last_used");
        addLegacyColumns(map, "oci_openai_lb_usage_window", "id", "member_id", "window_start",
                "request_count", "success_count", "failure_count", "token_count", "create_time", "update_time");
        addLegacyColumns(map, "oci_openai_lb_request_log", "id", "request_id", "lb_key_id", "member_id",
                "port_binding_id", "port", "model", "stream", "estimated_prompt_tokens", "status_code",
                "status", "error_type", "error_message", "latency_ms", "first_chunk_ms", "chunk_count",
                "token_count", "client_aborted", "retry_count", "create_time", "update_time");
        addLegacyColumns(map, "oci_openai_lb_member_model_state", "id", "member_id", "model", "status",
                "fail_count", "success_count", "unavailable_until", "last_status", "last_error",
                "last_checked_at", "create_time", "update_time");

        addLegacyColumns(map, "oci_kv", "id", "code", "value", "type", "create_time");
        addLegacyColumns(map, "cf_cfg", "id", "domain", "zone_id", "api_token", "create_time");
        addLegacyColumns(map, "ip_data", "id", "ip", "country", "area", "city", "org", "asn", "type",
                "lat", "lng", "create_time");

        return map;
    }

    private static void addLegacyColumns(Map<String, Map<Integer, List<String>>> map, String table, String... columns) {
        map.computeIfAbsent(table.toLowerCase(Locale.ROOT), ignored -> new HashMap<>())
                .put(columns.length, List.of(columns));
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
