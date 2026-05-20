package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.oracle.bmc.Region;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.onesubscription.SubscribedServiceClient;
import com.oracle.bmc.onesubscription.model.SubscribedServiceSummary;
import com.oracle.bmc.onesubscription.requests.ListSubscribedServicesRequest;
import com.oracle.bmc.onesubscription.responses.ListSubscribedServicesResponse;
import com.oracle.bmc.tenantmanagercontrolplane.SubscriptionClient;
import com.oracle.bmc.tenantmanagercontrolplane.model.AssignedSubscriptionSummary;
import com.oracle.bmc.tenantmanagercontrolplane.requests.ListAssignedSubscriptionsRequest;
import com.oracle.bmc.tenantmanagercontrolplane.responses.ListAssignedSubscriptionsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Organizations 订购：Assigned Subscriptions + OneSubscription Subscribed Services。
 * 仅展示 API 返回值（含 fundedAllocationValue、availableAmount），不做推算。
 */
@Slf4j
@Service
public class OrganizationSubscriptionService {

    private static final int PAGE_LIMIT = 100;

    /**
     * @param ospSubscriptionRef OSP Gateway 返回的订阅引用（可能是 ocid1.* 或订阅编号如 77007627）
     */
    public Map<String, Object> fetchOrganizationSubscription(
            OciClientService oci,
            String tenancyId,
            String fallbackRegion,
            String ospSubscriptionRef,
            List<String> extraSubscriptionOcids) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", Boolean.FALSE);
        out.put("reason", null);
        out.put("assignedSubscriptions", new ArrayList<>());
        out.put("subscribedServices", new ArrayList<>());

        if (StrUtil.isBlank(tenancyId)) {
            out.put("reason", "缺少 tenancy OCID");
            return out;
        }

        String region = UsageCostService.resolveTenancyHomeRegionName(
                oci.getIdentityClient(), tenancyId, fallbackRegion);

        List<Map<String, Object>> assignedRows = new ArrayList<>();
        List<Map<String, Object>> serviceRows = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        try (SubscriptionClient subClient = buildSubscriptionClient(oci)) {
            setRegion(subClient, region);
            assignedRows.addAll(listAssignedSubscriptions(subClient, tenancyId));
        } catch (BmcException e) {
            log.warn("listAssignedSubscriptions failed: {}", e.getMessage());
            notes.add(formatOrgError("订购分配", e));
        } catch (Exception e) {
            log.warn("listAssignedSubscriptions failed: {}", e.getMessage());
            notes.add("订购分配查询失败：" + e.getMessage());
        }

        Set<String> subscriptionIdsToQuery =
                resolveSubscriptionOcidCandidates(assignedRows, ospSubscriptionRef, extraSubscriptionOcids);

        if (subscriptionIdsToQuery.isEmpty()) {
            out.put("assignedSubscriptions", assignedRows);
            out.put("subscribedServices", serviceRows);
            notes.add(buildNoOcidReason(ospSubscriptionRef, assignedRows));
            out.put("reason", String.join("；", notes));
            return out;
        }

        try (SubscribedServiceClient svcClient = buildSubscribedServiceClient(oci)) {
            setRegion(svcClient, region);
            for (String subId : subscriptionIdsToQuery) {
                try {
                    serviceRows.addAll(listSubscribedServices(svcClient, tenancyId, subId));
                } catch (BmcException e) {
                    log.warn("listSubscribedServices {} failed: {}", subId, e.getMessage());
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("subscriptionId", subId);
                    err.put("error", formatOrgError("子服务额度", e));
                    serviceRows.add(err);
                }
            }
        } catch (Exception e) {
            log.warn("SubscribedService client failed: {}", e.getMessage());
            out.put("assignedSubscriptions", assignedRows);
            out.put("reason", "子服务额度查询失败：" + e.getMessage());
            return out;
        }

