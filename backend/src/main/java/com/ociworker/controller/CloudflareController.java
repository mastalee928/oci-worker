package com.ociworker.controller;

import com.ociworker.model.entity.CfCfg;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.CloudflareService;
import com.ociworker.service.VerifyCodeService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cf")
public class CloudflareController {

    @Resource
    private CloudflareService cloudflareService;
    @Resource
    private VerifyCodeService verifyCodeService;

    @GetMapping("/account/config")
    public ResponseData<?> getAccountConfig() {
        return ResponseData.ok(cloudflareService.getAccountConfigForDisplay());
    }

    @PostMapping("/account/config")
    public ResponseData<?> saveAccountConfig(@RequestBody Map<String, String> params) {
        cloudflareService.saveAccountConfig(
                params.get("accountId"),
                params.get("apiToken"));
        return ResponseData.ok();
    }

    @PostMapping("/account/test")
    public ResponseData<?> testAccountConfig(@RequestBody Map<String, String> params) {
        String msg = cloudflareService.testAccountConfig(
                params.get("accountId"),
                params.get("apiToken"));
        return ResponseData.ok(msg);
    }

    @PostMapping("/zones/list")
    public ResponseData<?> listZones(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(cloudflareService.listZones(
                parseInteger(params.get("page"), 1),
                parseInteger(params.get("perPage"), 50)));
    }

    @PostMapping("/zones/listPage")
    public ResponseData<?> listZonesPage(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(cloudflareService.listZonesPage(
                parseInteger(params.get("page"), 1),
                parseInteger(params.get("perPage"), 50)));
    }

