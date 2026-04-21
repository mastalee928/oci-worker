package com.ociworker.service;

import com.oracle.bmc.core.model.*;
import com.oracle.bmc.core.requests.*;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
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

    @Resource
    private OciUserMapper userMapper;

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

            List<BootVolumeAttachment> attachments = client.getComputeClient().listBootVolumeAttachments(
                    ListBootVolumeAttachmentsRequest.builder()
                            .compartmentId(client.getCompartmentId())
                            .instanceId(instanceId)
                            .availabilityDomain(instance.getAvailabilityDomain())
                            .build()
            ).getItems();

            List<Map<String, Object>> result = new ArrayList<>();
            for (BootVolumeAttachment att : attachments) {
                try {
                    BootVolume vol = client.getBlockstorageClient().getBootVolume(
                            GetBootVolumeRequest.builder().bootVolumeId(att.getBootVolumeId()).build()
                    ).getBootVolume();
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", vol.getId());
                    map.put("displayName", vol.getDisplayName());
                    map.put("sizeInGBs", vol.getSizeInGBs());
                    map.put("vpusPerGB", vol.getVpusPerGB());
                    map.put("lifecycleState", vol.getLifecycleState().getValue());
                    map.put("timeCreated", vol.getTimeCreated() != null ? vol.getTimeCreated().toString() : null);
                    result.add(map);
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

    public Map<String, Object> updateInstance(String userId, String instanceId,
                                               String displayName, Float ocpus, Float memoryInGBs, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            UpdateInstanceDetails.Builder detailsBuilder = UpdateInstanceDetails.builder();

            if (displayName != null && !displayName.isBlank()) {
                detailsBuilder.displayName(displayName);
            }

            if (ocpus != null || memoryInGBs != null) {
                UpdateInstanceShapeConfigDetails.Builder shapeBuilder =
                        UpdateInstanceShapeConfigDetails.builder();
                if (ocpus != null) shapeBuilder.ocpus(ocpus);
                if (memoryInGBs != null) shapeBuilder.memoryInGBs(memoryInGBs);
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
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "修改实例失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "修改实例失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listAvailableShapes(String userId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            var ads = client.getAvailabilityDomains();
            Set<String> seen = new LinkedHashSet<>();
            List<Map<String, Object>> result = new ArrayList<>();
            for (var ad : ads) {
                for (var shape : client.getShapes(ad.getName())) {
                    if (seen.add(shape.getShape())) {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("shape", shape.getShape());
                        map.put("ocpus", shape.getOcpus());
                        map.put("memoryInGBs", shape.getMemoryInGBs());
                        map.put("isFlexible", shape.getShape().contains("Flex"));
                        map.put("processorDescription", shape.getProcessorDescription());
                        result.add(map);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "获取可用 Shape 列表失败: " + e.getMessage());
        }
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
