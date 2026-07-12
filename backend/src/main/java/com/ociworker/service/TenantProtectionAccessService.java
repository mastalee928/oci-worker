package com.ociworker.service;

import com.ociworker.exception.OciException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TenantProtectionAccessService {
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);
    private final ConcurrentHashMap<String, Grant> grants = new ConcurrentHashMap<>();

    private record Grant(String tenantConfigId, String scope, long expiresAt) {}

    public String issue(String tenantConfigId, String scope) {
        if (tenantConfigId == null || tenantConfigId.isBlank() || scope == null || scope.isBlank()) {
            throw new OciException("无法生成操作授权");
        }
        long now = System.currentTimeMillis();
        grants.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        String token = UUID.randomUUID().toString();
        grants.put(token, new Grant(tenantConfigId, scope, now + TOKEN_TTL.toMillis()));
        return token;
    }

    public void consume(String token, String tenantConfigId, String scope) {
        if (token == null || token.isBlank()) throw new OciException("操作授权已失效，请重新完成 TG 验证");
        Grant grant = grants.get(token);
        if (grant == null || grant.expiresAt() < System.currentTimeMillis()
                || !Objects.equals(grant.tenantConfigId(), tenantConfigId)
                || !Objects.equals(grant.scope(), scope)) {
            grants.remove(token);
            throw new OciException("操作授权已失效，请重新完成 TG 验证");
        }
        if (!grants.remove(token, grant)) {
            throw new OciException("操作授权已被使用，请重新完成 TG 验证");
        }
    }
}
