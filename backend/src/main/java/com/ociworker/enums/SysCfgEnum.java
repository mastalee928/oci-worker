package com.ociworker.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SysCfgEnum {
    TG_BOT_TOKEN("tg_bot_token", "sys"),
    TG_CHAT_ID("tg_chat_id", "sys"),
    TG_NOTIFY_TYPES("tg_notify_types", "sys"),
    /** 姣忔棩鎾姤鍙戦€佹椂鍒伙紝24 灏忔椂鍒?HH:mm锛屼笢鍏尯 Asia/Shanghai */
    TG_DAILY_REPORT_TIME("tg_daily_report_time", "sys"),
    ANNOUNCEMENT_PUSH_ENABLED("announcement_push_enabled", "sys"),
    ANNOUNCEMENT_PUSH_MODE("announcement_push_mode", "sys"),
    ANNOUNCEMENT_PUSH_EVENT_TYPES("announcement_push_event_types", "sys"),
    ANNOUNCEMENT_PUSH_FREQUENCY_MINUTES("announcement_push_frequency_minutes", "sys"),
    ANNOUNCEMENT_PUSH_TENANT_SCOPE_MODE("announcement_push_tenant_scope_mode", "sys"),
    ANNOUNCEMENT_PUSH_SELECTED_TENANT_IDS("announcement_push_selected_tenant_ids", "sys"),
    ANNOUNCEMENT_PUSH_EXCLUDED_TENANT_IDS("announcement_push_excluded_tenant_ids", "sys"),
    ANNOUNCEMENT_PUSH_SELECTED_GROUPS("announcement_push_selected_groups", "sys"),
    ANNOUNCEMENT_PUSH_RECORD_RETENTION_DAYS("announcement_push_record_retention_days", "sys"),
    ANNOUNCEMENT_PUSH_BATCH_RETENTION_DAYS("announcement_push_batch_retention_days", "sys"),
    ANNOUNCEMENT_PUSH_BASELINE_DONE("announcement_push_baseline_done", "sys"),
    ANNOUNCEMENT_PUSH_LAST_SCAN_AT("announcement_push_last_scan_at", "sys"),
    /**
     * getUpdates 鐨?offset锛氫笅涓€鏉℃媺鍙栧簲浼犲叆鐨?update_id锛堝嵆銆屽凡澶勭悊鐨勬渶澶?update_id + 1銆嶏級锛屾寔涔呭寲閬垮厤閲嶅惎閲嶅澶勭悊銆?
     */
    TG_UPDATES_NEXT_OFFSET("tg_updates_next_offset", "sys"),
    /** 閫氱煡韬唤鍙樻洿鍥炴粴锛氭殏瀛樻棫 Bot Token锛?5 鍒嗛挓鍐呭彲鎷掔粷鎭㈠锛?*/
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

    /** Cloudflare 鍏ㄥ眬 API Token锛圓ccount 绾э級 */
    CF_API_TOKEN("cf_api_token", "sys"),
    CF_ACCOUNT_ID("cf_account_id", "sys"),

    MFA_SECRET("mfa_secret", "sys"),
    MFA_ENABLED("mfa_enabled", "sys"),

    /** 閫楀彿鍒嗛殧 IPv4/IPv6锛岀敤浜庢嫤鎴瘑鐮?TG 鐧诲綍鍏ュ彛 */
    LOGIN_IP_DENYLIST("login_ip_denylist", "sys"),
    /** 閫楀彿鍒嗛殧璁惧 ID锛堜笌 Cookie ow_did 涓€鑷达級 */
    LOGIN_DEVICE_DENYLIST("login_device_denylist", "sys"),
    /** true / false锛氫负 true 鏃堕櫎鐧藉悕鍗曟帴鍙ｅ涓€寰?503 */
    SITE_ACCESS_PAUSED("site_access_paused", "sys"),

    /** 阿里云DNS AccessKey */
    ALIDNS_ACCESS_KEY_ID("alidns_access_key_id", "sys"),
    ALIDNS_ACCESS_KEY_SECRET("alidns_access_key_secret", "sys"),
    ;

    private final String code;
    private final String type;
}
