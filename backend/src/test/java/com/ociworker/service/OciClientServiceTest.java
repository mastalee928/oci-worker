package com.ociworker.service;

import com.ociworker.model.dto.SysUserDTO;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.requests.ListInstancesRequest;
import com.oracle.bmc.model.BmcException;
import org.junit.jupiter.api.Test;

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
    void separatesRateLimitAndGenericServerErrorFromHostCapacity() {
        BmcException rateLimited = new BmcException(429, "TooManyRequests", "slow down", "opc");
        BmcException genericServerError = new BmcException(500, "InternalError", "unexpected failure", "opc");
        BmcException capacity = new BmcException(500, "InternalError", "Out of host capacity.", "opc");
        BmcException a1CapacityLimitCode = new BmcException(
                400, "LimitExceeded", "Out of capacity for shape VM.Standard.A1.Flex.", "opc");
        BmcException actualServiceLimit = new BmcException(
                400, "LimitExceeded",
                "The following service limits were exceeded: standard-a1-core-count.", "opc");

        assertThat(OciClientService.isRateLimited(new RuntimeException(rateLimited))).isTrue();
        assertThat(OciClientService.isOutOfHostCapacityError(rateLimited)).isFalse();
        assertThat(OciClientService.isOutOfHostCapacityError(genericServerError)).isFalse();
        assertThat(OciClientService.isOutOfHostCapacityError(capacity)).isTrue();
        assertThat(OciClientService.isOutOfHostCapacityError(a1CapacityLimitCode)).isTrue();
        assertThat(OciClientService.isOciServiceLimitExceeded(a1CapacityLimitCode)).isFalse();
        assertThat(OciClientService.isOciServiceLimitExceeded(actualServiceLimit)).isTrue();
    }
}
