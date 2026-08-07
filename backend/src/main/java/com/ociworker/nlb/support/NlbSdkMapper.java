package com.ociworker.nlb.support;

import com.oracle.bmc.networkloadbalancer.model.Backend;
import com.oracle.bmc.networkloadbalancer.model.BackendHealth;
import com.oracle.bmc.networkloadbalancer.model.BackendOperationalStatus;
import com.oracle.bmc.networkloadbalancer.model.BackendSet;
import com.oracle.bmc.networkloadbalancer.model.BackendSetHealth;
import com.oracle.bmc.networkloadbalancer.model.BackendSetSummary;
import com.oracle.bmc.networkloadbalancer.model.BackendSummary;
import com.oracle.bmc.networkloadbalancer.model.HealthCheckResult;
import com.oracle.bmc.networkloadbalancer.model.HealthChecker;
import com.oracle.bmc.networkloadbalancer.model.IpAddress;
import com.oracle.bmc.networkloadbalancer.model.Listener;
import com.oracle.bmc.networkloadbalancer.model.ListenerSummary;
import com.oracle.bmc.networkloadbalancer.model.NetworkLoadBalancer;
import com.oracle.bmc.networkloadbalancer.model.NetworkLoadBalancerHealth;
import com.oracle.bmc.networkloadbalancer.model.NetworkLoadBalancerSummary;
import com.oracle.bmc.networkloadbalancer.model.WorkRequest;
import com.oracle.bmc.networkloadbalancer.model.WorkRequestError;
import com.oracle.bmc.networkloadbalancer.model.WorkRequestLogEntry;
import com.oracle.bmc.networkloadbalancer.model.WorkRequestResource;
import com.oracle.bmc.networkloadbalancer.model.WorkRequestSummary;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts OCI SDK models into stable JSON-friendly module responses. */
public final class NlbSdkMapper {

    private NlbSdkMapper() {
    }

