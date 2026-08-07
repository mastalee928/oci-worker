package com.ociworker.nlb;

import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.ociworker.nlb.model.NlbRequests;
import com.ociworker.nlb.support.NlbSdkDetailsFactory;
import com.ociworker.nlb.support.NlbSdkMapper;
import com.ociworker.service.OciClientService;
import com.ociworker.service.OciReadCacheService;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.NetworkSecurityGroup;
import com.oracle.bmc.core.model.Subnet;
import com.oracle.bmc.core.model.Vcn;
import com.oracle.bmc.core.requests.GetSubnetRequest;
import com.oracle.bmc.core.requests.GetVcnRequest;
import com.oracle.bmc.core.requests.ListNetworkSecurityGroupsRequest;
import com.oracle.bmc.core.requests.ListSubnetsRequest;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.Compartment;
import com.oracle.bmc.identity.requests.ListCompartmentsRequest;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.networkloadbalancer.NetworkLoadBalancerClient;
import com.oracle.bmc.networkloadbalancer.model.ChangeNetworkLoadBalancerCompartmentDetails;
import com.oracle.bmc.networkloadbalancer.model.DnsHealthCheckQueryClasses;
import com.oracle.bmc.networkloadbalancer.model.DnsHealthCheckQueryTypes;
import com.oracle.bmc.networkloadbalancer.model.DnsHealthCheckRCodes;
import com.oracle.bmc.networkloadbalancer.model.DnsHealthCheckTransportProtocols;
import com.oracle.bmc.networkloadbalancer.model.HealthCheckProtocols;
import com.oracle.bmc.networkloadbalancer.model.IpVersion;
import com.oracle.bmc.networkloadbalancer.model.NetworkLoadBalancerHealth;
import com.oracle.bmc.networkloadbalancer.model.NetworkLoadBalancerSummary;
import com.oracle.bmc.networkloadbalancer.model.NlbIpVersion;
import com.oracle.bmc.networkloadbalancer.model.UpdateNetworkSecurityGroupsDetails;
import com.oracle.bmc.networkloadbalancer.requests.ChangeNetworkLoadBalancerCompartmentRequest;
import com.oracle.bmc.networkloadbalancer.requests.CreateBackendRequest;
import com.oracle.bmc.networkloadbalancer.requests.CreateBackendSetRequest;
import com.oracle.bmc.networkloadbalancer.requests.CreateListenerRequest;
import com.oracle.bmc.networkloadbalancer.requests.CreateNetworkLoadBalancerRequest;
import com.oracle.bmc.networkloadbalancer.requests.DeleteBackendRequest;
import com.oracle.bmc.networkloadbalancer.requests.DeleteBackendSetRequest;
import com.oracle.bmc.networkloadbalancer.requests.DeleteListenerRequest;
import com.oracle.bmc.networkloadbalancer.requests.DeleteNetworkLoadBalancerRequest;
import com.oracle.bmc.networkloadbalancer.requests.GetBackendHealthRequest;
import com.oracle.bmc.networkloadbalancer.requests.GetBackendOperationalStatusRequest;
import com.oracle.bmc.networkloadbalancer.requests.GetBackendRequest;
import com.oracle.bmc.networkloadbalancer.requests.GetBackendSetHealthRequest;
import com.oracle.bmc.networkloadbalancer.requests.GetBackendSetRequest;
import com.oracle.bmc.networkloadbalancer.requests.GetHealthCheckerRequest;
import com.oracle.bmc.networkloadbalancer.requests.GetListenerRequest;
import com.oracle.bmc.networkloadbalancer.requests.GetNetworkLoadBalancerHealthRequest;
import com.oracle.bmc.networkloadbalancer.requests.GetNetworkLoadBalancerRequest;
import com.oracle.bmc.networkloadbalancer.requests.GetWorkRequestRequest;
import com.oracle.bmc.networkloadbalancer.requests.ListBackendSetsRequest;
import com.oracle.bmc.networkloadbalancer.requests.ListBackendsRequest;
import com.oracle.bmc.networkloadbalancer.requests.ListListenersRequest;
import com.oracle.bmc.networkloadbalancer.requests.ListNetworkLoadBalancersPoliciesRequest;
import com.oracle.bmc.networkloadbalancer.requests.ListNetworkLoadBalancersProtocolsRequest;
import com.oracle.bmc.networkloadbalancer.requests.ListNetworkLoadBalancersRequest;
import com.oracle.bmc.networkloadbalancer.requests.ListWorkRequestErrorsRequest;
import com.oracle.bmc.networkloadbalancer.requests.ListWorkRequestLogsRequest;
import com.oracle.bmc.networkloadbalancer.requests.UpdateBackendRequest;
import com.oracle.bmc.networkloadbalancer.requests.UpdateBackendSetRequest;
import com.oracle.bmc.networkloadbalancer.requests.UpdateHealthCheckerRequest;
import com.oracle.bmc.networkloadbalancer.requests.UpdateListenerRequest;
import com.oracle.bmc.networkloadbalancer.requests.UpdateNetworkLoadBalancerRequest;
import com.oracle.bmc.networkloadbalancer.requests.UpdateNetworkSecurityGroupsRequest;
import com.oracle.bmc.networkloadbalancer.responses.GetNetworkLoadBalancerResponse;
import com.oracle.bmc.networkloadbalancer.responses.GetWorkRequestResponse;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Independent OCI Network Load Balancer business module. */
@Service
public class NetworkLoadBalancerService {

    private static final Duration READ_CACHE_TTL = Duration.ofSeconds(30);
    private static final Duration HEALTH_CACHE_TTL = Duration.ofSeconds(10);
    private static final Duration OPTIONS_CACHE_TTL = Duration.ofMinutes(5);
    private static final int PAGE_LIMIT = 1000;
    private static final int DEFAULT_WAIT_SECONDS = 60;
    private static final int MAX_WAIT_SECONDS = 120;
    private static final int DEFAULT_POLL_MILLIS = 1500;

    private final OciUserMapper userMapper;
    private final OciReadCacheService readCacheService;

    public NetworkLoadBalancerService(OciUserMapper userMapper, OciReadCacheService readCacheService) {
        this.userMapper = userMapper;
        this.readCacheService = readCacheService;
    }

    public List<Map<String, Object>> list(NlbRequests.ListRequest request) {
        NlbRequests.ListRequest input = require(request, "查询参数不能为空");
        OciUser user = requireUser(input.id());
        String vcnId = required(input.vcnId(), "VCN 不能为空");
        String compartmentId = required(input.compartmentId(), "Compartment 不能为空");
        return readCacheService.get(cacheKey(user, input.region(), "list", compartmentId, vcnId),
                READ_CACHE_TTL, input.forceEnabled(), () -> withClient(user, input.region(), "查询负载均衡器", client -> {
                    Vcn vcn = getVcn(client.getVirtualNetworkClient(), vcnId);
                    Map<String, Subnet> subnets = listVcnSubnets(client.getVirtualNetworkClient(), compartmentId, vcnId);
                    Map<String, Map<String, Object>> result = new LinkedHashMap<>();
                    for (String searchCompartmentId : listNlbSearchCompartments(
                            client.getIdentityClient(), user, compartmentId)) {
                        List<Map<String, Object>> compartmentRows = new ArrayList<>();
                        try {
                            String page = null;
                            do {
                                var response = client.getNetworkLoadBalancerClient().listNetworkLoadBalancers(
                                        ListNetworkLoadBalancersRequest.builder()
                                                .compartmentId(searchCompartmentId)
                                                .limit(PAGE_LIMIT)
                                                .page(page)
                                                .build());
                                if (response.getNetworkLoadBalancerCollection() != null
                                        && response.getNetworkLoadBalancerCollection().getItems() != null) {
                                    for (NetworkLoadBalancerSummary item : response.getNetworkLoadBalancerCollection().getItems()) {
                                        Subnet subnet = subnetInVcn(client.getVirtualNetworkClient(), subnets,
                                                item.getSubnetId(), vcn.getId());
                                        if (subnet == null) continue;
                                        String health = safeNetworkLoadBalancerHealthStatus(
                                                client.getNetworkLoadBalancerClient(), item.getId());
                                        Map<String, Object> row = NlbSdkMapper.networkLoadBalancerSummary(item, health);
                                        row.put("subnetName", subnet.getDisplayName());
                                        row.put("subnetCidrBlock", subnet.getCidrBlock());
                                        compartmentRows.add(row);
                                    }
                                }
                                page = response.getOpcNextPage();
                            } while (notBlank(page));
                        } catch (BmcException e) {
                            if (Objects.equals(searchCompartmentId, compartmentId)) throw e;
                            continue;
                        }
                        for (Map<String, Object> row : compartmentRows) {
                            String id = Objects.toString(row.get("id"), "");
                            if (notBlank(id)) result.putIfAbsent(id, row);
                        }
                    }
                    return new ArrayList<>(result.values());
                }));
    }

