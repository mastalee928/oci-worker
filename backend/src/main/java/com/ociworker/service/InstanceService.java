package com.ociworker.service;

import com.oracle.bmc.core.model.*;
import com.oracle.bmc.core.requests.*;
import com.oracle.bmc.identity.model.Compartment;
import com.ociworker.exception.OciException;
import com.ociworker.util.OciBmcErrorTranslator;
import com.ociworker.util.ShapeFlexLimitsUtil;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.ShapeEditTaskStatus;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.LinkedHashSet;

@Slf4j
@Service
public class InstanceService {

    private static final String SHAPE_A2_FLEX = "VM.Standard.A2.Flex";
    private static final String SHAPE_A1_FLEX = "VM.Standard.A1.Flex";
    private static final Duration SHAPE_LIST_CACHE_TTL = Duration.ofMinutes(15);
    private static final Duration INSTANCE_LIST_CACHE_TTL = Duration.ofSeconds(20);
    private static final Duration INSTANCE_DETAIL_CACHE_TTL = Duration.ofSeconds(45);

    @Resource
    private OciUserMapper userMapper;
    @Resource
    private NotificationService notificationService;
    @Resource
    private ShapeEditTaskManager shapeEditTaskManager;
    @Resource
    private OciReadCacheService ociReadCacheService;

    private String tag(OciUser u) { return "[" + u.getUsername() + "] "; }

    private OciClientService oci(OciUser ociUser, String region) {
        String r = (region == null || region.isBlank()) ? null : region.trim();
        return new OciClientService(buildBasicDTO(ociUser), r);
    }

    private String resolveInstanceCompartmentId(OciClientService client, String instanceId, String providedCompartmentId) {
        if (providedCompartmentId != null && !providedCompartmentId.isBlank()) {
            return providedCompartmentId.trim();
        }
        if (instanceId != null && !instanceId.isBlank()) {
            try {
                Instance instance = client.getComputeClient().getInstance(
                        GetInstanceRequest.builder().instanceId(instanceId).build()
                ).getInstance();
                if (instance != null && instance.getCompartmentId() != null && !instance.getCompartmentId().isBlank()) {
                    return instance.getCompartmentId();
                }
            } catch (Exception e) {
                log.debug("Failed to resolve instance compartment for {}: {}", instanceId, e.getMessage());
            }
        }
        return client.getCompartmentId();
    }

    private List<VnicAttachment> listVnicAttachmentsForInstance(
            OciClientService client, String instanceId, String compartmentId) {
        return client.getComputeClient().listVnicAttachments(
                ListVnicAttachmentsRequest.builder()
                        .compartmentId(resolveInstanceCompartmentId(client, instanceId, compartmentId))
                        .instanceId(instanceId)
                        .build()
        ).getItems();
    }

    public List<Map<String, Object>> listInstances(String userId, String region) {
        return listInstances(userId, region, false);
    }

