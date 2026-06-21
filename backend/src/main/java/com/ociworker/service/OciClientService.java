package com.ociworker.service;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.core.BlockstorageClient;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.ComputeWaiters;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.*;
import com.oracle.bmc.core.requests.*;
import com.oracle.bmc.core.responses.*;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.AvailabilityDomain;
import com.oracle.bmc.identity.model.Compartment;
import com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest;
import com.oracle.bmc.identity.requests.ListCompartmentsRequest;
import com.oracle.bmc.monitoring.MonitoringClient;
import com.oracle.bmc.networkloadbalancer.NetworkLoadBalancerClient;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.workrequests.WorkRequestClient;
import com.ociworker.enums.ArchitectureEnum;
import com.ociworker.exception.OciException;
import com.ociworker.model.dto.InstanceDetailDTO;
import com.ociworker.model.dto.OciProxySnapshot;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.util.BootVolumeVpusUtil;
import com.ociworker.util.CommonUtils;
import com.ociworker.util.OciRegionCatalog;
import com.ociworker.util.ShapeSeriesUtil;
import com.ociworker.util.VcnIpv6Util;
import com.ociworker.util.socks.OciSocksApacheConnectionManager;
import com.oracle.bmc.http.ClientConfigurator;
import com.oracle.bmc.http.client.ProxyConfiguration;
import com.oracle.bmc.http.client.StandardClientProperties;
import com.oracle.bmc.http.client.jersey3.ApacheClientProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.apache.http.conn.HttpClientConnectionManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Data
public class OciClientService implements Closeable {

    private final ComputeClient computeClient;
    private final IdentityClient identityClient;
    private final WorkRequestClient workRequestClient;
    private final VirtualNetworkClient virtualNetworkClient;
    private final BlockstorageClient blockstorageClient;
    private final ObjectStorageClient objectStorageClient;
    private final MonitoringClient monitoringClient;
    private final NetworkLoadBalancerClient networkLoadBalancerClient;
    private final SimpleAuthenticationDetailsProvider provider;
    private final ClientConfiguration clientConfiguration;
    private final ClientConfigurator ociClientConfigurator;
    private SysUserDTO user;
    private String compartmentId;
    /** SOCKS 时共享的 Apache 连接池；HTTP 代理时为 null */
    private final HttpClientConnectionManager ociSocksPoolingManager;

    private static final String CIDR_BLOCK = "10.0.0.0/16";
    private static final String SUBNET_CIDR = "10.0.0.0/24";

    @Override
    public void close() {
        computeClient.close();
        identityClient.close();
        workRequestClient.close();
        virtualNetworkClient.close();
        blockstorageClient.close();
        objectStorageClient.close();
        monitoringClient.close();
        networkLoadBalancerClient.close();
        if (ociSocksPoolingManager != null) {
            try {
                ociSocksPoolingManager.shutdown();
            } catch (Exception ignored) {
            }
        }
    }

    public OciClientService(SysUserDTO user) {
        this(user, user.getOciCfg() != null ? user.getOciCfg().getRegion() : null);
    }

    /**
     * 使用指定 Region 的端点创建客户端（用于「存储」等跨区查看）。
     */
    public OciClientService(SysUserDTO user, String regionId) {
        this.user = user;
        SysUserDTO.OciCfg ociCfg = user.getOciCfg();
        Region region = resolveRegion(StrUtil.isNotBlank(regionId) ? regionId : ociCfg.getRegion());
        SimpleAuthenticationDetailsProvider provider = SimpleAuthenticationDetailsProvider.builder()
                .tenantId(ociCfg.getTenantId())
                .userId(ociCfg.getUserId())
                .fingerprint(ociCfg.getFingerprint())
                .privateKeySupplier(() -> {
                    try (FileInputStream fis = new FileInputStream(ociCfg.getPrivateKeyPath());
                         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        return new ByteArrayInputStream(baos.toByteArray());
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to read private key");
                    }
                })
                .region(region)
                .build();

        ClientConfiguration clientConfig = ClientConfiguration.builder()
                .connectionTimeoutMillis(10_000)
                .readTimeoutMillis(30_000)
                .build();
        this.clientConfiguration = clientConfig;
        OciProxyConfigService ps = OciProxyConfigService.instance();
        OciProxySnapshot snap = ps == null ? null : ps.snapshot();
        if (ps == null || !ps.ociUsesExplicitClientProxy()) {
            OciProxyConfigService.clearInProcessHttpSocksProxySystemProperties();
        }
        final org.apache.http.impl.conn.PoolingHttpClientConnectionManager socksPool;
        if (snap != null && snap.usesSocksForOci()) {
            socksPool = OciSocksApacheConnectionManager.create(snap);
        } else {
            socksPool = null;
        }
        this.ociSocksPoolingManager = socksPool;

        Optional<ProxyConfiguration> ocx = ps == null
                ? Optional.empty()
                : ps.getOciProxyConfiguration();

        final ClientConfigurator ociApacheCfg;
        if (socksPool != null) {
            ociApacheCfg = b -> {
                b.property(ApacheClientProperties.CONNECTION_MANAGER, socksPool);
                b.property(ApacheClientProperties.CONNECTION_MANAGER_SHARED, Boolean.TRUE);
            };
        } else if (ocx.isPresent()) {
            ProxyConfiguration pc = ocx.get();
            ociApacheCfg = c -> c.property(StandardClientProperties.PROXY, pc);
        } else {
            ociApacheCfg = OciProxyConfigService.ociSdkJerseyDirectConfigurator();
        }
        this.ociClientConfigurator = ociApacheCfg;

        if (snap != null && snap.usesSocksForOci()) {
            log.debug("OCI 客户端经应用内 SOCKS5 代理出站: {}:{}", snap.host(), snap.port());
        }

        var idb = IdentityClient.builder().configuration(clientConfig);
        idb.additionalClientConfigurator(ociApacheCfg);
        identityClient = idb.build(provider);

        var c1 = ComputeClient.builder().configuration(clientConfig);
        c1.additionalClientConfigurator(ociApacheCfg);
        computeClient = c1.build(provider);

        var c2 = BlockstorageClient.builder().configuration(clientConfig);
        c2.additionalClientConfigurator(ociApacheCfg);
        blockstorageClient = c2.build(provider);

        var c3 = ObjectStorageClient.builder().configuration(clientConfig);
        c3.additionalClientConfigurator(ociApacheCfg);
        objectStorageClient = c3.build(provider);

        var c4 = WorkRequestClient.builder().configuration(clientConfig);
        c4.additionalClientConfigurator(ociApacheCfg);
        workRequestClient = c4.build(provider);

        var c5 = VirtualNetworkClient.builder().configuration(clientConfig);
        c5.additionalClientConfigurator(ociApacheCfg);
        virtualNetworkClient = c5.build(provider);

        var c6 = MonitoringClient.builder().configuration(clientConfig);
        c6.additionalClientConfigurator(ociApacheCfg);
        monitoringClient = c6.build(provider);

        var c7 = NetworkLoadBalancerClient.builder().configuration(clientConfig);
        c7.additionalClientConfigurator(ociApacheCfg);
        networkLoadBalancerClient = c7.build(provider);
        this.provider = provider;
        compartmentId = StrUtil.isBlank(ociCfg.getCompartmentId())
                ? findRootCompartment(identityClient, provider.getTenantId())
                : ociCfg.getCompartmentId();
    }

