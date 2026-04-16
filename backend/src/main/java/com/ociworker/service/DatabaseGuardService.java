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
                oci_tenant_id VARCHAR(128),
                oci_user_id VARCHAR(128),
                oci_fingerprint VARCHAR(128) NOT NULL,
                oci_region VARCHAR(32) NOT NULL,
                oci_key_path VARCHAR(256) NOT NULL,
                plan_type VARCHAR(32),
                group_level1 VARCHAR(64) DEFAULT NULL,
                group_level2 VARCHAR(64) DEFAULT NULL,
                create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
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
        addColumnIfMissing(conn, "oci_user", "group_level1", "VARCHAR(64) DEFAULT NULL AFTER plan_type");
        addColumnIfMissing(conn, "oci_user", "group_level2", "VARCHAR(64) DEFAULT NULL AFTER group_level1");
        addColumnIfMissing(conn, "oci_create_task", "custom_script", "TEXT DEFAULT NULL AFTER operation_system");
        addColumnIfMissing(conn, "oci_create_task", "assign_public_ip", "TINYINT(1) DEFAULT 1 AFTER custom_script");
        addColumnIfMissing(conn, "oci_create_task", "assign_ipv6", "TINYINT(1) DEFAULT 0 AFTER assign_public_ip");
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
