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

    private OciClientService oci(OciUser ociUser, String region) {
        String r = (region == null || region.isBlank()) ? null : region.trim();
        return new OciClientService(buildBasicDTO(ociUser), r);
    }

    // ---------------- VCN ----------------

    public List<Map<String, Object>> listVcns(String userId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        String r = (region == null || region.isBlank()) ? null : region.trim();
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
                        map.put("region", r != null ? r : ociUser.getOciRegion());
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

    public List<Map<String, Object>> listSubnets(String userId, String vcnId, String region) {
        return listChildren(userId, vcnId, region, (client, cid) -> {
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
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("创建子网失败: " + e.getMessage()); }
    }

    public void deleteSubnet(String userId, String subnetId, String region) {
        deleteResource(userId, region, () -> "deleteSubnet", (client) ->
                client.getVirtualNetworkClient().deleteSubnet(DeleteSubnetRequest.builder().subnetId(subnetId).build()));
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
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("更新子网失败: " + e.getMessage()); }
    }

    // ---------------- Internet Gateway ----------------

    public List<Map<String, Object>> listInternetGateways(String userId, String vcnId, String region) {
        return listChildren(userId, vcnId, region, (client, cid) -> {
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
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("创建 Internet Gateway 失败: " + e.getMessage()); }
    }

    public void deleteInternetGateway(String userId, String igId, String region) {
        deleteResource(userId, region, () -> "deleteInternetGateway", (client) ->
                client.getVirtualNetworkClient().deleteInternetGateway(DeleteInternetGatewayRequest.builder().igId(igId).build()));
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
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("更新 IGW 失败: " + e.getMessage()); }
    }

    // ---------------- NAT Gateway ----------------

    public List<Map<String, Object>> listNatGateways(String userId, String vcnId, String region) {
        return listChildren(userId, vcnId, region, (client, cid) -> {
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
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("创建 NAT Gateway 失败: " + e.getMessage()); }
    }

    public void deleteNatGateway(String userId, String natId, String region) {
        deleteResource(userId, region, () -> "deleteNatGateway", (client) ->
                client.getVirtualNetworkClient().deleteNatGateway(DeleteNatGatewayRequest.builder().natGatewayId(natId).build()));
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
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("更新 NAT 失败: " + e.getMessage()); }
    }

    // ---------------- Service Gateway ----------------

    public List<Map<String, Object>> listServiceGateways(String userId, String vcnId, String region) {
        return listChildren(userId, vcnId, region, (client, cid) -> {
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
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("创建 Service Gateway 失败: " + e.getMessage()); }
    }

    public void deleteServiceGateway(String userId, String sgId, String region) {
        deleteResource(userId, region, () -> "deleteServiceGateway", (client) ->
                client.getVirtualNetworkClient().deleteServiceGateway(DeleteServiceGatewayRequest.builder().serviceGatewayId(sgId).build()));
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
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("更新 SG 失败: " + e.getMessage()); }
    }

    // ---------------- Route Table ----------------

    public List<Map<String, Object>> listRouteTables(String userId, String vcnId, String region) {
        return listChildren(userId, vcnId, region, (client, cid) -> {
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
                        Map<String, Object> rr = new LinkedHashMap<>();
                        rr.put("destination", r.getDestination());
                        rr.put("destinationType", r.getDestinationType() != null ? r.getDestinationType().getValue() : null);
                        rr.put("networkEntityId", r.getNetworkEntityId());
                        rr.put("description", r.getDescription());
                        rules.add(rr);
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
                    RouteRule.Builder rb = RouteRule.builder()
                            .destination(asStr(r.get("destination")))
                            .networkEntityId(asStr(r.get("networkEntityId")))
                            .description(asStr(r.get("description")));
                    String dstType = asStr(r.get("destinationType"));
                    if (dstType != null && !dstType.isBlank()) {
                        try { rb.destinationType(RouteRule.DestinationType.create(dstType)); } catch (Exception ignored) {}
                    }
                    rules.add(rb.build());
                }
                b.routeRules(rules);
            }
            client.getVirtualNetworkClient().updateRouteTable(
                    UpdateRouteTableRequest.builder().rtId(rtId)
                            .updateRouteTableDetails(b.build()).build());
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
                    Map<String, Object> rr = new LinkedHashMap<>();
                    rr.put("destination", r.getDestination());
                    rr.put("destinationType", r.getDestinationType() != null ? r.getDestinationType().getValue() : null);
                    rr.put("networkEntityId", r.getNetworkEntityId());
                    rr.put("description", r.getDescription());
                    rules.add(rr);
                }
            }
            m.put("routeRules", rules);
            return m;
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("查询路由表失败: " + e.getMessage()); }
    }

    public List<Map<String, Object>> listVcnGateways(String userId, String vcnId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            Vcn vcn = client.getVirtualNetworkClient().getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();
            String cid = vcn.getCompartmentId();
            List<Map<String, Object>> result = new ArrayList<>();
            try {
                for (var ig : client.getVirtualNetworkClient().listInternetGateways(
                        ListInternetGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                    if (ig.getLifecycleState() == InternetGateway.LifecycleState.Terminated) continue;
                    result.add(Map.of("id", ig.getId(), "displayName", ig.getDisplayName(), "type", "internetGateway"));
                }
            } catch (Exception ignored) {}
            try {
                for (var ng : client.getVirtualNetworkClient().listNatGateways(
                        ListNatGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                    if (ng.getLifecycleState() == NatGateway.LifecycleState.Terminated) continue;
                    result.add(Map.of("id", ng.getId(), "displayName", ng.getDisplayName(), "type", "natGateway"));
                }
            } catch (Exception ignored) {}
            try {
                for (var sg : client.getVirtualNetworkClient().listServiceGateways(
                        ListServiceGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                    if (sg.getLifecycleState() == ServiceGateway.LifecycleState.Terminated) continue;
                    result.add(Map.of("id", sg.getId(), "displayName", sg.getDisplayName(), "type", "serviceGateway"));
                }
            } catch (Exception ignored) {}
            try {
                for (var lpg : client.getVirtualNetworkClient().listLocalPeeringGateways(
                        ListLocalPeeringGatewaysRequest.builder().compartmentId(cid).vcnId(vcnId).build()).getItems()) {
                    if (lpg.getLifecycleState() == LocalPeeringGateway.LifecycleState.Terminated) continue;
                    result.add(Map.of("id", lpg.getId(), "displayName", lpg.getDisplayName(), "type", "localPeeringGateway"));
                }
            } catch (Exception ignored) {}
            return result;
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("查询 VCN 网关失败: " + e.getMessage()); }
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
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("配置 IGW 默认路由失败: " + e.getMessage()); }
    }

    // ---------------- Security List ----------------

    public List<Map<String, Object>> listSecurityLists(String userId, String vcnId, String region) {
        return listChildren(userId, vcnId, region, (client, cid) -> {
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
        return listChildren(userId, vcnId, region, (client, cid) -> {
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
        } catch (OciException e) { throw e; }
        catch (Exception e) { throw new OciException("连接 LPG 失败: " + e.getMessage()); }
    }

    public void deleteLocalPeeringGateway(String userId, String lpgId, String region) {
        deleteResource(userId, region, () -> "deleteLocalPeeringGateway", (client) ->
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

    private List<Map<String, Object>> listChildren(String userId, String vcnId, String region, ChildrenFetcher fetcher) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
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

    private String asStr(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    public void updateVcn(String userId, String vcnId, String displayName, String dnsLabel, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        try (OciClientService client = oci(ociUser, region)) {
            UpdateVcnDetails.Builder b = UpdateVcnDetails.builder();
            if (displayName != null && !displayName.isBlank()) b.displayName(displayName);
            client.getVirtualNetworkClient().updateVcn(
                    UpdateVcnRequest.builder().vcnId(vcnId).updateVcnDetails(b.build()).build());
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
