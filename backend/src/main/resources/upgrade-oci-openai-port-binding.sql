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
