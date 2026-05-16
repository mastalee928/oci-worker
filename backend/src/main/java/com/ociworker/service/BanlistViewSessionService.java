package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.ociworker.util.CommonUtils;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 封禁列表：完成 TG 验证码校验后下发的短期会话，用于限制列表与封禁接口。
 */
@Service
public class BanlistViewSessionService {

    private static final long TTL_MS = 30 * 60 * 1000L;

    private final ConcurrentHashMap<String, Long> sessions = new ConcurrentHashMap<>();

    public String issue() {
        String id = CommonUtils.generateId();
        sessions.put(id, System.currentTimeMillis() + TTL_MS);
        return id;
    }

    public boolean isValid(String id) {
        if (StrUtil.isBlank(id)) {
            return false;
        }
        Long exp = sessions.get(id);
        if (exp == null) {
            return false;
        }
        if (System.currentTimeMillis() > exp) {
            sessions.remove(id);
            return false;
        }
        return true;
    }
}
