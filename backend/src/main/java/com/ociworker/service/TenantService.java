package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ociworker.enums.TaskStatusEnum;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciCreateTask;
import com.ociworker.model.entity.OciKv;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.params.IdListParams;
import com.ociworker.model.params.PageParams;
import com.ociworker.model.params.TenantParams;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class TenantService {

    @Resource
    private OciUserMapper userMapper;
    @Resource
    private OciCreateTaskMapper taskMapper;
    @Resource
    private OciKvMapper kvMapper;

    private static final String GROUP_TYPE = "group";
    private static final String GROUP_L1_PREFIX = "group_l1:";
    private static final String GROUP_L2_PREFIX = "group_l2:";
    private static final String GROUP_ORDER_CODE = "group_order_l1";

    @Value("${oci-cfg.key-dir-path}")
    private String keyDirPath;

    public Page<Map<String, Object>> list(PageParams params) {
        Page<OciUser> page = new Page<>(params.getCurrent(), params.getSize());
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
        long duplicateCount = userMapper.selectCount(
                new LambdaQueryWrapper<OciUser>()
                        .eq(OciUser::getOciTenantId, params.getOciTenantId())
                        .eq(OciUser::getOciUserId, params.getOciUserId())
                        .eq(OciUser::getOciRegion, params.getOciRegion()));
        if (duplicateCount > 0) {
            throw new OciException("该租户配置已存在（相同 Tenant ID + User ID + Region），请勿重复添加");
        }

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
        userMapper.insert(user);
        log.info("Added tenant config: {}", params.getUsername());

        Thread.ofVirtual().start(() -> fetchTenantInfo(user));
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
        OciUser user = userMapper.selectById(params.getId());
        if (user == null) {
            throw new OciException("配置不存在");
        }
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
        userMapper.updateById(user);
        log.info("Updated tenant config: {}", params.getUsername());
    }

    public void remove(IdListParams params) {
        userMapper.deleteBatchIds(params.getIdList());
        log.info("Removed tenant configs: {}", params.getIdList());
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
                            .build())
                    .build();
            try (OciClientService client = new OciClientService(dto)) {
                // Fetch tenant name
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

                // Fetch plan type
                com.oracle.bmc.ospgateway.SubscriptionServiceClient ospClient =
                        com.oracle.bmc.ospgateway.SubscriptionServiceClient.builder().build(client.getProvider());
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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configName", user.getUsername());
        result.put("id", user.getId());

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
            var identityClient = client.getIdentityClient();

            try {
                var tenancy = identityClient.getTenancy(
                        com.oracle.bmc.identity.requests.GetTenancyRequest.builder()
                                .tenancyId(user.getOciTenantId()).build()).getTenancy();
                result.put("tenantName", tenancy.getName());
                result.put("homeRegionKey", tenancy.getHomeRegionKey());
                result.put("tenantId", tenancy.getId());
                result.put("description", tenancy.getDescription());
                result.put("upiIdcsCompatibilityLayerEndpoint", tenancy.getUpiIdcsCompatibilityLayerEndpoint());
            } catch (Exception e) {
                log.warn("Failed to get tenancy info: {}", e.getMessage());
            }

            try {
                var regions = identityClient.listRegionSubscriptions(
                        com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest.builder()
                                .tenancyId(user.getOciTenantId()).build()).getItems();
                List<String> regionNames = new ArrayList<>();
                for (var r : regions) {
                    regionNames.add(r.getRegionName());
                }
                result.put("subscribedRegions", regionNames);
            } catch (Exception e) {
                log.warn("Failed to get subscribed regions: {}", e.getMessage());
            }

            com.oracle.bmc.ospgateway.SubscriptionServiceClient ospClient = null;
            try {
                ospClient = com.oracle.bmc.ospgateway.SubscriptionServiceClient.builder().build(client.getProvider());
                var resp = ospClient.listSubscriptions(
                        com.oracle.bmc.ospgateway.requests.ListSubscriptionsRequest.builder()
                                .ospHomeRegion(user.getOciRegion())
                                .compartmentId(client.getCompartmentId()).build());
                var items = resp.getSubscriptionCollection().getItems();
                if (items != null && !items.isEmpty()) {
                    var sub = items.get(0);
                    result.put("planType", sub.getPlanType() != null ? sub.getPlanType().getValue() : null);
                    result.put("accountType", sub.getAccountType() != null ? sub.getAccountType().getValue() : null);
                    result.put("upgradeState", sub.getUpgradeState() != null ? sub.getUpgradeState().getValue() : null);
                    result.put("currencyCode", sub.getCurrencyCode());
                    result.put("isIntentToPay", sub.getIsIntentToPay());
                    result.put("subscriptionStartTime", sub.getTimeStart() != null ? sub.getTimeStart().toString() : null);
                    // 说明：OSP Gateway SubscriptionSummary(oci-sdk 3.83.0) 不提供 timeEnd/status 字段；
                    // 控制台“状态/续订日期”在不同账号形态下可能来自合同/订单体系，这里先返回 null 供前端降级展示。
                    result.put("subscriptionRenewTime", null);
                    result.put("subscriptionStatus", null);
                }
            } catch (Exception e) {
                log.warn("Failed to get subscription info: {}", e.getMessage());
            } finally {
                if (ospClient != null) ospClient.close();
            }

        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("获取租户详情失败: " + e.getMessage());
        }

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
        if (limitsRaw instanceof Map<?, ?> m) {
            Object inv = m.get("invoices");
            Object pay = m.get("payments");
            Object us = m.get("usageStatements");
            if (inv instanceof Number n) limits.put("invoices", Math.max(1, Math.min(50, n.intValue())));
            if (pay instanceof Number n) limits.put("payments", Math.max(1, Math.min(50, n.intValue())));
            if (us instanceof Number n) limits.put("usageStatements", Math.max(1, Math.min(50, n.intValue())));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("configName", user.getUsername());
        result.put("ociRegion", user.getOciRegion());

        Map<String, Object> links = new LinkedHashMap<>();
        links.put("billingOverview", "https://cloud.oracle.com/billing/overview?region=" + user.getOciRegion());
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

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("available", Boolean.FALSE);
        usage.put("reason", "暂未接入 Cost Analysis/Usage API，后续可扩展；当前请在控制台查看或导出报表");
        usage.put("summary", null);
        usage.put("statements", new ArrayList<>());
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
            com.oracle.bmc.ospgateway.InvoiceServiceClient invoiceClient =
                    com.oracle.bmc.ospgateway.InvoiceServiceClient.builder().build(client.getProvider());
            try {
                var resp = invoiceClient.listInvoices(
                        com.oracle.bmc.ospgateway.requests.ListInvoicesRequest.builder()
                                .ospHomeRegion(user.getOciRegion())
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
            com.oracle.bmc.ospgateway.InvoiceServiceClient invoiceClient =
                    com.oracle.bmc.ospgateway.InvoiceServiceClient.builder().build(client.getProvider());
            try {
                var resp = invoiceClient.downloadPdfContent(
                        com.oracle.bmc.ospgateway.requests.DownloadPdfContentRequest.builder()
                                .ospHomeRegion(user.getOciRegion())
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
        level2Map.forEach((k, v) -> l2.put(k, new ArrayList<>(v)));
        result.put("level2", l2);
        return result;
    }

    public void saveGroupOrder(List<String> order) {
        if (order == null || order.isEmpty()) return;
        String value = String.join(",", order);
        OciKv kv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, GROUP_ORDER_CODE));
        if (kv != null) {
            kv.setValue(value);
            kvMapper.updateById(kv);
        } else {
            kv = new OciKv();
            kv.setId(CommonUtils.generateId());
            kv.setCode(GROUP_ORDER_CODE);
            kv.setValue(value);
            kv.setType(GROUP_TYPE);
            kv.setCreateTime(LocalDateTime.now());
            kvMapper.insert(kv);
        }
        log.info("Saved group order: {}", value);
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
        } else if ("2".equals(level)) {
            List<OciKv> kvs = kvMapper.selectList(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, GROUP_TYPE).likeRight(OciKv::getCode, GROUP_L2_PREFIX)
                    .eq(OciKv::getValue, oldName));
            for (OciKv kv : kvs) {
                kv.setValue(newName);
                kvMapper.updateById(kv);
            }
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
            kvMapper.delete(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, GROUP_L1_PREFIX + name));
            kvMapper.delete(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, GROUP_TYPE).eq(OciKv::getCode, GROUP_L2_PREFIX + name));
        } else if ("2".equals(level)) {
            kvMapper.delete(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getType, GROUP_TYPE).likeRight(OciKv::getCode, GROUP_L2_PREFIX)
                    .eq(OciKv::getValue, name));
        }
        log.info("Deleted group [{}] {}", level, name);
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
