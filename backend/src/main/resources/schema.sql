-- 租户配置表
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
    create_time DATETIME DEFAULT (datetime('now','localtime')) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_oci_user_create_time ON oci_user (create_time DESC);

-- 开机任务表
CREATE TABLE IF NOT EXISTS oci_create_task (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64),
    oci_region VARCHAR(64),
    ocpus REAL DEFAULT 1.0,
    memory REAL DEFAULT 6.0,
    disk INTEGER DEFAULT 50,
    architecture VARCHAR(64) DEFAULT 'ARM',
    interval_seconds INTEGER DEFAULT 60,
    create_numbers INTEGER DEFAULT 1,
    root_password VARCHAR(64),
    operation_system VARCHAR(64) DEFAULT 'Ubuntu',
    status VARCHAR(16) DEFAULT 'RUNNING',
    attempt_count INTEGER DEFAULT 0,
    create_time DATETIME DEFAULT (datetime('now','localtime')) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_oci_create_task_create_time ON oci_create_task (create_time DESC);

-- 键值配置表
CREATE TABLE IF NOT EXISTS oci_kv (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    value TEXT,
    type VARCHAR(64) NOT NULL,
    create_time DATETIME DEFAULT (datetime('now','localtime')) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_oci_kv_code ON oci_kv (code);
CREATE INDEX IF NOT EXISTS idx_oci_kv_type ON oci_kv (type);

-- Cloudflare 配置表
CREATE TABLE IF NOT EXISTS cf_cfg (
    id VARCHAR(64) PRIMARY KEY,
    domain VARCHAR(64) NOT NULL,
    zone_id VARCHAR(255) NOT NULL,
    api_token VARCHAR(255) NOT NULL,
    create_time DATETIME DEFAULT (datetime('now','localtime')) NOT NULL
);

-- IP 数据表
CREATE TABLE IF NOT EXISTS ip_data (
    id VARCHAR(64) PRIMARY KEY,
    ip VARCHAR(255) NOT NULL,
    country VARCHAR(255),
    area VARCHAR(120),
    city VARCHAR(120),
    org VARCHAR(120),
    asn VARCHAR(64),
    type VARCHAR(64),
    lat REAL,
    lng REAL,
    create_time DATETIME DEFAULT (datetime('now','localtime')) NOT NULL
);