    public Map<String, Object> detail(NlbRequests.ResourceRequest request) {
        NlbRequests.ResourceRequest input = require(request, "查询参数不能为空");
        OciUser user = requireUser(input.id());
        String nlbId = required(input.networkLoadBalancerId(), "负载均衡器 OCID 不能为空");
        String vcnId = required(input.vcnId(), "VCN 不能为空");
        return readCacheService.get(cacheKey(user, input.region(), "detail", vcnId, nlbId),
                READ_CACHE_TTL, input.forceEnabled(), () -> withClient(user, input.region(), "查询负载均衡器详情", client -> {
                    NlbContext context = requireNlbInVcn(client, nlbId, vcnId);
                    NetworkLoadBalancerHealth health = null;
                    String healthError = null;
                    try {
                        health = client.getNetworkLoadBalancerClient().getNetworkLoadBalancerHealth(
                                GetNetworkLoadBalancerHealthRequest.builder().networkLoadBalancerId(nlbId).build())
                                .getNetworkLoadBalancerHealth();
                    } catch (BmcException e) {
                        healthError = healthError(e);
                    }
                    Map<String, Object> result = NlbSdkMapper.networkLoadBalancer(
                            context.response().getNetworkLoadBalancer(), context.response().getEtag(), health);
                    result.put("subnet", subnetMap(context.subnet()));
                    if (healthError != null) result.put("healthError", healthError);
                    return result;
                }));
    }

    public Map<String, Object> options(NlbRequests.ListRequest request) {
        NlbRequests.ListRequest input = require(request, "查询参数不能为空");
        OciUser user = requireUser(input.id());
        String vcnId = required(input.vcnId(), "VCN 不能为空");
        String compartmentId = required(input.compartmentId(), "Compartment 不能为空");
        return readCacheService.get(cacheKey(user, input.region(), "options", compartmentId, vcnId),
                OPTIONS_CACHE_TTL, input.forceEnabled(), () -> withClient(user, input.region(), "查询负载均衡器选项", client -> {
                    getVcn(client.getVirtualNetworkClient(), vcnId);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("subnets", listVcnSubnets(client.getVirtualNetworkClient(), compartmentId, vcnId)
                            .values().stream().map(NetworkLoadBalancerService::subnetMap).toList());
                    result.put("networkSecurityGroups", listNetworkSecurityGroups(
                            client.getVirtualNetworkClient(), compartmentId, vcnId));
                    result.put("policies", listPolicies(client.getNetworkLoadBalancerClient()));
                    result.put("protocols", listProtocols(client.getNetworkLoadBalancerClient()));
                    result.put("nlbIpVersions", enumValues(NlbIpVersion.values()));
                    result.put("ipVersions", enumValues(IpVersion.values()));
                    result.put("healthCheckProtocols", enumValues(HealthCheckProtocols.values()));
                    result.put("dnsTransportProtocols", enumValues(DnsHealthCheckTransportProtocols.values()));
                    result.put("dnsQueryClasses", enumValues(DnsHealthCheckQueryClasses.values()));
                    result.put("dnsQueryTypes", enumValues(DnsHealthCheckQueryTypes.values()));
                    result.put("dnsRcodes", enumValues(DnsHealthCheckRCodes.values()));
                    return result;
                }));
    }

    public List<Map<String, Object>> listeners(NlbRequests.ResourceRequest request) {
        NlbRequests.ResourceRequest input = require(request, "查询参数不能为空");
        OciUser user = requireUser(input.id());
        String nlbId = required(input.networkLoadBalancerId(), "负载均衡器 OCID 不能为空");
        String vcnId = required(input.vcnId(), "VCN 不能为空");
        return readCacheService.get(cacheKey(user, input.region(), "listeners", vcnId, nlbId),
                READ_CACHE_TTL, input.forceEnabled(), () -> withClient(user, input.region(), "查询 Listener", client -> {
                    requireNlbInVcn(client, nlbId, vcnId);
                    List<Map<String, Object>> result = new ArrayList<>();
                    String page = null;
                    do {
                        var response = client.getNetworkLoadBalancerClient().listListeners(
                                ListListenersRequest.builder().networkLoadBalancerId(nlbId)
                                        .limit(PAGE_LIMIT).page(page).build());
                        if (response.getListenerCollection() != null && response.getListenerCollection().getItems() != null) {
                            response.getListenerCollection().getItems()
                                    .forEach(item -> result.add(NlbSdkMapper.listener(item, null)));
                        }
                        page = response.getOpcNextPage();
                    } while (notBlank(page));
                    return result;
                }));
    }

    public Map<String, Object> listener(NlbRequests.ListenerResourceRequest request) {
        NlbRequests.ListenerResourceRequest input = require(request, "查询参数不能为空");
        OciUser user = requireUser(input.id());
        String nlbId = required(input.networkLoadBalancerId(), "负载均衡器 OCID 不能为空");
        String vcnId = required(input.vcnId(), "VCN 不能为空");
        String listenerName = required(input.listenerName(), "Listener 名称不能为空");
        return readCacheService.get(cacheKey(user, input.region(), "listener", vcnId, nlbId, listenerName),
                READ_CACHE_TTL, input.forceEnabled(), () -> withClient(user, input.region(), "查询 Listener 详情", client -> {
                    requireNlbInVcn(client, nlbId, vcnId);
                    var response = client.getNetworkLoadBalancerClient().getListener(GetListenerRequest.builder()
                            .networkLoadBalancerId(nlbId).listenerName(listenerName).build());
                    return NlbSdkMapper.listener(response.getListener(), response.getEtag());
                }));
    }

    public List<Map<String, Object>> backendSets(NlbRequests.ResourceRequest request) {
        NlbRequests.ResourceRequest input = require(request, "查询参数不能为空");
        OciUser user = requireUser(input.id());
        String nlbId = required(input.networkLoadBalancerId(), "负载均衡器 OCID 不能为空");
        String vcnId = required(input.vcnId(), "VCN 不能为空");
        return readCacheService.get(cacheKey(user, input.region(), "backend-sets", vcnId, nlbId),
                READ_CACHE_TTL, input.forceEnabled(), () -> withClient(user, input.region(), "查询 Backend Set", client -> {
                    requireNlbInVcn(client, nlbId, vcnId);
                    List<Map<String, Object>> result = new ArrayList<>();
                    String page = null;
                    do {
                        var response = client.getNetworkLoadBalancerClient().listBackendSets(
                                ListBackendSetsRequest.builder().networkLoadBalancerId(nlbId)
                                        .limit(PAGE_LIMIT).page(page).build());
                        if (response.getBackendSetCollection() != null && response.getBackendSetCollection().getItems() != null) {
                            response.getBackendSetCollection().getItems().forEach(item -> {
                                Map<String, Object> row = NlbSdkMapper.backendSet(item, null);
                                row.put("health", safeBackendSetHealth(client.getNetworkLoadBalancerClient(), nlbId, item.getName()));
                                result.add(row);
                            });
                        }
                        page = response.getOpcNextPage();
                    } while (notBlank(page));
                    return result;
                }));
    }

