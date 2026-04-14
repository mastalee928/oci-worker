package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
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

@Slf4j
@Service
public class NetworkService {

    @Resource
    private OciUserMapper userMapper;

    public List<Map<String, Object>> listVcns(String userId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = new OciClientService(buildDTO(ociUser))) {
            return client.listVcn().stream().map(vcn -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", vcn.getId());
                map.put("displayName", vcn.getDisplayName());
                map.put("cidrBlocks", vcn.getCidrBlocks());
                map.put("state", vcn.getLifecycleState().getValue());
                map.put("timeCreated", vcn.getTimeCreated() != null ? vcn.getTimeCreated().toString() : null);

                List<Subnet> subnets = client.listSubnets(vcn.getId());
                map.put("subnets", subnets.stream().map(s -> Map.of(
                        "id", s.getId(),
                        "displayName", s.getDisplayName(),
                        "cidrBlock", s.getCidrBlock(),
                        "isPublic", !s.getProhibitInternetIngress()
                )).toList());
                return map;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            throw new OciException("获取VCN列表失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listSecurityRulesByInstance(String userId, String instanceId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = new OciClientService(buildDTO(ociUser))) {
            String subnetId = getSubnetIdFromInstance(client, instanceId);
            Subnet subnet = client.getVirtualNetworkClient().getSubnet(
                    GetSubnetRequest.builder().subnetId(subnetId).build()
            ).getSubnet();

            SecurityList secList = client.getVirtualNetworkClient().getSecurityList(
                    GetSecurityListRequest.builder()
                            .securityListId(subnet.getSecurityListIds().get(0))
                            .build()
            ).getSecurityList();

            List<Map<String, Object>> result = new ArrayList<>();
            for (IngressSecurityRule rule : secList.getIngressSecurityRules()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("direction", "ingress");
                map.put("protocol", rule.getProtocol());
                map.put("source", rule.getSource());
                map.put("description", rule.getDescription());
                if (rule.getTcpOptions() != null && rule.getTcpOptions().getDestinationPortRange() != null) {
                    map.put("portRange", rule.getTcpOptions().getDestinationPortRange().getMin()
                            + "-" + rule.getTcpOptions().getDestinationPortRange().getMax());
                } else {
                    map.put("portRange", "all");
                }
                result.add(map);
            }
            for (EgressSecurityRule rule : secList.getEgressSecurityRules()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("direction", "egress");
                map.put("protocol", rule.getProtocol());
                map.put("source", rule.getDestination());
                map.put("description", rule.getDescription());
                map.put("portRange", "all");
                result.add(map);
            }
            return result;
        } catch (Exception e) {
            throw new OciException("获取安全规则失败: " + e.getMessage());
        }
    }

    public void releaseAllPortsByInstance(String userId, String instanceId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = new OciClientService(buildDTO(ociUser))) {
            String subnetId = getSubnetIdFromInstance(client, instanceId);
            Subnet subnet = client.getVirtualNetworkClient().getSubnet(
                    GetSubnetRequest.builder().subnetId(subnetId).build()
            ).getSubnet();

            String secListId = subnet.getSecurityListIds().get(0);
            List<IngressSecurityRule> ingressRules = List.of(
                    IngressSecurityRule.builder()
                            .source("0.0.0.0/0")
                            .protocol("all")
                            .description("Allow all ingress")
                            .build());
            List<EgressSecurityRule> egressRules = List.of(
                    EgressSecurityRule.builder()
                            .destination("0.0.0.0/0")
                            .protocol("all")
                            .description("Allow all egress")
                            .build());

            client.getVirtualNetworkClient().updateSecurityList(
                    UpdateSecurityListRequest.builder()
                            .securityListId(secListId)
                            .updateSecurityListDetails(UpdateSecurityListDetails.builder()
                                    .ingressSecurityRules(ingressRules)
                                    .egressSecurityRules(egressRules)
                                    .build())
                            .build());
            log.info("Released all ports for subnet: {}", subnetId);
        } catch (Exception e) {
            throw new OciException("放行端口失败: " + e.getMessage());
        }
    }

    public void changePublicIp(String userId, String instanceId, List<String> cidrFilters) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = new OciClientService(buildDTO(ociUser))) {
            List<VnicAttachment> attachments = client.getComputeClient().listVnicAttachments(
                    ListVnicAttachmentsRequest.builder()
                            .compartmentId(client.getCompartmentId())
                            .instanceId(instanceId)
                            .build()
            ).getItems();

            if (attachments.isEmpty()) throw new OciException("未找到 VNIC");

            VnicAttachment attachment = attachments.get(0);
            Vnic vnic = client.getVirtualNetworkClient().getVnic(
                    GetVnicRequest.builder().vnicId(attachment.getVnicId()).build()
            ).getVnic();

            List<PrivateIp> privateIps = client.getVirtualNetworkClient().listPrivateIps(
                    ListPrivateIpsRequest.builder().vnicId(vnic.getId()).build()
            ).getItems();
            if (privateIps.isEmpty()) throw new OciException("未找到私有IP");

            List<PublicIp> publicIps = new ArrayList<>();
            for (PrivateIp pip : privateIps) {
                try {
                    PublicIp pubIp = client.getVirtualNetworkClient().getPublicIpByPrivateIpId(
                            GetPublicIpByPrivateIpIdRequest.builder()
                                    .getPublicIpByPrivateIpIdDetails(
                                            GetPublicIpByPrivateIpIdDetails.builder()
                                                    .privateIpId(pip.getId()).build())
                                    .build()
                    ).getPublicIp();
                    if (pubIp != null) publicIps.add(pubIp);
                } catch (Exception ignored) {}
            }

            for (PublicIp pubIp : publicIps) {
                client.getVirtualNetworkClient().deletePublicIp(
                        DeletePublicIpRequest.builder().publicIpId(pubIp.getId()).build());
            }

            PublicIp newPubIp = client.getVirtualNetworkClient().createPublicIp(
                    CreatePublicIpRequest.builder()
                            .createPublicIpDetails(CreatePublicIpDetails.builder()
                                    .compartmentId(client.getCompartmentId())
                                    .lifetime(CreatePublicIpDetails.Lifetime.Ephemeral)
                                    .privateIpId(privateIps.get(0).getId())
                                    .build())
                            .build()
            ).getPublicIp();

            log.info("Changed IP for instance {}: {}", instanceId, newPubIp.getIpAddress());
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("更换IP失败: " + e.getMessage());
        }
    }

    private String getSubnetIdFromInstance(OciClientService client, String instanceId) {
        List<VnicAttachment> attachments = client.getComputeClient().listVnicAttachments(
                ListVnicAttachmentsRequest.builder()
                        .compartmentId(client.getCompartmentId())
                        .instanceId(instanceId)
                        .build()
        ).getItems();
        if (attachments.isEmpty()) throw new OciException("未找到实例的 VNIC");
        return attachments.get(0).getSubnetId();
    }

    private SysUserDTO buildDTO(OciUser ociUser) {
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
