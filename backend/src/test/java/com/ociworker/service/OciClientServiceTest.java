package com.ociworker.service;

import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.requests.ListInstancesRequest;
import com.oracle.bmc.model.BmcException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OciClientServiceTest {

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
}
