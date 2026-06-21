package com.ociworker.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
public class DatabaseGuardService {

    @Resource
    private DataSource dataSource;
    @Resource
    private NotificationService notificationService;

    private static final String BACKUP_DIR = "./db-backups";
    private static final int KEEP_DAYS = 7;

    private static final Map<String, String> TABLE_DDL = new LinkedHashMap<>();

    static {
        TABLE_DDL.put("oci_user", """
            CREATE TABLE IF NOT EXISTS oci_user (
                id VARCHAR(64) PRIMARY KEY,
                username VARCHAR(64),
                tenant_name VARCHAR(64),
                tenant_create_time DATETIME,
                tenant_name_status VARCHAR(32) DEFAULT 'PENDING',
                tenant_name_error VARCHAR(512) DEFAULT NULL,
                tenant_name_updated_at DATETIME DEFAULT NULL,
                oci_tenant_id VARCHAR(128) NOT NULL,
                oci_user_id VARCHAR(128),
                oci_fingerprint VARCHAR(128) NOT NULL,
                oci_region VARCHAR(32) NOT NULL,
                oci_key_path VARCHAR(256) NOT NULL,
                plan_type VARCHAR(32),
                plan_type_status VARCHAR(32) DEFAULT 'PENDING',
                plan_type_error VARCHAR(512) DEFAULT NULL,
                plan_type_updated_at DATETIME DEFAULT NULL,
                info_retry_count INT DEFAULT 0,
                info_next_retry_at DATETIME DEFAULT NULL,
                group_level1 VARCHAR(64) DEFAULT NULL,
                group_level2 VARCHAR(64) DEFAULT NULL,
                generative_openai_project VARCHAR(512) DEFAULT NULL,
                generative_conversation_store_id VARCHAR(512) DEFAULT NULL,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE KEY uk_oci_user_tenant_id (oci_tenant_id),
                INDEX idx_oci_user_create_time (create_time DESC)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        TABLE_DDL.put("oci_create_task", """
            CREATE TABLE IF NOT EXISTS oci_create_task (
                id VARCHAR(64) PRIMARY KEY,
                user_id VARCHAR(64),
                oci_region VARCHAR(64),
                ocpus DOUBLE DEFAULT 1.0,
                memory DOUBLE DEFAULT 6.0,
                disk INT DEFAULT 50,
                vpus_per_gb INT DEFAULT 10,
                architecture VARCHAR(64) DEFAULT 'ARM',
                interval_seconds INT DEFAULT 60,
                create_numbers INT DEFAULT 1,
                root_password VARCHAR(64),
                operation_system VARCHAR(64) DEFAULT 'Ubuntu',
                custom_script TEXT,
                assign_public_ip TINYINT(1) DEFAULT 1,
                assign_ipv6 TINYINT(1) DEFAULT 0,
                status VARCHAR(16) DEFAULT 'RUNNING',
                attempt_count INT DEFAULT 0,
                success_count INT DEFAULT 0,
                created_instances TEXT DEFAULT NULL,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_oci_create_task_create_time (create_time DESC)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        TABLE_DDL.put("oci_kv", """
            CREATE TABLE IF NOT EXISTS oci_kv (
                id VARCHAR(64) PRIMARY KEY,
                code VARCHAR(64) NOT NULL,
                value TEXT,
                type VARCHAR(64) NOT NULL,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_oci_kv_code (code),
                INDEX idx_oci_kv_type (type)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        TABLE_DDL.put("cf_cfg", """
            CREATE TABLE IF NOT EXISTS cf_cfg (
                id VARCHAR(64) PRIMARY KEY,
                domain VARCHAR(64) NOT NULL,
                zone_id VARCHAR(255) NOT NULL,
                api_token VARCHAR(255) NOT NULL,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        TABLE_DDL.put("ip_data", """
            CREATE TABLE IF NOT EXISTS ip_data (
                id VARCHAR(64) PRIMARY KEY,
                ip VARCHAR(255) NOT NULL,
                country VARCHAR(255),
                area VARCHAR(120),
                city VARCHAR(120),
                org VARCHAR(120),
                asn VARCHAR(64),
                type VARCHAR(64),
                lat DOUBLE,
                lng DOUBLE,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        TABLE_DDL.put("oci_openai_key", """
            CREATE TABLE IF NOT EXISTS oci_openai_key (
                id VARCHAR(64) PRIMARY KEY,
                oci_user_id VARCHAR(64) NOT NULL,
                key_hash VARCHAR(64) NOT NULL,
                key_prefix VARCHAR(32) NOT NULL,
                name VARCHAR(128) DEFAULT NULL,
                disabled TINYINT(1) NOT NULL DEFAULT 0,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                last_used DATETIME DEFAULT NULL,
                UNIQUE KEY uk_oci_openai_key_hash (key_hash),
                INDEX idx_oci_openai_key_user (oci_user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        TABLE_DDL.put("oci_openai_port_binding", """
            CREATE TABLE IF NOT EXISTS oci_openai_port_binding (
                id VARCHAR(64) PRIMARY KEY,
                name VARCHAR(128) DEFAULT NULL,
                port INT NOT NULL,
                oci_user_id VARCHAR(64) NOT NULL,
                oci_region VARCHAR(64) DEFAULT NULL,
                openai_key_id VARCHAR(64) NOT NULL,
                default_max_tokens INT DEFAULT NULL,
                allowed_models_json TEXT DEFAULT NULL,
                enabled TINYINT(1) NOT NULL DEFAULT 1,
                status VARCHAR(32) DEFAULT 'stopped',
                status_message VARCHAR(512) DEFAULT NULL,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                update_time DATETIME DEFAULT NULL,
                last_used DATETIME DEFAULT NULL,
                UNIQUE KEY uk_oci_openai_port_binding_port (port),
                INDEX idx_oci_openai_port_binding_user (oci_user_id),
                INDEX idx_oci_openai_port_binding_region (oci_region),
                INDEX idx_oci_openai_port_binding_key (openai_key_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        TABLE_DDL.put("oci_openai_lb_key", """
            CREATE TABLE IF NOT EXISTS oci_openai_lb_key (
                id VARCHAR(64) PRIMARY KEY,
                key_hash VARCHAR(64) NOT NULL,
                key_prefix VARCHAR(32) NOT NULL,
                key_encrypted TEXT DEFAULT NULL,
                name VARCHAR(128) DEFAULT NULL,
                disabled TINYINT(1) NOT NULL DEFAULT 0,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                last_used DATETIME DEFAULT NULL,
                UNIQUE KEY uk_oci_openai_lb_key_hash (key_hash)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        TABLE_DDL.put("oci_openai_lb_member", """
            CREATE TABLE IF NOT EXISTS oci_openai_lb_member (
                id VARCHAR(64) PRIMARY KEY,
                port_binding_id VARCHAR(64) NOT NULL,
                weight INT NOT NULL DEFAULT 1,
                enabled TINYINT(1) NOT NULL DEFAULT 1,
                fail_count INT NOT NULL DEFAULT 0,
                cooldown_until DATETIME DEFAULT NULL,
                last_error VARCHAR(512) DEFAULT NULL,
                request_limit5h INT DEFAULT NULL,
                request_limit7d INT DEFAULT NULL,
                max_concurrency INT DEFAULT NULL,
                rpm_limit INT DEFAULT NULL,
                tpm_limit BIGINT DEFAULT NULL,
                context_limit INT DEFAULT NULL,
                stream_first_chunk_timeout_seconds INT DEFAULT NULL,
                stream_idle_timeout_seconds INT DEFAULT NULL,
                stream_max_seconds INT DEFAULT NULL,
                health_status VARCHAR(32) DEFAULT 'unknown',
                health_message VARCHAR(512) DEFAULT NULL,
                health_checked_at DATETIME DEFAULT NULL,
                last_latency_ms INT DEFAULT NULL,
                last_status INT DEFAULT NULL,
                last_error_type VARCHAR(64) DEFAULT NULL,
                ewma_success_rate DOUBLE DEFAULT NULL,
                ewma_latency_ms BIGINT DEFAULT NULL,
                recovery_until DATETIME DEFAULT NULL,
                last_used DATETIME DEFAULT NULL,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                update_time DATETIME DEFAULT NULL,
                UNIQUE KEY uk_oci_openai_lb_member_binding (port_binding_id),
                INDEX idx_oci_openai_lb_member_enabled (enabled),
                INDEX idx_oci_openai_lb_member_cooldown (cooldown_until)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        TABLE_DDL.put("oci_openai_lb_usage_window", """
            CREATE TABLE IF NOT EXISTS oci_openai_lb_usage_window (
                id VARCHAR(64) PRIMARY KEY,
                member_id VARCHAR(64) NOT NULL,
                window_start DATETIME NOT NULL,
                request_count INT NOT NULL DEFAULT 0,
                success_count INT NOT NULL DEFAULT 0,
                failure_count INT NOT NULL DEFAULT 0,
                token_count BIGINT NOT NULL DEFAULT 0,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                update_time DATETIME DEFAULT NULL,
                UNIQUE KEY uk_oci_openai_lb_usage_member_window (member_id, window_start),
                INDEX idx_oci_openai_lb_usage_window_start (window_start)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        TABLE_DDL.put("oci_openai_lb_request_log", """
            CREATE TABLE IF NOT EXISTS oci_openai_lb_request_log (
                id VARCHAR(64) PRIMARY KEY,
                request_id VARCHAR(64) NOT NULL,
                lb_key_id VARCHAR(64) DEFAULT NULL,
                member_id VARCHAR(64) DEFAULT NULL,
                port_binding_id VARCHAR(64) DEFAULT NULL,
                port INT DEFAULT NULL,
                model VARCHAR(256) DEFAULT NULL,
                stream TINYINT(1) NOT NULL DEFAULT 0,
                estimated_prompt_tokens BIGINT DEFAULT 0,
                status_code INT DEFAULT NULL,
                status VARCHAR(32) DEFAULT NULL,
                error_type VARCHAR(64) DEFAULT NULL,
                error_message VARCHAR(512) DEFAULT NULL,
                latency_ms BIGINT DEFAULT NULL,
                first_chunk_ms BIGINT DEFAULT NULL,
                chunk_count INT DEFAULT 0,
                token_count BIGINT DEFAULT 0,
                client_aborted TINYINT(1) NOT NULL DEFAULT 0,
                retry_count INT NOT NULL DEFAULT 0,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                update_time DATETIME DEFAULT NULL,
                INDEX idx_oci_openai_lb_req_time (create_time DESC),
                INDEX idx_oci_openai_lb_req_member_time (member_id, create_time),
                INDEX idx_oci_openai_lb_req_request (request_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        TABLE_DDL.put("oci_openai_lb_member_model_state", """
            CREATE TABLE IF NOT EXISTS oci_openai_lb_member_model_state (
                id VARCHAR(64) PRIMARY KEY,
                member_id VARCHAR(64) NOT NULL,
                model VARCHAR(256) NOT NULL,
                status VARCHAR(32) NOT NULL DEFAULT 'unknown',
                fail_count INT NOT NULL DEFAULT 0,
                success_count INT NOT NULL DEFAULT 0,
                unavailable_until DATETIME DEFAULT NULL,
                last_status INT DEFAULT NULL,
                last_error VARCHAR(512) DEFAULT NULL,
                last_checked_at DATETIME DEFAULT NULL,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                update_time DATETIME DEFAULT NULL,
                UNIQUE KEY uk_oci_openai_lb_member_model (member_id, model),
                INDEX idx_oci_openai_lb_member_model_status (status),
                INDEX idx_oci_openai_lb_member_model_unavailable (unavailable_until)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        TABLE_DDL.put("oci_login_audit", """
            CREATE TABLE IF NOT EXISTS oci_login_audit (
                id VARCHAR(64) PRIMARY KEY,
                account VARCHAR(128) DEFAULT NULL,
                password_attempt VARCHAR(512) DEFAULT NULL,
                ip VARCHAR(255) DEFAULT NULL,
                success TINYINT(1) NOT NULL DEFAULT 0,
                device_id VARCHAR(128) DEFAULT NULL,
                os_name VARCHAR(128) DEFAULT NULL,
                browser_name VARCHAR(128) DEFAULT NULL,
                login_channel VARCHAR(32) DEFAULT 'password',
                user_agent TEXT,
                login_detail MEDIUMTEXT NULL,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_oci_login_audit_time (create_time DESC)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
    }

    @PostConstruct
    public void startupCheck() {
        log.info("【数据库守护】启动自检...");
        List<String> missing = new ArrayList<>();
        List<String> repaired = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            Set<String> existing = getExistingTables(conn);

            for (Map.Entry<String, String> entry : TABLE_DDL.entrySet()) {
                String table = entry.getKey();
                if (!existing.contains(table)) {
                    missing.add(table);
                    log.warn("【数据库守护】表 {} 不存在，正在自动创建...", table);
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(entry.getValue());
                    }
                    repaired.add(table);
                    log.info("【数据库守护】表 {} 已自动创建", table);
                }
            }

            migrateColumns(conn);
        } catch (Exception e) {
            log.error("【数据库守护】启动自检失败: {}", e.getMessage(), e);
            sendAlert("启动自检失败", "数据库连接异常: " + e.getMessage());
            return;
        }

        if (!missing.isEmpty()) {
            String msg = String.format("检测到 %d 张表缺失: %s\n已自动修复: %s",
                    missing.size(), String.join(", ", missing), String.join(", ", repaired));
            log.warn("【数据库守护】{}", msg);
            sendAlert("表缺失已自动修复", msg);
        } else {
            log.info("【数据库守护】所有表正常 ✓");
        }
    }

    /**
     * 每6小时巡检一次表完整性
     */
    @Scheduled(cron = "0 0 */6 * * ?")
    public void periodicCheck() {
        log.info("【数据库守护】定时巡检...");
        List<String> problems = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            Set<String> existing = getExistingTables(conn);

            for (Map.Entry<String, String> entry : TABLE_DDL.entrySet()) {
                String table = entry.getKey();
                if (!existing.contains(table)) {
                    log.warn("【数据库守护】巡检发现表 {} 缺失，自动修复", table);
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(entry.getValue());
                    }
                    problems.add(table + "(已修复)");
                }
            }
        } catch (Exception e) {
            log.error("【数据库守护】巡检异常: {}", e.getMessage());
            sendAlert("巡检异常", "数据库连接失败: " + e.getMessage());
            return;
        }

        if (!problems.isEmpty()) {
            sendAlert("巡检发现异常", "缺失表: " + String.join(", ", problems));
        }
    }

    /**
     * 每6小时自动备份（00:00, 06:00, 12:00, 18:00）
     */
    @Scheduled(cron = "0 0 */6 * * ?")
    public void autoBackup() {
        log.info("【数据库守护】开始自动备份...");
        try {
            Path backupDir = Path.of(BACKUP_DIR);
            Files.createDirectories(backupDir);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path backupFile = backupDir.resolve("auto_backup_" + timestamp + ".sql");

            String dump = exportAllTables();
            Files.writeString(backupFile, dump);

            long sizeKB = Files.size(backupFile) / 1024;
            log.info("【数据库守护】自动备份完成: {} ({}KB)", backupFile.getFileName(), sizeKB);

            cleanOldBackups(backupDir);
        } catch (Exception e) {
            log.error("【数据库守护】自动备份失败: {}", e.getMessage(), e);
            sendAlert("自动备份失败", e.getMessage());
        }
    }

    private String exportAllTables() throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("-- OCI Worker Auto Backup\n");
        sb.append("-- Generated: ").append(LocalDateTime.now()).append("\n\n");
        sb.append("SET NAMES utf8mb4;\n");
        sb.append("SET FOREIGN_KEY_CHECKS=0;\n\n");

        try (Connection conn = dataSource.getConnection()) {
            for (Map.Entry<String, String> entry : TABLE_DDL.entrySet()) {
                String table = entry.getKey();
                sb.append("-- Table structure: ").append(table).append("\n");
                sb.append("DROP TABLE IF EXISTS `").append(table).append("`;\n");
                sb.append(entry.getValue()).append(";\n\n");

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT * FROM " + table)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    sb.append("-- Data: ").append(table).append("\n");

                    while (rs.next()) {
                        sb.append("INSERT INTO `").append(table).append("` VALUES (");
                        for (int i = 1; i <= colCount; i++) {
                            if (i > 1) sb.append(", ");
                            Object val = rs.getObject(i);
                            if (val == null) {
                                sb.append("NULL");
                            } else {
                                sb.append("'").append(val.toString().replace("\\", "\\\\").replace("'", "\\'")).append("'");
                            }
                        }
                        sb.append(");\n");
                    }
                    sb.append("\n");
                } catch (SQLException e) {
                    sb.append("-- WARN: table ").append(table).append(" export failed: ").append(e.getMessage()).append("\n\n");
                }
            }
        }

        sb.append("SET FOREIGN_KEY_CHECKS=1;\n");
        return sb.toString();
    }

    private void cleanOldBackups(Path backupDir) throws IOException {
        LocalDate cutoff = LocalDate.now().minusDays(KEEP_DAYS);
        try (var files = Files.list(backupDir)) {
            files.filter(p -> p.getFileName().toString().startsWith("auto_backup_"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant()
                                    .isBefore(cutoff.atStartOfDay().toInstant(java.time.ZoneOffset.systemDefault().getRules().getOffset(java.time.Instant.now())));
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                            log.info("【数据库守护】清理过期备份: {}", p.getFileName());
                        } catch (IOException ignored) {}
                    });
        }
    }

    private void migrateColumns(Connection conn) {
        addColumnIfMissing(conn, "oci_user", "tenant_name_status", "VARCHAR(32) DEFAULT 'PENDING' AFTER tenant_create_time");
        addColumnIfMissing(conn, "oci_user", "tenant_name_error", "VARCHAR(512) DEFAULT NULL AFTER tenant_name_status");
        addColumnIfMissing(conn, "oci_user", "tenant_name_updated_at", "DATETIME DEFAULT NULL AFTER tenant_name_error");
        addColumnIfMissing(conn, "oci_user", "plan_type_status", "VARCHAR(32) DEFAULT 'PENDING' AFTER plan_type");
        addColumnIfMissing(conn, "oci_user", "plan_type_error", "VARCHAR(512) DEFAULT NULL AFTER plan_type_status");
        addColumnIfMissing(conn, "oci_user", "plan_type_updated_at", "DATETIME DEFAULT NULL AFTER plan_type_error");
        addColumnIfMissing(conn, "oci_user", "info_retry_count", "INT DEFAULT 0 AFTER plan_type_updated_at");
        addColumnIfMissing(conn, "oci_user", "info_next_retry_at", "DATETIME DEFAULT NULL AFTER info_retry_count");
        addColumnIfMissing(conn, "oci_user", "group_level1", "VARCHAR(64) DEFAULT NULL AFTER plan_type");
        addColumnIfMissing(conn, "oci_user", "group_level2", "VARCHAR(64) DEFAULT NULL AFTER group_level1");
        addColumnIfMissing(conn, "oci_user", "generative_openai_project", "VARCHAR(512) DEFAULT NULL AFTER group_level2");
        addColumnIfMissing(conn, "oci_user", "generative_conversation_store_id", "VARCHAR(512) DEFAULT NULL AFTER generative_openai_project");
        addUniqueIndexIfMissing(conn, "oci_user", "uk_oci_user_tenant_id", "oci_tenant_id");
        addColumnIfMissing(conn, "oci_create_task", "custom_script", "TEXT DEFAULT NULL AFTER operation_system");
        addColumnIfMissing(conn, "oci_create_task", "vpus_per_gb", "INT DEFAULT 10 AFTER disk");
        addColumnIfMissing(conn, "oci_create_task", "assign_public_ip", "TINYINT(1) DEFAULT 1 AFTER custom_script");
        addColumnIfMissing(conn, "oci_create_task", "assign_ipv6", "TINYINT(1) DEFAULT 0 AFTER assign_public_ip");
        addColumnIfMissing(conn, "oci_create_task", "success_count", "INT DEFAULT 0 AFTER attempt_count");
        addColumnIfMissing(conn, "oci_create_task", "created_instances", "TEXT DEFAULT NULL AFTER success_count");
        addColumnIfMissing(conn, "oci_create_task", "failure_reason", "TEXT DEFAULT NULL AFTER created_instances");
        addColumnIfMissing(conn, "oci_login_audit", "login_detail",
                "MEDIUMTEXT NULL COMMENT 'JSON: 访问入口、网络与链路、客户端与能力' AFTER user_agent");
        addColumnIfMissing(conn, "oci_openai_key", "key_encrypted",
                "TEXT NULL COMMENT 'AES 加密完整 sk，供面板查看' AFTER key_prefix");
        addColumnIfMissing(conn, "oci_openai_port_binding", "status",
                "VARCHAR(32) DEFAULT 'stopped' AFTER enabled");
        addColumnIfMissing(conn, "oci_openai_port_binding", "default_max_tokens",
                "INT DEFAULT NULL AFTER openai_key_id");
        addColumnIfMissing(conn, "oci_openai_port_binding", "oci_region",
                "VARCHAR(64) DEFAULT NULL AFTER oci_user_id");
        addColumnIfMissing(conn, "oci_openai_port_binding", "allowed_models_json",
                "TEXT DEFAULT NULL AFTER default_max_tokens");
        addColumnIfMissing(conn, "oci_openai_port_binding", "status_message",
                "VARCHAR(512) DEFAULT NULL AFTER status");
        addColumnIfMissing(conn, "oci_openai_port_binding", "update_time",
                "DATETIME DEFAULT NULL AFTER create_time");
        addColumnIfMissing(conn, "oci_openai_port_binding", "last_used",
                "DATETIME DEFAULT NULL AFTER update_time");
        addColumnIfMissing(conn, "oci_openai_lb_member", "max_concurrency",
                "INT DEFAULT NULL AFTER request_limit7d");
        addColumnIfMissing(conn, "oci_openai_lb_member", "rpm_limit",
                "INT DEFAULT NULL AFTER max_concurrency");
        addColumnIfMissing(conn, "oci_openai_lb_member", "tpm_limit",
                "BIGINT DEFAULT NULL AFTER rpm_limit");
        addColumnIfMissing(conn, "oci_openai_lb_member", "context_limit",
                "INT DEFAULT NULL AFTER tpm_limit");
        addColumnIfMissing(conn, "oci_openai_lb_member", "stream_first_chunk_timeout_seconds",
                "INT DEFAULT NULL AFTER context_limit");
        addColumnIfMissing(conn, "oci_openai_lb_member", "stream_idle_timeout_seconds",
                "INT DEFAULT NULL AFTER stream_first_chunk_timeout_seconds");
        addColumnIfMissing(conn, "oci_openai_lb_member", "stream_max_seconds",
                "INT DEFAULT NULL AFTER stream_idle_timeout_seconds");
        addColumnIfMissing(conn, "oci_openai_lb_member", "health_status",
                "VARCHAR(32) DEFAULT 'unknown' AFTER stream_max_seconds");
        addColumnIfMissing(conn, "oci_openai_lb_member", "health_message",
                "VARCHAR(512) DEFAULT NULL AFTER health_status");
        addColumnIfMissing(conn, "oci_openai_lb_member", "health_checked_at",
                "DATETIME DEFAULT NULL AFTER health_message");
        addColumnIfMissing(conn, "oci_openai_lb_member", "last_latency_ms",
                "INT DEFAULT NULL AFTER health_checked_at");
        addColumnIfMissing(conn, "oci_openai_lb_member", "last_status",
                "INT DEFAULT NULL AFTER last_latency_ms");
        addColumnIfMissing(conn, "oci_openai_lb_member", "last_error_type",
                "VARCHAR(64) DEFAULT NULL AFTER last_status");
        addColumnIfMissing(conn, "oci_openai_lb_member", "ewma_success_rate",
                "DOUBLE DEFAULT NULL AFTER last_error_type");
        addColumnIfMissing(conn, "oci_openai_lb_member", "ewma_latency_ms",
                "BIGINT DEFAULT NULL AFTER ewma_success_rate");
        addColumnIfMissing(conn, "oci_openai_lb_member", "recovery_until",
                "DATETIME DEFAULT NULL AFTER ewma_latency_ms");
    }

    private void addColumnIfMissing(Connection conn, String table, String column, String definition) {
        try (ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, table, column)) {
            if (!rs.next()) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
                    log.info("【数据库守护】自动添加字段 {}.{}", table, column);
                }
            }
        } catch (SQLException e) {
            log.warn("【数据库守护】检查/添加字段 {}.{} 失败: {}", table, column, e.getMessage());
        }
    }

    private void addUniqueIndexIfMissing(Connection conn, String table, String indexName, String column) {
        try {
            if (indexExists(conn, table, indexName)) {
                return;
            }
            List<String> duplicates = findDuplicateValues(conn, table, column);
            if (!duplicates.isEmpty()) {
                log.warn("【数据库守护】{}.{} 存在重复值，暂不自动添加唯一索引 {}。请先合并重复项: {}",
                        table, column, indexName, String.join(", ", duplicates));
                return;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE `" + table + "` ADD UNIQUE KEY `" + indexName + "` (`" + column + "`)");
                log.info("【数据库守护】自动添加唯一索引 {}.{}", table, indexName);
            }
        } catch (SQLException e) {
            log.warn("【数据库守护】检查/添加唯一索引 {}.{} 失败: {}", table, indexName, e.getMessage());
        }
    }

    private boolean indexExists(Connection conn, String table, String indexName) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getIndexInfo(conn.getCatalog(), null, table, false, false)) {
            while (rs.next()) {
                String existing = rs.getString("INDEX_NAME");
                if (indexName.equalsIgnoreCase(existing)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> findDuplicateValues(Connection conn, String table, String column) throws SQLException {
        List<String> duplicates = new ArrayList<>();
        String sql = "SELECT TRIM(`" + column + "`) AS value, COUNT(*) AS count FROM `" + table + "` " +
                "WHERE `" + column + "` IS NOT NULL AND TRIM(`" + column + "`) <> '' " +
                "GROUP BY TRIM(`" + column + "`) HAVING COUNT(*) > 1 LIMIT 5";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                duplicates.add(rs.getString("value") + "×" + rs.getLong("count"));
            }
        }
        return duplicates;
    }

    private Set<String> getExistingTables(Connection conn) throws SQLException {
        Set<String> tables = new HashSet<>();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(conn.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME").toLowerCase());
            }
        }
        return tables;
    }

    private void sendAlert(String title, String detail) {
        try {
            String msg = String.format("⚠️【OCI Worker 数据库告警】\n状况：%s\n详情：%s\n时间：%s",
                    title, detail, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            notificationService.sendMessage(msg);
        } catch (Exception e) {
            log.warn("【数据库守护】TG 告警发送失败: {}", e.getMessage());
        }
    }
}
