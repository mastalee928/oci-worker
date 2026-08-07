package com.ociworker.nlb;

import com.ociworker.exception.OciException;
import com.ociworker.nlb.model.NlbRequests;
import com.ociworker.nlb.support.NlbSdkDetailsFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NlbSdkDetailsFactoryTest {

    @Test
    void buildsNetworkLoadBalancerWithExplicitVcnScopedSubnetAndDefaults() {
        var request = new NlbRequests.CreateNetworkLoadBalancerRequest(
                "tenant-1", "ap-singapore-1", "compartment-1", "vcn-1",
                "edge-nlb", null, "subnet-1", List.of("nsg-1", "nsg-1"),
                null, null, null, null, null, null, null,
                Map.of("owner", "platform"), null, null);

        var details = NlbSdkDetailsFactory.createNetworkLoadBalancer(request, "compartment-1");

        assertThat(details.getCompartmentId()).isEqualTo("compartment-1");
        assertThat(details.getDisplayName()).isEqualTo("edge-nlb");
        assertThat(details.getSubnetId()).isEqualTo("subnet-1");
        assertThat(details.getIsPrivate()).isTrue();
        assertThat(details.getNlbIpVersion().getValue()).isEqualTo("IPV4");
        assertThat(details.getNetworkSecurityGroupIds()).containsExactly("nsg-1");
        assertThat(details.getFreeformTags()).containsEntry("owner", "platform");
    }

    @Test
    void appliesBackendSetSourcePreservationDefaultAndRejectsInvalidSymmetricHash() {
        var backendSetRequest = new NlbRequests.CreateBackendSetRequest(
                "tenant-1", "region-1", "compartment-1", "vcn-1", "nlb-1", null,
                "set-1", "FIVE_TUPLE", null, false, false, null, false,
                "IPV4", List.of(), null);

        var backendSet = NlbSdkDetailsFactory.createBackendSet(backendSetRequest);

        assertThat(backendSet.getIsPreserveSource()).isTrue();
        assertThat(backendSet.getIsInstantFailoverTcpResetEnabled()).isTrue();
        assertThatThrownBy(() -> NlbSdkDetailsFactory.createNetworkLoadBalancer(
                new NlbRequests.CreateNetworkLoadBalancerRequest(
                        "tenant-1", "region-1", "compartment-1", "vcn-1",
                        "invalid", true, "subnet-1", null, "IPV4",
                        false, true, null, null, null, null, null, null, null),
                "compartment-1"))
                .isInstanceOf(OciException.class)
                .hasMessageContaining("对称哈希");
    }

    @Test
    void buildsListenerAndBackendDetails() {
        var listenerRequest = new NlbRequests.CreateListenerRequest(
                "tenant-1", "region-1", "compartment-1", "vcn-1", "nlb-1", null,
                "tcp-443", "backend-set-1", 443, "TCP", "IPV4", true,
                300, null, null);

        var listener = NlbSdkDetailsFactory.createListener(listenerRequest);
        var backend = NlbSdkDetailsFactory.createBackend(new NlbRequests.BackendInput(
                "backend-1", "10.0.0.10", null, 8443, 5, true, false, false));

        assertThat(listener.getName()).isEqualTo("tcp-443");
        assertThat(listener.getPort()).isEqualTo(443);
        assertThat(listener.getProtocol().getValue()).isEqualTo("TCP");
        assertThat(backend.getIpAddress()).isEqualTo("10.0.0.10");
        assertThat(backend.getWeight()).isEqualTo(5);
        assertThat(backend.getIsDrain()).isTrue();
    }

    @Test
    void rejectsBackendWithoutIpOrTargetAndInvalidPort() {
        assertThatThrownBy(() -> NlbSdkDetailsFactory.createBackend(new NlbRequests.BackendInput(
                "backend-1", null, null, 80, 1, false, false, false)))
                .isInstanceOf(OciException.class)
                .hasMessageContaining("至少填写一项");

        assertThatThrownBy(() -> NlbSdkDetailsFactory.createBackend(new NlbRequests.BackendInput(
                "backend-1", "10.0.0.10", null, 70000, 1, false, false, false)))
                .isInstanceOf(OciException.class)
                .hasMessageContaining("1-65535");

        assertThatThrownBy(() -> NlbSdkDetailsFactory.createBackend(new NlbRequests.BackendInput(
                "backend-1", "10.0.0.10", null, 80, 101, false, false, false)))
                .isInstanceOf(OciException.class)
                .hasMessageContaining("0-100");

        assertThatThrownBy(() -> NlbSdkDetailsFactory.updateBackend(new NlbRequests.UpdateBackendRequest(
                "tenant-1", "region-1", "compartment-1", "vcn-1", "nlb-1",
                "backend-set-1", "backend-1", null, -1, null, null, null)))
                .isInstanceOf(OciException.class)
                .hasMessageContaining("0-100");
    }

    @Test
    void buildsDnsHealthCheckerAndRejectsMissingDnsDetails() {
        var details = NlbSdkDetailsFactory.updateHealthChecker(new NlbRequests.HealthCheckerInput(
                "DNS", 53, 3, 3000, 10000, null, null, null, null, null,
                new NlbRequests.DnsHealthCheckerInput("UDP", "example.com", "IN", "A", List.of("NOERROR"))));

        assertThat(details.getProtocol().getValue()).isEqualTo("DNS");
        assertThat(details.getDns().getDomainName()).isEqualTo("example.com");
        assertThat(details.getDns().getTransportProtocol().getValue()).isEqualTo("UDP");

        assertThatThrownBy(() -> NlbSdkDetailsFactory.updateHealthChecker(
                new NlbRequests.HealthCheckerInput(
                        "DNS", 53, 3, 3000, 10000, null, null, null, null, null, null)))
                .isInstanceOf(OciException.class)
                .hasMessageContaining("DNS 健康检查参数不能为空");
    }
}
