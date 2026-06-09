package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.model.entity.OciKv;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class OracleAiGatewayConfigService {

    private static final String TYPE = "sys_config";
    private static final String CODE_DEFAULT_MAX_TOKENS = "oracle_ai_default_max_tokens";
    private static final long CACHE_TTL_MS = 2000;
    public static final int FALLBACK_DEFAULT_MAX_TOKENS = 4000;

    @Resource
    private OciKvMapper kvMapper;

    private volatile Integer cachedDefaultMaxTokens = null;
    private final AtomicLong cachedAtMs = new AtomicLong(0);

    public int getDefaultMaxTokens() {
        long now = System.currentTimeMillis();
        Integer c = cachedDefaultMaxTokens;
        if (c != null && now - cachedAtMs.get() < CACHE_TTL_MS) {
            return c;
        }

        OciKv kv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getCode, CODE_DEFAULT_MAX_TOKENS)
                .eq(OciKv::getType, TYPE));
        int value = parseDefaultMaxTokens(kv == null ? null : kv.getValue());
        cachedDefaultMaxTokens = value;
        cachedAtMs.set(now);
        return value;
    }

    public int setDefaultMaxTokens(int value) {
        int normalized = normalizeDefaultMaxTokens(value);
        OciKv existing = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getCode, CODE_DEFAULT_MAX_TOKENS)
                .eq(OciKv::getType, TYPE));
        if (existing != null) {
            existing.setValue(String.valueOf(normalized));
            kvMapper.updateById(existing);
        } else {
            OciKv kv = new OciKv();
            kv.setId(CommonUtils.generateId());
            kv.setCode(CODE_DEFAULT_MAX_TOKENS);
            kv.setType(TYPE);
            kv.setValue(String.valueOf(normalized));
            kv.setCreateTime(LocalDateTime.now());
            kvMapper.insert(kv);
        }
        cachedDefaultMaxTokens = normalized;
        cachedAtMs.set(System.currentTimeMillis());
        return normalized;
    }

    public static int normalizeDefaultMaxTokens(int value) {
        if (value < 1) {
            return 1;
        }
        return Math.min(value, 200000);
    }

    private static int parseDefaultMaxTokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return FALLBACK_DEFAULT_MAX_TOKENS;
        }
        try {
            return normalizeDefaultMaxTokens(Integer.parseInt(raw.trim()));
        } catch (Exception ignored) {
            return FALLBACK_DEFAULT_MAX_TOKENS;
        }
    }
}
