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
            var compartments = client.listAllCompartments();
            Map<String, String> compartmentNameMap = new LinkedHashMap<>();
            for (var c : compartments) {
                compartmentNameMap.put(c.getId(), c.getName());
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (var compartment : compartments) {
                List<Vcn> vcns = client.listVcnInCompartment(compartment.getId());
                for (Vcn vcn : vcns) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", vcn.getId());
                    map.put("displayName", vcn.getDisplayName());
                    map.put("cidrBlocks", vcn.getCidrBlocks());
                    map.put("state", vcn.getLifecycleState().getValue());
                    map.put("compartmentId", vcn.getCompartmentId());
                    map.put("compartmentName", compartmentNameMap.getOrDefault(vcn.getCompartmentId(), "unknown"));
                    map.put("timeCreated", vcn.getTimeCreated() != null ? vcn.getTimeCreated().toString() : null);

                    List<Subnet> subnets = client.listSubnets(vcn.getId());
                    map.put("subnets", subnets.stream().map(s -> Map.of(
                            "id", s.getId(),
                            "displayName", s.getDisplayName(),
                            "cidrBlock", s.getCidrBlock(),
                            "isPublic", !s.getProhibitInternetIngress()
                    )).toList());
                    result.add(map);
                }
            }
            return result;
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

    public void addSecurityRule(String userId, String instanceId, String direction,
                               String protocol, String source, String portMin, String portMax, String description) {
        if (description != null && description.isBlank()) description = null;
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = new OciClientService(buildDTO(ociUser))) {
            String subnetId = getSubnetIdFromInstance(client, instanceId);
            Subnet subnet = client.getVirtualNetworkClient().getSubnet(
                    GetSubnetRequest.builder().subnetId(subnetId).build()
            ).getSubnet();

            String secListId = subnet.getSecurityListIds().get(0);
            SecurityList secList = client.getVirtualNetworkClient().getSecurityList(
                    GetSecurityListRequest.builder().securityListId(secListId).build()
            ).getSecurityList();

            String proto = switch (protocol.toUpperCase()) {
                case "TCP" -> "6";
                case "UDP" -> "17";
                case "ICMP" -> "1";
                case "ICMPV6", "ICMP-IPV6" -> "58";
                default -> "all";
            };

            if ("ingress".equalsIgnoreCase(direction)) {
                List<IngressSecurityRule> rules = new ArrayList<>(secList.getIngressSecurityRules());
                IngressSecurityRule.Builder ruleBuilder = IngressSecurityRule.builder()
                        .source(source != null ? source : "0.0.0.0/0")
                        .protocol(proto)
                        .description(description);

                if ("6".equals(proto) || "17".equals(proto)) {
                    if (portMin != null && portMax != null) {
                        PortRange range = PortRange.builder()
                                .min(Integer.parseInt(portMin)).max(Integer.parseInt(portMax)).build();
                        if ("6".equals(proto)) {
                            ruleBuilder.tcpOptions(TcpOptions.builder().destinationPortRange(range).build());
                        } else {
                            ruleBuilder.udpOptions(UdpOptions.builder().destinationPortRange(range).build());
                        }
                    }
                }
                rules.add(ruleBuilder.build());
                client.getVirtualNetworkClient().updateSecurityList(
                        UpdateSecurityListRequest.builder()
                                .securityListId(secListId)
                                .updateSecurityListDetails(UpdateSecurityListDetails.builder()
                                        .ingressSecurityRules(rules)
                                        .egressSecurityRules(secList.getEgressSecurityRules())
                                        .build())
                                .build());
            } else {
                List<EgressSecurityRule> rules = new ArrayList<>(secList.getEgressSecurityRules());
                rules.add(EgressSecurityRule.builder()
                        .destination(source != null ? source : "0.0.0.0/0")
                        .protocol(proto)
                        .description(description)
                        .build());
                client.getVirtualNetworkClient().updateSecurityList(
                        UpdateSecurityListRequest.builder()
                                .securityListId(secListId)
                                .updateSecurityListDetails(UpdateSecurityListDetails.builder()
                                        .ingressSecurityRules(secList.getIngressSecurityRules())
                                        .egressSecurityRules(rules)
                                        .build())
                                .build());
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("添加安全规则失败: " + e.getMessage());
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

            PrivateIp primaryPrivateIp = privateIps.stream()
                    .filter(p -> Boolean.TRUE.equals(p.getIsPrimary()))
                    .findFirst()
                    .orElse(privateIps.get(0));

            try {
                PublicIp oldPubIp = client.getVirtualNetworkClient().getPublicIpByPrivateIpId(
                        GetPublicIpByPrivateIpIdRequest.builder()
                                .getPublicIpByPrivateIpIdDetails(
                                        GetPublicIpByPrivateIpIdDetails.builder()
                                                .privateIpId(primaryPrivateIp.getId()).build())
                                .build()
                ).getPublicIp();
                if (oldPubIp != null) {
                    client.getVirtualNetworkClient().deletePublicIp(
                            DeletePublicIpRequest.builder().publicIpId(oldPubIp.getId()).build());
                }
            } catch (Exception ignored) {}

            PublicIp newPubIp = client.getVirtualNetworkClient().createPublicIp(
                    CreatePublicIpRequest.builder()
                            .createPublicIpDetails(CreatePublicIpDetails.builder()
                                    .compartmentId(client.getCompartmentId())
                                    .lifetime(CreatePublicIpDetails.Lifetime.Ephemeral)
                                    .privateIpId(primaryPrivateIp.getId())
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

    public void deletePublicIpByPrivateIpId(String userId, String privateIpId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = new OciClientService(buildDTO(ociUser))) {
            PublicIp pubIp = client.getVirtualNetworkClient().getPublicIpByPrivateIpId(
                    GetPublicIpByPrivateIpIdRequest.builder()
                            .getPublicIpByPrivateIpIdDetails(
                                    GetPublicIpByPrivateIpIdDetails.builder()
                                            .privateIpId(privateIpId).build())
                            .build()
            ).getPublicIp();
            if (pubIp != null) {
                client.getVirtualNetworkClient().deletePublicIp(
                        DeletePublicIpRequest.builder().publicIpId(pubIp.getId()).build());
                log.info("Deleted public IP {} from private IP {}", pubIp.getIpAddress(), privateIpId);
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("删除公网IP失败: " + e.getMessage());
        }
    }

    public void deleteSecondaryIp(String userId, String privateIpId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = new OciClientService(buildDTO(ociUser))) {
            try {
                PublicIp pubIp = client.getVirtualNetworkClient().getPublicIpByPrivateIpId(
                        GetPublicIpByPrivateIpIdRequest.builder()
                                .getPublicIpByPrivateIpIdDetails(
                                        GetPublicIpByPrivateIpIdDetails.builder()
                                                .privateIpId(privateIpId).build())
                                .build()
                ).getPublicIp();
                if (pubIp != null) {
                    client.getVirtualNetworkClient().deletePublicIp(
                            DeletePublicIpRequest.builder().publicIpId(pubIp.getId()).build());
                    log.info("Deleted public IP {} before removing secondary private IP", pubIp.getIpAddress());
                }
            } catch (Exception ignored) {}

            client.getVirtualNetworkClient().deletePrivateIp(
                    DeletePrivateIpRequest.builder().privateIpId(privateIpId).build());
            log.info("Deleted secondary private IP {}", privateIpId);
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("删除辅助IP失败: " + e.getMessage());
        }
    }

    public Map<String, String> assignEphemeralPublicIp(String userId, String instanceId, String privateIpId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = new OciClientService(buildDTO(ociUser))) {
            PublicIp newPubIp = client.getVirtualNetworkClient().createPublicIp(
                    CreatePublicIpRequest.builder()
                            .createPublicIpDetails(CreatePublicIpDetails.builder()
                                    .compartmentId(client.getCompartmentId())
                                    .lifetime(CreatePublicIpDetails.Lifetime.Ephemeral)
                                    .privateIpId(privateIpId)
                                    .build())
                            .build()
            ).getPublicIp();

            log.info("Assigned ephemeral IP {} to private IP {}", newPubIp.getIpAddress(), privateIpId);
            return Map.of("publicIp", newPubIp.getIpAddress());
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("LimitExceeded")) {
                throw new OciException("公网 IP 配额已满，无法分配更多公网 IP");
            }
            throw new OciException("分配公网IP失败: " + msg);
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
