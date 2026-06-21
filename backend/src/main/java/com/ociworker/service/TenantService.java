package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ociworker.enums.TaskStatusEnum;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.requests.GetTenancyRequest;
import com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest;
import com.oracle.bmc.ospgateway.SubscriptionServiceClient;
import com.oracle.bmc.ospgateway.requests.ListSubscriptionsRequest;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciCreateTask;
import com.ociworker.model.entity.OciKv;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.params.IdListParams;
import com.ociworker.model.params.PageParams;
import com.ociworker.model.params.TenantBatchMoveGroupParams;
import com.ociworker.model.params.TenantParams;
import com.ociworker.util.CommonUtils;
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
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TenantService {

    @Resource
    private OciUserMapper userMapper;
    @Resource
    private OciCreateTaskMapper taskMapper;
    @Resource
    private OciKvMapper kvMapper;

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

            long running = taskMapper.selectCount(
                    new LambdaQueryWrapper<OciCreateTask>()
                            .eq(OciCreateTask::getUserId, u.getId())
                            .eq(OciCreateTask::getStatus, TaskStatusEnum.RUNNING.getStatus()));
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

            Thread.ofVirtual().start(() -> fetchTenantInfo(user));
        }
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

    public void remove(IdListParams params) {
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
                try {
                    var tenancy = client.getIdentityClient().getTenancy(
                            com.oracle.bmc.identity.requests.GetTenancyRequest.builder()
                                    .tenancyId(user.getOciTenantId())
                                    .build()).getTenancy();
                    if (tenancy != null && StrUtil.isNotBlank(tenancy.getName())) {
                        user.setTenantName(tenancy.getName());
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch tenantName for {}: {}", user.getUsername(), e.getMessage());
                }

                com.oracle.bmc.ospgateway.SubscriptionServiceClient ospClient = buildOspClient(client);
                try {
                    var resp = ospClient.listSubscriptions(
                            com.oracle.bmc.ospgateway.requests.ListSubscriptionsRequest.builder()
                                    .ospHomeRegion(user.getOciRegion())
                                    .compartmentId(client.getCompartmentId())
                                    .build());
                    var items = resp.getSubscriptionCollection().getItems();
                    if (items != null && !items.isEmpty()) {
                        String planType = items.get(0).getPlanType() != null
                                ? items.get(0).getPlanType().getValue() : "UNKNOWN";
                        user.setPlanType(planType);
                    }
                } finally {
                    ospClient.close();
                }

                userMapper.updateById(user);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch tenant info for {}: {}", user.getUsername(), e.getMessage());
        }
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
                ospFut.get(45, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Tenant OSP account fetch timeout or error: {}", e.getMessage());
            }
            try {
                assignedFut.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Tenant organization subscription fetch timeout or error: {}", e.getMessage());
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
                        row.put("invoiceNo", tryInvoke(inv, "getInvoiceNo"));
                        row.put("refNo", tryInvoke(inv, "getRefNo"));
                        row.put("status", tryEnumValue(tryInvoke(inv, "getStatus")));
                        row.put("type", tryEnumValue(tryInvoke(inv, "getType")));
                        row.put("invoiceDate", tryToString(tryInvoke(inv, "getInvoiceDate")));
                        row.put("dueDate", tryToString(tryInvoke(inv, "getDueDate")));
                        row.put("totalAmount", tryInvoke(inv, "getTotalAmount"));
                        row.put("currencyCode", tryInvoke(inv, "getCurrencyCode"));
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
        var b = SubscriptionServiceClient.builder()
                .configuration(client.getClientConfiguration());
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
        if (result.get("subscriptionStatus") != null) {
            return;
        }
        Object assigned = orgSub.get("assignedSubscriptions");
        if (!(assigned instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> m)) {
                continue;
            }
            String lifecycle = m.get("lifecycleState") == null
                    ? null : String.valueOf(m.get("lifecycleState")).trim();
            if (StrUtil.isNotBlank(lifecycle)) {
                String code = lifecycle.toUpperCase(Locale.ROOT);
                result.put("subscriptionStatus", code);
                result.put("subscriptionStatusLabel", OspSubscriptionEnricher.labelSubscriptionStatus(code));
                return;
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
        } catch (Exception ignored) {}
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
        }
        // apply saved order for level1
        List<String> ordered = new ArrayList<>();
        OciKv orderKv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, GROUP_ORDER_CODE));
        if (orderKv != null && StrUtil.isNotBlank(orderKv.getValue())) {
            for (String name : orderKv.getValue().split(",")) {
                String n = name.trim();
                if (level1.remove(n)) ordered.add(n);
            }
        }
        ordered.addAll(level1);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("level1", ordered);
        Map<String, List<String>> l2 = new LinkedHashMap<>();
        level2Map.forEach((k, v) -> l2.put(k, applySavedGroupOrder(k, v)));
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

    private List<String> applySavedGroupOrder(String parent, Set<String> values) {
        Set<String> remaining = new TreeSet<>(values);
        List<String> ordered = new ArrayList<>();
        OciKv orderKv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, GROUP_ORDER_L2_PREFIX + parent));
        if (orderKv != null && StrUtil.isNotBlank(orderKv.getValue())) {
            for (String name : orderKv.getValue().split(",")) {
                String n = name.trim();
                if (remaining.remove(n)) ordered.add(n);
            }
        }
        ordered.addAll(remaining);
        return ordered;
    }

    public void createGroup(String name, String level, String parent) {
        if (StrUtil.isBlank(name)) throw new OciException("分组名不能为空");
        if ("1".equals(level)) {
            String code = GROUP_L1_PREFIX + name;
            OciKv exist = kvMapper.selectOne(
                    new LambdaQueryWrapper<OciKv>().eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, code));
            if (exist == null) {
                OciKv kv = new OciKv();
                kv.setId(CommonUtils.generateId());
                kv.setCode(code);
                kv.setValue(name);
                kv.setType(GROUP_TYPE);
                kv.setCreateTime(LocalDateTime.now());
                kvMapper.insert(kv);
            }
        } else if ("2".equals(level)) {
            if (StrUtil.isBlank(parent)) throw new OciException("子分组必须指定父分组");
            String code = GROUP_L2_PREFIX + parent;
            OciKv exist = kvMapper.selectOne(
                    new LambdaQueryWrapper<OciKv>().eq(OciKv::getType, GROUP_TYPE)
                            .eq(OciKv::getCode, code).eq(OciKv::getValue, name));
            if (exist == null) {
                OciKv kv = new OciKv();
                kv.setId(CommonUtils.generateId());
                kv.setCode(code);
                kv.setValue(name);
                kv.setType(GROUP_TYPE);
                kv.setCreateTime(LocalDateTime.now());
                kvMapper.insert(kv);
            }
        }
        log.info("Created group [{}] {} parent={}", level, name, parent);
    }

    public void renameGroup(String oldName, String newName, String level) {
        if (StrUtil.isBlank(oldName) || StrUtil.isBlank(newName)) throw new OciException("分组名不能为空");
        if (oldName.equals(newName)) return;

        List<OciUser> users = userMapper.selectList(null);
        for (OciUser u : users) {
            boolean changed = false;
            if ("1".equals(level) && oldName.equals(u.getGroupLevel1())) {
                u.setGroupLevel1(newName);
                changed = true;
            }
            if ("2".equals(level) && oldName.equals(u.getGroupLevel2())) {
                u.setGroupLevel2(newName);
                changed = true;
            }
            if (changed) userMapper.updateById(u);
        }
        // update oci_kv records
        if ("1".equals(level)) {
            updateGroupOrderValue(GROUP_ORDER_CODE, oldName, newName);
            OciKv kv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, GROUP_L1_PREFIX + oldName));
            if (kv != null) {
                kv.setCode(GROUP_L1_PREFIX + newName);
                kv.setValue(newName);
                kvMapper.updateById(kv);
            }
            // rename parent references in level2 records
            List<OciKv> l2Kvs = kvMapper.selectList(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, GROUP_L2_PREFIX + oldName));
            for (OciKv l2 : l2Kvs) {
                l2.setCode(GROUP_L2_PREFIX + newName);
                kvMapper.updateById(l2);
            }
            OciKv l2OrderKv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, GROUP_ORDER_L2_PREFIX + oldName));
            if (l2OrderKv != null) {
                l2OrderKv.setCode(GROUP_ORDER_L2_PREFIX + newName);
                kvMapper.updateById(l2OrderKv);
            }
        } else if ("2".equals(level)) {
            List<OciKv> kvs = kvMapper.selectList(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, GROUP_TYPE).likeRight(OciKv::getCode, GROUP_L2_PREFIX)
                    .eq(OciKv::getValue, oldName));
            for (OciKv kv : kvs) {
                kv.setValue(newName);
                kvMapper.updateById(kv);
            }
            updateAllSubGroupOrderValues(oldName, newName);
        }
        log.info("Renamed group [{}] {} -> {}", level, oldName, newName);
    }

    public void deleteGroup(String name, String level) {
        if (StrUtil.isBlank(name)) return;
        List<OciUser> users = userMapper.selectList(null);
        for (OciUser u : users) {
            boolean changed = false;
            if ("1".equals(level) && name.equals(u.getGroupLevel1())) {
                u.setGroupLevel1("未分组");
                u.setGroupLevel2(null);
                changed = true;
            }
            if ("2".equals(level) && name.equals(u.getGroupLevel2())) {
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
            kvMapper.delete(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, GROUP_TYPE).likeRight(OciKv::getCode, GROUP_L2_PREFIX)
                    .eq(OciKv::getValue, name));
            removeAllSubGroupOrderValues(name);
        }
        log.info("Deleted group [{}] {}", level, name);
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
        Path dirPath = Path.of(System.getProperty("user.dir"), keyDirPath).normalize();
        File dir = dirPath.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String fileName = CommonUtils.generateId() + ".pem";
        File target = new File(dir, fileName);
        file.transferTo(target);
        log.info("Uploaded key file: {}", target.getAbsolutePath());
        return target.getAbsolutePath();
    }
}
