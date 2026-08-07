package com.ociworker.nlb;

import com.ociworker.model.vo.ResponseData;
import com.ociworker.nlb.model.NlbRequests;
import com.ociworker.service.VerifyCodeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for the independent OCI Network Load Balancer module. */
@RestController
@RequestMapping("/api/oci/nlb")
public class NetworkLoadBalancerController {

    private final NetworkLoadBalancerService service;
    private final VerifyCodeService verifyCodeService;

    public NetworkLoadBalancerController(
            NetworkLoadBalancerService service,
            VerifyCodeService verifyCodeService) {
        this.service = service;
        this.verifyCodeService = verifyCodeService;
    }

    @PostMapping("/list")
    public ResponseData<?> list(@RequestBody NlbRequests.ListRequest request) {
        return ResponseData.ok(service.list(request));
    }

    @PostMapping("/detail")
    public ResponseData<?> detail(@RequestBody NlbRequests.ResourceRequest request) {
        return ResponseData.ok(service.detail(request));
    }

    @PostMapping("/options")
    public ResponseData<?> options(@RequestBody NlbRequests.ListRequest request) {
        return ResponseData.ok(service.options(request));
    }

    @PostMapping({"/listeners", "/listener/list"})
    public ResponseData<?> listeners(@RequestBody NlbRequests.ResourceRequest request) {
        return ResponseData.ok(service.listeners(request));
    }

    @PostMapping("/listener/detail")
    public ResponseData<?> listener(@RequestBody NlbRequests.ListenerResourceRequest request) {
        return ResponseData.ok(service.listener(request));
    }

    @PostMapping({"/backend-sets", "/backend-set/list"})
    public ResponseData<?> backendSets(@RequestBody NlbRequests.ResourceRequest request) {
        return ResponseData.ok(service.backendSets(request));
    }

    @PostMapping("/backend-set/detail")
    public ResponseData<?> backendSet(@RequestBody NlbRequests.BackendSetResourceRequest request) {
        return ResponseData.ok(service.backendSet(request));
    }

    @PostMapping("/health-checker/detail")
    public ResponseData<?> healthChecker(@RequestBody NlbRequests.BackendSetResourceRequest request) {
        return ResponseData.ok(service.healthChecker(request));
    }

    @PostMapping({"/backends", "/backend/list"})
    public ResponseData<?> backends(@RequestBody NlbRequests.BackendSetResourceRequest request) {
        return ResponseData.ok(service.backends(request));
    }

    @PostMapping("/backend/detail")
    public ResponseData<?> backend(@RequestBody NlbRequests.BackendResourceRequest request) {
        return ResponseData.ok(service.backend(request));
    }

    @PostMapping("/health")
    public ResponseData<?> health(@RequestBody NlbRequests.ResourceRequest request) {
        return ResponseData.ok(service.networkLoadBalancerHealth(request));
    }

    @PostMapping("/backend-set/health")
    public ResponseData<?> backendSetHealth(@RequestBody NlbRequests.BackendSetResourceRequest request) {
        return ResponseData.ok(service.backendSetHealth(request));
    }

    @PostMapping("/backend/health")
    public ResponseData<?> backendHealth(@RequestBody NlbRequests.BackendResourceRequest request) {
        return ResponseData.ok(service.backendHealth(request));
    }

    @PostMapping("/create")
    public ResponseData<?> create(@RequestBody NlbRequests.CreateNetworkLoadBalancerRequest request) {
        return ResponseData.ok(service.create(request));
    }

    @PostMapping("/update")
    public ResponseData<?> update(@RequestBody NlbRequests.UpdateNetworkLoadBalancerRequest request) {
        return ResponseData.ok(service.update(request));
    }

    @PostMapping("/delete")
    public ResponseData<?> delete(@RequestBody NlbRequests.DeleteNetworkLoadBalancerRequest request) {
        verifyCodeService.verifyCode("deleteNlb", request.verifyCode(),
                contextKey(request.id(), request.networkLoadBalancerId()));
        return ResponseData.ok(service.delete(request));
    }

    @PostMapping("/update-nsgs")
    public ResponseData<?> updateNetworkSecurityGroups(
            @RequestBody NlbRequests.UpdateNetworkSecurityGroupsRequest request) {
        return ResponseData.ok(service.updateNetworkSecurityGroups(request));
    }

