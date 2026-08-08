package com.ociworker.bastion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;
import com.oracle.bmc.bastion.BastionClient;
import com.oracle.bmc.bastion.model.Bastion;
import com.oracle.bmc.bastion.model.BastionLifecycleState;
import com.oracle.bmc.bastion.model.BastionSummary;
import com.oracle.bmc.bastion.model.CreateBastionDetails;
import com.oracle.bmc.bastion.model.CreateManagedSshSessionTargetResourceDetails;
import com.oracle.bmc.bastion.model.CreatePortForwardingSessionTargetResourceDetails;
import com.oracle.bmc.bastion.model.CreateSessionDetails;
import com.oracle.bmc.bastion.model.CreateSessionTargetResourceDetails;
import com.oracle.bmc.bastion.model.OperationStatus;
import com.oracle.bmc.bastion.model.PublicKeyDetails;
import com.oracle.bmc.bastion.model.SessionLifecycleState;
import com.oracle.bmc.bastion.model.WorkRequest;
import com.oracle.bmc.bastion.model.WorkRequestResource;
import com.oracle.bmc.bastion.requests.CreateBastionRequest;
import com.oracle.bmc.bastion.requests.CreateSessionRequest;
import com.oracle.bmc.bastion.requests.DeleteSessionRequest;
import com.oracle.bmc.bastion.requests.GetBastionRequest;
import com.oracle.bmc.bastion.requests.GetSessionRequest;
import com.oracle.bmc.bastion.requests.GetWorkRequestRequest;
import com.oracle.bmc.bastion.requests.ListBastionsRequest;
import com.oracle.bmc.bastion.responses.CreateBastionResponse;
import com.oracle.bmc.bastion.responses.CreateSessionResponse;
import com.oracle.bmc.bastion.responses.GetWorkRequestResponse;
import com.oracle.bmc.bastion.model.Session;
import com.oracle.bmc.computeinstanceagent.PluginClient;
import com.oracle.bmc.computeinstanceagent.model.InstanceAgentPlugin;
import com.oracle.bmc.computeinstanceagent.requests.GetInstanceAgentPluginRequest;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.InstanceAgentConfig;
import com.oracle.bmc.core.model.InstanceAgentPluginConfigDetails;
import com.oracle.bmc.core.model.IngressSecurityRule;
import com.oracle.bmc.core.model.Image;
import com.oracle.bmc.core.model.InstanceSourceDetails;
import com.oracle.bmc.core.model.InstanceSourceViaImageDetails;
import com.oracle.bmc.core.model.PortRange;
import com.oracle.bmc.core.model.RouteRule;
import com.oracle.bmc.core.model.RouteTable;
import com.oracle.bmc.core.model.SecurityList;
import com.oracle.bmc.core.model.SecurityRule;
import com.oracle.bmc.core.model.ServiceGateway;
import com.oracle.bmc.core.model.Subnet;
import com.oracle.bmc.core.model.TcpOptions;
import com.oracle.bmc.core.model.Vnic;
import com.oracle.bmc.core.model.VnicAttachment;
import com.oracle.bmc.core.requests.GetInstanceRequest;
import com.oracle.bmc.core.requests.GetImageRequest;
import com.oracle.bmc.core.requests.GetRouteTableRequest;
import com.oracle.bmc.core.requests.GetSecurityListRequest;
import com.oracle.bmc.core.requests.GetServiceGatewayRequest;
import com.oracle.bmc.core.requests.GetVnicRequest;
import com.oracle.bmc.core.requests.ListNetworkSecurityGroupSecurityRulesRequest;
import com.oracle.bmc.core.requests.ListSubnetsRequest;
import com.oracle.bmc.core.requests.ListVnicAttachmentsRequest;
import com.oracle.bmc.core.requests.GetSubnetRequest;
import com.oracle.bmc.model.BmcException;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciCreateTask;
import com.ociworker.model.entity.OciUser;
import com.ociworker.service.OciClientService;
import com.ociworker.util.OciBmcErrorTranslator;
import com.ociworker.util.OciRegionCatalog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OCI Bastion orchestration kept separate from the existing WebSSH and serial
 * console services. Prepared credentials live only in short-lived leases.
 */