    private static Region resolveRegion(String regionId) {
        if (StrUtil.isBlank(regionId)) {
            throw new OciException("Region 不能为空");
        }
        return OciRegionCatalog.resolveRegion(regionId);
    }

    private String findRootCompartment(IdentityClient identityClient, String tenantId) {
        try {
            List<Compartment> compartments = identityClient.listCompartments(
                    ListCompartmentsRequest.builder()
                            .compartmentId(tenantId)
                            .accessLevel(ListCompartmentsRequest.AccessLevel.Accessible)
                            .build()
            ).getItems();
            if (CollectionUtil.isNotEmpty(compartments)) {
                return compartments.get(0).getCompartmentId() != null
                        ? compartments.get(0).getCompartmentId() : tenantId;
            }
        } catch (Exception e) {
            log.warn("Failed to find root compartment, using tenantId: {}", e.getMessage());
        }
        return tenantId;
    }

    public List<Compartment> listAllCompartments() {
        String tenantId = provider.getTenantId();
        List<Compartment> all = new ArrayList<>();
        // Root compartment (tenancy itself)
        try {
            var tenancy = identityClient.getTenancy(
                    com.oracle.bmc.identity.requests.GetTenancyRequest.builder()
                            .tenancyId(tenantId).build()).getTenancy();
            Compartment root = Compartment.builder()
                    .id(tenantId)
                    .name("root")
                    .compartmentId(tenantId)
                    .lifecycleState(Compartment.LifecycleState.Active)
                    .build();
            all.add(root);
        } catch (Exception e) {
            Compartment root = Compartment.builder()
                    .id(tenantId).name("root").compartmentId(tenantId)
                    .lifecycleState(Compartment.LifecycleState.Active).build();
            all.add(root);
        }
        try {
            all.addAll(identityClient.listCompartments(
                    ListCompartmentsRequest.builder()
                            .compartmentId(tenantId)
                            .accessLevel(ListCompartmentsRequest.AccessLevel.Accessible)
                            .compartmentIdInSubtree(true)
                            .lifecycleState(Compartment.LifecycleState.Active)
                            .build()
            ).getItems());
        } catch (Exception e) {
            log.warn("Failed to list compartments: {}", e.getMessage());
        }
        return all;
    }

    public List<Instance> listAllInstancesInCompartment(String cid) {
        List<Instance> all = new ArrayList<>();
        for (Instance.LifecycleState state : List.of(
                Instance.LifecycleState.Running, Instance.LifecycleState.Stopped,
                Instance.LifecycleState.Starting, Instance.LifecycleState.Stopping)) {
            try {
                all.addAll(computeClient.listInstances(
                        ListInstancesRequest.builder().compartmentId(cid).lifecycleState(state).build()
                ).getItems());
            } catch (Exception ignored) {}
        }
        return all;
    }

