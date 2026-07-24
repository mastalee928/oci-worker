package com.ociworker.service;

import com.ociworker.model.dto.SysUserDTO;
import com.oracle.bmc.core.model.CreateVnicDetails;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.InstanceSourceViaImageDetails;
import com.oracle.bmc.core.model.LaunchInstanceDetails;
import com.oracle.bmc.core.model.LaunchInstanceShapeConfigDetails;
import com.oracle.bmc.core.model.Subnet;
import com.oracle.bmc.core.model.Vcn;
import com.oracle.bmc.core.requests.ListInstancesRequest;
import com.oracle.bmc.model.BmcException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OciClientServiceTest {

    @Test
    void resolvesCustomAndDefaultLaunchDisplayNames() {
        SysUserDTO single = SysUserDTO.builder()
                .instanceName("  production  ")
                .createNumbers(1)
                .instanceDisplayOrdinal(1)
                .build();
        SysUserDTO batch = SysUserDTO.builder()
                .instanceName("worker")
                .createNumbers(3)
                .instanceDisplayOrdinal(2)
                .build();
        SysUserDTO defaultBatch = SysUserDTO.builder()
                .createNumbers(3)
                .instanceDisplayOrdinal(2)
                .build();

        assertThat(OciClientService.resolveLaunchDisplayName(single)).isEqualTo("production");
        assertThat(OciClientService.resolveLaunchDisplayName(batch)).isEqualTo("worker-2");
        assertThat(OciClientService.resolveLaunchDisplayName(defaultBatch)).isEqualTo("oci-worker-B");
    }

    @Test
    void launchRetryTokenIsStableForSameTaskOrdinalAndRequest() {
        SysUserDTO first = SysUserDTO.builder().taskId("task-1").instanceDisplayOrdinal(1).build();
        SysUserDTO second = SysUserDTO.builder().taskId("task-1").instanceDisplayOrdinal(2).build();
        LaunchInstanceDetails details = launchDetails("AD-1", "VM.Standard.A1.Flex", "script-1");
        LaunchInstanceDetails rebuiltDetails = launchDetails("AD-1", "VM.Standard.A1.Flex", "script-1");

        String token = OciClientService.resolveLaunchRetryToken(first, details);

        assertThat(OciClientService.resolveLaunchRetryToken(first, details)).isEqualTo(token);
        assertThat(OciClientService.resolveLaunchRetryToken(first, rebuiltDetails)).isEqualTo(token);
        assertThat(OciClientService.resolveLaunchRetryToken(second, details)).isNotEqualTo(token);
        assertThat(OciClientService.resolveLaunchRetryToken(first,
                launchDetails("AD-2", "VM.Standard.A1.Flex", "script-1"))).isNotEqualTo(token);
        assertThat(OciClientService.resolveLaunchRetryToken(first,
                launchDetails("AD-1", "VM.Standard.E4.Flex", "script-1"))).isNotEqualTo(token);
        assertThat(OciClientService.resolveLaunchRetryToken(first,
                launchDetails("AD-1", "VM.Standard.A1.Flex", "script-2"))).isNotEqualTo(token);
    }

    @Test
    void launchRetryTokenRotatesDeterministicallyAfterTerminalInstance() {
        SysUserDTO user = SysUserDTO.builder().taskId("task-1").instanceDisplayOrdinal(1).build();
        LaunchInstanceDetails details = launchDetails("AD-1", "VM.Standard.E2.1.Micro", "script-1");

        String base = OciClientService.resolveLaunchRetryToken(user, details);
        String afterFirstTerminal = OciClientService.resolveLaunchRetryToken(
                user, details, "instance-terminated-1");

        assertThat(afterFirstTerminal).isNotEqualTo(base);
        assertThat(OciClientService.resolveLaunchRetryToken(
                user, details, "instance-terminated-1")).isEqualTo(afterFirstTerminal);
        assertThat(OciClientService.resolveLaunchRetryToken(
                user, details, "instance-terminated-2")).isNotEqualTo(afterFirstTerminal);
    }

    @Test
    void launchRetryTokenIsRandomWithoutTaskId() {
        LaunchInstanceDetails details = launchDetails("AD-1", "VM.Standard.A1.Flex", "script-1");
        assertThat(OciClientService.resolveLaunchRetryToken(SysUserDTO.builder().build(), details))
                .isNotEqualTo(OciClientService.resolveLaunchRetryToken(SysUserDTO.builder().build(), details));
    }

    private static LaunchInstanceDetails launchDetails(String availabilityDomain, String shape, String userData) {
        return LaunchInstanceDetails.builder()
                .compartmentId("compartment-1")
                .availabilityDomain(availabilityDomain)
                .displayName("oci-worker-A")
                .shape(shape)
                .shapeConfig(LaunchInstanceShapeConfigDetails.builder().ocpus(1f).memoryInGBs(6f).build())
                .sourceDetails(InstanceSourceViaImageDetails.builder()
                        .imageId("image-1").bootVolumeSizeInGBs(50L).bootVolumeVpusPerGB(10L).build())
                .createVnicDetails(CreateVnicDetails.builder()
                        .subnetId("subnet-1").assignPublicIp(true).build())
                .metadata(java.util.Map.of("user_data", userData))
                .build();
    }

    @Test
    void describesZeroFlexShapeQuotaInChinese() {
        BmcException error = new BmcException(
                400,
                "InvalidParameter",
                "Invalid ratio of memory in GB to OCPUs. Current ratio: 3.0. Valid ratio range: 0 - 0",
                "opc-request-id");

        String message = OciClientService.describeBmcFailure(error, "VM.Standard.A2.Flex");

        assertThat(message).isEqualTo("当前账号没有 VM.Standard.A2.Flex 的 OCPU/内存配额");
        assertThat(message).doesNotContain("Invalid ratio");
    }

    @Test
    void buildsUnfilteredPagedInstanceRequestAndFiltersLifecycleLocally() {
        ListInstancesRequest request = OciClientService.instanceListRequest("compartment-1", "page-2");

        assertThat(request.getCompartmentId()).isEqualTo("compartment-1");
        assertThat(request.getLifecycleState()).isNull();
        assertThat(request.getLimit()).isEqualTo(1000);
        assertThat(request.getPage()).isEqualTo("page-2");
        assertThat(OciClientService.isVisibleInstanceLifecycle(Instance.LifecycleState.Running)).isTrue();
        assertThat(OciClientService.isVisibleInstanceLifecycle(Instance.LifecycleState.Stopped)).isTrue();
        assertThat(OciClientService.isVisibleInstanceLifecycle(Instance.LifecycleState.Terminated)).isFalse();
    }

    @Test
    void treatsTerminalLaunchStatesAsRetryableFailures() {
        assertThat(OciClientService.isTerminalLaunchState(Instance.LifecycleState.Terminating)).isTrue();
        assertThat(OciClientService.isTerminalLaunchState(Instance.LifecycleState.Terminated)).isTrue();
        assertThat(OciClientService.isTerminalLaunchState(Instance.LifecycleState.Stopped)).isTrue();
        assertThat(OciClientService.isTerminalLaunchState(Instance.LifecycleState.Provisioning)).isFalse();
        assertThat(OciClientService.isTerminalLaunchState(Instance.LifecycleState.Starting)).isFalse();
        assertThat(OciClientService.isTerminalLaunchState(Instance.LifecycleState.Running)).isFalse();
        assertThat(OciClientService.isTerminalLaunchState(null)).isFalse();
    }

    @Test
    void selectsOnlyMatchingAdOrRegionalSubnet() {
        Subnet ad2 = subnet("subnet-ad2", "eu-frankfurt-1-ad-2", "10.0.0.0/24", false);
        Subnet ad3 = subnet("subnet-ad3", "eu-frankfurt-1-ad-3", "10.0.1.0/24", false);
        Subnet regional = subnet("subnet-regional", null, "10.0.2.0/24", false);
        Subnet prohibited = subnet("subnet-prohibited", "eu-frankfurt-1-ad-4", "10.0.3.0/24", true);

        assertThat(OciClientService.selectSubnetForAvailabilityDomain(
                List.of(ad2, ad3, regional, prohibited), "eu-frankfurt-1-ad-2")).isSameAs(ad2);
        assertThat(OciClientService.selectSubnetForAvailabilityDomain(
                List.of(ad2, ad3, regional, prohibited), "eu-frankfurt-1-ad-3")).isSameAs(ad3);
        assertThat(OciClientService.selectSubnetForAvailabilityDomain(
                List.of(ad2, ad3, regional, prohibited), "eu-frankfurt-1-ad-5")).isSameAs(regional);
        assertThat(OciClientService.selectSubnetForAvailabilityDomain(
                List.of(ad2, ad3, prohibited), "eu-frankfurt-1-ad-5")).isNull();
    }

    @Test
    void allocatesFirstNonOverlappingSubnetCidrInsideVcn() {
        Vcn vcn = Vcn.builder().cidrBlocks(List.of("10.0.0.0/16")).build();
        List<Subnet> subnets = List.of(
                subnet("subnet-1", "eu-frankfurt-1-ad-2", "10.0.0.0/24", false),
                subnet("subnet-2", "eu-frankfurt-1-ad-3", "10.0.2.0/24", false));

        assertThat(OciClientService.nextAvailableSubnetCidr(vcn, subnets))
                .isEqualTo("10.0.1.0/24");
    }

    @Test
    void skipsOverlappingSupernetAndReturnsNullWhenVcnIsFull() {
        assertThat(OciClientService.nextAvailableSubnetCidr(
                "10.0.0.0/16", List.of("10.0.0.0/23")))
                .isEqualTo("10.0.2.0/24");
        assertThat(OciClientService.nextAvailableSubnetCidr(
                "10.0.0.0/24", List.of("10.0.0.0/24")))
                .isNull();
    }

    @Test
    void checksAllVcnIpv4CidrBlocksWhenAllocatingSubnet() {
        Vcn vcn = Vcn.builder()
                .cidrBlocks(List.of("10.0.0.0/30", "192.168.0.0/24"))
                .build();

        List<Subnet> occupied = List.of(subnet("subnet-full", null, "10.0.0.0/30", false));
        assertThat(OciClientService.nextAvailableSubnetCidr(vcn, occupied))
                .isEqualTo("192.168.0.0/24");
    }

    @Test
    void separatesRateLimitAndGenericServerErrorFromHostCapacity() {
        BmcException rateLimited = new BmcException(429, "TooManyRequests", "slow down", "opc");
        BmcException genericServerError = new BmcException(500, "InternalError", "unexpected failure", "opc");
        BmcException capacity = new BmcException(500, "InternalError", "Out of host capacity.", "opc");
        BmcException a1CapacityLimitCode = new BmcException(
                400, "LimitExceeded", "Out of capacity for shape VM.Standard.A1.Flex.", "opc");
        BmcException actualServiceLimit = new BmcException(
                400, "LimitExceeded",
                "The following service limits were exceeded: standard-a1-core-count.", "opc");
        BmcException capacityWithLimitWording = new BmcException(
                400, "LimitExceeded",
                "Out of capacity for shape VM.Standard.A1.Flex. The following service limits were exceeded.", "opc");
        BmcException unavailableCapacity = new BmcException(
                400, "LimitExceeded", "Capacity is not available for this shape.", "opc");

        assertThat(OciClientService.isRateLimited(new RuntimeException(rateLimited))).isTrue();
        assertThat(OciClientService.isOutOfHostCapacityError(rateLimited)).isFalse();
        assertThat(OciClientService.isOutOfHostCapacityError(genericServerError)).isFalse();
        assertThat(OciClientService.isOutOfHostCapacityError(capacity)).isTrue();
        assertThat(OciClientService.isOutOfHostCapacityError(a1CapacityLimitCode)).isTrue();
        assertThat(OciClientService.isOciServiceLimitExceeded(a1CapacityLimitCode)).isFalse();
        assertThat(OciClientService.isOciServiceLimitExceeded(capacityWithLimitWording)).isFalse();
        assertThat(OciClientService.isOciServiceLimitExceeded(unavailableCapacity)).isFalse();
        assertThat(OciClientService.isOciServiceLimitExceeded(actualServiceLimit)).isTrue();
        assertThat(OciClientService.describeBmcFailure(a1CapacityLimitCode, "VM.Standard.A1.Flex"))
                .isEqualTo("主机容量不足");
    }

    private static Subnet subnet(String id, String availabilityDomain, String cidr, boolean prohibitIngress) {
        return Subnet.builder()
                .id(id)
                .availabilityDomain(availabilityDomain)
                .cidrBlock(cidr)
                .prohibitInternetIngress(prohibitIngress)
                .build();
    }
}