    public static Map<String, Object> networkLoadBalancerSummary(
            NetworkLoadBalancerSummary value,
            String healthStatus) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value == null) return out;
        putNlbFields(out,
                value.getId(), value.getCompartmentId(), value.getDisplayName(),
                value.getLifecycleState() == null ? null : value.getLifecycleState().getValue(),
                value.getLifecycleDetails(),
                value.getNlbIpVersion() == null ? null : value.getNlbIpVersion().getValue(),
                value.getTimeCreated(), value.getTimeUpdated(), value.getIpAddresses(),
                value.getIsPrivate(), value.getIsPreserveSourceDestination(),
                value.getIsSymmetricHashEnabled(), value.getSubnetId(),
                value.getNetworkSecurityGroupIds(), value.getListeners(), value.getBackendSets(),
                value.getFreeformTags(), value.getDefinedTags(), value.getSecurityAttributes());
        out.put("healthStatus", healthStatus);
        return out;
    }

    public static Map<String, Object> networkLoadBalancer(
            NetworkLoadBalancer value,
            String etag,
            NetworkLoadBalancerHealth health) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value == null) return out;
        putNlbFields(out,
                value.getId(), value.getCompartmentId(), value.getDisplayName(),
                value.getLifecycleState() == null ? null : value.getLifecycleState().getValue(),
                value.getLifecycleDetails(),
                value.getNlbIpVersion() == null ? null : value.getNlbIpVersion().getValue(),
                value.getTimeCreated(), value.getTimeUpdated(), value.getIpAddresses(),
                value.getIsPrivate(), value.getIsPreserveSourceDestination(),
                value.getIsSymmetricHashEnabled(), value.getSubnetId(),
                value.getNetworkSecurityGroupIds(), value.getListeners(), value.getBackendSets(),
                value.getFreeformTags(), value.getDefinedTags(), value.getSecurityAttributes());
        out.put("etag", etag);
        out.put("health", networkLoadBalancerHealth(health));
        out.put("healthStatus", health == null || health.getStatus() == null
                ? null : health.getStatus().getValue());
        return out;
    }

    private static void putNlbFields(
            Map<String, Object> out,
            String id,
            String compartmentId,
            String displayName,
            String lifecycleState,
            String lifecycleDetails,
            String nlbIpVersion,
            Date timeCreated,
            Date timeUpdated,
            List<IpAddress> ipAddresses,
            Boolean isPrivate,
            Boolean preserveSourceDestination,
            Boolean symmetricHashEnabled,
            String subnetId,
            List<String> networkSecurityGroupIds,
            Map<String, Listener> listeners,
            Map<String, BackendSet> backendSets,
            Map<String, String> freeformTags,
            Map<String, Map<String, Object>> definedTags,
            Map<String, Map<String, Object>> securityAttributes) {
        out.put("id", id);
        out.put("compartmentId", compartmentId);
        out.put("displayName", displayName);
        out.put("lifecycleState", lifecycleState);
        out.put("lifecycleDetails", lifecycleDetails);
        out.put("nlbIpVersion", nlbIpVersion);
        out.put("timeCreated", date(timeCreated));
        out.put("timeUpdated", date(timeUpdated));
        out.put("ipAddresses", ipAddresses(ipAddresses));
        out.put("isPrivate", isPrivate);
        out.put("isPreserveSourceDestination", preserveSourceDestination);
        out.put("isSymmetricHashEnabled", symmetricHashEnabled);
        out.put("subnetId", subnetId);
        out.put("networkSecurityGroupIds", safeList(networkSecurityGroupIds));
        List<Map<String, Object>> listenerRows = new ArrayList<>();
        if (listeners != null) listeners.values().forEach(item -> listenerRows.add(listener(item, null)));
        List<Map<String, Object>> backendSetRows = new ArrayList<>();
        if (backendSets != null) backendSets.values().forEach(item -> backendSetRows.add(backendSet(item, null)));
        out.put("listeners", listenerRows);
        out.put("backendSets", backendSetRows);
        out.put("listenerCount", listenerRows.size());
        out.put("backendSetCount", backendSetRows.size());
        out.put("backendCount", backendSetRows.stream()
                .mapToInt(row -> row.get("backendCount") instanceof Number n ? n.intValue() : 0)
                .sum());
        out.put("freeformTags", freeformTags == null ? Map.of() : freeformTags);
        out.put("definedTags", definedTags == null ? Map.of() : definedTags);
        out.put("securityAttributes", securityAttributes == null ? Map.of() : securityAttributes);
    }

    public static Map<String, Object> listener(ListenerSummary value, String etag) {
        if (value == null) return new LinkedHashMap<>();
        return listenerFields(value.getName(), value.getDefaultBackendSetName(), value.getPort(),
                value.getProtocol() == null ? null : value.getProtocol().getValue(),
                value.getIpVersion() == null ? null : value.getIpVersion().getValue(),
                value.getIsPpv2Enabled(), value.getTcpIdleTimeout(), value.getUdpIdleTimeout(),
                value.getL3IpIdleTimeout(), etag);
    }

    public static Map<String, Object> listener(Listener value, String etag) {
        if (value == null) return new LinkedHashMap<>();
        return listenerFields(value.getName(), value.getDefaultBackendSetName(), value.getPort(),
                value.getProtocol() == null ? null : value.getProtocol().getValue(),
                value.getIpVersion() == null ? null : value.getIpVersion().getValue(),
                value.getIsPpv2Enabled(), value.getTcpIdleTimeout(), value.getUdpIdleTimeout(),
                value.getL3IpIdleTimeout(), etag);
    }

    private static Map<String, Object> listenerFields(
            String name, String backendSetName, Integer port, String protocol, String ipVersion,
            Boolean ppv2, Integer tcpTimeout, Integer udpTimeout, Integer l3Timeout, String etag) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("defaultBackendSetName", backendSetName);
        out.put("port", port);
        out.put("protocol", protocol);
        out.put("ipVersion", ipVersion);
        out.put("isPpv2Enabled", ppv2);
        out.put("tcpIdleTimeout", tcpTimeout);
        out.put("udpIdleTimeout", udpTimeout);
        out.put("l3IpIdleTimeout", l3Timeout);
        out.put("etag", etag);
        return out;
    }

    public static Map<String, Object> backendSet(BackendSetSummary value, String etag) {
        if (value == null) return new LinkedHashMap<>();
        return backendSetFields(value.getName(),
                value.getPolicy() == null ? null : value.getPolicy().getValue(),
                value.getIsPreserveSource(), value.getIsFailOpen(),
                value.getIsInstantFailoverEnabled(), value.getIsInstantFailoverTcpResetEnabled(),
                value.getAreOperationallyActiveBackendsPreferred(),
                value.getIpVersion() == null ? null : value.getIpVersion().getValue(),
                value.getBackends(), value.getHealthChecker(), etag);
    }

    public static Map<String, Object> backendSet(BackendSet value, String etag) {
        if (value == null) return new LinkedHashMap<>();
        return backendSetFields(value.getName(),
                value.getPolicy() == null ? null : value.getPolicy().getValue(),
                value.getIsPreserveSource(), value.getIsFailOpen(),
                value.getIsInstantFailoverEnabled(), value.getIsInstantFailoverTcpResetEnabled(),
                value.getAreOperationallyActiveBackendsPreferred(),
                value.getIpVersion() == null ? null : value.getIpVersion().getValue(),
                value.getBackends(), value.getHealthChecker(), etag);
    }

    private static Map<String, Object> backendSetFields(
            String name,
            String policy,
            Boolean preserveSource,
            Boolean failOpen,
            Boolean instantFailover,
            Boolean instantFailoverReset,
            Boolean activePreferred,
            String ipVersion,
            List<Backend> backends,
            HealthChecker checker,
            String etag) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("policy", policy);
        out.put("isPreserveSource", preserveSource);
        out.put("isFailOpen", failOpen);
        out.put("isInstantFailoverEnabled", instantFailover);
        out.put("isInstantFailoverTcpResetEnabled", instantFailoverReset);
        out.put("areOperationallyActiveBackendsPreferred", activePreferred);
        out.put("ipVersion", ipVersion);
        List<Map<String, Object>> backendRows = new ArrayList<>();
        if (backends != null) backends.forEach(item -> backendRows.add(backend(item, null)));
        out.put("backends", backendRows);
        out.put("backendCount", backendRows.size());
        out.put("healthChecker", healthChecker(checker, etag));
        out.put("etag", etag);
        return out;
    }

    public static Map<String, Object> backend(BackendSummary value, String etag) {
        if (value == null) return new LinkedHashMap<>();
        return backendFields(value.getName(), value.getIpAddress(), value.getTargetId(), value.getPort(),
                value.getWeight(), value.getIsDrain(), value.getIsBackup(), value.getIsOffline(), etag);
    }

    public static Map<String, Object> backend(Backend value, String etag) {
        if (value == null) return new LinkedHashMap<>();
        return backendFields(value.getName(), value.getIpAddress(), value.getTargetId(), value.getPort(),
                value.getWeight(), value.getIsDrain(), value.getIsBackup(), value.getIsOffline(), etag);
    }

    private static Map<String, Object> backendFields(
            String name, String ipAddress, String targetId, Integer port, Integer weight,
            Boolean drain, Boolean backup, Boolean offline, String etag) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("ipAddress", ipAddress);
        out.put("targetId", targetId);
        out.put("port", port);
        out.put("weight", weight);
        out.put("isDrain", drain);
        out.put("isBackup", backup);
        out.put("isOffline", offline);
        out.put("etag", etag);
        return out;
    }

    public static Map<String, Object> healthChecker(HealthChecker value, String etag) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value == null) return out;
        out.put("protocol", value.getProtocol() == null ? null : value.getProtocol().getValue());
        out.put("port", value.getPort());
        out.put("retries", value.getRetries());
        out.put("timeoutInMillis", value.getTimeoutInMillis());
        out.put("intervalInMillis", value.getIntervalInMillis());
        out.put("urlPath", value.getUrlPath());
        out.put("responseBodyRegex", value.getResponseBodyRegex());
        out.put("returnCode", value.getReturnCode());
        out.put("requestData", bytes(value.getRequestData()));
        out.put("responseData", bytes(value.getResponseData()));
        if (value.getDns() != null) {
            Map<String, Object> dns = new LinkedHashMap<>();
            dns.put("transportProtocol", value.getDns().getTransportProtocol() == null
                    ? null : value.getDns().getTransportProtocol().getValue());
            dns.put("domainName", value.getDns().getDomainName());
            dns.put("queryClass", value.getDns().getQueryClass() == null
                    ? null : value.getDns().getQueryClass().getValue());
            dns.put("queryType", value.getDns().getQueryType() == null
                    ? null : value.getDns().getQueryType().getValue());
            dns.put("rcodes", value.getDns().getRcodes() == null ? List.of()
                    : value.getDns().getRcodes().stream().map(item -> item.getValue()).toList());
            out.put("dns", dns);
        } else {
            out.put("dns", null);
        }
        out.put("etag", etag);
        return out;
    }

    public static Map<String, Object> networkLoadBalancerHealth(NetworkLoadBalancerHealth value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value == null) return out;
        out.put("status", value.getStatus() == null ? null : value.getStatus().getValue());
        out.put("warningStateBackendSetNames", safeList(value.getWarningStateBackendSetNames()));
        out.put("criticalStateBackendSetNames", safeList(value.getCriticalStateBackendSetNames()));
        out.put("unknownStateBackendSetNames", safeList(value.getUnknownStateBackendSetNames()));
        out.put("totalBackendSetCount", value.getTotalBackendSetCount());
        return out;
    }

    public static Map<String, Object> backendSetHealth(BackendSetHealth value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value == null) return out;
        out.put("status", value.getStatus() == null ? null : value.getStatus().getValue());
        out.put("warningStateBackendNames", safeList(value.getWarningStateBackendNames()));
        out.put("criticalStateBackendNames", safeList(value.getCriticalStateBackendNames()));
        out.put("unknownStateBackendNames", safeList(value.getUnknownStateBackendNames()));
        out.put("totalBackendCount", value.getTotalBackendCount());
        return out;
    }

    public static Map<String, Object> backendHealth(
            BackendHealth value,
            BackendOperationalStatus operationalStatus) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value != null) {
            out.put("status", value.getStatus() == null ? null : value.getStatus().getValue());
            List<Map<String, Object>> results = new ArrayList<>();
            if (value.getHealthCheckResults() != null) {
                for (HealthCheckResult item : value.getHealthCheckResults()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("timestamp", date(item.getTimestamp()));
                    row.put("status", item.getHealthCheckStatus() == null
                            ? null : item.getHealthCheckStatus().getValue());
                    results.add(row);
                }
            }
            out.put("healthCheckResults", results);
        }
        out.put("operationalStatus", operationalStatus == null || operationalStatus.getStatus() == null
                ? null : operationalStatus.getStatus().getValue());
        return out;
    }

    public static Map<String, Object> workRequest(WorkRequest value) {
        if (value == null) return new LinkedHashMap<>();
        return workRequestFields(value.getId(), value.getCompartmentId(),
                value.getOperationType() == null ? null : value.getOperationType().getValue(),
                value.getStatus() == null ? null : value.getStatus().getValue(), value.getPercentComplete(),
                value.getResources(), value.getTimeAccepted(), value.getTimeStarted(), value.getTimeFinished());
    }

    public static Map<String, Object> workRequest(WorkRequestSummary value) {
        if (value == null) return new LinkedHashMap<>();
        return workRequestFields(value.getId(), value.getCompartmentId(),
                value.getOperationType() == null ? null : value.getOperationType().getValue(),
                value.getStatus() == null ? null : value.getStatus().getValue(), value.getPercentComplete(),
                value.getResources(), value.getTimeAccepted(), value.getTimeStarted(), value.getTimeFinished());
    }

    private static Map<String, Object> workRequestFields(
            String id, String compartmentId, String operationType, String status, Float percentComplete,
            List<WorkRequestResource> resources, Date accepted, Date started, Date finished) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("compartmentId", compartmentId);
        out.put("operationType", operationType);
        out.put("status", status);
        out.put("percentComplete", percentComplete);
        List<Map<String, Object>> resourceRows = new ArrayList<>();
        if (resources != null) {
            for (WorkRequestResource resource : resources) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("entityType", resource.getEntityType());
                row.put("actionType", resource.getActionType() == null ? null : resource.getActionType().getValue());
                row.put("identifier", resource.getIdentifier());
                row.put("entityUri", resource.getEntityUri());
                resourceRows.add(row);
            }
        }
        out.put("resources", resourceRows);
        out.put("timeAccepted", date(accepted));
        out.put("timeStarted", date(started));
        out.put("timeFinished", date(finished));
        out.put("terminal", isTerminalWorkRequestStatus(status));
        out.put("successful", "SUCCEEDED".equalsIgnoreCase(status));
        return out;
    }

    public static Map<String, Object> workRequestError(WorkRequestError value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value == null) return out;
        out.put("code", value.getCode());
        out.put("message", value.getMessage());
        out.put("timestamp", date(value.getTimestamp()));
        return out;
    }

    public static Map<String, Object> workRequestLog(WorkRequestLogEntry value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value == null) return out;
        out.put("message", value.getMessage());
        out.put("timestamp", date(value.getTimestamp()));
        return out;
    }

    public static boolean isTerminalWorkRequestStatus(String status) {
        if (status == null) return false;
        return "SUCCEEDED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "CANCELED".equalsIgnoreCase(status);
    }

    private static List<Map<String, Object>> ipAddresses(List<IpAddress> values) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (values == null) return out;
        for (IpAddress value : values) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ipAddress", value.getIpAddress());
            row.put("isPublic", value.getIsPublic());
            row.put("ipVersion", value.getIpVersion() == null ? null : value.getIpVersion().getValue());
            row.put("reservedIpId", value.getReservedIp() == null ? null : value.getReservedIp().getId());
            out.add(row);
        }
        return out;
    }

    private static String date(Date value) {
        return value == null ? null : value.toInstant().toString();
    }

    private static String bytes(byte[] value) {
        if (value == null || value.length == 0) return null;
        String text = new String(value, StandardCharsets.UTF_8);
        boolean printable = text.chars().allMatch(ch -> ch == '\r' || ch == '\n' || ch == '\t'
                || (ch >= 32 && ch < 127));
        return printable ? text : "base64:" + Base64.getEncoder().encodeToString(value);
    }

    private static <T> List<T> safeList(Collection<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
