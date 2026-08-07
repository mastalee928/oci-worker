package com.ociworker.nlb;

import com.ociworker.nlb.support.NlbSdkMapper;
import com.oracle.bmc.networkloadbalancer.model.NetworkLoadBalancerHealth;
import com.oracle.bmc.networkloadbalancer.model.OperationStatus;
import com.oracle.bmc.networkloadbalancer.model.OperationType;
import com.oracle.bmc.networkloadbalancer.model.WorkRequest;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NlbSdkMapperTest {

    @Test
    void mapsHealthWithoutLeakingSdkObjects() {
        var health = NetworkLoadBalancerHealth.builder()
                .status(NetworkLoadBalancerHealth.Status.Warning)
                .warningStateBackendSetNames(List.of("set-a"))
                .criticalStateBackendSetNames(List.of())
                .unknownStateBackendSetNames(List.of("set-b"))
                .totalBackendSetCount(2)
                .build();

        var mapped = NlbSdkMapper.networkLoadBalancerHealth(health);

        assertThat(mapped).containsEntry("status", "WARNING");
        assertThat(mapped).containsEntry("totalBackendSetCount", 2);
        assertThat(mapped.get("warningStateBackendSetNames")).isEqualTo(List.of("set-a"));
    }

    @Test
    void marksSucceededWorkRequestAsTerminalAndSuccessful() {
        Date now = new Date(1_000L);
        var workRequest = WorkRequest.builder()
                .id("wr-1")
                .compartmentId("compartment-1")
                .operationType(OperationType.CreateNetworkLoadBalancer)
                .status(OperationStatus.Succeeded)
                .percentComplete(100F)
                .resources(List.of())
                .timeAccepted(now)
                .timeStarted(now)
                .timeFinished(now)
                .build();

        var mapped = NlbSdkMapper.workRequest(workRequest);

        assertThat(mapped).containsEntry("status", "SUCCEEDED");
        assertThat(mapped).containsEntry("terminal", true);
        assertThat(mapped).containsEntry("successful", true);
        assertThat(mapped).containsEntry("percentComplete", 100F);
    }

    @Test
    void recognizesOnlyFinalWorkRequestStatesAsTerminal() {
        assertThat(NlbSdkMapper.isTerminalWorkRequestStatus("SUCCEEDED")).isTrue();
        assertThat(NlbSdkMapper.isTerminalWorkRequestStatus("FAILED")).isTrue();
        assertThat(NlbSdkMapper.isTerminalWorkRequestStatus("CANCELED")).isTrue();
        assertThat(NlbSdkMapper.isTerminalWorkRequestStatus("CANCELING")).isFalse();
        assertThat(NlbSdkMapper.isTerminalWorkRequestStatus("IN_PROGRESS")).isFalse();
    }
}