    public List<Map<String, Object>> listInstances(String userId, String region, boolean force) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        return ociReadCacheService.get(instanceCacheKey("list", ociUser, region), INSTANCE_LIST_CACHE_TTL, force,
                () -> fetchInstances(ociUser, region));
    }

    private List<Map<String, Object>> fetchInstances(OciUser ociUser, String region) {
        try (OciClientService client = oci(ociUser, region)) {
            var compartments = client.listAllCompartments();
            Map<String, String> compartmentNameMap = new LinkedHashMap<>();
            for (var c : compartments) {
                compartmentNameMap.put(c.getId(), c.getName());
            }

            List<Instance> allInstances = new ArrayList<>();
            for (var compartment : compartments) {
                allInstances.addAll(client.listAllInstancesInCompartment(compartment.getId()));
            }

            if (allInstances.isEmpty()) {
                return new ArrayList<>();
            }

            // Fetch public IPs in parallel; OCI SDK clients are not thread-safe, so use separate clients per thread is ideal.
            // Here we reuse the read-only VirtualNetworkClient via getInstancePublicIp which is usually safe for list ops;
            // guarded by a bounded pool and always shut down in finally.
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(Math.max(allInstances.size(), 1), 8));
            Map<String, Future<String>> ipFutures = new LinkedHashMap<>();
            List<Map<String, Object>> result = new ArrayList<>();
            try {
                for (Instance inst : allInstances) {
                    ipFutures.put(inst.getId(), executor.submit(() -> {
                        try { return client.getInstancePublicIp(inst); }
                        catch (Exception e) { return null; }
                    }));
                }

                for (Instance inst : allInstances) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("instanceId", inst.getId());
                    map.put("name", inst.getDisplayName());
                    map.put("region", inst.getRegion());
                    map.put("shape", inst.getShape());
                    map.put("state", inst.getLifecycleState().getValue());
                    map.put("timeCreated", inst.getTimeCreated() != null ? inst.getTimeCreated().toString() : null);
                    map.put("availabilityDomain", inst.getAvailabilityDomain());
                    map.put("compartmentId", inst.getCompartmentId());
                    map.put("compartmentName", compartmentNameMap.getOrDefault(inst.getCompartmentId(), "unknown"));

                    if (inst.getShapeConfig() != null) {
                        map.put("ocpus", inst.getShapeConfig().getOcpus());
                        map.put("memoryInGBs", inst.getShapeConfig().getMemoryInGBs());
                    }

                    try {
                        map.put("publicIp", ipFutures.get(inst.getId()).get(15, TimeUnit.SECONDS));
                    } catch (Exception e) {
                        map.put("publicIp", null);
                    }
                    result.add(map);
                }
            } finally {
                executor.shutdownNow();
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to list instances: {}", e.getMessage());
            throw new OciException(tag(ociUser) + "获取实例列表失败: " + e.getMessage());
        }
    }

    public void updateInstanceState(String userId, String instanceId, String action, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            InstanceActionRequest request = InstanceActionRequest.builder()
                    .instanceId(instanceId)
                    .action(action)
                    .build();
            client.getComputeClient().instanceAction(request);
            log.info("Instance {} action: {}", instanceId, action);
            evictInstanceReadCaches(userId, region);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "操作失败: " + extractInstanceActionErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "操作失败: " + e.getMessage());
        }
    }

    public void terminateInstance(String userId, String instanceId, boolean preserveBootVolume, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            client.getComputeClient().terminateInstance(
                    TerminateInstanceRequest.builder()
                            .instanceId(instanceId)
                            .preserveBootVolume(preserveBootVolume)
                            .build());
            log.info("Instance terminated: {}, preserveBootVolume={}", instanceId, preserveBootVolume);
            evictInstanceReadCaches(userId, region);
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "终止实例失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listBootVolumesByInstance(String userId, String instanceId, String region) {
        return listBootVolumesByInstance(userId, instanceId, region, false);
    }

    public List<Map<String, Object>> listBootVolumesByInstance(String userId, String instanceId, String region, boolean force) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        return ociReadCacheService.get(instanceCacheKey("bootVolumes", ociUser, region, normalizeBlank(instanceId)),
                INSTANCE_DETAIL_CACHE_TTL, force, () -> fetchBootVolumesByInstance(ociUser, instanceId, region));
    }

    private List<Map<String, Object>> fetchBootVolumesByInstance(OciUser ociUser, String instanceId, String region) {
        try (OciClientService client = oci(ociUser, region)) {
            Instance instance = client.getComputeClient().getInstance(
                    GetInstanceRequest.builder().instanceId(instanceId).build()
            ).getInstance();

            BootVolumeAttachment rootAttachment = resolveRootBootVolumeAttachment(
                    listActiveBootVolumeAttachmentsForInstance(client, instance));
            if (rootAttachment == null || rootAttachment.getBootVolumeId() == null) {
                return List.of();
            }
            try {
                BootVolume vol = client.getBlockstorageClient().getBootVolume(
                        GetBootVolumeRequest.builder().bootVolumeId(rootAttachment.getBootVolumeId()).build()
                ).getBootVolume();
                Map<String, Object> row = bootVolumeRow(rootAttachment, vol);
                row.put("attachmentRole", "system");
                row.put("isRootBootVolume", true);
                return List.of(row);
            } catch (Exception e) {
                log.warn("Failed to get boot volume {}: {}", rootAttachment.getBootVolumeId(), e.getMessage());
                return List.of();
            }
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "获取引导卷列表失败: " + e.getMessage());
        }
    }

    public void updateBootVolume(String userId, String bootVolumeId, Long sizeInGBs, String displayName, Long vpusPerGB, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            UpdateBootVolumeDetails.Builder detailsBuilder = UpdateBootVolumeDetails.builder();
            if (sizeInGBs != null) detailsBuilder.sizeInGBs(sizeInGBs);
            if (displayName != null) detailsBuilder.displayName(displayName);
            if (vpusPerGB != null) detailsBuilder.vpusPerGB(vpusPerGB);

            client.getBlockstorageClient().updateBootVolume(
                    UpdateBootVolumeRequest.builder()
                            .bootVolumeId(bootVolumeId)
                            .updateBootVolumeDetails(detailsBuilder.build())
                            .build());
            log.info("Boot volume updated: {}", bootVolumeId);
            evictInstanceAndStorageReadCaches(userId, region);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "更新引导卷失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "更新引导卷失败: " + e.getMessage());
        }
    }

    /**
     * Gets detailed network info for an instance: private IP, public IP type (reserved/ephemeral), IPv6
     */
    public Map<String, Object> getInstanceNetworkDetail(String userId, String instanceId, String region, String compartmentId) {
        return getInstanceNetworkDetail(userId, instanceId, region, compartmentId, false);
    }

    public Map<String, Object> getInstanceNetworkDetail(String userId, String instanceId, String region, String compartmentId, boolean force) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        return ociReadCacheService.get(instanceCacheKey("networkDetail", ociUser, region,
                        normalizeBlank(instanceId), normalizeBlank(compartmentId)),
                INSTANCE_DETAIL_CACHE_TTL,
                force,
                () -> fetchInstanceNetworkDetail(ociUser, instanceId, region, compartmentId));
    }

    private Map<String, Object> fetchInstanceNetworkDetail(OciUser ociUser, String instanceId, String region, String compartmentId) {
        try (OciClientService client = oci(ociUser, region)) {
            Map<String, Object> result = new LinkedHashMap<>();

            List<VnicAttachment> attachments = listVnicAttachmentsForInstance(client, instanceId, compartmentId);
            Map<String, Subnet> subnetCache = new LinkedHashMap<>();
            Map<String, Vcn> vcnCache = new LinkedHashMap<>();
            Map<String, RouteTable> routeTableCache = new LinkedHashMap<>();
            String effectiveRegion = effectiveRegion(ociUser, region);

            List<Map<String, Object>> vnics = new ArrayList<>();
            for (VnicAttachment att : attachments) {
                try {
                    Vnic vnic = client.getVirtualNetworkClient().getVnic(
                            GetVnicRequest.builder().vnicId(att.getVnicId()).build()
                    ).getVnic();

                    Map<String, Object> vnicInfo = new LinkedHashMap<>();
                    vnicInfo.put("vnicId", vnic.getId());
                    vnicInfo.put("displayName", vnic.getDisplayName());
                    vnicInfo.put("isPrimary", vnic.getIsPrimary());
                    vnicInfo.put("availabilityDomain", vnic.getAvailabilityDomain());
                    vnicInfo.put("compartmentId", vnic.getCompartmentId());
                    vnicInfo.put("privateIp", vnic.getPrivateIp());
                    vnicInfo.put("publicIp", vnic.getPublicIp());
                    vnicInfo.put("hostnameLabel", vnic.getHostnameLabel());
                    vnicInfo.put("macAddress", vnic.getMacAddress());
                    vnicInfo.put("nsgIds", vnic.getNsgIds());
                    vnicInfo.put("vlanId", vnic.getVlanId());
                    vnicInfo.put("freeformTags", vnic.getFreeformTags());
                    vnicInfo.put("definedTags", vnic.getDefinedTags());
                    vnicInfo.put("skipSourceDestCheck", vnic.getSkipSourceDestCheck());
                    vnicInfo.put("lifecycleState", vnic.getLifecycleState() != null ? vnic.getLifecycleState().getValue() : null);
                    vnicInfo.put("timeCreated", vnic.getTimeCreated() != null ? vnic.getTimeCreated().toString() : null);
                    String subnetId = firstNonBlank(vnic.getSubnetId(), att.getSubnetId());
                    vnicInfo.put("subnetId", subnetId);
                    fillVnicNetworkResourceInfo(client, vnicInfo, subnetId, vnic.getRouteTableId(), effectiveRegion,
                            subnetCache, vcnCache, routeTableCache);

                    List<Ipv6> ipv6List = client.getVirtualNetworkClient().listIpv6s(
                            ListIpv6sRequest.builder().vnicId(vnic.getId()).build()
                    ).getItems();
                    // Keep old field for backward compatibility, and provide id for per-IPv6 unassign (delete).
                    vnicInfo.put("ipv6Addresses", ipv6List.stream().map(Ipv6::getIpAddress).toList());
                    List<Map<String, Object>> ipv6Details = new ArrayList<>();
                    for (Ipv6 ip6 : ipv6List) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("ipv6Id", ip6.getId());
                        m.put("ipAddress", ip6.getIpAddress());
                        ipv6Details.add(m);
                    }
                    vnicInfo.put("ipv6List", ipv6Details);

                    List<PrivateIp> privateIps = client.getVirtualNetworkClient().listPrivateIps(
                            ListPrivateIpsRequest.builder().vnicId(vnic.getId()).build()
                    ).getItems();

                    List<Map<String, Object>> ipDetails = new ArrayList<>();
                    for (PrivateIp pip : privateIps) {
                        Map<String, Object> ipInfo = new LinkedHashMap<>();
                        ipInfo.put("privateIpId", pip.getId());
                        ipInfo.put("privateIpAddress", pip.getIpAddress());
                        ipInfo.put("isPrimary", pip.getIsPrimary());
                        ipInfo.put("availabilityDomain", pip.getAvailabilityDomain());
                        ipInfo.put("compartmentId", pip.getCompartmentId());
                        ipInfo.put("displayName", pip.getDisplayName());
                        ipInfo.put("hostnameLabel", pip.getHostnameLabel());
                        ipInfo.put("cidrPrefixLength", pip.getCidrPrefixLength());
                        ipInfo.put("subnetId", pip.getSubnetId());
                        ipInfo.put("timeCreated", pip.getTimeCreated() != null ? pip.getTimeCreated().toString() : null);
                        ipInfo.put("vnicId", pip.getVnicId());
                        ipInfo.put("ipState", pip.getIpState() != null ? pip.getIpState().getValue() : null);
                        ipInfo.put("lifetime", pip.getLifetime() != null ? pip.getLifetime().getValue() : null);
                        ipInfo.put("routeTableId", pip.getRouteTableId());
                        ipInfo.put("ipv4SubnetCidrAtCreation", pip.getIpv4SubnetCidrAtCreation());
                        ipInfo.put("fqdn", buildInternalFqdn(pip.getHostnameLabel(), asString(vnicInfo.get("subnetDomainName"))));
                        fillRouteTableInfo(client, ipInfo, pip.getRouteTableId(), routeTableCache);

                        try {
                            PublicIp pubIp = client.getVirtualNetworkClient().getPublicIpByPrivateIpId(
                                    GetPublicIpByPrivateIpIdRequest.builder()
                                            .getPublicIpByPrivateIpIdDetails(
                                                    GetPublicIpByPrivateIpIdDetails.builder()
                                                            .privateIpId(pip.getId()).build())
                                            .build()
                            ).getPublicIp();
                            ipInfo.put("publicIpAddress", pubIp.getIpAddress());
                            ipInfo.put("publicIpId", pubIp.getId());
                            ipInfo.put("publicIpLifetime", pubIp.getLifetime() != null ? pubIp.getLifetime().getValue() : null);
                            ipInfo.put("publicIpDisplayName", pubIp.getDisplayName());
                            ipInfo.put("publicIpLifecycleState", pubIp.getLifecycleState() != null ? pubIp.getLifecycleState().getValue() : null);
                            ipInfo.put("publicIpScope", pubIp.getScope() != null ? pubIp.getScope().getValue() : null);
                            ipInfo.put("publicIpTimeCreated", pubIp.getTimeCreated() != null ? pubIp.getTimeCreated().toString() : null);
                            ipInfo.put("publicIpAssignedEntityId", pubIp.getAssignedEntityId());
                            ipInfo.put("publicIpAssignedEntityType", pubIp.getAssignedEntityType() != null ? pubIp.getAssignedEntityType().getValue() : null);
                            ipInfo.put("publicIpPoolId", pubIp.getPublicIpPoolId());
                        } catch (Exception ignored) {
                            ipInfo.put("publicIpAddress", null);
                            ipInfo.put("publicIpId", null);
                            ipInfo.put("publicIpLifetime", null);
                            ipInfo.put("publicIpDisplayName", null);
                            ipInfo.put("publicIpLifecycleState", null);
                            ipInfo.put("publicIpScope", null);
                            ipInfo.put("publicIpTimeCreated", null);
                            ipInfo.put("publicIpAssignedEntityId", null);
                            ipInfo.put("publicIpAssignedEntityType", null);
                            ipInfo.put("publicIpPoolId", null);
                        }
                        ipDetails.add(ipInfo);
                    }
                    vnicInfo.put("ipDetails", ipDetails);
                    vnics.add(vnicInfo);
                } catch (Exception e) {
                    log.warn("Failed to get VNIC detail: {}", e.getMessage());
                }
            }
            result.put("vnics", vnics);
            return result;
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "获取实例网络详情失败: " + e.getMessage());
        }
    }

    /**
     * Full IPv6 flow: ensure VCN has IPv6 CIDR → ensure subnet has IPv6 CIDR → create IPv6 on VNIC
     */
    public Map<String, String> addIpv6(String userId, String instanceId, String preferredVnicId, String region, String compartmentId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            List<VnicAttachment> attachments = listVnicAttachmentsForInstance(client, instanceId, compartmentId);
            if (attachments.isEmpty()) throw new OciException("未找到实例的 VNIC");

            VnicAttachment target = attachments.get(0);
            if (preferredVnicId != null && !preferredVnicId.isBlank()) {
                for (VnicAttachment att : attachments) {
                    if (preferredVnicId.equals(att.getVnicId())) {
                        target = att;
                        break;
                    }
                }
            }

            String vnicId = target.getVnicId();
            String subnetId = target.getSubnetId();

            Subnet subnet = client.getVirtualNetworkClient().getSubnet(
                    GetSubnetRequest.builder().subnetId(subnetId).build()
            ).getSubnet();

            Vcn vcn = client.getVirtualNetworkClient().getVcn(
                    GetVcnRequest.builder().vcnId(subnet.getVcnId()).build()
            ).getVcn();

            if (vcn.getIpv6CidrBlocks() == null || vcn.getIpv6CidrBlocks().isEmpty()) {
                log.info("VCN {} has no IPv6 CIDR, adding Oracle-assigned IPv6...", vcn.getDisplayName());
                try {
                    client.getVirtualNetworkClient().addIpv6VcnCidr(
                            AddIpv6VcnCidrRequest.builder()
                                    .vcnId(vcn.getId())
                                    .addVcnIpv6CidrDetails(AddVcnIpv6CidrDetails.builder()
                                            .isOracleGuaAllocationEnabled(true)
                                            .build())
                                    .build());
                    Thread.sleep(8000);
                    vcn = client.getVirtualNetworkClient().getVcn(
                            GetVcnRequest.builder().vcnId(vcn.getId()).build()
                    ).getVcn();
                } catch (com.oracle.bmc.model.BmcException e) {
                    String em = e.getMessage() == null ? "" : e.getMessage();
                    if (!em.contains("already exists") && !em.contains("already has")) {
                        throw new OciException(tag(ociUser) + "VCN 添加 IPv6 CIDR 失败: " + extractOciErrorMessage(e));
                    }
                    vcn = client.getVirtualNetworkClient().getVcn(
                            GetVcnRequest.builder().vcnId(vcn.getId()).build()
                    ).getVcn();
                }
            }

            if (subnet.getIpv6CidrBlocks() == null || subnet.getIpv6CidrBlocks().isEmpty()) {
                log.info("Subnet {} has no IPv6 CIDR, adding...", subnet.getDisplayName());
                String vcnIpv6Cidr = vcn.getIpv6CidrBlocks() != null && !vcn.getIpv6CidrBlocks().isEmpty()
                        ? vcn.getIpv6CidrBlocks().get(0) : null;
                if (vcnIpv6Cidr == null) {
                    throw new OciException("VCN 没有 IPv6 CIDR，无法为子网添加 IPv6。请先在OCI控制台手动为VCN启用IPv6。");
                }
                String subnetIpv6Cidr = vcnIpv6Cidr.replaceAll("/\\d+$", "/64");
                try {
                    client.getVirtualNetworkClient().updateSubnet(
                            UpdateSubnetRequest.builder()
                                    .subnetId(subnetId)
                                    .updateSubnetDetails(UpdateSubnetDetails.builder()
                                            .ipv6CidrBlocks(List.of(subnetIpv6Cidr))
                                            .build())
                                    .build());
                    Thread.sleep(3000);
                } catch (com.oracle.bmc.model.BmcException e) {
                    String em = e.getMessage() == null ? "" : e.getMessage();
                    if (!em.contains("already exists") && !em.contains("already has")) {
                        throw new OciException(tag(ociUser) + "子网添加 IPv6 CIDR 失败: " + extractOciErrorMessage(e));
                    }
                }
            }

            ensureIpv6InternetRoute(client, vcn, subnet);

            Ipv6 ipv6 = client.getVirtualNetworkClient().createIpv6(
                    CreateIpv6Request.builder()
                            .createIpv6Details(CreateIpv6Details.builder()
                                    .vnicId(vnicId)
                                    .build())
                            .build()
            ).getIpv6();

            evictInstanceReadCaches(userId, region);
            return Map.of("ipv6Address", ipv6.getIpAddress());
        } catch (OciException e) {
            throw e;
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "添加 IPv6 失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "添加 IPv6 失败: " + e.getMessage());
        }
    }

    /**
     * Unassign IPv6 from VNIC by deleting the IPv6 resource (does NOT modify VCN/Subnet route/security config).
     */
    public void removeIpv6(String userId, String ipv6Id, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        if (ipv6Id == null || ipv6Id.isBlank()) throw new OciException("ipv6Id 不能为空");

        try (OciClientService client = oci(ociUser, region)) {
            client.getVirtualNetworkClient().deleteIpv6(
                    DeleteIpv6Request.builder().ipv6Id(ipv6Id).build()
            );
            log.info("IPv6 unassigned (deleted): {}", ipv6Id);
            evictInstanceReadCaches(userId, region);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "取消分配 IPv6 失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "取消分配 IPv6 失败: " + e.getMessage());
        }
    }

    private void ensureIpv6InternetRoute(OciClientService client, Vcn vcn, Subnet subnet) {
        String networkCompartmentId = vcn.getCompartmentId() != null && !vcn.getCompartmentId().isBlank()
                ? vcn.getCompartmentId()
                : client.getCompartmentId();
        List<InternetGateway> igws = client.getVirtualNetworkClient().listInternetGateways(
                ListInternetGatewaysRequest.builder()
                        .compartmentId(networkCompartmentId)
                        .vcnId(vcn.getId())
                        .build()
        ).getItems();

        InternetGateway igw;
        if (igws == null || igws.isEmpty()) {
            log.info("VCN {} has no Internet Gateway, creating one...", vcn.getDisplayName());
            igw = client.getVirtualNetworkClient().createInternetGateway(
                    CreateInternetGatewayRequest.builder()
                            .createInternetGatewayDetails(CreateInternetGatewayDetails.builder()
                                    .compartmentId(networkCompartmentId)
                                    .vcnId(vcn.getId())
                                    .displayName("oci-worker-igw")
                                    .isEnabled(true)
                                    .build())
                            .build()
            ).getInternetGateway();
        } else {
            igw = igws.stream()
                    .filter(gw -> Boolean.TRUE.equals(gw.getIsEnabled()))
                    .findFirst()
                    .orElse(igws.get(0));
        }

        String routeTableId = subnet.getRouteTableId() != null ? subnet.getRouteTableId() : vcn.getDefaultRouteTableId();
        if (routeTableId == null) {
            log.warn("No route table found for subnet {}, skip IPv6 default route setup", subnet.getId());
            return;
        }

        RouteTable routeTable = client.getVirtualNetworkClient().getRouteTable(
                GetRouteTableRequest.builder().rtId(routeTableId).build()
        ).getRouteTable();

        List<RouteRule> rules = new ArrayList<>();
        if (routeTable.getRouteRules() != null) {
            rules.addAll(routeTable.getRouteRules());
        }

        boolean hasIpv6DefaultRoute = rules.stream().anyMatch(rule ->
                "::/0".equals(rule.getDestination())
                        && RouteRule.DestinationType.CidrBlock.equals(rule.getDestinationType())
        );

        if (!hasIpv6DefaultRoute) {
            rules.add(RouteRule.builder()
                    .destination("::/0")
                    .destinationType(RouteRule.DestinationType.CidrBlock)
                    .networkEntityId(igw.getId())
                    .description("oci-worker auto add IPv6 default route")
                    .build());

            client.getVirtualNetworkClient().updateRouteTable(
                    UpdateRouteTableRequest.builder()
                            .rtId(routeTableId)
                            .updateRouteTableDetails(UpdateRouteTableDetails.builder()
                                    .routeRules(rules)
                                    .build())
                            .build()
            );
            log.info("Added IPv6 default route (::/0 -> IGW) to route table {}", routeTableId);
        }
    }

    public Map<String, String> createReservedIp(String userId, String displayName, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            CreatePublicIpDetails.Builder builder = CreatePublicIpDetails.builder()
                    .compartmentId(client.getCompartmentId())
                    .lifetime(CreatePublicIpDetails.Lifetime.Reserved);
            if (displayName != null && !displayName.isBlank()) {
                builder.displayName(displayName);
            }

            PublicIp reservedIp = client.getVirtualNetworkClient().createPublicIp(
                    CreatePublicIpRequest.builder()
                            .createPublicIpDetails(builder.build())
                            .build()
            ).getPublicIp();

            evictInstanceReadCaches(userId, region);
            return Map.of(
                    "id", reservedIp.getId(),
                    "publicIpId", reservedIp.getId(),
                    "ipAddress", reservedIp.getIpAddress()
            );
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "创建预留IP失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "创建预留IP失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listReservedIps(String userId, String region) {
        return listReservedIps(userId, region, false);
    }

    public List<Map<String, Object>> listReservedIps(String userId, String region, boolean force) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        return ociReadCacheService.get(instanceCacheKey("reservedIps", ociUser, region),
                INSTANCE_DETAIL_CACHE_TTL, force, () -> fetchReservedIps(ociUser, region));
    }

    private List<Map<String, Object>> fetchReservedIps(OciUser ociUser, String region) {
        try (OciClientService client = oci(ociUser, region)) {
            List<PublicIp> publicIps = client.getVirtualNetworkClient().listPublicIps(
                    ListPublicIpsRequest.builder()
                            .compartmentId(client.getCompartmentId())
                            .scope(ListPublicIpsRequest.Scope.Region)
                            .lifetime(ListPublicIpsRequest.Lifetime.Reserved)
                            .build()
            ).getItems();

            return publicIps.stream().map(ip -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", ip.getId());
                map.put("ipAddress", ip.getIpAddress());
                map.put("displayName", ip.getDisplayName());
                map.put("lifecycleState", ip.getLifecycleState().getValue());
                map.put("lifetime", ip.getLifetime().getValue());
                map.put("assignedEntityId", ip.getAssignedEntityId());
                map.put("privateIpId", ip.getPrivateIpId());
                map.put("isAssigned", ip.getAssignedEntityId() != null);
                map.put("publicIpPoolId", ip.getPublicIpPoolId());
                map.put("timeCreated", ip.getTimeCreated() != null ? ip.getTimeCreated().toString() : null);
                return map;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "获取预留 IP 列表失败: " + e.getMessage());
        }
    }

    public void deleteReservedIp(String userId, String publicIpId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            client.getVirtualNetworkClient().deletePublicIp(
                    DeletePublicIpRequest.builder().publicIpId(publicIpId).build());
            log.info("Reserved IP deleted: {}", publicIpId);
            evictInstanceReadCaches(userId, region);
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "删除预留 IP 失败: " + e.getMessage());
        }
    }

    /**
     * Assigns a reserved IP to an instance by creating a secondary private IP on the VNIC,
     * then binding the reserved public IP to that secondary private IP.
     */
    public void assignReservedIp(String userId, String publicIpId, String instanceId, String region, String compartmentId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            List<VnicAttachment> attachments = listVnicAttachmentsForInstance(client, instanceId, compartmentId);
            if (attachments.isEmpty()) throw new OciException("未找到实例的 VNIC");

            String vnicId = attachments.get(0).getVnicId();
            String subnetId = attachments.get(0).getSubnetId();

            PrivateIp secondaryPip = client.getVirtualNetworkClient().createPrivateIp(
                    CreatePrivateIpRequest.builder()
                            .createPrivateIpDetails(CreatePrivateIpDetails.builder()
                                    .vnicId(vnicId)
                                    .displayName("privateip" + System.currentTimeMillis())
                                    .build())
                            .build()
            ).getPrivateIp();

            client.getVirtualNetworkClient().updatePublicIp(
                    UpdatePublicIpRequest.builder()
                            .publicIpId(publicIpId)
                            .updatePublicIpDetails(UpdatePublicIpDetails.builder()
                                    .privateIpId(secondaryPip.getId())
                                    .build())
                            .build());
            log.info("Reserved IP {} assigned to secondary private IP {} on instance {}",
                    publicIpId, secondaryPip.getIpAddress(), instanceId);
            evictInstanceReadCaches(userId, region);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "绑定预留IP失败: " + extractOciErrorMessage(e));
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "绑定预留IP失败: " + e.getMessage());
        }
    }

    public void unassignReservedIp(String userId, String publicIpId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            PublicIp pubIp = client.getVirtualNetworkClient().getPublicIp(
                    GetPublicIpRequest.builder().publicIpId(publicIpId).build()
            ).getPublicIp();

            String privateIpId = pubIp.getPrivateIpId();

            client.getVirtualNetworkClient().updatePublicIp(
                    UpdatePublicIpRequest.builder()
                            .publicIpId(publicIpId)
                            .updatePublicIpDetails(UpdatePublicIpDetails.builder()
                                    .privateIpId("")
                                    .build())
                            .build());

            if (privateIpId != null) {
                try {
                    PrivateIp pip = client.getVirtualNetworkClient().getPrivateIp(
                            GetPrivateIpRequest.builder().privateIpId(privateIpId).build()
                    ).getPrivateIp();
                    if (!Boolean.TRUE.equals(pip.getIsPrimary())) {
                        client.getVirtualNetworkClient().deletePrivateIp(
                                DeletePrivateIpRequest.builder().privateIpId(privateIpId).build());
                    }
                } catch (Exception ignored) {}
            }
            log.info("Reserved IP {} unassigned", publicIpId);
            evictInstanceReadCaches(userId, region);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "解绑预留IP失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "解绑预留IP失败: " + e.getMessage());
        }
    }

    public Object updateInstance(String userId, String instanceId,
                                 String displayName, String shape,
                                 Float ocpus, Float memoryInGBs, String region) {
        try {
            return updateInstanceOnce(userId, instanceId, displayName, shape, ocpus, memoryInGBs, region);
        } catch (OciException e) {
            throw e;
        } catch (com.oracle.bmc.model.BmcException e) {
            if (isShapeEditRequest(shape, ocpus, memoryInGBs) && ShapeEditTaskManager.isOutOfStock(e)) {
                ShapeEditTaskStatus status = shapeEditTaskManager.startTask(
                        userId,
                        instanceId,
                        region,
                        shape,
                        ocpus,
                        memoryInGBs,
                        () -> updateInstanceOnce(userId, instanceId, displayName, shape, ocpus, memoryInGBs, region));
                return status;
            }
            OciUser ociUser = userMapper.selectById(userId);
            String prefix = ociUser == null ? "" : tag(ociUser);
            throw new OciException(prefix + "修改实例失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            OciUser ociUser = userMapper.selectById(userId);
            String prefix = ociUser == null ? "" : tag(ociUser);
            throw new OciException(prefix + "修改实例失败: " + e.getMessage());
        }
    }

    private void fillVnicNetworkResourceInfo(
            OciClientService client,
            Map<String, Object> vnicInfo,
            String subnetId,
            String vnicRouteTableId,
            String region,
            Map<String, Subnet> subnetCache,
            Map<String, Vcn> vcnCache,
            Map<String, RouteTable> routeTableCache) {
        if (subnetId == null || subnetId.isBlank()) {
            return;
        }
        try {
            Subnet subnet = subnetCache.computeIfAbsent(subnetId, id -> client.getVirtualNetworkClient().getSubnet(
                    GetSubnetRequest.builder().subnetId(id).build()
            ).getSubnet());
            if (subnet == null) {
                return;
            }

            Map<String, Object> subnetInfo = subnetToMap(subnet);
            vnicInfo.put("subnet", subnetInfo);
            vnicInfo.put("subnetDisplayName", subnet.getDisplayName());
            vnicInfo.put("subnetCidrBlock", subnet.getCidrBlock());
            vnicInfo.put("subnetLifecycleState", subnet.getLifecycleState() != null ? subnet.getLifecycleState().getValue() : null);
            vnicInfo.put("subnetDomainName", subnet.getSubnetDomainName());
            vnicInfo.put("internalFqdn", buildInternalFqdn(asString(vnicInfo.get("hostnameLabel")), subnet.getSubnetDomainName()));

            String vcnId = subnet.getVcnId();
            vnicInfo.put("vcnId", vcnId);
            if (vcnId != null && !vcnId.isBlank()) {
                Vcn vcn = vcnCache.computeIfAbsent(vcnId, id -> client.getVirtualNetworkClient().getVcn(
                        GetVcnRequest.builder().vcnId(id).build()
                ).getVcn());
                if (vcn != null) {
                    Map<String, Object> vcnInfo = vcnToMap(vcn, region);
                    vnicInfo.put("vcn", vcnInfo);
                    vnicInfo.put("vcnDisplayName", vcn.getDisplayName());
                    vnicInfo.put("vcnCidrBlock", vcn.getCidrBlock());
                    vnicInfo.put("vcnLifecycleState", vcn.getLifecycleState() != null ? vcn.getLifecycleState().getValue() : null);
                }
            }

            String routeTableId = firstNonBlank(vnicRouteTableId, subnet.getRouteTableId());
            vnicInfo.put("routeTableId", routeTableId);
            if (routeTableId == null || routeTableId.isBlank()) {
                return;
            }
            RouteTable routeTable = routeTableCache.computeIfAbsent(routeTableId, id -> client.getVirtualNetworkClient().getRouteTable(
                    GetRouteTableRequest.builder().rtId(id).build()
            ).getRouteTable());
            if (routeTable != null) {
                Map<String, Object> routeTableInfo = routeTableToMap(routeTable);
                vnicInfo.put("routeTable", routeTableInfo);
                vnicInfo.put("routeTableDisplayName", routeTable.getDisplayName());
                vnicInfo.put("routeTableLifecycleState", routeTable.getLifecycleState() != null ? routeTable.getLifecycleState().getValue() : null);
            }
        } catch (Exception e) {
            log.debug("Failed to load VCN/Subnet detail for subnet {}: {}", subnetId, e.getMessage());
        }
    }

    private static Map<String, Object> vcnToMap(Vcn vcn, String region) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", vcn.getId());
        map.put("displayName", vcn.getDisplayName());
        map.put("cidrBlock", vcn.getCidrBlock());
        map.put("cidrBlocks", vcn.getCidrBlocks());
        map.put("ipv6CidrBlocks", vcn.getIpv6CidrBlocks());
        map.put("dnsLabel", vcn.getDnsLabel());
        map.put("vcnDomainName", vcn.getVcnDomainName());
        map.put("lifecycleState", vcn.getLifecycleState() != null ? vcn.getLifecycleState().getValue() : null);
        map.put("compartmentId", vcn.getCompartmentId());
        map.put("timeCreated", vcn.getTimeCreated() != null ? vcn.getTimeCreated().toString() : null);
        map.put("region", region);
        return map;
    }

    private static Map<String, Object> subnetToMap(Subnet subnet) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", subnet.getId());
        map.put("displayName", subnet.getDisplayName());
        map.put("cidrBlock", subnet.getCidrBlock());
        map.put("ipv6CidrBlock", subnet.getIpv6CidrBlock());
        map.put("availabilityDomain", subnet.getAvailabilityDomain());
        map.put("prohibitPublicIpOnVnic", subnet.getProhibitPublicIpOnVnic());
        map.put("virtualRouterIp", subnet.getVirtualRouterIp());
        map.put("virtualRouterMac", subnet.getVirtualRouterMac());
        map.put("routeTableId", subnet.getRouteTableId());
        map.put("dhcpOptionsId", subnet.getDhcpOptionsId());
        map.put("securityListIds", subnet.getSecurityListIds());
        map.put("subnetDomainName", subnet.getSubnetDomainName());
        map.put("freeformTags", subnet.getFreeformTags());
        map.put("definedTags", subnet.getDefinedTags());
        map.put("lifecycleState", subnet.getLifecycleState() != null ? subnet.getLifecycleState().getValue() : null);
        map.put("timeCreated", subnet.getTimeCreated() != null ? subnet.getTimeCreated().toString() : null);
        map.put("vcnId", subnet.getVcnId());
        return map;
    }

    private static Map<String, Object> routeTableToMap(RouteTable routeTable) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", routeTable.getId());
        map.put("displayName", routeTable.getDisplayName());
        map.put("vcnId", routeTable.getVcnId());
        map.put("compartmentId", routeTable.getCompartmentId());
        map.put("lifecycleState", routeTable.getLifecycleState() != null ? routeTable.getLifecycleState().getValue() : null);
        map.put("routeRulesCount", routeTable.getRouteRules() != null ? routeTable.getRouteRules().size() : 0);
        map.put("freeformTags", routeTable.getFreeformTags());
        map.put("definedTags", routeTable.getDefinedTags());
        map.put("timeCreated", routeTable.getTimeCreated() != null ? routeTable.getTimeCreated().toString() : null);
        return map;
    }

    private static void fillRouteTableInfo(
            OciClientService client,
            Map<String, Object> target,
            String routeTableId,
            Map<String, RouteTable> routeTableCache) {
        if (routeTableId == null || routeTableId.isBlank()) {
            return;
        }
        try {
            RouteTable routeTable = routeTableCache.computeIfAbsent(routeTableId, id -> client.getVirtualNetworkClient().getRouteTable(
                    GetRouteTableRequest.builder().rtId(id).build()
            ).getRouteTable());
            if (routeTable == null) {
                return;
            }
            target.put("routeTable", routeTableToMap(routeTable));
            target.put("routeTableDisplayName", routeTable.getDisplayName());
            target.put("routeTableLifecycleState", routeTable.getLifecycleState() != null ? routeTable.getLifecycleState().getValue() : null);
        } catch (Exception ignored) {
            // Private IP route table is supplemental display data; keep network detail usable when it cannot be read.
        }
    }

    private static String buildInternalFqdn(String hostnameLabel, String subnetDomainName) {
        if (hostnameLabel == null || hostnameLabel.isBlank() || subnetDomainName == null || subnetDomainName.isBlank()) {
            return null;
        }
        return hostnameLabel.trim() + "." + subnetDomainName.trim();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> updateInstanceOnce(String userId, String instanceId,
                                                   String displayName, String shape,
                                                   Float ocpus, Float memoryInGBs, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            Map<String, Object> result = updateInstanceOnce(client, ociUser, instanceId, displayName, shape, ocpus, memoryInGBs);
            evictInstanceReadCaches(userId, region);
            return result;
        }
    }

    private Map<String, Object> updateInstanceOnce(OciClientService client, OciUser ociUser, String instanceId,
                                                   String displayName, String shape,
                                                   Float ocpus, Float memoryInGBs) {
        Instance current = client.getComputeClient().getInstance(
                GetInstanceRequest.builder().instanceId(instanceId).build()
        ).getInstance();

        String targetShape = shape != null && !shape.isBlank() ? shape.trim() : current.getShape();
        List<Shape> compatible = client.getShapes(current.getAvailabilityDomain(), current.getImageId());
        Shape shapeMeta = findShapeMeta(compatible, targetShape);
        if (shapeMeta == null) {
            throw new OciException(tag(ociUser) + "目标 Shape 与当前实例镜像不兼容: " + targetShape);
        }

        boolean flex = isFlexibleShape(targetShape);
        Float useOcpus = ocpus;
        Float useMemory = memoryInGBs;
        if (flex) {
            if (useOcpus == null && current.getShapeConfig() != null) {
                useOcpus = current.getShapeConfig().getOcpus();
            }
            if (useMemory == null && current.getShapeConfig() != null) {
                useMemory = current.getShapeConfig().getMemoryInGBs();
            }
            validateFlexResources(shapeMeta, useOcpus, useMemory);
        } else if (ocpus != null || memoryInGBs != null) {
            throw new OciException(tag(ociUser) + "非 Flex Shape 仅可更换形状，不能单独调整 OCPU/内存");
        }

        UpdateInstanceDetails.Builder detailsBuilder = UpdateInstanceDetails.builder();

        if (displayName != null && !displayName.isBlank()) {
            detailsBuilder.displayName(displayName);
        }

        if (shape != null && !shape.isBlank() && !shape.trim().equals(current.getShape())) {
            detailsBuilder.shape(shape.trim());
        }

        if (flex && (useOcpus != null || useMemory != null)) {
            UpdateInstanceShapeConfigDetails.Builder shapeBuilder =
                    UpdateInstanceShapeConfigDetails.builder();
            if (useOcpus != null) shapeBuilder.ocpus(useOcpus);
            if (useMemory != null) shapeBuilder.memoryInGBs(useMemory);
            detailsBuilder.shapeConfig(shapeBuilder.build());
        }

        Instance updated = client.getComputeClient().updateInstance(
                UpdateInstanceRequest.builder()
                        .instanceId(instanceId)
                        .updateInstanceDetails(detailsBuilder.build())
                        .build()
        ).getInstance();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", updated.getId());
        result.put("name", updated.getDisplayName());
        result.put("shape", updated.getShape());
        if (updated.getShapeConfig() != null) {
            result.put("ocpus", updated.getShapeConfig().getOcpus());
            result.put("memoryInGBs", updated.getShapeConfig().getMemoryInGBs());
        }
        return result;
    }

    private static boolean isShapeEditRequest(String shape, Float ocpus, Float memoryInGBs) {
        return (shape != null && !shape.isBlank()) || ocpus != null || memoryInGBs != null;
    }

    /**
     * 特殊区域 A2→A1：不校验 ListShapes 是否列出 A1，直接 UpdateInstance（与 CLI 强改一致）。
     * 仅当 OCI 返回的当前 Shape 为 VM.Standard.A2.Flex 时执行。
     */
    public Map<String, Object> forceA2FlexToA1Flex(String userId, String instanceId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            Instance current = client.getComputeClient().getInstance(
                    GetInstanceRequest.builder().instanceId(instanceId).build()
            ).getInstance();

            String actualShape = current.getShape();
            if (!SHAPE_A2_FLEX.equals(actualShape)) {
                throw new OciException(tag(ociUser) + "当前实例 Shape 不是 "
                        + SHAPE_A2_FLEX + "，无法执行强改。请检查当前 Shape，实际为：" + actualShape);
            }

            Float ocpus = null;
            Float memoryInGBs = null;
            if (current.getShapeConfig() != null) {
                ocpus = current.getShapeConfig().getOcpus();
                memoryInGBs = current.getShapeConfig().getMemoryInGBs();
            }
            if (ocpus == null || memoryInGBs == null) {
                throw new OciException(tag(ociUser) + "无法读取当前 Flex 的 OCPU/内存配置，请检查后重试");
            }

            List<Shape> compatible = client.getShapes(current.getAvailabilityDomain(), current.getImageId());
            Shape a1Meta = findShapeMeta(compatible, SHAPE_A1_FLEX);
            if (a1Meta != null) {
                validateFlexResources(a1Meta, ocpus, memoryInGBs);
            }

            log.warn("{} force A2→A1 instanceId={} ocpus={} memoryInGBs={}",
                    tag(ociUser), instanceId, ocpus, memoryInGBs);

            Instance updated = client.getComputeClient().updateInstance(
                    UpdateInstanceRequest.builder()
                            .instanceId(instanceId)
                            .updateInstanceDetails(UpdateInstanceDetails.builder()
                                    .shape(SHAPE_A1_FLEX)
                                    .shapeConfig(UpdateInstanceShapeConfigDetails.builder()
                                            .ocpus(ocpus)
                                            .memoryInGBs(memoryInGBs)
                                            .build())
                                    .build())
                            .build()
            ).getInstance();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", updated.getId());
            result.put("name", updated.getDisplayName());
            result.put("shape", updated.getShape());
            if (updated.getShapeConfig() != null) {
                result.put("ocpus", updated.getShapeConfig().getOcpus());
                result.put("memoryInGBs", updated.getShapeConfig().getMemoryInGBs());
            }
            notifyForceA2ToA1Success(updated);
            evictInstanceReadCaches(userId, region);
            return result;
        } catch (OciException e) {
            notifyForceA2ToA1Failure(ociUser, region);
            throw e;
        } catch (com.oracle.bmc.model.BmcException e) {
            notifyForceA2ToA1Failure(ociUser, region);
            throw new OciException(tag(ociUser) + "A2 强改 A1 失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            notifyForceA2ToA1Failure(ociUser, region);
            throw new OciException(tag(ociUser) + "A2 强改 A1 失败: " + e.getMessage());
        }
    }

    private void notifyForceA2ToA1Success(Instance updated) {
        String nowShape = updated.getShape() != null ? updated.getShape() : SHAPE_A1_FLEX;
        String html = "🎉 <b>实例形状修改成功！</b>\n\n"
                + "原Shape：<code>" + SHAPE_A2_FLEX + "</code>\n"
                + "现Shape：<code>" + nowShape + "</code>\n"
                + "公网IP以及密码无变化\n"
                + "已成功实现A2➡A1";
        notificationService.sendHtmlWithType(NotificationService.TYPE_INSTANCE, html);
    }

    private void notifyForceA2ToA1Failure(OciUser ociUser, String region) {
        String username = ociUser.getUsername() != null ? ociUser.getUsername() : "-";
        String reg = (region != null && !region.isBlank()) ? region.trim() : "-";
        String html = "😟 <b>实例形状修改失败！</b>\n\n"
                + "租户：" + username + "\n"
                + "区域：" + reg + "\n"
                + "A2➡A1修改失败，可再次尝试";
        notificationService.sendHtmlWithType(NotificationService.TYPE_INSTANCE, html);
    }

    public List<Map<String, Object>> listAvailableShapes(String userId, String region) {
        return listAvailableShapes(userId, region, false);
    }

    public List<Map<String, Object>> listAvailableShapes(String userId, String region, boolean force) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        String targetRegion = normalizeRegionForCache(region);
        String cacheKey = OciReadCacheService.key(
                "oci:availableShapes",
                ociUser.getId(),
                ociUser.getOciTenantId(),
                ociUser.getOciRegion(),
                targetRegion);
        return ociReadCacheService.get(cacheKey, SHAPE_LIST_CACHE_TTL, force, () -> fetchAvailableShapes(ociUser, region));
    }

    private List<Map<String, Object>> fetchAvailableShapes(OciUser ociUser, String region) {
        try (OciClientService client = oci(ociUser, region)) {
            var ads = client.getAvailabilityDomains();
            Set<String> seen = new LinkedHashSet<>();
            List<Map<String, Object>> result = new ArrayList<>();
            for (var ad : ads) {
                for (var s : client.getShapes(ad.getName())) {
                    if (seen.add(s.getShape())) {
                        result.add(shapeToMap(s));
                    }
                }
            }
            return result;
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "获取可用 Shape 列表失败: " + e.getMessage());
        }
    }

    /** 与当前实例镜像、可用域兼容的 Shape（用于形状编辑） */
    public List<Map<String, Object>> listShapesForInstance(String userId, String instanceId, String region) {
        return listShapesForInstance(userId, instanceId, region, false);
    }

    public List<Map<String, Object>> listShapesForInstance(String userId, String instanceId, String region, boolean force) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        String targetRegion = normalizeRegionForCache(region);
        String cacheKey = OciReadCacheService.key(
                "oci:instanceShapes",
                ociUser.getId(),
                ociUser.getOciTenantId(),
                ociUser.getOciRegion(),
                targetRegion,
                instanceId);
        return ociReadCacheService.get(cacheKey, SHAPE_LIST_CACHE_TTL, force, () -> fetchShapesForInstance(ociUser, instanceId, region));
    }

    private List<Map<String, Object>> fetchShapesForInstance(OciUser ociUser, String instanceId, String region) {
        try (OciClientService client = oci(ociUser, region)) {
            Instance inst = client.getComputeClient().getInstance(
                    GetInstanceRequest.builder().instanceId(instanceId).build()
            ).getInstance();
            Set<String> seen = new LinkedHashSet<>();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Shape s : client.getShapes(inst.getAvailabilityDomain(), inst.getImageId())) {
                if (seen.add(s.getShape())) {
                    result.add(shapeToMap(s));
                }
            }
            result.sort(Comparator.comparing(m -> String.valueOf(m.get("shape"))));
            return result;
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "获取实例可用 Shape 失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "获取实例可用 Shape 失败: " + e.getMessage());
        }
    }

    private static String normalizeRegionForCache(String region) {
        return region == null ? "" : region.trim();
    }

    private void evictInstanceReadCaches(String userId, String region) {
        OciUser user = userMapper.selectById(userId);
        if (user == null) {
            return;
        }
        ociReadCacheService.evictByPrefix(instanceCachePrefix(user, region) + "|");
    }

    private void evictInstanceAndStorageReadCaches(String userId, String region) {
        OciUser user = userMapper.selectById(userId);
        if (user == null) {
            return;
        }
        ociReadCacheService.evictByPrefix(instanceCachePrefix(user, region) + "|");
        ociReadCacheService.evictByPrefix(StorageService.storageCachePrefix(user, region) + "|");
    }

    private static String instanceCacheKey(String type, OciUser user, String region, Object... parts) {
        Object[] all = new Object[(parts == null ? 0 : parts.length) + 1];
        all[0] = type;
        if (parts != null && parts.length > 0) {
            System.arraycopy(parts, 0, all, 1, parts.length);
        }
        return OciReadCacheService.key(instanceCachePrefix(user, region), all);
    }

    static String instanceCachePrefix(OciUser user, String region) {
        return OciReadCacheService.key(
                "oci:instance",
                user.getId(),
                user.getOciTenantId(),
                configuredRegion(user),
                effectiveRegion(user, region));
    }

    private static String configuredRegion(OciUser user) {
        return user.getOciRegion() == null ? "" : user.getOciRegion().trim();
    }

    private static String effectiveRegion(OciUser user, String region) {
        String r = normalizeBlank(region);
        return r.isEmpty() ? configuredRegion(user) : r;
    }

    private static String normalizeBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second != null && !second.isBlank() ? second.trim() : null;
    }

    private static boolean isFlexibleShape(String shapeName) {
        return shapeName != null && shapeName.contains("Flex");
    }

    private static Shape findShapeMeta(List<Shape> shapes, String shapeName) {
        if (shapeName == null) return null;
        for (Shape s : shapes) {
            if (shapeName.equals(s.getShape())) return s;
        }
        return null;
    }

    private static Map<String, Object> shapeToMap(Shape shape) {
        Map<String, Object> map = new LinkedHashMap<>();
        String name = shape.getShape();
        map.put("shape", name);
        map.put("processorDescription", shape.getProcessorDescription());
        boolean flex = isFlexibleShape(name);
        map.put("isFlexible", flex);

        Float ocpuMin = null;
        Float ocpuMax = null;
        if (shape.getOcpuOptions() != null) {
            ocpuMin = shape.getOcpuOptions().getMin();
            ocpuMax = shape.getOcpuOptions().getMax();
        }
        if (ocpuMin == null && shape.getOcpus() != null) {
            ocpuMin = shape.getOcpus();
            ocpuMax = shape.getOcpus();
        }
        map.put("ocpuMin", ocpuMin);
        map.put("ocpuMax", ocpuMax);
        map.put("ocpus", shape.getOcpus());

        Float memMin = null;
        Float memMax = null;
        if (shape.getMemoryOptions() != null) {
            memMin = shape.getMemoryOptions().getMinInGBs();
            memMax = shape.getMemoryOptions().getMaxInGBs();
        }
        if (memMin == null && shape.getMemoryInGBs() != null) {
            memMin = shape.getMemoryInGBs();
            memMax = shape.getMemoryInGBs();
        }
        map.put("memoryMinInGBs", memMin);
        map.put("memoryMaxInGBs", memMax);
        map.put("memoryInGBs", shape.getMemoryInGBs());
        applyFlexLimitsOverride(map, name);
        return map;
    }

    private static void applyFlexLimitsOverride(Map<String, Object> map, String shapeName) {
        ShapeFlexLimitsUtil.FlexLimits lim = ShapeFlexLimitsUtil.forShape(shapeName);
        if (lim == null || !Boolean.TRUE.equals(map.get("isFlexible"))) {
            return;
        }
        map.put("ocpuMax", lim.maxOcpus());
        map.put("memoryMaxInGBs", lim.maxMemoryGb());
        if (map.get("ocpuMin") == null) {
            map.put("ocpuMin", 1f);
        }
        if (map.get("memoryMinInGBs") == null) {
            map.put("memoryMinInGBs", lim.defaultMemoryGb());
        }
    }

    private static void validateFlexResources(Shape shapeMeta, Float ocpus, Float memoryInGBs) {
        if (ocpus == null || memoryInGBs == null) {
            throw new OciException("Flex Shape 须同时指定 OCPU 与内存 (GB)");
        }
        Float oMin = shapeMeta.getOcpuOptions() != null ? shapeMeta.getOcpuOptions().getMin() : shapeMeta.getOcpus();
        Float oMax = shapeMeta.getOcpuOptions() != null ? shapeMeta.getOcpuOptions().getMax() : shapeMeta.getOcpus();
        Float mMin = shapeMeta.getMemoryOptions() != null ? shapeMeta.getMemoryOptions().getMinInGBs() : shapeMeta.getMemoryInGBs();
        Float mMax = shapeMeta.getMemoryOptions() != null ? shapeMeta.getMemoryOptions().getMaxInGBs() : shapeMeta.getMemoryInGBs();
        ShapeFlexLimitsUtil.FlexLimits fixed = ShapeFlexLimitsUtil.forShape(shapeMeta.getShape());
        if (fixed != null) {
            oMax = fixed.maxOcpus();
            mMax = fixed.maxMemoryGb();
            if (oMin == null) {
                oMin = 1f;
            }
            if (mMin == null) {
                mMin = fixed.defaultMemoryGb();
            }
        }
        if (oMin != null && ocpus < oMin) {
            throw new OciException(String.format("OCPU 不能小于 %s（该 Shape 下限）", trimFloat(oMin)));
        }
        if (oMax != null && ocpus > oMax) {
            throw new OciException(String.format("OCPU 不能大于 %s（该 Shape 上限）", trimFloat(oMax)));
        }
        if (mMin != null && memoryInGBs < mMin) {
            throw new OciException(String.format("内存不能小于 %s GB（该 Shape 下限）", trimFloat(mMin)));
        }
        if (mMax != null && memoryInGBs > mMax) {
            throw new OciException(String.format("内存不能大于 %s GB（该 Shape 上限）", trimFloat(mMax)));
        }
    }

    private static String trimFloat(Float v) {
        if (v == null) return "";
        if (v == Math.floor(v)) return String.valueOf(v.intValue());
        return String.valueOf(v);
    }

    /**
     * 当前实例已挂载的普通块存储卷。引导卷在「引导卷」和「外部引导卷」里单独展示。
     */
    public List<Map<String, Object>> listBlockVolumesByInstance(String userId, String instanceId, String region) {
        return listBlockVolumesByInstance(userId, instanceId, region, false);
    }

    public List<Map<String, Object>> listBlockVolumesByInstance(String userId, String instanceId, String region, boolean force) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        if (instanceId == null || instanceId.isBlank()) throw new OciException("instanceId 不能为空");

        return ociReadCacheService.get(instanceCacheKey("blockVolumes", ociUser, region, normalizeBlank(instanceId)),
                INSTANCE_DETAIL_CACHE_TTL, force, () -> fetchBlockVolumesByInstance(ociUser, instanceId, region));
    }

    private List<Map<String, Object>> fetchBlockVolumesByInstance(OciUser ociUser, String instanceId, String region) {
        try (OciClientService client = oci(ociUser, region)) {
            Instance instance = getInstanceOrThrow(client, instanceId);
            String compartmentId = instance.getCompartmentId();
            String ad = instance.getAvailabilityDomain();

            List<Map<String, Object>> result = new ArrayList<>();

            List<VolumeAttachment> attachments = listActiveVolumeAttachments(client, compartmentId, ad, instanceId);
            for (VolumeAttachment att : attachments) {
                String volumeId = att.getVolumeId();
                if (volumeId == null) continue;
                try {
                    Volume vol = client.getBlockstorageClient().getVolume(
                            GetVolumeRequest.builder().volumeId(volumeId).build()
                    ).getVolume();
                    result.add(blockVolumeRow(att, vol));
                } catch (Exception e) {
                    log.warn("Failed to get block volume {}: {}", volumeId, e.getMessage());
                }
            }
            return result;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "获取块存储卷列表失败: " + e.getMessage());
        }
    }

    /**
     * 当前实例块存储页的外部引导卷：
     * 1. 已挂载到当前实例、但不是系统盘的引导卷；
     * 2. 同 AD、AVAILABLE、未挂载的引导卷（可作为救援盘挂到当前实例）。
     */
    public List<Map<String, Object>> listExternalBootVolumesForInstance(String userId, String instanceId, String region) {
        return listExternalBootVolumesForInstance(userId, instanceId, region, false);
    }

    public List<Map<String, Object>> listExternalBootVolumesForInstance(String userId, String instanceId, String region, boolean force) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        if (instanceId == null || instanceId.isBlank()) throw new OciException("instanceId 不能为空");

        return ociReadCacheService.get(instanceCacheKey("externalBootVolumes", ociUser, region, normalizeBlank(instanceId)),
                INSTANCE_DETAIL_CACHE_TTL, force, () -> fetchExternalBootVolumesForInstance(ociUser, instanceId, region));
    }

    private List<Map<String, Object>> fetchExternalBootVolumesForInstance(OciUser ociUser, String instanceId, String region) {
        try (OciClientService client = oci(ociUser, region)) {
            Instance instance = getInstanceOrThrow(client, instanceId);
            String ad = instance.getAvailabilityDomain();
            List<BootVolumeAttachment> currentAttachments = listActiveBootVolumeAttachmentsForInstance(client, instance);
            BootVolumeAttachment rootAttachment = resolveRootBootVolumeAttachment(currentAttachments);
            String rootBootVolumeId = rootAttachment != null ? rootAttachment.getBootVolumeId() : null;

            List<Map<String, Object>> result = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (BootVolumeAttachment att : currentAttachments) {
                String bootVolumeId = att.getBootVolumeId();
                if (bootVolumeId == null || Objects.equals(bootVolumeId, rootBootVolumeId) || !seen.add(bootVolumeId)) {
                    continue;
                }
                try {
                    BootVolume vol = client.getBlockstorageClient().getBootVolume(
                            GetBootVolumeRequest.builder().bootVolumeId(bootVolumeId).build()
                    ).getBootVolume();
                    result.add(externalBootVolumeRow(att, vol, instance));
                } catch (Exception e) {
                    log.warn("Failed to get attached external boot volume {}: {}", bootVolumeId, e.getMessage());
                }
            }

            Set<String> attachedBootVolumeIds = new HashSet<>();
            Set<String> compartmentIds = searchableCompartmentIds(client, instance.getCompartmentId());
            for (String compartmentId : compartmentIds) {
                try {
                    for (BootVolumeAttachment att : listActiveBootVolumeAttachments(client, compartmentId, ad, null)) {
                        if (att.getBootVolumeId() != null) {
                            attachedBootVolumeIds.add(att.getBootVolumeId());
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to list boot volume attachments in compartment {}: {}", compartmentId, e.getMessage());
                }
            }

            for (String compartmentId : compartmentIds) {
                String page = null;
                do {
                    try {
                        var resp = client.getBlockstorageClient().listBootVolumes(
                                ListBootVolumesRequest.builder()
                                        .compartmentId(compartmentId)
                                        .availabilityDomain(ad)
                                        .page(page)
                                        .build());
                        for (BootVolume vol : resp.getItems()) {
                            if (vol.getLifecycleState() != BootVolume.LifecycleState.Available) {
                                continue;
                            }
                            String bootVolumeId = vol.getId();
                            if (bootVolumeId == null
                                    || Objects.equals(bootVolumeId, rootBootVolumeId)
                                    || attachedBootVolumeIds.contains(bootVolumeId)
                                    || !seen.add(bootVolumeId)) {
                                continue;
                            }
                            result.add(externalBootVolumeRow(null, vol, null));
                        }
                        page = resp.getOpcNextPage();
                    } catch (Exception e) {
                        log.debug("Failed to list available boot volumes in compartment {}: {}", compartmentId, e.getMessage());
                        page = null;
                    }
                } while (page != null);
            }

            result.sort(Comparator
                    .comparing((Map<String, Object> m) -> Boolean.TRUE.equals(m.get("attached")) ? 0 : 1)
                    .thenComparing(m -> Objects.toString(m.get("displayName"), "")));
            return result;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "获取外部引导卷失败: " + e.getMessage());
        }
    }

    /**
     * 同 AD、同区间、AVAILABLE 且未挂载到他处的块存储卷（供 AttachVolume 选择）。
     */
    public List<Map<String, Object>> listUnattachedBlockVolumesForInstance(String userId, String instanceId, String region) {
        return listUnattachedBlockVolumesForInstance(userId, instanceId, region, false);
    }

    public List<Map<String, Object>> listUnattachedBlockVolumesForInstance(String userId, String instanceId, String region, boolean force) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        if (instanceId == null || instanceId.isBlank()) throw new OciException("instanceId 不能为空");

        return ociReadCacheService.get(instanceCacheKey("unattachedBlockVolumes", ociUser, region, normalizeBlank(instanceId)),
                INSTANCE_DETAIL_CACHE_TTL, force, () -> fetchUnattachedBlockVolumesForInstance(ociUser, instanceId, region));
    }

    private List<Map<String, Object>> fetchUnattachedBlockVolumesForInstance(OciUser ociUser, String instanceId, String region) {
        try (OciClientService client = oci(ociUser, region)) {
            Instance instance = getInstanceOrThrow(client, instanceId);
            String compartmentId = instance.getCompartmentId();
            String ad = instance.getAvailabilityDomain();

            Set<String> attachedVolumeIds = new HashSet<>();
            for (VolumeAttachment att : listActiveVolumeAttachments(client, compartmentId, ad, null)) {
                if (att.getVolumeId() != null) {
                    attachedVolumeIds.add(att.getVolumeId());
                }
            }

            List<Map<String, Object>> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String page = null;
            do {
                var resp = client.getBlockstorageClient().listVolumes(
                        ListVolumesRequest.builder()
                                .compartmentId(compartmentId)
                                .availabilityDomain(ad)
                                .lifecycleState(Volume.LifecycleState.Available)
                                .page(page)
                                .build());
                for (Volume v : resp.getItems()) {
                    if (!seen.add(v.getId())) continue;
                    if (attachedVolumeIds.contains(v.getId())) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", v.getId());
                    m.put("displayName", v.getDisplayName());
                    m.put("sizeInGBs", v.getSizeInGBs());
                    m.put("vpusPerGB", v.getVpusPerGB());
                    m.put("lifecycleState", v.getLifecycleState() != null ? v.getLifecycleState().getValue() : null);
                    m.put("availabilityDomain", v.getAvailabilityDomain());
                    result.add(m);
                }
                page = resp.getOpcNextPage();
            } while (page != null);
            return result;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "获取可挂载块存储卷失败: " + e.getMessage());
        }
    }

    /**
     * CreateVolume（Block Storage）后 AttachVolume（Compute），参数遵循 CreateVolumeDetails / AttachVolumeDetails。
     */
    public Map<String, Object> createBlockVolumeAndAttach(String userId, String instanceId, String displayName,
                                                         Long sizeInGBs, Long vpusPerGB, String device,
                                                         String attachmentType, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        validateBlockVolumeSize(sizeInGBs);
        long vpus = resolveVpusPerGb(vpusPerGB);

        try (OciClientService client = oci(ociUser, region)) {
            Instance instance = getInstanceOrThrow(client, instanceId);
            String compartmentId = instance.getCompartmentId();
            String ad = instance.getAvailabilityDomain();

            var createDetails = CreateVolumeDetails.builder()
                    .compartmentId(compartmentId)
                    .availabilityDomain(ad)
                    .displayName(displayName != null && !displayName.isBlank() ? displayName.trim() : "block-volume")
                    .sizeInGBs(sizeInGBs)
                    .vpusPerGB(vpus)
                    .build();

            Volume created = client.getBlockstorageClient().createVolume(
                    CreateVolumeRequest.builder().createVolumeDetails(createDetails).build()
            ).getVolume();

            Volume available = waitVolumeUntilAvailable(client, created.getId());
            VolumeAttachment attachment = attachVolumeToInstance(client, instanceId, available.getId(), device, attachmentType);

            Map<String, Object> out = blockVolumeRow(attachment, available);
            out.put("message", "块存储卷已创建并提交挂载");
            evictInstanceAndStorageReadCaches(userId, region);
            return out;
        } catch (OciException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OciException(tag(ociUser) + "创建并挂载块存储卷被中断");
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "创建并挂载块存储卷失败: " + e.getMessage());
        }
    }

    public Map<String, Object> attachBlockVolume(String userId, String instanceId, String volumeId,
                                                 String device, String attachmentType, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        if (volumeId == null || volumeId.isBlank()) throw new OciException("volumeId 不能为空");

        try (OciClientService client = oci(ociUser, region)) {
            Instance instance = getInstanceOrThrow(client, instanceId);
            Volume vol = client.getBlockstorageClient().getVolume(
                    GetVolumeRequest.builder().volumeId(volumeId).build()
            ).getVolume();

            if (!Objects.equals(vol.getAvailabilityDomain(), instance.getAvailabilityDomain())) {
                throw new OciException("块存储卷与实例须在同一可用域 (Availability Domain)");
            }
            if (!Objects.equals(vol.getCompartmentId(), instance.getCompartmentId())) {
                throw new OciException("块存储卷与实例须在同一区间 (Compartment)");
            }
            if (vol.getLifecycleState() != Volume.LifecycleState.Available) {
                throw new OciException("块存储卷须为 AVAILABLE 状态方可挂载，当前: "
                        + (vol.getLifecycleState() != null ? vol.getLifecycleState().getValue() : "unknown"));
            }

            VolumeAttachment attachment = attachVolumeToInstance(client, instanceId, volumeId, device, attachmentType);
            Volume refreshed = client.getBlockstorageClient().getVolume(
                    GetVolumeRequest.builder().volumeId(volumeId).build()
            ).getVolume();
            evictInstanceAndStorageReadCaches(userId, region);
            return blockVolumeRow(attachment, refreshed);
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "挂载块存储卷失败: " + e.getMessage());
        }
    }

    public Map<String, Object> attachExternalBootVolume(String userId, String instanceId, String bootVolumeId,
                                                       String attachmentType, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        if (instanceId == null || instanceId.isBlank()) throw new OciException("instanceId 不能为空");
        if (bootVolumeId == null || bootVolumeId.isBlank()) throw new OciException("bootVolumeId 不能为空");

        try (OciClientService client = oci(ociUser, region)) {
            Instance instance = getInstanceOrThrow(client, instanceId);
            BootVolume bootVolume = client.getBlockstorageClient().getBootVolume(
                    GetBootVolumeRequest.builder().bootVolumeId(bootVolumeId).build()
            ).getBootVolume();
            if (!Objects.equals(bootVolume.getAvailabilityDomain(), instance.getAvailabilityDomain())) {
                throw new OciException("引导卷与实例须在同一可用域 (Availability Domain)");
            }
            if (bootVolume.getLifecycleState() != BootVolume.LifecycleState.Available) {
                throw new OciException("引导卷须为 AVAILABLE 状态方可挂载，当前: "
                        + (bootVolume.getLifecycleState() != null ? bootVolume.getLifecycleState().getValue() : "unknown"));
            }

            if (attachmentType != null && !attachmentType.isBlank()) {
                log.debug("OCI Java SDK AttachBootVolumeDetails does not expose attachmentType; using API default");
            }
            BootVolumeAttachment attachment = attachBootVolumeToInstance(client, instanceId, bootVolumeId);
            Map<String, Object> row = externalBootVolumeRow(attachment, bootVolume, instance);
            row.put("message", "已提交挂载外部引导卷");
            evictInstanceAndStorageReadCaches(userId, region);
            return row;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "挂载外部引导卷失败: " + e.getMessage());
        }
    }

    public void detachBlockVolume(String userId, String volumeAttachmentId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        if (volumeAttachmentId == null || volumeAttachmentId.isBlank()) {
            throw new OciException("volumeAttachmentId 不能为空");
        }

        try (OciClientService client = oci(ociUser, region)) {
            client.getComputeClient().detachVolume(
                    DetachVolumeRequest.builder()
                            .volumeAttachmentId(volumeAttachmentId)
                            .build());
            log.info("Block volume detached: attachment {}", volumeAttachmentId);
            evictInstanceAndStorageReadCaches(userId, region);
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "卸载块存储卷失败: " + e.getMessage());
        }
    }

    public void detachExternalBootVolume(String userId, String instanceId, String bootVolumeAttachmentId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        if (instanceId == null || instanceId.isBlank()) throw new OciException("instanceId 不能为空");
        if (bootVolumeAttachmentId == null || bootVolumeAttachmentId.isBlank()) {
            throw new OciException("bootVolumeAttachmentId 不能为空");
        }

        try (OciClientService client = oci(ociUser, region)) {
            Instance instance = getInstanceOrThrow(client, instanceId);
            if (instance.getLifecycleState() != Instance.LifecycleState.Stopped) {
                throw new OciException("分离引导卷前必须先停止实例，当前实例状态: "
                        + (instance.getLifecycleState() != null ? instance.getLifecycleState().getValue() : "unknown"));
            }
            BootVolumeAttachment attachment = client.getComputeClient().getBootVolumeAttachment(
                    GetBootVolumeAttachmentRequest.builder()
                            .bootVolumeAttachmentId(bootVolumeAttachmentId)
                            .build()
            ).getBootVolumeAttachment();
            if (!Objects.equals(instanceId, attachment.getInstanceId())) {
                throw new OciException("该引导卷未挂载在当前实例，不能在此处分离");
            }
            BootVolumeAttachment rootAttachment = resolveRootBootVolumeAttachment(
                    listActiveBootVolumeAttachmentsForInstance(client, instance));
            if (rootAttachment != null
                    && (Objects.equals(rootAttachment.getId(), bootVolumeAttachmentId)
                    || Objects.equals(rootAttachment.getBootVolumeId(), attachment.getBootVolumeId()))) {
                throw new OciException("本机系统引导卷不能在块存储中分离");
            }
            client.getComputeClient().detachBootVolume(
                    DetachBootVolumeRequest.builder()
                            .bootVolumeAttachmentId(bootVolumeAttachmentId)
                            .build());
            log.info("External boot volume detached: attachment {}", bootVolumeAttachmentId);
            evictInstanceAndStorageReadCaches(userId, region);
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "分离外部引导卷失败: " + e.getMessage());
        }
    }

    public void updateBlockVolume(String userId, String volumeId, Long sizeInGBs, String displayName, Long vpusPerGB, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        if (volumeId == null || volumeId.isBlank()) throw new OciException("volumeId 不能为空");

        try (OciClientService client = oci(ociUser, region)) {
            UpdateVolumeDetails.Builder detailsBuilder = UpdateVolumeDetails.builder();
            if (displayName != null && !displayName.isBlank()) {
                detailsBuilder.displayName(displayName.trim());
            }
            if (sizeInGBs != null) {
                validateBlockVolumeSize(sizeInGBs);
                detailsBuilder.sizeInGBs(sizeInGBs);
            }
            if (vpusPerGB != null) {
                detailsBuilder.vpusPerGB(resolveVpusPerGb(vpusPerGB));
            }
            if (displayName == null && sizeInGBs == null && vpusPerGB == null) {
                throw new OciException("至少提供 displayName、sizeInGBs 或 vpusPerGB 之一");
            }

            client.getBlockstorageClient().updateVolume(
                    UpdateVolumeRequest.builder()
                            .volumeId(volumeId)
                            .updateVolumeDetails(detailsBuilder.build())
                            .build());
            log.info("Block volume updated: {}", volumeId);
            evictInstanceAndStorageReadCaches(userId, region);
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "更新块存储卷失败: " + e.getMessage());
        }
    }

    private Instance getInstanceOrThrow(OciClientService client, String instanceId) {
        return client.getComputeClient().getInstance(
                GetInstanceRequest.builder().instanceId(instanceId).build()
        ).getInstance();
    }

    private static BootVolumeAttachment resolveRootBootVolumeAttachment(List<BootVolumeAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        return attachments.stream()
                .filter(a -> a.getBootVolumeId() != null)
                .min(Comparator.comparing(
                        BootVolumeAttachment::getTimeCreated,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    private Set<String> searchableCompartmentIds(OciClientService client, String primaryCompartmentId) {
        Set<String> compartmentIds = new LinkedHashSet<>();
        if (primaryCompartmentId != null && !primaryCompartmentId.isBlank()) {
            compartmentIds.add(primaryCompartmentId);
        }
        try {
            String tenantId = client.getProvider() != null ? client.getProvider().getTenantId() : null;
            if (tenantId != null && !tenantId.isBlank()) {
                compartmentIds.add(tenantId);
            }
            for (Compartment compartment : client.listAllCompartments()) {
                if (compartment.getId() != null && !compartment.getId().isBlank()) {
                    compartmentIds.add(compartment.getId());
                }
            }
        } catch (Exception e) {
            log.debug("Failed to list compartments while resolving boot volumes: {}", e.getMessage());
        }
        return compartmentIds;
    }

    private List<BootVolumeAttachment> listActiveBootVolumeAttachmentsForInstance(OciClientService client, Instance instance) {
        Map<String, BootVolumeAttachment> byId = new LinkedHashMap<>();
        String instanceId = instance.getId();
        String availabilityDomain = instance.getAvailabilityDomain();
        String primaryCompartmentId = instance.getCompartmentId();

        for (BootVolumeAttachment attachment : listActiveBootVolumeAttachments(client, primaryCompartmentId, availabilityDomain, instanceId)) {
            byId.put(attachment.getId(), attachment);
        }

        Set<String> scanned = new LinkedHashSet<>();
        scanned.add(primaryCompartmentId);
        try {
            String tenantId = client.getProvider() != null ? client.getProvider().getTenantId() : null;
            if (tenantId != null && !tenantId.isBlank()) {
                scanned.add(tenantId);
            }
            for (Compartment compartment : client.listAllCompartments()) {
                if (compartment.getId() != null && !compartment.getId().isBlank()) {
                    scanned.add(compartment.getId());
                }
            }
        } catch (Exception e) {
            log.debug("Failed to list compartments while resolving boot volume attachments: {}", e.getMessage());
        }

        for (String compartmentId : scanned) {
            if (Objects.equals(compartmentId, primaryCompartmentId)) continue;
            try {
                for (BootVolumeAttachment attachment : listActiveBootVolumeAttachments(client, compartmentId, availabilityDomain, instanceId)) {
                    byId.put(attachment.getId(), attachment);
                }
            } catch (Exception e) {
                log.debug("Failed to list boot volume attachments in compartment {}: {}", compartmentId, e.getMessage());
            }
        }
        return new ArrayList<>(byId.values());
    }

    private List<BootVolumeAttachment> listActiveBootVolumeAttachments(OciClientService client, String compartmentId,
                                                                       String availabilityDomain, String instanceId) {
        List<BootVolumeAttachment> all = new ArrayList<>();
        String page = null;
        do {
            var b = ListBootVolumeAttachmentsRequest.builder()
                    .compartmentId(compartmentId)
                    .availabilityDomain(availabilityDomain);
            if (instanceId != null && !instanceId.isBlank()) {
                b.instanceId(instanceId);
            }
            var resp = client.getComputeClient().listBootVolumeAttachments(b.page(page).build());
            for (BootVolumeAttachment a : resp.getItems()) {
                if (a.getLifecycleState() != BootVolumeAttachment.LifecycleState.Detached) {
                    all.add(a);
                }
            }
            page = resp.getOpcNextPage();
        } while (page != null);
        return all;
    }

    private List<VolumeAttachment> listActiveVolumeAttachments(OciClientService client, String compartmentId,
                                                               String availabilityDomain, String instanceId) {
        List<VolumeAttachment> all = new ArrayList<>();
        String page = null;
        do {
            var b = ListVolumeAttachmentsRequest.builder()
                    .compartmentId(compartmentId)
                    .availabilityDomain(availabilityDomain);
            if (instanceId != null && !instanceId.isBlank()) {
                b.instanceId(instanceId);
            }
            var resp = client.getComputeClient().listVolumeAttachments(b.page(page).build());
            for (VolumeAttachment a : resp.getItems()) {
                if (a.getLifecycleState() != VolumeAttachment.LifecycleState.Detached) {
                    all.add(a);
                }
            }
            page = resp.getOpcNextPage();
        } while (page != null);
        return all;
    }

    private VolumeAttachment attachVolumeToInstance(OciClientService client, String instanceId, String volumeId,
                                                    String device, String attachmentType) {
        AttachVolumeDetails details = buildAttachVolumeDetails(instanceId, volumeId, device, attachmentType);
        return client.getComputeClient().attachVolume(
                AttachVolumeRequest.builder()
                        .attachVolumeDetails(details)
                        .build()
        ).getVolumeAttachment();
    }

    private BootVolumeAttachment attachBootVolumeToInstance(OciClientService client, String instanceId,
                                                            String bootVolumeId) {
        AttachBootVolumeDetails details = AttachBootVolumeDetails.builder()
                .instanceId(instanceId)
                .bootVolumeId(bootVolumeId)
                .build();
        return client.getComputeClient().attachBootVolume(
                AttachBootVolumeRequest.builder()
                        .attachBootVolumeDetails(details)
                        .build()
        ).getBootVolumeAttachment();
    }

    private static AttachVolumeDetails buildAttachVolumeDetails(String instanceId, String volumeId,
                                                                String device, String attachmentType) {
        String devicePath = (device != null && !device.isBlank()) ? device.trim() : null;
        return switch (resolveBlockAttachmentType(attachmentType)) {
            case "iscsi" -> {
                var builder = AttachIScsiVolumeDetails.builder()
                        .instanceId(instanceId)
                        .volumeId(volumeId);
                if (devicePath != null) {
                    builder.device(devicePath);
                }
                yield builder.build();
            }
            case "paravirtualized" -> {
                var builder = AttachParavirtualizedVolumeDetails.builder()
                        .instanceId(instanceId)
                        .volumeId(volumeId);
                if (devicePath != null) {
                    builder.device(devicePath);
                }
                yield builder.build();
            }
            default -> throw new OciException("不支持的块存储挂载类型");
        };
    }

    private static String resolveBlockAttachmentType(String attachmentType) {
        if (attachmentType == null || attachmentType.isBlank()) {
            return "paravirtualized";
        }
        return switch (attachmentType.trim().toLowerCase(Locale.ROOT).replace("-", "_")) {
            case "paravirtualized", "pv" -> "paravirtualized";
            case "iscsi" -> "iscsi";
            default -> throw new OciException("块存储挂载类型仅支持半虚拟化或 iSCSI");
        };
    }

    private static String enumValue(Object value) {
        if (value == null) return null;
        try {
            Object v = value.getClass().getMethod("getValue").invoke(value);
            return v == null ? null : String.valueOf(v);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private Volume waitVolumeUntilAvailable(OciClientService client, String volumeId) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            Volume v = client.getBlockstorageClient().getVolume(
                    GetVolumeRequest.builder().volumeId(volumeId).build()
            ).getVolume();
            Volume.LifecycleState st = v.getLifecycleState();
            if (st == Volume.LifecycleState.Available) {
                return v;
            }
            if (st == Volume.LifecycleState.Faulty || st == Volume.LifecycleState.Terminated) {
                throw new OciException("块存储卷状态异常: " + (st != null ? st.getValue() : "unknown"));
            }
            Thread.sleep(1000L);
        }
        throw new OciException("等待块存储卷进入 AVAILABLE 状态超时（最长 120 秒）");
    }

    private static Map<String, Object> bootVolumeRow(BootVolumeAttachment att, BootVolume vol) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("rowKey", "boot:" + att.getId());
        map.put("id", vol.getId());
        map.put("volumeType", "boot");
        map.put("volumeTypeLabel", "引导卷");
        map.put("bootVolumeAttachmentId", att.getId());
        map.put("bootVolumeId", vol.getId());
        map.put("displayName", vol.getDisplayName() != null ? vol.getDisplayName() : att.getDisplayName());
        map.put("sizeInGBs", vol.getSizeInGBs());
        map.put("vpusPerGB", vol.getVpusPerGB());
        map.put("device", null);
        map.put("volumeLifecycleState", vol.getLifecycleState() != null ? vol.getLifecycleState().getValue() : null);
        map.put("attachmentLifecycleState", att.getLifecycleState() != null ? att.getLifecycleState().getValue() : null);
        map.put("lifecycleState", vol.getLifecycleState() != null ? vol.getLifecycleState().getValue() : null);
        map.put("timeCreated", vol.getTimeCreated() != null ? vol.getTimeCreated().toString() : null);
        map.put("attachmentTimeCreated", att.getTimeCreated() != null ? att.getTimeCreated().toString() : null);
        map.put("attachmentTimeUpdated", att.getTimeUpdated() != null ? att.getTimeUpdated().toString() : null);
        map.put("availabilityDomain", vol.getAvailabilityDomain() != null ? vol.getAvailabilityDomain() : att.getAvailabilityDomain());
        map.put("isHydrated", vol.getIsHydrated());
        map.put("encryptionInTransitType", enumValue(att.getEncryptionInTransitType()));
        map.put("isPvEncryptionInTransitEnabled", att.getIsPvEncryptionInTransitEnabled());
        return map;
    }

    private static Map<String, Object> externalBootVolumeRow(BootVolumeAttachment att, BootVolume vol, Instance instance) {
        Map<String, Object> map = new LinkedHashMap<>();
        boolean attached = att != null;
        map.put("rowKey", attached ? "externalBoot:attached:" + att.getId() : "externalBoot:available:" + vol.getId());
        map.put("id", vol.getId());
        map.put("volumeType", "externalBoot");
        map.put("volumeTypeLabel", "外部引导卷");
        map.put("bootVolumeId", vol.getId());
        map.put("bootVolumeAttachmentId", attached ? att.getId() : null);
        map.put("displayName", vol.getDisplayName() != null ? vol.getDisplayName() : (attached ? att.getDisplayName() : null));
        map.put("sizeInGBs", vol.getSizeInGBs());
        map.put("vpusPerGB", vol.getVpusPerGB());
        map.put("attached", attached);
        map.put("attachState", attached ? "已挂载" : "可挂载");
        map.put("volumeLifecycleState", vol.getLifecycleState() != null ? vol.getLifecycleState().getValue() : null);
        map.put("attachmentLifecycleState", attached && att.getLifecycleState() != null ? att.getLifecycleState().getValue() : null);
        map.put("lifecycleState", vol.getLifecycleState() != null ? vol.getLifecycleState().getValue() : null);
        map.put("timeCreated", vol.getTimeCreated() != null ? vol.getTimeCreated().toString() : null);
        map.put("attachmentTimeCreated", attached && att.getTimeCreated() != null ? att.getTimeCreated().toString() : null);
        map.put("attachmentTimeUpdated", attached && att.getTimeUpdated() != null ? att.getTimeUpdated().toString() : null);
        map.put("availabilityDomain", vol.getAvailabilityDomain() != null ? vol.getAvailabilityDomain() : (attached ? att.getAvailabilityDomain() : null));
        map.put("compartmentId", vol.getCompartmentId());
        map.put("imageId", vol.getImageId());
        map.put("isHydrated", vol.getIsHydrated());
        map.put("instanceId", attached ? att.getInstanceId() : null);
        map.put("instanceName", instance != null ? instance.getDisplayName() : null);
        map.put("instanceState", instance != null && instance.getLifecycleState() != null ? instance.getLifecycleState().getValue() : null);
        map.put("encryptionInTransitType", attached ? enumValue(att.getEncryptionInTransitType()) : null);
        map.put("isPvEncryptionInTransitEnabled", attached ? att.getIsPvEncryptionInTransitEnabled() : null);
        return map;
    }

    private static Map<String, Object> blockVolumeRow(VolumeAttachment att, Volume vol) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("rowKey", "block:" + att.getId());
        map.put("volumeType", "block");
        map.put("volumeTypeLabel", "块存储卷");
        map.put("attachmentId", att.getId());
        map.put("volumeId", vol.getId());
        map.put("displayName", vol.getDisplayName());
        map.put("sizeInGBs", vol.getSizeInGBs());
        map.put("vpusPerGB", vol.getVpusPerGB());
        map.put("device", att.getDevice());
        map.put("volumeLifecycleState", vol.getLifecycleState() != null ? vol.getLifecycleState().getValue() : null);
        map.put("attachmentLifecycleState", att.getLifecycleState() != null ? att.getLifecycleState().getValue() : null);
        map.put("timeCreated", vol.getTimeCreated() != null ? vol.getTimeCreated().toString() : null);
        map.put("availabilityDomain", vol.getAvailabilityDomain());
        map.put("isHydrated", vol.getIsHydrated());
        return map;
    }

    private static void validateBlockVolumeSize(Long sizeInGBs) {
        if (sizeInGBs == null || sizeInGBs < 50) {
            throw new OciException("块存储卷容量须至少 50 GB（OCI CreateVolumeDetails.sizeInGBs）");
        }
        if (sizeInGBs > 32768) {
            throw new OciException("块存储卷容量不能超过 32768 GB");
        }
    }

    /** OCI Block Volume 性能档位：0 / 10 / 20 / 30–120（步进 10）。默认 10（Balanced）。 */
    private static long resolveVpusPerGb(Long vpusPerGB) {
        if (vpusPerGB == null) {
            return 10L;
        }
        long v = vpusPerGB;
        if (v == 0L || v == 10L || v == 20L) {
            return v;
        }
        if (v >= 30L && v <= 120L && v % 10L == 0L) {
            return v;
        }
        throw new OciException("vpusPerGB 须为 0、10、20 或 30～120（Ultra High 档步进 10），见 OCI Block Volume 性能档位文档");
    }

    private String extractOciErrorMessage(com.oracle.bmc.model.BmcException e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            return "OCI 调用失败（无详细信息）";
        }
        if (msg.contains("LimitExceeded")) {
            return "已超出免费账户限制，无法创建更多资源。请在OCI控制台申请提升配额。";
        }
        if (msg.contains("Conflict")) {
            return "资源冲突，该私有IP已有公网IP绑定。请先解绑现有公网IP。";
        }
        if (e.getStatusCode() == 409
                && (msg.contains("UpdateBootVolume operation") || msg.contains("BootVolume operation") || msg.contains("bootVolumes/"))
                && (msg.contains("UPDATE_PENDING") || msg.contains("must be in state AVAILABLE") || msg.contains("cannot be resized"))) {
            return "引导卷正在更新中，请等待状态变为 AVAILABLE（可用）后再操作。";
        }
        if (msg.contains("NotAuthorizedOrNotFound")) {
            return "权限不足或资源不存在。";
        }
        if (msg.contains("InvalidParameter")) {
            if (msg.contains("IPv6")) {
                return "子网或VCN未启用IPv6，正在自动配置中，请稍后重试。";
            }
            return "参数无效: " + msg.substring(0, Math.min(msg.length(), 100));
        }
        if (msg.contains("TooManyRequests")) {
            return "请求过于频繁，请稍后重试。";
        }
        return OciBmcErrorTranslator.translate(e);
    }

    private String extractInstanceActionErrorMessage(com.oracle.bmc.model.BmcException e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            return "OCI 调用失败（无详细信息）";
        }
        if (msg.contains("InstanceAction operation in Compute service")
                && msg.contains("is disabled and will not accept any action requests")) {
            return "实例已被禁用，不会接受任何操作请求。请联系客户支持以重新启用。";
        }
        return extractOciErrorMessage(e);
    }

    private SysUserDTO buildBasicDTO(OciUser ociUser) {
        return SysUserDTO.builder()
                .username(ociUser.getUsername())
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(ociUser.getOciTenantId())
                        .userId(ociUser.getOciUserId())
                        .fingerprint(ociUser.getOciFingerprint())
                        .region(ociUser.getOciRegion())
                        .privateKeyPath(ociUser.getOciKeyPath())
                        .build())
                .build();
    }
}
