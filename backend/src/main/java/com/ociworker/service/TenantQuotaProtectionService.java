package com.ociworker.service;

import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.ociworker.util.OciBmcErrorTranslator;
import com.oracle.bmc.limits.LimitsClient;
import com.oracle.bmc.limits.QuotasClient;
import com.oracle.bmc.limits.model.CreateQuotaDetails;
import com.oracle.bmc.limits.model.Quota;
import com.oracle.bmc.limits.model.QuotaSummary;
import com.oracle.bmc.limits.model.UpdateQuotaDetails;
import com.oracle.bmc.limits.requests.CreateQuotaRequest;
import com.oracle.bmc.limits.requests.DeleteQuotaRequest;
import com.oracle.bmc.limits.requests.GetQuotaRequest;
import com.oracle.bmc.limits.requests.ListLimitValuesRequest;
import com.oracle.bmc.limits.requests.ListQuotasRequest;
import com.oracle.bmc.limits.requests.UpdateQuotaRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Service
@Slf4j
public class TenantQuotaProtectionService {
    private static final String POLICY_NAME = "OCIWorker-Free-Tier-Protection";
    private static final String MANAGED_TAG = "ociworker-managed";
    private static final String PROFILE_TAG = "ociworker-profile";
    private static final Duration ACCOUNT_LIMIT_CACHE_TTL = Duration.ofMinutes(10);

    private static final LinkedHashMap<String, ResourceRule> RULES = new LinkedHashMap<>();
    static {
        add(new ResourceRule("a1Ocpu", "Ampere A1 OCPU", "compute-core", "standard-a1-core-count", "OCPU", 0, 8, 1, 4, 4, true, true));
        add(new ResourceRule("a1Memory", "Ampere A1 内存", "compute-memory", "standard-a1-memory-count", "GB", 0, 48, 1, 24, 24, true, true));
        add(new ResourceRule("e2Micro", "E2.1.Micro 实例", "compute", "vm-standard-e2-1-micro-count", "台", 0, 4, 1, 2, 2, true, true));
        add(new ResourceRule("blockStorage", "引导卷与块卷总容量", "block-storage", "total-storage-gb", "GB", 0, 400, 25, 200, 200, true, true));
        add(new ResourceRule("volumeBackup", "卷备份数量", "block-storage", "backup-count", "个", 0, 10, 1, 5, 5, true, true));
        add(new ResourceRule("vcn", "VCN 数量", "vcn", "vcn-count", "个", 0, 5, 1, 2, 2, true, true));
        add(new ResourceRule("reservedPublicIp", "保留公网 IP", "vcn", "reserved-public-ip-count", "个", 0, 4, 1, 1, 0, true, true));
        add(new ResourceRule("microLoadBalancer", "免费微型负载均衡", "load-balancer", "lb-10mbps-micro-count", "个", 0, 2, 1, 1, 1, true, true));
        add(new ResourceRule("paidLoadBalancer10", "10Mbps 负载均衡", "load-balancer", "lb-10mbps-count", "个", 0, 2, 1, 0, 0, false, true));
        add(new ResourceRule("paidLoadBalancer100", "100Mbps 负载均衡", "load-balancer", "lb-100mbps-count", "个", 0, 2, 1, 0, 0, false, true));
        add(new ResourceRule("paidLoadBalancer400", "400Mbps 负载均衡", "load-balancer", "lb-400mbps-count", "个", 0, 2, 1, 0, 0, false, true));
        add(new ResourceRule("paidLoadBalancer8000", "8000Mbps 负载均衡", "load-balancer", "lb-8000mbps-count", "个", 0, 2, 1, 0, 0, false, true));
    }

    @Resource private OciUserMapper userMapper;
    @Resource private VerifyCodeService verifyCodeService;
    @Resource private RegionManagementService regionManagementService;
    @Resource private OciReadCacheService ociReadCacheService;

    public Map<String, Object> overview(String tenantConfigId) {
        OciUser user = requireUser(tenantConfigId);
        String region = resolveHomeRegion(tenantConfigId, user);
        try (QuotaClients clients = clients(user, region)) {
            List<QuotaSummary> policies = listPolicies(clients.quotas(), user.getOciTenantId());
            QuotaSummary managed = findManaged(policies);
            QuotaSummary nameConflict = findNameConflict(policies);
            Quota managedDetail = managed == null ? null : clients.quotas().getQuota(
                    GetQuotaRequest.builder().quotaId(managed.getId()).build()).getQuota();
            AccountLimits limits = readAccountLimitsCached(user, region, clients.limits());
            ParsedPolicy parsed = managedDetail == null
                    ? new ParsedPolicy(presetValues("BASIC"), true)
                    : parseManagedValues(managedDetail);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("enabled", managed != null);
            out.put("policy", managed == null ? null : policyView(managed));
            Map<String, String> managedTags = managed == null || managed.getFreeformTags() == null
                    ? Map.of() : managed.getFreeformTags();
            out.put("profile", managed == null ? "BASIC" : parsed.compatible()
                    ? managedTags.getOrDefault(PROFILE_TAG, "CUSTOM") : "CUSTOM");
            out.put("values", parsed.values());
            out.put("policyEditable", parsed.compatible());
            out.put("policyIssue", parsed.compatible() ? null : "当前策略包含无法识别的规则，为避免覆盖已禁止直接修改");
            out.put("nameConflict", nameConflict != null);
            out.put("resources", resourceViews(limits.values()));
            out.put("accountLimitsComplete", limits.complete());
            return out;
        } catch (Exception e) {
            throw quotaError("读取 Oracle 配额保护失败", e);
        }
    }

