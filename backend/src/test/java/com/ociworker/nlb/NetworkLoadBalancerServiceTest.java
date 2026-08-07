package com.ociworker.nlb;

import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciUser;
import com.ociworker.nlb.model.NlbRequests;
import com.ociworker.service.OciClientService;
import com.ociworker.service.OciReadCacheService;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.Subnet;
import com.oracle.bmc.core.model.Vcn;
import com.oracle.bmc.core.requests.GetSubnetRequest;
import com.oracle.bmc.core.requests.ListSubnetsRequest;
import com.oracle.bmc.core.responses.GetSubnetResponse;
import com.oracle.bmc.core.responses.GetVcnResponse;
import com.oracle.bmc.core.responses.ListSubnetsResponse;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.Compartment;
import com.oracle.bmc.identity.requests.ListCompartmentsRequest;
import com.oracle.bmc.identity.responses.ListCompartmentsResponse;
import com.oracle.bmc.networkloadbalancer.NetworkLoadBalancerClient;
import com.oracle.bmc.networkloadbalancer.model.NetworkLoadBalancer;
import com.oracle.bmc.networkloadbalancer.model.NetworkLoadBalancerCollection;
import com.oracle.bmc.networkloadbalancer.model.NetworkLoadBalancerSummary;
import com.oracle.bmc.networkloadbalancer.requests.ListNetworkLoadBalancersRequest;
import com.oracle.bmc.networkloadbalancer.requests.ListWorkRequestErrorsRequest;
import com.oracle.bmc.networkloadbalancer.requests.ListWorkRequestLogsRequest;
import com.oracle.bmc.networkloadbalancer.responses.DeleteNetworkLoadBalancerResponse;
import com.oracle.bmc.networkloadbalancer.responses.GetNetworkLoadBalancerHealthResponse;
import com.oracle.bmc.networkloadbalancer.responses.GetNetworkLoadBalancerResponse;
import com.oracle.bmc.networkloadbalancer.responses.ListNetworkLoadBalancersResponse;
import com.oracle.bmc.networkloadbalancer.responses.ListWorkRequestErrorsResponse;
import com.oracle.bmc.networkloadbalancer.responses.ListWorkRequestLogsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetworkLoadBalancerServiceTest {

    private OciUserMapper userMapper;
    private OciClientService ociClient;
    private VirtualNetworkClient virtualNetworkClient;
    private IdentityClient identityClient;
    private NetworkLoadBalancerClient nlbClient;
    private NetworkLoadBalancerService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(OciUserMapper.class);
        ociClient = mock(OciClientService.class);
        virtualNetworkClient = mock(VirtualNetworkClient.class);
        identityClient = mock(IdentityClient.class);
        nlbClient = mock(NetworkLoadBalancerClient.class);

        OciUser user = new OciUser();
        user.setId("tenant-1");
        user.setOciTenantId("tenancy-1");
        user.setOciRegion("region-1");
        when(userMapper.selectById("tenant-1")).thenReturn(user);
        when(ociClient.getVirtualNetworkClient()).thenReturn(virtualNetworkClient);
        when(ociClient.getIdentityClient()).thenReturn(identityClient);
        when(ociClient.getNetworkLoadBalancerClient()).thenReturn(nlbClient);

        service = new NetworkLoadBalancerService(userMapper, new OciReadCacheService()) {
            @Override
            protected OciClientService openClient(OciUser ignored, String region) {
                return ociClient;
            }
        };
    }

    @Test
    void retryTokenIsStableForSameOperationAndChangesWithIdentity() {
        String first = NetworkLoadBalancerService.stableRetryToken(
                "create-nlb", "tenant-1", "region-1", "subnet-1", "edge");
        String repeated = NetworkLoadBalancerService.stableRetryToken(
                "create-nlb", "tenant-1", "region-1", "subnet-1", "edge");
        String changed = NetworkLoadBalancerService.stableRetryToken(
                "create-nlb", "tenant-1", "region-1", "subnet-2", "edge");

        assertThat(first).isEqualTo(repeated);
        assertThat(first).isNotEqualTo(changed);
        assertThat(first).hasSize(36);
    }

    @Test
    void workRequestDiagnosticsPassCompartmentIdRequiredByOci() {
        when(nlbClient.listWorkRequestErrors(any(ListWorkRequestErrorsRequest.class)))
                .thenReturn(mock(ListWorkRequestErrorsResponse.class));
        when(nlbClient.listWorkRequestLogs(any(ListWorkRequestLogsRequest.class)))
                .thenReturn(mock(ListWorkRequestLogsResponse.class));
        var request = new NlbRequests.WorkRequestResourceRequest(
                "tenant-1", "region-1", "compartment-1", "work-request-1");

        service.workRequestErrors(request);
        service.workRequestLogs(request);

        verify(nlbClient).listWorkRequestErrors(argThat(actual ->
                "work-request-1".equals(actual.getWorkRequestId())
                        && "compartment-1".equals(actual.getCompartmentId())));
        verify(nlbClient).listWorkRequestLogs(argThat(actual ->
                "work-request-1".equals(actual.getWorkRequestId())
                        && "compartment-1".equals(actual.getCompartmentId())));
    }

    @Test
    void rejectsSettingsThatConflictWithTransparentNlbMode() {
        NetworkLoadBalancer existing = mock(NetworkLoadBalancer.class);
        when(existing.getSubnetId()).thenReturn("subnet-1");
        when(existing.getIsPreserveSourceDestination()).thenReturn(true);
        when(existing.getIsSymmetricHashEnabled()).thenReturn(true);
        GetNetworkLoadBalancerResponse detailResponse = mock(GetNetworkLoadBalancerResponse.class);
        when(detailResponse.getNetworkLoadBalancer()).thenReturn(existing);
        when(nlbClient.getNetworkLoadBalancer(any())).thenReturn(detailResponse);

        GetSubnetResponse subnetResponse = mock(GetSubnetResponse.class);
        when(subnetResponse.getSubnet()).thenReturn(subnet("subnet-1", "vcn-1", "frontend", "10.0.1.0/24"));
        when(virtualNetworkClient.getSubnet(any(GetSubnetRequest.class))).thenReturn(subnetResponse);

        assertThatThrownBy(() -> service.update(new NlbRequests.UpdateNetworkLoadBalancerRequest(
                "tenant-1", "region-1", "compartment-1", "vcn-1", "nlb-1",
                null, null, false, null, null, null, null, null, null, null, null)))
                .isInstanceOf(com.ociworker.exception.OciException.class)
                .hasMessageContaining("对称哈希");

        assertThatThrownBy(() -> service.createBackendSet(new NlbRequests.CreateBackendSetRequest(
                "tenant-1", "region-1", "compartment-1", "vcn-1", "nlb-1", null,
                "set-1", "FIVE_TUPLE", false, false, false, false, false,
                "IPV4", List.of(), null)))
                .isInstanceOf(com.ociworker.exception.OciException.class)
                .hasMessageContaining("不能关闭保留源 IP");
    }

    @Test
    void listMergesOciPagesFiltersOtherVcnsAndCachesUntilMutation() {
        Vcn vcn = Vcn.builder().id("vcn-1").compartmentId("compartment-1").build();
        GetVcnResponse getVcnResponse = mock(GetVcnResponse.class);
        when(getVcnResponse.getVcn()).thenReturn(vcn);
        when(virtualNetworkClient.getVcn(any())).thenReturn(getVcnResponse);

        Compartment movedTarget = Compartment.builder()
                .id("compartment-moved")
                .name("moved")
                .lifecycleState(Compartment.LifecycleState.Active)
                .build();
        ListCompartmentsResponse compartmentsResponse = mock(ListCompartmentsResponse.class);
        when(compartmentsResponse.getItems()).thenReturn(List.of(movedTarget));
        when(identityClient.listCompartments(any(ListCompartmentsRequest.class))).thenReturn(compartmentsResponse);

        Subnet subnetOne = subnet("subnet-1", "vcn-1", "frontend", "10.0.1.0/24");
        Subnet subnetTwo = subnet("subnet-2", "vcn-1", "backend", "10.0.2.0/24");
        Subnet outsideSubnet = subnet("subnet-outside", "vcn-2", "outside", "10.2.0.0/24");
        when(virtualNetworkClient.listSubnets(any(ListSubnetsRequest.class))).thenAnswer(invocation -> {
            ListSubnetsRequest request = invocation.getArgument(0);
            ListSubnetsResponse response = mock(ListSubnetsResponse.class);
            if (request.getPage() == null) {
                when(response.getItems()).thenReturn(List.of(subnetOne));
                when(response.getOpcNextPage()).thenReturn("subnet-page-2");
            } else {
                when(response.getItems()).thenReturn(List.of(subnetTwo));
            }
            return response;
        });

        GetSubnetResponse getSubnetResponse = mock(GetSubnetResponse.class);
        when(virtualNetworkClient.getSubnet(any(GetSubnetRequest.class))).thenAnswer(invocation -> {
            GetSubnetRequest request = invocation.getArgument(0);
            when(getSubnetResponse.getSubnet()).thenReturn(
                    "subnet-1".equals(request.getSubnetId()) ? subnetOne : outsideSubnet);
            return getSubnetResponse;
        });

        NetworkLoadBalancerSummary first = summary("nlb-1", "subnet-1", "edge-one");
        NetworkLoadBalancerSummary outside = summary("nlb-outside", "subnet-outside", "wrong-vcn");
        NetworkLoadBalancerSummary second = summary("nlb-2", "subnet-2", "edge-two");
        NetworkLoadBalancerSummary moved = summary("nlb-moved", "subnet-1", "edge-moved", "compartment-moved");
        when(nlbClient.listNetworkLoadBalancers(any(ListNetworkLoadBalancersRequest.class))).thenAnswer(invocation -> {
            ListNetworkLoadBalancersRequest request = invocation.getArgument(0);
            ListNetworkLoadBalancersResponse response = mock(ListNetworkLoadBalancersResponse.class);
            NetworkLoadBalancerCollection collection = mock(NetworkLoadBalancerCollection.class);
            when(response.getNetworkLoadBalancerCollection()).thenReturn(collection);
            if ("compartment-moved".equals(request.getCompartmentId())) {
                when(collection.getItems()).thenReturn(List.of(moved));
            } else if ("tenancy-1".equals(request.getCompartmentId())) {
                when(collection.getItems()).thenReturn(List.of());
            } else if (request.getPage() == null) {
                when(collection.getItems()).thenReturn(List.of(first, outside));
                when(response.getOpcNextPage()).thenReturn("nlb-page-2");
            } else {
                when(collection.getItems()).thenReturn(List.of(second));
            }
            return response;
        });
        GetNetworkLoadBalancerHealthResponse healthResponse = mock(GetNetworkLoadBalancerHealthResponse.class);
        when(nlbClient.getNetworkLoadBalancerHealth(any())).thenReturn(healthResponse);

        NlbRequests.ListRequest listRequest = new NlbRequests.ListRequest(
                "tenant-1", "region-1", "compartment-1", "vcn-1", false);
        var firstRead = service.list(listRequest);
        var cachedRead = service.list(listRequest);

        assertThat(firstRead).extracting(row -> row.get("id"))
                .containsExactly("nlb-1", "nlb-2", "nlb-moved");
        assertThat(firstRead).extracting(row -> row.get("subnetName"))
                .containsExactly("frontend", "backend", "frontend");
        assertThat(cachedRead).isSameAs(firstRead);
        verify(nlbClient, times(4)).listNetworkLoadBalancers(any(ListNetworkLoadBalancersRequest.class));

        NetworkLoadBalancer existing = mock(NetworkLoadBalancer.class);
        when(existing.getSubnetId()).thenReturn("subnet-1");
        GetNetworkLoadBalancerResponse detailResponse = mock(GetNetworkLoadBalancerResponse.class);
        when(detailResponse.getNetworkLoadBalancer()).thenReturn(existing);
        when(nlbClient.getNetworkLoadBalancer(any())).thenReturn(detailResponse);
        when(nlbClient.deleteNetworkLoadBalancer(any())).thenReturn(mock(DeleteNetworkLoadBalancerResponse.class));

        service.delete(new NlbRequests.DeleteNetworkLoadBalancerRequest(
                "tenant-1", "region-1", "compartment-1", "vcn-1", "nlb-1", "etag-1", "123456"));
        service.list(listRequest);

        verify(nlbClient, times(8)).listNetworkLoadBalancers(any(ListNetworkLoadBalancersRequest.class));
    }

    private static Subnet subnet(String id, String vcnId, String name, String cidr) {
        return Subnet.builder()
                .id(id)
                .vcnId(vcnId)
                .compartmentId("compartment-1")
                .displayName(name)
                .cidrBlock(cidr)
                .lifecycleState(Subnet.LifecycleState.Available)
                .build();
    }

    private static NetworkLoadBalancerSummary summary(String id, String subnetId, String name) {
        return summary(id, subnetId, name, "compartment-1");
    }

    private static NetworkLoadBalancerSummary summary(
            String id, String subnetId, String name, String compartmentId) {
        NetworkLoadBalancerSummary summary = mock(NetworkLoadBalancerSummary.class);
        when(summary.getId()).thenReturn(id);
        when(summary.getCompartmentId()).thenReturn(compartmentId);
        when(summary.getSubnetId()).thenReturn(subnetId);
        when(summary.getDisplayName()).thenReturn(name);
        return summary;
    }
}
