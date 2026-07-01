package com.ociworker.service;

import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.*;
import com.oracle.bmc.core.requests.*;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.ociworker.util.OciBmcErrorTranslator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
public class VcnService {

    private static final Duration VCN_READ_CACHE_TTL = Duration.ofMinutes(2);

    @Resource
    private OciUserMapper userMapper;
    @Resource
    private OciReadCacheService ociReadCacheService;

    private OciClientService oci(OciUser ociUser, String region) {
        String r = (region == null || region.isBlank()) ? null : region.trim();
        return new OciClientService(buildBasicDTO(ociUser), r);
    }

    // ---------------- VCN ----------------

    public List<Map<String, Object>> listVcns(String userId, String region) {
        return listVcns(userId, region, false);
    }

    public List<Map<String, Object>> listVcns(String userId, String region, boolean force) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        return ociReadCacheService.get(vcnListCacheKey(ociUser, region), VCN_READ_CACHE_TTL, force, () -> fetchVcns(ociUser, region));
    }

    private List<Map<String, Object>> fetchVcns(OciUser ociUser, String region) {
        String r = effectiveRegion(ociUser, region);
        try (OciClientService client = oci(ociUser, region)) {
            var compartments = client.listAllCompartments();
            List<Map<String, Object>> result = new ArrayList<>();
            for (var c : compartments) {
                try {
                    var vcns = client.getVirtualNetworkClient().listVcns(
                            ListVcnsRequest.builder().compartmentId(c.getId()).build()
                    ).getItems();
                    for (var v : vcns) {
                        if (v.getLifecycleState() == Vcn.LifecycleState.Terminated) continue;
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("id", v.getId());
                        map.put("displayName", v.getDisplayName());
                        map.put("cidrBlock", v.getCidrBlock());
                        map.put("cidrBlocks", v.getCidrBlocks());
                        map.put("ipv6CidrBlocks", v.getIpv6CidrBlocks());
                        map.put("dnsLabel", v.getDnsLabel());
                        map.put("vcnDomainName", v.getVcnDomainName());
                        map.put("lifecycleState", v.getLifecycleState() != null ? v.getLifecycleState().getValue() : null);
                        map.put("compartmentId", c.getId());
                        map.put("compartmentName", c.getName());
                        map.put("timeCreated", v.getTimeCreated() != null ? v.getTimeCreated().toString() : null);
                        map.put("region", r);
                        result.add(map);
                    }
                } catch (Exception e) {
                    log.debug("listVcns in {} failed: {}", c.getId(), e.getMessage());
                }
            }
            return result;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("查询 VCN 列表失败: " + e.getMessage());
        }
    }

    public Map<String, Object> createVcn(String userId, String compartmentId, String displayName, String cidrBlock,
                                         String dnsLabel, boolean createIgw, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService client = oci(ociUser, region)) {
            String cid = compartmentId == null || compartmentId.isBlank() ? client.getProvider().getTenantId() : compartmentId;
            Vcn vcn = client.getVirtualNetworkClient().createVcn(
                    CreateVcnRequest.builder().createVcnDetails(
                            CreateVcnDetails.builder()
                                    .compartmentId(cid)
                                    .displayName(displayName)
                                    .cidrBlock(cidrBlock)
                                    .dnsLabel(dnsLabel)
                                    .build()
                    ).build()
            ).getVcn();
            String igwId = null;
            if (createIgw) {
                try {
                    igwId = client.getVirtualNetworkClient().createInternetGateway(
                            CreateInternetGatewayRequest.builder().createInternetGatewayDetails(
                                    CreateInternetGatewayDetails.builder()
                                            .compartmentId(cid)
                                            .vcnId(vcn.getId())
                                            .isEnabled(true)
                                            .displayName("default-igw")
                                            .build()
                            ).build()
                    ).getInternetGateway().getId();
                } catch (Exception e) {
                    log.warn("createInternetGateway failed: {}", e.getMessage());
                }
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", vcn.getId());
            map.put("internetGatewayId", igwId);
            evictVcnReadCaches(userId, region);
            return map;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("创建 VCN 失败: " + e.getMessage());
        }
    }

    public Map<String, Object> previewVcnDelete(String userId, String vcnId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            Vcn vcn = client.getVirtualNetworkClient().getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();
            String cid = vcn.getCompartmentId();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("subnets", listMapped(client.getVirtualNetworkClient().listSubnets(
                    ListSubnetsRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems(),
                    Subnet::getId, Subnet::getDisplayName));
            map.put("internetGateways", listMapped(client.getVirtualNetworkClient().listInternetGateways(
                    ListInternetGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems(),
                    InternetGateway::getId, InternetGateway::getDisplayName));
            map.put("natGateways", listMapped(client.getVirtualNetworkClient().listNatGateways(
                    ListNatGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems(),
                    NatGateway::getId, NatGateway::getDisplayName));
            map.put("serviceGateways", listMapped(client.getVirtualNetworkClient().listServiceGateways(
                    ListServiceGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems(),
                    ServiceGateway::getId, ServiceGateway::getDisplayName));
            map.put("localPeeringGateways", listMapped(client.getVirtualNetworkClient().listLocalPeeringGateways(
                    ListLocalPeeringGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems(),
                    LocalPeeringGateway::getId, LocalPeeringGateway::getDisplayName));
            map.put("routeTables", listMapped(client.getVirtualNetworkClient().listRouteTables(
                    ListRouteTablesRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems(),
                    RouteTable::getId, RouteTable::getDisplayName));
            map.put("securityLists", listMapped(client.getVirtualNetworkClient().listSecurityLists(
                    ListSecurityListsRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems(),
                    SecurityList::getId, SecurityList::getDisplayName));
            return map;
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("查询 VCN 子资源失败: " + e.getMessage()); }
    }

    public void deleteVcn(String userId, String vcnId, boolean cascade, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            Vcn vcn = client.getVirtualNetworkClient().getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();
            if (cascade) {
                cascadeDeleteVcnChildren(client, vcn);
            }
            client.getVirtualNetworkClient().deleteVcn(DeleteVcnRequest.builder().vcnId(vcnId).build());
            log.info("VCN deleted: {}", vcnId);
            evictVcnReadCaches(userId, region);
        } catch (OciException e) {
            throw e;
        } catch (com.oracle.bmc.model.BmcException e) {
            if (e.getStatusCode() == 409) {
                throw new OciException("删除 VCN 失败：仍有子资源未清理。"
                        + summarizeRemainingVcnChildren(userId, vcnId, region));
            }
            throw new OciException("删除 VCN 失败: " + briefBmcMessage(e));
        } catch (Exception e) {
            throw new OciException("删除 VCN 失败: " + e.getMessage());
        }
    }

    /**
     * OCI 要求：先删子网 → 去掉路由表中指向网关的规则 → 删网关 → 删非默认路由表/安全列表 → 再删 VCN。
     * 默认路由表/默认安全列表随 VCN 删除，勿单独 Delete。
     */
    private void cascadeDeleteVcnChildren(OciClientService client, Vcn vcn) {
        String cid = vcn.getCompartmentId();
        String vcnId = vcn.getId();
        var net = client.getVirtualNetworkClient();

        Set<String> gatewayIds = collectVcnGatewayIds(net, cid, vcnId);

        for (Subnet s : net.listSubnets(ListSubnetsRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
            if (s.getLifecycleState() == Subnet.LifecycleState.Terminated) {
                continue;
            }
            try {
                net.deleteSubnet(DeleteSubnetRequest.builder().subnetId(s.getId()).build());
                log.info("cascade delete subnet: {}", s.getDisplayName());
            } catch (Exception e) {
                throw new OciException("级联删除子网失败（" + s.getDisplayName() + "）: " + e.getMessage()
                        + "。请先终止使用该子网的实例或负载均衡。");
            }
        }

        clearRouteRulesReferencingGateways(net, cid, vcnId, gatewayIds);

        for (InternetGateway ig : net.listInternetGateways(
                ListInternetGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
            if (ig.getLifecycleState() == InternetGateway.LifecycleState.Terminated) {
                continue;
            }
            deleteOrThrow(() -> net.deleteInternetGateway(
                    DeleteInternetGatewayRequest.builder().igId(ig.getId()).build()),
                    "Internet Gateway", ig.getDisplayName());
        }
        for (NatGateway ng : net.listNatGateways(
                ListNatGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
            if (ng.getLifecycleState() == NatGateway.LifecycleState.Terminated) {
                continue;
            }
            deleteOrThrow(() -> net.deleteNatGateway(
                    DeleteNatGatewayRequest.builder().natGatewayId(ng.getId()).build()),
                    "NAT Gateway", ng.getDisplayName());
        }
        for (ServiceGateway sg : net.listServiceGateways(
                ListServiceGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
            if (sg.getLifecycleState() == ServiceGateway.LifecycleState.Terminated) {
                continue;
            }
            deleteOrThrow(() -> net.deleteServiceGateway(
                    DeleteServiceGatewayRequest.builder().serviceGatewayId(sg.getId()).build()),
                    "Service Gateway", sg.getDisplayName());
        }
        for (LocalPeeringGateway lpg : net.listLocalPeeringGateways(
                ListLocalPeeringGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
            if (lpg.getLifecycleState() == LocalPeeringGateway.LifecycleState.Terminated) {
                continue;
            }
            deleteOrThrow(() -> net.deleteLocalPeeringGateway(
                    DeleteLocalPeeringGatewayRequest.builder().localPeeringGatewayId(lpg.getId()).build()),
                    "Local Peering Gateway", lpg.getDisplayName());
        }

        String defaultRtId = vcn.getDefaultRouteTableId();
        for (RouteTable rt : net.listRouteTables(
                ListRouteTablesRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
            if (rt.getId().equals(defaultRtId)) {
                continue;
            }
            if (rt.getLifecycleState() == RouteTable.LifecycleState.Terminated) {
                continue;
            }
            try {
                net.deleteRouteTable(DeleteRouteTableRequest.builder().rtId(rt.getId()).build());
            } catch (Exception e) {
                log.debug("deleteRouteTable {} skipped: {}", rt.getDisplayName(), e.getMessage());
            }
        }

        String defaultSlId = vcn.getDefaultSecurityListId();
        for (SecurityList sl : net.listSecurityLists(
                ListSecurityListsRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
            if (sl.getId().equals(defaultSlId)) {
                continue;
            }
            if (sl.getLifecycleState() == SecurityList.LifecycleState.Terminated) {
                continue;
            }
            try {
                net.deleteSecurityList(DeleteSecurityListRequest.builder().securityListId(sl.getId()).build());
            } catch (Exception e) {
                log.debug("deleteSecurityList {} skipped: {}", sl.getDisplayName(), e.getMessage());
            }
        }
    }

    private static Set<String> collectVcnGatewayIds(
            com.oracle.bmc.core.VirtualNetworkClient net, String compartmentId, String vcnId) {
        Set<String> ids = new HashSet<>();
        try {
            for (InternetGateway ig : net.listInternetGateways(
                    ListInternetGatewaysRequest.builder().compartmentId(compartmentId).vcnId(vcnId).build()).getItems()) {
                ids.add(ig.getId());
            }
            for (NatGateway ng : net.listNatGateways(
                    ListNatGatewaysRequest.builder().compartmentId(compartmentId).vcnId(vcnId).build()).getItems()) {
                ids.add(ng.getId());
            }
            for (ServiceGateway sg : net.listServiceGateways(
                    ListServiceGatewaysRequest.builder().compartmentId(compartmentId).vcnId(vcnId).build()).getItems()) {
                ids.add(sg.getId());
            }
            for (LocalPeeringGateway lpg : net.listLocalPeeringGateways(
                    ListLocalPeeringGatewaysRequest.builder().compartmentId(compartmentId).vcnId(vcnId).build()).getItems()) {
                ids.add(lpg.getId());
            }
        } catch (Exception e) {
            log.warn("collectVcnGatewayIds failed: {}", e.getMessage());
        }
        return ids;
    }

    /** 删除网关前须去掉所有路由表中指向该网关的规则（含默认路由表）。 */
    private static void clearRouteRulesReferencingGateways(
            com.oracle.bmc.core.VirtualNetworkClient net,
            String compartmentId,
            String vcnId,
            Set<String> gatewayIds) {
        if (gatewayIds.isEmpty()) {
            return;
        }
        try {
            for (RouteTable rt : net.listRouteTables(
                    ListRouteTablesRequest.builder().compartmentId(compartmentId).vcnId(vcnId).build()).getItems()) {
                List<RouteRule> rules = rt.getRouteRules();
                if (rules == null || rules.isEmpty()) {
                    continue;
                }
                List<RouteRule> kept = new ArrayList<>();
                boolean removed = false;
                for (RouteRule r : rules) {
                    String target = r.getNetworkEntityId();
                    if (target != null && gatewayIds.contains(target)) {
                        removed = true;
                        continue;
                    }
                    kept.add(r);
                }
                if (!removed) {
                    continue;
                }
                net.updateRouteTable(UpdateRouteTableRequest.builder()
                        .rtId(rt.getId())
                        .updateRouteTableDetails(UpdateRouteTableDetails.builder().routeRules(kept).build())
                        .build());
                log.info("cleared gateway routes on route table: {}", rt.getDisplayName());
            }
        } catch (Exception e) {
            throw new OciException("级联删除：清理路由表规则失败: " + e.getMessage());
        }
    }

    private static void deleteOrThrow(Runnable delete, String resourceType, String displayName) {
        try {
            delete.run();
            log.info("cascade deleted {}: {}", resourceType, displayName);
        } catch (Exception e) {
            throw new OciException("级联删除 " + resourceType + "（" + displayName + "）失败: " + e.getMessage());
        }
    }

    private String summarizeRemainingVcnChildren(String userId, String vcnId, String region) {
        try {
            Map<String, Object> left = previewVcnDelete(userId, vcnId, region);
            List<String> parts = new ArrayList<>();
            appendRemaining(parts, "子网", left.get("subnets"));
            appendRemaining(parts, "Internet Gateway", left.get("internetGateways"));
            appendRemaining(parts, "NAT Gateway", left.get("natGateways"));
            appendRemaining(parts, "Service Gateway", left.get("serviceGateways"));
            appendRemaining(parts, "Local Peering Gateway", left.get("localPeeringGateways"));
            appendRemaining(parts, "路由表", left.get("routeTables"));
            appendRemaining(parts, "安全列表", left.get("securityLists"));
            if (parts.isEmpty()) {
                return "";
            }
            return " 剩余: " + String.join("；", parts);
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static void appendRemaining(List<String> parts, String label, Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Object n = m.get("displayName");
                if (n != null && !String.valueOf(n).isBlank()) {
                    names.add(String.valueOf(n));
                }
            }
        }
        if (!names.isEmpty()) {
            parts.add(label + "(" + String.join(", ", names) + ")");
        }
    }

    private static String briefBmcMessage(com.oracle.bmc.model.BmcException e) {
        return OciBmcErrorTranslator.translate(e);
    }

    // ---------------- Subnets ----------------

    public List<Map<String, Object>> listSubnets(String userId, String vcnId, String region) {
        return listSubnets(userId, vcnId, region, false);
    }

    public List<Map<String, Object>> listSubnets(String userId, String vcnId, String region, boolean force) {
        return listChildren("subnets", userId, vcnId, region, force, (client, cid) -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (var s : client.getVirtualNetworkClient().listSubnets(
                    ListSubnetsRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                if (s.getLifecycleState() == Subnet.LifecycleState.Terminated) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.getId());
                m.put("displayName", s.getDisplayName());
                m.put("cidrBlock", s.getCidrBlock());
                m.put("ipv6CidrBlock", s.getIpv6CidrBlock());
                m.put("availabilityDomain", s.getAvailabilityDomain());
                m.put("prohibitPublicIpOnVnic", s.getProhibitPublicIpOnVnic());
                m.put("routeTableId", s.getRouteTableId());
                m.put("dhcpOptionsId", s.getDhcpOptionsId());
                m.put("securityListIds", s.getSecurityListIds());
                m.put("lifecycleState", s.getLifecycleState() != null ? s.getLifecycleState().getValue() : null);
                m.put("timeCreated", s.getTimeCreated() != null ? s.getTimeCreated().toString() : null);
                list.add(m);
            }
            return list;
        });
    }

    public void createSubnet(String userId, String vcnId, String displayName, String cidrBlock,
                             String availabilityDomain, String routeTableId, Boolean prohibitPublicIp, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            Vcn vcn = client.getVirtualNetworkClient().getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();
            CreateSubnetDetails.Builder b = CreateSubnetDetails.builder()
                    .compartmentId(vcn.getCompartmentId())
                    .vcnId(vcnId)
                    .displayName(displayName)
                    .cidrBlock(cidrBlock);
            if (availabilityDomain != null && !availabilityDomain.isBlank()) b.availabilityDomain(availabilityDomain);
            if (routeTableId != null && !routeTableId.isBlank()) b.routeTableId(routeTableId);
            if (prohibitPublicIp != null) b.prohibitPublicIpOnVnic(prohibitPublicIp);
            client.getVirtualNetworkClient().createSubnet(CreateSubnetRequest.builder().createSubnetDetails(b.build()).build());
            evictVcnReadCaches(userId, region);
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("创建子网失败: " + e.getMessage()); }
    }

    public void deleteSubnet(String userId, String subnetId, String region) {
        deleteResource(userId, region, () -> "deleteSubnet", (client) ->
                client.getVirtualNetworkClient().deleteSubnet(DeleteSubnetRequest.builder().subnetId(subnetId).build()));
        evictVcnReadCaches(userId, region);
    }

    public void updateSubnet(String userId, String subnetId, String displayName, String routeTableId, List<String> securityListIds, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            UpdateSubnetDetails.Builder b = UpdateSubnetDetails.builder();
            if (displayName != null && !displayName.isBlank()) b.displayName(displayName);
            if (routeTableId != null && !routeTableId.isBlank()) b.routeTableId(routeTableId);
            if (securityListIds != null && !securityListIds.isEmpty()) b.securityListIds(securityListIds);
            client.getVirtualNetworkClient().updateSubnet(
                    UpdateSubnetRequest.builder().subnetId(subnetId)
                            .updateSubnetDetails(b.build()).build());
            evictVcnReadCaches(userId, region);
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("更新子网失败: " + e.getMessage()); }
    }

    // ---------------- Internet Gateway ----------------

    public List<Map<String, Object>> listInternetGateways(String userId, String vcnId, String region) {
        return listInternetGateways(userId, vcnId, region, false);
    }

    public List<Map<String, Object>> listInternetGateways(String userId, String vcnId, String region, boolean force) {
        return listChildren("igw", userId, vcnId, region, force, (client, cid) -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (var ig : client.getVirtualNetworkClient().listInternetGateways(
                    ListInternetGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                if (ig.getLifecycleState() == InternetGateway.LifecycleState.Terminated) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", ig.getId());
                m.put("displayName", ig.getDisplayName());
                m.put("isEnabled", ig.getIsEnabled());
                m.put("lifecycleState", ig.getLifecycleState() != null ? ig.getLifecycleState().getValue() : null);
                m.put("timeCreated", ig.getTimeCreated() != null ? ig.getTimeCreated().toString() : null);
                list.add(m);
            }
            return list;
        });
    }

    public void createInternetGateway(String userId, String vcnId, String displayName, boolean enabled, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            Vcn vcn = client.getVirtualNetworkClient().getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();
            client.getVirtualNetworkClient().createInternetGateway(
                    CreateInternetGatewayRequest.builder().createInternetGatewayDetails(
                            CreateInternetGatewayDetails.builder()
                                    .compartmentId(vcn.getCompartmentId())
                                    .vcnId(vcnId)
                                    .displayName(displayName)
                                    .isEnabled(enabled)
                                    .build()
                    ).build());
            evictVcnReadCaches(userId, region);
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("创建 Internet Gateway 失败: " + e.getMessage()); }
    }

    public void deleteInternetGateway(String userId, String igId, String region) {
        deleteResource(userId, region, () -> "deleteInternetGateway", (client) ->
                client.getVirtualNetworkClient().deleteInternetGateway(DeleteInternetGatewayRequest.builder().igId(igId).build()));
        evictVcnReadCaches(userId, region);
    }

    public void updateInternetGateway(String userId, String igId, String displayName, Boolean isEnabled, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            UpdateInternetGatewayDetails.Builder b = UpdateInternetGatewayDetails.builder();
            if (displayName != null && !displayName.isBlank()) b.displayName(displayName);
            if (isEnabled != null) b.isEnabled(isEnabled);
            client.getVirtualNetworkClient().updateInternetGateway(
                    UpdateInternetGatewayRequest.builder().igId(igId)
                            .updateInternetGatewayDetails(b.build()).build());
            evictVcnReadCaches(userId, region);
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("更新 IGW 失败: " + e.getMessage()); }
    }

    // ---------------- NAT Gateway ----------------

    public List<Map<String, Object>> listNatGateways(String userId, String vcnId, String region) {
        return listNatGateways(userId, vcnId, region, false);
    }

    public List<Map<String, Object>> listNatGateways(String userId, String vcnId, String region, boolean force) {
        return listChildren("nat", userId, vcnId, region, force, (client, cid) -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (var ng : client.getVirtualNetworkClient().listNatGateways(
                    ListNatGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                if (ng.getLifecycleState() == NatGateway.LifecycleState.Terminated) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", ng.getId());
                m.put("displayName", ng.getDisplayName());
                m.put("natIp", ng.getNatIp());
                m.put("blockTraffic", ng.getBlockTraffic());
                m.put("lifecycleState", ng.getLifecycleState() != null ? ng.getLifecycleState().getValue() : null);
                m.put("timeCreated", ng.getTimeCreated() != null ? ng.getTimeCreated().toString() : null);
                list.add(m);
            }
            return list;
        });
    }

    public void createNatGateway(String userId, String vcnId, String displayName, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            Vcn vcn = client.getVirtualNetworkClient().getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();
            client.getVirtualNetworkClient().createNatGateway(
                    CreateNatGatewayRequest.builder().createNatGatewayDetails(
                            CreateNatGatewayDetails.builder()
                                    .compartmentId(vcn.getCompartmentId())
                                    .vcnId(vcnId)
                                    .displayName(displayName)
                                    .build()
                    ).build());
            evictVcnReadCaches(userId, region);
        } catch (OciException e) { throw e; }
        catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException("创建 NAT 网关失败: " + OciBmcErrorTranslator.translate(e));
        } catch (Exception e) {
            throw new OciException("创建 NAT 网关失败: " + OciBmcErrorTranslator.translate(e));
        }
    }

    public void deleteNatGateway(String userId, String natId, String region) {
        deleteResource(userId, region, () -> "deleteNatGateway", (client) ->
                client.getVirtualNetworkClient().deleteNatGateway(DeleteNatGatewayRequest.builder().natGatewayId(natId).build()));
        evictVcnReadCaches(userId, region);
    }

    public void updateNatGateway(String userId, String natId, String displayName, Boolean blockTraffic, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            UpdateNatGatewayDetails.Builder b = UpdateNatGatewayDetails.builder();
            if (displayName != null && !displayName.isBlank()) b.displayName(displayName);
            if (blockTraffic != null) b.blockTraffic(blockTraffic);
            client.getVirtualNetworkClient().updateNatGateway(
                    UpdateNatGatewayRequest.builder().natGatewayId(natId)
                            .updateNatGatewayDetails(b.build()).build());
            evictVcnReadCaches(userId, region);
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("更新 NAT 失败: " + e.getMessage()); }
    }

    // ---------------- Service Gateway ----------------

    public List<Map<String, Object>> listServiceGateways(String userId, String vcnId, String region) {
        return listServiceGateways(userId, vcnId, region, false);
    }

    public List<Map<String, Object>> listServiceGateways(String userId, String vcnId, String region, boolean force) {
        return listChildren("sg", userId, vcnId, region, force, (client, cid) -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (var sg : client.getVirtualNetworkClient().listServiceGateways(
                    ListServiceGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                if (sg.getLifecycleState() == ServiceGateway.LifecycleState.Terminated) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", sg.getId());
                m.put("displayName", sg.getDisplayName());
                m.put("blockTraffic", sg.getBlockTraffic());
                m.put("services", sg.getServices());
                m.put("lifecycleState", sg.getLifecycleState() != null ? sg.getLifecycleState().getValue() : null);
                m.put("timeCreated", sg.getTimeCreated() != null ? sg.getTimeCreated().toString() : null);
                list.add(m);
            }
            return list;
        });
    }

    public void createServiceGateway(String userId, String vcnId, String displayName, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            Vcn vcn = client.getVirtualNetworkClient().getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();
            // Find the "All <region> Services In Oracle Services Network" service
            var services = client.getVirtualNetworkClient().listServices(
                    ListServicesRequest.builder().build()).getItems();
            List<ServiceIdRequestDetails> serviceIds = new ArrayList<>();
            for (var s : services) {
                if (s.getName() != null && s.getName().toLowerCase().contains("all") && s.getName().toLowerCase().contains("services")) {
                    serviceIds.add(ServiceIdRequestDetails.builder().serviceId(s.getId()).build());
                    break;
                }
            }
            client.getVirtualNetworkClient().createServiceGateway(
                    CreateServiceGatewayRequest.builder().createServiceGatewayDetails(
                            CreateServiceGatewayDetails.builder()
                                    .compartmentId(vcn.getCompartmentId())
                                    .vcnId(vcnId)
                                    .displayName(displayName)
                                    .services(serviceIds)
                                    .build()
                    ).build());
            evictVcnReadCaches(userId, region);
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("创建 Service Gateway 失败: " + e.getMessage()); }
    }

    public void deleteServiceGateway(String userId, String sgId, String region) {
        deleteResource(userId, region, () -> "deleteServiceGateway", (client) ->
                client.getVirtualNetworkClient().deleteServiceGateway(DeleteServiceGatewayRequest.builder().serviceGatewayId(sgId).build()));
        evictVcnReadCaches(userId, region);
    }

    public void updateServiceGateway(String userId, String sgId, String displayName, Boolean blockTraffic, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            UpdateServiceGatewayDetails.Builder b = UpdateServiceGatewayDetails.builder();
            if (displayName != null && !displayName.isBlank()) b.displayName(displayName);
            if (blockTraffic != null) b.blockTraffic(blockTraffic);
            client.getVirtualNetworkClient().updateServiceGateway(
                    UpdateServiceGatewayRequest.builder().serviceGatewayId(sgId)
                            .updateServiceGatewayDetails(b.build()).build());
            evictVcnReadCaches(userId, region);
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("更新 SG 失败: " + e.getMessage()); }
    }

    // ---------------- Route Table ----------------

    public List<Map<String, Object>> listRouteTables(String userId, String vcnId, String region) {
        return listRouteTables(userId, vcnId, region, false);
    }

    public List<Map<String, Object>> listRouteTables(String userId, String vcnId, String region, boolean force) {
        return listChildren("rt", userId, vcnId, region, force, (client, cid) -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (var rt : client.getVirtualNetworkClient().listRouteTables(
                    ListRouteTablesRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                if (rt.getLifecycleState() == RouteTable.LifecycleState.Terminated) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rt.getId());
                m.put("displayName", rt.getDisplayName());
                List<Map<String, Object>> rules = new ArrayList<>();
                if (rt.getRouteRules() != null) {
                    for (RouteRule r : rt.getRouteRules()) {
                        rules.add(routeRuleMap(r));
                    }
                }
                m.put("routeRules", rules);
                m.put("lifecycleState", rt.getLifecycleState() != null ? rt.getLifecycleState().getValue() : null);
                m.put("timeCreated", rt.getTimeCreated() != null ? rt.getTimeCreated().toString() : null);
                list.add(m);
            }
            return list;
        });
    }

    public void deleteRouteTable(String userId, String rtId, String region) {
        deleteResource(userId, region, () -> "deleteRouteTable", (client) ->
                client.getVirtualNetworkClient().deleteRouteTable(DeleteRouteTableRequest.builder().rtId(rtId).build()));
        evictVcnReadCaches(userId, region);
    }

    public void updateRouteTable(String userId, String rtId, String displayName, List<Map<String, Object>> routeRules, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            UpdateRouteTableDetails.Builder b = UpdateRouteTableDetails.builder();
            if (displayName != null && !displayName.isBlank()) b.displayName(displayName);
            if (routeRules != null) {
                List<RouteRule> rules = new ArrayList<>();
                for (Map<String, Object> r : routeRules) {
                    String destination = firstNonBlank(asStr(r.get("destination")), asStr(r.get("cidrBlock")));
                    String networkEntityId = normalizeBlank(asStr(r.get("networkEntityId")));
                    if (destination == null || networkEntityId == null) {
                        throw new OciException("路由规则的目标和下一跳不能为空");
                    }
                    RouteRule.Builder rb = RouteRule.builder()
                            .destination(destination)
                            .networkEntityId(networkEntityId)
                            .description(normalizeBlank(asStr(r.get("description"))));
                    String dstType = asStr(r.get("destinationType"));
                    if (dstType != null && !dstType.isBlank()) {
                        try { rb.destinationType(RouteRule.DestinationType.create(dstType)); } catch (Exception ignored) {}
                    }
                    String routeType = asStr(r.get("routeType"));
                    if (routeType != null && !routeType.isBlank()) {
                        try { rb.routeType(RouteRule.RouteType.create(routeType)); } catch (Exception ignored) {}
                    }
                    rules.add(rb.build());
                }
                b.routeRules(rules);
            }
            client.getVirtualNetworkClient().updateRouteTable(
                    UpdateRouteTableRequest.builder().rtId(rtId)
                            .updateRouteTableDetails(b.build()).build());
            evictVcnReadCaches(userId, region);
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("更新路由表失败: " + e.getMessage()); }
    }

    public Map<String, Object> getRouteTable(String userId, String rtId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            RouteTable rt = client.getVirtualNetworkClient().getRouteTable(
                    GetRouteTableRequest.builder().rtId(rtId).build()).getRouteTable();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rt.getId());
            m.put("displayName", rt.getDisplayName());
            m.put("lifecycleState", rt.getLifecycleState() != null ? rt.getLifecycleState().getValue() : null);
            List<Map<String, Object>> rules = new ArrayList<>();
            if (rt.getRouteRules() != null) {
                for (RouteRule r : rt.getRouteRules()) {
                    rules.add(routeRuleMap(r));
                }
            }
            m.put("routeRules", rules);
            return m;
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("查询路由表失败: " + e.getMessage()); }
    }

    public Map<String, Object> listRouteRuleOptions(String userId, String vcnId, String targetCompartmentId,
                                                    String region, boolean force) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        String targetCid = normalizeBlank(targetCompartmentId);
        String cacheKey = OciReadCacheService.key(
                vcnChildCacheKey("rtRuleOptions", ociUser, vcnId, region),
                targetCid == null ? "default" : targetCid
        );
        return ociReadCacheService.get(cacheKey, VCN_READ_CACHE_TTL, force,
                () -> fetchRouteRuleOptions(ociUser, vcnId, targetCid, region));
    }

    private Map<String, Object> fetchRouteRuleOptions(OciUser ociUser, String vcnId, String targetCompartmentId,
                                                      String region) {
        try (OciClientService client = oci(ociUser, region)) {
            VirtualNetworkClient net = client.getVirtualNetworkClient();
            Vcn vcn = net.getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();
            String defaultCompartmentId = vcn.getCompartmentId();
            String selectedCompartmentId = firstNonBlank(targetCompartmentId, defaultCompartmentId);

            List<Map<String, Object>> compartments = new ArrayList<>();
            Map<String, String> compartmentNames = new HashMap<>();
            for (var c : client.listAllCompartments()) {
                if (c == null || c.getId() == null) continue;
                String name = firstNonBlank(c.getName(), c.getId());
                compartmentNames.put(c.getId(), name);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("name", name);
                m.put("displayName", name);
                m.put("parentCompartmentId", c.getCompartmentId());
                compartments.add(m);
            }
            if (!compartmentNames.containsKey(selectedCompartmentId)) {
                compartmentNames.put(selectedCompartmentId, selectedCompartmentId);
            }

            List<Map<String, Object>> targetGroups = new ArrayList<>();
            List<Map<String, Object>> warnings = new ArrayList<>();
            addRouteTargetGroup(targetGroups, warnings, "drg", "动态路由网关",
                    () -> listDrgRouteTargets(net, selectedCompartmentId, compartmentNames));
            addRouteTargetGroup(targetGroups, warnings, "internetGateway", "Internet 网关",
                    () -> listInternetGatewayRouteTargets(net, selectedCompartmentId, vcnId, compartmentNames));
            addRouteTargetGroup(targetGroups, warnings, "localPeeringGateway", "本地对等连接网关",
                    () -> listLocalPeeringGatewayRouteTargets(net, selectedCompartmentId, vcnId, compartmentNames));
            addRouteTargetGroup(targetGroups, warnings, "natGateway", "NAT 网关",
                    () -> listNatGatewayRouteTargets(net, selectedCompartmentId, vcnId, compartmentNames));
            addRouteTargetGroup(targetGroups, warnings, "privateIp", "专用 IP",
                    () -> listPrivateIpRouteTargets(net, selectedCompartmentId, vcnId, compartmentNames));
            addRouteTargetGroup(targetGroups, warnings, "serviceGateway", "服务网关",
                    () -> listServiceGatewayRouteTargets(net, selectedCompartmentId, vcnId, compartmentNames));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("vcn", vcnSummary(vcn));
            result.put("compartments", compartments);
            result.put("defaultCompartmentId", defaultCompartmentId);
            result.put("selectedCompartmentId", selectedCompartmentId);
            result.put("targetGroups", targetGroups);
            result.put("services", listRouteServices(net));
            result.put("warnings", warnings);
            return result;
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("查询路由规则选项失败: " + e.getMessage()); }
    }

    private Map<String, Object> routeRuleMap(RouteRule r) {
        Map<String, Object> rr = new LinkedHashMap<>();
        rr.put("cidrBlock", r.getCidrBlock());
        rr.put("destination", firstNonBlank(r.getDestination(), r.getCidrBlock()));
        rr.put("destinationType", r.getDestinationType() != null ? r.getDestinationType().getValue() : null);
        rr.put("networkEntityId", r.getNetworkEntityId());
        rr.put("description", r.getDescription());
        rr.put("routeType", r.getRouteType() != null ? r.getRouteType().getValue() : null);
        return rr;
    }

    private Map<String, Object> vcnSummary(Vcn vcn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", vcn.getId());
        m.put("displayName", vcn.getDisplayName());
        m.put("compartmentId", vcn.getCompartmentId());
        m.put("cidrBlock", vcn.getCidrBlock());
        m.put("cidrBlocks", vcn.getCidrBlocks());
        m.put("ipv6CidrBlocks", vcn.getIpv6CidrBlocks());
        return m;
    }

    private void addRouteTargetGroup(List<Map<String, Object>> groups, List<Map<String, Object>> warnings,
                                     String type, String label, RouteTargetFetcher fetcher) {
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            items = fetcher.fetch();
        } catch (Exception e) {
            Map<String, Object> warning = new LinkedHashMap<>();
            warning.put("type", type);
            warning.put("label", label);
            warning.put("message", e.getMessage());
            warnings.add(warning);
        }
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("type", type);
        group.put("label", label);
        group.put("items", items);
        groups.add(group);
    }

    private List<Map<String, Object>> listDrgRouteTargets(VirtualNetworkClient net, String compartmentId,
                                                          Map<String, String> compartmentNames) {
        List<Map<String, Object>> list = new ArrayList<>();
        String page = null;
        do {
            var resp = net.listDrgs(ListDrgsRequest.builder()
                    .compartmentId(compartmentId).limit(1000).page(page).build());
            for (var drg : resp.getItems()) {
                if (drg.getLifecycleState() == Drg.LifecycleState.Terminated) continue;
                Map<String, Object> m = routeTargetOption("drg", drg.getId(), drg.getDisplayName(),
                        compartmentId, compartmentNames, drg.getLifecycleState() != null ? drg.getLifecycleState().getValue() : null);
                list.add(m);
            }
            page = resp.getOpcNextPage();
        } while (page != null);
        return list;
    }

    private List<Map<String, Object>> listInternetGatewayRouteTargets(VirtualNetworkClient net, String compartmentId,
                                                                      String vcnId, Map<String, String> compartmentNames) {
        List<Map<String, Object>> list = new ArrayList<>();
        String page = null;
        do {
            var resp = net.listInternetGateways(ListInternetGatewaysRequest.builder()
                    .compartmentId(compartmentId).vcnId(vcnId).limit(1000).page(page).build());
            for (var ig : resp.getItems()) {
                if (ig.getLifecycleState() == InternetGateway.LifecycleState.Terminated) continue;
                Map<String, Object> m = routeTargetOption("internetGateway", ig.getId(), ig.getDisplayName(),
                        compartmentId, compartmentNames, ig.getLifecycleState() != null ? ig.getLifecycleState().getValue() : null);
                m.put("isEnabled", ig.getIsEnabled());
                list.add(m);
            }
            page = resp.getOpcNextPage();
        } while (page != null);
        return list;
    }

    private List<Map<String, Object>> listNatGatewayRouteTargets(VirtualNetworkClient net, String compartmentId,
                                                                 String vcnId, Map<String, String> compartmentNames) {
        List<Map<String, Object>> list = new ArrayList<>();
        String page = null;
        do {
            var resp = net.listNatGateways(ListNatGatewaysRequest.builder()
                    .compartmentId(compartmentId).vcnId(vcnId).limit(1000).page(page).build());
            for (var nat : resp.getItems()) {
                if (nat.getLifecycleState() == NatGateway.LifecycleState.Terminated) continue;
                Map<String, Object> m = routeTargetOption("natGateway", nat.getId(), nat.getDisplayName(),
                        compartmentId, compartmentNames, nat.getLifecycleState() != null ? nat.getLifecycleState().getValue() : null);
                m.put("blockTraffic", nat.getBlockTraffic());
                m.put("natIp", nat.getNatIp());
                list.add(m);
            }
            page = resp.getOpcNextPage();
        } while (page != null);
        return list;
    }

    private List<Map<String, Object>> listServiceGatewayRouteTargets(VirtualNetworkClient net, String compartmentId,
                                                                     String vcnId, Map<String, String> compartmentNames) {
        List<Map<String, Object>> list = new ArrayList<>();
        String page = null;
        do {
            var resp = net.listServiceGateways(ListServiceGatewaysRequest.builder()
                    .compartmentId(compartmentId).vcnId(vcnId).limit(1000).page(page).build());
            for (var sg : resp.getItems()) {
                if (sg.getLifecycleState() == ServiceGateway.LifecycleState.Terminated) continue;
                Map<String, Object> m = routeTargetOption("serviceGateway", sg.getId(), sg.getDisplayName(),
                        compartmentId, compartmentNames, sg.getLifecycleState() != null ? sg.getLifecycleState().getValue() : null);
                m.put("blockTraffic", sg.getBlockTraffic());
                m.put("services", sg.getServices());
                list.add(m);
            }
            page = resp.getOpcNextPage();
        } while (page != null);
        return list;
    }

    private List<Map<String, Object>> listLocalPeeringGatewayRouteTargets(VirtualNetworkClient net, String compartmentId,
                                                                          String vcnId, Map<String, String> compartmentNames) {
        List<Map<String, Object>> list = new ArrayList<>();
        String page = null;
        do {
            var resp = net.listLocalPeeringGateways(ListLocalPeeringGatewaysRequest.builder()
                    .compartmentId(compartmentId).vcnId(vcnId).limit(1000).page(page).build());
            for (var lpg : resp.getItems()) {
                if (lpg.getLifecycleState() == LocalPeeringGateway.LifecycleState.Terminated) continue;
                Map<String, Object> m = routeTargetOption("localPeeringGateway", lpg.getId(), lpg.getDisplayName(),
                        compartmentId, compartmentNames, lpg.getLifecycleState() != null ? lpg.getLifecycleState().getValue() : null);
                m.put("peeringStatus", lpg.getPeeringStatus() != null ? lpg.getPeeringStatus().getValue() : null);
                m.put("peerAdvertisedCidr", lpg.getPeerAdvertisedCidr());
                list.add(m);
            }
            page = resp.getOpcNextPage();
        } while (page != null);
        return list;
    }

    private List<Map<String, Object>> listPrivateIpRouteTargets(VirtualNetworkClient net, String compartmentId,
                                                                String vcnId, Map<String, String> compartmentNames) {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Subnet> subnets = new LinkedHashMap<>();
        String subnetPage = null;
        do {
            var subnetResp = net.listSubnets(ListSubnetsRequest.builder()
                    .compartmentId(compartmentId).vcnId(vcnId).limit(1000).page(subnetPage).build());
            for (Subnet subnet : subnetResp.getItems()) {
                if (subnet.getLifecycleState() == Subnet.LifecycleState.Terminated) continue;
                subnets.put(subnet.getId(), subnet);
            }
            subnetPage = subnetResp.getOpcNextPage();
        } while (subnetPage != null);

        Map<String, Vnic> vnicCache = new HashMap<>();
        for (Subnet subnet : subnets.values()) {
            String page = null;
            do {
                var resp = net.listPrivateIps(ListPrivateIpsRequest.builder()
                        .subnetId(subnet.getId()).limit(1000).page(page).build());
                for (PrivateIp ip : resp.getItems()) {
                    if (ip.getId() == null) continue;
                    String ipCompartmentId = firstNonBlank(ip.getCompartmentId(), compartmentId);
                    Map<String, Object> m = routeTargetOption("privateIp", ip.getId(),
                            privateIpDisplayName(ip, subnet), ipCompartmentId, compartmentNames,
                            ip.getIpState() != null ? ip.getIpState().getValue() : null);
                    m.put("ipAddress", ip.getIpAddress());
                    m.put("privateIpAddress", ip.getIpAddress());
                    m.put("isPrimary", ip.getIsPrimary());
                    m.put("hostnameLabel", ip.getHostnameLabel());
                    m.put("subnetId", subnet.getId());
                    m.put("subnetName", subnet.getDisplayName());
                    m.put("subnetCidrBlock", subnet.getCidrBlock());
                    m.put("vnicId", ip.getVnicId());
                    m.put("routeTableId", ip.getRouteTableId());
                    if (ip.getVnicId() != null && !ip.getVnicId().isBlank()) {
                        Vnic vnic = vnicCache.get(ip.getVnicId());
                        if (vnic == null && !vnicCache.containsKey(ip.getVnicId())) {
                            try {
                                vnic = net.getVnic(GetVnicRequest.builder().vnicId(ip.getVnicId()).build()).getVnic();
                            } catch (Exception ignored) {
                                vnic = null;
                            }
                            vnicCache.put(ip.getVnicId(), vnic);
                        }
                        if (vnic != null) {
                            m.put("vnicDisplayName", vnic.getDisplayName());
                            m.put("vnicPrivateIp", vnic.getPrivateIp());
                            m.put("skipSourceDestCheck", vnic.getSkipSourceDestCheck());
                        }
                    }
                    list.add(m);
                }
                page = resp.getOpcNextPage();
            } while (page != null);
        }
        return list;
    }

    private List<Map<String, Object>> listRouteServices(VirtualNetworkClient net) {
        List<Map<String, Object>> list = new ArrayList<>();
        String page = null;
        do {
            var resp = net.listServices(ListServicesRequest.builder().limit(1000).page(page).build());
            for (var s : resp.getItems()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.getId());
                m.put("name", s.getName());
                m.put("displayName", firstNonBlank(s.getName(), s.getCidrBlock(), s.getId()));
                m.put("cidrBlock", s.getCidrBlock());
                m.put("description", s.getDescription());
                list.add(m);
            }
            page = resp.getOpcNextPage();
        } while (page != null);
        return list;
    }

    private Map<String, Object> routeTargetOption(String type, String id, String displayName, String compartmentId,
                                                  Map<String, String> compartmentNames, String lifecycleState) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", id);
        m.put("displayName", firstNonBlank(displayName, id));
        m.put("compartmentId", compartmentId);
        m.put("compartmentName", compartmentNames.getOrDefault(compartmentId, compartmentId));
        m.put("lifecycleState", lifecycleState);
        return m;
    }

    private String privateIpDisplayName(PrivateIp ip, Subnet subnet) {
        String name = firstNonBlank(ip.getDisplayName(), ip.getHostnameLabel(), ip.getIpAddress(), ip.getId());
        String subnetName = subnet == null ? null : subnet.getDisplayName();
        return subnetName == null || subnetName.isBlank() ? name : name + " / " + subnetName;
    }

    public List<Map<String, Object>> listVcnGateways(String userId, String vcnId, String region) {
        return listVcnGateways(userId, vcnId, region, false);
    }

    public List<Map<String, Object>> listVcnGateways(String userId, String vcnId, String region, boolean force) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        return ociReadCacheService.get(vcnChildCacheKey("gateways", ociUser, vcnId, region), VCN_READ_CACHE_TTL, force,
                () -> fetchVcnGateways(ociUser, vcnId, region));
    }

    private List<Map<String, Object>> fetchVcnGateways(OciUser ociUser, String vcnId, String region) {
        try (OciClientService client = oci(ociUser, region)) {
            Vcn vcn = client.getVirtualNetworkClient().getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();
            String cid = vcn.getCompartmentId();
            List<Map<String, Object>> result = new ArrayList<>();
            try {
                for (var ig : client.getVirtualNetworkClient().listInternetGateways(
                        ListInternetGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                    if (ig.getLifecycleState() == InternetGateway.LifecycleState.Terminated) continue;
                    result.add(gatewayOption(ig.getId(), ig.getDisplayName(), "internetGateway"));
                }
            } catch (Exception ignored) {}
            try {
                for (var ng : client.getVirtualNetworkClient().listNatGateways(
                        ListNatGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                    if (ng.getLifecycleState() == NatGateway.LifecycleState.Terminated) continue;
                    result.add(gatewayOption(ng.getId(), ng.getDisplayName(), "natGateway"));
                }
            } catch (Exception ignored) {}
            try {
                for (var sg : client.getVirtualNetworkClient().listServiceGateways(
                        ListServiceGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                    if (sg.getLifecycleState() == ServiceGateway.LifecycleState.Terminated) continue;
                    result.add(gatewayOption(sg.getId(), sg.getDisplayName(), "serviceGateway"));
                }
            } catch (Exception ignored) {}
            try {
                for (var lpg : client.getVirtualNetworkClient().listLocalPeeringGateways(
                        ListLocalPeeringGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                    if (lpg.getLifecycleState() == LocalPeeringGateway.LifecycleState.Terminated) continue;
                    result.add(gatewayOption(lpg.getId(), lpg.getDisplayName(), "localPeeringGateway"));
                }
            } catch (Exception ignored) {}
            return result;
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("查询 VCN 网关失败: " + e.getMessage()); }
    }

    private Map<String, Object> gatewayOption(String id, String displayName, String type) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("displayName", displayName);
        map.put("type", type);
        return map;
    }

    public Map<String, Object> getSecurityList(String userId, String slId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            SecurityList sl = client.getVirtualNetworkClient().getSecurityList(
                    GetSecurityListRequest.builder().securityListId(slId).build()).getSecurityList();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", sl.getId());
            m.put("displayName", sl.getDisplayName());
            m.put("lifecycleState", sl.getLifecycleState() != null ? sl.getLifecycleState().getValue() : null);

            List<Map<String, Object>> ingress = new ArrayList<>();
            int idx = 0;
            if (sl.getIngressSecurityRules() != null) {
                for (IngressSecurityRule r : sl.getIngressSecurityRules()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("index", idx++);
                    map.put("direction", "ingress");
                    map.put("protocol", r.getProtocol());
                    map.put("source", r.getSource());
                    map.put("sourceType", r.getSourceType() != null ? r.getSourceType().getValue() : null);
                    map.put("isStateless", r.getIsStateless());
                    map.put("description", r.getDescription());
                    map.put("portRange", portRangeLabel(r.getTcpOptions(), r.getUdpOptions()));
                    ingress.add(map);
                }
            }
            List<Map<String, Object>> egress = new ArrayList<>();
            idx = 0;
            if (sl.getEgressSecurityRules() != null) {
                for (EgressSecurityRule r : sl.getEgressSecurityRules()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("index", idx++);
                    map.put("direction", "egress");
                    map.put("protocol", r.getProtocol());
                    map.put("destination", r.getDestination());
                    map.put("destinationType", r.getDestinationType() != null ? r.getDestinationType().getValue() : null);
                    map.put("isStateless", r.getIsStateless());
                    map.put("description", r.getDescription());
                    map.put("portRange", portRangeLabel(r.getTcpOptions(), r.getUdpOptions()));
                    egress.add(map);
                }
            }
            m.put("ingressSecurityRules", ingress);
            m.put("egressSecurityRules", egress);
            return m;
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("查询安全列表失败: " + e.getMessage()); }
    }

    private String portRangeLabel(TcpOptions tcp, UdpOptions udp) {
        if (tcp != null && tcp.getDestinationPortRange() != null) {
            return tcp.getDestinationPortRange().getMin() + "-" + tcp.getDestinationPortRange().getMax();
        }
        if (udp != null && udp.getDestinationPortRange() != null) {
            return udp.getDestinationPortRange().getMin() + "-" + udp.getDestinationPortRange().getMax();
        }
        return "all";
    }

    public void addSecurityListRule(String userId, String slId, String direction, String protocol,
                                    String source, String portMin, String portMax, String description, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        if (description != null && description.isBlank()) description = null;
        boolean ingress = !"egress".equalsIgnoreCase(direction);
        try (OciClientService client = oci(ociUser, region)) {
            SecurityList sl = client.getVirtualNetworkClient().getSecurityList(
                    GetSecurityListRequest.builder().securityListId(slId).build()).getSecurityList();

            List<IngressSecurityRule> ingressRules = new ArrayList<>(sl.getIngressSecurityRules());
            List<EgressSecurityRule> egressRules = new ArrayList<>(sl.getEgressSecurityRules());

            TcpOptions tcpOpt = null;
            UdpOptions udpOpt = null;
            if (("6".equals(protocol) || "17".equals(protocol)) && portMin != null && !portMin.isBlank()) {
                int min = Integer.parseInt(portMin);
                int max = portMax == null || portMax.isBlank() ? min : Integer.parseInt(portMax);
                PortRange pr = PortRange.builder().min(min).max(max).build();
                if ("6".equals(protocol)) tcpOpt = TcpOptions.builder().destinationPortRange(pr).build();
                else udpOpt = UdpOptions.builder().destinationPortRange(pr).build();
            }
            String src = source == null || source.isBlank() ? "0.0.0.0/0" : source;
            boolean isIpv6 = src.contains(":");
            if (ingress) {
                IngressSecurityRule.Builder b = IngressSecurityRule.builder()
                        .source(src).protocol(protocol == null || protocol.isBlank() ? "all" : protocol)
                        .description(description);
                if (isIpv6) b.sourceType(IngressSecurityRule.SourceType.CidrBlock);
                if (tcpOpt != null) b.tcpOptions(tcpOpt);
                if (udpOpt != null) b.udpOptions(udpOpt);
                ingressRules.add(b.build());
            } else {
                EgressSecurityRule.Builder b = EgressSecurityRule.builder()
                        .destination(src).protocol(protocol == null || protocol.isBlank() ? "all" : protocol)
                        .description(description);
                if (isIpv6) b.destinationType(EgressSecurityRule.DestinationType.CidrBlock);
                if (tcpOpt != null) b.tcpOptions(tcpOpt);
                if (udpOpt != null) b.udpOptions(udpOpt);
                egressRules.add(b.build());
            }
            client.getVirtualNetworkClient().updateSecurityList(
                    UpdateSecurityListRequest.builder().securityListId(slId)
                            .updateSecurityListDetails(UpdateSecurityListDetails.builder()
                                    .ingressSecurityRules(ingressRules)
                                    .egressSecurityRules(egressRules).build()).build());
            evictVcnReadCaches(userId, region);
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("添加安全规则失败: " + e.getMessage()); }
    }

    public void deleteSecurityListRule(String userId, String slId, String direction, int ruleIndex, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        boolean ingress = !"egress".equalsIgnoreCase(direction);
        try (OciClientService client = oci(ociUser, region)) {
            SecurityList sl = client.getVirtualNetworkClient().getSecurityList(
                    GetSecurityListRequest.builder().securityListId(slId).build()).getSecurityList();
            List<IngressSecurityRule> ingressRules = new ArrayList<>(sl.getIngressSecurityRules());
            List<EgressSecurityRule> egressRules = new ArrayList<>(sl.getEgressSecurityRules());
            if (ingress) {
                if (ruleIndex < 0 || ruleIndex >= ingressRules.size()) throw new OciException("入站规则索引越界");
                ingressRules.remove(ruleIndex);
            } else {
                if (ruleIndex < 0 || ruleIndex >= egressRules.size()) throw new OciException("出站规则索引越界");
                egressRules.remove(ruleIndex);
            }
            client.getVirtualNetworkClient().updateSecurityList(
                    UpdateSecurityListRequest.builder().securityListId(slId)
                            .updateSecurityListDetails(UpdateSecurityListDetails.builder()
                                    .ingressSecurityRules(ingressRules)
                                    .egressSecurityRules(egressRules).build()).build());
            evictVcnReadCaches(userId, region);
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("删除安全规则失败: " + e.getMessage()); }
    }

    /** 在 VCN 的默认路由表中加入 0.0.0.0/0 与 ::/0 两条规则指向该 IGW（已存在则跳过） */
    public void setupIgwDefaultRoutes(String userId, String vcnId, String igwId, boolean addIpv6, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            Vcn vcn = client.getVirtualNetworkClient().getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();
            String defaultRtId = vcn.getDefaultRouteTableId();
            if (defaultRtId == null) throw new OciException("未找到 VCN 的默认路由表");
            RouteTable rt = client.getVirtualNetworkClient().getRouteTable(
                    GetRouteTableRequest.builder().rtId(defaultRtId).build()).getRouteTable();
            List<RouteRule> rules = rt.getRouteRules() == null ? new ArrayList<>() : new ArrayList<>(rt.getRouteRules());
            boolean hasIpv4 = rules.stream().anyMatch(r -> "0.0.0.0/0".equals(r.getDestination()) && igwId.equals(r.getNetworkEntityId()));
            boolean hasIpv6 = rules.stream().anyMatch(r -> "::/0".equals(r.getDestination()) && igwId.equals(r.getNetworkEntityId()));
            if (!hasIpv4) {
                rules.add(RouteRule.builder().destination("0.0.0.0/0")
                        .destinationType(RouteRule.DestinationType.CidrBlock)
                        .networkEntityId(igwId).description("Default IPv4 route via IGW").build());
            }
            if (addIpv6 && !hasIpv6) {
                rules.add(RouteRule.builder().destination("::/0")
                        .destinationType(RouteRule.DestinationType.CidrBlock)
                        .networkEntityId(igwId).description("Default IPv6 route via IGW").build());
            }
            client.getVirtualNetworkClient().updateRouteTable(
                    UpdateRouteTableRequest.builder().rtId(defaultRtId)
                            .updateRouteTableDetails(UpdateRouteTableDetails.builder()
                                    .routeRules(rules).build()).build());
            evictVcnReadCaches(userId, region);
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("配置 IGW 默认路由失败: " + e.getMessage()); }
    }

    // ---------------- Security List ----------------

    public List<Map<String, Object>> listSecurityLists(String userId, String vcnId, String region) {
        return listSecurityLists(userId, vcnId, region, false);
    }

    public List<Map<String, Object>> listSecurityLists(String userId, String vcnId, String region, boolean force) {
        return listChildren("sl", userId, vcnId, region, force, (client, cid) -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (var sl : client.getVirtualNetworkClient().listSecurityLists(
                    ListSecurityListsRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                if (sl.getLifecycleState() == SecurityList.LifecycleState.Terminated) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", sl.getId());
                m.put("displayName", sl.getDisplayName());
                m.put("ingressRulesCount", sl.getIngressSecurityRules() != null ? sl.getIngressSecurityRules().size() : 0);
                m.put("egressRulesCount", sl.getEgressSecurityRules() != null ? sl.getEgressSecurityRules().size() : 0);
                m.put("lifecycleState", sl.getLifecycleState() != null ? sl.getLifecycleState().getValue() : null);
                m.put("timeCreated", sl.getTimeCreated() != null ? sl.getTimeCreated().toString() : null);
                list.add(m);
            }
            return list;
        });
    }

    public void deleteSecurityList(String userId, String slId, String region) {
        deleteResource(userId, region, () -> "deleteSecurityList", (client) ->
                client.getVirtualNetworkClient().deleteSecurityList(DeleteSecurityListRequest.builder().securityListId(slId).build()));
        evictVcnReadCaches(userId, region);
    }

    // ---------------- DRG ----------------

    public List<Map<String, Object>> listDrgs(String userId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (var c : client.listAllCompartments()) {
                try {
                    for (var d : client.getVirtualNetworkClient().listDrgs(
                            ListDrgsRequest.builder().compartmentId(c.getId()).build()).getItems()) {
                        if (d.getLifecycleState() == Drg.LifecycleState.Terminated) continue;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", d.getId());
                        m.put("displayName", d.getDisplayName());
                        m.put("compartmentId", c.getId());
                        m.put("compartmentName", c.getName());
                        m.put("lifecycleState", d.getLifecycleState() != null ? d.getLifecycleState().getValue() : null);
                        m.put("timeCreated", d.getTimeCreated() != null ? d.getTimeCreated().toString() : null);
                        result.add(m);
                    }
                } catch (Exception ignored) {}
            }
            return result;
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("查询 DRG 失败: " + e.getMessage()); }
    }

    public void createDrg(String userId, String compartmentId, String displayName, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            String cid = compartmentId == null || compartmentId.isBlank() ? client.getProvider().getTenantId() : compartmentId;
            client.getVirtualNetworkClient().createDrg(
                    CreateDrgRequest.builder().createDrgDetails(
                            CreateDrgDetails.builder().compartmentId(cid).displayName(displayName).build()
                    ).build());
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("创建 DRG 失败: " + e.getMessage()); }
    }

    public void deleteDrg(String userId, String drgId, String region) {
        deleteResource(userId, region, () -> "deleteDrg", (client) ->
                client.getVirtualNetworkClient().deleteDrg(DeleteDrgRequest.builder().drgId(drgId).build()));
    }

    // ---------------- Local Peering Gateway ----------------

    public List<Map<String, Object>> listLocalPeeringGateways(String userId, String vcnId, String region) {
        return listLocalPeeringGateways(userId, vcnId, region, false);
    }

    public List<Map<String, Object>> listLocalPeeringGateways(String userId, String vcnId, String region, boolean force) {
        return listChildren("lpg", userId, vcnId, region, force, (client, cid) -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (var lpg : client.getVirtualNetworkClient().listLocalPeeringGateways(
                    ListLocalPeeringGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                if (lpg.getLifecycleState() == LocalPeeringGateway.LifecycleState.Terminated) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", lpg.getId());
                m.put("displayName", lpg.getDisplayName());
                m.put("peeringStatus", lpg.getPeeringStatus() != null ? lpg.getPeeringStatus().getValue() : null);
                m.put("peerAdvertisedCidr", lpg.getPeerAdvertisedCidr());
                m.put("lifecycleState", lpg.getLifecycleState() != null ? lpg.getLifecycleState().getValue() : null);
                m.put("timeCreated", lpg.getTimeCreated() != null ? lpg.getTimeCreated().toString() : null);
                list.add(m);
            }
            return list;
        });
    }

    public void createLocalPeeringGateway(String userId, String vcnId, String displayName, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            Vcn vcn = client.getVirtualNetworkClient().getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();
            client.getVirtualNetworkClient().createLocalPeeringGateway(
                    CreateLocalPeeringGatewayRequest.builder().createLocalPeeringGatewayDetails(
                            CreateLocalPeeringGatewayDetails.builder()
                                    .compartmentId(vcn.getCompartmentId())
                                    .vcnId(vcnId)
                                    .displayName(displayName)
                                    .build()
                    ).build());
            evictVcnReadCaches(userId, region);
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("创建 LPG 失败: " + e.getMessage()); }
    }

    public void connectLocalPeeringGateway(String userId, String lpgId, String peerId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            client.getVirtualNetworkClient().connectLocalPeeringGateways(
                    ConnectLocalPeeringGatewaysRequest.builder()
                            .localPeeringGatewayId(lpgId)
                            .connectLocalPeeringGatewaysDetails(
                                    ConnectLocalPeeringGatewaysDetails.builder().peerId(peerId).build())
                            .build());
            evictVcnReadCaches(userId, region);
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("连接 LPG 失败: " + e.getMessage()); }
    }

    public void deleteLocalPeeringGateway(String userId, String lpgId, String region) {
        deleteResource(userId, region, () -> "deleteLocalPeeringGateway", (client) ->
                client.getVirtualNetworkClient().deleteLocalPeeringGateway(DeleteLocalPeeringGatewayRequest.builder().localPeeringGatewayId(lpgId).build()));
        evictVcnReadCaches(userId, region);
    }

    // ---------------- Helpers ----------------

    @FunctionalInterface
    private interface ChildrenFetcher {
        List<Map<String, Object>> fetch(OciClientService client, String compartmentId);
    }

    @FunctionalInterface
    private interface RouteTargetFetcher {
        List<Map<String, Object>> fetch();
    }

    @FunctionalInterface
    private interface ClientAction {
        void run(OciClientService client);
    }

    @FunctionalInterface
    private interface OpName { String get(); }

    private List<Map<String, Object>> listChildren(String cacheType, String userId, String vcnId, String region, boolean force, ChildrenFetcher fetcher) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        return ociReadCacheService.get(vcnChildCacheKey(cacheType, ociUser, vcnId, region), VCN_READ_CACHE_TTL, force,
                () -> fetchChildren(vcnId, region, fetcher, ociUser));
    }

    private List<Map<String, Object>> fetchChildren(String vcnId, String region, ChildrenFetcher fetcher, OciUser ociUser) {
        try (OciClientService client = oci(ociUser, region)) {
            Vcn vcn = client.getVirtualNetworkClient().getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();
            return fetcher.fetch(client, vcn.getCompartmentId());
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("查询子资源失败: " + e.getMessage()); }
    }

    private void deleteResource(String userId, String region, OpName op, ClientAction action) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            action.run(client);
            log.info("{} succeeded", op.get());
        } catch (OciException e) { throw e; }
        catch (com.oracle.bmc.model.BmcException e) {
            if (e.getStatusCode() == 409) throw new OciException("资源仍被引用或正在使用，无法删除");
            throw new OciException(op.get() + " 失败: " + OciBmcErrorTranslator.translate(e));
        } catch (Exception e) { throw new OciException(op.get() + " 失败: " + e.getMessage()); }
    }

    private <T> List<Map<String, String>> listMapped(List<T> items,
                                                     java.util.function.Function<T, String> idFn,
                                                     java.util.function.Function<T, String> nameFn) {
        List<Map<String, String>> list = new ArrayList<>();
        if (items == null) return list;
        for (T it : items) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", idFn.apply(it));
            m.put("displayName", nameFn.apply(it));
            list.add(m);
        }
        return list;
    }

    private String asStr(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String normalizeBlank(String v) {
        if (v == null) return null;
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            String normalized = normalizeBlank(value);
            if (normalized != null) return normalized;
        }
        return null;
    }

    private void evictVcnReadCaches(String userId, String region) {
        OciUser user = userMapper.selectById(userId);
        if (user == null) return;
        ociReadCacheService.evictByPrefix(vcnCachePrefix(user, region) + "|");
    }

    private static String vcnListCacheKey(OciUser user, String region) {
        return OciReadCacheService.key(vcnCachePrefix(user, region), "list");
    }

    private static String vcnChildCacheKey(String type, OciUser user, String vcnId, String region) {
        return OciReadCacheService.key(vcnCachePrefix(user, region), type, vcnId);
    }

    static String vcnCachePrefix(OciUser user, String region) {
        return OciReadCacheService.key(
                "oci:vcn",
                user.getId(),
                user.getOciTenantId(),
                configuredRegion(user),
                effectiveRegion(user, region));
    }

    private static String effectiveRegion(OciUser user, String region) {
        if (region != null && !region.isBlank()) {
            return region.trim();
        }
        return configuredRegion(user);
    }

    private static String configuredRegion(OciUser user) {
        String region = user == null ? null : user.getOciRegion();
        return region == null ? "" : region.trim();
    }

    public void updateVcn(String userId, String vcnId, String displayName, String dnsLabel, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            UpdateVcnDetails.Builder b = UpdateVcnDetails.builder();
            if (displayName != null && !displayName.isBlank()) b.displayName(displayName);
            client.getVirtualNetworkClient().updateVcn(
                    UpdateVcnRequest.builder().vcnId(vcnId).updateVcnDetails(b.build()).build());
            evictVcnReadCaches(userId, region);
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("更新 VCN 失败: " + e.getMessage()); }
    }

    public void updateLocalPeeringGateway(String userId, String lpgId, String displayName, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            UpdateLocalPeeringGatewayDetails.Builder b = UpdateLocalPeeringGatewayDetails.builder();
            if (displayName != null && !displayName.isBlank()) b.displayName(displayName);
            client.getVirtualNetworkClient().updateLocalPeeringGateway(
                    UpdateLocalPeeringGatewayRequest.builder().localPeeringGatewayId(lpgId)
                            .updateLocalPeeringGatewayDetails(b.build()).build());
            evictVcnReadCaches(userId, region);
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("更新 LPG 失败: " + e.getMessage()); }
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
