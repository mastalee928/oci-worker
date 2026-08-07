package com.ociworker.nlb.support;

import com.ociworker.exception.OciException;
import com.ociworker.nlb.model.NlbRequests;
import com.oracle.bmc.networkloadbalancer.model.BackendDetails;
import com.oracle.bmc.networkloadbalancer.model.CreateBackendDetails;
import com.oracle.bmc.networkloadbalancer.model.CreateBackendSetDetails;
import com.oracle.bmc.networkloadbalancer.model.CreateListenerDetails;
import com.oracle.bmc.networkloadbalancer.model.CreateNetworkLoadBalancerDetails;
import com.oracle.bmc.networkloadbalancer.model.DnsHealthCheckQueryClasses;
import com.oracle.bmc.networkloadbalancer.model.DnsHealthCheckQueryTypes;
import com.oracle.bmc.networkloadbalancer.model.DnsHealthCheckRCodes;
import com.oracle.bmc.networkloadbalancer.model.DnsHealthCheckTransportProtocols;
import com.oracle.bmc.networkloadbalancer.model.DnsHealthCheckerDetails;
import com.oracle.bmc.networkloadbalancer.model.HealthCheckProtocols;
import com.oracle.bmc.networkloadbalancer.model.HealthCheckerDetails;
import com.oracle.bmc.networkloadbalancer.model.IpVersion;
import com.oracle.bmc.networkloadbalancer.model.ListenerProtocols;
import com.oracle.bmc.networkloadbalancer.model.NetworkLoadBalancingPolicy;
import com.oracle.bmc.networkloadbalancer.model.NlbIpVersion;
import com.oracle.bmc.networkloadbalancer.model.ReservedIP;
import com.oracle.bmc.networkloadbalancer.model.UpdateBackendDetails;
import com.oracle.bmc.networkloadbalancer.model.UpdateBackendSetDetails;
import com.oracle.bmc.networkloadbalancer.model.UpdateHealthCheckerDetails;
import com.oracle.bmc.networkloadbalancer.model.UpdateListenerDetails;
import com.oracle.bmc.networkloadbalancer.model.UpdateNetworkLoadBalancerDetails;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Builds and validates OCI SDK detail models from the module's typed request records. */
public final class NlbSdkDetailsFactory {

    private NlbSdkDetailsFactory() {
    }

    public static CreateNetworkLoadBalancerDetails createNetworkLoadBalancer(
            NlbRequests.CreateNetworkLoadBalancerRequest request,
            String compartmentId) {
        require(request, "创建参数不能为空");
        String displayName = required(request.displayName(), "名称不能为空");
        String subnetId = required(request.subnetId(), "子网不能为空");
        CreateNetworkLoadBalancerDetails.Builder builder = CreateNetworkLoadBalancerDetails.builder()
                .compartmentId(required(compartmentId, "Compartment 不能为空"))
                .displayName(displayName)
                .subnetId(subnetId)
                .isPrivate(Boolean.TRUE.equals(request.isPrivate()))
                .isPreserveSourceDestination(Boolean.TRUE.equals(request.isPreserveSourceDestination()))
                .isSymmetricHashEnabled(Boolean.TRUE.equals(request.isSymmetricHashEnabled()))
                .nlbIpVersion(nlbIpVersion(request.nlbIpVersion(), NlbIpVersion.Ipv4));
        if (request.networkSecurityGroupIds() != null) {
            builder.networkSecurityGroupIds(cleanStrings(request.networkSecurityGroupIds()));
        }
        if (request.reservedIpIds() != null) {
            builder.reservedIps(cleanStrings(request.reservedIpIds()).stream()
                    .map(id -> ReservedIP.builder().id(id).build()).toList());
        }
        if (notBlank(request.subnetIpv6Cidr())) builder.subnetIpv6Cidr(request.subnetIpv6Cidr().trim());
        if (notBlank(request.assignedPrivateIpv4())) builder.assignedPrivateIpv4(request.assignedPrivateIpv4().trim());
        if (notBlank(request.assignedIpv6())) builder.assignedIpv6(request.assignedIpv6().trim());
        if (request.freeformTags() != null) builder.freeformTags(request.freeformTags());
        if (request.definedTags() != null) builder.definedTags(request.definedTags());
        if (request.securityAttributes() != null) builder.securityAttributes(request.securityAttributes());
        return builder.build();
    }