    public Map<String, Object> save(String tenantConfigId, Map<String, Object> input) {
        verifyCodeService.verifyCode("quotaProtectionSave", required(input, "verifyCode"), tenantConfigId);
        OciUser user = requireUser(tenantConfigId);
        String profile = normalizeProfile(input == null ? null : input.get("profile"));
        Map<String, Long> values = resolveValues(profile, input == null ? null : input.get("values"));
        List<String> statements = buildStatements(values);
        if (statements.isEmpty()) throw new OciException("请至少选择一项需要保护的资源");

        String region = resolveHomeRegion(tenantConfigId, user);
        try (QuotaClients clients = clients(user, region)) {
            List<QuotaSummary> policies = listPolicies(clients.quotas(), user.getOciTenantId());
            QuotaSummary managed = findManaged(policies);
            if (managed == null && findNameConflict(policies) != null) {
                throw new OciException("已存在同名的非 OCIWorker 配额策略，请先在 Oracle 控制台重命名该策略");
            }
            Map<String, String> tags = Map.of(MANAGED_TAG, "true", PROFILE_TAG, profile);
            if (managed == null) {
                clients.quotas().createQuota(CreateQuotaRequest.builder()
                        .opcRetryToken(UUID.randomUUID().toString())
                        .createQuotaDetails(CreateQuotaDetails.builder()
                                .compartmentId(user.getOciTenantId())
                                .name(POLICY_NAME)
                                .description("OCIWorker 免费资源配额保护")
                                .statements(statements)
                                .freeformTags(tags)
                                .build())
                        .build());
            } else {
                Quota current = clients.quotas().getQuota(GetQuotaRequest.builder().quotaId(managed.getId()).build()).getQuota();
                if (!parseManagedValues(current).compatible()) {
                    throw new OciException("当前配额策略包含无法识别的规则，为避免覆盖，请先在 Oracle 控制台处理后再修改");
                }
                clients.quotas().updateQuota(UpdateQuotaRequest.builder()
                        .quotaId(managed.getId())
                        .updateQuotaDetails(UpdateQuotaDetails.builder()
                                .description("OCIWorker 免费资源配额保护")
                                .statements(statements)
                                .freeformTags(tags)
                                .build())
                        .build());
            }
        } catch (Exception e) {
            throw quotaError("保存 Oracle 配额保护失败", e);
        }
        return overview(tenantConfigId);
    }

    public Map<String, Object> disable(String tenantConfigId, String verifyCode) {
        verifyCodeService.verifyCode("quotaProtectionDisable", verifyCode, tenantConfigId);
        OciUser user = requireUser(tenantConfigId);
        String region = resolveHomeRegion(tenantConfigId, user);
        try (QuotaClients clients = clients(user, region)) {
            QuotaSummary managed = findManaged(listPolicies(clients.quotas(), user.getOciTenantId()));
            if (managed != null) {
                clients.quotas().deleteQuota(DeleteQuotaRequest.builder().quotaId(managed.getId()).build());
            }
        } catch (Exception e) {
            throw quotaError("关闭 Oracle 配额保护失败", e);
        }
        return overview(tenantConfigId);
    }

    private AccountLimits readAccountLimits(LimitsClient client, String tenancyId) {
        Map<String, Long> found = new HashMap<>();
        boolean complete = true;
        for (String service : RULES.values().stream().map(ResourceRule::service).distinct().toList()) {
            try {
                String page = null;
                do {
                    var response = client.listLimitValues(ListLimitValuesRequest.builder()
                            .compartmentId(tenancyId).serviceName(service).page(page).limit(1000).build());
                    if (response.getItems() != null) {
                        for (var item : response.getItems()) {
                            if (item.getName() == null || item.getValue() == null) continue;
                            String key = service + "/" + item.getName();
                            found.merge(key, item.getValue().longValue(), Math::max);
                        }
                    }
                    page = response.getOpcNextPage();
                } while (page != null && !page.isBlank());
            } catch (Exception e) {
                complete = false;
                log.warn("读取配额服务失败: service={}", service, e);
            }
        }
        return new AccountLimits(found, complete);
    }

