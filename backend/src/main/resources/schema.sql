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
    login_mode VARCHAR(32) DEFAULT 'PASSWORD',
    ssh_public_key TEXT DEFAULT NULL,
    operation_system VARCHAR(64) DEFAULT 'Ubuntu',
    instance_name VARCHAR(255) DEFAULT NULL,
    custom_script TEXT,
    status VARCHAR(16) DEFAULT 'RUNNING',
    status_time DATETIME DEFAULT NULL,
    attempt_count INT DEFAULT 0,
    success_count INT DEFAULT 0,
    created_instances TEXT DEFAULT NULL,
    failure_reason TEXT DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_oci_create_task_create_time (create_time DESC),
    INDEX idx_oci_create_task_user_status (user_id, status)
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

CREATE TABLE IF NOT EXISTS oci_webssh_connection_bookmark (
    id VARCHAR(64) PRIMARY KEY,
    dedupe_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hostname VARCHAR(255) NOT NULL,
    port INT NOT NULL,
    username VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    auth_type VARCHAR(16) NOT NULL DEFAULT 'password',
    sort_order BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_webssh_connection_bookmark_dedupe (dedupe_key),
    INDEX idx_webssh_connection_bookmark_order (sort_order, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS oci_webssh_script_bookmark (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    command_encrypted MEDIUMTEXT NOT NULL,
    sort_order BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_webssh_script_bookmark_order (sort_order, create_time)
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
    password_attempt TEXT NULL,
    ip VARCHAR(255) DEFAULT NULL,
    success TINYINT(1) NOT NULL DEFAULT 0,
    device_id VARCHAR(128) DEFAULT NULL,
    os_name VARCHAR(128) DEFAULT NULL,
    browser_name VARCHAR(128) DEFAULT NULL,
    login_channel VARCHAR(32) DEFAULT 'password',
    result_message VARCHAR(128) DEFAULT NULL,
    user_agent TEXT,
    login_detail LONGTEXT NULL COMMENT 'AES-256-GCM: 登录请求完整详情',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_oci_login_audit_time (create_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS oci_organization_work_task (
    id VARCHAR(64) PRIMARY KEY,
    tenant_config_id VARCHAR(64) NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    target_name VARCHAR(255),
    target_id VARCHAR(255),
    work_request_id VARCHAR(255) NOT NULL,
    request_id VARCHAR(255),
    status VARCHAR(32) DEFAULT 'ACCEPTED',
    percent_complete FLOAT DEFAULT 0,
    error_message TEXT,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_org_task_tenant_time (tenant_config_id, create_time DESC),
    UNIQUE KEY uk_org_task_work_request (work_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS oci_scheduled_ip_task (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    tenant_config_id VARCHAR(64) NOT NULL,
    tenant_name VARCHAR(255) DEFAULT NULL,
    region VARCHAR(64) NOT NULL,
    instance_id VARCHAR(255) NOT NULL,
    instance_name VARCHAR(255) DEFAULT NULL,
    shape VARCHAR(128) DEFAULT NULL,
    compartment_id VARCHAR(255) DEFAULT NULL,
    current_public_ip VARCHAR(64) DEFAULT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    interval_minutes INT NOT NULL DEFAULT 60,
    next_run_time DATETIME DEFAULT NULL,
    last_run_time DATETIME DEFAULT NULL,
    last_status VARCHAR(32) DEFAULT 'PENDING',
    last_message VARCHAR(1024) DEFAULT NULL,
    dns_enabled TINYINT(1) NOT NULL DEFAULT 0,
    dns_provider VARCHAR(16) DEFAULT NULL,
    fqdn VARCHAR(255) DEFAULT NULL,
    dns_zone_id VARCHAR(255) DEFAULT NULL,
    dns_domain_name VARCHAR(255) DEFAULT NULL,
    dns_record_id VARCHAR(255) DEFAULT NULL,
    dns_record_name VARCHAR(255) DEFAULT NULL,
    notify_success TINYINT(1) NOT NULL DEFAULT 0,
    notify_ip_failure TINYINT(1) NOT NULL DEFAULT 1,
    notify_dns_failure TINYINT(1) NOT NULL DEFAULT 1,
    notify_auto_paused TINYINT(1) NOT NULL DEFAULT 1,
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_notify_key VARCHAR(255) DEFAULT NULL,
    last_notify_time DATETIME DEFAULT NULL,
    lock_owner VARCHAR(64) DEFAULT NULL,
    lock_until DATETIME DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_scheduled_ip_due (enabled, next_run_time),
    INDEX idx_scheduled_ip_instance (tenant_config_id, region, instance_id),
    INDEX idx_scheduled_ip_tenant_time (tenant_config_id, create_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS oci_scheduled_ip_instance_lock (
    instance_key VARCHAR(512) PRIMARY KEY,
    enabled_task_id VARCHAR(64) DEFAULT NULL,
    lock_owner VARCHAR(64) DEFAULT NULL,
    lock_until DATETIME DEFAULT NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_scheduled_ip_instance_lock_until (lock_until),
    INDEX idx_scheduled_ip_instance_enabled_task (enabled_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS oci_scheduled_ip_run_log (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    trigger_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    old_ip VARCHAR(64) DEFAULT NULL,
    new_ip VARCHAR(64) DEFAULT NULL,
    dns_status VARCHAR(32) DEFAULT NULL,
    message VARCHAR(1024) DEFAULT NULL,
    dns_message VARCHAR(1024) DEFAULT NULL,
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME DEFAULT NULL,
    UNIQUE KEY uk_scheduled_ip_run_id (run_id),
    INDEX idx_scheduled_ip_log_task_time (task_id, started_at DESC),
    INDEX idx_scheduled_ip_log_started_at (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS oci_tenant_traffic_protection (
    tenant_config_id VARCHAR(64) PRIMARY KEY,
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    monthly_limit_bytes BIGINT NOT NULL DEFAULT 10995116277760,
    warning_percent INT NOT NULL DEFAULT 80,
    exceed_action VARCHAR(32) NOT NULL DEFAULT 'ALERT_ONLY',
    month_key VARCHAR(7), monthly_bytes BIGINT NOT NULL DEFAULT 0,
    last_collect_time DATETIME, next_collect_time DATETIME,
    last_warning_level INT NOT NULL DEFAULT 0,
    stop_executed TINYINT(1) NOT NULL DEFAULT 0, stop_executed_time DATETIME,
    collection_lock_owner VARCHAR(64), collection_lock_until DATETIME,
    last_error VARCHAR(1024), create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS oci_tenant_traffic_instance_usage (
    id VARCHAR(64) PRIMARY KEY, tenant_config_id VARCHAR(64) NOT NULL,
    month_key VARCHAR(7) NOT NULL, instance_id VARCHAR(255) NOT NULL,
    instance_name VARCHAR(255), region VARCHAR(64), lifecycle_state VARCHAR(32),
    bytes_to_network BIGINT NOT NULL DEFAULT 0, last_seen_time DATETIME,
    UNIQUE KEY uk_traffic_instance_month (tenant_config_id, month_key, instance_id),
    INDEX idx_traffic_usage_tenant_month (tenant_config_id, month_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS oci_tenant_traffic_action_log (
    id VARCHAR(64) PRIMARY KEY, tenant_config_id VARCHAR(64) NOT NULL,
    month_key VARCHAR(7) NOT NULL, action VARCHAR(64) NOT NULL,
    estimated_bytes BIGINT NOT NULL DEFAULT 0, affected_instance_ids TEXT,
    success_count INT NOT NULL DEFAULT 0, failure_count INT NOT NULL DEFAULT 0,
    error_summary VARCHAR(1024), create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_traffic_action_tenant_time (tenant_config_id, create_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS oci_announcement_record (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    tenant_name VARCHAR(128) DEFAULT NULL,
    announcement_id VARCHAR(160) NOT NULL,
    aggregate_key VARCHAR(255) NOT NULL,
    chain_id VARCHAR(160) DEFAULT NULL,
    summary VARCHAR(1024) DEFAULT NULL,
    announcement_type VARCHAR(64) DEFAULT NULL,
    services_json TEXT DEFAULT NULL,
    affected_regions_json TEXT DEFAULT NULL,
    time_created DATETIME DEFAULT NULL,
    time_updated DATETIME DEFAULT NULL,
    time_one_title VARCHAR(128) DEFAULT NULL,
    time_one_type VARCHAR(64) DEFAULT NULL,
    time_one_value DATETIME DEFAULT NULL,
    time_two_title VARCHAR(128) DEFAULT NULL,
    time_two_type VARCHAR(64) DEFAULT NULL,
    time_two_value DATETIME DEFAULT NULL,
    pushed TINYINT(1) NOT NULL DEFAULT 0,
    ignored TINYINT(1) NOT NULL DEFAULT 0,
    read_flag TINYINT(1) NOT NULL DEFAULT 0,
    first_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    pushed_batch_id VARCHAR(64) DEFAULT NULL,
    pushed_at DATETIME DEFAULT NULL,
    UNIQUE KEY uk_oci_announcement_tenant_announcement (tenant_id, announcement_id),
    INDEX idx_oci_announcement_aggregate (aggregate_key),
    INDEX idx_oci_announcement_time (time_created DESC),
    INDEX idx_oci_announcement_pushed (pushed, ignored)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS oci_announcement_push_batch (
    id VARCHAR(64) PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    pushed_at DATETIME DEFAULT NULL,
    announcement_count INT NOT NULL DEFAULT 0,
    tenant_count INT NOT NULL DEFAULT 0,
    message_preview TEXT DEFAULT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    error_message TEXT DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_oci_announcement_push_batch (batch_id),
    INDEX idx_oci_announcement_push_time (create_time DESC)
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
    context_limit BIGINT DEFAULT NULL,
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

CREATE TABLE IF NOT EXISTS oci_instance_guard (
    id VARCHAR(64) PRIMARY KEY,
    tenant_config_id VARCHAR(64) NOT NULL,
    tenant_name VARCHAR(128) DEFAULT NULL,
    region VARCHAR(64) NOT NULL,
    instance_id VARCHAR(255) NOT NULL,
    instance_name VARCHAR(255) DEFAULT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    interval_minutes INT NOT NULL DEFAULT 2,
    next_check_time DATETIME DEFAULT NULL,
    notify_muted TINYINT(1) NOT NULL DEFAULT 0,
    last_state VARCHAR(32) DEFAULT NULL,
    last_check_time DATETIME DEFAULT NULL,
    last_start_time DATETIME DEFAULT NULL,
    start_count INT NOT NULL DEFAULT 0,
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_message VARCHAR(512) DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT NULL,
    UNIQUE KEY uk_instance_guard (tenant_config_id, region, instance_id),
    INDEX idx_instance_guard_due (enabled, next_check_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
