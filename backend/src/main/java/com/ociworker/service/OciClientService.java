package com.ociworker.service;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
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
import com.oracle.bmc.workrequests.WorkRequestClient;
import com.ociworker.enums.ArchitectureEnum;
import com.ociworker.model.dto.InstanceDetailDTO;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.util.CommonUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
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
    private final MonitoringClient monitoringClient;
    private final NetworkLoadBalancerClient networkLoadBalancerClient;
    private final SimpleAuthenticationDetailsProvider provider;
    private SysUserDTO user;
    private String compartmentId;

    private static final String CIDR_BLOCK = "10.0.0.0/16";
    private static final String SUBNET_CIDR = "10.0.0.0/24";

    @Override
    public void close() {
        computeClient.close();
        identityClient.close();
        workRequestClient.close();
        virtualNetworkClient.close();
        blockstorageClient.close();
        monitoringClient.close();
        networkLoadBalancerClient.close();
    }

    public OciClientService(SysUserDTO user) {
        this.user = user;
        SysUserDTO.OciCfg ociCfg = user.getOciCfg();
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
                .region(Region.valueOf(ociCfg.getRegion()))
                .build();

        identityClient = IdentityClient.builder().build(provider);
        computeClient = ComputeClient.builder().build(provider);
        blockstorageClient = BlockstorageClient.builder().build(provider);
        workRequestClient = WorkRequestClient.builder().build(provider);
        virtualNetworkClient = VirtualNetworkClient.builder().build(provider);
        monitoringClient = MonitoringClient.builder().build(provider);
        networkLoadBalancerClient = NetworkLoadBalancerClient.builder().build(provider);
        this.provider = provider;
        compartmentId = StrUtil.isBlank(ociCfg.getCompartmentId())
                ? findRootCompartment(identityClient, provider.getTenantId())
                : ociCfg.getCompartmentId();
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

    public List<AvailabilityDomain> getAvailabilityDomains() {
        return identityClient.listAvailabilityDomains(
                ListAvailabilityDomainsRequest.builder()
                        .compartmentId(compartmentId)
                        .build()
        ).getItems();
    }

    public List<Shape> getShapes(String availabilityDomain) {
        return computeClient.listShapes(
                ListShapesRequest.builder()
                        .compartmentId(compartmentId)
                        .availabilityDomain(availabilityDomain)
                        .build()
        ).getItems();
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
        List<Image> images = computeClient.listImages(
                ListImagesRequest.builder()
                        .compartmentId(compartmentId)
                        .shape(shape.getShape())
                        .operatingSystem(os.contains("Ubuntu") ? "Canonical Ubuntu" : os)
                        .sortBy(ListImagesRequest.SortBy.Timecreated)
                        .sortOrder(ListImagesRequest.SortOrder.Desc)
                        .build()
        ).getItems();

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
        String targetShape = ArchitectureEnum.getShape(user.getArchitecture());

        List<String> shapeList = availabilityDomains.parallelStream()
                .flatMap(ad -> getShapes(ad.getName()).stream())
                .map(Shape::getShape)
                .distinct()
                .collect(Collectors.toList());

        if (CollectionUtil.isEmpty(shapeList) || !shapeList.contains(targetShape)) {
            result.setNoShape(true);
            log.error("[CreateTask] User:[{}], Region:[{}], Arch:[{}] - Shape not available. Available: {}",
                    user.getUsername(), user.getOciCfg().getRegion(), user.getArchitecture(), shapeList);
            return result;
        }

        try {
            for (AvailabilityDomain ad : availabilityDomains) {
                List<Shape> shapes = getShapes(ad.getName()).stream()
                        .filter(s -> s.getShape().equals(targetShape))
                        .collect(Collectors.toList());
                if (shapes.isEmpty()) continue;

                for (Shape shape : shapes) {
                    Image image = getImage(shape);
                    if (image == null) continue;

                    Subnet subnet = findOrCreateSubnet(ad.getName());
                    if (subnet == null) {
                        result.setNoPubVcn(true);
                        continue;
                    }

                    log.info("【开机任务】用户:[{}],区域:[{}],系统架构:[{}],使用子网:[{}] 创建实例...",
                            user.getUsername(), user.getOciCfg().getRegion(), user.getArchitecture(), subnet.getDisplayName());

                    String cloudInitScript = CommonUtils.getPwdShell(user.getRootPassword());
                    LaunchInstanceDetails launchDetails = buildLaunchDetails(ad, shape, image, subnet, cloudInitScript);
                    Instance instance = launchInstance(launchDetails);

                    String publicIp = getInstancePublicIp(instance);
                    result.setSuccess(true);
                    result.setInstanceId(instance.getId());
                    result.setInstanceName(instance.getDisplayName());
                    result.setShape(shape.getShape());
                    result.setOcpus(user.getOcpus());
                    result.setMemory(user.getMemory());
                    result.setDisk(user.getDisk());
                    result.setPublicIp(publicIp);
                    result.setImage(image.getId());
                    result.setRootPassword(user.getRootPassword());
                    result.setRegion(user.getOciCfg().getRegion());
                    return result;
                }
            }
        } catch (com.oracle.bmc.model.BmcException e) {
            if (e.getStatusCode() == 401) {
                result.setDie(true);
            } else if (e.getStatusCode() == 500 || e.getMessage().contains("Out of host capacity")
                    || (e.getStatusCode() == 400 && e.getMessage().contains("LimitExceeded"))
                    || e.getStatusCode() == 429) {
                result.setOutOfCapacity(true);
            } else {
                log.error("【开机任务】用户:[{}],区域:[{}] - OCI 错误: {} ({})",
                        user.getUsername(), user.getOciCfg().getRegion(), e.getMessage(), e.getStatusCode());
            }
        } catch (Exception e) {
            log.error("【开机任务】用户:[{}],区域:[{}] - 异常: {}",
                    user.getUsername(), user.getOciCfg().getRegion(), e.getMessage());
        }
        return result;
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

    private LaunchInstanceDetails buildLaunchDetails(AvailabilityDomain ad, Shape shape, Image image,
                                                      Subnet subnet, String cloudInitScript) {
        LaunchInstanceDetails.Builder builder = LaunchInstanceDetails.builder()
                .compartmentId(compartmentId)
                .availabilityDomain(ad.getName())
                .displayName("oci-worker-instance")
                .shape(shape.getShape())
                .sourceDetails(InstanceSourceViaImageDetails.builder()
                        .imageId(image.getId())
                        .bootVolumeSizeInGBs((long) (user.getDisk() != null ? user.getDisk() : 50))
                        .build())
                .createVnicDetails(CreateVnicDetails.builder()
                        .subnetId(subnet.getId())
                        .assignPublicIp(true)
                        .build())
                .metadata(cloudInitScript != null && !cloudInitScript.isEmpty()
                        ? Map.of("user_data", Base64.getEncoder().encodeToString(cloudInitScript.getBytes()))
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