    public Map<String, Object> backendSet(NlbRequests.BackendSetResourceRequest request) {
        NlbRequests.BackendSetResourceRequest input = require(request, "查询参数不能为空");
        OciUser user = requireUser(input.id());
        String nlbId = required(input.networkLoadBalancerId(), "负载均衡器 OCID 不能为空");
        String vcnId = required(input.vcnId(), "VCN 不能为空");
        String backendSetName = required(input.backendSetName(), "Backend Set 名称不能为空");
        return readCacheService.get(cacheKey(user, input.region(), "backend-set", vcnId, nlbId, backendSetName),
                READ_CACHE_TTL, input.forceEnabled(), () -> withClient(user, input.region(), "查询 Backend Set 详情", client -> {
                    requireNlbInVcn(client, nlbId, vcnId);
                    var response = client.getNetworkLoadBalancerClient().getBackendSet(GetBackendSetRequest.builder()
                            .networkLoadBalancerId(nlbId).backendSetName(backendSetName).build());
                    Map<String, Object> result = NlbSdkMapper.backendSet(response.getBackendSet(), response.getEtag());
                    result.put("health", safeBackendSetHealth(client.getNetworkLoadBalancerClient(), nlbId, backendSetName));
                    return result;
                }));
    }

    public Map<String, Object> healthChecker(NlbRequests.BackendSetResourceRequest request) {
        NlbRequests.BackendSetResourceRequest input = require(request, "查询参数不能为空");
        OciUser user = requireUser(input.id());
        String nlbId = required(input.networkLoadBalancerId(), "负载均衡器 OCID 不能为空");
        String vcnId = required(input.vcnId(), "VCN 不能为空");
        String backendSetName = required(input.backendSetName(), "Backend Set 名称不能为空");
        return readCacheService.get(cacheKey(user, input.region(), "health-checker", vcnId, nlbId, backendSetName),
                READ_CACHE_TTL, input.forceEnabled(), () -> withClient(user, input.region(), "查询健康检查器", client -> {
                    requireNlbInVcn(client, nlbId, vcnId);
                    var response = client.getNetworkLoadBalancerClient().getHealthChecker(GetHealthCheckerRequest.builder()
                            .networkLoadBalancerId(nlbId).backendSetName(backendSetName).build());
                    return NlbSdkMapper.healthChecker(response.getHealthChecker(), response.getEtag());
                }));
    }

    public List<Map<String, Object>> backends(NlbRequests.BackendSetResourceRequest request) {
        NlbRequests.BackendSetResourceRequest input = require(request, "查询参数不能为空");
        OciUser user = requireUser(input.id());
        String nlbId = required(input.networkLoadBalancerId(), "负载均衡器 OCID 不能为空");
        String vcnId = required(input.vcnId(), "VCN 不能为空");
        String backendSetName = required(input.backendSetName(), "Backend Set 名称不能为空");
        return readCacheService.get(cacheKey(user, input.region(), "backends", vcnId, nlbId, backendSetName),
                READ_CACHE_TTL, input.forceEnabled(), () -> withClient(user, input.region(), "查询 Backend", client -> {
                    requireNlbInVcn(client, nlbId, vcnId);
                    List<Map<String, Object>> result = new ArrayList<>();
                    String page = null;
                    do {
                        var response = client.getNetworkLoadBalancerClient().listBackends(ListBackendsRequest.builder()
                                .networkLoadBalancerId(nlbId).backendSetName(backendSetName)
                                .limit(PAGE_LIMIT).page(page).build());
                        if (response.getBackendCollection() != null && response.getBackendCollection().getItems() != null) {
                            response.getBackendCollection().getItems().forEach(item -> {
                                Map<String, Object> row = NlbSdkMapper.backend(item, null);
                                row.put("health", safeBackendHealth(client.getNetworkLoadBalancerClient(),
                                        nlbId, backendSetName, item.getName()));
                                result.add(row);
                            });
                        }
                        page = response.getOpcNextPage();
                    } while (notBlank(page));
                    return result;
                }));
    }

    public Map<String, Object> backend(NlbRequests.BackendResourceRequest request) {
        NlbRequests.BackendResourceRequest input = require(request, "查询参数不能为空");
        OciUser user = requireUser(input.id());
        String nlbId = required(input.networkLoadBalancerId(), "负载均衡器 OCID 不能为空");
        String vcnId = required(input.vcnId(), "VCN 不能为空");
        String backendSetName = required(input.backendSetName(), "Backend Set 名称不能为空");
        String backendName = required(input.backendName(), "Backend 名称不能为空");
        return readCacheService.get(cacheKey(user, input.region(), "backend", vcnId, nlbId, backendSetName, backendName),
                READ_CACHE_TTL, input.forceEnabled(), () -> withClient(user, input.region(), "查询 Backend 详情", client -> {
                    requireNlbInVcn(client, nlbId, vcnId);
                    var response = client.getNetworkLoadBalancerClient().getBackend(GetBackendRequest.builder()
                            .networkLoadBalancerId(nlbId).backendSetName(backendSetName)
                            .backendName(backendName).build());
                    Map<String, Object> result = NlbSdkMapper.backend(response.getBackend(), response.getEtag());
                    result.put("health", safeBackendHealth(client.getNetworkLoadBalancerClient(),
                            nlbId, backendSetName, backendName));
                    return result;
                }));
    }

    public Map<String, Object> networkLoadBalancerHealth(NlbRequests.ResourceRequest request) {
        NlbRequests.ResourceRequest input = require(request, "查询参数不能为空");
        OciUser user = requireUser(input.id());
        String nlbId = required(input.networkLoadBalancerId(), "负载均衡器 OCID 不能为空");
        String vcnId = required(input.vcnId(), "VCN 不能为空");
        return readCacheService.get(cacheKey(user, input.region(), "health", vcnId, nlbId),
                HEALTH_CACHE_TTL, input.forceEnabled(), () -> withClient(user, input.region(), "查询负载均衡器健康状态", client -> {
                    requireNlbInVcn(client, nlbId, vcnId);
                    return NlbSdkMapper.networkLoadBalancerHealth(client.getNetworkLoadBalancerClient()
                            .getNetworkLoadBalancerHealth(GetNetworkLoadBalancerHealthRequest.builder()
                                    .networkLoadBalancerId(nlbId).build()).getNetworkLoadBalancerHealth());
                }));
    }

    public Map<String, Object> backendSetHealth(NlbRequests.BackendSetResourceRequest request) {
        NlbRequests.BackendSetResourceRequest input = require(request, "查询参数不能为空");
        OciUser user = requireUser(input.id());
        String nlbId = required(input.networkLoadBalancerId(), "负载均衡器 OCID 不能为空");
        String vcnId = required(input.vcnId(), "VCN 不能为空");
        String backendSetName = required(input.backendSetName(), "Backend Set 名称不能为空");
        return readCacheService.get(cacheKey(user, input.region(), "backend-set-health", vcnId, nlbId, backendSetName),
                HEALTH_CACHE_TTL, input.forceEnabled(), () -> withClient(user, input.region(), "查询 Backend Set 健康状态", client -> {
                    requireNlbInVcn(client, nlbId, vcnId);
                    return NlbSdkMapper.backendSetHealth(client.getNetworkLoadBalancerClient()
                            .getBackendSetHealth(GetBackendSetHealthRequest.builder()
                                    .networkLoadBalancerId(nlbId).backendSetName(backendSetName).build())
                            .getBackendSetHealth());
                }));
    }

