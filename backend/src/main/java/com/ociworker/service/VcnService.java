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

@Slf4j
@Service
public class VcnService {

    @Resource
    private OciUserMapper userMapper;

    // ---------------- VCN ----------------

    public List<Map<String, Object>> listVcns(String userId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
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
                                         String dnsLabel, boolean createIgw) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
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
            return map;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("创建 VCN 失败: " + e.getMessage());
        }
    }

    public Map<String, Object> previewVcnDelete(String userId, String vcnId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
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

    public void deleteVcn(String userId, String vcnId, boolean cascade) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            Vcn vcn = client.getVirtualNetworkClient().getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();
            String cid = vcn.getCompartmentId();
            if (cascade) {
                deleteAllChildren(client, cid, vcnId);
            }
            client.getVirtualNetworkClient().deleteVcn(DeleteVcnRequest.builder().vcnId(vcnId).build());
            log.info("VCN deleted: {}", vcnId);
        } catch (OciException e) { throw e; }
        catch (com.oracle.bmc.model.BmcException e) {
            if (e.getStatusCode() == 409) throw new OciException("VCN 仍包含子资源，请先开启级联删除或手动清理");
            throw new OciException("删除 VCN 失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        } catch (Exception e) { throw new OciException("删除 VCN 失败: " + e.getMessage()); }
    }

    private void deleteAllChildren(OciClientService client, String cid, String vcnId) {
        var net = client.getVirtualNetworkClient();
        // Subnets
        try {
            for (var s : net.listSubnets(ListSubnetsRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                try { net.deleteSubnet(DeleteSubnetRequest.builder().subnetId(s.getId()).build()); } catch (Exception e) { log.warn("deleteSubnet {} failed: {}", s.getId(), e.getMessage()); }
            }
        } catch (Exception e) { log.warn("listSubnets failed: {}", e.getMessage()); }
        // Internet Gateways
        try {
            for (var ig : net.listInternetGateways(ListInternetGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                try { net.deleteInternetGateway(DeleteInternetGatewayRequest.builder().igId(ig.getId()).build()); } catch (Exception e) { log.warn("deleteIGW failed: {}", e.getMessage()); }
            }
        } catch (Exception e) { log.warn("listIGW failed: {}", e.getMessage()); }
        // NAT Gateways
        try {
            for (var ng : net.listNatGateways(ListNatGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                try { net.deleteNatGateway(DeleteNatGatewayRequest.builder().natGatewayId(ng.getId()).build()); } catch (Exception e) { log.warn("deleteNatGateway failed: {}", e.getMessage()); }
            }
        } catch (Exception e) { log.warn("listNatGateways failed: {}", e.getMessage()); }
        // Service Gateways
        try {
            for (var sg : net.listServiceGateways(ListServiceGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                try { net.deleteServiceGateway(DeleteServiceGatewayRequest.builder().serviceGatewayId(sg.getId()).build()); } catch (Exception e) { log.warn("deleteSG failed: {}", e.getMessage()); }
            }
        } catch (Exception e) { log.warn("listSG failed: {}", e.getMessage()); }
        // Local Peering Gateways
        try {
            for (var lpg : net.listLocalPeeringGateways(ListLocalPeeringGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                try { net.deleteLocalPeeringGateway(DeleteLocalPeeringGatewayRequest.builder().localPeeringGatewayId(lpg.getId()).build()); } catch (Exception e) { log.warn("deleteLPG failed: {}", e.getMessage()); }
            }
        } catch (Exception e) { log.warn("listLPG failed: {}", e.getMessage()); }
        // Route tables (skip default)
        try {
            for (var rt : net.listRouteTables(ListRouteTablesRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                try { net.deleteRouteTable(DeleteRouteTableRequest.builder().rtId(rt.getId()).build()); } catch (Exception e) { log.debug("deleteRouteTable {} failed: {}", rt.getId(), e.getMessage()); }
            }
        } catch (Exception e) { log.warn("listRouteTables failed: {}", e.getMessage()); }
        // Security lists (skip default)
        try {
            for (var sl : net.listSecurityLists(ListSecurityListsRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                try { net.deleteSecurityList(DeleteSecurityListRequest.builder().securityListId(sl.getId()).build()); } catch (Exception e) { log.debug("deleteSecurityList failed: {}", e.getMessage()); }
            }
        } catch (Exception e) { log.warn("listSecurityLists failed: {}", e.getMessage()); }
    }

    // ---------------- Subnets ----------------

    public List<Map<String, Object>> listSubnets(String userId, String vcnId) {
        return listChildren(userId, vcnId, (client, cid) -> {
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
                             String availabilityDomain, String routeTableId, Boolean prohibitPublicIp) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
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
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("创建子网失败: " + e.getMessage()); }
    }

    public void deleteSubnet(String userId, String subnetId) {
        deleteResource(userId, () -> "deleteSubnet", (client) ->
                client.getVirtualNetworkClient().deleteSubnet(DeleteSubnetRequest.builder().subnetId(subnetId).build()));
    }

    // ---------------- Internet Gateway ----------------

    public List<Map<String, Object>> listInternetGateways(String userId, String vcnId) {
        return listChildren(userId, vcnId, (client, cid) -> {
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

    public void createInternetGateway(String userId, String vcnId, String displayName, boolean enabled) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
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
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("创建 Internet Gateway 失败: " + e.getMessage()); }
    }

    public void deleteInternetGateway(String userId, String igId) {
        deleteResource(userId, () -> "deleteInternetGateway", (client) ->
                client.getVirtualNetworkClient().deleteInternetGateway(DeleteInternetGatewayRequest.builder().igId(igId).build()));
    }

    // ---------------- NAT Gateway ----------------

    public List<Map<String, Object>> listNatGateways(String userId, String vcnId) {
        return listChildren(userId, vcnId, (client, cid) -> {
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

    public void createNatGateway(String userId, String vcnId, String displayName) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            Vcn vcn = client.getVirtualNetworkClient().getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();
            client.getVirtualNetworkClient().createNatGateway(
                    CreateNatGatewayRequest.builder().createNatGatewayDetails(
                            CreateNatGatewayDetails.builder()
                                    .compartmentId(vcn.getCompartmentId())
                                    .vcnId(vcnId)
                                    .displayName(displayName)
                                    .build()
                    ).build());
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("创建 NAT Gateway 失败: " + e.getMessage()); }
    }

    public void deleteNatGateway(String userId, String natId) {
        deleteResource(userId, () -> "deleteNatGateway", (client) ->
                client.getVirtualNetworkClient().deleteNatGateway(DeleteNatGatewayRequest.builder().natGatewayId(natId).build()));
    }

    // ---------------- Service Gateway ----------------

    public List<Map<String, Object>> listServiceGateways(String userId, String vcnId) {
        return listChildren(userId, vcnId, (client, cid) -> {
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

    public void createServiceGateway(String userId, String vcnId, String displayName) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
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
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("创建 Service Gateway 失败: " + e.getMessage()); }
    }

    public void deleteServiceGateway(String userId, String sgId) {
        deleteResource(userId, () -> "deleteServiceGateway", (client) ->
                client.getVirtualNetworkClient().deleteServiceGateway(DeleteServiceGatewayRequest.builder().serviceGatewayId(sgId).build()));
    }

    // ---------------- Route Table ----------------

    public List<Map<String, Object>> listRouteTables(String userId, String vcnId) {
        return listChildren(userId, vcnId, (client, cid) -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (var rt : client.getVirtualNetworkClient().listRouteTables(
                    ListRouteTablesRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                if (rt.getLifecycleState() == RouteTable.LifecycleState.Terminated) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rt.getId());
                m.put("displayName", rt.getDisplayName());
                m.put("routeRules", rt.getRouteRules());
                m.put("lifecycleState", rt.getLifecycleState() != null ? rt.getLifecycleState().getValue() : null);
                m.put("timeCreated", rt.getTimeCreated() != null ? rt.getTimeCreated().toString() : null);
                list.add(m);
            }
            return list;
        });
    }

    public void deleteRouteTable(String userId, String rtId) {
        deleteResource(userId, () -> "deleteRouteTable", (client) ->
                client.getVirtualNetworkClient().deleteRouteTable(DeleteRouteTableRequest.builder().rtId(rtId).build()));
    }

    // ---------------- Security List ----------------

    public List<Map<String, Object>> listSecurityLists(String userId, String vcnId) {
        return listChildren(userId, vcnId, (client, cid) -> {
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

    public void deleteSecurityList(String userId, String slId) {
        deleteResource(userId, () -> "deleteSecurityList", (client) ->
                client.getVirtualNetworkClient().deleteSecurityList(DeleteSecurityListRequest.builder().securityListId(slId).build()));
    }

    // ---------------- DRG ----------------

    public List<Map<String, Object>> listDrgs(String userId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
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

    public void createDrg(String userId, String compartmentId, String displayName) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            String cid = compartmentId == null || compartmentId.isBlank() ? client.getProvider().getTenantId() : compartmentId;
            client.getVirtualNetworkClient().createDrg(
                    CreateDrgRequest.builder().createDrgDetails(
                            CreateDrgDetails.builder().compartmentId(cid).displayName(displayName).build()
                    ).build());
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("创建 DRG 失败: " + e.getMessage()); }
    }

    public void deleteDrg(String userId, String drgId) {
        deleteResource(userId, () -> "deleteDrg", (client) ->
                client.getVirtualNetworkClient().deleteDrg(DeleteDrgRequest.builder().drgId(drgId).build()));
    }

    // ---------------- Local Peering Gateway ----------------

    public List<Map<String, Object>> listLocalPeeringGateways(String userId, String vcnId) {
        return listChildren(userId, vcnId, (client, cid) -> {
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

    public void createLocalPeeringGateway(String userId, String vcnId, String displayName) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            Vcn vcn = client.getVirtualNetworkClient().getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();
            client.getVirtualNetworkClient().createLocalPeeringGateway(
                    CreateLocalPeeringGatewayRequest.builder().createLocalPeeringGatewayDetails(
                            CreateLocalPeeringGatewayDetails.builder()
                                    .compartmentId(vcn.getCompartmentId())
                                    .vcnId(vcnId)
                                    .displayName(displayName)
                                    .build()
                    ).build());
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("创建 LPG 失败: " + e.getMessage()); }
    }

    public void connectLocalPeeringGateway(String userId, String lpgId, String peerId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            client.getVirtualNetworkClient().connectLocalPeeringGateways(
                    ConnectLocalPeeringGatewaysRequest.builder()
                            .localPeeringGatewayId(lpgId)
                            .connectLocalPeeringGatewaysDetails(
                                    ConnectLocalPeeringGatewaysDetails.builder().peerId(peerId).build())
                            .build());
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("连接 LPG 失败: " + e.getMessage()); }
    }

    public void deleteLocalPeeringGateway(String userId, String lpgId) {
        deleteResource(userId, () -> "deleteLocalPeeringGateway", (client) ->
                client.getVirtualNetworkClient().deleteLocalPeeringGateway(DeleteLocalPeeringGatewayRequest.builder().localPeeringGatewayId(lpgId).build()));
    }

    // ---------------- Helpers ----------------

    @FunctionalInterface
    private interface ChildrenFetcher {
        List<Map<String, Object>> fetch(OciClientService client, String compartmentId);
    }

    @FunctionalInterface
    private interface ClientAction {
        void run(OciClientService client);
    }

    @FunctionalInterface
    private interface OpName { String get(); }

    private List<Map<String, Object>> listChildren(String userId, String vcnId, ChildrenFetcher fetcher) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            Vcn vcn = client.getVirtualNetworkClient().getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();
            return fetcher.fetch(client, vcn.getCompartmentId());
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("查询子资源失败: " + e.getMessage()); }
    }

    private void deleteResource(String userId, OpName op, ClientAction action) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            action.run(client);
            log.info("{} succeeded", op.get());
        } catch (OciException e) { throw e; }
        catch (com.oracle.bmc.model.BmcException e) {
            if (e.getStatusCode() == 409) throw new OciException("资源仍被引用或正在使用，无法删除");
            throw new OciException(op.get() + " 失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
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
