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
import java.util.stream.Collectors;
import java.util.LinkedHashSet;

@Slf4j
@Service
public class InstanceService {

    @Resource
    private OciUserMapper userMapper;

    public List<Map<String, Object>> listInstances(String userId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            List<Instance> instances = client.listAllInstances();
            return instances.stream().map(inst -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("instanceId", inst.getId());
                map.put("name", inst.getDisplayName());
                map.put("region", inst.getRegion());
                map.put("shape", inst.getShape());
                map.put("state", inst.getLifecycleState().getValue());
                map.put("timeCreated", inst.getTimeCreated() != null ? inst.getTimeCreated().toString() : null);
                map.put("availabilityDomain", inst.getAvailabilityDomain());

                if (inst.getShapeConfig() != null) {
                    map.put("ocpus", inst.getShapeConfig().getOcpus());
                    map.put("memoryInGBs", inst.getShapeConfig().getMemoryInGBs());
                }

                String publicIp = client.getInstancePublicIp(inst);
                map.put("publicIp", publicIp);
                return map;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to list instances: {}", e.getMessage());
            throw new OciException("获取实例列表失败: " + e.getMessage());
        }
    }

    public void updateInstanceState(String userId, String instanceId, String action) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            InstanceActionRequest request = InstanceActionRequest.builder()
                    .instanceId(instanceId)
                    .action(action)
                    .build();
            client.getComputeClient().instanceAction(request);
            log.info("Instance {} action: {}", instanceId, action);
        } catch (Exception e) {
            throw new OciException("操作失败: " + e.getMessage());
        }
    }

    public void terminateInstance(String userId, String instanceId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            client.getComputeClient().terminateInstance(
                    TerminateInstanceRequest.builder()
                            .instanceId(instanceId)
                            .preserveBootVolume(false)
                            .build());
            log.info("Instance terminated: {}", instanceId);
        } catch (Exception e) {
            throw new OciException("终止实例失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listBootVolumesByInstance(String userId, String instanceId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
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
            throw new OciException("获取引导卷列表失败: " + e.getMessage());
        }
    }

    public void updateBootVolume(String userId, String bootVolumeId, Long sizeInGBs, String displayName, Long vpusPerGB) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
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
            throw new OciException("更新引导卷失败: " + e.getMessage());
        }
    }

    /**
     * Gets detailed network info for an instance: private IP, public IP type (reserved/ephemeral), IPv6
     */
    public Map<String, Object> getInstanceNetworkDetail(String userId, String instanceId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
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
                    vnicInfo.put("ipv6Addresses", ipv6List.stream().map(Ipv6::getIpAddress).toList());

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
            throw new OciException("获取实例网络详情失败: " + e.getMessage());
        }
    }

    /**
     * Full IPv6 flow: ensure VCN has IPv6 CIDR → ensure subnet has IPv6 CIDR → create IPv6 on VNIC
     */
    public Map<String, String> addIpv6(String userId, String instanceId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            List<VnicAttachment> attachments = client.getComputeClient().listVnicAttachments(
                    ListVnicAttachmentsRequest.builder()
                            .compartmentId(client.getCompartmentId())
                            .instanceId(instanceId)
                            .build()
            ).getItems();
            if (attachments.isEmpty()) throw new OciException("未找到实例的 VNIC");

            String vnicId = attachments.get(0).getVnicId();
            String subnetId = attachments.get(0).getSubnetId();

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
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("already has")) {
                        throw new OciException("VCN 添加 IPv6 CIDR 失败: " + extractOciErrorMessage(e));
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
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("already has")) {
                        throw new OciException("子网添加 IPv6 CIDR 失败: " + extractOciErrorMessage(e));
                    }
                }
            }

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
            throw new OciException("添加 IPv6 失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException("添加 IPv6 失败: " + e.getMessage());
        }
    }

    public Map<String, String> createReservedIp(String userId, String displayName) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
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
            throw new OciException("创建预留IP失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException("创建预留IP失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listReservedIps(String userId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
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
            throw new OciException("获取预留 IP 列表失败: " + e.getMessage());
        }
    }

    public void deleteReservedIp(String userId, String publicIpId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            client.getVirtualNetworkClient().deletePublicIp(
                    DeletePublicIpRequest.builder().publicIpId(publicIpId).build());
            log.info("Reserved IP deleted: {}", publicIpId);
        } catch (Exception e) {
            throw new OciException("删除预留 IP 失败: " + e.getMessage());
        }
    }

    /**
     * Assigns a reserved IP to an instance by creating a secondary private IP on the VNIC,
     * then binding the reserved public IP to that secondary private IP.
     */
    public void assignReservedIp(String userId, String publicIpId, String instanceId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
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
            throw new OciException("绑定预留IP失败: " + extractOciErrorMessage(e));
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("绑定预留IP失败: " + e.getMessage());
        }
    }

    public void unassignReservedIp(String userId, String publicIpId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
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
            throw new OciException("解绑预留IP失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException("解绑预留IP失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listAvailableShapes(String userId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
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
            throw new OciException("获取可用 Shape 列表失败: " + e.getMessage());
        }
    }

    private String extractOciErrorMessage(com.oracle.bmc.model.BmcException e) {
        String msg = e.getMessage();
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