    @PostMapping("/zones/detail")
    public ResponseData<?> getZoneDetail(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.getZoneDetail(params.get("zoneId")));
    }

    @PostMapping("/zones/create")
    public ResponseData<?> createZone(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.createZone(params.get("name")));
    }

    @PostMapping("/zones/delete")
    public ResponseData<?> deleteZone(@RequestBody Map<String, String> params) {
        verifyCodeService.verifyCode("cfZoneDelete", params.get("verifyCode"));
        cloudflareService.deleteZone(params.get("zoneId"));
        return ResponseData.ok();
    }

    @PostMapping("/zones/paused")
    public ResponseData<?> setZonePaused(@RequestBody Map<String, Object> params) {
        verifyCodeService.verifyCode("cfZonePause", parseString(params.get("verifyCode")));
        return ResponseData.ok(cloudflareService.setZonePaused(
                parseString(params.get("zoneId")),
                parseBoolean(params.get("paused"), false)));
    }

    @PostMapping("/tunnel/list")
    public ResponseData<?> listTunnels() {
        return ResponseData.ok(cloudflareService.listTunnels());
    }

    @PostMapping("/tunnel/create")
    public ResponseData<?> createTunnel(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.createTunnel(params.get("name")));
    }

    @PostMapping("/tunnel/delete")
    public ResponseData<?> deleteTunnel(@RequestBody Map<String, String> params) {
        verifyCodeService.verifyCode("cfTunnelDelete", params.get("verifyCode"));
        cloudflareService.deleteTunnel(params.get("tunnelId"));
        return ResponseData.ok();
    }

    @PostMapping("/tunnel/token")
    public ResponseData<?> getTunnelToken(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.getTunnelRunToken(params.get("tunnelId")));
    }

    @PostMapping("/tunnel/connections")
    public ResponseData<?> listTunnelConnections(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.listTunnelConnections(params.get("tunnelId")));
    }

    @PostMapping("/tunnel/routes/list")
    public ResponseData<?> listTunnelRoutes(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.listTunnelRoutes(params.get("tunnelId")));
    }

    @PostMapping("/tunnel/routes/create")
    public ResponseData<?> createTunnelRoute(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.addTunnelRoute(
                params.get("tunnelId"),
                params.get("zoneId"),
                params.get("subdomain"),
                params.get("service")));
    }

    @PostMapping("/tunnel/routes/delete")
    public ResponseData<?> deleteTunnelRoute(@RequestBody Map<String, String> params) {
        cloudflareService.deleteTunnelRoute(params.get("tunnelId"), params.get("hostname"));
        return ResponseData.ok();
    }

    @PostMapping("/access-rules/list")
    public ResponseData<?> listIpAccessRules() {
        return ResponseData.ok(cloudflareService.listIpAccessRules());
    }

    @PostMapping("/access-rules/create")
    public ResponseData<?> createIpAccessRule(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.createIpAccessRule(
                params.get("target"),
                params.get("value"),
                params.get("mode"),
                params.get("notes")));
    }

    @PostMapping("/access-rules/delete")
    public ResponseData<?> deleteIpAccessRule(@RequestBody Map<String, String> params) {
        cloudflareService.deleteIpAccessRule(params.get("ruleId"));
        return ResponseData.ok();
    }

    /** @deprecated legacy cf_cfg table */
    @PostMapping("/cfg/list")
    public ResponseData<?> listCfg(@RequestBody Map<String, Integer> params) {
        return ResponseData.ok(cloudflareService.listCfgPage(
                params.getOrDefault("current", 1),
                params.getOrDefault("size", 10)));
    }

    @PostMapping("/cfg/add")
    public ResponseData<?> addCfg(@RequestBody CfCfg cfg) {
        cloudflareService.addCfg(cfg);
        return ResponseData.ok();
    }

    @PostMapping("/cfg/remove")
    public ResponseData<?> removeCfg(@RequestBody Map<String, String> params) {
        cloudflareService.removeCfg(params.get("id"));
        return ResponseData.ok();
    }

    @PostMapping("/dns/list")
    public ResponseData<?> listDns(@RequestBody Map<String, Object> params) {
        String zoneId = (String) params.get("zoneId");
        if (zoneId != null && !zoneId.isBlank()) {
            return ResponseData.ok(cloudflareService.listDnsRecords(
                    zoneId,
                    parseInteger(params.get("page"), 1),
                    parseInteger(params.get("perPage"), 50)));
        }
        return ResponseData.ok(cloudflareService.listDnsRecordsByCfgId(
                (String) params.get("cfgId"),
                parseInteger(params.get("page"), 1),
                parseInteger(params.get("perPage"), 50)));
    }

    @PostMapping("/dns/listPage")
    public ResponseData<?> listDnsPage(@RequestBody Map<String, Object> params) {
        String zoneId = parseString(params.get("zoneId"));
        if (zoneId == null || zoneId.isBlank()) {
            throw new com.ociworker.exception.OciException("请选择 Zone");
        }
        return ResponseData.ok(cloudflareService.listDnsRecordsPage(
                zoneId,
                parseInteger(params.get("page"), 1),
                parseInteger(params.get("perPage"), 50),
                parseString(params.get("search")),
                parseString(params.get("type"))));
    }

    @PostMapping("/dns/add")
    public ResponseData<?> addDns(@RequestBody Map<String, Object> params) {
        String zoneId = (String) params.get("zoneId");
        if (zoneId == null || zoneId.isBlank()) {
            throw new com.ociworker.exception.OciException("请提供 zoneId");
        }
        cloudflareService.addDnsRecord(
                zoneId,
                (String) params.get("type"),
                (String) params.get("name"),
                (String) params.get("content"),
                params.containsKey("proxied") ? parseBoolean(params.get("proxied"), false) : null,
                parseInteger(params.get("ttl"), 1),
                parseInteger(params.get("priority"), null),
                (String) params.get("comment"));
        return ResponseData.ok();
    }

    @PostMapping("/dns/update")
    public ResponseData<?> updateDns(@RequestBody Map<String, Object> params) {
        cloudflareService.updateDnsRecord(
                (String) params.get("zoneId"),
                (String) params.get("recordId"),
                (String) params.get("type"),
                (String) params.get("name"),
                (String) params.get("content"),
                params.containsKey("proxied") ? parseBoolean(params.get("proxied"), false) : null,
                parseInteger(params.get("ttl"), 1),
                parseInteger(params.get("priority"), null),
                (String) params.get("comment"));
        return ResponseData.ok();
    }

    @PostMapping("/dns/delete")
    public ResponseData<?> deleteDns(@RequestBody Map<String, String> params) {
        cloudflareService.deleteDnsRecord(params.get("zoneId"), params.get("recordId"));
        return ResponseData.ok();
    }

    @PostMapping("/dns/export")
    public ResponseData<?> exportDns(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.exportDnsRecords(params.get("zoneId")));
    }

    @PostMapping("/dns/import")
    public ResponseData<?> importDns(@RequestBody Map<String, Object> params) {
        cloudflareService.importDnsRecords(
                (String) params.get("zoneId"),
                (String) params.get("bindContent"),
                params.containsKey("proxied") ? parseBoolean(params.get("proxied"), false) : null);
        return ResponseData.ok();
    }

    @PostMapping("/dns/dnssec/get")
    public ResponseData<?> getDnssec(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.getDnssec(params.get("zoneId")));
    }

    @PostMapping("/dns/dnssec/set")
    public ResponseData<?> setDnssec(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.setDnssec(
                params.get("zoneId"), params.get("status")));
    }

    @PostMapping("/email/settings")
    public ResponseData<?> emailSettings(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.getEmailRoutingSettings(params.get("zoneId")));
    }

    @PostMapping("/email/enable")
    public ResponseData<?> emailEnable(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.enableEmailRouting(params.get("zoneId")));
    }

    @PostMapping("/email/disable")
    public ResponseData<?> emailDisable(@RequestBody Map<String, String> params) {
        cloudflareService.disableEmailRouting(params.get("zoneId"));
        return ResponseData.ok();
    }

    @PostMapping("/email/dns/get")
    public ResponseData<?> emailDnsGet(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.getEmailRoutingDns(params.get("zoneId")));
    }

    @PostMapping("/email/dns/lock")
    public ResponseData<?> emailDnsLock(@RequestBody Map<String, String> params) {
        cloudflareService.lockEmailDns(params.get("zoneId"));
        return ResponseData.ok();
    }

    @PostMapping("/email/dns/unlock")
    public ResponseData<?> emailDnsUnlock(@RequestBody Map<String, String> params) {
        cloudflareService.unlockEmailDns(params.get("zoneId"));
        return ResponseData.ok();
    }

    @PostMapping("/email/rules/list")
    public ResponseData<?> emailRulesList(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.listEmailRoutingRules(params.get("zoneId")));
    }

    @PostMapping("/email/rules/create")
    public ResponseData<?> emailRulesCreate(@RequestBody Map<String, Object> params) {
        String zoneId = (String) params.get("zoneId");
        String customAddress = (String) params.get("customAddress");
        String name = (String) params.get("name");
        Boolean enabled = params.get("enabled") == null ? null : parseBoolean(params.get("enabled"), true);
        if (params.containsKey("actionType") || params.containsKey("destinations")
                || params.containsKey("workerName") || params.containsKey("priority")) {
            return ResponseData.ok(cloudflareService.createEmailRoutingRule(
                    zoneId,
                    name,
                    customAddress,
                    (String) params.get("actionType"),
                    parseStringList(params.get("destinations")),
                    (String) params.get("workerName"),
                    parseInteger(params.get("priority"), null),
                    enabled));
        }
        return ResponseData.ok(cloudflareService.createEmailRoutingRule(
                zoneId,
                name,
                customAddress,
                (String) params.get("destination"),
                enabled == null || enabled));
    }

    @PostMapping("/email/rules/delete")
    public ResponseData<?> emailRulesDelete(@RequestBody Map<String, String> params) {
        cloudflareService.deleteEmailRoutingRule(params.get("zoneId"), params.get("ruleId"));
        return ResponseData.ok();
    }

    @PostMapping("/email/rules/update")
    public ResponseData<?> emailRulesUpdate(@RequestBody Map<String, Object> params) {
        Boolean enabled = params.get("enabled") == null ? null : parseBoolean(params.get("enabled"), true);
        if (params.containsKey("actionType") || params.containsKey("destinations")
                || params.containsKey("workerName") || params.containsKey("customAddress")
                || params.containsKey("name") || params.containsKey("priority")) {
            return ResponseData.ok(cloudflareService.updateEmailRoutingRule(
                    (String) params.get("zoneId"),
                    (String) params.get("ruleId"),
                    (String) params.get("name"),
                    (String) params.get("customAddress"),
                    (String) params.get("actionType"),
                    parseStringList(params.get("destinations")),
                    (String) params.get("workerName"),
                    enabled,
                    parseInteger(params.get("priority"), null)));
        }
        return ResponseData.ok(cloudflareService.updateEmailRoutingRule(
                (String) params.get("zoneId"),
                (String) params.get("ruleId"),
                null,
                null,
                null,
                null,
                null,
                enabled,
                null));
    }

    @PostMapping("/email/rules/catch-all/get")
    public ResponseData<?> emailCatchAllGet(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.getCatchAllRule(params.get("zoneId")));
    }

    @PostMapping("/email/rules/catch-all/update")
    public ResponseData<?> emailCatchAllUpdate(@RequestBody Map<String, Object> params) {
        Boolean enabled = params.get("enabled") == null ? null : parseBoolean(params.get("enabled"), true);
        return ResponseData.ok(cloudflareService.updateCatchAllRule(
                (String) params.get("zoneId"),
                (String) params.get("actionType"),
                parseStringList(params.get("destinations")),
                (String) params.get("workerName"),
                enabled));
    }

    @PostMapping("/email/destinations/list")
    public ResponseData<?> emailDestinationsList() {
        return ResponseData.ok(cloudflareService.listEmailDestinations());
    }

    @PostMapping("/email/destinations/create")
    public ResponseData<?> emailDestinationsCreate(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.createEmailDestination(params.get("email")));
    }

    @PostMapping("/email/destinations/resend")
    public ResponseData<?> emailDestinationsResend(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.resendEmailDestination(params.get("email")));
    }

    @PostMapping("/email/destinations/delete")
    public ResponseData<?> emailDestinationsDelete(@RequestBody Map<String, String> params) {
        cloudflareService.deleteEmailDestination(params.get("destinationId"));
        return ResponseData.ok();
    }

    @PostMapping("/email/workers/list")
    public ResponseData<?> emailWorkersList() {
        return ResponseData.ok(cloudflareService.listWorkers());
    }

    @PostMapping("/workers/scripts/list")
    public ResponseData<?> workerScriptsList() {
        return ResponseData.ok(cloudflareService.listWorkerScripts());
    }

    @PostMapping("/workers/pages/usage")
    public ResponseData<?> workersPagesUsage() {
        return ResponseData.ok(cloudflareService.getWorkersUsageSummary());
    }

    @PostMapping("/workers/pages/applications/list")
    public ResponseData<?> workersPagesApplicationsList() {
        return ResponseData.ok(cloudflareService.listWorkersAndPagesApplications());
    }

    @PostMapping("/workers/pages/templates/list")
    public ResponseData<?> workersPagesTemplatesList() {
        return ResponseData.ok(cloudflareService.listWorkerTemplates());
    }

    @PostMapping("/workers/subdomain/info")
    public ResponseData<?> workersSubdomainInfo() {
        return ResponseData.ok(cloudflareService.getWorkersSubdomainInfo());
    }

    @PostMapping("/workers/pages/templates/preview")
    public ResponseData<?> workersPagesTemplatePreview(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.getWorkersPagesTemplatePreview(params.get("templateId")));
    }

    @PostMapping("/workers/deploy")
    public ResponseData<?> workerDeploy(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.deployWorker(params.get("name"), params.get("script")));
    }

    @PostMapping("/workers/script/get")
    public ResponseData<?> workerScriptGet(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.getWorkerScriptContent(params.get("name")));
    }

    @PostMapping("/workers/script/update")
    public ResponseData<?> workerScriptUpdate(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.updateWorkerScript(params.get("name"), params.get("script")));
    }

    @PostMapping("/workers/rename")
    public ResponseData<?> workerRename(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.renameWorkerScript(params.get("name"), params.get("newName")));
    }

    @PostMapping("/workers/delete")
    public ResponseData<?> workerDelete(@RequestBody Map<String, String> params) {
        verifyCodeService.verifyCode("cfWorkerDelete", params.get("verifyCode"));
        cloudflareService.deleteWorkerScript(params.get("name"));
        return ResponseData.ok();
    }

    @PostMapping("/workers/create/hello-world")
    public ResponseData<?> workerCreateHelloWorld(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.createWorkerHelloWorld(
                params.get("name"), params.get("script")));
    }

    @PostMapping("/workers/create/template")
    public ResponseData<?> workerCreateTemplate(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.createWorkerFromTemplate(
                params.get("name"), params.get("templateId"), params.get("script")));
    }

    @PostMapping("/pages/create/template")
    public ResponseData<?> pagesCreateTemplate(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.createPagesFromTemplate(
                params.get("name"), params.get("templateId")));
    }

    @PostMapping("/pages/deploy/static")
    public ResponseData<?> pagesDeployStatic(@RequestBody Map<String, Object> params) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> encoded = (List<Map<String, String>>) params.get("files");
        return ResponseData.ok(cloudflareService.deployPagesStaticFromUpload(
                (String) params.get("name"),
                encoded));
    }

    @PostMapping("/ssl/get")
    public ResponseData<?> sslGet(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.getSslSettings(params.get("zoneId")));
    }

    @PostMapping("/ssl/set")
    public ResponseData<?> sslSet(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(cloudflareService.updateSslSetting(
                (String) params.get("zoneId"),
                (String) params.get("settingId"),
                params.get("value")));
    }

    @PostMapping("/cache/get")
    public ResponseData<?> cacheGet(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.getCacheSettings(params.get("zoneId")));
    }

    @PostMapping("/cache/set")
    public ResponseData<?> cacheSet(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(cloudflareService.updateCacheSetting(
                (String) params.get("zoneId"),
                (String) params.get("settingId"),
                params.get("value")));
    }

    @PostMapping("/cache/purge")
    public ResponseData<?> cachePurge(@RequestBody Map<String, Object> params) {
        cloudflareService.purgeZoneCache(
                (String) params.get("zoneId"),
                parseBoolean(params.get("purgeEverything"), false),
                parseStringList(params.get("files")));
        return ResponseData.ok();
    }

    @PostMapping("/security/firewall/list")
    public ResponseData<?> firewallList(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.listFirewallRules(params.get("zoneId")));
    }

    @PostMapping("/security/firewall/create")
    public ResponseData<?> firewallCreate(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(cloudflareService.createFirewallRule(
                parseString(params.get("zoneId")),
                parseString(params.get("action")),
                parseString(params.get("expression")),
                parseString(params.get("description")),
                parseBoolean(params.get("paused"), false)));
    }

    @PostMapping("/security/firewall/paused")
    public ResponseData<?> firewallPaused(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(cloudflareService.setFirewallRulePaused(
                parseString(params.get("zoneId")),
                parseString(params.get("rulesetId")),
                parseString(params.get("ruleId")),
                parseBoolean(params.get("paused"), false)));
    }

    @PostMapping("/security/firewall/update")
    public ResponseData<?> firewallUpdate(@RequestBody Map<String, Object> params) {
        Boolean paused = params.containsKey("paused") ? parseBoolean(params.get("paused"), false) : null;
        return ResponseData.ok(cloudflareService.updateFirewallRule(
                parseString(params.get("zoneId")),
                parseString(params.get("rulesetId")),
                parseString(params.get("ruleId")),
                parseString(params.get("action")),
                params.containsKey("description") ? parseString(params.get("description")) : null,
                parseString(params.get("expression")),
                paused));
    }

    @PostMapping("/security/firewall/delete")
    public ResponseData<?> firewallDelete(@RequestBody Map<String, String> params) {
        cloudflareService.deleteFirewallRule(
                params.get("zoneId"), params.get("rulesetId"), params.get("ruleId"));
        return ResponseData.ok();
    }

    @PostMapping("/security/protection/get")
    public ResponseData<?> securityProtectionGet(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.getSecuritySettings(params.get("zoneId")));
    }

    @PostMapping("/security/protection/set")
    public ResponseData<?> securityProtectionSet(@RequestBody Map<String, Object> params) {
        return ResponseData.ok(cloudflareService.updateSecuritySetting(
                parseString(params.get("zoneId")),
                parseString(params.get("settingId")),
                params.get("value")));
    }

    @PostMapping("/workers/routes/list")
    public ResponseData<?> workersRoutesList(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.listWorkersRoutes(params.get("zoneId")));
    }

    @PostMapping("/workers/routes/create")
    public ResponseData<?> workersRoutesCreate(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.createWorkersRoute(
                params.get("zoneId"),
                params.get("pattern"),
                params.get("script")));
    }

    @PostMapping("/workers/routes/delete")
    public ResponseData<?> workersRoutesDelete(@RequestBody Map<String, String> params) {
        cloudflareService.deleteWorkersRoute(params.get("zoneId"), params.get("routeId"));
        return ResponseData.ok();
    }

    @PostMapping("/rules/list")
    public ResponseData<?> rulesList(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.listZoneRules(params.get("zoneId")));
    }

    /** @deprecated 不支持 cfat_ 账户令牌 */
    @PostMapping("/rules/pagerules/list")
    public ResponseData<?> pageRulesList(@RequestBody Map<String, String> params) {
        return ResponseData.ok(cloudflareService.listPageRules(params.get("zoneId")));
    }

    @PostMapping("/rules/pagerules/delete")
    public ResponseData<?> pageRulesDelete(@RequestBody Map<String, String> params) {
        cloudflareService.deletePageRule(params.get("zoneId"), params.get("ruleId"));
        return ResponseData.ok();
    }

    private static boolean parseBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static Integer parseInteger(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String parseString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseStringList(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }
        return List.of(String.valueOf(value));
    }
}
