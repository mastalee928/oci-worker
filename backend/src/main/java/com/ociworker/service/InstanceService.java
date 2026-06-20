package com.ociworker.service;

import com.oracle.bmc.core.model.*;
import com.oracle.bmc.core.requests.*;
import com.oracle.bmc.identity.model.Compartment;
import com.ociworker.exception.OciException;
import com.ociworker.util.ShapeFlexLimitsUtil;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.ShapeEditTaskStatus;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.LinkedHashSet;

@Slf4j
@Service
public class InstanceService {

    private static final String SHAPE_A2_FLEX = "VM.Standard.A2.Flex";
    private static final String SHAPE_A1_FLEX = "VM.Standard.A1.Flex";

    @Resource
    private OciUserMapper userMapper;
    @Resource
    private NotificationService notificationService;
    @Resource
    private ShapeEditTaskManager shapeEditTaskManager;

    private String tag(OciUser u) { return "[" + u.getUsername() + "] "; }

    private OciClientService oci(OciUser ociUser, String region) {
        String r = (region == null || region.isBlank()) ? null : region.trim();
        return new OciClientService(buildBasicDTO(ociUser), r);
    }

    public List<Map<String, Object>> listInstances(String userId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

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
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "终止实例失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listBootVolumesByInstance(String userId, String instanceId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            Instance instance = client.getComputeClient().getInstance(
                    GetInstanceRequest.builder().instanceId(instanceId).build()
            ).getInstance();

            List<BootVolumeAttachment> attachments = listActiveBootVolumeAttachmentsForInstance(client, instance);

            List<Map<String, Object>> result = new ArrayList<>();
            for (BootVolumeAttachment att : attachments) {
                try {
                    BootVolume vol = client.getBlockstorageClient().getBootVolume(
                            GetBootVolumeRequest.builder().bootVolumeId(att.getBootVolumeId()).build()
                    ).getBootVolume();
                    result.add(bootVolumeRow(att, vol));
                } catch (Exception e) {
                    log.warn("Failed to get boot volume {}: {}", att.getBootVolumeId(), e.getMessage());
                }
            }
            return result;
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
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "更新引导卷失败: " + e.getMessage());
        }
    }

    /**
     * Gets detailed network info for an instance: private IP, public IP type (reserved/ephemeral), IPv6
     */
    public Map<String, Object> getInstanceNetworkDetail(String userId, String instanceId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            Map<String, Object> result = new LinkedHashMap<>();

            List<VnicAttachment> attachments = client.getComputeClient().listVnicAttachments(
                    ListVnicAttachmentsRequest.builder()
                            .compartmentId(client.getCompartmentId())
                            .instanceId(instanceId)
                            .build()
            ).getItems();

            List<Map<String, Object>> vnics = new ArrayList<>();
            for (VnicAttachment att : attachments) {
                try {
                    Vnic vnic = client.getVirtualNetworkClient().getVnic(
                            GetVnicRequest.builder().vnicId(att.getVnicId()).build()
                    ).getVnic();

                    Map<String, Object> vnicInfo = new LinkedHashMap<>();
                    vnicInfo.put("vnicId", vnic.getId());
                    vnicInfo.put("displayName", vnic.getDisplayName());
                    vnicInfo.put("privateIp", vnic.getPrivateIp());
                    vnicInfo.put("publicIp", vnic.getPublicIp());
                    vnicInfo.put("subnetId", att.getSubnetId());

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
                            ipInfo.put("publicIpLifetime", pubIp.getLifetime().getValue());
                        } catch (Exception ignored) {
                            ipInfo.put("publicIpAddress", null);
                            ipInfo.put("publicIpId", null);
                            ipInfo.put("publicIpLifetime", null);
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
    public Map<String, String> addIpv6(String userId, String instanceId, String preferredVnicId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            List<VnicAttachment> attachments = client.getComputeClient().listVnicAttachments(
                    ListVnicAttachmentsRequest.builder()
                            .compartmentId(client.getCompartmentId())
                            .instanceId(instanceId)
                            .build()
            ).getItems();
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
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "取消分配 IPv6 失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "取消分配 IPv6 失败: " + e.getMessage());
        }
    }

    private void ensureIpv6InternetRoute(OciClientService client, Vcn vcn, Subnet subnet) {
        List<InternetGateway> igws = client.getVirtualNetworkClient().listInternetGateways(
                ListInternetGatewaysRequest.builder()
                        .compartmentId(client.getCompartmentId())
                        .vcnId(vcn.getId())
                        .build()
        ).getItems();

        InternetGateway igw;
        if (igws == null || igws.isEmpty()) {
            log.info("VCN {} has no Internet Gateway, creating one...", vcn.getDisplayName());
            igw = client.createInternetGateway(vcn);
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
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

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
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "删除预留 IP 失败: " + e.getMessage());
        }
    }

    /**
     * Assigns a reserved IP to an instance by creating a secondary private IP on the VNIC,
     * then binding the reserved public IP to that secondary private IP.
     */
    public void assignReservedIp(String userId, String publicIpId, String instanceId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            List<VnicAttachment> attachments = client.getComputeClient().listVnicAttachments(
                    ListVnicAttachmentsRequest.builder()
                            .compartmentId(client.getCompartmentId())
                            .instanceId(instanceId)
                            .build()
            ).getItems();
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

    private Map<String, Object> updateInstanceOnce(String userId, String instanceId,
                                                   String displayName, String shape,
                                                   Float ocpus, Float memoryInGBs, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            return updateInstanceOnce(client, ociUser, instanceId, displayName, shape, ocpus, memoryInGBs);
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
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

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
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

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
     * 当前实例已挂载的卷：引导卷来自 ListBootVolumeAttachments，普通块卷来自 ListVolumeAttachments。
     */
    public List<Map<String, Object>> listBlockVolumesByInstance(String userId, String instanceId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        if (instanceId == null || instanceId.isBlank()) throw new OciException("instanceId 不能为空");

        try (OciClientService client = oci(ociUser, region)) {
            Instance instance = getInstanceOrThrow(client, instanceId);
            String compartmentId = instance.getCompartmentId();
            String ad = instance.getAvailabilityDomain();

            List<Map<String, Object>> result = new ArrayList<>();

            for (BootVolumeAttachment att : listActiveBootVolumeAttachmentsForInstance(client, instance)) {
                String bootVolumeId = att.getBootVolumeId();
                if (bootVolumeId == null) continue;
                try {
                    BootVolume vol = client.getBlockstorageClient().getBootVolume(
                            GetBootVolumeRequest.builder().bootVolumeId(bootVolumeId).build()
                    ).getBootVolume();
                    result.add(bootVolumeRow(att, vol));
                } catch (Exception e) {
                    log.warn("Failed to get boot volume {}: {}", bootVolumeId, e.getMessage());
                }
            }

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
     * 同 AD、同区间、AVAILABLE 且未挂载到他处的块存储卷（供 AttachVolume 选择）。
     */
    public List<Map<String, Object>> listUnattachedBlockVolumesForInstance(String userId, String instanceId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        if (instanceId == null || instanceId.isBlank()) throw new OciException("instanceId 不能为空");

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
            return blockVolumeRow(attachment, refreshed);
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "挂载块存储卷失败: " + e.getMessage());
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
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "卸载块存储卷失败: " + e.getMessage());
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
        return msg.length() > 150 ? msg.substring(0, 150) + "..." : msg;
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