    public static UpdateNetworkLoadBalancerDetails updateNetworkLoadBalancer(
            NlbRequests.UpdateNetworkLoadBalancerRequest request) {
        require(request, "更新参数不能为空");
        UpdateNetworkLoadBalancerDetails.Builder builder = UpdateNetworkLoadBalancerDetails.builder();
        if (notBlank(request.displayName())) builder.displayName(request.displayName().trim());
        if (request.isPreserveSourceDestination() != null) {
            builder.isPreserveSourceDestination(request.isPreserveSourceDestination());
        }
        if (request.isSymmetricHashEnabled() != null) {
            builder.isSymmetricHashEnabled(request.isSymmetricHashEnabled());
        }
        if (notBlank(request.nlbIpVersion())) builder.nlbIpVersion(nlbIpVersion(request.nlbIpVersion(), null));
        if (request.subnetIpv6Cidr() != null) builder.subnetIpv6Cidr(trimToNull(request.subnetIpv6Cidr()));
        if (request.assignedIpv6() != null) builder.assignedIpv6(trimToNull(request.assignedIpv6()));
        if (request.reservedIpv6Id() != null) builder.reservedIpv6Id(trimToNull(request.reservedIpv6Id()));
        if (request.freeformTags() != null) builder.freeformTags(request.freeformTags());
        if (request.definedTags() != null) builder.definedTags(request.definedTags());
        if (request.securityAttributes() != null) builder.securityAttributes(request.securityAttributes());
        return builder.build();
    }

    public static CreateListenerDetails createListener(NlbRequests.CreateListenerRequest request) {
        require(request, "Listener 参数不能为空");
        return CreateListenerDetails.builder()
                .name(required(request.name(), "Listener 名称不能为空"))
                .defaultBackendSetName(required(request.defaultBackendSetName(), "默认 Backend Set 不能为空"))
                .port(requiredPort(request.port(), "Listener 端口"))
                .protocol(listenerProtocol(request.protocol(), ListenerProtocols.Tcp))
                .ipVersion(ipVersion(request.ipVersion(), IpVersion.Ipv4))
                .isPpv2Enabled(Boolean.TRUE.equals(request.isPpv2Enabled()))
                .tcpIdleTimeout(request.tcpIdleTimeout())
                .udpIdleTimeout(request.udpIdleTimeout())
                .l3IpIdleTimeout(request.l3IpIdleTimeout())
                .build();
    }

    public static UpdateListenerDetails updateListener(NlbRequests.UpdateListenerRequest request) {
        require(request, "Listener 更新参数不能为空");
        UpdateListenerDetails.Builder builder = UpdateListenerDetails.builder();
        if (notBlank(request.defaultBackendSetName())) {
            builder.defaultBackendSetName(request.defaultBackendSetName().trim());
        }
        if (request.port() != null) builder.port(requiredPort(request.port(), "Listener 端口"));
        if (notBlank(request.protocol())) builder.protocol(listenerProtocol(request.protocol(), null));
        if (notBlank(request.ipVersion())) builder.ipVersion(ipVersion(request.ipVersion(), null));
        if (request.isPpv2Enabled() != null) builder.isPpv2Enabled(request.isPpv2Enabled());
        if (request.tcpIdleTimeout() != null) builder.tcpIdleTimeout(request.tcpIdleTimeout());
        if (request.udpIdleTimeout() != null) builder.udpIdleTimeout(request.udpIdleTimeout());
        if (request.l3IpIdleTimeout() != null) builder.l3IpIdleTimeout(request.l3IpIdleTimeout());
        return builder.build();
    }