    public List<Vcn> listVcnInCompartment(String cid) {
        try {
            return virtualNetworkClient.listVcns(
                    ListVcnsRequest.builder().compartmentId(cid)
                            .lifecycleState(Vcn.LifecycleState.Available).build()
            ).getItems();
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<AvailabilityDomain> getAvailabilityDomains() {
        return identityClient.listAvailabilityDomains(
                ListAvailabilityDomainsRequest.builder()
                        .compartmentId(compartmentId)
                        .build()
        ).getItems();
    }

    public List<Shape> getShapes(String availabilityDomain) {
        return getShapes(availabilityDomain, null);
    }

    /** @param imageId 非空时仅返回与该镜像兼容的 Shape（与控制台 Edit shape 一致） */
    public List<Shape> getShapes(String availabilityDomain, String imageId) {
        ListShapesRequest.Builder b = ListShapesRequest.builder()
                .compartmentId(compartmentId)
                .availabilityDomain(availabilityDomain);
        if (imageId != null && !imageId.isBlank()) {
            b.imageId(imageId.trim());
        }
        return computeClient.listShapes(b.build()).getItems();
    }

    public List<Instance> listInstances() {
        return computeClient.listInstances(
                ListInstancesRequest.builder()
                        .compartmentId(compartmentId)
                        .lifecycleState(Instance.LifecycleState.Running)
                        .build()
        ).getItems();
    }

    public List<Instance> listAllInstances() {
        List<Instance> all = new ArrayList<>();
        for (Instance.LifecycleState state : List.of(
                Instance.LifecycleState.Running,
                Instance.LifecycleState.Stopped,
                Instance.LifecycleState.Starting,
                Instance.LifecycleState.Stopping)) {
            all.addAll(computeClient.listInstances(
                    ListInstancesRequest.builder()
                            .compartmentId(compartmentId)
                            .lifecycleState(state)
                            .build()
            ).getItems());
        }
        return all;
    }

    public List<Vcn> listVcn() {
        return virtualNetworkClient.listVcns(
                ListVcnsRequest.builder()
                        .compartmentId(compartmentId)
                        .lifecycleState(Vcn.LifecycleState.Available)
                        .build()
        ).getItems();
    }

    public List<Subnet> listSubnets(String vcnId) {
        return virtualNetworkClient.listSubnets(
                ListSubnetsRequest.builder()
                        .compartmentId(compartmentId)
                        .vcnId(vcnId)
                        .lifecycleState(Subnet.LifecycleState.Available)
                        .build()
        ).getItems();
    }

    public Vcn createVcn(String cidrBlock) {
        CreateVcnResponse response = virtualNetworkClient.createVcn(
                CreateVcnRequest.builder()
                        .createVcnDetails(CreateVcnDetails.builder()
                                .compartmentId(compartmentId)
                                .displayName("oci-worker-vcn")
                                .cidrBlocks(List.of(cidrBlock))
                                .build())
                        .build());
        log.info("Created VCN: {}", response.getVcn().getDisplayName());
        return response.getVcn();
    }

    public InternetGateway createInternetGateway(Vcn vcn) {
        CreateInternetGatewayResponse response = virtualNetworkClient.createInternetGateway(
                CreateInternetGatewayRequest.builder()
                        .createInternetGatewayDetails(CreateInternetGatewayDetails.builder()
                                .compartmentId(compartmentId)
                                .vcnId(vcn.getId())
                                .displayName("oci-worker-igw")
                                .isEnabled(true)
                                .build())
                        .build());
        return response.getInternetGateway();
    }

    public void addInternetGatewayToDefaultRouteTable(Vcn vcn, InternetGateway igw) {
        GetRouteTableResponse rtResponse = virtualNetworkClient.getRouteTable(
                GetRouteTableRequest.builder()
                        .rtId(vcn.getDefaultRouteTableId())
                        .build());
        List<RouteRule> rules = new ArrayList<>(rtResponse.getRouteTable().getRouteRules());
        rules.add(RouteRule.builder()
                .destination("0.0.0.0/0")
                .destinationType(RouteRule.DestinationType.CidrBlock)
                .networkEntityId(igw.getId())
                .build());
        virtualNetworkClient.updateRouteTable(
                UpdateRouteTableRequest.builder()
                        .rtId(vcn.getDefaultRouteTableId())
                        .updateRouteTableDetails(UpdateRouteTableDetails.builder()
                                .routeRules(rules)
                                .build())
                        .build());
    }

    public Subnet createSubnet(String availabilityDomain, String cidrBlock, Vcn vcn) {
        try {
            CreateSubnetResponse response = virtualNetworkClient.createSubnet(
                    CreateSubnetRequest.builder()
                            .createSubnetDetails(CreateSubnetDetails.builder()
                                    .compartmentId(compartmentId)
                                    .vcnId(vcn.getId())
                                    .displayName("oci-worker-subnet")
                                    .cidrBlock(cidrBlock)
                                    .availabilityDomain(availabilityDomain)
                                    .build())
                            .build());
            return response.getSubnet();
        } catch (Exception e) {
            log.error("Failed to create subnet: {}", e.getMessage());
            return null;
        }
    }

    public Image getImage(Shape shape) {
        String os = user.getOperationSystem() != null ? user.getOperationSystem() : "Ubuntu";

        String apiOs;
        String apiVersion = null;
        if (os.startsWith("Ubuntu")) {
            apiOs = "Canonical Ubuntu";
            if (os.contains("20.04")) apiVersion = "20.04";
            else if (os.contains("22.04")) apiVersion = "22.04";
            else if (os.contains("24.04")) apiVersion = "24.04";
        } else {
            apiOs = os;
        }

        ListImagesRequest.Builder reqBuilder = ListImagesRequest.builder()
                .compartmentId(compartmentId)
                .shape(shape.getShape())
                .operatingSystem(apiOs)
                .sortBy(ListImagesRequest.SortBy.Timecreated)
                .sortOrder(ListImagesRequest.SortOrder.Desc);
        if (apiVersion != null) {
            reqBuilder.operatingSystemVersion(apiVersion);
        }
        List<Image> images = computeClient.listImages(reqBuilder.build()).getItems();

        if (CollectionUtil.isEmpty(images)) {
            images = computeClient.listImages(
                    ListImagesRequest.builder()
                            .compartmentId(compartmentId)
                            .shape(shape.getShape())
                            .sortBy(ListImagesRequest.SortBy.Timecreated)
                            .sortOrder(ListImagesRequest.SortOrder.Desc)
                            .build()
            ).getItems();
        }
        return CollectionUtil.isEmpty(images) ? null : images.get(0);
    }

    public synchronized InstanceDetailDTO createInstanceData() {
        InstanceDetailDTO result = new InstanceDetailDTO();
        result.setTaskId(user.getTaskId());
        result.setUsername(user.getUsername());
        result.setRegion(user.getOciCfg().getRegion());
        result.setArchitecture(user.getArchitecture());
        result.setCreateNumbers(user.getCreateNumbers());

        List<AvailabilityDomain> availabilityDomains = getAvailabilityDomains();
        String targetShape = resolveTargetShape(user.getArchitecture());
        result.setResolvedTargetShape(targetShape);

        java.util.Set<String> excludedAds = user.getExcludedAvailabilityDomains() != null
                ? user.getExcludedAvailabilityDomains()
                : java.util.Set.of();

        boolean sawOutOfCapacity = false;
        for (AvailabilityDomain ad : availabilityDomains) {
            if (excludedAds.contains(ad.getName())) {
                continue;
            }
            String tryNextAdSuffix = hasNextAvailabilityDomain(availabilityDomains, ad, excludedAds)
                    ? "，尝试下一可用域" : "";
            List<Shape> shapes;
            try {
                shapes = getShapes(ad.getName()).stream()
                        .filter(s -> s.getShape().equals(targetShape))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                markAdExcludedNoShape(result, ad.getName());
                log.warn("【开机任务】用户:[{}], AD:[{}] - 当前可用域无此 Shape [{}]（ListShapes 失败）",
                        user.getUsername(), ad.getName(), targetShape);
                continue;
            }
            if (shapes.isEmpty()) {
                markAdExcludedNoShape(result, ad.getName());
                log.info("【开机任务】用户:[{}], AD:[{}] - 当前可用域无此 Shape [{}]",
                        user.getUsername(), ad.getName(), targetShape);
                continue;
            }

            for (Shape shape : shapes) {
                Image image = getImage(shape);
                if (image == null) continue;

                Subnet subnet;
                try {
                    subnet = findOrCreateSubnet(ad.getName());
                } catch (com.oracle.bmc.model.BmcException e) {
                    String hint = describeBmcFailure(e);
                    result.setFailureHint(hint);
                    if (isVcnCountLimitError(e)) {
                        log.warn("【开机任务】用户:[{}], AD:[{}] - {}", user.getUsername(), ad.getName(), hint);
                        break;
                    }
                    log.warn("【开机任务】用户:[{}], AD:[{}] - 准备网络失败{}。{}",
                            user.getUsername(), ad.getName(), tryNextAdSuffix, hint);
                    break;
                } catch (Exception e) {
                    String hint = describeThrowableFailure(e);
                    result.setFailureHint(hint);
                    log.warn("【开机任务】用户:[{}], AD:[{}] - 准备网络失败{}。{}",
                            user.getUsername(), ad.getName(), tryNextAdSuffix, hint);
                    break;
                }
                if (subnet == null) {
                    result.setNoPubVcn(true);
                    log.warn("【开机任务】用户:[{}], AD:[{}] - 无可用公有子网{}",
                            user.getUsername(), ad.getName(), tryNextAdSuffix);
                    break;
                }

                log.info("【开机任务】用户:[{}],区域:[{}], AD:[{}], 子网:[{}] 创建实例...",
                        user.getUsername(), user.getOciCfg().getRegion(), ad.getName(), subnet.getDisplayName());

                try {
                    String cloudInitScript = CommonUtils.getPwdShell(user.getRootPassword(), user.getCustomScript());
                    LaunchInstanceDetails launchDetails = buildLaunchDetails(ad, shape, image, subnet, cloudInitScript);
                    Instance instance = launchInstance(launchDetails);

                    String publicIp = getInstancePublicIp(instance);

                    try {
                        ensureIpv4AllIngressSecurityRules(subnet.getId());
                    } catch (Exception e) {
                        log.warn("【开机任务】用户:[{}] - IPv4 安全列表入站规则失败: {}", user.getUsername(), e.getMessage());
                    }

                    if (Boolean.TRUE.equals(user.getAssignIpv6())) {
                        String ipv6Address = null;
                        try {
                            ipv6Address = assignIpv6ToInstance(instance, subnet);
                            if (StrUtil.isNotBlank(ipv6Address)) {
                                result.setIpv6Address(ipv6Address);
                                log.info("【开机任务】用户:[{}] - IPv6 已分配: {}", user.getUsername(), ipv6Address);
                            } else {
                                log.warn("【开机任务】用户:[{}] - IPv6 分配未完成（VCN/子网/地址未就绪）", user.getUsername());
                            }
                        } catch (Exception e) {
                            log.warn("【开机任务】用户:[{}] - IPv6 分配失败: {}", user.getUsername(), e.getMessage());
                        }
                        boolean ipv6Ready = StrUtil.isNotBlank(ipv6Address)
                                || VcnIpv6Util.isEnabled(virtualNetworkClient, subnet.getVcnId());
                        if (ipv6Ready) {
                            try {
                                ensureIpv6AllSecurityRules(subnet.getId());
                            } catch (Exception e) {
                                log.warn("【开机任务】用户:[{}] - IPv6 安全列表规则失败: {}", user.getUsername(), e.getMessage());
                            }
                        } else {
                            log.warn("【开机任务】用户:[{}] - VCN 未启用 IPv6，跳过 ::/0 安全列表", user.getUsername());
                        }
                    }

                    result.setSuccess(true);
                    result.setInstanceId(instance.getId());
                    result.setInstanceName(instance.getDisplayName());
                    result.setShape(shape.getShape());
                    fillResultHardwareFromLaunch(result, instance, shape);
                    result.setDisk(user.getDisk());
                    ensureBootVolumeConfig(instance, result);
                    result.setPublicIp(publicIp);
                    result.setImage(image.getId());
                    result.setRootPassword(user.getRootPassword());
                    result.setRegion(user.getOciCfg().getRegion());
                    return result;
                } catch (com.oracle.bmc.model.BmcException e) {
                    if (e.getStatusCode() == 401) {
                        result.setDie(true);
                        return result;
                    }
                    if (isBootVolumeQuotaError(e)) {
                        String hint = describeBmcFailure(e, shape.getShape());
                        result.setBootVolumeQuotaExceeded(true);
                        result.setFailureHint(hint);
                        log.warn("【开机任务】用户:[{}], AD:[{}] - {}",
                                user.getUsername(), ad.getName(), hint);
                        return result;
                    }
                    if (isZeroFlexShapeQuotaError(e)) {
                        String hint = describeBmcFailure(e, shape.getShape());
                        result.setUnrecoverableLaunchFailure(true);
                        result.setFailureHint(hint);
                        log.warn("【开机任务】用户:[{}], AD:[{}] - 创建失败。{}，任务将停止",
                                user.getUsername(), ad.getName(), hint);
                        return result;
                    }
                    String hint = describeBmcFailure(e, shape.getShape());
                    if (isOutOfHostCapacityError(e)) {
                        sawOutOfCapacity = true;
                        log.warn("【开机任务】用户:[{}], AD:[{}] - 容量不足{}。{}",
                                user.getUsername(), ad.getName(), tryNextAdSuffix, hint);
                    } else {
                        log.warn("【开机任务】用户:[{}], AD:[{}] - 创建失败{}。{}",
                                user.getUsername(), ad.getName(), tryNextAdSuffix, hint);
                    }
                    break;
                } catch (Exception e) {
                    String hint = describeThrowableFailure(e);
                    result.setFailureHint(hint);
                    log.warn("【开机任务】用户:[{}], AD:[{}] - 创建异常{}。{}",
                            user.getUsername(), ad.getName(), tryNextAdSuffix, hint);
                    break;
                }
            }
        }
        if (sawOutOfCapacity) {
            result.setOutOfCapacity(true);
        }
        if (!availabilityDomains.isEmpty()) {
            boolean anyAdLeft = availabilityDomains.stream()
                    .anyMatch(ad -> !excludedAds.contains(ad.getName())
                            && (result.getAdsExcludedNoShape() == null
                            || !result.getAdsExcludedNoShape().contains(ad.getName())));
            if (!anyAdLeft && !result.isSuccess()) {
                result.setAllAdsExcludedNoShape(true);
            }
        }
        return result;
    }

    private static String resolveTargetShape(String arch) {
        if (arch != null && ("ARM".equalsIgnoreCase(arch) || "AMD".equalsIgnoreCase(arch))) {
            return ArchitectureEnum.getShape(arch);
        }
        if (ShapeSeriesUtil.isFullShapeName(arch)) {
            return arch.trim();
        }
        return ArchitectureEnum.getShape(arch == null ? "ARM" : arch);
    }

    private static void markAdExcludedNoShape(InstanceDetailDTO result, String adName) {
        if (result.getAdsExcludedNoShape() == null) {
            result.setAdsExcludedNoShape(new java.util.ArrayList<>());
        }
        if (!result.getAdsExcludedNoShape().contains(adName)) {
            result.getAdsExcludedNoShape().add(adName);
        }
    }

    private static boolean hasNextAvailabilityDomain(List<AvailabilityDomain> ads, AvailabilityDomain current,
                                                       java.util.Set<String> excludedAds) {
        boolean seenCurrent = false;
        for (AvailabilityDomain ad : ads) {
            if (excludedAds != null && excludedAds.contains(ad.getName())) {
                continue;
            }
            if (Objects.equals(ad.getName(), current.getName())) {
                seenCurrent = true;
                continue;
            }
            if (seenCurrent) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOutOfHostCapacityError(com.oracle.bmc.model.BmcException e) {
        if (isBootVolumeQuotaError(e)) {
            return false;
        }
        String em = e.getMessage() == null ? "" : e.getMessage();
        return e.getStatusCode() == 500 || em.contains("Out of host capacity")
                || (e.getStatusCode() == 400 && em.contains("LimitExceeded"))
                || e.getStatusCode() == 429;
    }

    /** LaunchInstance 返回 bootVolumeQuota / 引导卷 QuotaExceeded */
    private static boolean isBootVolumeQuotaError(com.oracle.bmc.model.BmcException e) {
        String em = e.getMessage() == null ? "" : e.getMessage();
        if (em.contains("bootVolumeQuota")) {
            return true;
        }
        return em.contains("QuotaExceeded")
                && (em.toLowerCase().contains("bootvolume") || em.contains("boot volume"));
    }

    /** 免费试用或无该 Flex Shape 配额时，OCI 可能返回可用 OCPU/内存比例 0-0。继续重试不会成功。 */
    private static boolean isZeroFlexShapeQuotaError(com.oracle.bmc.model.BmcException e) {
        String em = e.getMessage() == null ? "" : e.getMessage();
        return e.getStatusCode() == 400
                && em.contains("Invalid ratio of memory in GB to OCPUs")
                && em.contains("Valid ratio range: 0 - 0");
    }

    static String describeThrowableFailure(Throwable e) {
        if (e instanceof com.oracle.bmc.model.BmcException bmc) {
            return describeBmcFailure(bmc);
        }
        Throwable c = e.getCause();
        if (c instanceof com.oracle.bmc.model.BmcException bmc) {
            return describeBmcFailure(bmc);
        }
        String msg = e.getMessage();
        if (msg != null && msg.contains("ListShapes")) {
            return "当前可用域无此 Shape";
        }
        if (msg == null || msg.isBlank()) {
            return "创建失败";
        }
        int cut = Math.min(msg.length(), 200);
        return msg.substring(0, cut);
    }

    /** OCI 失败原因（中文，写入日志/任务播报，不含 SDK 长堆栈） */
    static String describeBmcFailure(com.oracle.bmc.model.BmcException e) {
        return describeBmcFailure(e, null);
    }

    /** OCI 失败原因（中文，写入日志/任务播报，不含 SDK 长堆栈） */
    static String describeBmcFailure(com.oracle.bmc.model.BmcException e, String shapeName) {
        String em = e.getMessage() == null ? "" : e.getMessage();
        if (isBootVolumeQuotaError(e)) {
            return "引导卷（启动盘）存储配额已达上限，硬盘配额用尽，创建失败";
        }
        if (isZeroFlexShapeQuotaError(e)) {
            String shape = StrUtil.isNotBlank(shapeName) ? shapeName : "目标 Flex Shape";
            return "当前账号没有 " + shape + " 的 OCPU/内存配额";
        }
        if (isVcnCountLimitError(e)) {
            return "VCN 数量已达配额上限，无法创建虚拟云网络，请删除无用 VCN 或申请提额";
        }
        if (em.contains("QuotaExceeded")) {
            return "OCI 服务配额已达上限，创建失败";
        }
        if (em.contains("Out of host capacity")) {
            return "主机容量不足";
        }
        if (em.contains("LimitExceeded")) {
            return "已触发 OCI 服务限制，创建失败";
        }
        if (e.getStatusCode() == 429) {
            return "请求过于频繁，请稍后重试";
        }
        int code = e.getStatusCode();
        int cut = Math.min(em.length(), 200);
        String brief = em.substring(0, cut);
        return code > 0 ? ("OCI 错误 (" + code + "): " + brief) : brief;
    }

    private static boolean isVcnCountLimitError(com.oracle.bmc.model.BmcException e) {
        String em = e.getMessage() == null ? "" : e.getMessage();
        if (em.contains("vcn-count")) {
            return true;
        }
        return em.contains("LimitExceeded")
                && (em.contains("CreateVcn") || em.contains("service limits were exceeded") && em.toLowerCase().contains("vcn"));
    }

    /**
     * 播报/任务详情中的 CPU、内存须与 OCI 实际交付一致：固定规格（如 E2.1.Micro）不用任务单上的 memory，
     * 否则会出现 TG 显示 6G、控制台实际 1G。
     */
    private void fillResultHardwareFromLaunch(InstanceDetailDTO result, Instance instance, Shape shape) {
        String shapeName = shape != null ? shape.getShape() : "";
        boolean flex = shapeName.contains("Flex");
        InstanceShapeConfig sc = instance.getShapeConfig();
        if (sc != null) {
            if (sc.getOcpus() != null) {
                result.setOcpus(sc.getOcpus().doubleValue());
            }
            if (sc.getMemoryInGBs() != null) {
                result.setMemory(sc.getMemoryInGBs().doubleValue());
            }
        }
        if (result.getOcpus() == null) {
            result.setOcpus(flex ? (user.getOcpus() != null ? user.getOcpus() : 1d) : 1d);
        }
        if (result.getMemory() == null) {
            if (flex) {
                result.setMemory(user.getMemory() != null ? user.getMemory() : 6d);
            } else {
                result.setMemory(fixedShapeDefaultMemoryGb(shapeName));
            }
        }
    }

    private void ensureBootVolumeConfig(Instance instance, InstanceDetailDTO result) {
        String bootVolumeId = waitForBootVolumeId(instance);
        int targetVpus = BootVolumeVpusUtil.normalize(user.getVpusPerGB());
        long targetSize = user.getDisk() != null ? user.getDisk() : 50L;
        result.setVpusPerGB(targetVpus);
        if (StrUtil.isBlank(bootVolumeId)) {
            log.warn("【开机任务】用户:[{}],实例:[{}] - 未找到引导卷，无法校准 VPUs/GB",
                    user.getUsername(), instance.getId());
            return;
        }
        try {
            BootVolume current = blockstorageClient.getBootVolume(
                    GetBootVolumeRequest.builder().bootVolumeId(bootVolumeId).build()).getBootVolume();
            Long currentVpus = current.getVpusPerGB();
            Long currentSize = current.getSizeInGBs();
            boolean needUpdate = currentVpus == null || currentVpus.intValue() != targetVpus
                    || currentSize == null || currentSize.longValue() != targetSize;
            if (needUpdate) {
                UpdateBootVolumeDetails.Builder details = UpdateBootVolumeDetails.builder()
                        .vpusPerGB((long) targetVpus);
                if (currentSize == null || currentSize.longValue() != targetSize) {
                    details.sizeInGBs(targetSize);
                }
                blockstorageClient.updateBootVolume(UpdateBootVolumeRequest.builder()
                        .bootVolumeId(bootVolumeId)
                        .updateBootVolumeDetails(details.build())
                        .build());
                log.info("【开机任务】用户:[{}],实例:[{}],引导卷:[{}] - 已校准为 {}GB/{}VPUs",
                        user.getUsername(), instance.getId(), bootVolumeId, targetSize, targetVpus);
            }
            BootVolume after = needUpdate
                    ? waitForBootVolumeConfig(bootVolumeId, targetSize, targetVpus)
                    : current;
            if (after.getSizeInGBs() != null) {
                result.setDisk(after.getSizeInGBs().intValue());
            }
            if (after.getVpusPerGB() != null) {
                result.setVpusPerGB(after.getVpusPerGB().intValue());
            }
        } catch (Exception e) {
            log.warn("【开机任务】用户:[{}],实例:[{}],引导卷:[{}] - 校准 VPUs/GB 失败: {}",
                    user.getUsername(), instance.getId(), bootVolumeId, e.getMessage());
        }
    }

    private BootVolume waitForBootVolumeConfig(String bootVolumeId, long targetSize, int targetVpus) {
        BootVolume last = null;
        for (int i = 0; i < 10; i++) {
            try {
                last = blockstorageClient.getBootVolume(
                        GetBootVolumeRequest.builder().bootVolumeId(bootVolumeId).build()).getBootVolume();
                Long size = last.getSizeInGBs();
                Long vpus = last.getVpusPerGB();
                if (size != null && size.longValue() == targetSize
                        && vpus != null && vpus.intValue() == targetVpus) {
                    return last;
                }
            } catch (Exception e) {
                log.debug("等待引导卷配置生效失败 bootVolumeId={} attempt={} err={}",
                        bootVolumeId, i + 1, e.getMessage());
            }
            try {
                Thread.sleep(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return last != null ? last : blockstorageClient.getBootVolume(
                GetBootVolumeRequest.builder().bootVolumeId(bootVolumeId).build()).getBootVolume();
    }

    private String waitForBootVolumeId(Instance instance) {
        if (instance == null || StrUtil.isBlank(instance.getId())) {
            return null;
        }
        for (int i = 0; i < 12; i++) {
            try {
                ListBootVolumeAttachmentsResponse response = computeClient.listBootVolumeAttachments(
                        ListBootVolumeAttachmentsRequest.builder()
                                .compartmentId(compartmentId)
                                .availabilityDomain(instance.getAvailabilityDomain())
                                .instanceId(instance.getId())
                                .build());
                if (response.getItems() != null) {
                    for (BootVolumeAttachment attachment : response.getItems()) {
                        if (attachment != null && StrUtil.isNotBlank(attachment.getBootVolumeId())) {
                            return attachment.getBootVolumeId();
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("等待引导卷挂载信息失败 instanceId={} attempt={} err={}",
                        instance.getId(), i + 1, e.getMessage());
            }
            try {
                Thread.sleep(5000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static double fixedShapeDefaultMemoryGb(String shapeName) {
        if (StrUtil.isBlank(shapeName)) {
            return 1d;
        }
        if (shapeName.contains("Micro")) {
            return 1d;
        }
        return 1d;
    }

    private Subnet findOrCreateSubnet(String availabilityDomain) {
        List<Vcn> vcnList = listVcn();
        if (CollectionUtil.isEmpty(vcnList)) {
            log.info("【开机任务】用户:[{}],区域:[{}] - 未找到 VCN，正在创建...", user.getUsername(), user.getOciCfg().getRegion());
            Vcn vcn = createVcn(CIDR_BLOCK);
            InternetGateway igw = createInternetGateway(vcn);
            addInternetGatewayToDefaultRouteTable(vcn, igw);
            return createSubnet(availabilityDomain, SUBNET_CIDR, vcn);
        }

        for (Vcn vcn : vcnList) {
            List<InternetGateway> igws = virtualNetworkClient.listInternetGateways(
                    ListInternetGatewaysRequest.builder()
                            .vcnId(vcn.getId())
                            .compartmentId(compartmentId)
                            .build()
            ).getItems();
            if (CollectionUtil.isEmpty(igws)) {
                InternetGateway igw = createInternetGateway(vcn);
                addInternetGatewayToDefaultRouteTable(vcn, igw);
            }

            List<Subnet> subnets = listSubnets(vcn.getId());
            if (CollectionUtil.isEmpty(subnets)) {
                return createSubnet(availabilityDomain, SUBNET_CIDR, vcn);
            }
            for (Subnet subnet : subnets) {
                if (!subnet.getProhibitInternetIngress()) {
                    return subnet;
                }
            }
        }
        return null;
    }

    private String resolveLaunchDisplayName() {
        int target = user.getCreateNumbers() != null && user.getCreateNumbers() > 0 ? user.getCreateNumbers() : 1;
        int ord = user.getInstanceDisplayOrdinal() != null && user.getInstanceDisplayOrdinal() > 0
                ? user.getInstanceDisplayOrdinal() : 1;
        if (target == 1) {
            return "oci-worker-instance";
        }
        if (target <= 4) {
            int o = Math.min(Math.max(ord, 1), target);
            char letter = (char) ('A' + o - 1);
            return "oci-worker-" + letter;
        }
        return "oci-instance-" + ord;
    }

    private static InstanceSourceViaImageDetails buildBootVolumeSource(String imageId, SysUserDTO user) {
        long sizeGb = user.getDisk() != null ? user.getDisk() : 50;
        InstanceSourceViaImageDetails.Builder b = InstanceSourceViaImageDetails.builder()
                .imageId(imageId)
                .bootVolumeSizeInGBs(sizeGb);
        int vpus = BootVolumeVpusUtil.normalize(user.getVpusPerGB());
        if (vpus > 0) {
            b.bootVolumeVpusPerGB((long) vpus);
        }
        return b.build();
    }

    private LaunchInstanceDetails buildLaunchDetails(AvailabilityDomain ad, Shape shape, Image image,
                                                      Subnet subnet, String cloudInitScript) {
        LaunchInstanceDetails.Builder builder = LaunchInstanceDetails.builder()
                .compartmentId(compartmentId)
                .availabilityDomain(ad.getName())
                .displayName(resolveLaunchDisplayName())
                .shape(shape.getShape())
                .sourceDetails(buildBootVolumeSource(image.getId(), user))
                .createVnicDetails(CreateVnicDetails.builder()
                        .subnetId(subnet.getId())
                        .assignPublicIp(user.getAssignPublicIp() != null ? user.getAssignPublicIp() : true)
                        .build())
                .metadata(cloudInitScript != null && !cloudInitScript.isEmpty()
                        ? Map.of("user_data", Base64.getEncoder().encodeToString(
                                cloudInitScript.getBytes(StandardCharsets.UTF_8)))
                        : null);

        if (shape.getShape().contains("Flex")) {
            builder.shapeConfig(LaunchInstanceShapeConfigDetails.builder()
                    .ocpus(user.getOcpus() != null ? user.getOcpus().floatValue() : 1f)
                    .memoryInGBs(user.getMemory() != null ? user.getMemory().floatValue() : 6f)
                    .build());
        }
        return builder.build();
    }

    private Instance launchInstance(LaunchInstanceDetails details) throws Exception {
        ComputeWaiters waiters = computeClient.newWaiters(workRequestClient);
        LaunchInstanceResponse launchResponse = waiters
                .forLaunchInstance(LaunchInstanceRequest.builder()
                        .launchInstanceDetails(details)
                        .build())
                .execute();

        return waiters.forInstance(
                GetInstanceRequest.builder()
                        .instanceId(launchResponse.getInstance().getId())
                        .build(),
                Instance.LifecycleState.Running
        ).execute().getInstance();
    }

    /** @return 分配成功时的 IPv6 地址，失败或未就绪则 {@code null} */
    private String assignIpv6ToInstance(Instance instance, Subnet subnet) {
        List<VnicAttachment> attachments = computeClient.listVnicAttachments(
                ListVnicAttachmentsRequest.builder()
                        .compartmentId(compartmentId)
                        .instanceId(instance.getId())
                        .build()
        ).getItems();
        if (attachments.isEmpty()) return null;

        String vnicId = attachments.get(0).getVnicId();
        String subnetId = subnet.getId();
        Vcn vcn = virtualNetworkClient.getVcn(
                GetVcnRequest.builder().vcnId(subnet.getVcnId()).build()
        ).getVcn();

        if (vcn.getIpv6CidrBlocks() == null || vcn.getIpv6CidrBlocks().isEmpty()) {
            try {
                virtualNetworkClient.addIpv6VcnCidr(
                        AddIpv6VcnCidrRequest.builder()
                                .vcnId(vcn.getId())
                                .addVcnIpv6CidrDetails(AddVcnIpv6CidrDetails.builder()
                                        .isOracleGuaAllocationEnabled(true)
                                        .build())
                                .build());
                Thread.sleep(8000);
            } catch (Exception e) {
                String em = e.getMessage() == null ? "" : e.getMessage();
                if (!em.contains("already exists") && !em.contains("already has")) {
                    log.warn("VCN IPv6 CIDR 添加失败: {}", em);
                    return null;
                }
            }
            vcn = virtualNetworkClient.getVcn(GetVcnRequest.builder().vcnId(vcn.getId()).build()).getVcn();
        }

        Subnet freshSubnet = virtualNetworkClient.getSubnet(
                GetSubnetRequest.builder().subnetId(subnetId).build()).getSubnet();
        if (freshSubnet.getIpv6CidrBlocks() == null || freshSubnet.getIpv6CidrBlocks().isEmpty()) {
            String vcnIpv6Cidr = vcn.getIpv6CidrBlocks() != null && !vcn.getIpv6CidrBlocks().isEmpty()
                    ? vcn.getIpv6CidrBlocks().get(0) : null;
            if (vcnIpv6Cidr == null) return null;
            String subnetIpv6Cidr = vcnIpv6Cidr.replaceAll("/\\d+$", "/64");
            try {
                virtualNetworkClient.updateSubnet(UpdateSubnetRequest.builder()
                        .subnetId(subnetId)
                        .updateSubnetDetails(UpdateSubnetDetails.builder()
                                .ipv6CidrBlocks(List.of(subnetIpv6Cidr))
                                .build())
                        .build());
                Thread.sleep(3000);
            } catch (Exception e) {
                String em = e.getMessage() == null ? "" : e.getMessage();
                if (!em.contains("already exists") && !em.contains("already has")) {
                    log.warn("子网 IPv6 CIDR 添加失败: {}", em);
                    return null;
                }
            }
        }

        ensureIpv6InternetRoute(vcn, freshSubnet);

        Ipv6 ipv6 = virtualNetworkClient.createIpv6(CreateIpv6Request.builder()
                .createIpv6Details(CreateIpv6Details.builder()
                        .vnicId(vnicId)
                        .build())
                .build()).getIpv6();
        return ipv6 != null ? ipv6.getIpAddress() : null;
    }

    /** 开机任务创建实例后：静默补 0.0.0.0/0 全协议入站（已有则跳过） */
    private void ensureIpv4AllIngressSecurityRules(String subnetId) {
        Subnet subnet = virtualNetworkClient.getSubnet(
                GetSubnetRequest.builder().subnetId(subnetId).build()).getSubnet();
        if (subnet.getSecurityListIds() == null || subnet.getSecurityListIds().isEmpty()) {
            log.warn("子网 {} 无安全列表，跳过 IPv4 0.0.0.0/0 入站规则", subnetId);
            return;
        }
        String secListId = subnet.getSecurityListIds().get(0);
        SecurityList secList = virtualNetworkClient.getSecurityList(
                GetSecurityListRequest.builder().securityListId(secListId).build()
        ).getSecurityList();

        List<IngressSecurityRule> ingressRules = new ArrayList<>(secList.getIngressSecurityRules());
        if (hasIpv4AllIngress(ingressRules)) {
            return;
        }
        ingressRules.add(IngressSecurityRule.builder()
                .source("0.0.0.0/0")
                .protocol("all")
                .description("oci-worker auto IPv4 ingress")
                .build());
        virtualNetworkClient.updateSecurityList(
                UpdateSecurityListRequest.builder()
                        .securityListId(secListId)
                        .updateSecurityListDetails(UpdateSecurityListDetails.builder()
                                .ingressSecurityRules(ingressRules)
                                .egressSecurityRules(secList.getEgressSecurityRules())
                                .build())
                        .build());
        log.info("子网 {} 安全列表已补 IPv4 0.0.0.0/0 全协议入站", subnetId);
    }

    /** 开机任务勾选 IPv6 时：静默补 ::/0 全协议入站+出站（已有则跳过；合并重复项） */
    private void ensureIpv6AllSecurityRules(String subnetId) {
        if (!VcnIpv6Util.isEnabledForSubnet(virtualNetworkClient, subnetId)) {
            log.warn("子网 {} 所属 VCN 未启用 IPv6，跳过 ::/0 安全列表规则", subnetId);
            return;
        }
        Subnet subnet = virtualNetworkClient.getSubnet(
                GetSubnetRequest.builder().subnetId(subnetId).build()).getSubnet();
        if (subnet.getSecurityListIds() == null || subnet.getSecurityListIds().isEmpty()) {
            log.warn("子网 {} 无安全列表，跳过 IPv6 ::/0 规则", subnetId);
            return;
        }
        String secListId = subnet.getSecurityListIds().get(0);
        SecurityList secList = virtualNetworkClient.getSecurityList(
                GetSecurityListRequest.builder().securityListId(secListId).build()
        ).getSecurityList();

        int ingressBefore = secList.getIngressSecurityRules() != null ? secList.getIngressSecurityRules().size() : 0;
        int egressBefore = secList.getEgressSecurityRules() != null ? secList.getEgressSecurityRules().size() : 0;

        List<IngressSecurityRule> ingressRules = dedupeIpv6AllIngress(
                new ArrayList<>(secList.getIngressSecurityRules() != null ? secList.getIngressSecurityRules() : List.of()));
        List<EgressSecurityRule> egressRules = dedupeIpv6AllEgress(
                new ArrayList<>(secList.getEgressSecurityRules() != null ? secList.getEgressSecurityRules() : List.of()));

        boolean changed = ingressRules.size() != ingressBefore || egressRules.size() != egressBefore;

        if (!hasIpv6AllIngress(ingressRules)) {
            ingressRules.add(IngressSecurityRule.builder()
                    .source("::/0")
                    .sourceType(IngressSecurityRule.SourceType.CidrBlock)
                    .protocol("all")
                    .description("oci-worker auto IPv6 ingress")
                    .build());
            changed = true;
        }
        if (!hasIpv6AllEgress(egressRules)) {
            egressRules.add(EgressSecurityRule.builder()
                    .destination("::/0")
                    .destinationType(EgressSecurityRule.DestinationType.CidrBlock)
                    .protocol("all")
                    .description("oci-worker auto IPv6 egress")
                    .build());
            changed = true;
        }

        if (!changed) {
            return;
        }
        virtualNetworkClient.updateSecurityList(
                UpdateSecurityListRequest.builder()
                        .securityListId(secListId)
                        .updateSecurityListDetails(UpdateSecurityListDetails.builder()
                                .ingressSecurityRules(ingressRules)
                                .egressSecurityRules(egressRules)
                                .build())
                        .build());
        log.info("子网 {} 安全列表已补/整理 IPv6 ::/0 全协议入站/出站", subnetId);
    }

    private static boolean isProtocolAll(String protocol) {
        return protocol != null && "all".equalsIgnoreCase(protocol.trim());
    }

    private static boolean isIpv6WildcardCidr(String cidr) {
        return cidr != null && "::/0".equals(cidr.trim());
    }

    private static boolean hasIpv4AllIngress(List<IngressSecurityRule> rules) {
        return rules.stream().anyMatch(r ->
                "0.0.0.0/0".equals(r.getSource()) && isProtocolAll(r.getProtocol()));
    }

    private static boolean hasIpv6AllIngress(List<IngressSecurityRule> rules) {
        return rules.stream().anyMatch(r ->
                isIpv6WildcardCidr(r.getSource()) && isProtocolAll(r.getProtocol()));
    }

    private static boolean hasIpv6AllEgress(List<EgressSecurityRule> rules) {
        return rules.stream().anyMatch(r ->
                isIpv6WildcardCidr(r.getDestination()) && isProtocolAll(r.getProtocol()));
    }

    /** 保留第一条 ::/0 + all 入站，去掉重复项 */
    private static List<IngressSecurityRule> dedupeIpv6AllIngress(List<IngressSecurityRule> rules) {
        List<IngressSecurityRule> out = new ArrayList<>();
        boolean seenIpv6All = false;
        for (IngressSecurityRule r : rules) {
            if (isIpv6WildcardCidr(r.getSource()) && isProtocolAll(r.getProtocol())) {
                if (!seenIpv6All) {
                    out.add(r);
                    seenIpv6All = true;
                }
            } else {
                out.add(r);
            }
        }
        return out;
    }

    /** 保留第一条 ::/0 + all 出站，去掉重复项 */
    private static List<EgressSecurityRule> dedupeIpv6AllEgress(List<EgressSecurityRule> rules) {
        List<EgressSecurityRule> out = new ArrayList<>();
        boolean seenIpv6All = false;
        for (EgressSecurityRule r : rules) {
            if (isIpv6WildcardCidr(r.getDestination()) && isProtocolAll(r.getProtocol())) {
                if (!seenIpv6All) {
                    out.add(r);
                    seenIpv6All = true;
                }
            } else {
                out.add(r);
            }
        }
        return out;
    }

    private void ensureIpv6InternetRoute(Vcn vcn, Subnet subnet) {
        List<InternetGateway> igws = virtualNetworkClient.listInternetGateways(
                ListInternetGatewaysRequest.builder()
                        .compartmentId(compartmentId)
                        .vcnId(vcn.getId())
                        .build()
        ).getItems();

        InternetGateway igw;
        if (CollectionUtil.isEmpty(igws)) {
            igw = createInternetGateway(vcn);
        } else {
            igw = igws.stream()
                    .filter(gw -> Boolean.TRUE.equals(gw.getIsEnabled()))
                    .findFirst()
                    .orElse(igws.get(0));
        }

        String routeTableId = subnet.getRouteTableId() != null ? subnet.getRouteTableId() : vcn.getDefaultRouteTableId();
        if (StrUtil.isBlank(routeTableId)) {
            return;
        }

        RouteTable routeTable = virtualNetworkClient.getRouteTable(
                GetRouteTableRequest.builder().rtId(routeTableId).build()
        ).getRouteTable();

        List<RouteRule> rules = new ArrayList<>();
        if (routeTable.getRouteRules() != null) {
            rules.addAll(routeTable.getRouteRules());
        }

        boolean hasIpv6DefaultRoute = rules.stream().anyMatch(rule ->
                "::/0".equals(rule.getDestination())
                        && RouteRule.DestinationType.CidrBlock.equals(rule.getDestinationType())
        );

        if (!hasIpv6DefaultRoute) {
            rules.add(RouteRule.builder()
                    .destination("::/0")
                    .destinationType(RouteRule.DestinationType.CidrBlock)
                    .networkEntityId(igw.getId())
                    .description("oci-worker auto add IPv6 default route")
                    .build());

            virtualNetworkClient.updateRouteTable(
                    UpdateRouteTableRequest.builder()
                            .rtId(routeTableId)
                            .updateRouteTableDetails(UpdateRouteTableDetails.builder()
                                    .routeRules(rules)
                                    .build())
                            .build()
            );
        }
    }

    public String getInstancePublicIp(Instance instance) {
        try {
            List<VnicAttachment> vnicAttachments = computeClient.listVnicAttachments(
                    ListVnicAttachmentsRequest.builder()
                            .compartmentId(compartmentId)
                            .instanceId(instance.getId())
                            .build()
            ).getItems();

            for (VnicAttachment attachment : vnicAttachments) {
                Vnic vnic = virtualNetworkClient.getVnic(
                        GetVnicRequest.builder()
                                .vnicId(attachment.getVnicId())
                                .build()
                ).getVnic();
                if (vnic.getPublicIp() != null) {
                    return vnic.getPublicIp();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get public IP: {}", e.getMessage());
        }
        return null;
    }
}