    private AccountLimits readAccountLimitsCached(OciUser user, String region, LimitsClient client) {
        String key = OciReadCacheService.key("oci:quotaProtection:limits", user.getId(), user.getOciTenantId(), region);
        return ociReadCacheService.get(key, ACCOUNT_LIMIT_CACHE_TTL, false,
                () -> readAccountLimits(client, user.getOciTenantId()));
    }

    private List<Map<String, Object>> resourceViews(Map<String, Long> accountLimits) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ResourceRule rule : RULES.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", rule.key()); row.put("label", rule.label()); row.put("unit", rule.unit());
            row.put("min", rule.min()); row.put("max", rule.max()); row.put("step", rule.step());
            row.put("basic", rule.basic()); row.put("strict", rule.strict());
            row.put("basicEnabled", rule.basicEnabled()); row.put("strictEnabled", rule.strictEnabled());
            row.put("accountLimit", accountLimits.get(rule.service() + "/" + rule.limitName()));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Long> resolveValues(String profile, Object rawValues) {
        Map<?, ?> submitted = rawValues instanceof Map<?, ?> map ? map : Map.of();
        Map<String, Long> values = new LinkedHashMap<>();
        for (ResourceRule rule : RULES.values()) {
            long value = "STRICT".equals(profile) ? rule.strict() : rule.basic();
            if ("BASIC".equals(profile) && !rule.basicEnabled()) continue;
            if ("STRICT".equals(profile) && !rule.strictEnabled()) continue;
            if ("CUSTOM".equals(profile)) {
                Object raw = submitted.get(rule.key());
                if (raw == null) continue;
                value = parseLong(raw, rule.label());
            }
            if (value < rule.min() || value > rule.max() || (value - rule.min()) % rule.step() != 0) {
                throw new OciException(rule.label() + "的数值不在允许范围内");
            }
            values.put(rule.key(), value);
        }
        return values;
    }