    public Map<String, Object> backendHealth(NlbRequests.BackendResourceRequest request) {
        NlbRequests.BackendResourceRequest input = require(request, "查询参数不能为空");
        OciUser user = requireUser(input.id());
        String nlbId = required(input.networkLoadBalancerId(), "负载均衡器 OCID 不能为空");
        String vcnId = required(input.vcnId(), "VCN 不能为空");
        String backendSetName = required(input.backendSetName(), "Backend Set 名称不能为空");
        String backendName = required(input.backendName(), "Backend 名称不能为空");
        return readCacheService.get(cacheKey(user, input.region(), "backend-health", vcnId, nlbId, backendSetName, backendName),
                HEALTH_CACHE_TTL, input.forceEnabled(), () -> withClient(user, input.region(), "查询 Backend 健康状态", client -> {
                    requireNlbInVcn(client, nlbId, vcnId);
                    return fetchBackendHealth(client.getNetworkLoadBalancerClient(), nlbId, backendSetName, backendName);
                }));
    }

    public Map<String, Object> create(NlbRequests.CreateNetworkLoadBalancerRequest request) {
        NlbRequests.CreateNetworkLoadBalancerRequest input = require(request, "创建参数不能为空");
        OciUser user = requireUser(input.id());
        String vcnId = required(input.vcnId(), "VCN 不能为空");
        return withClient(user, input.region(), "创建负载均衡器", client -> {
            Vcn vcn = getVcn(client.getVirtualNetworkClient(), vcnId);
            Subnet subnet = getSubnet(client.getVirtualNetworkClient(), required(input.subnetId(), "子网不能为空"));
            requireSubnetInVcn(subnet, vcnId);
            String compartmentId = notBlank(input.compartmentId()) ? input.compartmentId().trim() : vcn.getCompartmentId();
            var response = client.getNetworkLoadBalancerClient().createNetworkLoadBalancer(
                    CreateNetworkLoadBalancerRequest.builder()
                            .createNetworkLoadBalancerDetails(
                                    NlbSdkDetailsFactory.createNetworkLoadBalancer(input, compartmentId))
                            .opcRetryToken(stableRetryToken("create-nlb", input.id(), input.region(),
                                    compartmentId, vcnId, input.subnetId(), input.displayName()))
                            .build());
            evict(user, input.region());
            Map<String, Object> result = mutationResult("CREATE_NETWORK_LOAD_BALANCER",
                    response.getOpcWorkRequestId(), response.getOpcRequestId(),
                    response.getNetworkLoadBalancer() == null ? null : response.getNetworkLoadBalancer().getId(),
                    response.getEtag());
            if (response.getNetworkLoadBalancer() != null) {
                result.put("resource", NlbSdkMapper.networkLoadBalancer(
                        response.getNetworkLoadBalancer(), response.getEtag(), null));
            }
            return result;
        });
    }

    public Map<String, Object> update(NlbRequests.UpdateNetworkLoadBalancerRequest request) {
        NlbRequests.UpdateNetworkLoadBalancerRequest input = require(request, "更新参数不能为空");
        OciUser user = requireUser(input.id());
        return withExistingNlbContext(user, input.region(), input.vcnId(), input.networkLoadBalancerId(),
                "更新负载均衡器", (client, context) -> {
                    validateNlbUpdateCompatibility(context, input);
                    var response = client.getNetworkLoadBalancerClient().updateNetworkLoadBalancer(
                            UpdateNetworkLoadBalancerRequest.builder()
                                    .networkLoadBalancerId(input.networkLoadBalancerId())
                                    .updateNetworkLoadBalancerDetails(
                                            NlbSdkDetailsFactory.updateNetworkLoadBalancer(input))
                                    .ifMatch(trimToNull(input.ifMatch()))
                                    .build());
                    evict(user, input.region());
                    return mutationResult("UPDATE_NETWORK_LOAD_BALANCER", response.getOpcWorkRequestId(),
                            response.getOpcRequestId(), input.networkLoadBalancerId(), null);
                });
    }

    public Map<String, Object> delete(NlbRequests.DeleteNetworkLoadBalancerRequest request) {
        NlbRequests.DeleteNetworkLoadBalancerRequest input = require(request, "删除参数不能为空");
        OciUser user = requireUser(input.id());
        return withExistingNlb(user, input.region(), input.vcnId(), input.networkLoadBalancerId(),
                "删除负载均衡器", client -> {
                    var response = client.getNetworkLoadBalancerClient().deleteNetworkLoadBalancer(
                            DeleteNetworkLoadBalancerRequest.builder()
                                    .networkLoadBalancerId(input.networkLoadBalancerId())
                                    .ifMatch(trimToNull(input.ifMatch()))
                                    .build());
                    evict(user, input.region());
                    return mutationResult("DELETE_NETWORK_LOAD_BALANCER", response.getOpcWorkRequestId(),
                            response.getOpcRequestId(), input.networkLoadBalancerId(), null);
                });
    }

    public Map<String, Object> updateNetworkSecurityGroups(NlbRequests.UpdateNetworkSecurityGroupsRequest request) {
        NlbRequests.UpdateNetworkSecurityGroupsRequest input = require(request, "更新参数不能为空");
        OciUser user = requireUser(input.id());
        return withExistingNlb(user, input.region(), input.vcnId(), input.networkLoadBalancerId(),
                "更新网络安全组", client -> {
                    List<String> nsgIds = input.networkSecurityGroupIds() == null ? List.of()
                            : input.networkSecurityGroupIds().stream().filter(NetworkLoadBalancerService::notBlank)
                            .map(String::trim).distinct().toList();
                    var response = client.getNetworkLoadBalancerClient().updateNetworkSecurityGroups(
                            UpdateNetworkSecurityGroupsRequest.builder()
                                    .networkLoadBalancerId(input.networkLoadBalancerId())
                                    .updateNetworkSecurityGroupsDetails(UpdateNetworkSecurityGroupsDetails.builder()
                                            .networkSecurityGroupIds(nsgIds).build())
                                    .opcRetryToken(stableRetryToken("update-nlb-nsg", input.networkLoadBalancerId(), nsgIds))
                                    .ifMatch(trimToNull(input.ifMatch()))
                                    .build());
                    evict(user, input.region());
                    return mutationResult("UPDATE_NETWORK_SECURITY_GROUPS", response.getOpcWorkRequestId(),
                            response.getOpcRequestId(), input.networkLoadBalancerId(), null);
                });
    }

    public Map<String, Object> changeCompartment(NlbRequests.ChangeCompartmentRequest request) {
        NlbRequests.ChangeCompartmentRequest input = require(request, "迁移参数不能为空");
        OciUser user = requireUser(input.id());
        String targetCompartmentId = required(input.targetCompartmentId(), "目标 Compartment 不能为空");
        return withExistingNlb(user, input.region(), input.vcnId(), input.networkLoadBalancerId(),
                "迁移负载均衡器 Compartment", client -> {
                    var response = client.getNetworkLoadBalancerClient().changeNetworkLoadBalancerCompartment(
                            ChangeNetworkLoadBalancerCompartmentRequest.builder()
                                    .networkLoadBalancerId(input.networkLoadBalancerId())
                                    .changeNetworkLoadBalancerCompartmentDetails(
                                            ChangeNetworkLoadBalancerCompartmentDetails.builder()
                                                    .compartmentId(targetCompartmentId).build())
                                    .opcRetryToken(stableRetryToken("change-nlb-compartment",
                                            input.networkLoadBalancerId(), targetCompartmentId))
                                    .ifMatch(trimToNull(input.ifMatch()))
                                    .build());
                    evict(user, input.region());
                    return mutationResult("CHANGE_NETWORK_LOAD_BALANCER_COMPARTMENT",
                            response.getOpcWorkRequestId(), response.getOpcRequestId(),
                            input.networkLoadBalancerId(), null);
                });
    }

