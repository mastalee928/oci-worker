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
    MFA_SECRET("mfa_secret", "sys"),
    MFA_ENABLED("mfa_enabled", "sys"),
    ;

    private final String code;
    private final String type;
}
