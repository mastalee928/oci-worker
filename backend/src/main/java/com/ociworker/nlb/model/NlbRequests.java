package com.ociworker.nlb.model;

import java.util.List;
import java.util.Map;

/** Typed HTTP request models for the OCI Network Load Balancer module. */
public final class NlbRequests {

    private NlbRequests() {
    }

    public record ListRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            Boolean force) {
        public boolean forceEnabled() {
            return Boolean.TRUE.equals(force);
        }
    }

    public record ResourceRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            Boolean force) {
        public boolean forceEnabled() {
            return Boolean.TRUE.equals(force);
        }
    }

    public record BackendSetResourceRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            String backendSetName,
            Boolean force) {
        public boolean forceEnabled() {
            return Boolean.TRUE.equals(force);
        }
    }

    public record ListenerResourceRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            String listenerName,
            Boolean force) {
        public boolean forceEnabled() {
            return Boolean.TRUE.equals(force);
        }
    }

    public record BackendResourceRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            String backendSetName,
            String backendName,
            Boolean force) {
        public boolean forceEnabled() {
            return Boolean.TRUE.equals(force);
        }
    }

    public record WorkRequestResourceRequest(
            String id,
            String region,
            String compartmentId,
            String workRequestId) {
    }

    public record WaitWorkRequestRequest(
            String id,
            String region,
            String compartmentId,
            String workRequestId,
            Integer timeoutSeconds,
            Integer pollIntervalMillis) {
    }

    public record CreateNetworkLoadBalancerRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String displayName,
            Boolean isPrivate,
            String subnetId,
            List<String> networkSecurityGroupIds,
            String nlbIpVersion,
            Boolean isPreserveSourceDestination,
            Boolean isSymmetricHashEnabled,
            List<String> reservedIpIds,
            String subnetIpv6Cidr,
            String assignedPrivateIpv4,
            String assignedIpv6,
            Map<String, String> freeformTags,
            Map<String, Map<String, Object>> definedTags,
            Map<String, Map<String, Object>> securityAttributes) {
    }

    public record UpdateNetworkLoadBalancerRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            String ifMatch,
            String displayName,
            Boolean isPreserveSourceDestination,
            Boolean isSymmetricHashEnabled,
            String nlbIpVersion,
            String subnetIpv6Cidr,
            String assignedIpv6,
            String reservedIpv6Id,
            Map<String, String> freeformTags,
            Map<String, Map<String, Object>> definedTags,
            Map<String, Map<String, Object>> securityAttributes) {
    }

    public record DeleteNetworkLoadBalancerRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            String ifMatch,
            String verifyCode) {
    }

    public record UpdateNetworkSecurityGroupsRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            String ifMatch,
            List<String> networkSecurityGroupIds) {
    }

    public record ChangeCompartmentRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            String ifMatch,
            String targetCompartmentId,
            String verifyCode) {
    }

    public record CreateListenerRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            String ifMatch,
            String name,
            String defaultBackendSetName,
            Integer port,
            String protocol,
            String ipVersion,
            Boolean isPpv2Enabled,
            Integer tcpIdleTimeout,
            Integer udpIdleTimeout,
            Integer l3IpIdleTimeout) {
    }

    public record UpdateListenerRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            String listenerName,
            String ifMatch,
            String defaultBackendSetName,
            Integer port,
            String protocol,
            String ipVersion,
            Boolean isPpv2Enabled,
            Integer tcpIdleTimeout,
            Integer udpIdleTimeout,
            Integer l3IpIdleTimeout) {
    }

    public record DeleteListenerRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            String listenerName,
            String ifMatch,
            String verifyCode) {
    }

    public record HealthCheckerInput(
            String protocol,
            Integer port,
            Integer retries,
            Integer timeoutInMillis,
            Integer intervalInMillis,
            String urlPath,
            String responseBodyRegex,
            Integer returnCode,
            String requestData,
            String responseData,
            DnsHealthCheckerInput dns) {
    }

    public record DnsHealthCheckerInput(
            String transportProtocol,
            String domainName,
            String queryClass,
            String queryType,
            List<String> rcodes) {
    }

    public record BackendInput(
            String name,
            String ipAddress,
            String targetId,
            Integer port,
            Integer weight,
            Boolean isDrain,
            Boolean isBackup,
            Boolean isOffline) {
    }

    public record CreateBackendSetRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            String ifMatch,
            String name,
            String policy,
            Boolean isPreserveSource,
            Boolean isFailOpen,
            Boolean isInstantFailoverEnabled,
            Boolean isInstantFailoverTcpResetEnabled,
            Boolean areOperationallyActiveBackendsPreferred,
            String ipVersion,
            List<BackendInput> backends,
            HealthCheckerInput healthChecker) {
    }

    public record UpdateBackendSetRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            String backendSetName,
            String ifMatch,
            String policy,
            Boolean isPreserveSource,
            Boolean isFailOpen,
            Boolean isInstantFailoverEnabled,
            Boolean isInstantFailoverTcpResetEnabled,
            Boolean areOperationallyActiveBackendsPreferred,
            String ipVersion,
            List<BackendInput> backends,
            HealthCheckerInput healthChecker) {
    }

    public record DeleteBackendSetRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            String backendSetName,
            String ifMatch,
            String verifyCode) {
    }

    public record UpdateHealthCheckerRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            String backendSetName,
            String ifMatch,
            HealthCheckerInput healthChecker) {
    }

    public record CreateBackendRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            String backendSetName,
            String ifMatch,
            BackendInput backend) {
    }

    public record UpdateBackendRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            String backendSetName,
            String backendName,
            String ifMatch,
            Integer weight,
            Boolean isDrain,
            Boolean isBackup,
            Boolean isOffline) {
    }

    public record DeleteBackendRequest(
            String id,
            String region,
            String compartmentId,
            String vcnId,
            String networkLoadBalancerId,
            String backendSetName,
            String backendName,
            String ifMatch,
            String verifyCode) {
    }
}
