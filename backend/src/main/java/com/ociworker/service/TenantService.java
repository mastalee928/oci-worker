package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.oracle.bmc.ClientConfiguration;
import com.ociworker.enums.TaskStatusEnum;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.requests.GetTenancyRequest;
import com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.ospgateway.SubscriptionServiceClient;
import com.oracle.bmc.ospgateway.requests.ListSubscriptionsRequest;
import com.oracle.bmc.retrier.RetryConfiguration;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciKv;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.params.IdListParams;
import com.ociworker.model.params.PageParams;
import com.ociworker.model.params.TenantBatchMoveGroupParams;
import com.ociworker.model.params.TenantParams;
import com.ociworker.util.CommonUtils;
import com.ociworker.util.OciBmcErrorTranslator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class TenantService {

    private static final long TENANT_PRIVATE_KEY_MAX_BYTES = 64 * 1024;
    private static final Pattern PEM_PRIVATE_KEY_PATTERN = Pattern.compile(
            "-----BEGIN (PRIVATE KEY|RSA PRIVATE KEY|EC PRIVATE KEY)-----([A-Za-z0-9+/=\\r\\n\\s]+)-----END \\1-----");

    @Resource
    private OciUserMapper userMapper;
    @Resource
    private OciCreateTaskMapper taskMapper;
    @Resource
    private OciKvMapper kvMapper;
    @Resource
    private OracleAiPortBindingService oracleAiPortBindingService;
    @Resource
    private OciOpenaiKeyService ociOpenaiKeyService;
    @Resource
    private OracleAiModelWhitelistService oracleAiModelWhitelistService;

    private final ConcurrentMap<String, Object> tenantLocks = new ConcurrentHashMap<>();
    @Resource
    private UsageCostService usageCostService;

    @Resource
    private OrganizationSubscriptionService organizationSubscriptionService;

    private static final Set<String> TENANT_ACCOUNT_INFO_KEYS = Set.of(
            "tenantName", "homeRegionKey", "tenantId", "description",
            "subscribedRegions", "planType", "planTypeLabel", "paymentMethod", "paymentMethodLabel",
            "accountType", "upgradeState", "upgradeStateLabel",
            "subscriptionStatus", "subscriptionStatusLabel", "currencyCode", "isIntentToPay",
            "subscriptionStartTime",
            "registrationLocation", "subscriptionPlanNumber", "subscriptionOrgOcid");

    private static final ExecutorService TENANT_ACCOUNT_EXECUTOR = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "tenant-account");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService TENANT_INFO_REFRESH_EXECUTOR = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256),
            r -> {
                Thread t = new Thread(r, "tenant-info-refresh");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    private static final long TENANT_NAME_REFRESH_HOURS = 24;
    private final Set<String> tenantInfoInflight = ConcurrentHashMap.newKeySet();

    private static final String GROUP_TYPE = "group";
    private static final String GROUP_L1_PREFIX = "group_l1:";
    private static final String GROUP_L2_PREFIX = "group_l2:";
    private static final String GROUP_ORDER_CODE = "group_order_l1";
    private static final String GROUP_ORDER_L2_PREFIX = "group_order_l2:";

    @Value("${oci-cfg.key-dir-path}")
    private String keyDirPath;

    public Page<Map<String, Object>> list(PageParams params) {
        int pageSize = params.getSize();
        if (pageSize < 1) {
            pageSize = 10;
        } else if (pageSize > 500) {
            pageSize = 500;
        }
        Page<OciUser> page = new Page<>(params.getCurrent(), pageSize);
        LambdaQueryWrapper<OciUser> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(params.getKeyword())) {
            wrapper.and(w -> w
                    .like(OciUser::getUsername, params.getKeyword())
                    .or().like(OciUser::getTenantName, params.getKeyword())
                    .or().like(OciUser::getOciRegion, params.getKeyword()));
        }
        wrapper.orderByDesc(OciUser::getCreateTime);
        Page<OciUser> result = userMapper.selectPage(page, wrapper);
        result.getRecords().forEach(this::scheduleTenantInfoFetchIfNeeded);

        List<String> tenantIds = result.getRecords().stream()
                .map(OciUser::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<String, Long> runningTaskCounts = new HashMap<>();
        if (!tenantIds.isEmpty()) {
            taskMapper.countByUserIdsAndStatus(tenantIds, TaskStatusEnum.RUNNING.getStatus()).forEach(row -> {
                Object userId = row.get("user_id");
                Object count = row.get("running_count");
                if (userId != null && count instanceof Number number) {
                    runningTaskCounts.put(String.valueOf(userId), number.longValue());
                }
            });
        }

        Page<Map<String, Object>> enriched = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        enriched.setRecords(result.getRecords().stream().map(u -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", u.getId());
            map.put("username", u.getUsername());
            map.put("tenantName", u.getTenantName());
            map.put("ociTenantId", u.getOciTenantId());
            map.put("ociUserId", u.getOciUserId());
            map.put("ociFingerprint", u.getOciFingerprint());
            map.put("ociRegion", u.getOciRegion());
            map.put("ociKeyPath", u.getOciKeyPath());
            map.put("planType", u.getPlanType());
            map.put("groupLevel1", u.getGroupLevel1());
            map.put("groupLevel2", u.getGroupLevel2());
            map.put("createTime", u.getCreateTime());

            long running = runningTaskCounts.getOrDefault(u.getId(), 0L);
            map.put("taskStatus", running > 0 ? "执行开机任务中" : "无开机任务");
            map.put("hasRunningTask", running > 0);
            return map;
        }).toList());
        return enriched;
    }

    public void add(TenantParams params) {
        normalizeTenantParams(params);
        ensureRequiredTenantParams(params);
        Object lock = tenantLocks.computeIfAbsent(params.getOciTenantId(), k -> new Object());
        synchronized (lock) {
            ensureTenantAvailable(params.getOciTenantId(), null);

            long nameCount = userMapper.selectCount(
                    new LambdaQueryWrapper<OciUser>().eq(OciUser::getUsername, params.getUsername()));
            if (nameCount > 0) {
                throw new OciException("名称「" + params.getUsername() + "」已被使用，请更换名称");
            }

            validateOciCredentials(params);

            OciUser user = new OciUser();
            user.setId(CommonUtils.generateId());
            user.setUsername(params.getUsername());
            user.setOciTenantId(params.getOciTenantId());
            user.setOciUserId(params.getOciUserId());
            user.setOciFingerprint(params.getOciFingerprint());
            user.setOciRegion(params.getOciRegion());
            user.setOciKeyPath(params.getOciKeyPath());
            user.setGroupLevel1(StrUtil.isBlank(params.getGroupLevel1()) ? "未分组" : params.getGroupLevel1());
            user.setGroupLevel2(StrUtil.isBlank(params.getGroupLevel2()) ? null : params.getGroupLevel2());
            user.setCreateTime(LocalDateTime.now());
            try {
                userMapper.insert(user);
            } catch (DuplicateKeyException e) {
                throw duplicateTenantException();
            }
            log.info("Added tenant config: {}", params.getUsername());

            scheduleTenantInfoFetchIfNeeded(user);
        }
    }

    private void scheduleTenantInfoFetchIfNeeded(OciUser user) {
        if (user == null || StrUtil.isBlank(user.getId()) || !shouldFetchTenantInfo(user)) {
            return;
        }
        if (!tenantInfoInflight.add(user.getId())) {
            return;
        }
        try {
            TENANT_INFO_REFRESH_EXECUTOR.execute(() -> {
                try {
                    OciUser latest = userMapper.selectById(user.getId());
                    if (latest != null && shouldFetchTenantInfo(latest)) {
                        fetchTenantInfo(latest);
                    }
                } finally {
                    tenantInfoInflight.remove(user.getId());
                }
            });
        } catch (RejectedExecutionException e) {
            tenantInfoInflight.remove(user.getId());
            log.debug("Tenant info refresh queue is full; deferring tenant {}", user.getId());
        }
    }

    private boolean shouldFetchTenantInfo(OciUser user) {
        if (StrUtil.isBlank(user.getTenantName())) {
            return true;
        }
        LocalDateTime tenantNameUpdatedAt = user.getTenantNameUpdatedAt();
        if (tenantNameUpdatedAt == null || tenantNameUpdatedAt.plusHours(TENANT_NAME_REFRESH_HOURS).isBefore(LocalDateTime.now())) {
            return true;
        }
        return shouldFetchPlanType(user);
    }

    private boolean shouldFetchPlanType(OciUser user) {
        if (StrUtil.isBlank(user.getPlanType())) {
            LocalDateTime next = user.getInfoNextRetryAt();
            return next == null || !next.isAfter(LocalDateTime.now());
        }
        LocalDateTime planUpdatedAt = user.getPlanTypeUpdatedAt();
        return planUpdatedAt == null || planUpdatedAt.plusHours(TENANT_NAME_REFRESH_HOURS).isBefore(LocalDateTime.now());
    }

    private void normalizeTenantParams(TenantParams params) {
        params.setUsername(trim(params.getUsername()));
        params.setOciTenantId(trim(params.getOciTenantId()));
        params.setOciUserId(trim(params.getOciUserId()));
        params.setOciFingerprint(trim(params.getOciFingerprint()));
        params.setOciRegion(trim(params.getOciRegion()));
        params.setOciKeyPath(trim(params.getOciKeyPath()));
        params.setGroupLevel1(trim(params.getGroupLevel1()));
        params.setGroupLevel2(trim(params.getGroupLevel2()));
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private void ensureRequiredTenantParams(TenantParams params) {
        if (StrUtil.isBlank(params.getUsername())) {
            throw new OciException("名称不能为空");
        }
        if (StrUtil.isBlank(params.getOciTenantId())) {
            throw new OciException("Tenant OCID 不能为空");
        }
        if (StrUtil.isBlank(params.getOciUserId())) {
            throw new OciException("User OCID 不能为空");
        }
        if (StrUtil.isBlank(params.getOciFingerprint())) {
            throw new OciException("Fingerprint 不能为空");
        }
        if (StrUtil.isBlank(params.getOciRegion())) {
            throw new OciException("Region 不能为空");
        }
    }

    private void ensureTenantAvailable(String ociTenantId, String excludeId) {
        LambdaQueryWrapper<OciUser> wrapper = new LambdaQueryWrapper<OciUser>()
                .eq(OciUser::getOciTenantId, ociTenantId);
        if (StrUtil.isNotBlank(excludeId)) {
            wrapper.ne(OciUser::getId, excludeId);
        }
        long duplicateCount = userMapper.selectCount(wrapper);
        if (duplicateCount > 0) {
            throw duplicateTenantException();
        }
    }

    private OciException duplicateTenantException() {
        return new OciException("该租户已存在（相同 tenancy），请勿重复添加");
    }

    private void validateOciCredentials(TenantParams params) {
        com.ociworker.model.dto.SysUserDTO dto = com.ociworker.model.dto.SysUserDTO.builder()
                .username(params.getUsername())
                .ociCfg(com.ociworker.model.dto.SysUserDTO.OciCfg.builder()
                        .tenantId(params.getOciTenantId())
                        .userId(params.getOciUserId())
                        .fingerprint(params.getOciFingerprint())
                        .region(params.getOciRegion())
                        .privateKeyPath(params.getOciKeyPath())
                        .build())
                .build();
        try (OciClientService client = new OciClientService(dto)) {
            client.getIdentityClient().getTenancy(
                    com.oracle.bmc.identity.requests.GetTenancyRequest.builder()
                            .tenancyId(params.getOciTenantId())
                            .build());
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("NotAuthenticated")) {
                throw new OciException("API 配置验证失败：认证不通过，请检查 Tenant ID、User ID、Fingerprint 和密钥文件");
            } else if (msg != null && msg.contains("not found")) {
                throw new OciException("API 配置验证失败：Tenant ID 不存在");
            } else if (e instanceof java.io.IOException || (msg != null && msg.contains("key"))) {
                throw new OciException("API 配置验证失败：密钥文件无效或不存在");
            }
            throw new OciException("API 配置验证失败：" + (msg != null ? msg.substring(0, Math.min(msg.length(), 120)) : "未知错误"));
        }
    }

    public void update(TenantParams params) {
        if (StrUtil.isBlank(params.getId())) {
            throw new OciException("ID不能为空");
        }
        normalizeTenantParams(params);
        ensureRequiredTenantParams(params);
        OciUser user = userMapper.selectById(params.getId());
        if (user == null) {
            throw new OciException("配置不存在");
        }
        Object lock = tenantLocks.computeIfAbsent(params.getOciTenantId(), k -> new Object());
        synchronized (lock) {
            ensureTenantAvailable(params.getOciTenantId(), params.getId());
            user.setUsername(params.getUsername());
            user.setOciTenantId(params.getOciTenantId());
            user.setOciUserId(params.getOciUserId());
            user.setOciFingerprint(params.getOciFingerprint());
            user.setOciRegion(params.getOciRegion());
            if (StrUtil.isNotBlank(params.getOciKeyPath())) {
                user.setOciKeyPath(params.getOciKeyPath());
            }
            user.setGroupLevel1(StrUtil.isBlank(params.getGroupLevel1()) ? null : params.getGroupLevel1());
            user.setGroupLevel2(StrUtil.isBlank(params.getGroupLevel2()) ? null : params.getGroupLevel2());
            try {
                userMapper.updateById(user);
            } catch (DuplicateKeyException e) {
                throw duplicateTenantException();
            }
        }
        log.info("Updated tenant config: {}", params.getUsername());
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(IdListParams params) {
        oracleAiPortBindingService.removeByTenantIds(params.getIdList());
        ociOpenaiKeyService.removeByTenantIds(params.getIdList());
        oracleAiModelWhitelistService.removeByTenantIds(params.getIdList());
        userMapper.deleteByIds(params.getIdList());
        log.info("Removed tenant configs: {}", params.getIdList());
    }

    @Transactional(rollbackFor = Exception.class)
    public void batchMoveGroup(TenantBatchMoveGroupParams params) {
        String l1 = params.getGroupLevel1().trim();
        String l2 = null;
        if (!"未分组".equals(l1) && StrUtil.isNotBlank(params.getGroupLevel2())) {
            l2 = params.getGroupLevel2().trim();
        }
        for (String id : params.getIdList()) {
            OciUser user = userMapper.selectById(id);
            if (user == null) {
                throw new OciException("配置不存在: " + id);
            }
            user.setGroupLevel1(l1);
            user.setGroupLevel2(l2);
            userMapper.updateById(user);
        }
        log.info("Batch moved {} tenants to group {}/{}", params.getIdList().size(), l1, l2);
    }

    public OciUser getById(String id) {
        OciUser user = userMapper.selectById(id);
        if (user == null) {
            throw new OciException("配置不存在");
        }
        return user;
    }

    public void refreshPlanType(String id) {
        OciUser user = userMapper.selectById(id);
        if (user == null) throw new OciException("配置不存在");
        fetchTenantInfo(user);
    }

    public void refreshInfo(String id) {
        OciUser user = userMapper.selectById(id);
        if (user == null) throw new OciException("配置不存在");
        fetchTenantInfo(user);
    }

    private void fetchTenantInfo(OciUser user) {
        try {
            com.ociworker.model.dto.SysUserDTO dto = com.ociworker.model.dto.SysUserDTO.builder()
                    .username(user.getUsername())
                    .ociCfg(com.ociworker.model.dto.SysUserDTO.OciCfg.builder()
                            .tenantId(user.getOciTenantId())
                            .userId(user.getOciUserId())
                            .fingerprint(user.getOciFingerprint())
                            .region(user.getOciRegion())
                            .privateKeyPath(user.getOciKeyPath())
                            .compartmentId(user.getOciTenantId())
                            .build())
                    .build();
            try (OciClientService client = new OciClientService(dto)) {
                boolean tenantChanged = false;
                try {
                    var tenancy = client.getIdentityClient().getTenancy(
                            com.oracle.bmc.identity.requests.GetTenancyRequest.builder()
                                    .tenancyId(user.getOciTenantId())
                                    .build()).getTenancy();
                    if (tenancy != null && StrUtil.isNotBlank(tenancy.getName())) {
                        if (!Objects.equals(user.getTenantName(), tenancy.getName())) {
                            user.setTenantName(tenancy.getName());
                        }
                        user.setTenantNameStatus("SUCCESS");
                        user.setTenantNameError(null);
                        user.setTenantNameUpdatedAt(LocalDateTime.now());
                        tenantChanged = true;
                    }
                } catch (Exception e) {
                    user.setTenantNameStatus("FAILED");
                    user.setTenantNameError(shortError(e));
                    user.setTenantNameUpdatedAt(LocalDateTime.now());
                    tenantChanged = true;
                    log.warn("获取租户名失败：[{}] {}", user.getUsername(), shortError(e));
                }
                if (tenantChanged) {
                    userMapper.updateById(user);
                }

                boolean planChanged = false;
                if (shouldFetchPlanType(user)) {
                    com.oracle.bmc.ospgateway.SubscriptionServiceClient ospClient = buildOspClient(client);
                    try {
                        var resp = ospClient.listSubscriptions(
                                com.oracle.bmc.ospgateway.requests.ListSubscriptionsRequest.builder()
                                        .ospHomeRegion(user.getOciRegion())
                                        .compartmentId(client.getCompartmentId())
                                        .limit(1)
                                        .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION)
                                        .build());
                        var items = resp.getSubscriptionCollection().getItems();
                        if (items != null && !items.isEmpty()) {
                            String planType = items.get(0).getPlanType() != null
                                    ? items.get(0).getPlanType().getValue() : "UNKNOWN";
                            user.setPlanType(planType);
                        } else {
                            user.setPlanType("UNKNOWN");
                        }
                        user.setPlanTypeStatus("SUCCESS");
                        user.setPlanTypeError(null);
                        user.setPlanTypeUpdatedAt(LocalDateTime.now());
                        user.setInfoRetryCount(0);
                        user.setInfoNextRetryAt(null);
                        planChanged = true;
                    } catch (Exception e) {
                        int retries = Optional.ofNullable(user.getInfoRetryCount()).orElse(0) + 1;
                        user.setPlanTypeStatus("FAILED");
                        user.setPlanTypeError(shortError(e));
                        user.setPlanTypeUpdatedAt(LocalDateTime.now());
                        user.setInfoRetryCount(retries);
                        user.setInfoNextRetryAt(LocalDateTime.now().plusMinutes(planRetryMinutes(retries)));
                        planChanged = true;
                        log.warn("获取账户类型失败：[{}] {}", user.getUsername(), shortError(e));
                    } finally {
                        ospClient.close();
                    }
                }

                if (planChanged) {
                    userMapper.updateById(user);
                }
            }
        } catch (Exception e) {
            user.setTenantNameStatus("FAILED");
            user.setTenantNameError(shortError(e));
            user.setTenantNameUpdatedAt(LocalDateTime.now());
            user.setPlanTypeStatus("FAILED");
            user.setPlanTypeError(shortError(e));
            user.setPlanTypeUpdatedAt(LocalDateTime.now());
            userMapper.updateById(user);
            log.warn("获取租户信息失败：[{}] {}", user.getUsername(), shortError(e));
        }
    }

    private static long planRetryMinutes(int retries) {
        int safeRetries = Math.max(1, Math.min(retries, 6));
        return Math.min(240, 5L << (safeRetries - 1));
    }

    private static String shortError(Exception e) {
        String msg = OciBmcErrorTranslator.translate(e);
        if (StrUtil.isBlank(msg)) {
            msg = e == null ? "未知错误" : e.getClass().getSimpleName();
        }
        return msg.length() > 512 ? msg.substring(0, 512) : msg;
    }

    public Map<String, Object> getTenantFullInfo(String id) {
        OciUser user = userMapper.selectById(id);
        if (user == null) throw new OciException("配置不存在");

        Map<String, Object> result = Collections.synchronizedMap(new LinkedHashMap<>());
        result.put("configName", user.getUsername());
        result.put("id", user.getId());
        if (StrUtil.isNotBlank(user.getTenantName())) {
            result.put("tenantName", user.getTenantName());
        }
        if (StrUtil.isNotBlank(user.getOciTenantId())) {
            result.put("tenantId", user.getOciTenantId());
        }
        if (StrUtil.isNotBlank(user.getPlanType())) {
            result.put("planType", user.getPlanType());
            result.put("planTypeLabel", OspSubscriptionEnricher.labelPlanType(user.getPlanType()));
        }

        com.ociworker.model.dto.SysUserDTO dto = com.ociworker.model.dto.SysUserDTO.builder()
                .username(user.getUsername())
                .ociCfg(com.ociworker.model.dto.SysUserDTO.OciCfg.builder()
                        .tenantId(user.getOciTenantId())
                        .userId(user.getOciUserId())
                        .fingerprint(user.getOciFingerprint())
                        .region(user.getOciRegion())
                        .privateKeyPath(user.getOciKeyPath())
                        .compartmentId(user.getOciTenantId())
                        .build())
                .build();

        try (OciClientService client = new OciClientService(dto)) {
            String savedTenantName = user.getTenantName();
            String savedPlanType = user.getPlanType();
            IdentityClient identityClient = client.getIdentityClient();
            String tenancyId = user.getOciTenantId();
            String fallbackRegion = user.getOciRegion();
            String compartmentId = client.getCompartmentId();
            String homeRegionName = applyIdentityAccountFields(identityClient, tenancyId, user, result);
            if (StrUtil.isBlank(homeRegionName)) {
                homeRegionName = fallbackRegion;
            }
            final String resolvedHomeRegionName = homeRegionName;

            CompletableFuture<List<Map<String, Object>>> assignedFut = CompletableFuture.supplyAsync(
                    () -> organizationSubscriptionService.listAssignedSubscriptionsOnly(
                            client, tenancyId, resolvedHomeRegionName),
                    TENANT_ACCOUNT_EXECUTOR);
            CompletableFuture<Void> ospFut = CompletableFuture.runAsync(
                    () -> applyOspAccountFields(client, resolvedHomeRegionName, compartmentId, result),
                    TENANT_ACCOUNT_EXECUTOR);

            try {
                ospFut.get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("获取租户 OSP 账户信息超时或失败：{}", shortError(e));
            }
            try {
                assignedFut.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("获取组织订阅信息超时或失败：{}", shortError(e));
            }

            List<Map<String, Object>> assignedRows = assignedFut.getNow(List.of());
            Map<String, Object> orgSub = new LinkedHashMap<>();
            orgSub.put("assignedSubscriptions", assignedRows);
            enrichSubscriptionStatusFromAssigned(result, orgSub);

            String ospRef = result.get("subscriptionOspRef") == null
                    ? null : String.valueOf(result.get("subscriptionOspRef")).trim();
            String orgOcid = resolveOrganizationSubscriptionOcid(ospRef, orgSub);
            if (StrUtil.isNotBlank(orgOcid)) {
                result.put("subscriptionOrgOcid", orgOcid);
            }

            String planVal = result.get("planType") == null ? null : String.valueOf(result.get("planType"));
            if (StrUtil.isNotBlank(planVal) && !Objects.equals(planVal, savedPlanType)) {
                user.setPlanType(planVal);
            }
            if (!Objects.equals(savedTenantName, user.getTenantName())
                    || !Objects.equals(savedPlanType, user.getPlanType())) {
                userMapper.updateById(user);
            }

        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof BmcException bmc) {
                throw new OciException("获取租户详情失败: " + OciBmcErrorTranslator.translate(bmc));
            }
            throw new OciException("获取租户详情失败: " + e.getMessage());
        }

        pruneTenantAccountInfo(result);
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getTenantBillingSummary(String id, Object limitsRaw) {
        if (StrUtil.isBlank(id)) throw new OciException("ID不能为空");
        OciUser user = userMapper.selectById(id);
        if (user == null) throw new OciException("配置不存在");

        Map<String, Integer> limits = new HashMap<>();
        limits.put("invoices", 5);
        limits.put("payments", 5);
        limits.put("usageStatements", 3);
        limits.put("costDays", 30);
        if (limitsRaw instanceof Map<?, ?> m) {
            Object inv = m.get("invoices");
            Object pay = m.get("payments");
            Object us = m.get("usageStatements");
            Object costDays = m.get("costDays");
            if (inv instanceof Number n) limits.put("invoices", Math.max(1, Math.min(50, n.intValue())));
            if (pay instanceof Number n) limits.put("payments", Math.max(1, Math.min(50, n.intValue())));
            if (us instanceof Number n) limits.put("usageStatements", Math.max(1, Math.min(50, n.intValue())));
            if (costDays instanceof Number n) limits.put("costDays", Math.max(1, Math.min(90, n.intValue())));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("configName", user.getUsername());
        result.put("ociRegion", user.getOciRegion());

        Map<String, Object> links = new LinkedHashMap<>();
        links.put("billingOverview", "https://cloud.oracle.com/billing/overview?region=" + user.getOciRegion());
        links.put("costAnalysis", "https://cloud.oracle.com/billing/cost-analysis?region=" + user.getOciRegion());
        links.put("invoices", "https://cloud.oracle.com/billing/invoices?region=" + user.getOciRegion());
        links.put("paymentHistory", "https://cloud.oracle.com/billing/payments?region=" + user.getOciRegion());
        links.put("upgradeAndPayment", "https://cloud.oracle.com/billing/account?region=" + user.getOciRegion());
        result.put("links", links);

        Map<String, Object> invoices = new LinkedHashMap<>();
        invoices.put("available", Boolean.TRUE);
        invoices.put("items", new ArrayList<>());
        result.put("invoices", invoices);

        Map<String, Object> payments = new LinkedHashMap<>();
        payments.put("available", Boolean.FALSE);
        payments.put("reason", "暂未接入付款历史 API（不同账号形态可用性不一致），请使用控制台查看");
        payments.put("items", new ArrayList<>());
        result.put("payments", payments);

        Map<String, Object> usage;
        try {
            usage = usageCostService.fetchCostAnalysis(id, limits.get("costDays"));
        } catch (Exception e) {
            usage = new LinkedHashMap<>();
            usage.put("available", Boolean.FALSE);
            usage.put("reason", e.getMessage() == null ? "成本分析查询失败" : e.getMessage());
            usage.put("summary", null);
            usage.put("byService", new ArrayList<>());
            usage.put("byDay", new ArrayList<>());
        }
        result.put("usage", usage);

        com.ociworker.model.dto.SysUserDTO dto = com.ociworker.model.dto.SysUserDTO.builder()
                .username(user.getUsername())
                .ociCfg(com.ociworker.model.dto.SysUserDTO.OciCfg.builder()
                        .tenantId(user.getOciTenantId())
                        .userId(user.getOciUserId())
                        .fingerprint(user.getOciFingerprint())
                        .region(user.getOciRegion())
                        .privateKeyPath(user.getOciKeyPath())
                        .build())
                .build();

        try (OciClientService client = new OciClientService(dto)) {
            String ospHomeRegion = resolveOspHomeRegion(client.getIdentityClient(), user.getOciTenantId(), user.getOciRegion());
            com.oracle.bmc.ospgateway.InvoiceServiceClient invoiceClient =
                    com.oracle.bmc.ospgateway.InvoiceServiceClient.builder().build(client.getProvider());
            try {
                var resp = invoiceClient.listInvoices(
                        com.oracle.bmc.ospgateway.requests.ListInvoicesRequest.builder()
                                .ospHomeRegion(ospHomeRegion)
                                .compartmentId(client.getCompartmentId())
                                .limit(limits.get("invoices"))
                                .build());
                List<Map<String, Object>> items = new ArrayList<>();
                // 不同 oci-java-sdk-ospgateway 版本 ListInvoicesResponse 的 getter 名称存在差异，这里用反射兼容。
                Object col = null;
                try {
                    col = resp.getClass().getMethod("getInvoiceSummaryCollection").invoke(resp);
                } catch (Exception ignored) {
                    try {
                        col = resp.getClass().getMethod("getInvoiceCollection").invoke(resp);
                    } catch (Exception ignored2) {
                        col = null;
                    }
                }

                java.util.List<?> summaries = null;
                if (col != null) {
                    try {
                        Object rawItems = col.getClass().getMethod("getItems").invoke(col);
                        if (rawItems instanceof java.util.List<?> list) {
                            summaries = list;
                        }
                    } catch (Exception ignored) {
                        summaries = null;
                    }
                }

                if (summaries != null) {
                    for (Object inv : summaries) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("invoiceId", tryInvoke(inv, "getInternalInvoiceId"));
                        row.put("invoiceNo", tryInvokeAny(inv, "getInvoiceNo", "getInvoiceNumber"));
                        row.put("refNo", tryInvokeAny(inv, "getRefNo", "getInvoiceRefNumber"));
                        row.put("status", tryEnumValue(tryInvokeAny(inv, "getStatus", "getInvoiceStatus")));
                        row.put("type", tryEnumValue(tryInvokeAny(inv, "getType", "getInvoiceType")));
                        row.put("invoiceDate", tryIsoTime(tryInvokeAny(inv, "getInvoiceDate", "getTimeInvoice")));
                        row.put("dueDate", tryIsoTime(tryInvokeAny(inv, "getDueDate", "getTimeInvoiceDue")));
                        row.put("totalAmount", tryInvokeAny(inv, "getTotalAmount", "getInvoiceAmount"));
                        Object currencyCode = tryInvoke(inv, "getCurrencyCode");
                        if (currencyCode == null) {
                            currencyCode = tryInvoke(tryInvoke(inv, "getCurrency"), "getCurrencyCode");
                        }
                        row.put("currencyCode", currencyCode);
                        items.add(row);
                    }
                }
                // 某些 SDK 版本不支持 sortBy/sortOrder，改为本地按 invoiceDate 降序排序（字符串为 RFC3339 时可比较）
                items.sort((a, b) -> {
                    String da = String.valueOf(a.getOrDefault("invoiceDate", ""));
                    String db = String.valueOf(b.getOrDefault("invoiceDate", ""));
                    return db.compareTo(da);
                });
                invoices.put("items", items);
            } catch (Exception e) {
                invoices.put("available", Boolean.FALSE);
                invoices.put("reason", "发票接口不可用/权限不足：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
            } finally {
                invoiceClient.close();
            }
        } catch (Exception e) {
            invoices.put("available", Boolean.FALSE);
            invoices.put("reason", "初始化账务客户端失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("invoiceCount", ((List<?>) invoices.getOrDefault("items", List.of())).size());
        Map<String, Object> latestInvoice = null;
        List<?> invItems = (List<?>) invoices.getOrDefault("items", List.of());
        if (!invItems.isEmpty() && invItems.get(0) instanceof Map<?, ?> m) {
            latestInvoice = new LinkedHashMap<>();
            latestInvoice.put("invoiceNo", m.get("invoiceNo"));
            latestInvoice.put("status", m.get("status"));
            latestInvoice.put("totalAmount", m.get("totalAmount"));
            latestInvoice.put("currencyCode", m.get("currencyCode"));
            latestInvoice.put("dueDate", m.get("dueDate"));
        }
        summary.put("latestInvoice", latestInvoice);
        result.put("summary", summary);

        return result;
    }

    @SuppressWarnings("unchecked")
    private static void pruneTenantAccountInfo(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return;
        }
        result.keySet().removeIf(k -> !TENANT_ACCOUNT_INFO_KEYS.contains(k));
    }

    @SuppressWarnings("unchecked")
    private static String resolveOrganizationSubscriptionOcid(String ospRef, Map<String, Object> orgSub) {
        List<String> ids = resolveOrganizationSubscriptionOcids(ospRef, orgSub);
        return ids.isEmpty() ? null : ids.get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<String> resolveOrganizationSubscriptionOcids(
            String ospRef, Map<String, Object> orgSub) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (orgSub == null) {
            return List.of();
        }
        Object assigned = orgSub.get("assignedSubscriptions");
        if (assigned instanceof List<?> list) {
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> m)) {
                    continue;
                }
                String id = m.get("id") == null ? null : String.valueOf(m.get("id")).trim();
                if (!OspSubscriptionEnricher.isOciOcid(id)) {
                    continue;
                }
                if (StrUtil.isBlank(ospRef)) {
                    ids.add(id);
                    continue;
                }
                String num = m.get("subscriptionNumber") == null
                        ? null : String.valueOf(m.get("subscriptionNumber")).trim();
                if (ospRef.equals(num) || ospRef.equals(id)) {
                    ids.add(id);
                }
            }
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> m)) {
                    continue;
                }
                String id = m.get("id") == null ? null : String.valueOf(m.get("id")).trim();
                if (OspSubscriptionEnricher.isOciOcid(id)) {
                    ids.add(id);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private static SubscriptionServiceClient buildOspClient(OciClientService client) {
        ClientConfiguration ospConfig = ClientConfiguration.builder()
                .connectionTimeoutMillis(5_000)
                .readTimeoutMillis(10_000)
                .build();
        var b = SubscriptionServiceClient.builder()
                .configuration(ospConfig);
        b.additionalClientConfigurator(client.getOciClientConfigurator());
        return b.build(client.getProvider());
    }

    private String applyIdentityAccountFields(
            IdentityClient ic,
            String tenancyId,
            OciUser user,
            Map<String, Object> result) {
        String homeRegionName = null;
        try {
            var tenancy = ic.getTenancy(
                    GetTenancyRequest.builder().tenancyId(tenancyId).build()).getTenancy();
            String homeRegionKey = null;
            if (tenancy != null) {
                result.put("tenantName", tenancy.getName());
                if (StrUtil.isNotBlank(tenancy.getName()) && !tenancy.getName().equals(user.getTenantName())) {
                    user.setTenantName(tenancy.getName());
                }
                homeRegionKey = tenancy.getHomeRegionKey();
                result.put("homeRegionKey", homeRegionKey);
                result.put("tenantId", tenancy.getId());
                result.put("description", tenancy.getDescription());
            }
            var regions = ic.listRegionSubscriptions(
                    ListRegionSubscriptionsRequest.builder().tenancyId(tenancyId).build()).getItems();
            List<String> regionNames = new ArrayList<>();
            if (regions != null) {
                for (var r : regions) {
                    regionNames.add(r.getRegionName());
                    if (StrUtil.isNotBlank(homeRegionKey)
                            && homeRegionKey.equalsIgnoreCase(r.getRegionKey())
                            && StrUtil.isNotBlank(r.getRegionName())) {
                        homeRegionName = r.getRegionName();
                    }
                }
            }
            result.put("subscribedRegions", regionNames);
        } catch (Exception e) {
            log.warn("Failed to get identity account fields: {}", e.getMessage());
        }
        return homeRegionName;
    }

    private static void applyOspAccountFields(
            OciClientService client,
            String ospHomeRegion,
            String compartmentId,
            Map<String, Object> result) {
        try (SubscriptionServiceClient ospClient = buildOspClient(client)) {
            var resp = ospClient.listSubscriptions(
                    ListSubscriptionsRequest.builder()
                            .ospHomeRegion(ospHomeRegion)
                            .compartmentId(compartmentId)
                            .limit(1)
                            .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION)
                            .build());
            var items = resp.getSubscriptionCollection() == null
                    ? null : resp.getSubscriptionCollection().getItems();
            if (items == null || items.isEmpty()) {
                return;
            }
            var sub = items.get(0);
            String subId = sub.getId();
            OspSubscriptionEnricher.enrich(sub, result);
            Object merged = sub;
            if (OspSubscriptionEnricher.isOciOcid(subId)) {
                Object detail = OspSubscriptionEnricher.fetchSubscriptionDetail(
                        ospClient, ospHomeRegion, compartmentId, subId);
                if (detail != null) {
                    merged = detail;
                    OspSubscriptionEnricher.enrich(detail, result);
                }
            }
            applyRegistrationFromSdk(merged, result);
            if (StrUtil.isNotBlank(subId)) {
                result.put("subscriptionOspRef", subId.trim());
                if (!OspSubscriptionEnricher.isOciOcid(subId)
                        && result.get("subscriptionPlanNumber") == null) {
                    result.put("subscriptionPlanNumber", subId.trim());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get OSP subscription: {}", e.getMessage());
        }
    }

    private static void applyRegistrationFromSdk(Object merged, Map<String, Object> result) {
        if (merged == null || result == null) {
            return;
        }
        String countryName = null;
        Object addr = tryInvoke(merged, "getBillToAddress");
        if (addr == null) {
            addr = tryInvoke(merged, "getBillingAddress");
        }
        if (addr == null) {
            addr = tryInvoke(merged, "getAddress");
        }
        Object country = addr == null ? null : tryInvoke(addr, "getCountry");
        if (country != null) {
            Object n = tryInvoke(country, "getName");
            if (n == null) {
                n = tryInvoke(country, "getCountryName");
            }
            if (n == null) {
                n = tryInvoke(country, "getDisplayName");
            }
            if (n != null) {
                countryName = String.valueOf(n);
            }
        }
        if (StrUtil.isBlank(countryName) && addr != null) {
            Object n = tryInvoke(addr, "getCountryName");
            if (n == null) {
                n = tryInvoke(addr, "getCountry");
            }
            if (n != null) {
                countryName = String.valueOf(n);
            }
        }
        if (StrUtil.isNotBlank(countryName)) {
            result.put("registrationLocation", countryName);
        } else {
            result.putIfAbsent("registrationLocation", null);
        }
    }

    @SuppressWarnings("unchecked")
    private static void enrichSubscriptionStatusFromAssigned(Map<String, Object> result, Map<String, Object> orgSub) {
        if (result == null || orgSub == null) {
            return;
        }
        Object assigned = orgSub.get("assignedSubscriptions");
        if (!(assigned instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        boolean needStatus = result.get("subscriptionStatus") == null;
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> m)) {
                continue;
            }
            Object subNumber = m.get("subscriptionNumber");
            if (result.get("subscriptionPlanNumber") == null
                    && subNumber != null
                    && StrUtil.isNotBlank(String.valueOf(subNumber))) {
                result.put("subscriptionPlanNumber", String.valueOf(subNumber).trim());
            }
            Object currency = m.get("currencyCode");
            if (result.get("currencyCode") == null
                    && currency != null
                    && StrUtil.isNotBlank(String.valueOf(currency))) {
                result.put("currencyCode", String.valueOf(currency).trim());
            }
            if (!needStatus) {
                continue;
            }
            String lifecycle = m.get("lifecycleState") == null
                    ? null : String.valueOf(m.get("lifecycleState")).trim();
            if (StrUtil.isNotBlank(lifecycle)) {
                String code = lifecycle.toUpperCase(Locale.ROOT);
                result.put("subscriptionStatus", code);
                result.put("subscriptionStatusLabel", OspSubscriptionEnricher.labelSubscriptionStatus(code));
                needStatus = false;
            }
        }
    }

    private static Object tryInvoke(Object target, String method) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    // 按顺序尝试多个 getter 名，取第一个非 null 结果（兼容不同 SDK 版本的命名差异）
    private static Object tryInvokeAny(Object target, String... methods) {
        for (String method : methods) {
            Object v = tryInvoke(target, method);
            if (v != null) return v;
        }
        return null;
    }

    // Date 统一转 ISO-8601（UTC）字符串，保证前端可读且按字符串排序正确
    private static String tryIsoTime(Object v) {
        if (v instanceof java.util.Date d) {
            return java.time.format.DateTimeFormatter.ISO_INSTANT.format(d.toInstant());
        }
        return tryToString(v);
    }

    private static String tryToString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String tryEnumValue(Object v) {
        if (v == null) return null;
        try {
            Object raw = v.getClass().getMethod("getValue").invoke(v);
            return raw == null ? null : String.valueOf(raw);
        } catch (Exception ignored) {
            return String.valueOf(v);
        }
    }

    private static String resolveOspHomeRegion(com.oracle.bmc.identity.IdentityClient identityClient,
                                              String tenancyId,
                                              String fallbackRegionName) {
        if (identityClient == null || StrUtil.isBlank(tenancyId)) return fallbackRegionName;
        try {
            var tenancy = identityClient.getTenancy(
                    com.oracle.bmc.identity.requests.GetTenancyRequest.builder()
                            .tenancyId(tenancyId).build()).getTenancy();
            String homeKey = tenancy == null ? null : tenancy.getHomeRegionKey();
            if (StrUtil.isBlank(homeKey)) return fallbackRegionName;

            var regions = identityClient.listRegionSubscriptions(
                    com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest.builder()
                            .tenancyId(tenancyId).build()).getItems();
            if (regions != null) {
                for (var r : regions) {
                    if (homeKey.equalsIgnoreCase(r.getRegionKey())) {
                        String name = r.getRegionName();
                        if (StrUtil.isNotBlank(name)) return name;
                    }
                }
            }
        } catch (Exception ignored) {
            // Keep the configured fallback region when home-region lookup is unavailable.
        }
        return fallbackRegionName;
    }

    public byte[] downloadInvoicePdf(String id, String invoiceId) {
        if (StrUtil.isBlank(id)) throw new OciException("ID不能为空");
        if (StrUtil.isBlank(invoiceId)) throw new OciException("invoiceId不能为空");
        OciUser user = userMapper.selectById(id);
        if (user == null) throw new OciException("配置不存在");

        com.ociworker.model.dto.SysUserDTO dto = com.ociworker.model.dto.SysUserDTO.builder()
                .username(user.getUsername())
                .ociCfg(com.ociworker.model.dto.SysUserDTO.OciCfg.builder()
                        .tenantId(user.getOciTenantId())
                        .userId(user.getOciUserId())
                        .fingerprint(user.getOciFingerprint())
                        .region(user.getOciRegion())
                        .privateKeyPath(user.getOciKeyPath())
                        .build())
                .build();

        try (OciClientService client = new OciClientService(dto)) {
            String ospHomeRegion = resolveOspHomeRegion(client.getIdentityClient(), user.getOciTenantId(), user.getOciRegion());
            com.oracle.bmc.ospgateway.InvoiceServiceClient invoiceClient =
                    com.oracle.bmc.ospgateway.InvoiceServiceClient.builder().build(client.getProvider());
            try {
                var resp = invoiceClient.downloadPdfContent(
                        com.oracle.bmc.ospgateway.requests.DownloadPdfContentRequest.builder()
                                .ospHomeRegion(ospHomeRegion)
                                .compartmentId(client.getCompartmentId())
                                .internalInvoiceId(invoiceId)
                                .build());
                try (InputStream is = resp.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    is.transferTo(out);
                    return out.toByteArray();
                }
            } finally {
                invoiceClient.close();
            }
        } catch (Exception e) {
            throw new OciException("下载发票 PDF 失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    public Map<String, Object> getDistinctGroups() {
        List<OciUser> all = userMapper.selectList(null);
        Set<String> level1 = new TreeSet<>();
        Map<String, Set<String>> level2Map = new TreeMap<>();
        for (OciUser u : all) {
            String g1 = u.getGroupLevel1();
            if (StrUtil.isNotBlank(g1)) {
                level1.add(g1);
                String g2 = u.getGroupLevel2();
                if (StrUtil.isNotBlank(g2)) {
                    level2Map.computeIfAbsent(g1, k -> new TreeSet<>()).add(g2);
                }
            }
        }
        // merge persisted empty groups from oci_kv
        List<OciKv> kvGroups = kvMapper.selectList(
                new LambdaQueryWrapper<OciKv>().eq(OciKv::getType, GROUP_TYPE));
        Map<String, String> savedOrderValues = new HashMap<>();
        for (OciKv kv : kvGroups) {
            String code = kv.getCode();
            if (code.startsWith(GROUP_L1_PREFIX)) {
                level1.add(code.substring(GROUP_L1_PREFIX.length()));
            } else if (code.startsWith(GROUP_L2_PREFIX)) {
                String val = kv.getValue();
                if (StrUtil.isNotBlank(val)) {
                    String parent = code.substring(GROUP_L2_PREFIX.length());
                    level2Map.computeIfAbsent(parent, k -> new TreeSet<>()).add(val);
                }
            }
            if (GROUP_ORDER_CODE.equals(code) || code.startsWith(GROUP_ORDER_L2_PREFIX)) {
                savedOrderValues.put(code, kv.getValue());
            }
        }
        // apply saved order for level1
        List<String> ordered = new ArrayList<>();
        String level1Order = savedOrderValues.get(GROUP_ORDER_CODE);
        if (StrUtil.isNotBlank(level1Order)) {
            for (String name : level1Order.split(",")) {
                String n = name.trim();
                if (level1.remove(n)) ordered.add(n);
            }
        }
        ordered.addAll(level1);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("level1", ordered);
        Map<String, List<String>> l2 = new LinkedHashMap<>();
        level2Map.forEach((k, v) -> l2.put(k,
                applySavedGroupOrder(v, savedOrderValues.get(GROUP_ORDER_L2_PREFIX + k))));
        result.put("level2", l2);
        return result;
    }

    public void saveGroupOrder(List<String> order) {
        saveGroupOrder(order, null);
    }

    public void saveGroupOrder(List<String> order, String parent) {
        if (order == null || order.isEmpty()) return;
        String code = StrUtil.isBlank(parent) ? GROUP_ORDER_CODE : GROUP_ORDER_L2_PREFIX + parent.trim();
        String value = String.join(",", order);
        OciKv kv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, code));
        if (kv != null) {
            kv.setValue(value);
            kvMapper.updateById(kv);
        } else {
            kv = new OciKv();
            kv.setId(CommonUtils.generateId());
            kv.setCode(code);
            kv.setValue(value);
            kv.setType(GROUP_TYPE);
            kv.setCreateTime(LocalDateTime.now());
            kvMapper.insert(kv);
        }
        log.info("Saved group order {}: {}", code, value);
    }

    private List<String> applySavedGroupOrder(Set<String> values, String savedOrder) {
        Set<String> remaining = new TreeSet<>(values);
        List<String> ordered = new ArrayList<>();
        if (StrUtil.isNotBlank(savedOrder)) {
            for (String name : savedOrder.split(",")) {
                String n = name.trim();
                if (remaining.remove(n)) ordered.add(n);
            }
        }
        ordered.addAll(remaining);
        return ordered;
    }

    private String normalizeGroupNameForWrite(String name) {
        String groupName = StrUtil.blankToDefault(name, "").trim();
        if (StrUtil.isBlank(groupName)) throw new OciException("分组名不能为空");
        if ("未分组".equals(groupName)) throw new OciException("未分组为系统保留名称");
        if (groupName.contains(",")) throw new OciException("分组名不能包含英文逗号");
        return groupName;
    }

    private boolean level1GroupExists(List<OciUser> users, String name) {
        boolean existsInUsers = users.stream().anyMatch(u -> name.equals(u.getGroupLevel1()));
        if (existsInUsers) return true;
        return kvMapper.selectCount(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getType, GROUP_TYPE)
                .eq(OciKv::getCode, GROUP_L1_PREFIX + name)) > 0;
    }

    private boolean level2GroupExists(List<OciUser> users, String parent, String name) {
        boolean existsInUsers = users.stream().anyMatch(u ->
                parent.equals(u.getGroupLevel1()) && name.equals(u.getGroupLevel2()));
        if (existsInUsers) return true;
        return kvMapper.selectCount(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getType, GROUP_TYPE)
                .eq(OciKv::getCode, GROUP_L2_PREFIX + parent)
                .eq(OciKv::getValue, name)) > 0;
    }

    public void createGroup(String name, String level, String parent) {
        String groupName = normalizeGroupNameForWrite(name);
        if ("1".equals(level)) {
            String code = GROUP_L1_PREFIX + groupName;
            OciKv exist = kvMapper.selectOne(
                    new LambdaQueryWrapper<OciKv>().eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, code));
            if (exist == null) {
                OciKv kv = new OciKv();
                kv.setId(CommonUtils.generateId());
                kv.setCode(code);
                kv.setValue(groupName);
                kv.setType(GROUP_TYPE);
                kv.setCreateTime(LocalDateTime.now());
                kvMapper.insert(kv);
            }
        } else if ("2".equals(level)) {
            String parentName = StrUtil.blankToDefault(parent, "").trim();
            if (StrUtil.isBlank(parentName)) throw new OciException("子分组必须指定父分组");
            if ("未分组".equals(parentName)) throw new OciException("未分组不能作为子分组父级");
            String code = GROUP_L2_PREFIX + parentName;
            OciKv exist = kvMapper.selectOne(
                    new LambdaQueryWrapper<OciKv>().eq(OciKv::getType, GROUP_TYPE)
                            .eq(OciKv::getCode, code).eq(OciKv::getValue, groupName));
            if (exist == null) {
                OciKv kv = new OciKv();
                kv.setId(CommonUtils.generateId());
                kv.setCode(code);
                kv.setValue(groupName);
                kv.setType(GROUP_TYPE);
                kv.setCreateTime(LocalDateTime.now());
                kvMapper.insert(kv);
            }
        }
        log.info("Created group [{}] {} parent={}", level, groupName, parent);
    }

    public void renameGroup(String oldName, String newName, String level) {
        renameGroup(oldName, newName, level, null);
    }

    public void renameGroup(String oldName, String newName, String level, String parent) {
        if (StrUtil.isBlank(oldName)) throw new OciException("分组名不能为空");
        String currentName = oldName.trim();
        String targetName = normalizeGroupNameForWrite(newName);
        if (currentName.equals(targetName)) return;
        String parentName = StrUtil.blankToDefault(parent, "").trim();
        boolean hasParent = StrUtil.isNotBlank(parentName);

        List<OciUser> users = userMapper.selectList(null);
        if ("1".equals(level) && level1GroupExists(users, targetName)) {
            throw new OciException("一级分组已存在");
        }
        if ("2".equals(level) && hasParent && level2GroupExists(users, parentName, targetName)) {
            throw new OciException("该一级分组下已存在同名子分组");
        }
        for (OciUser u : users) {
            boolean changed = false;
            if ("1".equals(level) && currentName.equals(u.getGroupLevel1())) {
                u.setGroupLevel1(targetName);
                changed = true;
            }
            if ("2".equals(level) && currentName.equals(u.getGroupLevel2())
                    && (!hasParent || parentName.equals(u.getGroupLevel1()))) {
                u.setGroupLevel2(targetName);
                changed = true;
            }
            if (changed) userMapper.updateById(u);
        }
        // update oci_kv records
        if ("1".equals(level)) {
            updateGroupOrderValue(GROUP_ORDER_CODE, currentName, targetName);
            OciKv kv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, GROUP_L1_PREFIX + currentName));
            if (kv != null) {
                kv.setCode(GROUP_L1_PREFIX + targetName);
                kv.setValue(targetName);
                kvMapper.updateById(kv);
            }
            // rename parent references in level2 records
            List<OciKv> l2Kvs = kvMapper.selectList(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, GROUP_L2_PREFIX + currentName));
            for (OciKv l2 : l2Kvs) {
                l2.setCode(GROUP_L2_PREFIX + targetName);
                kvMapper.updateById(l2);
            }
            OciKv l2OrderKv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, GROUP_ORDER_L2_PREFIX + currentName));
            if (l2OrderKv != null) {
                l2OrderKv.setCode(GROUP_ORDER_L2_PREFIX + targetName);
                kvMapper.updateById(l2OrderKv);
            }
        } else if ("2".equals(level)) {
            LambdaQueryWrapper<OciKv> wrapper = new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, GROUP_TYPE)
                    .eq(OciKv::getValue, currentName);
            if (hasParent) {
                wrapper.eq(OciKv::getCode, GROUP_L2_PREFIX + parentName);
            } else {
                wrapper.likeRight(OciKv::getCode, GROUP_L2_PREFIX);
            }
            List<OciKv> kvs = kvMapper.selectList(wrapper);
            for (OciKv kv : kvs) {
                kv.setValue(targetName);
                kvMapper.updateById(kv);
            }
            if (hasParent) {
                updateGroupOrderValue(GROUP_ORDER_L2_PREFIX + parentName, currentName, targetName);
            } else {
                updateAllSubGroupOrderValues(currentName, targetName);
            }
        }
        log.info("Renamed group [{}] {} -> {} parent={}", level, currentName, targetName, parentName);
    }

    public void deleteGroup(String name, String level) {
        deleteGroup(name, level, null);
    }

    public void deleteGroup(String name, String level, String parent) {
        if (StrUtil.isBlank(name)) return;
        String parentName = StrUtil.blankToDefault(parent, "").trim();
        boolean hasParent = StrUtil.isNotBlank(parentName);
        List<OciUser> users = userMapper.selectList(null);
        for (OciUser u : users) {
            boolean changed = false;
            if ("1".equals(level) && name.equals(u.getGroupLevel1())) {
                u.setGroupLevel1("未分组");
                u.setGroupLevel2(null);
                changed = true;
            }
            if ("2".equals(level) && name.equals(u.getGroupLevel2())
                    && (!hasParent || parentName.equals(u.getGroupLevel1()))) {
                u.setGroupLevel2(null);
                changed = true;
            }
            if (changed) userMapper.updateById(u);
        }
        // remove oci_kv records
        if ("1".equals(level)) {
            removeGroupOrderValue(GROUP_ORDER_CODE, name);
            kvMapper.delete(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, GROUP_L1_PREFIX + name));
            kvMapper.delete(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, GROUP_L2_PREFIX + name));
            kvMapper.delete(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, GROUP_ORDER_L2_PREFIX + name));
        } else if ("2".equals(level)) {
            LambdaQueryWrapper<OciKv> wrapper = new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, GROUP_TYPE)
                    .eq(OciKv::getValue, name);
            if (hasParent) {
                wrapper.eq(OciKv::getCode, GROUP_L2_PREFIX + parentName);
            } else {
                wrapper.likeRight(OciKv::getCode, GROUP_L2_PREFIX);
            }
            kvMapper.delete(wrapper);
            if (hasParent) {
                removeGroupOrderValue(GROUP_ORDER_L2_PREFIX + parentName, name);
            } else {
                removeAllSubGroupOrderValues(name);
            }
        }
        log.info("Deleted group [{}] {} parent={}", level, name, parentName);
    }

    private void updateAllSubGroupOrderValues(String oldName, String newName) {
        List<OciKv> orderKvs = kvMapper.selectList(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getType, GROUP_TYPE).likeRight(OciKv::getCode, GROUP_ORDER_L2_PREFIX));
        for (OciKv kv : orderKvs) {
            replaceGroupOrderValue(kv, oldName, newName);
        }
    }

    private void removeAllSubGroupOrderValues(String name) {
        List<OciKv> orderKvs = kvMapper.selectList(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getType, GROUP_TYPE).likeRight(OciKv::getCode, GROUP_ORDER_L2_PREFIX));
        for (OciKv kv : orderKvs) {
            removeGroupOrderValue(kv, name);
        }
    }

    private void updateGroupOrderValue(String code, String oldName, String newName) {
        OciKv kv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, code));
        replaceGroupOrderValue(kv, oldName, newName);
    }

    private void removeGroupOrderValue(String code, String name) {
        OciKv kv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, code));
        removeGroupOrderValue(kv, name);
    }

    private void replaceGroupOrderValue(OciKv kv, String oldName, String newName) {
        if (kv == null || StrUtil.isBlank(kv.getValue())) return;
        List<String> names = new ArrayList<>();
        boolean changed = false;
        for (String part : kv.getValue().split(",")) {
            String n = part.trim();
            if (n.isEmpty()) continue;
            if (n.equals(oldName)) {
                n = newName;
                changed = true;
            }
            if (!names.contains(n)) names.add(n);
        }
        if (changed) {
            kv.setValue(String.join(",", names));
            kvMapper.updateById(kv);
        }
    }

    private void removeGroupOrderValue(OciKv kv, String name) {
        if (kv == null || StrUtil.isBlank(kv.getValue())) return;
        List<String> names = new ArrayList<>();
        boolean changed = false;
        for (String part : kv.getValue().split(",")) {
            String n = part.trim();
            if (n.isEmpty()) continue;
            if (n.equals(name)) {
                changed = true;
                continue;
            }
            if (!names.contains(n)) names.add(n);
        }
        if (changed) {
            kv.setValue(String.join(",", names));
            kvMapper.updateById(kv);
        }
    }

    public String uploadKey(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new OciException("私钥文件为空");
        }
        if (file.getSize() > TENANT_PRIVATE_KEY_MAX_BYTES) {
            throw new OciException("私钥文件不能超过 64 KB");
        }

        byte[] content = file.getBytes();
        String pem = new String(content, StandardCharsets.UTF_8);
        Matcher matcher = PEM_PRIVATE_KEY_PATTERN.matcher(pem);
        if (!matcher.find()) {
            throw new OciException("私钥文件不是完整、受支持的 PEM 私钥");
        }
        try {
            byte[] decoded = Base64.getMimeDecoder().decode(matcher.group(2));
            if (decoded.length == 0) {
                throw new OciException("私钥文件内容为空");
            }
        } catch (IllegalArgumentException e) {
            throw new OciException("私钥文件包含无效的 PEM 内容");
        }

        Path dirPath = Path.of(System.getProperty("user.dir"), keyDirPath).normalize();
        Files.createDirectories(dirPath);
        String fileName = CommonUtils.generateId() + ".pem";
        Path target = dirPath.resolve(fileName);
        try {
            String normalizedPem = "-----BEGIN " + matcher.group(1) + "-----"
                    + matcher.group(2) + "-----END " + matcher.group(1) + "-----\n";
            Files.write(target, normalizedPem.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(target, EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Windows and non-POSIX file systems do not expose POSIX permissions.
            }
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        log.info("Uploaded key file: {}", target.toAbsolutePath());
        return target.toAbsolutePath().toString();
    }
}
