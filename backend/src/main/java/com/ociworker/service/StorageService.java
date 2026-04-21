package com.ociworker.service;

import com.oracle.bmc.core.model.*;
import com.oracle.bmc.core.requests.*;
import com.oracle.bmc.identity.model.Compartment;
import com.oracle.bmc.identity.requests.GetCompartmentRequest;
import com.oracle.bmc.objectstorage.model.*;
import com.oracle.bmc.objectstorage.requests.*;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.ociworker.util.ObjectStorageBucketPolicyHttp;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class StorageService {

    @Resource
    private OciUserMapper userMapper;

    public List<String> listPublicRegionIds() {
        return Arrays.stream(com.oracle.bmc.Region.values())
                .map(com.oracle.bmc.Region::getRegionId)
                .sorted()
                .toList();
    }

    public List<Map<String, Object>> listCompartments(String userId, String region) {
        OciUser ociUser = requireUser(userId);
        try (OciClientService client = new OciClientService(buildDto(ociUser), region)) {
            return client.listAllCompartments().stream()
                    .map(c -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", c.getId());
                        m.put("name", c.getName());
                        m.put("compartmentId", c.getCompartmentId());
                        if (c.getLifecycleState() != null) {
                            m.put("lifecycleState", c.getLifecycleState().getValue());
                        }
                        return m;
                    })
                    .toList();
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("列出隔间失败: " + e.getMessage());
        }
    }

    public Map<String, Object> blockAggregate(String userId, String region, String compartmentIdOpt) {
        OciUser ociUser = requireUser(userId);
        try (OciClientService client = new OciClientService(buildDto(ociUser), region)) {
            List<Compartment> compartments = resolveCompartments(client, compartmentIdOpt);
            List<String> availabilityDomains = listAvailabilityDomainNames(client);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("region", region);

            List<Map<String, Object>> bootVolumes = new ArrayList<>();
            List<Map<String, Object>> blockVolumes = new ArrayList<>();
            List<Map<String, Object>> bootBackups = new ArrayList<>();
            List<Map<String, Object>> blockBackups = new ArrayList<>();
            List<Map<String, Object>> bootReplicas = new ArrayList<>();
            List<Map<String, Object>> blockReplicas = new ArrayList<>();
            List<Map<String, Object>> volumeGroups = new ArrayList<>();
            List<Map<String, Object>> volumeGroupBackups = new ArrayList<>();
            List<Map<String, Object>> volumeGroupReplicas = new ArrayList<>();
            List<Map<String, Object>> backupPolicies = new ArrayList<>();
            List<Map<String, Object>> backupPolicyAssignments = new ArrayList<>();

            for (Compartment compartment : compartments) {
                String cid = compartment.getId();
                String cname = compartment.getName();
                Map<String, String> instanceNames = loadInstanceNames(client, cid);
                Map<String, List<Map<String, Object>>> bootAttach =
                        loadBootVolumeAttachments(client, cid, instanceNames, availabilityDomains);
                Map<String, List<Map<String, Object>>> volAttach =
                        loadVolumeAttachments(client, cid, instanceNames, availabilityDomains);

                int bootStart = bootVolumes.size();
                int blockStart = blockVolumes.size();
                int vgStart = volumeGroups.size();
                listBootVolumes(client, region, cid, cname, bootAttach, bootVolumes, availabilityDomains);
                listBlockVolumes(client, region, cid, cname, volAttach, blockVolumes, availabilityDomains);
                listBootBackups(client, region, cid, cname, bootBackups);
                listBlockBackups(client, region, cid, cname, blockBackups);
                listBootReplicas(client, region, cid, cname, bootReplicas, availabilityDomains);
                listBlockReplicas(client, region, cid, cname, blockReplicas, availabilityDomains);
                listVolumeGroups(client, region, cid, cname, volumeGroups, availabilityDomains);
                listVolumeGroupBackups(client, region, cid, cname, volumeGroupBackups);
                listVolumeGroupReplicas(client, region, cid, cname, volumeGroupReplicas, availabilityDomains);
                listBackupPolicies(client, region, cid, cname, backupPolicies);
                List<String> policyAssetIds = new ArrayList<>();
                for (int i = bootStart; i < bootVolumes.size(); i++) {
                    Object id = bootVolumes.get(i).get("id");
                    if (id != null) policyAssetIds.add(String.valueOf(id));
                }
                for (int i = blockStart; i < blockVolumes.size(); i++) {
                    Object id = blockVolumes.get(i).get("id");
                    if (id != null) policyAssetIds.add(String.valueOf(id));
                }
                for (int i = vgStart; i < volumeGroups.size(); i++) {
                    Object id = volumeGroups.get(i).get("id");
                    if (id != null) policyAssetIds.add(String.valueOf(id));
                }
                collectVolumeBackupPolicyAssignments(client, region, cid, cname, policyAssetIds, backupPolicyAssignments);
            }

            out.put("bootVolumes", bootVolumes);
            out.put("blockVolumes", blockVolumes);
            out.put("bootVolumeBackups", bootBackups);
            out.put("blockVolumeBackups", blockBackups);
            out.put("bootVolumeReplicas", bootReplicas);
            out.put("blockVolumeReplicas", blockReplicas);
            out.put("volumeGroups", volumeGroups);
            out.put("volumeGroupBackups", volumeGroupBackups);
            out.put("volumeGroupReplicas", volumeGroupReplicas);
            out.put("volumeBackupPolicies", backupPolicies);
            out.put("volumeBackupPolicyAssignments", backupPolicyAssignments);
            return out;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("加载块存储数据失败: " + e.getMessage());
        }
    }

    public Map<String, Object> objectAggregate(String userId, String region, String compartmentIdOpt) {
        OciUser ociUser = requireUser(userId);
        try (OciClientService client = new OciClientService(buildDto(ociUser), region)) {
            List<Compartment> compartments = resolveCompartments(client, compartmentIdOpt);
            String namespace = client.getObjectStorageClient().getNamespace(
                    GetNamespaceRequest.builder().build()).getValue();

            List<Map<String, Object>> buckets = new ArrayList<>();
            List<Map<String, Object>> privateEndpoints = new ArrayList<>();

            for (Compartment compartment : compartments) {
                String cid = compartment.getId();
                String cname = compartment.getName();
                listBuckets(client, namespace, region, cid, cname, buckets);
                listPrivateEndpoints(client, namespace, region, cid, cname, privateEndpoints);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("region", region);
            out.put("namespace", namespace);
            out.put("buckets", buckets);
            out.put("privateEndpoints", privateEndpoints);
            return out;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("加载对象存储数据失败: " + e.getMessage());
        }
    }

    public void deleteResource(String userId, String region, String resourceType, String resourceId,
                               String namespace, String bucketName) {
        if (resourceType == null || resourceType.isBlank()) {
            throw new OciException("resourceType 不能为空");
        }
        if (!"BUCKET".equals(resourceType)
                && (resourceId == null || resourceId.isBlank())) {
            throw new OciException("resourceId 不能为空");
        }
        OciUser ociUser = requireUser(userId);
        try (OciClientService client = new OciClientService(buildDto(ociUser), region)) {
            switch (resourceType) {
                case "BOOT_VOLUME" ->
                        client.getBlockstorageClient().deleteBootVolume(
                                DeleteBootVolumeRequest.builder().bootVolumeId(resourceId).build());
                case "BLOCK_VOLUME" ->
                        client.getBlockstorageClient().deleteVolume(
                                DeleteVolumeRequest.builder().volumeId(resourceId).build());
                case "BOOT_VOLUME_BACKUP" ->
                        client.getBlockstorageClient().deleteBootVolumeBackup(
                                DeleteBootVolumeBackupRequest.builder().bootVolumeBackupId(resourceId).build());
                case "BLOCK_VOLUME_BACKUP" ->
                        client.getBlockstorageClient().deleteVolumeBackup(
                                DeleteVolumeBackupRequest.builder().volumeBackupId(resourceId).build());
                case "BOOT_VOLUME_REPLICA", "BLOCK_VOLUME_REPLICA", "VOLUME_GROUP_REPLICA" ->
                        throw new OciException("当前 OCI Java SDK 已不再暴露副本删除接口，请在 OCI 控制台删除副本");
                case "VOLUME_GROUP" ->
                        client.getBlockstorageClient().deleteVolumeGroup(
                                DeleteVolumeGroupRequest.builder().volumeGroupId(resourceId).build());
                case "VOLUME_GROUP_BACKUP" ->
                        client.getBlockstorageClient().deleteVolumeGroupBackup(
                                DeleteVolumeGroupBackupRequest.builder().volumeGroupBackupId(resourceId).build());
                case "BUCKET" -> {
                    if (namespace == null || namespace.isBlank() || bucketName == null || bucketName.isBlank()) {
                        throw new OciException("删除桶需要 namespace 与 bucketName");
                    }
                    assertBucketEmpty(client, namespace, bucketName);
                    client.getObjectStorageClient().deleteBucket(
                            DeleteBucketRequest.builder()
                                    .namespaceName(namespace)
                                    .bucketName(bucketName)
                                    .build());
                }
                case "VOLUME_BACKUP_POLICY" ->
                        client.getBlockstorageClient().deleteVolumeBackupPolicy(
                                DeleteVolumeBackupPolicyRequest.builder().policyId(resourceId).build());
                case "VOLUME_BACKUP_POLICY_ASSIGNMENT" ->
                        client.getBlockstorageClient().deleteVolumeBackupPolicyAssignment(
                                DeleteVolumeBackupPolicyAssignmentRequest.builder()
                                        .policyAssignmentId(resourceId)
                                        .build());
                case "PRIVATE_ENDPOINT" -> {
                    if (namespace == null || namespace.isBlank()) {
                        throw new OciException("删除专用端点需要 namespace 与端点名称（resourceId 传 peName）");
                    }
                    client.getObjectStorageClient().deletePrivateEndpoint(
                            DeletePrivateEndpointRequest.builder()
                                    .namespaceName(namespace)
                                    .peName(resourceId)
                                    .build());
                }
                default -> throw new OciException("未知资源类型: " + resourceType);
            }
        } catch (OciException e) {
            throw e;
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException("删除失败: " + e.getMessage());
        } catch (Exception e) {
            throw new OciException("删除失败: " + e.getMessage());
        }
    }

    public void putBucketPolicy(String userId, String region, String namespace, String bucketName, String policy) {
        if (namespace == null || namespace.isBlank() || bucketName == null || bucketName.isBlank()) {
            throw new OciException("namespace / bucketName 不能为空");
        }
        if (policy == null) {
            throw new OciException("policy 不能为空");
        }
        OciUser ociUser = requireUser(userId);
        try (OciClientService client = new OciClientService(buildDto(ociUser), region)) {
            ObjectStorageBucketPolicyHttp.putBucketPolicy(
                    client.getObjectStorageClient(),
                    client.getProvider(),
                    namespace,
                    bucketName,
                    policy);
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("保存桶策略失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public Object mutate(Map<String, Object> params) {
        String action = stringParam(params, "action");
        String userId = stringParam(params, "id");
        String region = stringParam(params, "region");
        OciUser ociUser = requireUser(userId);
        try (OciClientService client = new OciClientService(buildDto(ociUser), region)) {
            return switch (action) {
                case "updateBootVolume" -> {
                    String bootVolumeId = stringParam(params, "bootVolumeId");
                    String displayName = stringParam(params, "displayName");
                    Long size = longParam(params, "sizeInGBs");
                    var b = UpdateBootVolumeDetails.builder();
                    if (!displayName.isBlank()) {
                        b.displayName(displayName);
                    }
                    if (size != null) {
                        b.sizeInGBs(size);
                    }
                    if (displayName.isBlank() && size == null) {
                        throw new OciException("至少提供 displayName 或 sizeInGBs");
                    }
                    yield toMap(client.getBlockstorageClient().updateBootVolume(
                            UpdateBootVolumeRequest.builder()
                                    .bootVolumeId(bootVolumeId)
                                    .updateBootVolumeDetails(b.build())
                                    .build()).getBootVolume());
                }
                case "updateBlockVolume" -> {
                    String volumeId = stringParam(params, "volumeId");
                    String displayName = stringParam(params, "displayName");
                    Long size = longParam(params, "sizeInGBs");
                    var b = UpdateVolumeDetails.builder().displayName(displayName);
                    if (size != null) b.sizeInGBs(size);
                    yield toMap(client.getBlockstorageClient().updateVolume(
                            UpdateVolumeRequest.builder()
                                    .volumeId(volumeId)
                                    .updateVolumeDetails(b.build())
                                    .build()).getVolume());
                }
                case "updateBootVolumeReplica", "updateBlockVolumeReplica" ->
                        throw new OciException("当前 OCI Java SDK 已不再暴露副本更新接口，请在 OCI 控制台修改副本显示名称");
                case "updateVolumeGroup" -> {
                    String id = stringParam(params, "volumeGroupId");
                    String displayName = stringParam(params, "displayName");
                    yield toMap(client.getBlockstorageClient().updateVolumeGroup(
                            UpdateVolumeGroupRequest.builder()
                                    .volumeGroupId(id)
                                    .updateVolumeGroupDetails(UpdateVolumeGroupDetails.builder()
                                            .displayName(displayName)
                                            .build())
                                    .build()).getVolumeGroup());
                }
                case "enableBlockVolumeReplication" -> {
                    String volumeId = stringParam(params, "volumeId");
                    String replicaDisplayName = stringParam(params, "replicaDisplayName");
                    String destinationAvailabilityDomain = stringParam(params, "destinationAvailabilityDomain");
                    String xrrKmsKeyId = stringParam(params, "xrrKmsKeyId");
                    var replica = BlockVolumeReplicaDetails.builder()
                            .displayName(replicaDisplayName)
                            .availabilityDomain(destinationAvailabilityDomain);
                    if (!xrrKmsKeyId.isBlank()) {
                        replica.xrrKmsKeyId(xrrKmsKeyId);
                    }
                    var details = UpdateVolumeDetails.builder()
                            .blockVolumeReplicas(List.of(replica.build()))
                            .build();
                    yield toMap(client.getBlockstorageClient().updateVolume(
                            UpdateVolumeRequest.builder()
                                    .volumeId(volumeId)
                                    .updateVolumeDetails(details)
                                    .build()).getVolume());
                }
                case "enableBootVolumeReplication" -> {
                    String bootVolumeId = stringParam(params, "bootVolumeId");
                    String replicaDisplayName = stringParam(params, "replicaDisplayName");
                    String destinationAvailabilityDomain = stringParam(params, "destinationAvailabilityDomain");
                    String xrrKmsKeyId = stringParam(params, "xrrKmsKeyId");
                    var replica = BootVolumeReplicaDetails.builder()
                            .displayName(replicaDisplayName)
                            .availabilityDomain(destinationAvailabilityDomain);
                    if (!xrrKmsKeyId.isBlank()) {
                        replica.xrrKmsKeyId(xrrKmsKeyId);
                    }
                    var details = UpdateBootVolumeDetails.builder()
                            .bootVolumeReplicas(List.of(replica.build()))
                            .build();
                    yield toMap(client.getBlockstorageClient().updateBootVolume(
                            UpdateBootVolumeRequest.builder()
                                    .bootVolumeId(bootVolumeId)
                                    .updateBootVolumeDetails(details)
                                    .build()).getBootVolume());
                }
                case "activateBlockReplicaAsVolume" -> {
                    String replicaId = stringParam(params, "replicaId");
                    String compartmentId = stringParam(params, "compartmentId");
                    String ad = stringParam(params, "availabilityDomain");
                    String displayName = stringParam(params, "displayName");
                    Long sizeInGBs = longParam(params, "sizeInGBs");
                    var src = VolumeSourceFromBlockVolumeReplicaDetails.builder()
                            .id(replicaId)
                            .build();
                    var detailsB = CreateVolumeDetails.builder()
                            .availabilityDomain(ad)
                            .compartmentId(compartmentId)
                            .displayName(displayName)
                            .sourceDetails(src);
                    if (sizeInGBs != null) {
                        detailsB.sizeInGBs(sizeInGBs);
                    }
                    yield toMap(client.getBlockstorageClient().createVolume(
                            CreateVolumeRequest.builder()
                                    .createVolumeDetails(detailsB.build())
                                    .build()).getVolume());
                }
                case "activateBootReplicaAsBootVolume" -> {
                    String replicaId = stringParam(params, "replicaId");
                    String compartmentId = stringParam(params, "compartmentId");
                    String ad = stringParam(params, "availabilityDomain");
                    String displayName = stringParam(params, "displayName");
                    var src = BootVolumeSourceFromBootVolumeReplicaDetails.builder()
                            .id(replicaId)
                            .build();
                    var details = CreateBootVolumeDetails.builder()
                            .availabilityDomain(ad)
                            .compartmentId(compartmentId)
                            .displayName(displayName)
                            .sourceDetails(src)
                            .build();
                    yield toMap(client.getBlockstorageClient().createBootVolume(
                            CreateBootVolumeRequest.builder()
                                    .createBootVolumeDetails(details)
                                    .build()).getBootVolume());
                }
                case "createBucket" -> {
                    String compartmentId = stringParam(params, "compartmentId");
                    String name = stringParam(params, "name");
                    String accessType = stringParam(params, "publicAccessType");
                    var details = CreateBucketDetails.builder()
                            .compartmentId(compartmentId)
                            .name(name);
                    if (accessType != null && !accessType.isBlank()) {
                        details.publicAccessType(CreateBucketDetails.PublicAccessType.create(accessType));
                    }
                    yield toMap(client.getObjectStorageClient().createBucket(
                            CreateBucketRequest.builder()
                                    .namespaceName(stringParam(params, "namespace"))
                                    .createBucketDetails(details.build())
                                    .build()).getBucket());
                }
                case "updateBucket" -> {
                    String namespace = stringParam(params, "namespace");
                    String bucketName = stringParam(params, "bucketName");
                    var ub = UpdateBucketDetails.builder();
                    if (params.containsKey("namespace")) { /* noop */ }
                    if (params.get("versioning") != null) {
                        String v = String.valueOf(params.get("versioning"));
                        ub.versioning(UpdateBucketDetails.Versioning.create(v));
                    }
                    if (params.get("freeformTags") instanceof Map<?, ?> m) {
                        Map<String, String> tags = new LinkedHashMap<>();
                        for (var e : m.entrySet()) {
                            tags.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                        }
                        ub.freeformTags(tags);
                    }
                    if (params.containsKey("publicAccessType") && params.get("publicAccessType") != null) {
                        String pa = String.valueOf(params.get("publicAccessType"));
                        ub.publicAccessType(UpdateBucketDetails.PublicAccessType.create(pa));
                    }
                    yield toMap(client.getObjectStorageClient().updateBucket(
                            UpdateBucketRequest.builder()
                                    .namespaceName(namespace)
                                    .bucketName(bucketName)
                                    .updateBucketDetails(ub.build())
                                    .build()).getBucket());
                }
                case "createPrivateEndpoint" -> {
                    String namespace = stringParam(params, "namespace");
                    String compartmentId = stringParam(params, "compartmentId");
                    String subnetId = stringParam(params, "subnetId");
                    String displayName = stringParam(params, "displayName");
                    var det = CreatePrivateEndpointDetails.builder()
                            .compartmentId(compartmentId)
                            .subnetId(subnetId)
                            .name(displayName)
                            .build();
                    // SDK 3.83+: CreatePrivateEndpointResponse 仅返回 opcWorkRequestId（异步创建），无 getPrivateEndpoint()
                    var resp = client.getObjectStorageClient().createPrivateEndpoint(
                            CreatePrivateEndpointRequest.builder()
                                    .namespaceName(namespace)
                                    .createPrivateEndpointDetails(det)
                                    .build());
                    Map<String, Object> peOut = new LinkedHashMap<>();
                    peOut.put("opcWorkRequestId", resp.getOpcWorkRequestId());
                    peOut.put("namespace", namespace);
                    peOut.put("name", displayName);
                    peOut.put("compartmentId", compartmentId);
                    peOut.put("subnetId", subnetId);
                    yield peOut;
                }
                case "createVolumeBackupPolicyAssignment" -> {
                    String policyId = stringParam(params, "policyId");
                    String assetId = stringParam(params, "assetId");
                    var det = CreateVolumeBackupPolicyAssignmentDetails.builder()
                            .policyId(policyId)
                            .assetId(assetId)
                            .build();
                    yield toMap(client.getBlockstorageClient().createVolumeBackupPolicyAssignment(
                            CreateVolumeBackupPolicyAssignmentRequest.builder()
                                    .createVolumeBackupPolicyAssignmentDetails(det)
                                    .build()).getVolumeBackupPolicyAssignment());
                }
                case "createVolumeBackupPolicy" -> {
                    String compartmentId = stringParam(params, "compartmentId");
                    String displayName = stringParam(params, "displayName");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> schedules = (List<Map<String, Object>>) params.get("schedules");
                    List<VolumeBackupSchedule> built = parseVolumeBackupSchedules(schedules);
                    if (built.isEmpty()) {
                        built.add(VolumeBackupSchedule.builder()
                                .backupType(VolumeBackupSchedule.BackupType.Full)
                                .period(VolumeBackupSchedule.Period.OneDay)
                                .offsetType(VolumeBackupSchedule.OffsetType.Structured)
                                .hourOfDay(2)
                                .retentionSeconds(86400 * 7)
                                .build());
                    }
                    var det = CreateVolumeBackupPolicyDetails.builder()
                            .compartmentId(compartmentId)
                            .displayName(displayName)
                            .schedules(built)
                            .build();
                    yield toMap(client.getBlockstorageClient().createVolumeBackupPolicy(
                            CreateVolumeBackupPolicyRequest.builder()
                                    .createVolumeBackupPolicyDetails(det)
                                    .build()).getVolumeBackupPolicy());
                }
                case "updateVolumeBackupPolicy" -> {
                    String policyId = stringParam(params, "policyId");
                    String displayName = stringParam(params, "displayName");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> schedules = (List<Map<String, Object>>) params.get("schedules");
                    List<VolumeBackupSchedule> built = parseVolumeBackupSchedules(schedules);
                    var ub = UpdateVolumeBackupPolicyDetails.builder().displayName(displayName);
                    if (!built.isEmpty()) {
                        ub.schedules(built);
                    }
                    yield toMap(client.getBlockstorageClient().updateVolumeBackupPolicy(
                            UpdateVolumeBackupPolicyRequest.builder()
                                    .policyId(policyId)
                                    .updateVolumeBackupPolicyDetails(ub.build())
                                    .build()).getVolumeBackupPolicy());
                }
                case "createVolumeGroup" -> {
                    String compartmentId = stringParam(params, "compartmentId");
                    String availabilityDomain = stringParam(params, "availabilityDomain");
                    String displayName = stringParam(params, "displayName");
                    @SuppressWarnings("unchecked")
                    List<String> volumeIds = (List<String>) params.get("volumeIds");
                    var det = CreateVolumeGroupDetails.builder()
                            .compartmentId(compartmentId)
                            .availabilityDomain(availabilityDomain)
                            .displayName(displayName)
                            .sourceDetails(VolumeGroupSourceFromVolumesDetails.builder()
                                    .volumeIds(volumeIds == null ? List.of() : volumeIds)
                                    .build())
                            .build();
                    yield toMap(client.getBlockstorageClient().createVolumeGroup(
                            CreateVolumeGroupRequest.builder()
                                    .createVolumeGroupDetails(det)
                                    .build()).getVolumeGroup());
                }
                default -> throw new OciException("未知操作: " + action);
            };
        } catch (OciException e) {
            throw e;
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException("操作失败: " + e.getMessage());
        } catch (Exception e) {
            throw new OciException("操作失败: " + e.getMessage());
        }
    }

    private void assertBucketEmpty(OciClientService client, String namespace, String bucketName) {
        var resp = client.getObjectStorageClient().listObjects(
                ListObjectsRequest.builder()
                        .namespaceName(namespace)
                        .bucketName(bucketName)
                        .limit(1)
                        .build());
        if (resp.getListObjects() != null && resp.getListObjects().getObjects() != null
                && !resp.getListObjects().getObjects().isEmpty()) {
            throw new OciException("桶非空，拒绝删除。请先清空对象后再删除。");
        }
    }

    /**
     * OCI Java SDK 3.83+ 中部分 Core List 请求在 {@code build()} 时强制要求 availabilityDomain，
     * 需先按当前 Region 枚举 AD 再逐 AD 查询并合并。
     */
    private List<String> listAvailabilityDomainNames(OciClientService client) {
        try {
            List<String> names = client.getAvailabilityDomains().stream()
                    .map(ad -> ad.getName())
                    .filter(n -> n != null && !n.isBlank())
                    .distinct()
                    .toList();
            if (names.isEmpty()) {
                throw new OciException("当前区域未返回任何可用域（Availability Domain），无法列举块存储资源");
            }
            return names;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("获取可用域列表失败: " + e.getMessage());
        }
    }

    private List<Compartment> resolveCompartments(OciClientService client, String compartmentIdOpt) {
        if (compartmentIdOpt == null || compartmentIdOpt.isBlank()) {
            return client.listAllCompartments();
        }
        try {
            var c = client.getIdentityClient().getCompartment(
                    GetCompartmentRequest.builder().compartmentId(compartmentIdOpt).build()
            ).getCompartment();
            return List.of(c);
        } catch (Exception e) {
            throw new OciException("读取隔间失败: " + e.getMessage());
        }
    }

    private Map<String, String> loadInstanceNames(OciClientService client, String compartmentId) {
        Map<String, String> names = new HashMap<>();
        for (Instance.LifecycleState state : List.of(
                Instance.LifecycleState.Running,
                Instance.LifecycleState.Stopped,
                Instance.LifecycleState.Starting,
                Instance.LifecycleState.Stopping)) {
            String page = null;
            do {
                var resp = client.getComputeClient().listInstances(
                        ListInstancesRequest.builder()
                                .compartmentId(compartmentId)
                                .lifecycleState(state)
                                .page(page)
                                .build());
                for (Instance i : resp.getItems()) {
                    names.put(i.getId(), i.getDisplayName());
                }
                page = resp.getOpcNextPage();
            } while (page != null);
        }
        return names;
    }

    private Map<String, List<Map<String, Object>>> loadBootVolumeAttachments(OciClientService client, String compartmentId,
                                                                             Map<String, String> instanceNames,
                                                                             List<String> availabilityDomains) {
        Map<String, List<Map<String, Object>>> map = new HashMap<>();
        for (String ad : availabilityDomains) {
            String page = null;
            do {
                var resp = client.getComputeClient().listBootVolumeAttachments(
                        ListBootVolumeAttachmentsRequest.builder()
                                .compartmentId(compartmentId)
                                .availabilityDomain(ad)
                                .page(page)
                                .build());
                for (BootVolumeAttachment a : resp.getItems()) {
                    if (a.getLifecycleState() == BootVolumeAttachment.LifecycleState.Detached) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("instanceId", a.getInstanceId());
                    row.put("instanceName", instanceNames.getOrDefault(a.getInstanceId(), ""));
                    row.put("lifecycleState", a.getLifecycleState() != null ? a.getLifecycleState().getValue() : null);
                    map.computeIfAbsent(a.getBootVolumeId(), k -> new ArrayList<>()).add(row);
                }
                page = resp.getOpcNextPage();
            } while (page != null);
        }
        return map;
    }

    private Map<String, List<Map<String, Object>>> loadVolumeAttachments(OciClientService client, String compartmentId,
                                                                        Map<String, String> instanceNames,
                                                                        List<String> availabilityDomains) {
        Map<String, List<Map<String, Object>>> map = new HashMap<>();
        for (String ad : availabilityDomains) {
            String page = null;
            do {
                var resp = client.getComputeClient().listVolumeAttachments(
                        ListVolumeAttachmentsRequest.builder()
                                .compartmentId(compartmentId)
                                .availabilityDomain(ad)
                                .page(page)
                                .build());
                for (VolumeAttachment a : resp.getItems()) {
                    if (a.getLifecycleState() == VolumeAttachment.LifecycleState.Detached) continue;
                    String volId = a.getVolumeId();
                    if (volId == null) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("instanceId", a.getInstanceId());
                    row.put("instanceName", instanceNames.getOrDefault(a.getInstanceId(), ""));
                    row.put("lifecycleState", a.getLifecycleState() != null ? a.getLifecycleState().getValue() : null);
                    map.computeIfAbsent(volId, k -> new ArrayList<>()).add(row);
                }
                page = resp.getOpcNextPage();
            } while (page != null);
        }
        return map;
    }

    private void listBootVolumes(OciClientService client, String region, String cid, String cname,
                                 Map<String, List<Map<String, Object>>> bootAttach,
                                 List<Map<String, Object>> out, List<String> availabilityDomains) {
        try {
            Set<String> seenIds = new HashSet<>();
            for (String ad : availabilityDomains) {
                String page = null;
                do {
                    var resp = client.getBlockstorageClient().listBootVolumes(
                            ListBootVolumesRequest.builder()
                                    .compartmentId(cid)
                                    .availabilityDomain(ad)
                                    .page(page)
                                    .build());
                    for (BootVolume v : resp.getItems()) {
                        if (v.getLifecycleState() == BootVolume.LifecycleState.Terminated) continue;
                        if (!seenIds.add(v.getId())) continue;
                        Map<String, Object> m = baseRow(region, cid, cname, v.getId(), v.getDisplayName(),
                                v.getLifecycleState() != null ? v.getLifecycleState().getValue() : null,
                                v.getTimeCreated() != null ? v.getTimeCreated().toString() : null);
                        m.put("sizeInGBs", v.getSizeInGBs());
                        m.put("vpusPerGB", v.getVpusPerGB());
                        m.put("availabilityDomain", v.getAvailabilityDomain());
                        m.put("imageId", v.getImageId());
                        m.put("attachments", bootAttach.getOrDefault(v.getId(), List.of()));
                        m.put("attachmentSummary", summarizeAttachments(bootAttach.get(v.getId())));
                        out.add(m);
                    }
                    page = resp.getOpcNextPage();
                } while (page != null);
            }
        } catch (Exception e) {
            log.debug("listBootVolumes {}: {}", cid, e.getMessage());
        }
    }

    private void listBlockVolumes(OciClientService client, String region, String cid, String cname,
                                  Map<String, List<Map<String, Object>>> volAttach,
                                  List<Map<String, Object>> out, List<String> availabilityDomains) {
        try {
            Set<String> seenIds = new HashSet<>();
            for (String ad : availabilityDomains) {
                String page = null;
                do {
                    var resp = client.getBlockstorageClient().listVolumes(
                            ListVolumesRequest.builder()
                                    .compartmentId(cid)
                                    .availabilityDomain(ad)
                                    .page(page)
                                    .build());
                    for (Volume v : resp.getItems()) {
                        if (v.getLifecycleState() == Volume.LifecycleState.Terminated) continue;
                        if (!seenIds.add(v.getId())) continue;
                        Map<String, Object> m = baseRow(region, cid, cname, v.getId(), v.getDisplayName(),
                                v.getLifecycleState() != null ? v.getLifecycleState().getValue() : null,
                                v.getTimeCreated() != null ? v.getTimeCreated().toString() : null);
                        m.put("sizeInGBs", v.getSizeInGBs());
                        m.put("vpusPerGB", v.getVpusPerGB());
                        m.put("availabilityDomain", v.getAvailabilityDomain());
                        m.put("isHydrated", v.getIsHydrated());
                        m.put("attachments", volAttach.getOrDefault(v.getId(), List.of()));
                        m.put("attachmentSummary", summarizeAttachments(volAttach.get(v.getId())));
                        out.add(m);
                    }
                    page = resp.getOpcNextPage();
                } while (page != null);
            }
        } catch (Exception e) {
            log.debug("listVolumes {}: {}", cid, e.getMessage());
        }
    }

    private void listBootBackups(OciClientService client, String region, String cid, String cname, List<Map<String, Object>> out) {
        try {
            String page = null;
            do {
                var resp = client.getBlockstorageClient().listBootVolumeBackups(
                        ListBootVolumeBackupsRequest.builder().compartmentId(cid).page(page).build());
                for (BootVolumeBackup b : resp.getItems()) {
                    if (b.getLifecycleState() == BootVolumeBackup.LifecycleState.Terminated) continue;
                    Map<String, Object> m = baseRow(region, cid, cname, b.getId(), b.getDisplayName(),
                            b.getLifecycleState() != null ? b.getLifecycleState().getValue() : null,
                            b.getTimeCreated() != null ? b.getTimeCreated().toString() : null);
                    m.put("sizeInGBs", b.getSizeInGBs());
                    m.put("uniqueSizeInGBs", b.getUniqueSizeInGBs());
                    m.put("sourceBootVolumeId", b.getBootVolumeId());
                    m.put("sourceType", b.getSourceType() != null ? b.getSourceType().getValue() : null);
                    out.add(m);
                }
                page = resp.getOpcNextPage();
            } while (page != null);
        } catch (Exception e) {
            log.debug("listBootVolumeBackups {}: {}", cid, e.getMessage());
        }
    }

    private void listBlockBackups(OciClientService client, String region, String cid, String cname, List<Map<String, Object>> out) {
        try {
            String page = null;
            do {
                var resp = client.getBlockstorageClient().listVolumeBackups(
                        ListVolumeBackupsRequest.builder().compartmentId(cid).page(page).build());
                for (VolumeBackup b : resp.getItems()) {
                    if (b.getLifecycleState() == VolumeBackup.LifecycleState.Terminated) continue;
                    Map<String, Object> m = baseRow(region, cid, cname, b.getId(), b.getDisplayName(),
                            b.getLifecycleState() != null ? b.getLifecycleState().getValue() : null,
                            b.getTimeCreated() != null ? b.getTimeCreated().toString() : null);
                    m.put("sizeInGBs", b.getSizeInGBs());
                    m.put("uniqueSizeInGBs", b.getUniqueSizeInGBs());
                    m.put("sourceVolumeId", b.getVolumeId());
                    m.put("sourceType", b.getSourceType() != null ? b.getSourceType().getValue() : null);
                    out.add(m);
                }
                page = resp.getOpcNextPage();
            } while (page != null);
        } catch (Exception e) {
            log.debug("listVolumeBackups {}: {}", cid, e.getMessage());
        }
    }

    private void listBootReplicas(OciClientService client, String region, String cid, String cname, List<Map<String, Object>> out,
                                  List<String> availabilityDomains) {
        try {
            Set<String> seenIds = new HashSet<>();
            for (String ad : availabilityDomains) {
                String page = null;
                do {
                    var resp = client.getBlockstorageClient().listBootVolumeReplicas(
                            ListBootVolumeReplicasRequest.builder()
                                    .compartmentId(cid)
                                    .availabilityDomain(ad)
                                    .page(page)
                                    .build());
                    for (BootVolumeReplica r : resp.getItems()) {
                        if (r.getLifecycleState() == BootVolumeReplica.LifecycleState.Terminated) continue;
                        if (!seenIds.add(r.getId())) continue;
                        Map<String, Object> m = baseRow(region, cid, cname, r.getId(), r.getDisplayName(),
                                r.getLifecycleState() != null ? r.getLifecycleState().getValue() : null,
                                r.getTimeCreated() != null ? r.getTimeCreated().toString() : null);
                        m.put("sizeInGBs", r.getSizeInGBs());
                        m.put("availabilityDomain", r.getAvailabilityDomain());
                        m.put("sourceBootVolumeId", r.getBootVolumeId());
                        m.put("timeLastSynced", r.getTimeLastSynced() != null ? r.getTimeLastSynced().toString() : null);
                        out.add(m);
                    }
                    page = resp.getOpcNextPage();
                } while (page != null);
            }
        } catch (Exception e) {
            log.debug("listBootVolumeReplicas {}: {}", cid, e.getMessage());
        }
    }

    private void listBlockReplicas(OciClientService client, String region, String cid, String cname, List<Map<String, Object>> out,
                                   List<String> availabilityDomains) {
        try {
            Set<String> seenIds = new HashSet<>();
            for (String ad : availabilityDomains) {
                String page = null;
                do {
                    var resp = client.getBlockstorageClient().listBlockVolumeReplicas(
                            ListBlockVolumeReplicasRequest.builder()
                                    .compartmentId(cid)
                                    .availabilityDomain(ad)
                                    .page(page)
                                    .build());
                    for (BlockVolumeReplica r : resp.getItems()) {
                        if (r.getLifecycleState() == BlockVolumeReplica.LifecycleState.Terminated) continue;
                        if (!seenIds.add(r.getId())) continue;
                        Map<String, Object> m = baseRow(region, cid, cname, r.getId(), r.getDisplayName(),
                                r.getLifecycleState() != null ? r.getLifecycleState().getValue() : null,
                                r.getTimeCreated() != null ? r.getTimeCreated().toString() : null);
                        m.put("sizeInGBs", r.getSizeInGBs());
                        m.put("availabilityDomain", r.getAvailabilityDomain());
                        m.put("sourceVolumeId", r.getBlockVolumeId());
                        m.put("timeLastSynced", r.getTimeLastSynced() != null ? r.getTimeLastSynced().toString() : null);
                        m.put("volumeGroupReplicaId", r.getVolumeGroupReplicaId());
                        out.add(m);
                    }
                    page = resp.getOpcNextPage();
                } while (page != null);
            }
        } catch (Exception e) {
            log.debug("listBlockVolumeReplicas {}: {}", cid, e.getMessage());
        }
    }

    private void listVolumeGroups(OciClientService client, String region, String cid, String cname, List<Map<String, Object>> out,
                                  List<String> availabilityDomains) {
        try {
            Set<String> seenIds = new HashSet<>();
            for (String ad : availabilityDomains) {
                String page = null;
                do {
                    var resp = client.getBlockstorageClient().listVolumeGroups(
                            ListVolumeGroupsRequest.builder()
                                    .compartmentId(cid)
                                    .availabilityDomain(ad)
                                    .page(page)
                                    .build());
                    for (VolumeGroup g : resp.getItems()) {
                        if (g.getLifecycleState() == VolumeGroup.LifecycleState.Terminated) continue;
                        if (!seenIds.add(g.getId())) continue;
                        Map<String, Object> m = baseRow(region, cid, cname, g.getId(), g.getDisplayName(),
                                g.getLifecycleState() != null ? g.getLifecycleState().getValue() : null,
                                g.getTimeCreated() != null ? g.getTimeCreated().toString() : null);
                        m.put("availabilityDomain", g.getAvailabilityDomain());
                        m.put("volumeIds", g.getVolumeIds());
                        out.add(m);
                    }
                    page = resp.getOpcNextPage();
                } while (page != null);
            }
        } catch (Exception e) {
            log.debug("listVolumeGroups {}: {}", cid, e.getMessage());
        }
    }

    private void listVolumeGroupBackups(OciClientService client, String region, String cid, String cname, List<Map<String, Object>> out) {
        try {
            String page = null;
            do {
                var resp = client.getBlockstorageClient().listVolumeGroupBackups(
                        ListVolumeGroupBackupsRequest.builder().compartmentId(cid).page(page).build());
                for (VolumeGroupBackup b : resp.getItems()) {
                    if (b.getLifecycleState() == VolumeGroupBackup.LifecycleState.Terminated) continue;
                    Map<String, Object> m = baseRow(region, cid, cname, b.getId(), b.getDisplayName(),
                            b.getLifecycleState() != null ? b.getLifecycleState().getValue() : null,
                            b.getTimeCreated() != null ? b.getTimeCreated().toString() : null);
                    m.put("sizeInGBs", b.getSizeInGBs());
                    m.put("uniqueSizeInGBs", b.getUniqueSizeInGbs());
                    m.put("volumeGroupId", b.getVolumeGroupId());
                    out.add(m);
                }
                page = resp.getOpcNextPage();
            } while (page != null);
        } catch (Exception e) {
            log.debug("listVolumeGroupBackups {}: {}", cid, e.getMessage());
        }
    }

    private void listVolumeGroupReplicas(OciClientService client, String region, String cid, String cname, List<Map<String, Object>> out,
                                         List<String> availabilityDomains) {
        try {
            Set<String> seenIds = new HashSet<>();
            for (String ad : availabilityDomains) {
                String page = null;
                do {
                    var resp = client.getBlockstorageClient().listVolumeGroupReplicas(
                            ListVolumeGroupReplicasRequest.builder()
                                    .compartmentId(cid)
                                    .availabilityDomain(ad)
                                    .page(page)
                                    .build());
                    for (VolumeGroupReplica r : resp.getItems()) {
                        if (r.getLifecycleState() == VolumeGroupReplica.LifecycleState.Terminated) continue;
                        if (!seenIds.add(r.getId())) continue;
                        Map<String, Object> m = baseRow(region, cid, cname, r.getId(), r.getDisplayName(),
                                r.getLifecycleState() != null ? r.getLifecycleState().getValue() : null,
                                r.getTimeCreated() != null ? r.getTimeCreated().toString() : null);
                        m.put("availabilityDomain", r.getAvailabilityDomain());
                        m.put("sourceVolumeGroupId", r.getVolumeGroupId());
                        m.put("timeLastSynced", r.getTimeLastSynced() != null ? r.getTimeLastSynced().toString() : null);
                        out.add(m);
                    }
                    page = resp.getOpcNextPage();
                } while (page != null);
            }
        } catch (Exception e) {
            log.debug("listVolumeGroupReplicas {}: {}", cid, e.getMessage());
        }
    }

    private void listBackupPolicies(OciClientService client, String region, String cid, String cname, List<Map<String, Object>> out) {
        try {
            String page = null;
            do {
                var resp = client.getBlockstorageClient().listVolumeBackupPolicies(
                        ListVolumeBackupPoliciesRequest.builder().compartmentId(cid).page(page).build());
                for (VolumeBackupPolicy p : resp.getItems()) {
                    Map<String, Object> m = baseRow(region, cid, cname, p.getId(), p.getDisplayName(), null, null);
                    m.put("schedules", p.getSchedules());
                    out.add(m);
                }
                page = resp.getOpcNextPage();
            } while (page != null);
        } catch (Exception e) {
            log.debug("listVolumeBackupPolicies {}: {}", cid, e.getMessage());
        }
    }

    private void collectVolumeBackupPolicyAssignments(OciClientService client, String region, String cid, String cname,
                                                    List<String> assetIds, List<Map<String, Object>> out) {
        for (String assetId : assetIds) {
            if (assetId == null || assetId.isBlank()) continue;
            try {
                String page = null;
                do {
                    var resp = client.getBlockstorageClient().getVolumeBackupPolicyAssetAssignment(
                            GetVolumeBackupPolicyAssetAssignmentRequest.builder()
                                    .assetId(assetId)
                                    .limit(100)
                                    .page(page)
                                    .build());
                    for (VolumeBackupPolicyAssignment a : resp.getItems()) {
                        Map<String, Object> m = baseRow(region, cid, cname, a.getId(), null, null,
                                a.getTimeCreated() != null ? a.getTimeCreated().toString() : null);
                        m.put("policyId", a.getPolicyId());
                        m.put("assetId", a.getAssetId());
                        out.add(m);
                    }
                    page = resp.getOpcNextPage();
                } while (page != null);
            } catch (Exception e) {
                log.debug("getVolumeBackupPolicyAssetAssignment {}: {}", assetId, e.getMessage());
            }
        }
    }

    private static List<VolumeBackupSchedule> parseVolumeBackupSchedules(List<Map<String, Object>> schedules) {
        List<VolumeBackupSchedule> built = new ArrayList<>();
        if (schedules == null) return built;
        for (Map<String, Object> s : schedules) {
            var b = VolumeBackupSchedule.builder();
            String bt = stringParam(s, "backupType");
            if (!bt.isBlank()) {
                b.backupType(VolumeBackupSchedule.BackupType.create(bt));
            }
            String period = stringParam(s, "period");
            if (!period.isBlank()) {
                b.period(VolumeBackupSchedule.Period.create(period));
            }
            String ot = stringParam(s, "offsetType");
            if (!ot.isBlank()) {
                b.offsetType(VolumeBackupSchedule.OffsetType.create(ot));
            }
            Integer os = intParam(s, "offsetSeconds");
            if (os != null) {
                b.offsetSeconds(os);
            }
            Integer hod = intParam(s, "hourOfDay");
            if (hod != null) {
                b.hourOfDay(hod);
            }
            String dow = stringParam(s, "dayOfWeek");
            if (!dow.isBlank()) {
                b.dayOfWeek(VolumeBackupSchedule.DayOfWeek.create(dow));
            }
            Integer dom = intParam(s, "dayOfMonth");
            if (dom != null) {
                b.dayOfMonth(dom);
            }
            String month = stringParam(s, "month");
            if (!month.isBlank()) {
                b.month(VolumeBackupSchedule.Month.create(month));
            }
            Integer ret = intParam(s, "retentionSeconds");
            if (ret != null) {
                b.retentionSeconds(ret);
            }
            built.add(b.build());
        }
        return built;
    }

    private void listBuckets(OciClientService client, String namespace, String region, String cid, String cname,
                             List<Map<String, Object>> out) {
        try {
            String page = null;
            do {
                var resp = client.getObjectStorageClient().listBuckets(
                        ListBucketsRequest.builder()
                                .namespaceName(namespace)
                                .compartmentId(cid)
                                .page(page)
                                .build());
                for (BucketSummary s : resp.getItems()) {
                    Map<String, Object> m = baseRow(region, cid, cname, null, s.getName(), null, null);
                    m.put("namespace", namespace);
                    m.put("name", s.getName());
                    m.put("publicAccessType", null);
                    m.put("storageTier", null);
                    m.put("createdBy", s.getCreatedBy());
                    m.put("timeCreated", s.getTimeCreated() != null ? s.getTimeCreated().toString() : null);
                    out.add(m);
                }
                page = resp.getOpcNextPage();
            } while (page != null);
        } catch (Exception e) {
            log.debug("listBuckets {}: {}", cid, e.getMessage());
        }
    }

    private void listPrivateEndpoints(OciClientService client, String namespace, String region, String cid, String cname,
                                      List<Map<String, Object>> out) {
        try {
            String page = null;
            do {
                var resp = client.getObjectStorageClient().listPrivateEndpoints(
                        ListPrivateEndpointsRequest.builder()
                                .compartmentId(cid)
                                .namespaceName(namespace)
                                .page(page)
                                .build());
                for (PrivateEndpointSummary pe : resp.getItems()) {
                    String peName = pe.getName();
                    Map<String, Object> m = baseRow(region, cid, cname, peName, peName,
                            pe.getLifecycleState() != null ? pe.getLifecycleState().getValue() : null,
                            pe.getTimeCreated() != null ? pe.getTimeCreated().toString() : null);
                    m.put("subnetId", null);
                    m.put("namespace", pe.getNamespace() != null ? pe.getNamespace() : namespace);
                    out.add(m);
                }
                page = resp.getOpcNextPage();
            } while (page != null);
        } catch (Exception e) {
            log.debug("listPrivateEndpoints {}: {}", cid, e.getMessage());
        }
    }

    private static Map<String, Object> baseRow(String region, String compartmentId, String compartmentName,
                                               String id, String displayName, String lifecycleState, String timeCreated) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("region", region);
        m.put("compartmentId", compartmentId);
        m.put("compartmentName", compartmentName);
        m.put("id", id);
        m.put("displayName", displayName);
        m.put("lifecycleState", lifecycleState);
        m.put("timeCreated", timeCreated);
        return m;
    }

    private static String summarizeAttachments(List<Map<String, Object>> attachments) {
        if (attachments == null || attachments.isEmpty()) return "未挂载";
        if (attachments.size() == 1) {
            var a = attachments.get(0);
            String nm = Objects.toString(a.get("instanceName"), "");
            if (nm.isBlank()) return "已挂载 1";
            return "已挂载: " + nm;
        }
        return "已挂载: " + attachments.size() + " 处";
    }

    private static Map<String, Object> toMap(Object model) {
        if (model == null) return Map.of();
        // minimal: rely on string representation for complex models — replaced below for key types
        Map<String, Object> m = new LinkedHashMap<>();
        if (model instanceof Volume v) {
            m.put("id", v.getId());
            m.put("displayName", v.getDisplayName());
            m.put("lifecycleState", v.getLifecycleState() != null ? v.getLifecycleState().getValue() : null);
        } else if (model instanceof BootVolume v) {
            m.put("id", v.getId());
            m.put("displayName", v.getDisplayName());
            m.put("lifecycleState", v.getLifecycleState() != null ? v.getLifecycleState().getValue() : null);
        } else if (model instanceof BootVolumeReplica v) {
            m.put("id", v.getId());
            m.put("displayName", v.getDisplayName());
            m.put("lifecycleState", v.getLifecycleState() != null ? v.getLifecycleState().getValue() : null);
        } else if (model instanceof BlockVolumeReplica v) {
            m.put("id", v.getId());
            m.put("displayName", v.getDisplayName());
            m.put("lifecycleState", v.getLifecycleState() != null ? v.getLifecycleState().getValue() : null);
        } else if (model instanceof VolumeGroup v) {
            m.put("id", v.getId());
            m.put("displayName", v.getDisplayName());
            m.put("lifecycleState", v.getLifecycleState() != null ? v.getLifecycleState().getValue() : null);
        } else if (model instanceof Bucket b) {
            m.put("name", b.getName());
            m.put("namespace", b.getNamespace());
        } else if (model instanceof PrivateEndpoint pe) {
            m.put("id", pe.getId());
            m.put("displayName", pe.getName());
        } else if (model instanceof VolumeBackupPolicy p) {
            m.put("id", p.getId());
            m.put("displayName", p.getDisplayName());
        } else if (model instanceof VolumeBackupPolicyAssignment a) {
            m.put("id", a.getId());
            m.put("assetId", a.getAssetId());
            m.put("policyId", a.getPolicyId());
        } else {
            m.put("result", String.valueOf(model));
        }
        return m;
    }

    private static String stringParam(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null) return "";
        return String.valueOf(v).trim();
    }

    private static Long longParam(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer intParam(Map<?, ?> map, String key) {
        Long v = longParam(map, key);
        return v == null ? null : v.intValue();
    }

    private OciUser requireUser(String userId) {
        OciUser u = userMapper.selectById(userId);
        if (u == null) throw new OciException("租户配置不存在");
        return u;
    }

    private SysUserDTO buildDto(OciUser ociUser) {
        return SysUserDTO.builder()
                .username(ociUser.getUsername())
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(ociUser.getOciTenantId())
                        .userId(ociUser.getOciUserId())
                        .fingerprint(ociUser.getOciFingerprint())
                        .region(ociUser.getOciRegion())
                        .privateKeyPath(ociUser.getOciKeyPath())
                        .build())
                .build();
    }
}
