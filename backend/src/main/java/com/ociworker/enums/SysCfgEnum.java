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
    /**
     * getUpdates 的 offset：下一条拉取应传入的 update_id（即「已处理的最大 update_id + 1」），持久化避免重启重复处理。
     */
    TG_UPDATES_NEXT_OFFSET("tg_updates_next_offset", "sys"),
    /** 通知身份变更回滚：暂存旧 Bot Token（15 分钟内可拒绝恢复） */
    TG_ROLLBACK_OLD_BOT_TOKEN("tg_rollback_old_bot_token", "sys"),
    TG_ROLLBACK_OLD_CHAT_ID("tg_rollback_old_chat_id", "sys"),
    TG_ROLLBACK_EXPIRE_AT("tg_rollback_expire_at", "sys"),
    TG_ROLLBACK_SESSION_ID("tg_rollback_session_id", "sys"),
    TG_ROLLBACK_UPDATES_OFFSET("tg_rollback_updates_offset", "sys"),

    OCI_PROXY_ENABLED("oci_proxy_enabled", "sys"),
    OCI_PROXY_TYPE("oci_proxy_type", "sys"),
    OCI_PROXY_HOST("oci_proxy_host", "sys"),
    OCI_PROXY_PORT("oci_proxy_port", "sys"),
    OCI_PROXY_USER("oci_proxy_user", "sys"),
    OCI_PROXY_PASS("oci_proxy_pass", "sys"),
    OCI_PROXY_FULL_URL("oci_proxy_full_url", "sys"),

    MFA_SECRET("mfa_secret", "sys"),
    MFA_ENABLED("mfa_enabled", "sys"),

    /** 逗号分隔 IPv4/IPv6，用于拦截密码/TG 登录入口 */
    LOGIN_IP_DENYLIST("login_ip_denylist", "sys"),
    /** 逗号分隔设备 ID（与 Cookie ow_did 一致） */
    LOGIN_DEVICE_DENYLIST("login_device_denylist", "sys"),
    /** true / false：为 true 时除白名单接口外一律 503 */
    SITE_ACCESS_PAUSED("site_access_paused", "sys"),
    ;

    private final String code;
    private final String type;
}
