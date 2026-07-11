package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.mapper.OrganizationWorkTaskMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.entity.OrganizationWorkTask;
import com.ociworker.util.OciRegionCatalog;
import com.ociworker.util.OciBmcErrorTranslator;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.tenantmanagercontrolplane.OrganizationClient;
import com.oracle.bmc.tenantmanagercontrolplane.SenderInvitationClient;
import com.oracle.bmc.tenantmanagercontrolplane.SubscriptionClient;
import com.oracle.bmc.tenantmanagercontrolplane.WorkRequestClient;
import com.oracle.bmc.tenantmanagercontrolplane.model.*;
import com.oracle.bmc.tenantmanagercontrolplane.requests.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class OrganizationManagementService {
    private static final long TOKEN_TTL = 10 * 60 * 1000L;
    private record Grant(String tenantConfigId, String action, long expiresAt) {}
    private record IdentityRegions(String homeRegion, List<String> subscribedRegions) {}
    private static final Map<String, Grant> TOKENS = new ConcurrentHashMap<>();

    @Resource private OciUserMapper userMapper;
    @Resource private OrganizationWorkTaskMapper taskMapper;
    @Resource private VerifyCodeService verifyCodeService;

    public String unlock(String tenantConfigId, String action, String code) {
        if (!Set.of("createChildTenancy", "inviteOrganizationTenancy").contains(action)) throw new OciException("不支持的组织操作");
        verifyCodeService.verifyCode(action, code, tenantConfigId);
        long now = System.currentTimeMillis();
        TOKENS.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        String token = UUID.randomUUID().toString();
        TOKENS.put(token, new Grant(tenantConfigId, action, now + TOKEN_TTL));
        return token;
    }

    private Grant requireToken(String token, String tenantConfigId, String action) {
        Grant grant = TOKENS.get(token);
        if (grant == null || grant.expiresAt() < System.currentTimeMillis()
                || !Objects.equals(grant.tenantConfigId(), tenantConfigId) || !Objects.equals(grant.action(), action)) {
            if (token != null) TOKENS.remove(token);
            throw new OciException("操作授权已失效，请重新完成 TG 验证");
        }
        return grant;
    }

    private void consume(String token, Grant grant) {
        if (!TOKENS.remove(token, grant)) throw new OciException("操作授权已被使用，请重新完成 TG 验证");
    }

    public Map<String, Object> overview(String tenantConfigId) {
        try (OciClientService oci = buildClient(tenantConfigId);
             OrganizationClient org = organizationClient(oci)) {
            String tenancyId = oci.getProvider().getTenantId();
            Organization organization = firstOrganization(org, tenancyId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("organization", organization == null ? null : mapOrganization(organization));
            out.put("tenancies", organization == null ? List.of() : listTenancies(org, organization.getId()));
            out.put("tasks", listTasks(tenantConfigId));
            return out;
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw failure("读取组织信息失败", e); }
    }

    public Map<String, Object> createOptions(String tenantConfigId, String accessToken) {
        requireToken(accessToken, tenantConfigId, "createChildTenancy");
        try (OciClientService oci = buildClient(tenantConfigId);
             OrganizationClient org = organizationClient(oci);
             SubscriptionClient sub = subscriptionClient(oci)) {
            Organization organization = firstOrganization(org, oci.getProvider().getTenantId());
            if (organization == null) throw new OciException("当前租户未加入组织，无法创建子租户");
            String subscriptionId = organization.getDefaultUcmSubscriptionId();
            if (StrUtil.isBlank(subscriptionId)) throw new OciException("当前组织没有默认订阅，无法创建子租户");
            List<String> subscriptionRegions = safeListAvailableRegionNames(sub, subscriptionId, tenantConfigId);
            String configuredRegion = normalizeRegion(oci.getUser().getOciCfg().getRegion());
            IdentityRegions identityRegions = resolveIdentityRegions(oci, oci.getProvider().getTenantId(), tenantConfigId);
            String homeRegion = normalizeRegion(identityRegions.homeRegion());
            LinkedHashSet<String> regions = new LinkedHashSet<>();
            if (StrUtil.isNotBlank(homeRegion)) regions.add(homeRegion);
            identityRegions.subscribedRegions().stream().map(this::normalizeRegion).filter(StrUtil::isNotBlank).forEach(regions::add);
            subscriptionRegions.stream().map(this::normalizeRegion).filter(StrUtil::isNotBlank).forEach(regions::add);
            if (regions.isEmpty() && StrUtil.isNotBlank(configuredRegion)) {
                regions.add(configuredRegion);
                log.warn("组织区域接口均未返回数据，已回退租户配置 Region: tenantConfigId={}, configuredRegion={}",
                        tenantConfigId, configuredRegion);
            }
            if (regions.isEmpty()) throw new OciException("无法读取当前租户可用于创建子租户的区域");
            String defaultRegion = StrUtil.blankToDefault(homeRegion,
                    regions.contains(configuredRegion) ? configuredRegion : regions.iterator().next());
            log.info("已解析创建子租户区域: tenantConfigId={}, subscriptionRegionCount={}, homeRegion={}, optionCount={}",
                    tenantConfigId, subscriptionRegions.size(), homeRegion, regions.size());
            return Map.of("regions", List.copyOf(regions), "defaultRegion", defaultRegion);
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw failure("读取创建租户选项失败", e); }
    }

    public Map<String, Object> createChild(String tenantConfigId, Map<String, Object> input) {
        String token = required(input, "accessToken", "操作授权");
        Grant grant = requireToken(token, tenantConfigId, "createChildTenancy");
        String name = required(input, "tenancyName", "租户名称").toLowerCase(Locale.ROOT);
        if (!name.matches("^[a-z][a-z0-9]{0,29}$")) throw new OciException("租户名称必须以小写字母开头，仅包含小写字母和数字，最长 30 位");
        String region = required(input, "homeRegion", "主区域");
        String email = required(input, "adminEmail", "管理员电子邮件");
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new OciException("管理员电子邮件格式不正确");
        try (OciClientService oci = buildClient(tenantConfigId);
             OrganizationClient org = organizationClient(oci)) {
            String tenancyId = oci.getProvider().getTenantId();
            Organization organization = firstOrganization(org, tenancyId);
            if (organization == null || StrUtil.isBlank(organization.getDefaultUcmSubscriptionId())) throw new OciException("当前组织没有可用默认订阅");
            String subscriptionId = organization.getDefaultUcmSubscriptionId();
            consume(token, grant);
            var response = org.createChildTenancy(CreateChildTenancyRequest.builder()
                    .opcRetryToken(UUID.randomUUID().toString())
                    .createChildTenancyDetails(CreateChildTenancyDetails.builder()
                            .compartmentId(tenancyId).tenancyName(name).homeRegion(region).adminEmail(email)
                            .governanceStatus(GovernanceStatus.OptedOut).subscriptionId(subscriptionId).build())
                    .build());
            if (StrUtil.isBlank(response.getOpcWorkRequestId())) throw new OciException("Oracle 未返回创建租户任务 ID");
            boolean trackingSaved = saveTaskSafely(tenantConfigId, "CREATE_CHILD_TENANCY", name, null,
                    response.getOpcWorkRequestId(), response.getOpcRequestId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("workRequestId", response.getOpcWorkRequestId()); out.put("requestId", response.getOpcRequestId());
            out.put("trackingSaved", trackingSaved);
            return out;
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw failure("创建子租户失败", e); }
    }

    public Map<String, Object> invite(String tenantConfigId, Map<String, Object> input) {
        String token = required(input, "accessToken", "操作授权");
        Grant grant = requireToken(token, tenantConfigId, "inviteOrganizationTenancy");
        String displayName = required(input, "displayName", "邀请名称");
        String recipientId = required(input, "recipientTenancyId", "接收方租户 OCID");
        String email = required(input, "recipientEmailAddress", "接收方电子邮件");
        if (!recipientId.startsWith("ocid1.tenancy.")) throw new OciException("接收方租户 OCID 格式不正确");
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new OciException("接收方电子邮件格式不正确");
        try (OciClientService oci = buildClient(tenantConfigId);
             SenderInvitationClient client = senderInvitationClient(oci)) {
            String tenancyId = oci.getProvider().getTenantId();
            if (tenancyId.equals(recipientId)) throw new OciException("不能邀请当前租户自身");
            consume(token, grant);
            var response = client.createSenderInvitation(CreateSenderInvitationRequest.builder()
                    .opcRetryToken(UUID.randomUUID().toString())
                    .createSenderInvitationDetails(CreateSenderInvitationDetails.builder()
                            .compartmentId(tenancyId).displayName(displayName).recipientTenancyId(recipientId)
                            .recipientEmailAddress(email).subjects(List.of(InvitationSubject.Link)).build())
                    .build());
            if (StrUtil.isBlank(response.getOpcWorkRequestId())) throw new OciException("Oracle 未返回邀请任务 ID");
            String invitationId = response.getSenderInvitation() == null ? null : response.getSenderInvitation().getId();
            boolean trackingSaved = saveTaskSafely(tenantConfigId, "CREATE_SENDER_INVITATION", displayName,
                    invitationId, response.getOpcWorkRequestId(), response.getOpcRequestId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("invitationId", invitationId);
            out.put("workRequestId", response.getOpcWorkRequestId());
            out.put("requestId", response.getOpcRequestId());
            out.put("trackingSaved", trackingSaved);
            return out;
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw failure("邀请租户失败", e); }
    }

    public List<Map<String, Object>> refreshTasks(String tenantConfigId) {
        List<OrganizationWorkTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<OrganizationWorkTask>()
                .eq(OrganizationWorkTask::getTenantConfigId, tenantConfigId).orderByDesc(OrganizationWorkTask::getCreateTime).last("LIMIT 20"));
        if (tasks.isEmpty()) return List.of();
        try (OciClientService oci = buildClient(tenantConfigId); WorkRequestClient client = workRequestClient(oci)) {
            for (OrganizationWorkTask task : tasks) {
                if (Set.of("SUCCEEDED", "FAILED", "CANCELED").contains(task.getStatus())) continue;
                try {
                    var response = client.getWorkRequest(GetWorkRequestRequest.builder().workRequestId(task.getWorkRequestId()).build());
                    var work = response.getWorkRequest();
                    task.setStatus(work.getStatus() == null ? null : work.getStatus().getValue());
                    task.setPercentComplete(work.getPercentComplete()); task.setUpdateTime(new Date());
                    if (work.getResources() != null && !work.getResources().isEmpty()) task.setTargetId(work.getResources().get(0).getIdentifier());
                    if (work.getStatus() == OperationStatus.Failed) task.setErrorMessage(firstWorkError(client, task.getWorkRequestId()));
                    taskMapper.updateById(task);
                } catch (Exception e) {
                    log.warn("刷新组织 Work Request 失败: tenantConfigId={}, workRequestId={}",
                            tenantConfigId, task.getWorkRequestId(), e);
                }
            }
        }
        return listTasks(tenantConfigId);
    }

    private String firstWorkError(WorkRequestClient client, String id) {
        var response = client.listWorkRequestErrors(ListWorkRequestErrorsRequest.builder().workRequestId(id).limit(10).build());
        var items = response.getWorkRequestErrorCollection() == null ? null : response.getWorkRequestErrorCollection().getItems();
        return items == null || items.isEmpty() ? "Oracle 未返回失败详情" : items.get(0).getMessage();
    }

    private List<String> listAvailableRegionNames(SubscriptionClient client, String subscriptionId) {
        List<String> regions = new ArrayList<>(); String page = null;
        do { var response = client.listAvailableRegions(ListAvailableRegionsRequest.builder().subscriptionId(subscriptionId).page(page).build());
            if (response.getAvailableRegionCollection()!=null && response.getAvailableRegionCollection().getItems()!=null)
                for (var item:response.getAvailableRegionCollection().getItems()) if (StrUtil.isNotBlank(item.getRegionName())) regions.add(item.getRegionName());
            page=response.getOpcNextPage();
        } while (StrUtil.isNotBlank(page));
        return regions;
    }

    private List<String> safeListAvailableRegionNames(SubscriptionClient client, String subscriptionId, String tenantConfigId) {
        try {
            return listAvailableRegionNames(client, subscriptionId);
        } catch (Exception e) {
            log.warn("读取默认订阅可用区域失败，将继续尝试租户 Home Region: tenantConfigId={}, subscriptionId={}",
                    tenantConfigId, subscriptionId, e);
            return List.of();
        }
    }

    private String normalizeRegion(String region) {
        return StrUtil.isBlank(region) ? null : region.trim().toLowerCase(Locale.ROOT);
    }

    private IdentityRegions resolveIdentityRegions(OciClientService oci, String tenancyId, String tenantConfigId) {
        List<String> subscribedRegions = new ArrayList<>();
        String homeRegion = null;
        try {
            var identity = oci.getIdentityClient();
            var tenancy = identity.getTenancy(com.oracle.bmc.identity.requests.GetTenancyRequest.builder()
                    .tenancyId(tenancyId).build()).getTenancy();
            String homeRegionKey = tenancy == null ? null : tenancy.getHomeRegionKey();
            if (StrUtil.isBlank(homeRegionKey)) return null;
            var subscriptions = identity.listRegionSubscriptions(
                    com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest.builder()
                            .tenancyId(tenancyId).build()).getItems();
            if (subscriptions != null) {
                for (var subscription : subscriptions) {
                    if (StrUtil.isNotBlank(subscription.getRegionName())) subscribedRegions.add(subscription.getRegionName());
                    if (homeRegionKey.equalsIgnoreCase(subscription.getRegionKey())
                            && StrUtil.isNotBlank(subscription.getRegionName())) {
                        homeRegion = subscription.getRegionName();
                    }
                }
            }
            if (StrUtil.isNotBlank(homeRegion)) return new IdentityRegions(homeRegion, List.copyOf(subscribedRegions));
            log.warn("已读取租户 Home Region Key，但未找到对应 Region Name: tenantConfigId={}, homeRegionKey={}",
                    tenantConfigId, homeRegionKey);
        } catch (Exception e) {
            log.warn("读取租户真实 Home Region 失败: tenantConfigId={}", tenantConfigId, e);
        }
        return new IdentityRegions(homeRegion, List.copyOf(subscribedRegions));
    }

    private List<Map<String, Object>> listTasks(String tenantConfigId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OrganizationWorkTask t : taskMapper.selectList(new LambdaQueryWrapper<OrganizationWorkTask>().eq(OrganizationWorkTask::getTenantConfigId, tenantConfigId).orderByDesc(OrganizationWorkTask::getCreateTime).last("LIMIT 20"))) {
            Map<String,Object> m=new LinkedHashMap<>(); m.put("id",t.getId());m.put("operationType",t.getOperationType());m.put("targetName",t.getTargetName());m.put("targetId",t.getTargetId());m.put("workRequestId",t.getWorkRequestId());m.put("status",t.getStatus());m.put("percentComplete",t.getPercentComplete());m.put("errorMessage",t.getErrorMessage());m.put("createTime",t.getCreateTime());m.put("updateTime",t.getUpdateTime());out.add(m);
        } return out;
    }

    private boolean saveTaskSafely(String tenantConfigId, String type, String name, String targetId,
                                   String workRequestId, String requestId) {
        try {
            OrganizationWorkTask task = new OrganizationWorkTask();
            task.setId(UUID.randomUUID().toString());
            task.setTenantConfigId(tenantConfigId);
            task.setOperationType(type);
            task.setTargetName(name);
            task.setTargetId(targetId);
            task.setWorkRequestId(workRequestId);
            task.setRequestId(requestId);
            task.setStatus("ACCEPTED");
            task.setPercentComplete(0f);
            task.setCreateTime(new Date());
            task.setUpdateTime(new Date());
            taskMapper.insert(task);
            return true;
        } catch (Exception e) {
            log.error("Oracle 已受理组织操作，但本地任务保存失败: tenantConfigId={}, workRequestId={}",
                    tenantConfigId, workRequestId, e);
            return false;
        }
    }
    private Organization firstOrganization(OrganizationClient client,String tenancyId){var r=client.listOrganizations(ListOrganizationsRequest.builder().compartmentId(tenancyId).limit(100).build());var c=r.getOrganizationCollection();return c==null||c.getItems()==null||c.getItems().isEmpty()?null:client.getOrganization(GetOrganizationRequest.builder().organizationId(c.getItems().get(0).getId()).build()).getOrganization();}
    private List<Map<String, Object>> listTenancies(OrganizationClient client, String organizationId) {
        List<Map<String, Object>> out = new ArrayList<>();
        String page = null;
        do {
            var response = client.listOrganizationTenancies(ListOrganizationTenanciesRequest.builder()
                    .organizationId(organizationId).limit(100).page(page).build());
            if (response.getOrganizationTenancyCollection() != null
                    && response.getOrganizationTenancyCollection().getItems() != null) {
                for (var tenancy : response.getOrganizationTenancyCollection().getItems()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("tenancyId", tenancy.getTenancyId());
                    row.put("name", tenancy.getName());
                    row.put("role", tenancy.getRole() == null ? null : tenancy.getRole().getValue());
                    row.put("status", tenancy.getLifecycleState() == null ? null : tenancy.getLifecycleState().getValue());
                    row.put("timeJoined", tenancy.getTimeJoined());
                    out.add(row);
                }
            }
            page = response.getOpcNextPage();
        } while (StrUtil.isNotBlank(page));
        return out;
    }
    private Map<String,Object> mapOrganization(Organization o){Map<String,Object>m=new LinkedHashMap<>();m.put("id",o.getId());m.put("displayName",o.getDisplayName());m.put("status",o.getLifecycleState()==null?null:o.getLifecycleState().getValue());return m;}
    private String required(Map<String,Object>m,String k,String l){String v=m==null||m.get(k)==null?null:String.valueOf(m.get(k)).trim();if(StrUtil.isBlank(v))throw new OciException(l+"不能为空");return v;}
    private OciClientService buildClient(String id){OciUser u=userMapper.selectById(id);if(u==null)throw new OciException("租户配置不存在");return new OciClientService(SysUserDTO.builder().username(u.getUsername()).ociCfg(SysUserDTO.OciCfg.builder().tenantId(u.getOciTenantId()).userId(u.getOciUserId()).fingerprint(u.getOciFingerprint()).region(u.getOciRegion()).privateKeyPath(u.getOciKeyPath()).build()).build());}
    private <T> T region(T c,OciClientService o){try{String r=UsageCostService.resolveTenancyHomeRegionName(o.getIdentityClient(),o.getProvider().getTenantId(),o.getUser().getOciCfg().getRegion());c.getClass().getMethod("setRegion",com.oracle.bmc.Region.class).invoke(c,OciRegionCatalog.resolveRegion(r));}catch(Exception ignored){}return c;}
    private OrganizationClient organizationClient(OciClientService o){var b=OrganizationClient.builder().configuration(o.getClientConfiguration());b.additionalClientConfigurator(o.getOciClientConfigurator());return region(b.build(o.getProvider()),o);}
    private SubscriptionClient subscriptionClient(OciClientService o){var b=SubscriptionClient.builder().configuration(o.getClientConfiguration());b.additionalClientConfigurator(o.getOciClientConfigurator());return region(b.build(o.getProvider()),o);}
    private SenderInvitationClient senderInvitationClient(OciClientService o){var b=SenderInvitationClient.builder().configuration(o.getClientConfiguration());b.additionalClientConfigurator(o.getOciClientConfigurator());return region(b.build(o.getProvider()),o);}
    private WorkRequestClient workRequestClient(OciClientService o){var b=WorkRequestClient.builder().configuration(o.getClientConfiguration());b.additionalClientConfigurator(o.getOciClientConfigurator());return region(b.build(o.getProvider()),o);}
    private OciException failure(String label, Exception error) {
        log.warn(label, error);
        if (error instanceof BmcException bmc) {
            return new OciException(label + "：" + OciBmcErrorTranslator.translate(bmc));
        }
        return new OciException(label + "，请稍后重试");
    }
}
