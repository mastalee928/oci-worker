package com.ociworker.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SysCfgEnum {
    TG_BOT_TOKEN("tg_bot_token", "sys"),
    TG_CHAT_ID("tg_chat_id", "sys"),
    TG_NOTIFY_TYPES("tg_notify_types", "sys"),
    MFA_SECRET("mfa_secret", "sys"),
    MFA_ENABLED("mfa_enabled", "sys"),
    ;

    private final String code;
    private final String type;
}
