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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
    status VARCHAR(16) DEFAULT 'RUNNING',
    attempt_count INT DEFAULT 0,
    success_count INT DEFAULT 0,
    created_instances TEXT DEFAULT NULL,
    failure_reason TEXT DEFAULT NULL,
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
    login_detail MEDIUMTEXT NULL COMMENT 'JSON: 访问入口、网络与链路、客户端与能力',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_oci_login_audit_time (create_time DESC)
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