    public static CreateBackendSetDetails createBackendSet(NlbRequests.CreateBackendSetRequest request) {
        require(request, "Backend Set 参数不能为空");
        return CreateBackendSetDetails.builder()
                .name(required(request.name(), "Backend Set 名称不能为空"))
                .policy(policy(request.policy(), NetworkLoadBalancingPolicy.FiveTuple))
                .isPreserveSource(Boolean.TRUE.equals(request.isPreserveSource()))
                .isFailOpen(Boolean.TRUE.equals(request.isFailOpen()))
                .isInstantFailoverEnabled(Boolean.TRUE.equals(request.isInstantFailoverEnabled()))
                .isInstantFailoverTcpResetEnabled(Boolean.TRUE.equals(request.isInstantFailoverTcpResetEnabled()))
                .areOperationallyActiveBackendsPreferred(
                        Boolean.TRUE.equals(request.areOperationallyActiveBackendsPreferred()))
                .ipVersion(ipVersion(request.ipVersion(), IpVersion.Ipv4))
                .backends(backendDetails(request.backends()))
                .healthChecker(healthCheckerDetails(request.healthChecker()))
                .build();
    }

    public static UpdateBackendSetDetails updateBackendSet(NlbRequests.UpdateBackendSetRequest request) {
        require(request, "Backend Set 更新参数不能为空");
        UpdateBackendSetDetails.Builder builder = UpdateBackendSetDetails.builder();
        if (notBlank(request.policy())) builder.policy(policy(request.policy(), null).getValue());
        if (request.isPreserveSource() != null) builder.isPreserveSource(request.isPreserveSource());
        if (request.isFailOpen() != null) builder.isFailOpen(request.isFailOpen());
        if (request.isInstantFailoverEnabled() != null) {
            builder.isInstantFailoverEnabled(request.isInstantFailoverEnabled());
        }
        if (request.isInstantFailoverTcpResetEnabled() != null) {
            builder.isInstantFailoverTcpResetEnabled(request.isInstantFailoverTcpResetEnabled());
        }
        if (request.areOperationallyActiveBackendsPreferred() != null) {
            builder.areOperationallyActiveBackendsPreferred(request.areOperationallyActiveBackendsPreferred());
        }
        if (notBlank(request.ipVersion())) builder.ipVersion(ipVersion(request.ipVersion(), null));
        if (request.backends() != null) builder.backends(backendDetails(request.backends()));
        if (request.healthChecker() != null) builder.healthChecker(healthCheckerDetails(request.healthChecker()));
        return builder.build();
    }

    public static UpdateHealthCheckerDetails updateHealthChecker(NlbRequests.HealthCheckerInput input) {
        HealthCheckerDetails source = healthCheckerDetails(input);
        return UpdateHealthCheckerDetails.builder()
                .protocol(source.getProtocol())
                .port(source.getPort())
                .retries(source.getRetries())
                .timeoutInMillis(source.getTimeoutInMillis())
                .intervalInMillis(source.getIntervalInMillis())
                .urlPath(source.getUrlPath())
                .responseBodyRegex(source.getResponseBodyRegex())
                .returnCode(source.getReturnCode())
                .requestData(source.getRequestData())
                .responseData(source.getResponseData())
                .dns(source.getDns())
                .build();
    }

    public static CreateBackendDetails createBackend(NlbRequests.BackendInput input) {
        validateBackend(input);
        return CreateBackendDetails.builder()
                .name(trimToNull(input.name()))
                .ipAddress(trimToNull(input.ipAddress()))
                .targetId(trimToNull(input.targetId()))
                .port(requiredPort(input.port(), "Backend 端口"))
                .weight(input.weight() == null ? 1 : input.weight())
                .isDrain(Boolean.TRUE.equals(input.isDrain()))
                .isBackup(Boolean.TRUE.equals(input.isBackup()))
                .isOffline(Boolean.TRUE.equals(input.isOffline()))
                .build();
    }