    private Map<String, Long> presetValues(String profile) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (ResourceRule rule : RULES.values()) {
            if ("STRICT".equals(profile) ? rule.strictEnabled() : rule.basicEnabled()) {
                values.put(rule.key(), "STRICT".equals(profile) ? rule.strict() : rule.basic());
            }
        }
        return values;
    }

    private ParsedPolicy parseManagedValues(Quota policy) {
        Map<String, Long> values = new LinkedHashMap<>();
        boolean compatible = true;
        if (policy.getStatements() != null) {
            for (String statement : policy.getStatements()) {
                if (statement == null || statement.isBlank()) continue;
                String normalized = statement.trim().replaceAll("\\s+", " ");
                boolean matched = false;
                for (ResourceRule rule : RULES.values()) {
                    String zero = "zero " + rule.service() + " quota " + rule.limitName() + " in tenancy";
                    String prefix = "set " + rule.service() + " quota " + rule.limitName() + " to ";
                    if (normalized.equalsIgnoreCase(zero)) {
                        values.put(rule.key(), 0L);
                        matched = true;
                        break;
                    } else if (normalized.regionMatches(true, 0, prefix, 0, prefix.length())) {
                        String tail = normalized.substring(prefix.length()).replaceFirst("(?i)\\s+in tenancy\\s*$", "").trim();
                        try {
                            values.put(rule.key(), Long.parseLong(tail));
                            matched = true;
                        } catch (NumberFormatException ignored) { }
                        break;
                    }
                }
                if (!matched) compatible = false;
            }
        }
        return new ParsedPolicy(values, compatible);
    }

    private List<String> buildStatements(Map<String, Long> values) {
        List<String> statements = new ArrayList<>();
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            ResourceRule rule = RULES.get(entry.getKey());
            if (rule == null) throw new OciException("包含不支持的配额项目");
            long value = entry.getValue();
            statements.add(value == 0
                    ? "zero " + rule.service() + " quota " + rule.limitName() + " in tenancy"
                    : "set " + rule.service() + " quota " + rule.limitName() + " to " + value + " in tenancy");
        }
        return statements;
    }

    private List<QuotaSummary> listPolicies(QuotasClient client, String tenancyId) {
        List<QuotaSummary> items = new ArrayList<>();
        String page = null;
        do {
            var response = client.listQuotas(ListQuotasRequest.builder()
                    .compartmentId(tenancyId).page(page).limit(1000).build());
            if (response.getItems() != null) items.addAll(response.getItems());
            page = response.getOpcNextPage();
        } while (page != null && !page.isBlank());
        return items;
    }

    private QuotaSummary findManaged(List<QuotaSummary> policies) {
        return policies.stream().filter(this::isActive).filter(policy ->
                policy.getFreeformTags() != null
                        && "true".equalsIgnoreCase(policy.getFreeformTags().get(MANAGED_TAG))).findFirst().orElse(null);
    }

    private QuotaSummary findNameConflict(List<QuotaSummary> policies) {
        return policies.stream().filter(this::isActive)
                .filter(policy -> POLICY_NAME.equals(policy.getName()))
                .filter(policy -> policy.getFreeformTags() == null
                        || !"true".equalsIgnoreCase(policy.getFreeformTags().get(MANAGED_TAG)))
                .findFirst().orElse(null);
    }

    private boolean isActive(QuotaSummary policy) {
        return policy != null && policy.getLifecycleState() != null
                && !"DELETED".equalsIgnoreCase(policy.getLifecycleState().getValue());
    }

    private Map<String, Object> policyView(QuotaSummary policy) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", policy.getId()); row.put("name", policy.getName());
        row.put("status", policy.getLifecycleState() == null ? null : policy.getLifecycleState().getValue());
        row.put("timeCreated", policy.getTimeCreated());
        return row;
    }

    private String resolveHomeRegion(String tenantConfigId, OciUser user) {
        try {
            Map<String, Object> data = regionManagementService.listRegions(tenantConfigId);
            Object home = data.get("homeRegionName");
            if (home != null && !String.valueOf(home).isBlank()) return String.valueOf(home).trim();
        } catch (Exception e) {
            log.warn("读取主区域失败，使用租户配置区域: tenantConfigId={}", tenantConfigId, e);
        }
        return user.getOciRegion();
    }

    private QuotaClients clients(OciUser user, String region) {
        OciClientService base = new OciClientService(buildDto(user), region);
        try {
            var quotasBuilder = QuotasClient.builder().configuration(base.getClientConfiguration());
            var limitsBuilder = LimitsClient.builder().configuration(base.getClientConfiguration());
            if (base.getOciClientConfigurator() != null) {
                quotasBuilder.clientConfigurator(base.getOciClientConfigurator());
                limitsBuilder.clientConfigurator(base.getOciClientConfigurator());
            }
            QuotasClient quotas = quotasBuilder.build(base.getProvider());
            LimitsClient limits = limitsBuilder.build(base.getProvider());
            if (region != null && !region.isBlank()) { quotas.setRegion(region); limits.setRegion(region); }
            return new QuotaClients(base, quotas, limits);
        } catch (Exception e) {
            base.close();
            throw e;
        }
    }

    private SysUserDTO buildDto(OciUser user) {
        return SysUserDTO.builder().username(user.getUsername()).ociCfg(SysUserDTO.OciCfg.builder()
                .tenantId(user.getOciTenantId()).userId(user.getOciUserId())
                .fingerprint(user.getOciFingerprint()).region(user.getOciRegion())
                .privateKeyPath(user.getOciKeyPath()).build()).build();
    }

    private OciUser requireUser(String id) {
        if (id == null || id.isBlank()) throw new OciException("缺少租户 id");
        OciUser user = userMapper.selectById(id);
        if (user == null) throw new OciException("租户配置不存在");
        return user;
    }

    private String normalizeProfile(Object raw) {
        String profile = raw == null ? "BASIC" : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if (!Set.of("BASIC", "STRICT", "CUSTOM").contains(profile)) throw new OciException("不支持的保护方案");
        return profile;
    }

    private String required(Map<String, Object> input, String key) {
        Object raw = input == null ? null : input.get(key);
        String value = raw == null ? null : String.valueOf(raw).trim();
        if (value == null || value.isBlank()) throw new OciException("请输入 Telegram 验证码");
        return value;
    }

    private long parseLong(Object raw, String label) {
        try { return Long.parseLong(String.valueOf(raw)); }
        catch (Exception e) { throw new OciException(label + "必须为整数"); }
    }

    private OciException quotaError(String prefix, Exception e) {
        if (e instanceof OciException oe) return oe;
        return new OciException(prefix + "：" + OciBmcErrorTranslator.translate(e));
    }

    private static void add(ResourceRule rule) { RULES.put(rule.key(), rule); }
    private record ResourceRule(String key, String label, String service, String limitName, String unit,
                                long min, long max, long step, long basic, long strict,
                                boolean basicEnabled, boolean strictEnabled) {}
    private record AccountLimits(Map<String, Long> values, boolean complete) {}
    private record ParsedPolicy(Map<String, Long> values, boolean compatible) {}

    private record QuotaClients(OciClientService base, QuotasClient quotas, LimitsClient limits) implements AutoCloseable {
        @Override public void close() {
            try { quotas.close(); } finally { try { limits.close(); } finally { base.close(); } }
        }
    }
}
