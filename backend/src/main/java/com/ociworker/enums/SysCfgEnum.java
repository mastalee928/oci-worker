package com.ociworker.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SysCfgEnum {
    TG_BOT_TOKEN("tg_bot_token", "sys"),
    TG_CHAT_ID("tg_chat_id", "sys"),
    TG_NOTIFY_TYPES("tg_notify_types", "sys"),
    /** 每日播报发送时刻，24 小时制 HH:mm，东八区 Asia/Shanghai */
    TG_DAILY_REPORT_TIME("tg_daily_report_time", "sys"),

    OCI_PROXY_ENABLED("oci_proxy_enabled", "sys"),
    OCI_PROXY_TYPE("oci_proxy_type", "sys"),
    OCI_PROXY_HOST("oci_proxy_host", "sys"),
    OCI_PROXY_PORT("oci_proxy_port", "sys"),
    OCI_PROXY_USER("oci_proxy_user", "sys"),
    OCI_PROXY_PASS("oci_proxy_pass", "sys"),
    OCI_PROXY_FULL_URL("oci_proxy_full_url", "sys"),

    MFA_SECRET("mfa_secret", "sys"),
    MFA_ENABLED("mfa_enabled", "sys"),
    ;

    private final String code;
    private final String type;
}