        out.put("available", Boolean.TRUE);
        out.put("assignedSubscriptions", assignedRows);
        out.put("subscribedServices", serviceRows);
        if (assignedRows.isEmpty() && serviceRows.isEmpty()) {
            out.put("available", Boolean.FALSE);
            notes.add("订购与子服务接口均无数据");
        }
        if (!notes.isEmpty()) {
            out.put("reason", String.join("；", notes));
        }
        return out;
    }

    static Set<String> resolveSubscriptionOcidCandidates(
            List<Map<String, Object>> assignedRows,
            String ospSubscriptionRef,
            List<String> extraSubscriptionOcids) {
        Set<String> ids = new LinkedHashSet<>();
        if (extraSubscriptionOcids != null) {
            for (String id : extraSubscriptionOcids) {
                if (OspSubscriptionEnricher.isOciOcid(id)
                        && !OspSubscriptionEnricher.isOrganizationsSubscriptionOcid(id)) {
                    ids.add(id.trim());
                }
            }
        }
        if (assignedRows != null) {
            for (Map<String, Object> row : assignedRows) {
                String id = row.get("id") == null ? null : String.valueOf(row.get("id")).trim();
                if (OspSubscriptionEnricher.isOciOcid(id)) {
                    ids.add(id);
                }
            }
        }
        if (OspSubscriptionEnricher.isOciOcid(ospSubscriptionRef)) {
            ids.add(ospSubscriptionRef.trim());
        } else if (StrUtil.isNotBlank(ospSubscriptionRef) && assignedRows != null) {
            String ref = ospSubscriptionRef.trim();
            for (Map<String, Object> row : assignedRows) {
                String num = row.get("subscriptionNumber") == null
                        ? null : String.valueOf(row.get("subscriptionNumber")).trim();
                if (ref.equals(num)) {
                    String id = row.get("id") == null ? null : String.valueOf(row.get("id")).trim();
                    if (OspSubscriptionEnricher.isOciOcid(id)) {
                        ids.add(id);
                    }
                }
            }
        }
        return ids;
    }

    private static String buildNoOcidReason(String ospRef, List<Map<String, Object>> assignedRows) {
        if (assignedRows == null || assignedRows.isEmpty()) {
            return "无 Assigned Subscription 记录"
                    + (StrUtil.isNotBlank(ospRef) ? "（OSP 引用：" + ospRef + "）" : "");
        }
        if (StrUtil.isNotBlank(ospRef) && !OspSubscriptionEnricher.isOciOcid(ospRef)) {
            return "OSP 订阅引用「" + ospRef + "」为编号非 OCID；Subscribed Service / Rewards 需 ocid1.*，请对照下方 Assigned 表中的订阅 ID";
        }
        return "未解析到可用于 Subscribed Service 的订阅 OCID";
    }

    private static SubscriptionClient buildSubscriptionClient(OciClientService oci) {
        var b = SubscriptionClient.builder();
        OciProxyConfigService pxy = OciProxyConfigService.instance();
        if (pxy == null || !pxy.ociUsesExplicitClientProxy()) {
            b = b.additionalClientConfigurator(OciProxyConfigService.ociSdkJerseyDirectConfigurator());
        }
        return b.build(oci.getProvider());
    }

    private static SubscribedServiceClient buildSubscribedServiceClient(OciClientService oci) {
        var b = SubscribedServiceClient.builder();
        OciProxyConfigService pxy = OciProxyConfigService.instance();
        if (pxy == null || !pxy.ociUsesExplicitClientProxy()) {
            b = b.additionalClientConfigurator(OciProxyConfigService.ociSdkJerseyDirectConfigurator());
        }
        return b.build(oci.getProvider());
    }

    private static void setRegion(Object client, String regionId) {
        try {
            if (client instanceof SubscriptionClient c) {
                c.setRegion(Region.fromRegionId(regionId));
            } else if (client instanceof SubscribedServiceClient c) {
                c.setRegion(Region.fromRegionId(regionId));
            }
        } catch (Exception e) {
            if (client instanceof SubscriptionClient c) {
                c.setRegion(Region.US_ASHBURN_1);
            } else if (client instanceof SubscribedServiceClient c) {
                c.setRegion(Region.US_ASHBURN_1);
            }
        }
    }

    private static List<Map<String, Object>> listAssignedSubscriptions(
            SubscriptionClient client, String tenancyId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String page = null;
        do {
            ListAssignedSubscriptionsRequest.Builder req = ListAssignedSubscriptionsRequest.builder()
                    .compartmentId(tenancyId)
                    .limit(PAGE_LIMIT);
            if (StrUtil.isNotBlank(page)) {
                req.page(page);
            }
            ListAssignedSubscriptionsResponse resp = client.listAssignedSubscriptions(req.build());
            var col = resp == null ? null : resp.getAssignedSubscriptionCollection();
            if (col == null || col.getItems() == null) {
                break;
            }
            for (AssignedSubscriptionSummary item : col.getItems()) {
                if (item != null) {
                    rows.add(mapAssignedSubscription(item));
                }
            }
            page = resp.getOpcNextPage();
        } while (StrUtil.isNotBlank(page));
        return rows;
    }

    private static Map<String, Object> mapAssignedSubscription(AssignedSubscriptionSummary item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("compartmentId", item.getCompartmentId());
        m.put("serviceName", item.getServiceName());
        m.put("timeCreated", formatInstant(item.getTimeCreated()));
        m.put("timeUpdated", formatInstant(item.getTimeUpdated()));
        m.put("entityVersion", enumValue(tryInvoke(item, "getEntityVersion")));

        String lifecycle = firstString(item,
                "getLifecycleState", "getLifecycleStateDetails");
        m.put("lifecycleState", lifecycle);
        m.put("subscriptionNumber", asString(tryInvoke(item, "getSubscriptionNumber")));
        m.put("currencyCode", asString(tryInvoke(item, "getCurrencyCode")));
        return m;
    }

    private static List<Map<String, Object>> listSubscribedServices(
            SubscribedServiceClient client, String tenancyId, String subscriptionId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String page = null;
        do {
            ListSubscribedServicesRequest.Builder req = ListSubscribedServicesRequest.builder()
                    .compartmentId(tenancyId)
                    .subscriptionId(subscriptionId)
                    .limit(PAGE_LIMIT);
            if (StrUtil.isNotBlank(page)) {
                req.page(page);
            }
            ListSubscribedServicesResponse resp = client.listSubscribedServices(req.build());
            List<SubscribedServiceSummary> items = resp == null ? null : resp.getItems();
            if (items == null || items.isEmpty()) {
                break;
            }
            for (SubscribedServiceSummary item : items) {
                if (item != null) {
                    rows.add(mapSubscribedService(item, subscriptionId));
                }
            }
            page = resp.getOpcNextPage();
        } while (StrUtil.isNotBlank(page));
        return rows;
    }

    private static Map<String, Object> mapSubscribedService(SubscribedServiceSummary item, String subscriptionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subscriptionId", subscriptionId);
        m.put("orderNumber", item.getOrderNumber());
        m.put("status", item.getStatus());
        m.put("fundedAllocationValue", item.getFundedAllocationValue());
        m.put("availableAmount", item.getAvailableAmount());
        m.put("creditPercentage", item.getCreditPercentage());
        m.put("timeStart", formatInstant(item.getTimeStart()));
        m.put("timeEnd", formatInstant(item.getTimeEnd()));
        m.put("timeCreated", formatInstant(item.getTimeCreated()));
        m.put("timeUpdated", formatInstant(item.getTimeUpdated()));

        Object product = item.getProduct();
        if (product != null) {
            Object name = tryInvoke(product, "getName");
            if (name == null) name = tryInvoke(product, "getProductName");
            if (name == null) name = tryInvoke(product, "getDisplayName");
            m.put("productName", name == null ? null : String.valueOf(name));
        }
        return m;
    }

    private static String formatOrgError(String apiLabel, BmcException e) {
        String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
        int code = e.getStatusCode();
        if (code == 404) {
            return apiLabel + " 无数据（404）";
        }
        if (code == 401 || code == 403 || msg.contains("NotAuthorized")) {
            return apiLabel + " 权限不足（需 inspect/read 订购与子服务相关权限）：" + msg;
        }
        return apiLabel + " 查询失败（HTTP " + code + "）：" + msg;
    }

    private static String formatInstant(Date d) {
        return d == null ? null : d.toInstant().toString();
    }

    private static String enumValue(Object v) {
        if (v == null) return null;
        if (v instanceof Enum<?> e) {
            Object val = tryInvoke(e, "getValue");
            return val != null ? String.valueOf(val) : e.name();
        }
        return String.valueOf(v);
    }

    private static String firstString(Object target, String... getters) {
        for (String g : getters) {
            Object v = tryInvoke(target, g);
            String s = asString(v);
            if (StrUtil.isNotBlank(s)) return s;
        }
        return null;
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private static Object tryInvoke(Object target, String method) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }
}
