package com.ociworker.controller;

import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.VcnService;
import com.ociworker.service.VerifyCodeService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/oci/vcn")
public class VcnController {

    @Resource
    private VcnService vcnService;
    @Resource
    private VerifyCodeService verifyCodeService;

    // ---------- VCN ----------

    @PostMapping("/list")
    public ResponseData<?> list(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listVcns(str(params, "id"), reg(params)));
    }

    @PostMapping("/create")
    public ResponseData<?> create(@RequestBody Map<String, Object> params) {
        vcnService.createVcn(
                str(params, "id"),
                str(params, "compartmentId"),
                str(params, "displayName"),
                str(params, "cidrBlock"),
                str(params, "dnsLabel"),
                bool(params, "createIgw", true),
                reg(params)
        );
        return ResponseData.ok();
    }

    @PostMapping("/preview-delete")
    public ResponseData<?> previewDelete(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.previewVcnDelete(str(params, "id"), str(params, "vcnId"), reg(params)));
    }

    @PostMapping("/delete")
    public ResponseData<?> delete(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteVcn(str(params, "id"), str(params, "vcnId"), bool(params, "cascade", true), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/update")
    public ResponseData<?> updateVcn(@RequestBody Map<String, Object> params) {
        vcnService.updateVcn(str(params, "id"), str(params, "vcnId"), str(params, "displayName"), null, reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/gateways")
    public ResponseData<?> listVcnGateways(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listVcnGateways(str(params, "id"), str(params, "vcnId"), reg(params)));
    }

    // ---------- Subnet ----------

    @PostMapping("/subnet/list")
    public ResponseData<?> listSubnets(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listSubnets(str(params, "id"), str(params, "vcnId"), reg(params)));
    }

    @PostMapping("/subnet/create")
    public ResponseData<?> createSubnet(@RequestBody Map<String, Object> params) {
        vcnService.createSubnet(
                str(params, "id"),
                str(params, "vcnId"),
                str(params, "displayName"),
                str(params, "cidrBlock"),
                str(params, "availabilityDomain"),
                str(params, "routeTableId"),
                params.get("prohibitPublicIp") == null ? null : bool(params, "prohibitPublicIp", false),
                reg(params)
        );
        return ResponseData.ok();
    }

    @PostMapping("/subnet/delete")
    public ResponseData<?> deleteSubnet(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteSubnet(str(params, "id"), str(params, "subnetId"), reg(params));
        return ResponseData.ok();
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/subnet/update")
    public ResponseData<?> updateSubnet(@RequestBody Map<String, Object> params) {
        Object secIds = params.get("securityListIds");
        java.util.List<String> sl = null;
        if (secIds instanceof java.util.List<?> list) {
            sl = new java.util.ArrayList<>();
            for (Object o : list) if (o != null) sl.add(String.valueOf(o));
        }
        vcnService.updateSubnet(str(params, "id"), str(params, "subnetId"),
                str(params, "displayName"), str(params, "routeTableId"), sl, reg(params));
        return ResponseData.ok();
    }

    // ---------- Internet Gateway ----------

    @PostMapping("/igw/list")
    public ResponseData<?> listIgw(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listInternetGateways(str(params, "id"), str(params, "vcnId"), reg(params)));
    }

    @PostMapping("/igw/create")
    public ResponseData<?> createIgw(@RequestBody Map<String, Object> params) {
        vcnService.createInternetGateway(
                str(params, "id"),
                str(params, "vcnId"),
                str(params, "displayName"),
                bool(params, "isEnabled", true),
                reg(params)
        );
        return ResponseData.ok();
    }

    @PostMapping("/igw/delete")
    public ResponseData<?> deleteIgw(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteInternetGateway(str(params, "id"), str(params, "igwId"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/igw/update")
    public ResponseData<?> updateIgw(@RequestBody Map<String, Object> params) {
        Boolean enabled = params.get("isEnabled") == null ? null : bool(params, "isEnabled", true);
        vcnService.updateInternetGateway(str(params, "id"), str(params, "igwId"),
                str(params, "displayName"), enabled, reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/igw/setupDefaultRoutes")
    public ResponseData<?> setupIgwDefaultRoutes(@RequestBody Map<String, Object> params) {
        vcnService.setupIgwDefaultRoutes(str(params, "id"), str(params, "vcnId"),
                str(params, "igwId"), bool(params, "addIpv6", true), reg(params));
        return ResponseData.ok();
    }

    // ---------- NAT Gateway ----------

    @PostMapping("/nat/list")
    public ResponseData<?> listNat(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listNatGateways(str(params, "id"), str(params, "vcnId"), reg(params)));
    }

    @PostMapping("/nat/create")
    public ResponseData<?> createNat(@RequestBody Map<String, Object> params) {
        vcnService.createNatGateway(str(params, "id"), str(params, "vcnId"), str(params, "displayName"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/nat/delete")
    public ResponseData<?> deleteNat(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteNatGateway(str(params, "id"), str(params, "natId"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/nat/update")
    public ResponseData<?> updateNat(@RequestBody Map<String, Object> params) {
        Boolean block = params.get("blockTraffic") == null ? null : bool(params, "blockTraffic", false);
        vcnService.updateNatGateway(str(params, "id"), str(params, "natId"),
                str(params, "displayName"), block, reg(params));
        return ResponseData.ok();
    }

    // ---------- Service Gateway ----------

    @PostMapping("/sg/list")
    public ResponseData<?> listSg(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listServiceGateways(str(params, "id"), str(params, "vcnId"), reg(params)));
    }

    @PostMapping("/sg/create")
    public ResponseData<?> createSg(@RequestBody Map<String, Object> params) {
        vcnService.createServiceGateway(str(params, "id"), str(params, "vcnId"), str(params, "displayName"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/sg/delete")
    public ResponseData<?> deleteSg(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteServiceGateway(str(params, "id"), str(params, "sgId"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/sg/update")
    public ResponseData<?> updateSg(@RequestBody Map<String, Object> params) {
        Boolean block = params.get("blockTraffic") == null ? null : bool(params, "blockTraffic", false);
        vcnService.updateServiceGateway(str(params, "id"), str(params, "sgId"),
                str(params, "displayName"), block, reg(params));
        return ResponseData.ok();
    }

    // ---------- Route Table ----------

    @PostMapping("/rt/list")
    public ResponseData<?> listRt(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listRouteTables(str(params, "id"), str(params, "vcnId"), reg(params)));
    }

    @PostMapping("/rt/delete")
    public ResponseData<?> deleteRt(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteRouteTable(str(params, "id"), str(params, "rtId"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/rt/detail")
    public ResponseData<?> rtDetail(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.getRouteTable(str(params, "id"), str(params, "rtId"), reg(params)));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/rt/update")
    public ResponseData<?> updateRt(@RequestBody Map<String, Object> params) {
        java.util.List<Map<String, Object>> rules = null;
        Object rr = params.get("routeRules");
        if (rr instanceof java.util.List<?> list) {
            rules = new java.util.ArrayList<>();
            for (Object o : list) if (o instanceof Map) rules.add((Map<String, Object>) o);
        }
        vcnService.updateRouteTable(str(params, "id"), str(params, "rtId"),
                str(params, "displayName"), rules, reg(params));
        return ResponseData.ok();
    }

    // ---------- Security List ----------

    @PostMapping("/sl/list")
    public ResponseData<?> listSl(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listSecurityLists(str(params, "id"), str(params, "vcnId"), reg(params)));
    }

    @PostMapping("/sl/delete")
    public ResponseData<?> deleteSl(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteSecurityList(str(params, "id"), str(params, "slId"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/sl/detail")
    public ResponseData<?> slDetail(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.getSecurityList(str(params, "id"), str(params, "slId"), reg(params)));
    }

    @PostMapping("/sl/addRule")
    public ResponseData<?> slAddRule(@RequestBody Map<String, Object> params) {
        vcnService.addSecurityListRule(
                str(params, "id"), str(params, "slId"), str(params, "direction"),
                str(params, "protocol"), str(params, "source"),
                str(params, "portMin"), str(params, "portMax"), str(params, "description"),
                reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/sl/deleteRule")
    public ResponseData<?> slDeleteRule(@RequestBody Map<String, Object> params) {
        Object idx = params.get("ruleIndex");
        int i;
        try { i = Integer.parseInt(String.valueOf(idx)); }
        catch (Exception e) { throw new com.ociworker.exception.OciException("ruleIndex 非法"); }
        vcnService.deleteSecurityListRule(str(params, "id"), str(params, "slId"), str(params, "direction"), i, reg(params));
        return ResponseData.ok();
    }

    // ---------- DRG ----------

    @PostMapping("/drg/list")
    public ResponseData<?> listDrg(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listDrgs(str(params, "id"), reg(params)));
    }

    @PostMapping("/drg/create")
    public ResponseData<?> createDrg(@RequestBody Map<String, Object> params) {
        vcnService.createDrg(str(params, "id"), str(params, "compartmentId"), str(params, "displayName"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/drg/delete")
    public ResponseData<?> deleteDrg(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteDrg(str(params, "id"), str(params, "drgId"), reg(params));
        return ResponseData.ok();
    }

    // ---------- Local Peering Gateway ----------

    @PostMapping("/lpg/list")
    public ResponseData<?> listLpg(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listLocalPeeringGateways(str(params, "id"), str(params, "vcnId"), reg(params)));
    }

    @PostMapping("/lpg/create")
    public ResponseData<?> createLpg(@RequestBody Map<String, Object> params) {
        vcnService.createLocalPeeringGateway(str(params, "id"), str(params, "vcnId"), str(params, "displayName"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/lpg/connect")
    public ResponseData<?> connectLpg(@RequestBody Map<String, Object> params) {
        vcnService.connectLocalPeeringGateway(str(params, "id"), str(params, "lpgId"), str(params, "peerId"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/lpg/delete")
    public ResponseData<?> deleteLpg(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteLocalPeeringGateway(str(params, "id"), str(params, "lpgId"), reg(params));
        return ResponseData.ok();
    }

    @PostMapping("/lpg/update")
    public ResponseData<?> updateLpg(@RequestBody Map<String, Object> params) {
        vcnService.updateLocalPeeringGateway(str(params, "id"), str(params, "lpgId"), str(params, "displayName"), reg(params));
        return ResponseData.ok();
    }

    // ---------- helpers ----------

    private static String reg(Map<String, Object> params) {
        Object v = params == null ? null : params.get("region");
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String str(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static boolean bool(Map<String, Object> params, String key, boolean def) {
        Object v = params == null ? null : params.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }
}
