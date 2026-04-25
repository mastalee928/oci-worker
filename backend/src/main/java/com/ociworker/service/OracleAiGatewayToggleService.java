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
public class OracleAiGatewayToggleService {

    private static final String TYPE = "sys_config";
    private static final String CODE = "oracle_ai_openai_proxy_enabled";

    @Resource
    private OciKvMapper kvMapper;

    private volatile Boolean cachedEnabled = null;
    private final AtomicLong cachedAtMs = new AtomicLong(0);
    private static final long CACHE_TTL_MS = 2000;

    public boolean isEnabled() {
        long now = System.currentTimeMillis();
        Boolean c = cachedEnabled;
        if (c != null && now - cachedAtMs.get() < CACHE_TTL_MS) {
            return c;
        }
        OciKv kv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getCode, CODE)
                .eq(OciKv::getType, TYPE));
        boolean enabled = kv == null || kv.getValue() == null || !"false".equalsIgnoreCase(kv.getValue().trim());
        cachedEnabled = enabled;
        cachedAtMs.set(now);
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        OciKv existing = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getCode, CODE)
                .eq(OciKv::getType, TYPE));
        String val = enabled ? "true" : "false";
        if (existing != null) {
            existing.setValue(val);
            kvMapper.updateById(existing);
        } else {
            OciKv kv = new OciKv();
            kv.setId(CommonUtils.generateId());
            kv.setCode(CODE);
            kv.setType(TYPE);
            kv.setValue(val);
            kv.setCreateTime(LocalDateTime.now());
            kvMapper.insert(kv);
        }
        cachedEnabled = enabled;
        cachedAtMs.set(System.currentTimeMillis());
    }
}