    @PostMapping("/change-compartment")
    public ResponseData<?> changeCompartment(@RequestBody NlbRequests.ChangeCompartmentRequest request) {
        verifyCodeService.verifyCode("changeNlbCompartment", request.verifyCode(),
                contextKey(request.id(), request.networkLoadBalancerId()));
        return ResponseData.ok(service.changeCompartment(request));
    }

    @PostMapping("/listener/create")
    public ResponseData<?> createListener(@RequestBody NlbRequests.CreateListenerRequest request) {
        return ResponseData.ok(service.createListener(request));
    }

    @PostMapping("/listener/update")
    public ResponseData<?> updateListener(@RequestBody NlbRequests.UpdateListenerRequest request) {
        return ResponseData.ok(service.updateListener(request));
    }

    @PostMapping("/listener/delete")
    public ResponseData<?> deleteListener(@RequestBody NlbRequests.DeleteListenerRequest request) {
        verifyCodeService.verifyCode("deleteNlbListener", request.verifyCode(),
                contextKey(request.id(), request.networkLoadBalancerId(), request.listenerName()));
        return ResponseData.ok(service.deleteListener(request));
    }

    @PostMapping("/backend-set/create")
    public ResponseData<?> createBackendSet(@RequestBody NlbRequests.CreateBackendSetRequest request) {
        return ResponseData.ok(service.createBackendSet(request));
    }

    @PostMapping("/backend-set/update")
    public ResponseData<?> updateBackendSet(@RequestBody NlbRequests.UpdateBackendSetRequest request) {
        return ResponseData.ok(service.updateBackendSet(request));
    }

    @PostMapping("/backend-set/delete")
    public ResponseData<?> deleteBackendSet(@RequestBody NlbRequests.DeleteBackendSetRequest request) {
        verifyCodeService.verifyCode("deleteNlbBackendSet", request.verifyCode(),
                contextKey(request.id(), request.networkLoadBalancerId(), request.backendSetName()));
        return ResponseData.ok(service.deleteBackendSet(request));
    }

    @PostMapping("/health-checker/update")
    public ResponseData<?> updateHealthChecker(@RequestBody NlbRequests.UpdateHealthCheckerRequest request) {
        return ResponseData.ok(service.updateHealthChecker(request));
    }

    @PostMapping("/backend/create")
    public ResponseData<?> createBackend(@RequestBody NlbRequests.CreateBackendRequest request) {
        return ResponseData.ok(service.createBackend(request));
    }

    @PostMapping("/backend/update")
    public ResponseData<?> updateBackend(@RequestBody NlbRequests.UpdateBackendRequest request) {
        return ResponseData.ok(service.updateBackend(request));
    }

    @PostMapping("/backend/delete")
    public ResponseData<?> deleteBackend(@RequestBody NlbRequests.DeleteBackendRequest request) {
        verifyCodeService.verifyCode("deleteNlbBackend", request.verifyCode(),
                contextKey(request.id(), request.networkLoadBalancerId(),
                        request.backendSetName(), request.backendName()));
        return ResponseData.ok(service.deleteBackend(request));
    }

    @PostMapping("/work-request")
    public ResponseData<?> workRequest(@RequestBody NlbRequests.WorkRequestResourceRequest request) {
        return ResponseData.ok(service.workRequest(request));
    }

    @PostMapping("/work-request/errors")
    public ResponseData<?> workRequestErrors(@RequestBody NlbRequests.WorkRequestResourceRequest request) {
        return ResponseData.ok(service.workRequestErrors(request));
    }

    @PostMapping("/work-request/logs")
    public ResponseData<?> workRequestLogs(@RequestBody NlbRequests.WorkRequestResourceRequest request) {
        return ResponseData.ok(service.workRequestLogs(request));
    }

    @PostMapping("/work-request/wait")
    public ResponseData<?> waitWorkRequest(@RequestBody NlbRequests.WaitWorkRequestRequest request) {
        return ResponseData.ok(service.waitWorkRequest(request));
    }

    private static String contextKey(String... values) {
        return String.join("|", values == null ? new String[0]
                : java.util.Arrays.stream(values).map(value -> value == null ? "" : value.trim())
                .toArray(String[]::new));
    }
}