    public static UpdateBackendDetails updateBackend(NlbRequests.UpdateBackendRequest request) {
        require(request, "Backend 更新参数不能为空");
        validateBackendWeight(request.weight());
        UpdateBackendDetails.Builder builder = UpdateBackendDetails.builder();
        if (request.weight() != null) builder.weight(request.weight());
        if (request.isDrain() != null) builder.isDrain(request.isDrain());
        if (request.isBackup() != null) builder.isBackup(request.isBackup());
        if (request.isOffline() != null) builder.isOffline(request.isOffline());
        return builder.build();
    }

    private static HealthCheckerDetails healthCheckerDetails(NlbRequests.HealthCheckerInput input) {
        String protocol = input == null ? null : input.protocol();
        HealthCheckProtocols resolved = healthProtocol(protocol, HealthCheckProtocols.Tcp);
        HealthCheckerDetails.Builder builder = HealthCheckerDetails.builder()
                .protocol(resolved)
                .port(input == null || input.port() == null ? 80 : requiredPort(input.port(), "健康检查端口"))
                .retries(input == null || input.retries() == null ? 3 : input.retries())
                .timeoutInMillis(input == null || input.timeoutInMillis() == null ? 3000 : input.timeoutInMillis())
                .intervalInMillis(input == null || input.intervalInMillis() == null ? 10000 : input.intervalInMillis());
        if (input != null) {
            if (input.urlPath() != null) builder.urlPath(input.urlPath());
            if (input.responseBodyRegex() != null) builder.responseBodyRegex(input.responseBodyRegex());
            if (input.returnCode() != null) builder.returnCode(input.returnCode());
            if (input.requestData() != null) builder.requestData(bytes(input.requestData()));
            if (input.responseData() != null) builder.responseData(bytes(input.responseData()));
            if (resolved == HealthCheckProtocols.Dns) {
                if (input.dns() == null) throw new OciException("DNS 健康检查参数不能为空");
                builder.dns(dnsHealthChecker(input.dns()));
            }
        }
        return builder.build();
    }

    private static DnsHealthCheckerDetails dnsHealthChecker(NlbRequests.DnsHealthCheckerInput input) {
        List<DnsHealthCheckRCodes> rcodes = input.rcodes() == null ? List.of(DnsHealthCheckRCodes.Noerror)
                : cleanStrings(input.rcodes()).stream().map(DnsHealthCheckRCodes::create).toList();
        return DnsHealthCheckerDetails.builder()
                .transportProtocol(dnsTransport(input.transportProtocol(), DnsHealthCheckTransportProtocols.Udp))
                .domainName(required(input.domainName(), "DNS 健康检查域名不能为空"))
                .queryClass(dnsQueryClass(input.queryClass(), DnsHealthCheckQueryClasses.In))
                .queryType(dnsQueryType(input.queryType(), DnsHealthCheckQueryTypes.A))
                .rcodes(rcodes)
                .build();
    }

    private static List<BackendDetails> backendDetails(List<NlbRequests.BackendInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();
        List<BackendDetails> out = new ArrayList<>();
        for (NlbRequests.BackendInput input : inputs) {
            validateBackend(input);
            out.add(BackendDetails.builder()
                    .name(trimToNull(input.name()))
                    .ipAddress(trimToNull(input.ipAddress()))
                    .targetId(trimToNull(input.targetId()))
                    .port(requiredPort(input.port(), "Backend 端口"))
                    .weight(input.weight() == null ? 1 : input.weight())
                    .isDrain(Boolean.TRUE.equals(input.isDrain()))
                    .isBackup(Boolean.TRUE.equals(input.isBackup()))
                    .isOffline(Boolean.TRUE.equals(input.isOffline()))
                    .build());
        }
        return out;
    }

    private static void validateBackend(NlbRequests.BackendInput input) {
        require(input, "Backend 参数不能为空");
        if (!notBlank(input.ipAddress()) && !notBlank(input.targetId())) {
            throw new OciException("Backend 的 IP 地址和目标 OCID 至少填写一项");
        }
        validateBackendWeight(input.weight());
    }

    private static void validateBackendWeight(Integer weight) {
        if (weight != null && (weight < 0 || weight > 100)) {
            throw new OciException("Backend 权重必须在 0-100 之间");
        }
    }