    public Map<String, Object> createListener(NlbRequests.CreateListenerRequest request) {
        NlbRequests.CreateListenerRequest input = require(request, "Listener 创建参数不能为空");
        OciUser user = requireUser(input.id());
        return withExistingNlb(user, input.region(), input.vcnId(), input.networkLoadBalancerId(),
                "创建 Listener", client -> {
                    var response = client.getNetworkLoadBalancerClient().createListener(CreateListenerRequest.builder()
                            .networkLoadBalancerId(input.networkLoadBalancerId())
                            .createListenerDetails(NlbSdkDetailsFactory.createListener(input))
                            .opcRetryToken(stableRetryToken("create-listener", input.networkLoadBalancerId(),
                                    input.name(), input.port(), input.protocol()))
                            .ifMatch(trimToNull(input.ifMatch())).build());
                    evict(user, input.region());
                    return mutationResult("CREATE_LISTENER", response.getOpcWorkRequestId(),
                            response.getOpcRequestId(), input.name(), response.getETag());
                });
    }

    public Map<String, Object> updateListener(NlbRequests.UpdateListenerRequest request) {
        NlbRequests.UpdateListenerRequest input = require(request, "Listener 更新参数不能为空");
        OciUser user = requireUser(input.id());
        String listenerName = required(input.listenerName(), "Listener 名称不能为空");
        return withExistingNlb(user, input.region(), input.vcnId(), input.networkLoadBalancerId(),
                "更新 Listener", client -> {
                    var response = client.getNetworkLoadBalancerClient().updateListener(UpdateListenerRequest.builder()
                            .networkLoadBalancerId(input.networkLoadBalancerId()).listenerName(listenerName)
                            .updateListenerDetails(NlbSdkDetailsFactory.updateListener(input))
                            .opcRetryToken(stableRetryToken("update-listener", input.networkLoadBalancerId(),
                                    listenerName, input.defaultBackendSetName(), input.port(), input.protocol()))
                            .ifMatch(trimToNull(input.ifMatch())).build());
                    evict(user, input.region());
                    return mutationResult("UPDATE_LISTENER", response.getOpcWorkRequestId(),
                            response.getOpcRequestId(), listenerName, null);
                });
    }

    public Map<String, Object> deleteListener(NlbRequests.DeleteListenerRequest request) {
        NlbRequests.DeleteListenerRequest input = require(request, "Listener 删除参数不能为空");
        OciUser user = requireUser(input.id());
        String listenerName = required(input.listenerName(), "Listener 名称不能为空");
        return withExistingNlb(user, input.region(), input.vcnId(), input.networkLoadBalancerId(),
                "删除 Listener", client -> {
                    var response = client.getNetworkLoadBalancerClient().deleteListener(DeleteListenerRequest.builder()
                            .networkLoadBalancerId(input.networkLoadBalancerId()).listenerName(listenerName)
                            .ifMatch(trimToNull(input.ifMatch())).build());
                    evict(user, input.region());
                    return mutationResult("DELETE_LISTENER", response.getOpcWorkRequestId(),
                            response.getOpcRequestId(), listenerName, response.getETag());
                });
    }

    public Map<String, Object> createBackendSet(NlbRequests.CreateBackendSetRequest request) {
        NlbRequests.CreateBackendSetRequest input = require(request, "Backend Set 创建参数不能为空");
        OciUser user = requireUser(input.id());
        return withExistingNlbContext(user, input.region(), input.vcnId(), input.networkLoadBalancerId(),
                "创建 Backend Set", (client, context) -> {
                    validateBackendSetPreserveSource(context, input.isPreserveSource());
                    var response = client.getNetworkLoadBalancerClient().createBackendSet(CreateBackendSetRequest.builder()
                            .networkLoadBalancerId(input.networkLoadBalancerId())
                            .createBackendSetDetails(NlbSdkDetailsFactory.createBackendSet(input))
                            .opcRetryToken(stableRetryToken("create-backend-set", input.networkLoadBalancerId(),
                                    input.name(), input.policy(), input.ipVersion()))
                            .ifMatch(trimToNull(input.ifMatch())).build());
                    evict(user, input.region());
                    return mutationResult("CREATE_BACKEND_SET", response.getOpcWorkRequestId(),
                            response.getOpcRequestId(), input.name(), null);
                });
    }

    public Map<String, Object> updateBackendSet(NlbRequests.UpdateBackendSetRequest request) {
        NlbRequests.UpdateBackendSetRequest input = require(request, "Backend Set 更新参数不能为空");
        OciUser user = requireUser(input.id());
        String backendSetName = required(input.backendSetName(), "Backend Set 名称不能为空");
        return withExistingNlbContext(user, input.region(), input.vcnId(), input.networkLoadBalancerId(),
                "更新 Backend Set", (client, context) -> {
                    validateBackendSetPreserveSource(context, input.isPreserveSource());
                    var response = client.getNetworkLoadBalancerClient().updateBackendSet(UpdateBackendSetRequest.builder()
                            .networkLoadBalancerId(input.networkLoadBalancerId()).backendSetName(backendSetName)
                            .updateBackendSetDetails(NlbSdkDetailsFactory.updateBackendSet(input))
                            .opcRetryToken(stableRetryToken("update-backend-set", input.networkLoadBalancerId(),
                                    backendSetName, input.policy(), input.ipVersion()))
                            .ifMatch(trimToNull(input.ifMatch())).build());
                    evict(user, input.region());
                    return mutationResult("UPDATE_BACKEND_SET", response.getOpcWorkRequestId(),
                            response.getOpcRequestId(), backendSetName, null);
                });
    }

    public Map<String, Object> deleteBackendSet(NlbRequests.DeleteBackendSetRequest request) {
        NlbRequests.DeleteBackendSetRequest input = require(request, "Backend Set 删除参数不能为空");
        OciUser user = requireUser(input.id());
        String backendSetName = required(input.backendSetName(), "Backend Set 名称不能为空");
        return withExistingNlb(user, input.region(), input.vcnId(), input.networkLoadBalancerId(),
                "删除 Backend Set", client -> {
                    var response = client.getNetworkLoadBalancerClient().deleteBackendSet(DeleteBackendSetRequest.builder()
                            .networkLoadBalancerId(input.networkLoadBalancerId()).backendSetName(backendSetName)
                            .ifMatch(trimToNull(input.ifMatch())).build());
                    evict(user, input.region());
                    return mutationResult("DELETE_BACKEND_SET", response.getOpcWorkRequestId(),
                            response.getOpcRequestId(), backendSetName, null);
                });
    }

    public Map<String, Object> updateHealthChecker(NlbRequests.UpdateHealthCheckerRequest request) {
        NlbRequests.UpdateHealthCheckerRequest input = require(request, "健康检查器更新参数不能为空");
        OciUser user = requireUser(input.id());
        String backendSetName = required(input.backendSetName(), "Backend Set 名称不能为空");
        return withExistingNlb(user, input.region(), input.vcnId(), input.networkLoadBalancerId(),
                "更新健康检查器", client -> {
                    var response = client.getNetworkLoadBalancerClient().updateHealthChecker(
                            UpdateHealthCheckerRequest.builder()
                                    .networkLoadBalancerId(input.networkLoadBalancerId())
                                    .backendSetName(backendSetName)
                                    .updateHealthCheckerDetails(
                                            NlbSdkDetailsFactory.updateHealthChecker(input.healthChecker()))
                                    .opcRetryToken(stableRetryToken("update-health-checker",
                                            input.networkLoadBalancerId(), backendSetName, input.healthChecker()))
                                    .ifMatch(trimToNull(input.ifMatch())).build());
                    evict(user, input.region());
                    return mutationResult("UPDATE_HEALTH_CHECKER", response.getOpcWorkRequestId(),
                            response.getOpcRequestId(), backendSetName, null);
                });
    }

