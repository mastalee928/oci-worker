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
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_oci_user_create_time (create_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
    status VARCHAR(16) DEFAULT 'RUNNING',
    attempt_count INT DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_oci_create_task_create_time (create_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS oci_kv (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    value TEXT,
    type VARCHAR(64) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_oci_kv_code (code),
    INDEX idx_oci_kv_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cf_cfg (
    id VARCHAR(64) PRIMARY KEY,
    domain VARCHAR(64) NOT NULL,
    zone_id VARCHAR(255) NOT NULL,
    api_token VARCHAR(255) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