    private static NlbIpVersion nlbIpVersion(String value, NlbIpVersion fallback) {
        NlbIpVersion result = notBlank(value) ? NlbIpVersion.create(value.trim()) : fallback;
        if (result == null || result == NlbIpVersion.UnknownEnumValue) throw new OciException("NLB IP 版本无效");
        return result;
    }

    private static IpVersion ipVersion(String value, IpVersion fallback) {
        IpVersion result = notBlank(value) ? IpVersion.create(value.trim()) : fallback;
        if (result == null || result == IpVersion.UnknownEnumValue) throw new OciException("IP 版本无效");
        return result;
    }

    private static ListenerProtocols listenerProtocol(String value, ListenerProtocols fallback) {
        ListenerProtocols result = notBlank(value) ? ListenerProtocols.create(value.trim()) : fallback;
        if (result == null || result == ListenerProtocols.UnknownEnumValue) throw new OciException("Listener 协议无效");
        return result;
    }

    private static NetworkLoadBalancingPolicy policy(String value, NetworkLoadBalancingPolicy fallback) {
        NetworkLoadBalancingPolicy result = notBlank(value)
                ? NetworkLoadBalancingPolicy.create(value.trim()) : fallback;
        if (result == null || result == NetworkLoadBalancingPolicy.UnknownEnumValue) {
            throw new OciException("负载均衡策略无效");
        }
        return result;
    }

    private static HealthCheckProtocols healthProtocol(String value, HealthCheckProtocols fallback) {
        HealthCheckProtocols result = notBlank(value) ? HealthCheckProtocols.create(value.trim()) : fallback;
        if (result == null || result == HealthCheckProtocols.UnknownEnumValue) {
            throw new OciException("健康检查协议无效");
        }
        return result;
    }

    private static DnsHealthCheckTransportProtocols dnsTransport(
            String value, DnsHealthCheckTransportProtocols fallback) {
        DnsHealthCheckTransportProtocols result = notBlank(value)
                ? DnsHealthCheckTransportProtocols.create(value.trim()) : fallback;
        if (result == null || result == DnsHealthCheckTransportProtocols.UnknownEnumValue) {
            throw new OciException("DNS 健康检查传输协议无效");
        }
        return result;
    }

    private static DnsHealthCheckQueryClasses dnsQueryClass(
            String value, DnsHealthCheckQueryClasses fallback) {
        DnsHealthCheckQueryClasses result = notBlank(value)
                ? DnsHealthCheckQueryClasses.create(value.trim()) : fallback;
        if (result == null || result == DnsHealthCheckQueryClasses.UnknownEnumValue) {
            throw new OciException("DNS 查询 Class 无效");
        }
        return result;
    }

    private static DnsHealthCheckQueryTypes dnsQueryType(
            String value, DnsHealthCheckQueryTypes fallback) {
        DnsHealthCheckQueryTypes result = notBlank(value)
                ? DnsHealthCheckQueryTypes.create(value.trim()) : fallback;
        if (result == null || result == DnsHealthCheckQueryTypes.UnknownEnumValue) {
            throw new OciException("DNS 查询类型无效");
        }
        return result;
    }

    private static Integer requiredPort(Integer value, String label) {
        if (value == null || value < 1 || value > 65535) {
            throw new OciException(label + "必须在 1-65535 之间");
        }
        return value;
    }

    private static byte[] bytes(String value) {
        if (value == null) return null;
        if (value.startsWith("base64:")) {
            try {
                return Base64.getDecoder().decode(value.substring("base64:".length()));
            } catch (IllegalArgumentException e) {
                throw new OciException("健康检查 Base64 数据无效");
            }
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> cleanStrings(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(NlbSdkDetailsFactory::notBlank).map(String::trim).distinct().toList();
    }

    private static String required(String value, String message) {
        String result = trimToNull(value);
        if (result == null) throw new OciException(message);
        return result;
    }

    private static <T> T require(T value, String message) {
        if (value == null) throw new OciException(message);
        return value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}
