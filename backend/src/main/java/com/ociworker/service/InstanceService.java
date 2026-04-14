package com.ociworker.service;

import com.oracle.bmc.core.model.BootVolume;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.Vnic;
import com.oracle.bmc.core.model.VnicAttachment;
import com.oracle.bmc.core.requests.*;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class InstanceService {

    @Resource
    private OciUserMapper userMapper;

    public List<Map<String, Object>> listInstances(String userId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            List<Instance> instances = client.listAllInstances();
            return instances.stream().map(inst -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("instanceId", inst.getId());
                map.put("name", inst.getDisplayName());
                map.put("region", inst.getRegion());
                map.put("shape", inst.getShape());
                map.put("state", inst.getLifecycleState().getValue());
                map.put("timeCreated", inst.getTimeCreated() != null ? inst.getTimeCreated().toString() : null);
                map.put("availabilityDomain", inst.getAvailabilityDomain());

                if (inst.getShapeConfig() != null) {
                    map.put("ocpus", inst.getShapeConfig().getOcpus());
                    map.put("memoryInGBs", inst.getShapeConfig().getMemoryInGBs());
                }

                String publicIp = client.getInstancePublicIp(inst);
                map.put("publicIp", publicIp);
                return map;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to list instances: {}", e.getMessage());
            throw new OciException("获取实例列表失败: " + e.getMessage());
        }
    }

    public void updateInstanceState(String userId, String instanceId, String action) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            InstanceActionRequest request = InstanceActionRequest.builder()
                    .instanceId(instanceId)
                    .action(action)
                    .build();
            client.getComputeClient().instanceAction(request);
            log.info("Instance {} action: {}", instanceId, action);
        } catch (Exception e) {
            throw new OciException("操作失败: " + e.getMessage());
        }
    }

    public void terminateInstance(String userId, String instanceId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            client.getComputeClient().terminateInstance(
                    TerminateInstanceRequest.builder()
                            .instanceId(instanceId)
                            .preserveBootVolume(false)
                            .build());
            log.info("Instance terminated: {}", instanceId);
        } catch (Exception e) {
            throw new OciException("终止实例失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listBootVolumes(String userId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            var ads = client.getAvailabilityDomains();
            List<Map<String, Object>> result = new ArrayList<>();
            for (var ad : ads) {
                List<BootVolume> volumes = client.getBlockstorageClient().listBootVolumes(
                        ListBootVolumesRequest.builder()
                                .compartmentId(client.getCompartmentId())
                                .availabilityDomain(ad.getName())
                                .build()
                ).getItems();
                for (BootVolume vol : volumes) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", vol.getId());
                    map.put("displayName", vol.getDisplayName());
                    map.put("sizeInGBs", vol.getSizeInGBs());
                    map.put("state", vol.getLifecycleState().getValue());
                    map.put("timeCreated", vol.getTimeCreated() != null ? vol.getTimeCreated().toString() : null);
                    result.add(map);
                }
            }
            return result;
        } catch (Exception e) {
            throw new OciException("获取引导卷列表失败: " + e.getMessage());
        }
    }

    private SysUserDTO buildBasicDTO(OciUser ociUser) {
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