    public Map<String, Object> createBackend(NlbRequests.CreateBackendRequest request) {
        NlbRequests.CreateBackendRequest input = require(request, "Backend 创建参数不能为空");
        OciUser user = requireUser(input.id());
        String backendSetName = required(input.backendSetName(), "Backend Set 名称不能为空");
        return withExistingNlb(user, input.region(), input.vcnId(), input.networkLoadBalancerId(),
                "创建 Backend", client -> {
                    var response = client.getNetworkLoadBalancerClient().createBackend(CreateBackendRequest.builder()
                            .networkLoadBalancerId(input.networkLoadBalancerId()).backendSetName(backendSetName)
                            .createBackendDetails(NlbSdkDetailsFactory.createBackend(input.backend()))
                            .opcRetryToken(stableRetryToken("create-backend", input.networkLoadBalancerId(),
                                    backendSetName, input.backend()))
                            .ifMatch(trimToNull(input.ifMatch())).build());
                    evict(user, input.region());
                    String resourceName = input.backend() == null ? null : input.backend().name();
                    return mutationResult("CREATE_BACKEND", response.getOpcWorkRequestId(),
                            response.getOpcRequestId(), resourceName, null);
                });
    }

    public Map<String, Object> updateBackend(NlbRequests.UpdateBackendRequest request) {
        NlbRequests.UpdateBackendRequest input = require(request, "Backend 更新参数不能为空");
        OciUser user = requireUser(input.id());
        String backendSetName = required(input.backendSetName(), "Backend Set 名称不能为空");
        String backendName = required(input.backendName(), "Backend 名称不能为空");
        return withExistingNlb(user, input.region(), input.vcnId(), input.networkLoadBalancerId(),
                "更新 Backend", client -> {
                    var response = client.getNetworkLoadBalancerClient().updateBackend(UpdateBackendRequest.builder()
                            .networkLoadBalancerId(input.networkLoadBalancerId()).backendSetName(backendSetName)
                            .backendName(backendName).updateBackendDetails(NlbSdkDetailsFactory.updateBackend(input))
                            .opcRetryToken(stableRetryToken("update-backend", input.networkLoadBalancerId(),
                                    backendSetName, backendName, input.weight(), input.isDrain(),
                                    input.isBackup(), input.isOffline()))
                            .ifMatch(trimToNull(input.ifMatch())).build());
                    evict(user, input.region());
                    return mutationResult("UPDATE_BACKEND", response.getOpcWorkRequestId(),
                            response.getOpcRequestId(), backendName, null);
                });
    }

    public Map<String, Object> deleteBackend(NlbRequests.DeleteBackendRequest request) {
        NlbRequests.DeleteBackendRequest input = require(request, "Backend 删除参数不能为空");
        OciUser user = requireUser(input.id());
        String backendSetName = required(input.backendSetName(), "Backend Set 名称不能为空");
        String backendName = required(input.backendName(), "Backend 名称不能为空");
        return withExistingNlb(user, input.region(), input.vcnId(), input.networkLoadBalancerId(),
                "删除 Backend", client -> {
                    var response = client.getNetworkLoadBalancerClient().deleteBackend(DeleteBackendRequest.builder()
                            .networkLoadBalancerId(input.networkLoadBalancerId()).backendSetName(backendSetName)
                            .backendName(backendName).ifMatch(trimToNull(input.ifMatch())).build());
                    evict(user, input.region());
                    return mutationResult("DELETE_BACKEND", response.getOpcWorkRequestId(),
                            response.getOpcRequestId(), backendName, null);
                });
    }

    public Map<String, Object> workRequest(NlbRequests.WorkRequestResourceRequest request) {
        NlbRequests.WorkRequestResourceRequest input = require(request, "Work Request 查询参数不能为空");
        OciUser user = requireUser(input.id());
        String workRequestId = required(input.workRequestId(), "Work Request OCID 不能为空");
        return withClient(user, input.region(), "查询 Work Request", client ->
                fetchWorkRequest(client.getNetworkLoadBalancerClient(), workRequestId));
    }

    public List<Map<String, Object>> workRequestErrors(NlbRequests.WorkRequestResourceRequest request) {
        NlbRequests.WorkRequestResourceRequest input = require(request, "Work Request 查询参数不能为空");
        OciUser user = requireUser(input.id());
        String compartmentId = required(input.compartmentId(), "Compartment 不能为空");
        String workRequestId = required(input.workRequestId(), "Work Request OCID 不能为空");
        return withClient(user, input.region(), "查询 Work Request 错误", client ->
                fetchWorkRequestErrors(client.getNetworkLoadBalancerClient(), workRequestId, compartmentId));
    }

    public List<Map<String, Object>> workRequestLogs(NlbRequests.WorkRequestResourceRequest request) {
        NlbRequests.WorkRequestResourceRequest input = require(request, "Work Request 查询参数不能为空");
        OciUser user = requireUser(input.id());
        String compartmentId = required(input.compartmentId(), "Compartment 不能为空");
        String workRequestId = required(input.workRequestId(), "Work Request OCID 不能为空");
        return withClient(user, input.region(), "查询 Work Request 日志", client ->
                fetchWorkRequestLogs(client.getNetworkLoadBalancerClient(), workRequestId, compartmentId));
    }

