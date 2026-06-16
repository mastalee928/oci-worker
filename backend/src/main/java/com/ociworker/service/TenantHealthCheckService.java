package com.ociworker.service;

import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciUser;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.requests.GetTenancyRequest;
import com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest;
import com.oracle.bmc.model.BmcException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

@Service
public class TenantHealthCheckService {

    private static final int CHECK_CONCURRENCY = 5;

    @Resource
    private OciUserMapper userMapper;

    public TenantHealthSummary checkAllTenants() {
        List<OciUser> users = userMapper.selectList(null);
        List<TenantHealthResult> results = checkTenants(users);
        return new TenantHealthSummary(users.size(), results);
    }

    private List<TenantHealthResult> checkTenants(List<OciUser> users) {
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        Semaphore permits = new Semaphore(CHECK_CONCURRENCY);
        List<Future<TenantHealthResult>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (OciUser user : users) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        return checkTenant(user);
                    } finally {
                        permits.release();
                    }
                }));
            }
            List<TenantHealthResult> results = new ArrayList<>();
            for (Future<TenantHealthResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    results.add(new TenantHealthResult("-", TenantHealthStatus.CHECK_FAILED, shortReason(e)));
                }
            }
            results.sort(Comparator.comparing(TenantHealthResult::name, Comparator.nullsLast(String::compareToIgnoreCase)));
            return results;
        }
    }

    private TenantHealthResult checkTenant(OciUser user) {
        String name = user == null || user.getUsername() == null || user.getUsername().isBlank()
                ? "-"
                : user.getUsername().trim();
        try {
            requireField(user.getOciTenantId(), "Tenant ID 为空");
            requireField(user.getOciUserId(), "User ID 为空");
            requireField(user.getOciFingerprint(), "Fingerprint 为空");
            requireField(user.getOciRegion(), "Region 为空");
            requireField(user.getOciKeyPath(), "密钥路径为空");

            SimpleAuthenticationDetailsProvider provider = SimpleAuthenticationDetailsProvider.builder()
                    .tenantId(user.getOciTenantId().trim())
                    .userId(user.getOciUserId().trim())
                    .fingerprint(user.getOciFingerprint().trim())
                    .privateKeySupplier(() -> {
                        try (FileInputStream fis = new FileInputStream(user.getOciKeyPath().trim());
                             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                            fis.transferTo(baos);
                            return new ByteArrayInputStream(baos.toByteArray());
                        } catch (Exception e) {
                            throw new RuntimeException("密钥文件无效或不存在", e);
                        }
                    })
                    .region(Region.fromRegionCodeOrId(user.getOciRegion().trim()))
                    .build();

            try (IdentityClient identityClient = buildIdentityClient(provider)) {
                identityClient.getTenancy(GetTenancyRequest.builder()
                        .tenancyId(user.getOciTenantId().trim())
                        .build());
                identityClient.listRegionSubscriptions(ListRegionSubscriptionsRequest.builder()
                        .tenancyId(user.getOciTenantId().trim())
                        .build());
            }
            return new TenantHealthResult(name, TenantHealthStatus.OK, "");
        } catch (Exception e) {
            if (isDefinitelyInvalid(e)) {
                return new TenantHealthResult(name, TenantHealthStatus.INVALID, shortReason(e));
            }
            return new TenantHealthResult(name, TenantHealthStatus.CHECK_FAILED, shortReason(e));
        }
    }

    private static IdentityClient buildIdentityClient(SimpleAuthenticationDetailsProvider provider) {
        var builder = IdentityClient.builder();
        OciProxyConfigService proxyConfigService = OciProxyConfigService.instance();
        if (proxyConfigService == null || !proxyConfigService.ociUsesExplicitClientProxy()) {
            builder = builder.additionalClientConfigurator(OciProxyConfigService.ociSdkJerseyDirectConfigurator());
        }
        return builder.build(provider);
    }

    private static void requireField(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean isDefinitelyInvalid(Exception e) {
        if (e instanceof IllegalArgumentException) {
            return true;
        }
        Throwable cur = e;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("notauthenticated")
                        || lower.contains("not authorized")
                        || lower.contains("notauthorized")
                        || lower.contains("tenant id 不存在")
                        || lower.contains("not found")
                        || lower.contains("密钥")
                        || lower.contains("private key")
                        || lower.contains("unknown region")) {
                    return true;
                }
            }
            if (cur instanceof BmcException bmc) {
                int status = bmc.getStatusCode();
                return status == 401 || status == 403 || status == 404;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String shortReason(Throwable e) {
        String msg = e == null ? null : e.getMessage();
        if ((msg == null || msg.isBlank()) && e != null && e.getCause() != null) {
            msg = e.getCause().getMessage();
        }
        if (msg == null || msg.isBlank()) {
            msg = e == null ? "未知错误" : e.getClass().getSimpleName();
        }
        msg = msg.replace('\n', ' ').replace('\r', ' ').trim();
        return msg.length() > 80 ? msg.substring(0, 80) : msg;
    }

    public enum TenantHealthStatus {
        OK,
        INVALID,
        CHECK_FAILED
    }

    public record TenantHealthResult(String name, TenantHealthStatus status, String reason) {}

    public record TenantHealthSummary(int total, List<TenantHealthResult> results) {
        public long okCount() {
            return results.stream().filter(x -> x.status() == TenantHealthStatus.OK).count();
        }

        public List<TenantHealthResult> invalid() {
            return results.stream().filter(x -> x.status() == TenantHealthStatus.INVALID).toList();
        }

        public List<TenantHealthResult> failed() {
            return results.stream().filter(x -> x.status() == TenantHealthStatus.CHECK_FAILED).toList();
        }

        public List<String> invalidNames() {
            return invalid().stream().map(TenantHealthResult::name).toList();
        }

        public List<String> failedNamesWithReason() {
            return failed().stream()
                    .map(x -> x.name() + "（" + x.reason() + "）")
                    .toList();
        }
    }
}