@Slf4j
@Service
public class BastionService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern COMMAND_HOST = Pattern.compile("@([^\\s\\\"']+)");
    private static final Pattern COMMAND_USER = Pattern.compile("(?:^|\\s)([^\\s\\\"']+)@(?:host\\.)?bastion\\.");
    private static final Pattern COMMAND_PORT = Pattern.compile("(?:-p|--port)\\s+(\\d{1,5})");
    private static final int MAX_SECRET_LENGTH = 512 * 1024;
    private static final int MIN_SESSION_TTL_SECONDS = 1_800;
    private static final int MAX_SESSION_TTL_SECONDS = 10_800;
    private static final long POLL_INTERVAL_MILLIS = 2_000L;
    private static final String MANAGED_BY_TAG = "ociworker";
    private static final String SSH_PROTOCOL = "6";

    private final OciUserMapper userMapper;
    private final OciCreateTaskMapper taskMapper;
    private final ConcurrentHashMap<String, Lease> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Lease> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> bastionLocks = new ConcurrentHashMap<>();
    private final Object prepareAdmissionLock = new Object();
    private final ConcurrentHashMap<String, AtomicInteger> activePreparesByOwner = new ConcurrentHashMap<>();
    private final AtomicInteger activePrepares = new AtomicInteger();
    private final ScheduledExecutorService expiryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "bastion-lease-expiry");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    @Value("${oci.bastion.client-cidr-block-allow-list:}")
    private String clientCidrBlockAllowList = "";

    @Value("${oci.bastion.session-ttl-seconds:10800}")
    private int configuredSessionTtlSeconds = MAX_SESSION_TTL_SECONDS;

    @Value("${oci.bastion.prepare-lease-seconds:300}")
    private int prepareLeaseSeconds = 300;

    @Value("${oci.bastion.operation-timeout-seconds:120}")
    private int operationTimeoutSeconds = 120;

    @Value("${oci.bastion.prepare-timeout-seconds:150}")
    private int prepareTimeoutSeconds = 150;

    @Value("${oci.bastion.max-concurrent-prepares:8}")
    private int maxConcurrentPrepares = 8;

    @Value("${oci.bastion.max-concurrent-prepares-per-account:2}")
    private int maxConcurrentPreparesPerAccount = 2;

    @Value("${oci.bastion.max-pending-leases:64}")
    private int maxPendingLeases = 64;

    @Value("${oci.bastion.max-pending-leases-per-account:4}")
    private int maxPendingLeasesPerAccount = 4;

    @Value("${oci.bastion.require-cloud-agent:true}")
    private boolean requireCloudAgent = true;

    @Value("${oci.bastion.cloud-agent-plugin-name:Bastion}")
    private String cloudAgentPluginName = "Bastion";

    @Value("${oci.bastion.validate-target-ingress:true}")
    private boolean validateTargetIngress = true;

    public BastionService(OciUserMapper userMapper, OciCreateTaskMapper taskMapper) {
        this.userMapper = Objects.requireNonNull(userMapper, "userMapper");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
    }

    @PostConstruct
    void startLeaseExpiry() {
        expiryExecutor.scheduleAtFixedRate(this::expireLeases, 30, 30, TimeUnit.SECONDS);
    }

    /** Return credential availability without returning any credential value. */
    public Map<String, Object> inspectCredentials(String tenantId, String instanceId) {
        tenantId = requireIdentifier(tenantId, "tenant id");
        instanceId = requireIdentifier(instanceId, "instance id");
        SavedCredentials saved = findSavedCredentials(tenantId, instanceId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("loginMode", saved.loginMode());
        data.put("username", saved.username());
        data.put("passwordAvailable", hasSecret(saved.password()));
        data.put("publicKeyAvailable", hasText(saved.publicKey()));
        data.put("privateKeyRequired", true);
        return data;
    }

    /**
     * Creates a Managed SSH or Port Forwarding session and returns only a
     * one-shot token. Password login uses Port Forwarding because Managed SSH
     * requires the same public key on the target hop.
     */
    public Map<String, Object> prepare(BastionPrepareRequest request, String ownerAccount) {
        if (request == null) {
            throw new OciException("Bastion connection request is required");
        }
        ownerAccount = requireOwner(ownerAccount);
        try (PreparePermit ignored = acquirePreparePermit(ownerAccount)) {
            ensurePendingCapacity(ownerAccount);
            return prepareInternal(request, ownerAccount);
        }
    }

    private Map<String, Object> prepareInternal(BastionPrepareRequest request, String ownerAccount) {
        String tenantId = requireIdentifier(request.id(), "tenant id");
        String instanceId = requireIdentifier(request.instanceId(), "instance id");
        OciUser ociUser = userMapper.selectById(tenantId);
        if (ociUser == null) {
            throw new OciException("OCI tenant was not found");
        }

        SavedCredentials saved = findSavedCredentials(tenantId, instanceId);
        ResolvedCredentials credentials = resolveCredentials(request, saved);
        String region = firstNonBlank(request.region(), ociUser.getOciRegion());
        if (!hasText(region)) {
            throw new OciException("OCI region is required");
        }
        region = OciRegionCatalog.resolveRegion(region).getRegionId();
        PrepareDeadline deadline = PrepareDeadline.start(prepareTimeoutSeconds);

        String createdSessionId = null;
        try (OciClientService client = new OciClientService(toSysUser(ociUser, region), region);
             BastionClient bastionClient = buildBastionClient(client, region)) {
            deadline.ensureRemaining();
            TargetNetwork target = resolveTargetNetwork(
                    client, instanceId, request.compartmentId(), region);
            boolean managedSsh = credentials.loginType() == 1;
            if (managedSsh) {
                validateManagedSshPrerequisites(client, target, region, deadline);
            }

            Subnet bastionSubnet = resolveBastionSubnet(client, target, deadline);

            Bastion bastion = getOrCreateBastion(
                    bastionClient,
                    target.compartmentId(),
                    bastionSubnet.getId(),
                    target.vcnId(),
                    deadline);
            validateTargetIngress(client, target, bastion, deadline);
            KeyMaterial key = managedSsh
                    ? loadKeyMaterial(credentials.privateKey(), credentials.passphrase())
                    : generateKeyMaterial();
            PreparedSession prepared = createBastionSession(
                    bastionClient,
                    bastion,
                    target,
                    credentials.username(),
                    key.publicKey(),
                    managedSsh,
                    deadline);
            createdSessionId = prepared.session().getId();

            long expiresAt = System.currentTimeMillis()
                    + Math.max(60, Math.min(prepareLeaseSeconds, 900)) * 1000L;
            BastionConnectionSpec spec = new BastionConnectionSpec(
                    tenantId,
                    region,
                    createdSessionId,
                    prepared.host(),
                    prepared.port(),
                    prepared.user(),
                    key.privateKey(),
                    target.privateIp(),
                    target.port(),
                    credentials.username(),
                    credentials.loginType(),
                    credentials.password(),
                    credentials.privateKey(),
                    credentials.passphrase(),
                    instanceId,
                    expiresAt,
                    managedSsh,
                    prepared.hostKeyInfo());
            String token = registerLease(ownerAccount, spec);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", token);
            data.put("expiresAt", expiresAt);
            data.put("targetPrivateIp", target.privateIp());
            data.put("targetUsername", credentials.username());
            data.put("bastionName", bastion.getName());
            data.put("sessionTtlInSeconds", effectiveSessionTtlSeconds());
            data.put("sessionType", managedSsh ? "MANAGED_SSH" : "PORT_FORWARDING");
            return data;
        } catch (RuntimeException e) {
            if (createdSessionId != null) {
                deleteRemoteSession(tenantId, region, createdSessionId);
            }
            throw e;
        } catch (Exception e) {
            if (createdSessionId != null) {
                deleteRemoteSession(tenantId, region, createdSessionId);
            }
            log.warn("Bastion prepare failed for instance {}: {}", instanceId, e.getMessage());
            throw new OciException("OCI Bastion session could not be prepared");
        }
    }

    /** Claim a token exactly once when the dedicated Bastion WebSocket starts. */
    public BastionLease claim(String token, String ownerAccount) {
        if (!hasText(token)) {
            throw new IllegalArgumentException("Bastion connection token is required");
        }
        String normalizedToken = token.trim();
        String normalizedOwner = requireOwner(ownerAccount);
        Lease lease = pending.get(normalizedToken);
        if (lease == null) {
            throw new IllegalArgumentException("Bastion connection token has expired");
        }
        if (!Objects.equals(lease.ownerAccount(), normalizedOwner)) {
            throw new IllegalArgumentException("Bastion connection token does not belong to this account");
        }
        if (lease.expired()) {
            if (pending.remove(normalizedToken, lease)) {
                deleteRemoteSession(lease.spec().tenantId(), lease.spec().region(), lease.spec().sessionId());
            }
            throw new IllegalArgumentException("Bastion connection token has expired");
        }
        if (!pending.remove(normalizedToken, lease)) {
            throw new IllegalArgumentException("Bastion connection token has expired");
        }
        active.put(lease.token(), lease);
        return new BastionLease(lease.token(), lease.spec());
    }

    /** Idempotently releases the OCI session associated with a claimed token. */
    public void release(BastionLease lease) {
        if (lease == null || !lease.released().compareAndSet(false, true)) {
            return;
        }
        active.remove(lease.token());
        deleteRemoteSession(lease.spec().tenantId(), lease.spec().region(), lease.spec().sessionId());
    }

    private BastionClient buildBastionClient(OciClientService client, String region) {
        BastionClient.Builder builder = BastionClient.builder()
                .configuration(client.getClientConfiguration());
        if (client.getOciClientConfigurator() != null) {
            builder.additionalClientConfigurator(client.getOciClientConfigurator());
        }
        BastionClient bastion = builder.build(client.getProvider());
        bastion.setRegion(region);
        return bastion;
    }

    private TargetNetwork resolveTargetNetwork(OciClientService client, String instanceId,
                                               String requestedCompartmentId, String region) {
        Instance instance = client.getComputeClient().getInstance(
                GetInstanceRequest.builder().instanceId(instanceId).build()).getInstance();
        if (instance == null) {
            throw new OciException("Target instance was not found");
        }
        if (instance.getLifecycleState() != Instance.LifecycleState.Running) {
            throw new OciException(409, "Target instance must be RUNNING before starting Bastion SSH");
        }
        String compartmentId = requireIdentifier(instance.getCompartmentId(), "instance compartment id");
        String requestedCompartment = trimToNull(requestedCompartmentId);
        if (requestedCompartment != null && !requestedCompartment.equals(compartmentId)) {
            throw new OciException("Target instance compartment does not match the request");
        }
        List<VnicAttachment> attachments = client.getComputeClient().listVnicAttachments(
                ListVnicAttachmentsRequest.builder()
                        .compartmentId(compartmentId)
                        .instanceId(instanceId)
                        .limit(1000)
                        .build()).getItems();
        if (attachments == null || attachments.isEmpty()) {
            throw new OciException("Target instance has no VNIC");
        }
        VnicAttachment attachment = attachments.get(0);
        Vnic primary = null;
        for (VnicAttachment candidate : attachments) {
            if (candidate.getVnicId() == null) continue;
            Vnic vnic = client.getVirtualNetworkClient().getVnic(
                    GetVnicRequest.builder().vnicId(candidate.getVnicId()).build()).getVnic();
            if (vnic == null) continue;
            if (primary == null) primary = vnic;
            if (Boolean.TRUE.equals(vnic.getIsPrimary())) {
                attachment = candidate;
                primary = vnic;
                break;
            }
        }
        if (primary == null) {
            throw new OciException("Target VNIC could not be read");
        }
        String subnetId = firstNonBlank(primary.getSubnetId(), attachment.getSubnetId());
        String privateIp = primary.getPrivateIp();
        if (!hasText(subnetId) || !hasText(privateIp)) {
            throw new OciException("Target instance has no private IP or subnet");
        }
        Subnet subnet = client.getVirtualNetworkClient().getSubnet(
                GetSubnetRequest.builder().subnetId(subnetId).build()).getSubnet();
        if (subnet == null || subnet.getLifecycleState() != Subnet.LifecycleState.Available) {
            throw new OciException("Target subnet is not available");
        }
        String instanceAvailabilityDomain = trimToNull(instance.getAvailabilityDomain());
        String subnetAvailabilityDomain = trimToNull(subnet.getAvailabilityDomain());
        if (!availabilityDomainCompatible(instanceAvailabilityDomain, subnetAvailabilityDomain)) {
            throw new OciException(422,
                    "Target instance availability domain " + instanceAvailabilityDomain
                            + " does not match target subnet availability domain "
                            + subnetAvailabilityDomain);
        }
        return new TargetNetwork(
                instanceId,
                region,
                compartmentId,
                subnetId,
                subnet.getVcnId(),
                privateIp,
                22,
                instance,
                subnet,
                subnet.getCidrBlock(),
                subnet.getSecurityListIds() == null ? List.of() : List.copyOf(subnet.getSecurityListIds()),
                primary.getNsgIds() == null ? List.of() : List.copyOf(primary.getNsgIds()));
    }

    private void validateManagedSshPrerequisites(OciClientService client, TargetNetwork target,
                                                 String region, PrepareDeadline deadline) {
        validateManagedOperatingSystem(client, target, deadline);
        if (requireCloudAgent && !isBastionPluginEnabled(
                target.instance().getAgentConfig(), cloudAgentPluginName)) {
            throw new OciException(422,
                    "Oracle Cloud Agent Bastion plugin is not enabled on the target instance");
        }
        if (!subnetHasGatewayRoute(client, target.subnet(), deadline)) {
            throw new OciException(422,
                    "Managed SSH requires the target subnet route table to use a service, NAT, "
                            + "or internet gateway, as required by OCI Bastion");
        }
        if (!requireCloudAgent) return;

        deadline.ensureRemaining();
        try (PluginClient pluginClient = buildPluginClient(client, region)) {
            InstanceAgentPlugin plugin = pluginClient.getInstanceAgentPlugin(
                    GetInstanceAgentPluginRequest.builder()
                            .compartmentId(target.compartmentId())
                            .instanceagentId(target.instanceId())
                            .pluginName(firstNonBlank(cloudAgentPluginName, "Bastion"))
                            .build()).getInstanceAgentPlugin();
            if (plugin == null || plugin.getStatus() != InstanceAgentPlugin.Status.Running) {
                String status = plugin == null || plugin.getStatus() == null
                        ? "UNKNOWN" : plugin.getStatus().getValue();
                String detail = plugin == null ? null : trimToNull(plugin.getMessage());
                throw new OciException(422,
                        "Oracle Cloud Agent Bastion plugin must be RUNNING; current status is "
                                + status + (detail == null ? "" : ": " + detail));
            }
        } catch (BmcException e) {
            throw new OciException(422,
                    "Unable to verify the Oracle Cloud Agent Bastion plugin. Ensure OCI IAM grants "
                            + "read instance-agent-plugins: " + OciBmcErrorTranslator.translate(e));
        }
    }

    private void validateManagedOperatingSystem(OciClientService client, TargetNetwork target,
                                               PrepareDeadline deadline) {
        InstanceSourceDetails source = target.instance().getSourceDetails();
        if (!(source instanceof InstanceSourceViaImageDetails imageSource)
                || !hasText(imageSource.getImageId())) {
            return;
        }
        deadline.ensureRemaining();
        Image image = client.getComputeClient().getImage(
                GetImageRequest.builder().imageId(imageSource.getImageId()).build()).getImage();
        String operatingSystem = image == null ? null : trimToNull(image.getOperatingSystem());
        if (operatingSystem != null && !operatingSystem.toLowerCase(Locale.ROOT).contains("linux")) {
            throw new OciException(422,
                    "OCI Managed SSH sessions require a Linux target instance; detected "
                            + operatingSystem);
        }
    }

    private PluginClient buildPluginClient(OciClientService client, String region) {
        PluginClient.Builder builder = PluginClient.builder()
                .configuration(client.getClientConfiguration());
        if (client.getOciClientConfigurator() != null) {
            builder.additionalClientConfigurator(client.getOciClientConfigurator());
        }
        PluginClient pluginClient = builder.build(client.getProvider());
        pluginClient.setRegion(region);
        return pluginClient;
    }

    static boolean isBastionPluginEnabled(InstanceAgentConfig agent, String pluginName) {
        if (agent == null
                || Boolean.TRUE.equals(agent.getAreAllPluginsDisabled())
                || Boolean.TRUE.equals(agent.getIsManagementDisabled())) {
            return false;
        }
        String expected = firstNonBlank(pluginName, "Bastion");
        List<InstanceAgentPluginConfigDetails> plugins = agent.getPluginsConfig();
        if (plugins == null) return true;
        for (InstanceAgentPluginConfigDetails plugin : plugins) {
            if (plugin == null || !expected.equalsIgnoreCase(firstNonBlank(plugin.getName()))) continue;
            return plugin.getDesiredState() != InstanceAgentPluginConfigDetails.DesiredState.Disabled;
        }
        // OCI enables individual agent plugins by default unless an override disables them.
        return true;
    }

    private Subnet resolveBastionSubnet(OciClientService client, TargetNetwork target,
                                        PrepareDeadline deadline) {
        Map<String, Subnet> privateCandidatesById = new LinkedHashMap<>();
        if (isPrivateSubnet(target.subnet())) {
            privateCandidatesById.put(target.subnet().getId(), target.subnet());
        }

        for (String compartmentId : bastionSubnetSearchCompartments(client, target, deadline)) {
            String page = null;
            do {
                deadline.ensureRemaining();
                try {
                    var response = client.getVirtualNetworkClient().listSubnets(ListSubnetsRequest.builder()
                            .compartmentId(compartmentId)
                            .vcnId(target.vcnId())
                            .lifecycleState(Subnet.LifecycleState.Available)
                            .limit(1000)
                            .page(page)
                            .build());
                    for (Subnet subnet : response.getItems() == null ? List.<Subnet>of() : response.getItems()) {
                        if (subnet != null && hasText(subnet.getId()) && isPrivateSubnet(subnet)) {
                            privateCandidatesById.putIfAbsent(subnet.getId(), subnet);
                        }
                    }
                    page = response.getOpcNextPage();
                } catch (BmcException e) {
                    log.debug("Skipping inaccessible compartment {} while locating Bastion subnet: {}",
                            compartmentId, OciBmcErrorTranslator.translate(e));
                    page = null;
                }
            } while (hasText(page));
        }

        List<Subnet> privateCandidates = new ArrayList<>(privateCandidatesById.values());
        String instanceAvailabilityDomain = trimToNull(target.instance().getAvailabilityDomain());
        List<Subnet> candidates = new ArrayList<>(privateCandidates.stream()
                .filter(subnet -> availabilityDomainCompatible(
                        instanceAvailabilityDomain, subnet.getAvailabilityDomain()))
                .toList());
        candidates.sort(Comparator
                .comparing((Subnet subnet) -> !target.subnetId().equals(subnet.getId()))
                .thenComparing(subnet -> firstNonBlank(subnet.getDisplayName(), subnet.getId())));
        if (candidates.isEmpty()) {
            if (!privateCandidates.isEmpty()) {
                throw new OciException(422,
                        "No private subnet in VCN " + target.vcnId()
                                + " is compatible with target instance availability domain "
                                + firstNonBlank(instanceAvailabilityDomain, "unspecified")
                                + ". Regional subnets are compatible; an AD-specific subnet must use the same AD.");
            }
            throw new OciException(422,
                    "目标 VCN 中没有可用的私有子网。OCI 官方要求 Bastion 与目标资源位于同一 VCN，"
                            + "并选择一个可访问目标子网的私有子网。VCN: " + target.vcnId());
        }
        for (Subnet candidate : candidates) {
            if (subnetHasBastionServiceGatewayRoute(client, candidate, deadline)) return candidate;
        }
        throw new OciException(422,
                "已找到同一 VCN 的私有子网，但其路由表没有指向可用 Service Gateway 的 "
                        + "Service CIDR 路由。OCI 官方要求创建 Bastion 前配置 Service Gateway 及对应路由。");
    }

    private List<String> bastionSubnetSearchCompartments(OciClientService client,
                                                           TargetNetwork target,
                                                           PrepareDeadline deadline) {
        LinkedHashSet<String> compartmentIds = new LinkedHashSet<>();
        addIfPresent(compartmentIds, target.subnet().getCompartmentId());
        addIfPresent(compartmentIds, target.compartmentId());
        if (client.getProvider() != null) {
            addIfPresent(compartmentIds, client.getProvider().getTenantId());
        }
        try {
            for (var compartment : client.listAllCompartmentsStrict(deadline.deadlineNanos())) {
                if (compartment != null) addIfPresent(compartmentIds, compartment.getId());
            }
        } catch (RuntimeException e) {
            deadline.ensureRemaining();
            log.debug("Unable to enumerate every compartment while locating a Bastion subnet: {}",
                    e.getMessage());
        }
        return List.copyOf(compartmentIds);
    }

    private static void addIfPresent(Set<String> values, String value) {
        String normalized = trimToNull(value);
        if (normalized != null) values.add(normalized);
    }

    static boolean isPrivateSubnet(Subnet subnet) {
        return subnet != null
                && subnet.getLifecycleState() == Subnet.LifecycleState.Available
                && Boolean.TRUE.equals(subnet.getProhibitPublicIpOnVnic());
    }

    static boolean availabilityDomainCompatible(String instanceAvailabilityDomain,
                                                 String subnetAvailabilityDomain) {
        String instanceAd = trimToNull(instanceAvailabilityDomain);
        String subnetAd = trimToNull(subnetAvailabilityDomain);
        return instanceAd == null || subnetAd == null || instanceAd.equals(subnetAd);
    }

    private boolean subnetHasGatewayRoute(OciClientService client, Subnet subnet,
                                          PrepareDeadline deadline) {
        if (subnet == null || !hasText(subnet.getRouteTableId())) return false;
        deadline.ensureRemaining();
        RouteTable routeTable = client.getVirtualNetworkClient().getRouteTable(
                GetRouteTableRequest.builder().rtId(subnet.getRouteTableId()).build()).getRouteTable();
        return hasUsableGatewayRoute(routeTable);
    }

    private boolean subnetHasBastionServiceGatewayRoute(OciClientService client, Subnet subnet,
                                                         PrepareDeadline deadline) {
        if (subnet == null || !hasText(subnet.getRouteTableId())) return false;
        try {
            deadline.ensureRemaining();
            RouteTable routeTable = client.getVirtualNetworkClient().getRouteTable(
                    GetRouteTableRequest.builder().rtId(subnet.getRouteTableId()).build()).getRouteTable();
            if (!hasBastionServiceGatewayRoute(routeTable)) return false;
            for (RouteRule rule : routeTable.getRouteRules()) {
                if (!isServiceGatewayRoute(rule)) continue;
                deadline.ensureRemaining();
                ServiceGateway gateway = client.getVirtualNetworkClient().getServiceGateway(
                        GetServiceGatewayRequest.builder()
                                .serviceGatewayId(rule.getNetworkEntityId())
                                .build()).getServiceGateway();
                if (gateway != null
                        && gateway.getLifecycleState() == ServiceGateway.LifecycleState.Available
                        && Objects.equals(subnet.getVcnId(), gateway.getVcnId())) {
                    return true;
                }
            }
            return false;
        } catch (BmcException e) {
            log.debug("Skipping Bastion subnet {} because its route table is not readable: {}",
                    subnet.getId(), OciBmcErrorTranslator.translate(e));
            return false;
        }
    }

    static boolean hasBastionServiceGatewayRoute(RouteTable routeTable) {
        if (routeTable == null || routeTable.getLifecycleState() != RouteTable.LifecycleState.Available
                || routeTable.getRouteRules() == null) {
            return false;
        }
        return routeTable.getRouteRules().stream().anyMatch(BastionService::isServiceGatewayRoute);
    }

    static boolean hasUsableGatewayRoute(RouteTable routeTable) {
        if (routeTable == null || routeTable.getLifecycleState() != RouteTable.LifecycleState.Available
                || routeTable.getRouteRules() == null) {
            return false;
        }
        return routeTable.getRouteRules().stream().anyMatch(BastionService::isUsableGatewayRoute);
    }

    private static boolean isUsableGatewayRoute(RouteRule rule) {
        if (rule == null || !hasText(rule.getNetworkEntityId())) return false;
        if (isServiceGatewayRoute(rule)) return true;
        String entity = rule.getNetworkEntityId().toLowerCase(Locale.ROOT);
        if (entity.startsWith("ocid1.natgateway.") || entity.startsWith("ocid1.internetgateway.")) {
            return rule.getDestinationType() == RouteRule.DestinationType.CidrBlock
                    && "0.0.0.0/0".equals(rule.getDestination());
        }
        return false;
    }

    private static boolean isServiceGatewayRoute(RouteRule rule) {
        return rule != null
                && hasText(rule.getNetworkEntityId())
                && rule.getNetworkEntityId().toLowerCase(Locale.ROOT)
                        .startsWith("ocid1.servicegateway.")
                && rule.getDestinationType() == RouteRule.DestinationType.ServiceCidrBlock;
    }

    private void validateTargetIngress(OciClientService client, TargetNetwork target,
                                       Bastion bastion, PrepareDeadline deadline) {
        if (!validateTargetIngress) return;
        String sourceCidr = bastionSourceCidr(bastion);
        for (String securityListId : target.securityListIds()) {
            if (!hasText(securityListId)) continue;
            deadline.ensureRemaining();
            SecurityList securityList = client.getVirtualNetworkClient().getSecurityList(
                    GetSecurityListRequest.builder().securityListId(securityListId).build())
                    .getSecurityList();
            if (securityList != null && securityList.getIngressSecurityRules() != null
                    && securityList.getIngressSecurityRules().stream()
                    .anyMatch(rule -> allowsTargetPort(rule, sourceCidr, target.port()))) {
                return;
            }
        }
        for (String nsgId : target.nsgIds()) {
            if (!hasText(nsgId)) continue;
            String page = null;
            do {
                deadline.ensureRemaining();
                var response = client.getVirtualNetworkClient().listNetworkSecurityGroupSecurityRules(
                        ListNetworkSecurityGroupSecurityRulesRequest.builder()
                                .networkSecurityGroupId(nsgId)
                                .direction(ListNetworkSecurityGroupSecurityRulesRequest.Direction.Ingress)
                                .limit(1000)
                                .page(page)
                                .build());
                if (response.getItems() != null && response.getItems().stream()
                        .anyMatch(rule -> allowsTargetPort(rule, sourceCidr, target.port()))) {
                    return;
                }
                page = response.getOpcNextPage();
            } while (hasText(page));
        }
        throw new OciException(422,
                "Target subnet or NSG does not allow TCP " + target.port()
                        + " from the OCI Bastion private endpoint " + sourceCidr);
    }

    static String bastionSourceCidr(Bastion bastion) {
        String address = bastion == null ? null : trimToNull(bastion.getPrivateEndpointIpAddress());
        if (address == null || address.contains("/")) {
            throw new OciException(422, "OCI Bastion did not return a valid private endpoint IP");
        }
        String sourceCidr = address + (address.contains(":") ? "/128" : "/32");
        if (parseCidr(sourceCidr) == null) {
            throw new OciException(422, "OCI Bastion returned an invalid private endpoint IP");
        }
        return sourceCidr;
    }

    static boolean allowsTargetPort(IngressSecurityRule rule, String sourceCidr, int targetPort) {
        if (rule == null || rule.getSourceType() == IngressSecurityRule.SourceType.ServiceCidrBlock) {
            return false;
        }
        return allowsProtocolAndPort(rule.getProtocol(), rule.getTcpOptions(), targetPort)
                && cidrContains(rule.getSource(), sourceCidr);
    }

    static boolean allowsTargetPort(SecurityRule rule, String sourceCidr, int targetPort) {
        if (rule == null || rule.getDirection() != SecurityRule.Direction.Ingress
                || Boolean.FALSE.equals(rule.getIsValid())
                || rule.getSourceType() != SecurityRule.SourceType.CidrBlock) {
            return false;
        }
        return allowsProtocolAndPort(rule.getProtocol(), rule.getTcpOptions(), targetPort)
                && cidrContains(rule.getSource(), sourceCidr);
    }

    private static boolean allowsProtocolAndPort(String protocol, TcpOptions tcp, int targetPort) {
        if ("all".equalsIgnoreCase(protocol)) return true;
        if (!SSH_PROTOCOL.equals(protocol)) return false;
        PortRange range = tcp == null ? null : tcp.getDestinationPortRange();
        if (range == null) return true;
        int min = range.getMin() == null ? 1 : range.getMin();
        int max = range.getMax() == null ? 65_535 : range.getMax();
        return targetPort >= min && targetPort <= max;
    }

    private Bastion getOrCreateBastion(BastionClient client, String compartmentId, String subnetId,
                                       String vcnId, PrepareDeadline deadline) {
        List<String> allowList = parseAllowList(clientCidrBlockAllowList);
        String lockKey = compartmentId + '|' + subnetId;
        Object lock = bastionLocks.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (lock) {
            deadline.ensureRemaining();
            Bastion existing = findReusableBastion(
                    client, compartmentId, subnetId, vcnId, allowList, deadline);
            if (existing != null) {
                return existing;
            }

            CreateBastionDetails details = CreateBastionDetails.builder()
                    .bastionType("STANDARD")
                    .name(newBastionName())
                    .compartmentId(compartmentId)
                    .targetSubnetId(subnetId)
                    .clientCidrBlockAllowList(allowList)
                    .maxSessionTtlInSeconds(effectiveSessionTtlSeconds())
                    .freeformTags(Map.of(
                            "managed-by", MANAGED_BY_TAG,
                            "purpose", "bastion-ssh"))
                    .build();
            deadline.ensureRemaining();
            CreateBastionResponse response = client.createBastion(CreateBastionRequest.builder()
                    .createBastionDetails(details)
                    .opcRetryToken(newToken())
                    .build());
            String id = response.getBastion() == null ? null : response.getBastion().getId();
            if (!hasText(id)) {
                id = idFromLocation(response.getLocation());
            }
            if (!hasText(id)) {
                id = waitForWorkRequestResource(client, response.getOpcWorkRequestId(), deadline);
            }
            if (!hasText(id)) {
                throw new OciException("OCI Bastion creation did not return an id");
            }
            return waitForBastion(client, id, deadline);
        }
    }

    private Bastion findReusableBastion(BastionClient client, String compartmentId,
                                         String subnetId, String vcnId, List<String> allowList,
                                         PrepareDeadline deadline) {
        String page = null;
        do {
            deadline.ensureRemaining();
            var response = client.listBastions(ListBastionsRequest.builder()
                    .compartmentId(compartmentId)
                    .bastionLifecycleState(BastionLifecycleState.Active)
                    .limit(1000)
                    .page(page)
                    .build());
            for (BastionSummary summary : response.getItems() == null
                    ? List.<BastionSummary>of() : response.getItems()) {
                if (!subnetId.equals(summary.getTargetSubnetId())) continue;
                if (hasText(vcnId) && !vcnId.equals(summary.getTargetVcnId())) continue;
                if (!isOciworkerManaged(summary.getFreeformTags())) continue;
                deadline.ensureRemaining();
                Bastion existing = client.getBastion(GetBastionRequest.builder()
                        .bastionId(summary.getId()).build()).getBastion();
                if (isReusableBastion(existing, subnetId, vcnId, allowList)) return existing;
            }
            page = response.getOpcNextPage();
        } while (hasText(page));
        return null;
    }

    private boolean isReusableBastion(Bastion bastion, String subnetId, String vcnId,
                                      List<String> allowList) {
        if (bastion == null || bastion.getLifecycleState() != BastionLifecycleState.Active) return false;
        if (!subnetId.equals(bastion.getTargetSubnetId())) return false;
        if (hasText(vcnId) && !vcnId.equals(bastion.getTargetVcnId())) return false;
        if (!isOciworkerManaged(bastion.getFreeformTags())) return false;
        if (!normalizedCidrSet(allowList).equals(
                normalizedCidrSet(bastion.getClientCidrBlockAllowList()))) {
            return false;
        }
        if (bastion.getMaxSessionTtlInSeconds() == null
                || bastion.getMaxSessionTtlInSeconds() < effectiveSessionTtlSeconds()) {
            return false;
        }
        return bastion.getMaxSessionsAllowed() == null || bastion.getMaxSessionsAllowed() > 0;
    }

    private static boolean isOciworkerManaged(Map<String, String> tags) {
        return tags != null && MANAGED_BY_TAG.equals(tags.get("managed-by"));
    }

    private static Set<String> normalizedCidrSet(List<String> values) {
        if (values == null) return Set.of();
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (hasText(value)) normalized.add(value.trim());
        }
        return Set.copyOf(normalized);
    }

    static String newBastionName() {
        return "ociworkerbastion"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private Bastion waitForBastion(BastionClient client, String id, PrepareDeadline deadline) {
        long waitDeadline = deadline.operationDeadlineNanos(operationTimeoutSeconds);
        while (System.nanoTime() < waitDeadline) {
            deadline.ensureRemaining();
            Bastion current = client.getBastion(GetBastionRequest.builder().bastionId(id).build()).getBastion();
            if (current != null && current.getLifecycleState() == BastionLifecycleState.Active) {
                return current;
            }
            if (current != null && current.getLifecycleState() == BastionLifecycleState.Failed) {
                throw new OciException("OCI Bastion creation failed: " + current.getLifecycleDetails());
            }
            sleepPoll();
        }
        throw new OciException("Timed out waiting for OCI Bastion");
    }

    private PreparedSession createBastionSession(BastionClient client, Bastion bastion,
                                                 TargetNetwork target, String username,
                                                 String publicKey, boolean managedSsh,
                                                 PrepareDeadline deadline) {
        CreateSessionTargetResourceDetails targetDetails = managedSsh
                ? CreateManagedSshSessionTargetResourceDetails.builder()
                    .targetResourceOperatingSystemUserName(username)
                    .targetResourceId(target.instanceId())
                    .targetResourcePrivateIpAddress(target.privateIp())
                    .targetResourcePort(target.port())
                    .build()
                : CreatePortForwardingSessionTargetResourceDetails.builder()
                    .targetResourceId(target.instanceId())
                    .targetResourcePrivateIpAddress(target.privateIp())
                    .targetResourcePort(target.port())
                    .build();
        CreateSessionDetails details = CreateSessionDetails.builder()
                .displayName((managedSsh ? "ociworker-managed-" : "ociworker-forward-")
                        + target.privateIp())
                .bastionId(bastion.getId())
                .targetResourceDetails(targetDetails)
                .keyType(CreateSessionDetails.KeyType.Pub)
                .keyDetails(PublicKeyDetails.builder().publicKeyContent(publicKey).build())
                .sessionTtlInSeconds(effectiveSessionTtlSeconds())
                .build();
        String id = null;
        try {
            deadline.ensureRemaining();
            CreateSessionResponse response = client.createSession(CreateSessionRequest.builder()
                    .createSessionDetails(details)
                    .opcRetryToken(newToken())
                    .build());
            Session initial = response.getSession();
            id = initial == null ? null : initial.getId();
            if (!hasText(id)) {
                id = idFromLocation(response.getLocation());
            }
            if (!hasText(id)) {
                id = waitForWorkRequestResource(client, response.getOpcWorkRequestId(), deadline);
            }
            if (!hasText(id)) {
                throw new OciException("OCI Bastion session did not return an id");
            }
            Session activeSession = waitForSession(client, id, deadline);
            Endpoint endpoint = resolveEndpoint(activeSession, target.region());
            String hostKeyInfo = trimToNull(activeSession.getBastionPublicHostKeyInfo());
            if (hostKeyInfo == null) {
                throw new OciException("OCI Bastion session did not return its host key");
            }
            return new PreparedSession(
                    activeSession, endpoint.host(), endpoint.port(), endpoint.user(), hostKeyInfo);
        } catch (RuntimeException e) {
            deleteRemoteSession(client, id);
            throw e;
        }
    }

    private Session waitForSession(BastionClient client, String id, PrepareDeadline deadline) {
        long waitDeadline = deadline.operationDeadlineNanos(operationTimeoutSeconds);
        while (System.nanoTime() < waitDeadline) {
            deadline.ensureRemaining();
            Session current = client.getSession(GetSessionRequest.builder().sessionId(id).build()).getSession();
            if (current != null && current.getLifecycleState() == SessionLifecycleState.Active) {
                return current;
            }
            if (current != null && current.getLifecycleState() == SessionLifecycleState.Failed) {
                throw new OciException("OCI Bastion SSH session failed: " + current.getLifecycleDetails());
            }
            sleepPoll();
        }
        throw new OciException("Timed out waiting for OCI Bastion SSH session");
    }

    private String waitForWorkRequestResource(BastionClient client, String workRequestId,
                                              PrepareDeadline deadline) {
        if (!hasText(workRequestId)) return null;
        long waitDeadline = deadline.operationDeadlineNanos(operationTimeoutSeconds);
        while (System.nanoTime() < waitDeadline) {
            deadline.ensureRemaining();
            GetWorkRequestResponse response = client.getWorkRequest(
                    GetWorkRequestRequest.builder().workRequestId(workRequestId).build());
            WorkRequest workRequest = response == null ? null : response.getWorkRequest();
            if (workRequest != null) {
                OperationStatus status = workRequest.getStatus();
                if (status == OperationStatus.Failed || status == OperationStatus.Canceled) {
                    throw new OciException("OCI Bastion work request failed: " + status);
                }
                if (workRequest.getResources() != null) {
                    for (WorkRequestResource resource : workRequest.getResources()) {
                        if (resource == null) continue;
                        String identifier = firstNonBlank(resource.getIdentifier(),
                                idFromLocation(resource.getEntityUri()));
                        if (hasText(identifier)) return identifier;
                    }
                }
                if (status == OperationStatus.Succeeded) return null;
            }
            sleepPoll();
        }
        throw new OciException("Timed out waiting for OCI Bastion work request");
    }

    static Endpoint resolveEndpoint(Session session, String region) {
        Map<String, String> metadata = session.getSshMetadata() == null
                ? Collections.emptyMap() : session.getSshMetadata();
        String command = firstNonBlank(metadata.get("command"), metadata.get("sshCommand"));
        String host = firstNonBlank(metadata.get("host"), metadata.get("hostname"),
                metadata.get("bastionHost"), metadata.get("bastionHostname"));
        String user = firstNonBlank(metadata.get("user"), metadata.get("username"),
                metadata.get("bastionUser"), metadata.get("bastionUserName"), session.getBastionUserName());
        int port = parsePort(firstNonBlank(metadata.get("port"), metadata.get("bastionPort")), 22);

        if (hasText(command)) {
            Matcher hostMatcher = COMMAND_HOST.matcher(command);
            while (hostMatcher.find()) {
                String candidate = trimEndpoint(hostMatcher.group(1));
                if (candidate.contains("bastion.")) {
                    host = candidate;
                    break;
                }
            }
            // OCI's command is authoritative: metadata can expose the target OS
            // user instead of the temporary Bastion session user.
            Matcher userMatcher = COMMAND_USER.matcher(command);
            if (userMatcher.find()) user = trimEndpoint(userMatcher.group(1));
            Matcher portMatcher = COMMAND_PORT.matcher(command);
            if (portMatcher.find()) port = parsePort(portMatcher.group(1), port);
        }
        if (!hasText(host)) {
            host = "host.bastion." + region + ".oci.oraclecloud.com";
        }
        if (!hasText(user)) {
            user = session.getId();
        }
        validateEndpoint(host, user, port);
        return new Endpoint(host, user, port);
    }

    void deleteRemoteSession(String tenantId, String region, String sessionId) {
        if (!hasText(tenantId) || !hasText(region) || !hasText(sessionId)) return;
        try {
            OciUser user = userMapper.selectById(tenantId);
            if (user == null) return;
            try (OciClientService client = new OciClientService(toSysUser(user, region), region);
                 BastionClient bastion = buildBastionClient(client, region)) {
                bastion.deleteSession(DeleteSessionRequest.builder().sessionId(sessionId).build());
            }
        } catch (Exception e) {
            log.debug("Bastion session cleanup failed for {}: {}", sessionId, e.getMessage());
        }
    }

    private static void deleteRemoteSession(BastionClient client, String sessionId) {
        if (client == null || !hasText(sessionId)) return;
        try {
            client.deleteSession(DeleteSessionRequest.builder().sessionId(sessionId).build());
        } catch (Exception e) {
            log.debug("Bastion session cleanup failed for {}: {}", sessionId, e.getMessage());
        }
    }

    private ResolvedCredentials resolveCredentials(BastionPrepareRequest request, SavedCredentials saved) {
        String mode = normalizeLoginMode(firstNonBlank(request.loginMode(), saved.loginMode()));
        String username = firstNonBlank(request.username(), saved.username(), "root");
        validateUsername(username);
        if ("SSH_PUBLIC_KEY".equals(mode)) {
            String privateKey = secretToNull(request.privateKey(), true);
            if (!hasText(privateKey)) {
                throw new OciException(422, "SSH public-key mode requires a private key");
            }
            validateSecret(privateKey, "SSH private key");
            String passphrase = secretToNull(request.passphrase(), false);
            if (hasSecret(passphrase)) validateSecret(passphrase, "SSH key passphrase");
            return new ResolvedCredentials(username, 1, null, privateKey, passphrase);
        }
        String password = firstSecret(request.password(), saved.password());
        if (!hasSecret(password)) {
            throw new OciException(422, "SSH password is required");
        }
        validateSecret(password, "SSH password");
        return new ResolvedCredentials(username, 0, password, null, null);
    }

    private SavedCredentials findSavedCredentials(String tenantId, String instanceId) {
        try {
            List<OciCreateTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<OciCreateTask>()
                    .eq(OciCreateTask::getUserId, tenantId)
                    .orderByDesc(OciCreateTask::getCreateTime));
            if (tasks == null) return new SavedCredentials("root", "PASSWORD", null, null);
            for (OciCreateTask task : tasks) {
                for (Map<String, Object> item : parseCreatedInstances(task.getCreatedInstances())) {
                    if (!instanceId.equals(firstNonBlank(
                            asText(item.get("instanceId")), asText(item.get("instance_id"))))) continue;
                    String mode = normalizeLoginMode(firstNonBlank(
                            asText(item.get("loginMode")), asText(item.get("login_mode")),
                            task.getLoginMode()));
                    String username = firstNonBlank(
                            asText(item.get("loginUser")), asText(item.get("login_user")), "root");
                    String password = firstSecret(
                            asSecret(item.get("rootPassword")),
                            asSecret(item.get("password")),
                            asSecret(item.get("root_password")),
                            task.getRootPassword());
                    String publicKey = firstNonBlank(
                            asText(item.get("sshPublicKey")), asText(item.get("ssh_public_key")),
                            task.getSshPublicKey());
                    return new SavedCredentials(username, mode, password, publicKey);
                }
            }
        } catch (Exception e) {
            log.debug("Unable to resolve saved SSH credential for {}: {}", instanceId, e.getMessage());
        }
        return new SavedCredentials("root", "PASSWORD", null, null);
    }

    private static List<Map<String, Object>> parseCreatedInstances(String json) {
        if (!hasText(json)) return List.of();
        try {
            List<Map<String, Object>> list = JSON.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() { });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static SysUserDTO toSysUser(OciUser user, String region) {
        return SysUserDTO.builder()
                .username(user.getUsername())
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(user.getOciTenantId())
                        .userId(user.getOciUserId())
                        .fingerprint(user.getOciFingerprint())
                        .region(region)
                        .privateKeyPath(user.getOciKeyPath())
                        .build())
                .build();
    }

    private KeyMaterial generateKeyMaterial() throws JSchException {
        JSch jsch = new JSch();
        KeyPair pair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 2048);
        try {
            ByteArrayOutputStream privateOut = new ByteArrayOutputStream();
            pair.writePrivateKey(privateOut);
            ByteArrayOutputStream publicOut = new ByteArrayOutputStream();
            pair.writePublicKey(publicOut, "ociworker-bastion");
            return new KeyMaterial(
                    new String(privateOut.toByteArray(), StandardCharsets.UTF_8),
                    new String(publicOut.toByteArray(), StandardCharsets.UTF_8).trim());
        } finally {
            pair.dispose();
        }
    }

    private KeyMaterial loadKeyMaterial(String privateKey, String passphrase) {
        byte[] keyBytes = privateKey.getBytes(StandardCharsets.UTF_8);
        KeyPair pair = null;
        try {
            pair = KeyPair.load(new JSch(), keyBytes, null);
            if (pair == null || pair.getKeyType() == KeyPair.UNKNOWN) {
                throw new OciException(422, "SSH private key format is not supported");
            }
            if (pair.isEncrypted()) {
                if (!hasSecret(passphrase) || !pair.decrypt(passphrase)) {
                    throw new OciException(422, "SSH private key passphrase is incorrect");
                }
            }
            ByteArrayOutputStream publicOut = new ByteArrayOutputStream();
            pair.writePublicKey(publicOut, "ociworker-bastion");
            String publicKey = new String(publicOut.toByteArray(), StandardCharsets.UTF_8).trim();
            if (!hasText(publicKey)) {
                throw new OciException(422, "SSH public key could not be derived from the private key");
            }
            return new KeyMaterial(privateKey, publicKey);
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(422, "SSH private key could not be read");
        } finally {
            java.util.Arrays.fill(keyBytes, (byte) 0);
            if (pair != null) pair.dispose();
        }
    }

    private void expireLeases() {
        if (shuttingDown.get()) return;
        long now = System.currentTimeMillis();
        for (Lease lease : pending.values()) {
            if (lease.spec().expiresAtMillis() <= now && pending.remove(lease.token(), lease)) {
                release(new BastionLease(lease.token(), lease.spec()));
            }
        }
    }

    private PreparePermit acquirePreparePermit(String ownerAccount) {
        synchronized (prepareAdmissionLock) {
            int globalLimit = Math.max(1, maxConcurrentPrepares);
            int accountLimit = Math.max(1, maxConcurrentPreparesPerAccount);
            AtomicInteger ownerCount = activePreparesByOwner.computeIfAbsent(
                    ownerAccount, ignored -> new AtomicInteger());
            if (activePrepares.get() >= globalLimit || ownerCount.get() >= accountLimit) {
                if (ownerCount.get() == 0) activePreparesByOwner.remove(ownerAccount, ownerCount);
                throw new OciException(429, "Too many Bastion connection preparations are in progress");
            }
            activePrepares.incrementAndGet();
            ownerCount.incrementAndGet();
            return new PreparePermit(ownerAccount, ownerCount);
        }
    }

    private void releasePreparePermit(String ownerAccount, AtomicInteger ownerCount) {
        synchronized (prepareAdmissionLock) {
            if (ownerCount.decrementAndGet() <= 0) {
                activePreparesByOwner.remove(ownerAccount, ownerCount);
            }
            activePrepares.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    private void ensurePendingCapacity(String ownerAccount) {
        int globalLimit = Math.max(1, maxPendingLeases);
        int accountLimit = Math.max(1, maxPendingLeasesPerAccount);
        long ownerPending = pending.values().stream()
                .filter(lease -> ownerAccount.equals(lease.ownerAccount()))
                .count();
        if (pending.size() >= globalLimit || ownerPending >= accountLimit) {
            throw new OciException(429,
                    "Too many unclaimed Bastion sessions exist; close or claim an existing session first");
        }
    }

    String registerLease(String ownerAccount, BastionConnectionSpec spec) {
        String normalizedOwner = requireOwner(ownerAccount);
        Objects.requireNonNull(spec, "spec");
        synchronized (prepareAdmissionLock) {
            ensurePendingCapacity(normalizedOwner);
            String token;
            Lease lease;
            do {
                token = newToken();
                lease = new Lease(token, normalizedOwner, spec);
            } while (pending.putIfAbsent(token, lease) != null);
            return token;
        }
    }

    @PreDestroy
    void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return;
        expiryExecutor.shutdownNow();
        List<Lease> leases = new ArrayList<>();
        leases.addAll(pending.values());
        leases.addAll(active.values());
        pending.clear();
        active.clear();
        for (Lease lease : leases) {
            deleteRemoteSession(lease.spec().tenantId(), lease.spec().region(), lease.spec().sessionId());
        }
    }

    private int effectiveSessionTtlSeconds() {
        return normalizeSessionTtlSeconds(configuredSessionTtlSeconds);
    }

    static int normalizeSessionTtlSeconds(int configuredSeconds) {
        return Math.max(MIN_SESSION_TTL_SECONDS,
                Math.min(MAX_SESSION_TTL_SECONDS, configuredSeconds));
    }

    private static List<String> parseAllowList(String raw) {
        if (!hasText(raw)) {
            throw new OciException(422,
                    "OCI Bastion client CIDR allow-list is not configured; set "
                            + "OCI_BASTION_CLIENT_CIDR_ALLOW_LIST to this worker's public egress CIDR "
                            + "(for example 203.0.113.10/32), never 0.0.0.0/0");
        }
        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (value.isBlank()) continue;
            Cidr cidr = parseCidr(value);
            if (cidr == null || cidr.address().length != 4 || cidr.prefixLength() == 0) {
                throw new OciException(422, "OCI Bastion client CIDR is invalid or too broad: " + value);
            }
            if (!values.contains(value)) values.add(value);
        }
        if (values.isEmpty()) {
            throw new OciException(422,
                    "OCI Bastion client CIDR allow-list must contain at least one CIDR; set "
                            + "OCI_BASTION_CLIENT_CIDR_ALLOW_LIST explicitly");
        }
        if (values.size() > 20) {
            throw new OciException(422,
                    "OCI Bastion client CIDR allow-list cannot contain more than 20 entries");
        }
        return List.copyOf(values);
    }

    static boolean cidrContains(String containerValue, String memberValue) {
        Cidr container = parseCidr(containerValue);
        Cidr member = parseCidr(memberValue);
        if (container == null || member == null
                || container.address().length != member.address().length
                || container.prefixLength() > member.prefixLength()) {
            return false;
        }
        int wholeBytes = container.prefixLength() / 8;
        int remainingBits = container.prefixLength() % 8;
        for (int i = 0; i < wholeBytes; i++) {
            if (container.address()[i] != member.address()[i]) return false;
        }
        if (remainingBits == 0) return true;
        int mask = 0xff & (0xff << (8 - remainingBits));
        return (container.address()[wholeBytes] & mask) == (member.address()[wholeBytes] & mask);
    }

    private static Cidr parseCidr(String raw) {
        if (!hasText(raw)) return null;
        String value = raw.trim();
        int slash = value.lastIndexOf('/');
        if (slash <= 0 || slash + 1 >= value.length()) return null;
        String addressText = value.substring(0, slash);
        if (!addressText.matches("[0-9A-Fa-f:.]+")) return null;
        try {
            InetAddress address = InetAddress.getByName(addressText);
            byte[] bytes = address.getAddress();
            int prefix = Integer.parseInt(value.substring(slash + 1));
            if (prefix < 0 || prefix > bytes.length * 8) return null;
            return new Cidr(bytes, prefix);
        } catch (UnknownHostException | NumberFormatException e) {
            return null;
        }
    }

    private void sleepPoll() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OciException("OCI Bastion operation was interrupted");
        }
    }

    private static String idFromLocation(String location) {
        if (!hasText(location)) return null;
        String value = location.trim();
        int slash = value.lastIndexOf('/');
        return slash >= 0 && slash + 1 < value.length() ? value.substring(slash + 1) : value;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void validateEndpoint(String host, String user, int port) {
        String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (!hasText(host) || !host.matches("[A-Za-z0-9.-]{1,253}")
                || !normalizedHost.startsWith("host.bastion.")
                || host.chars().anyMatch(Character::isWhitespace)
                || host.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
            throw new OciException("OCI Bastion endpoint is invalid");
        }
        if (!hasText(user) || user.chars().anyMatch(Character::isWhitespace)) {
            throw new OciException("OCI Bastion session user is invalid");
        }
        if (port <= 0 || port > 65535) {
            throw new OciException("OCI Bastion endpoint port is invalid");
        }
    }

    private static int parsePort(String value, int fallback) {
        try {
            int port = Integer.parseInt(value);
            return port > 0 && port <= 65535 ? port : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String trimEndpoint(String value) {
        if (value == null) return null;
        return value.trim().replaceAll("[\\\"'`,;]+$", "");
    }

    private static String normalizeLoginMode(String value) {
        return "SSH_PUBLIC_KEY".equalsIgnoreCase(value)
                || "PUBLIC_KEY".equalsIgnoreCase(value)
                || "KEY".equalsIgnoreCase(value)
                ? "SSH_PUBLIC_KEY" : "PASSWORD";
    }

    private static void validateUsername(String username) {
        if (!username.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new OciException("SSH username is invalid");
        }
    }

    private static void validateSecret(String value, String label) {
        if (value.length() > MAX_SECRET_LENGTH || value.chars().anyMatch(ch -> ch == 0)) {
            throw new OciException(label + " is invalid");
        }
    }

    private static String requireIdentifier(String value, String label) {
        String normalized = trimToNull(value);
        if (normalized == null || normalized.length() > 256
                || normalized.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
            throw new OciException(label + " is required");
        }
        return normalized;
    }

    private static String requireOwner(String ownerAccount) {
        String normalized = trimToNull(ownerAccount);
        if (normalized == null || normalized.length() > 256
                || normalized.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
            throw new OciException("Authenticated panel account is required");
        }
        return normalized;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (hasText(value)) return value.trim();
        }
        return null;
    }

    private static String firstSecret(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (hasSecret(value)) return value;
        }
        return null;
    }

    private static String secretToNull(String value, boolean rejectBlank) {
        if (value == null || value.isEmpty()) return null;
        return rejectBlank && value.isBlank() ? null : value;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean hasSecret(String value) {
        return value != null && !value.isEmpty();
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static String asSecret(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    record BastionLease(String token, BastionConnectionSpec spec, AtomicBoolean released) {
        BastionLease(String token, BastionConnectionSpec spec) {
            this(token, spec, new AtomicBoolean(false));
        }
    }

    private record Lease(String token, String ownerAccount, BastionConnectionSpec spec) {
        boolean expired() {
            return spec.expiresAtMillis() <= System.currentTimeMillis();
        }
    }

    private record TargetNetwork(String instanceId, String region, String compartmentId,
                                 String subnetId, String vcnId, String privateIp, int port,
                                 Instance instance, Subnet subnet, String subnetCidr,
                                 List<String> securityListIds, List<String> nsgIds) {
    }

    private record PreparedSession(Session session, String host, int port, String user,
                                   String hostKeyInfo) {
    }

    record Endpoint(String host, String user, int port) {
    }

    private record KeyMaterial(String privateKey, String publicKey) {
    }

    private record SavedCredentials(String username, String loginMode, String password, String publicKey) {
    }

    private record ResolvedCredentials(String username, int loginType, String password,
                                       String privateKey, String passphrase) {
    }

    private record Cidr(byte[] address, int prefixLength) {
    }

    private record PrepareDeadline(long deadlineNanos) {
        static PrepareDeadline start(int timeoutSeconds) {
            int boundedSeconds = Math.max(30, Math.min(600, timeoutSeconds));
            return new PrepareDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(boundedSeconds));
        }

        void ensureRemaining() {
            if (System.nanoTime() >= deadlineNanos) {
                throw new OciException("Timed out preparing OCI Bastion SSH");
            }
        }

        long operationDeadlineNanos(int timeoutSeconds) {
            long local = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(Math.max(1, Math.min(300, timeoutSeconds)));
            return Math.min(deadlineNanos, local);
        }
    }

    private final class PreparePermit implements AutoCloseable {
        private final String ownerAccount;
        private final AtomicInteger ownerCount;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private PreparePermit(String ownerAccount, AtomicInteger ownerCount) {
            this.ownerAccount = ownerAccount;
            this.ownerCount = ownerCount;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                releasePreparePermit(ownerAccount, ownerCount);
            }
        }
    }
}