    public Map<String, Object> waitWorkRequest(NlbRequests.WaitWorkRequestRequest request) {
        NlbRequests.WaitWorkRequestRequest input = require(request, "Work Request 等待参数不能为空");
        OciUser user = requireUser(input.id());
        String compartmentId = required(input.compartmentId(), "Compartment 不能为空");
        String workRequestId = required(input.workRequestId(), "Work Request OCID 不能为空");
        int timeoutSeconds = clamp(input.timeoutSeconds(), DEFAULT_WAIT_SECONDS, 1, MAX_WAIT_SECONDS);
        int pollMillis = clamp(input.pollIntervalMillis(), DEFAULT_POLL_MILLIS, 500, 5000);
        return withClient(user, input.region(), "等待 Work Request", client -> {
            long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
            Map<String, Object> current;
            do {
                current = fetchWorkRequest(client.getNetworkLoadBalancerClient(), workRequestId);
                if (Boolean.TRUE.equals(current.get("terminal"))) {
                    current.put("timedOut", false);
                    String diagnosticsCompartmentId = notBlank((String) current.get("compartmentId"))
                            ? ((String) current.get("compartmentId")).trim()
                            : compartmentId;
                    if (!Boolean.TRUE.equals(current.get("successful"))) {
                        current.put("errors", fetchWorkRequestErrors(
                                client.getNetworkLoadBalancerClient(), workRequestId, diagnosticsCompartmentId));
                    }
                    current.put("logs", fetchWorkRequestLogs(
                            client.getNetworkLoadBalancerClient(), workRequestId, diagnosticsCompartmentId));
                    return current;
                }
                if (System.nanoTime() >= deadline) break;
                try {
                    Thread.sleep(pollMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new OciException("等待 Work Request 已取消");
                }
            } while (true);
            current.put("timedOut", true);
            return current;
        });
    }

    protected OciClientService openClient(OciUser user, String region) {
        String selectedRegion = notBlank(region) ? region.trim() : null;
        return new OciClientService(toSysUser(user), selectedRegion);
    }

    static String stableRetryToken(String operation, Object... parts) {
        StringBuilder seed = new StringBuilder(Objects.toString(operation, "nlb"));
        if (parts != null) {
            for (Object part : parts) seed.append('\u001f').append(Objects.toString(part, ""));
        }
        return UUID.nameUUIDFromBytes(seed.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private <T> T withExistingNlb(OciUser user, String region, String vcnId, String nlbId,
                                  String action, Function<OciClientService, T> operation) {
        return withExistingNlbContext(user, region, vcnId, nlbId, action,
                (client, context) -> operation.apply(client));
    }

    private <T> T withExistingNlbContext(
            OciUser user, String region, String vcnId, String nlbId, String action,
            BiFunction<OciClientService, NlbContext, T> operation) {
        String resolvedVcnId = required(vcnId, "VCN 不能为空");
        String resolvedNlbId = required(nlbId, "负载均衡器 OCID 不能为空");
        return withClient(user, region, action, client -> {
            NlbContext context = requireNlbInVcn(client, resolvedNlbId, resolvedVcnId);
            return operation.apply(client, context);
        });
    }

    private static void validateNlbUpdateCompatibility(
            NlbContext context, NlbRequests.UpdateNetworkLoadBalancerRequest input) {
        var current = context.response().getNetworkLoadBalancer();
        boolean preserveSourceDestination = input.isPreserveSourceDestination() != null
                ? input.isPreserveSourceDestination()
                : Boolean.TRUE.equals(current.getIsPreserveSourceDestination());
        boolean symmetricHashEnabled = input.isSymmetricHashEnabled() != null
                ? input.isSymmetricHashEnabled()
                : Boolean.TRUE.equals(current.getIsSymmetricHashEnabled());
        if (symmetricHashEnabled && !preserveSourceDestination) {
            throw new OciException("启用对称哈希前必须先启用保留源/目标地址");
        }
    }

    private static void validateBackendSetPreserveSource(NlbContext context, Boolean preserveSource) {
        var current = context.response().getNetworkLoadBalancer();
        if (Boolean.TRUE.equals(current.getIsPreserveSourceDestination())
                && Boolean.FALSE.equals(preserveSource)) {
            throw new OciException("当前 NLB 已启用保留源/目标地址，Backend Set 不能关闭保留源 IP");
        }
    }

    private <T> T withClient(OciUser user, String region, String action, Function<OciClientService, T> operation) {
        try (OciClientService client = openClient(user, region)) {
            return operation.apply(client);
        } catch (OciException | BmcException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(action + "失败: " + Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private NlbContext requireNlbInVcn(OciClientService client, String nlbId, String vcnId) {
        GetNetworkLoadBalancerResponse response = client.getNetworkLoadBalancerClient().getNetworkLoadBalancer(
                GetNetworkLoadBalancerRequest.builder().networkLoadBalancerId(nlbId).build());
        if (response.getNetworkLoadBalancer() == null) throw new OciException("负载均衡器不存在");
        Subnet subnet = getSubnet(client.getVirtualNetworkClient(), response.getNetworkLoadBalancer().getSubnetId());
        requireSubnetInVcn(subnet, vcnId);
        return new NlbContext(response, subnet);
    }

    private static Vcn getVcn(VirtualNetworkClient client, String vcnId) {
        Vcn vcn = client.getVcn(GetVcnRequest.builder().vcnId(required(vcnId, "VCN 不能为空")).build()).getVcn();
        if (vcn == null) throw new OciException("VCN 不存在");
        return vcn;
    }

    private static Subnet getSubnet(VirtualNetworkClient client, String subnetId) {
        Subnet subnet = client.getSubnet(GetSubnetRequest.builder()
                .subnetId(required(subnetId, "子网不能为空")).build()).getSubnet();
        if (subnet == null) throw new OciException("子网不存在");
        return subnet;
    }

    private static void requireSubnetInVcn(Subnet subnet, String vcnId) {
        if (subnet == null || !Objects.equals(required(vcnId, "VCN 不能为空"), subnet.getVcnId())) {
            throw new OciException("所选资源不属于当前 VCN");
        }
    }

    private static Map<String, Subnet> listVcnSubnets(VirtualNetworkClient client, String compartmentId, String vcnId) {
        Map<String, Subnet> result = new LinkedHashMap<>();
        String page = null;
        do {
            var response = client.listSubnets(ListSubnetsRequest.builder()
                    .compartmentId(compartmentId).vcnId(vcnId).limit(PAGE_LIMIT).page(page).build());
            if (response.getItems() != null) {
                for (Subnet subnet : response.getItems()) {
                    if (subnet.getLifecycleState() == Subnet.LifecycleState.Terminated) continue;
                    result.put(subnet.getId(), subnet);
                }
            }
            page = response.getOpcNextPage();
        } while (notBlank(page));
        return result;
    }

    private static Subnet subnetInVcn(VirtualNetworkClient client, Map<String, Subnet> known,
                                      String subnetId, String vcnId) {
        if (!notBlank(subnetId)) return null;
        Subnet subnet = known.get(subnetId);
        if (subnet != null) return Objects.equals(vcnId, subnet.getVcnId()) ? subnet : null;
        try {
            subnet = getSubnet(client, subnetId);
            if (Objects.equals(vcnId, subnet.getVcnId())) {
                known.put(subnetId, subnet);
                return subnet;
            }
        } catch (BmcException e) {
            return null;
        }
        return null;
    }

    private static List<Map<String, Object>> listNetworkSecurityGroups(
            VirtualNetworkClient client, String compartmentId, String vcnId) {
        List<Map<String, Object>> result = new ArrayList<>();
        String page = null;
        do {
            var response = client.listNetworkSecurityGroups(ListNetworkSecurityGroupsRequest.builder()
                    .compartmentId(compartmentId).vcnId(vcnId).limit(PAGE_LIMIT).page(page).build());
            if (response.getItems() != null) {
                for (NetworkSecurityGroup nsg : response.getItems()) {
                    if (nsg.getLifecycleState() == NetworkSecurityGroup.LifecycleState.Terminated) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", nsg.getId());
                    row.put("displayName", nsg.getDisplayName());
                    row.put("compartmentId", nsg.getCompartmentId());
                    row.put("lifecycleState", nsg.getLifecycleState() == null ? null : nsg.getLifecycleState().getValue());
                    result.add(row);
                }
            }
            page = response.getOpcNextPage();
        } while (notBlank(page));
        return result;
    }

    private static List<String> listNlbSearchCompartments(
            IdentityClient identityClient, OciUser user, String preferredCompartmentId) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (notBlank(preferredCompartmentId)) result.add(preferredCompartmentId.trim());
        String tenancyId = user == null ? null : user.getOciTenantId();
        if (notBlank(tenancyId)) result.add(tenancyId.trim());
        if (identityClient == null || !notBlank(tenancyId)) return List.copyOf(result);
        try {
            String page = null;
            do {
                var response = identityClient.listCompartments(ListCompartmentsRequest.builder()
                        .compartmentId(tenancyId.trim())
                        .accessLevel(ListCompartmentsRequest.AccessLevel.Accessible)
                        .compartmentIdInSubtree(true)
                        .lifecycleState(Compartment.LifecycleState.Active)
                        .limit(PAGE_LIMIT)
                        .page(page)
                        .build());
                if (response.getItems() != null) {
                    response.getItems().stream().map(Compartment::getId)
                            .filter(NetworkLoadBalancerService::notBlank)
                            .map(String::trim).forEach(result::add);
                }
                page = response.getOpcNextPage();
            } while (notBlank(page));
        } catch (BmcException ignored) {
            // The preferred compartment remains usable even when tenancy-wide inspection is unavailable.
        }
        return List.copyOf(result);
    }

    private static List<String> listPolicies(NetworkLoadBalancerClient client) {
        Set<String> result = new LinkedHashSet<>();
        String page = null;
        do {
            var response = client.listNetworkLoadBalancersPolicies(ListNetworkLoadBalancersPoliciesRequest.builder()
                    .limit(PAGE_LIMIT).page(page).build());
            if (response.getNetworkLoadBalancersPolicyCollection() != null
                    && response.getNetworkLoadBalancersPolicyCollection().getItems() != null) {
                response.getNetworkLoadBalancersPolicyCollection().getItems().forEach(item -> result.add(item.getValue()));
            }
            page = response.getOpcNextPage();
        } while (notBlank(page));
        return List.copyOf(result);
    }

    private static List<String> listProtocols(NetworkLoadBalancerClient client) {
        Set<String> result = new LinkedHashSet<>();
        String page = null;
        do {
            var response = client.listNetworkLoadBalancersProtocols(ListNetworkLoadBalancersProtocolsRequest.builder()
                    .limit(PAGE_LIMIT).page(page).build());
            if (response.getNetworkLoadBalancersProtocolCollection() != null
                    && response.getNetworkLoadBalancersProtocolCollection().getItems() != null) {
                response.getNetworkLoadBalancersProtocolCollection().getItems().forEach(item -> result.add(item.getValue()));
            }
            page = response.getOpcNextPage();
        } while (notBlank(page));
        return List.copyOf(result);
    }

    private static List<String> enumValues(Object[] values) {
        return Arrays.stream(values)
                .map(value -> {
                    try {
                        return String.valueOf(value.getClass().getMethod("getValue").invoke(value));
                    } catch (Exception e) {
                        return String.valueOf(value);
                    }
                })
                .filter(NetworkLoadBalancerService::notBlank)
                .filter(value -> !"unknownenumvalue".equalsIgnoreCase(
                        value.replaceAll("[^A-Za-z0-9]", "")))
                .distinct().toList();
    }

    private static String safeNetworkLoadBalancerHealthStatus(NetworkLoadBalancerClient client, String nlbId) {
        try {
            var health = client.getNetworkLoadBalancerHealth(GetNetworkLoadBalancerHealthRequest.builder()
                    .networkLoadBalancerId(nlbId).build()).getNetworkLoadBalancerHealth();
            return health == null || health.getStatus() == null ? "UNKNOWN" : health.getStatus().getValue();
        } catch (BmcException e) {
            return "UNKNOWN";
        }
    }

    private static Map<String, Object> safeBackendSetHealth(
            NetworkLoadBalancerClient client, String nlbId, String backendSetName) {
        try {
            return NlbSdkMapper.backendSetHealth(client.getBackendSetHealth(GetBackendSetHealthRequest.builder()
                    .networkLoadBalancerId(nlbId).backendSetName(backendSetName).build()).getBackendSetHealth());
        } catch (BmcException e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "UNKNOWN");
            result.put("error", healthError(e));
            return result;
        }
    }

    private static Map<String, Object> safeBackendHealth(
            NetworkLoadBalancerClient client, String nlbId, String backendSetName, String backendName) {
        try {
            return fetchBackendHealth(client, nlbId, backendSetName, backendName);
        } catch (BmcException e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "UNKNOWN");
            result.put("operationalStatus", "UNKNOWN");
            result.put("error", healthError(e));
            return result;
        }
    }

    private static Map<String, Object> fetchBackendHealth(
            NetworkLoadBalancerClient client, String nlbId, String backendSetName, String backendName) {
        var health = client.getBackendHealth(GetBackendHealthRequest.builder()
                .networkLoadBalancerId(nlbId).backendSetName(backendSetName).backendName(backendName).build())
                .getBackendHealth();
        var operational = client.getBackendOperationalStatus(GetBackendOperationalStatusRequest.builder()
                .networkLoadBalancerId(nlbId).backendSetName(backendSetName).backendName(backendName).build())
                .getBackendOperationalStatus();
        return NlbSdkMapper.backendHealth(health, operational);
    }

    private static Map<String, Object> fetchWorkRequest(NetworkLoadBalancerClient client, String workRequestId) {
        GetWorkRequestResponse response = client.getWorkRequest(GetWorkRequestRequest.builder()
                .workRequestId(workRequestId).build());
        Map<String, Object> result = NlbSdkMapper.workRequest(response.getWorkRequest());
        result.put("retryAfterSeconds", response.getRetryAfter());
        return result;
    }

    private static List<Map<String, Object>> fetchWorkRequestErrors(
            NetworkLoadBalancerClient client, String workRequestId, String compartmentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        String page = null;
        do {
            var response = client.listWorkRequestErrors(ListWorkRequestErrorsRequest.builder()
                    .workRequestId(workRequestId).compartmentId(compartmentId)
                    .limit(PAGE_LIMIT).page(page).build());
            if (response.getWorkRequestErrorCollection() != null
                    && response.getWorkRequestErrorCollection().getItems() != null) {
                response.getWorkRequestErrorCollection().getItems()
                        .forEach(item -> result.add(NlbSdkMapper.workRequestError(item)));
            }
            page = response.getOpcNextPage();
        } while (notBlank(page));
        return result;
    }

    private static List<Map<String, Object>> fetchWorkRequestLogs(
            NetworkLoadBalancerClient client, String workRequestId, String compartmentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        String page = null;
        do {
            var response = client.listWorkRequestLogs(ListWorkRequestLogsRequest.builder()
                    .workRequestId(workRequestId).compartmentId(compartmentId)
                    .limit(PAGE_LIMIT).page(page).build());
            if (response.getWorkRequestLogEntryCollection() != null
                    && response.getWorkRequestLogEntryCollection().getItems() != null) {
                response.getWorkRequestLogEntryCollection().getItems()
                        .forEach(item -> result.add(NlbSdkMapper.workRequestLog(item)));
            }
            page = response.getOpcNextPage();
        } while (notBlank(page));
        return result;
    }

    private OciUser requireUser(String id) {
        String userId = required(id, "租户配置 ID 不能为空");
        OciUser user = userMapper.selectById(userId);
        if (user == null) throw new OciException("租户配置不存在");
        return user;
    }

    private void evict(OciUser user, String region) {
        readCacheService.evictByPrefix(cachePrefix(user, region) + "|");
    }

    private static String cacheKey(OciUser user, String region, Object... parts) {
        return OciReadCacheService.key(cachePrefix(user, region), parts);
    }

    private static String cachePrefix(OciUser user, String region) {
        return OciReadCacheService.key("oci:nlb", user.getId(), user.getOciTenantId(), effectiveRegion(user, region));
    }

    private static String effectiveRegion(OciUser user, String region) {
        if (notBlank(region)) return region.trim();
        return user == null || user.getOciRegion() == null ? "" : user.getOciRegion().trim();
    }

    private static SysUserDTO toSysUser(OciUser user) {
        return SysUserDTO.builder()
                .username(user.getUsername())
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(user.getOciTenantId())
                        .userId(user.getOciUserId())
                        .fingerprint(user.getOciFingerprint())
                        .region(user.getOciRegion())
                        .privateKeyPath(user.getOciKeyPath())
                        .build())
                .build();
    }

    private static Map<String, Object> subnetMap(Subnet subnet) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (subnet == null) return result;
        result.put("id", subnet.getId());
        result.put("displayName", subnet.getDisplayName());
        result.put("vcnId", subnet.getVcnId());
        result.put("compartmentId", subnet.getCompartmentId());
        result.put("cidrBlock", subnet.getCidrBlock());
        result.put("ipv6CidrBlock", subnet.getIpv6CidrBlock());
        result.put("prohibitPublicIpOnVnic", subnet.getProhibitPublicIpOnVnic());
        result.put("lifecycleState", subnet.getLifecycleState() == null ? null : subnet.getLifecycleState().getValue());
        return result;
    }

    private static Map<String, Object> mutationResult(
            String operation, String workRequestId, String opcRequestId, String resourceId, String etag) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("submitted", true);
        result.put("operation", operation);
        result.put("workRequestId", workRequestId);
        result.put("opcRequestId", opcRequestId);
        result.put("resourceId", resourceId);
        result.put("etag", etag);
        result.put("finalStateKnown", false);
        return result;
    }

    private static String healthError(BmcException e) {
        return "健康状态暂不可用（HTTP " + e.getStatusCode() + "）";
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        int resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(max, resolved));
    }

    private static <T> T require(T value, String message) {
        if (value == null) throw new OciException(message);
        return value;
    }

    private static String required(String value, String message) {
        String result = trimToNull(value);
        if (result == null) throw new OciException(message);
        return result;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record NlbContext(GetNetworkLoadBalancerResponse response, Subnet subnet) {
    }
}
