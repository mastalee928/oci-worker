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
        return ResponseData.ok(vcnService.listVcns(str(params, "id")));
    }

    @PostMapping("/create")
    public ResponseData<?> create(@RequestBody Map<String, Object> params) {
        vcnService.createVcn(
                str(params, "id"),
                str(params, "compartmentId"),
                str(params, "displayName"),
                str(params, "cidrBlock"),
                str(params, "dnsLabel"),
                bool(params, "createIgw", true)
        );
        return ResponseData.ok();
    }

    @PostMapping("/preview-delete")
    public ResponseData<?> previewDelete(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.previewVcnDelete(str(params, "id"), str(params, "vcnId")));
    }

    @PostMapping("/delete")
    public ResponseData<?> delete(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteVcn(str(params, "id"), str(params, "vcnId"), bool(params, "cascade", true));
        return ResponseData.ok();
    }

    // ---------- Subnet ----------

    @PostMapping("/subnet/list")
    public ResponseData<?> listSubnets(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listSubnets(str(params, "id"), str(params, "vcnId")));
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
                params.get("prohibitPublicIp") == null ? null : bool(params, "prohibitPublicIp", false)
        );
        return ResponseData.ok();
    }

    @PostMapping("/subnet/delete")
    public ResponseData<?> deleteSubnet(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteSubnet(str(params, "id"), str(params, "subnetId"));
        return ResponseData.ok();
    }

    // ---------- Internet Gateway ----------

    @PostMapping("/igw/list")
    public ResponseData<?> listIgw(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listInternetGateways(str(params, "id"), str(params, "vcnId")));
    }

    @PostMapping("/igw/create")
    public ResponseData<?> createIgw(@RequestBody Map<String, Object> params) {
        vcnService.createInternetGateway(
                str(params, "id"),
                str(params, "vcnId"),
                str(params, "displayName"),
                bool(params, "isEnabled", true)
        );
        return ResponseData.ok();
    }

    @PostMapping("/igw/delete")
    public ResponseData<?> deleteIgw(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteInternetGateway(str(params, "id"), str(params, "igwId"));
        return ResponseData.ok();
    }

    // ---------- NAT Gateway ----------

    @PostMapping("/nat/list")
    public ResponseData<?> listNat(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listNatGateways(str(params, "id"), str(params, "vcnId")));
    }

    @PostMapping("/nat/create")
    public ResponseData<?> createNat(@RequestBody Map<String, Object> params) {
        vcnService.createNatGateway(str(params, "id"), str(params, "vcnId"), str(params, "displayName"));
        return ResponseData.ok();
    }

    @PostMapping("/nat/delete")
    public ResponseData<?> deleteNat(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteNatGateway(str(params, "id"), str(params, "natId"));
        return ResponseData.ok();
    }

    // ---------- Service Gateway ----------

    @PostMapping("/sg/list")
    public ResponseData<?> listSg(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listServiceGateways(str(params, "id"), str(params, "vcnId")));
    }

    @PostMapping("/sg/create")
    public ResponseData<?> createSg(@RequestBody Map<String, Object> params) {
        vcnService.createServiceGateway(str(params, "id"), str(params, "vcnId"), str(params, "displayName"));
        return ResponseData.ok();
    }

    @PostMapping("/sg/delete")
    public ResponseData<?> deleteSg(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteServiceGateway(str(params, "id"), str(params, "sgId"));
        return ResponseData.ok();
    }

    // ---------- Route Table ----------

    @PostMapping("/rt/list")
    public ResponseData<?> listRt(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listRouteTables(str(params, "id"), str(params, "vcnId")));
    }

    @PostMapping("/rt/delete")
    public ResponseData<?> deleteRt(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteRouteTable(str(params, "id"), str(params, "rtId"));
        return ResponseData.ok();
    }

    // ---------- Security List ----------

    @PostMapping("/sl/list")
    public ResponseData<?> listSl(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listSecurityLists(str(params, "id"), str(params, "vcnId")));
    }

    @PostMapping("/sl/delete")
    public ResponseData<?> deleteSl(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteSecurityList(str(params, "id"), str(params, "slId"));
        return ResponseData.ok();
    }

    // ---------- DRG ----------

    @PostMapping("/drg/list")
    public ResponseData<?> listDrg(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listDrgs(str(params, "id")));
    }

    @PostMapping("/drg/create")
    public ResponseData<?> createDrg(@RequestBody Map<String, Object> params) {
        vcnService.createDrg(str(params, "id"), str(params, "compartmentId"), str(params, "displayName"));
        return ResponseData.ok();
    }

    @PostMapping("/drg/delete")
    public ResponseData<?> deleteDrg(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteDrg(str(params, "id"), str(params, "drgId"));
        return ResponseData.ok();
    }

    // ---------- Local Peering Gateway ----------

    @PostMapping("/lpg/list")
    public ResponseData<?> listLpg(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(vcnService.listLocalPeeringGateways(str(params, "id"), str(params, "vcnId")));
    }

    @PostMapping("/lpg/create")
    public ResponseData<?> createLpg(@RequestBody Map<String, Object> params) {
        vcnService.createLocalPeeringGateway(str(params, "id"), str(params, "vcnId"), str(params, "displayName"));
        return ResponseData.ok();
    }

    @PostMapping("/lpg/connect")
    public ResponseData<?> connectLpg(@RequestBody Map<String, Object> params) {
        vcnService.connectLocalPeeringGateway(str(params, "id"), str(params, "lpgId"), str(params, "peerId"));
        return ResponseData.ok();
    }

    @PostMapping("/lpg/delete")
    public ResponseData<?> deleteLpg(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("deleteVcn", str(params, "verifyCode"));
        vcnService.deleteLocalPeeringGateway(str(params, "id"), str(params, "lpgId"));
        return ResponseData.ok();
    }

    // ---------- helpers ----------

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
