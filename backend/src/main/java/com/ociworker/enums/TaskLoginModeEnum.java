package com.ociworker.enums;

import cn.hutool.core.util.StrUtil;

public enum TaskLoginModeEnum {
    PASSWORD,
    SSH_PUBLIC_KEY;

    public static TaskLoginModeEnum of(String value) {
        if (StrUtil.isBlank(value)) {
            return PASSWORD;
        }
        for (TaskLoginModeEnum mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return PASSWORD;
    }

    public static boolean isSshPublicKey(String value) {
        return of(value) == SSH_PUBLIC_KEY;
    }
}
