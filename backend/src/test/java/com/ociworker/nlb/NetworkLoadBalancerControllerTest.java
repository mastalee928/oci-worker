package com.ociworker.nlb;

import com.ociworker.nlb.model.NlbRequests;
import com.ociworker.service.VerifyCodeService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetworkLoadBalancerControllerTest {

    @Test
    void verifiesResourceBoundCodeBeforeDeletingNlb() {
        NetworkLoadBalancerService service = mock(NetworkLoadBalancerService.class);
        VerifyCodeService verifyCodeService = mock(VerifyCodeService.class);
        NetworkLoadBalancerController controller = new NetworkLoadBalancerController(service, verifyCodeService);
        var request = new NlbRequests.DeleteNetworkLoadBalancerRequest(
                "tenant-1", "region-1", "compartment-1", "vcn-1",
                "nlb-1", "etag-1", "123456");
        when(service.delete(request)).thenReturn(Map.of("submitted", true));

        var response = controller.delete(request);

        verify(verifyCodeService).verifyCode("deleteNlb", "123456", "tenant-1|nlb-1");
        verify(service).delete(request);
        assertThat(response.getCode()).isZero();
    }

    @Test
    void verifiesBackendCodeAgainstFullResourcePath() {
        NetworkLoadBalancerService service = mock(NetworkLoadBalancerService.class);
        VerifyCodeService verifyCodeService = mock(VerifyCodeService.class);
        NetworkLoadBalancerController controller = new NetworkLoadBalancerController(service, verifyCodeService);
        var request = new NlbRequests.DeleteBackendRequest(
                "tenant-1", "region-1", "compartment-1", "vcn-1",
                "nlb-1", "set-a", "backend-a", "etag-1", "654321");
        when(service.deleteBackend(request)).thenReturn(Map.of("submitted", true));

        controller.deleteBackend(request);

        verify(verifyCodeService).verifyCode(
                "deleteNlbBackend", "654321", "tenant-1|nlb-1|set-a|backend-a");
        verify(service).deleteBackend(request);
    }

    @Test
    void bindsCompartmentMoveCodeToTheSelectedTargetCompartment() {
        NetworkLoadBalancerService service = mock(NetworkLoadBalancerService.class);
        VerifyCodeService verifyCodeService = mock(VerifyCodeService.class);
        NetworkLoadBalancerController controller = new NetworkLoadBalancerController(service, verifyCodeService);
        var request = new NlbRequests.ChangeCompartmentRequest(
                "tenant-1", "region-1", "compartment-1", "vcn-1",
                "nlb-1", "etag-1", "compartment-2", "123456");
        when(service.changeCompartment(request)).thenReturn(Map.of("submitted", true));

        controller.changeCompartment(request);

        verify(verifyCodeService).verifyCode(
                "changeNlbCompartment", "123456", "tenant-1|nlb-1|compartment-2");
        verify(service).changeCompartment(request);
    }
}
