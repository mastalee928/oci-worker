package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.requests.GetTenancyRequest;
import com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.ospgateway.SubscriptionServiceClient;
import com.oracle.bmc.ospgateway.requests.ListSubscriptionsRequest;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class TenantInfoRefreshQueue {

    public static final String PENDING = "PENDING";
    public static final String SUCCESS = "SUCCESS";
    public static final String NO_PERMISSION = "NO_PERMISSION";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String FAILED = "FAILED";

    private static final int WORKER_COUNT = 3;
    private static final int MAX_AUTO_RETRY = 3;

    @Resource
    private OciUserMapper userMapper;

    private final BlockingQueue<RefreshTask> queue = new LinkedBlockingQueue<>();
    private final Set<String> queuedIds = ConcurrentHashMap.newKeySet();
    private final Set<String> forcedIds = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newFixedThreadPool(WORKER_COUNT, r -> {
        Thread t = new Thread(r, "tenant-info-refresh");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    @PostConstruct
    public void start() {
        for (int i = 0; i < WORKER_COUNT; i++) {
            workers.submit(this::workerLoop);
        }
        try {
            enqueueRecoverable();
        } catch (Exception e) {
            log.warn("Initial tenant info refresh enqueue failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        workers.shutdownNow();
    }

    public void enqueue(String userId) {
        enqueue(userId, false);
    }

    public void enqueue(String userId, boolean force) {
        if (StrUtil.isBlank(userId)) return;
        if (force) {
            forcedIds.add(userId);
            resetForManualRefresh(userId);
        }
        if (queuedIds.add(userId)) {
            queue.offer(new RefreshTask(userId, force));
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void enqueueRecoverable() {
        LocalDateTime now = LocalDateTime.now();
        List<OciUser> users = userMapper.selectList(new LambdaQueryWrapper<OciUser>()
                .and(w -> w
                        .in(OciUser::getTenantNameStatus, List.of(PENDING, RATE_LIMITED, TIMEOUT, FAILED))
                        .or()
                        .in(OciUser::getPlanTypeStatus, List.of(PENDING, RATE_LIMITED, TIMEOUT, FAILED))));
        for (OciUser user : users) {
            if (user == null || StrUtil.isBlank(user.getId())) continue;
            int retry = user.getInfoRetryCount() == null ? 0 : user.getInfoRetryCount();
            LocalDateTime next = user.getInfoNextRetryAt();
            boolean due = next == null || !next.isAfter(now);
            boolean pending = PENDING.equals(user.getTenantNameStatus()) || PENDING.equals(user.getPlanTypeStatus());
            if (due && (pending || retry < MAX_AUTO_RETRY)) {
                enqueue(user.getId(), false);
            }
        }
    }

    private void workerLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            RefreshTask task = null;
            try {
                task = queue.take();
                boolean force = task.force || forcedIds.remove(task.userId);
                process(task.userId, force);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Tenant info refresh worker failed: {}", e.getMessage(), e);
            } finally {
                if (task != null) {
                    queuedIds.remove(task.userId);
                }
            }
        }
    }

    private void process(String userId, boolean force) {
        OciUser user = userMapper.selectById(userId);
        if (user == null) return;
        LocalDateTime now = LocalDateTime.now();
        if (!force && user.getInfoNextRetryAt() != null && user.getInfoNextRetryAt().isAfter(now)) {
            return;
        }

        boolean retryable = false;
        int retryCount = force ? 0 : (user.getInfoRetryCount() == null ? 0 : user.getInfoRetryCount());

        try (OciClientService client = new OciClientService(buildDto(user))) {
            RefreshStatus tenantStatus = refreshTenantName(user, client);
            retryable = retryable || tenantStatus.retryable;

            RefreshStatus planStatus = refreshPlanType(user, client);
            retryable = retryable || planStatus.retryable;
        } catch (Exception e) {
            RefreshStatus status = classify(e);
            updateTenantNameStatus(user.getId(), null, status.status, status.message);
            updatePlanTypeStatus(user.getId(), null, status.status, status.message);
            retryable = status.retryable;
            log.warn("Failed to refresh tenant info for {}: {}", user.getUsername(), status.message);
        }

        applyRetryState(user.getId(), retryCount, retryable);
    }

    private SysUserDTO buildDto(OciUser user) {
        return SysUserDTO.builder()
                .username(user.getUsername())
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(user.getOciTenantId())
                        .userId(user.getOciUserId())
                        .fingerprint(user.getOciFingerprint())
                        .region(user.getOciRegion())
                        .privateKeyPath(user.getOciKeyPath())
                        .build())
                .build();
    }

    private RefreshStatus refreshTenantName(OciUser user, OciClientService client) {
        try {
            var tenancy = client.getIdentityClient().getTenancy(
                    GetTenancyRequest.builder().tenancyId(user.getOciTenantId()).build()).getTenancy();
            String name = tenancy == null ? null : tenancy.getName();
            if (StrUtil.isBlank(name)) {
                RefreshStatus status = new RefreshStatus(UNAVAILABLE, "OCI 未返回租户名称", false);
                updateTenantNameStatus(user.getId(), null, status.status, status.message);
                return status;
            }
            updateTenantNameStatus(user.getId(), name, SUCCESS, null);
            return new RefreshStatus(SUCCESS, null, false);
        } catch (Exception e) {
            RefreshStatus status = classify(e);
            updateTenantNameStatus(user.getId(), null, status.status, status.message);
            return status;
        }
    }

    private RefreshStatus refreshPlanType(OciUser user, OciClientService client) {
        SubscriptionServiceClient ospClient = null;
        try {
            String ospHomeRegion = resolveOspHomeRegion(client.getIdentityClient(), user.getOciTenantId(), user.getOciRegion());
            var ospB = SubscriptionServiceClient.builder();
            OciProxyConfigService pxy = OciProxyConfigService.instance();
            if (pxy == null || !pxy.ociUsesExplicitClientProxy()) {
                ospB = ospB.additionalClientConfigurator(OciProxyConfigService.ociSdkJerseyDirectConfigurator());
            }
            ospClient = ospB.build(client.getProvider());
            var resp = ospClient.listSubscriptions(
                    ListSubscriptionsRequest.builder()
                            .ospHomeRegion(ospHomeRegion)
                            .compartmentId(client.getCompartmentId())
                            .build());
            var items = resp.getSubscriptionCollection() == null ? null : resp.getSubscriptionCollection().getItems();
            if (items == null || items.isEmpty()) {
                RefreshStatus status = new RefreshStatus(UNAVAILABLE, "订阅接口未返回账户类型", false);
                updatePlanTypeStatus(user.getId(), "UNKNOWN", status.status, status.message);
                return status;
            }
            String planType = items.get(0).getPlanType() == null ? "UNKNOWN" : items.get(0).getPlanType().getValue();
            if (StrUtil.isBlank(planType)) planType = "UNKNOWN";
            updatePlanTypeStatus(user.getId(), planType, SUCCESS, null);
            return new RefreshStatus(SUCCESS, null, false);
        } catch (Exception e) {
            RefreshStatus status = classify(e);
            updatePlanTypeStatus(user.getId(), null, status.status, status.message);
            return status;
        } finally {
            if (ospClient != null) {
                try { ospClient.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void updateTenantNameStatus(String id, String tenantName, String status, String error) {
        OciUser patch = new OciUser();
        patch.setId(id);
        if (StrUtil.isNotBlank(tenantName)) patch.setTenantName(tenantName);
        patch.setTenantNameStatus(status);
        patch.setTenantNameError(error);
        patch.setTenantNameUpdatedAt(LocalDateTime.now());
        userMapper.updateById(patch);
    }

    private void updatePlanTypeStatus(String id, String planType, String status, String error) {
        OciUser patch = new OciUser();
        patch.setId(id);
        if (StrUtil.isNotBlank(planType)) patch.setPlanType(planType);
        patch.setPlanTypeStatus(status);
        patch.setPlanTypeError(error);
        patch.setPlanTypeUpdatedAt(LocalDateTime.now());
        userMapper.updateById(patch);
    }

    private void applyRetryState(String id, int previousRetryCount, boolean retryable) {
        OciUser patch = new OciUser();
        patch.setId(id);
        if (retryable && previousRetryCount < MAX_AUTO_RETRY) {
            int nextRetry = previousRetryCount + 1;
            patch.setInfoRetryCount(nextRetry);
            patch.setInfoNextRetryAt(LocalDateTime.now().plusMinutes(nextRetry == 1 ? 2 : nextRetry == 2 ? 5 : 15));
        } else {
            patch.setInfoRetryCount(previousRetryCount);
            patch.setInfoNextRetryAt(null);
        }
        userMapper.updateById(patch);
    }

    private void resetForManualRefresh(String userId) {
        OciUser patch = new OciUser();
        patch.setId(userId);
        patch.setTenantNameStatus(PENDING);
        patch.setTenantNameError(null);
        patch.setPlanTypeStatus(PENDING);
        patch.setPlanTypeError(null);
        patch.setInfoRetryCount(0);
        patch.setInfoNextRetryAt(null);
        userMapper.updateById(patch);
    }

    private RefreshStatus classify(Throwable throwable) {
        BmcException bmc = findCause(throwable, BmcException.class);
        if (bmc != null) {
            int status = bmc.getStatusCode();
            String msg = briefMessage(bmc.getMessage());
            if (status == 429) return new RefreshStatus(RATE_LIMITED, "API 受限，稍后重试", true);
            if (status == 401 || status == 403) return new RefreshStatus(NO_PERMISSION, "无权限或认证失败", false);
            if (status == 404) return new RefreshStatus(UNAVAILABLE, "接口不可用或资源不存在", false);
            if (status >= 500 || status == -1) return new RefreshStatus(TIMEOUT, msg, true);
            return new RefreshStatus(FAILED, msg, true);
        }
        if (findCause(throwable, SocketTimeoutException.class) != null
                || findCause(throwable, HttpTimeoutException.class) != null
                || findCause(throwable, TimeoutException.class) != null) {
            return new RefreshStatus(TIMEOUT, "请求超时，稍后重试", true);
        }
        if (findCause(throwable, IOException.class) != null) {
            return new RefreshStatus(TIMEOUT, "网络异常，稍后重试", true);
        }
        return new RefreshStatus(FAILED, briefMessage(throwable.getMessage()), true);
    }

    private String resolveOspHomeRegion(IdentityClient identityClient, String tenancyId, String fallbackRegionName) {
        if (identityClient == null || StrUtil.isBlank(tenancyId)) return fallbackRegionName;
        try {
            var tenancy = identityClient.getTenancy(
                    GetTenancyRequest.builder().tenancyId(tenancyId).build()).getTenancy();
            String homeKey = tenancy == null ? null : tenancy.getHomeRegionKey();
            if (StrUtil.isBlank(homeKey)) return fallbackRegionName;

            var regions = identityClient.listRegionSubscriptions(
                    ListRegionSubscriptionsRequest.builder().tenancyId(tenancyId).build()).getItems();
            if (regions != null) {
                for (var r : regions) {
                    if (homeKey.equalsIgnoreCase(r.getRegionKey()) && StrUtil.isNotBlank(r.getRegionName())) {
                        return r.getRegionName();
                    }
                }
            }
        } catch (Exception ignored) {}
        return fallbackRegionName;
    }

    private String briefMessage(String message) {
        if (StrUtil.isBlank(message)) return "未知错误";
        String s = message.replaceAll("\\s+", " ").trim();
        return s.length() > 180 ? s.substring(0, 180) : s;
    }

    private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable cur = throwable;
        while (cur != null) {
            if (type.isInstance(cur)) return type.cast(cur);
            cur = cur.getCause();
        }
        return null;
    }

    private record RefreshTask(String userId, boolean force) {}

    private record RefreshStatus(String status, String message, boolean retryable) {}
}
