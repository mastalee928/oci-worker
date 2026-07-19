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

    private enum State { AVAILABLE, CLAIMED }
    private record Grant(String tenantConfigId, String scope, long expiresAt, State state) {}

    public String issue(String tenantConfigId, String scope) {
        if (tenantConfigId == null || tenantConfigId.isBlank() || scope == null || scope.isBlank()) {
            throw new OciException("无法生成操作授权");
        }
        long now = System.currentTimeMillis();
        grants.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        String token = UUID.randomUUID().toString();
        grants.put(token, new Grant(tenantConfigId, scope, now + TOKEN_TTL.toMillis(), State.AVAILABLE));
        return token;
    }

    public void claim(String token, String tenantConfigId, String scope) {
        if (token == null || token.isBlank()) throw new OciException("操作授权已失效，请重新完成 TG 验证");
        grants.compute(token, (key, grant) -> {
            requireValid(grant, tenantConfigId, scope);
            if (grant.state() == State.CLAIMED) throw new OciException("操作正在处理中，请勿重复提交");
            return new Grant(grant.tenantConfigId(), grant.scope(), grant.expiresAt(), State.CLAIMED);
        });
    }

    public void complete(String token, String tenantConfigId, String scope) {
        grants.computeIfPresent(token, (key, grant) -> {
            if (matches(grant, tenantConfigId, scope) && grant.state() == State.CLAIMED) return null;
            return grant;
        });
    }

    public void release(String token, String tenantConfigId, String scope) {
        grants.computeIfPresent(token, (key, grant) -> {
            if (grant.expiresAt() < System.currentTimeMillis()) return null;
            if (!matches(grant, tenantConfigId, scope) || grant.state() != State.CLAIMED) return grant;
            return new Grant(grant.tenantConfigId(), grant.scope(), grant.expiresAt(), State.AVAILABLE);
        });
    }

    public void revoke(String token, String tenantConfigId, String scope) {
        if (token == null || token.isBlank()) return;
        grants.computeIfPresent(token, (key, grant) -> matches(grant, tenantConfigId, scope) ? null : grant);
    }

    private void requireValid(Grant grant, String tenantConfigId, String scope) {
        if (grant == null || grant.expiresAt() < System.currentTimeMillis()
                || !matches(grant, tenantConfigId, scope)) {
            throw new OciException("操作授权已失效，请重新完成 TG 验证");
        }
    }

    private boolean matches(Grant grant, String tenantConfigId, String scope) {
        return grant != null && Objects.equals(grant.tenantConfigId(), tenantConfigId)
                && Objects.equals(grant.scope(), scope);
    }
}
