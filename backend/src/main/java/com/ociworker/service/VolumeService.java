package com.ociworker.service;

import com.oracle.bmc.core.model.*;
import com.oracle.bmc.core.requests.*;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class VolumeService {

    @Resource
    private OciUserMapper userMapper;

    public List<Map<String, Object>> listAllVolumes(String userId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            var compartments = client.listAllCompartments();
            List<String> ads = client.getAvailabilityDomains().stream()
                    .map(ad -> ad.getName())
                    .filter(n -> n != null && !n.isBlank())
                    .distinct()
                    .toList();
            List<Map<String, Object>> result = new ArrayList<>();

            for (var compartment : compartments) {
                String cid = compartment.getId();

                // Boot Volumes（SDK 3.83+ ListBootVolumes 需 availabilityDomain）
                Set<String> seenBoot = new HashSet<>();
                for (String ad : ads) {
                    try {
                        String page = null;
                        do {
                            var bootResp = client.getBlockstorageClient().listBootVolumes(
                                    ListBootVolumesRequest.builder()
                                            .compartmentId(cid)
                                            .availabilityDomain(ad)
                                            .page(page)
                                            .build());
                            for (var bv : bootResp.getItems()) {
                                if (bv.getLifecycleState() == BootVolume.LifecycleState.Terminated) continue;
                                if (!seenBoot.add(bv.getId())) continue;
                                result.add(volumeMap("BOOT", bv.getId(), bv.getDisplayName(),
                                        bv.getSizeInGBs(), bv.getLifecycleState().getValue(),
                                        bv.getTimeCreated() != null ? bv.getTimeCreated().toString() : null, null));
                            }
                            page = bootResp.getOpcNextPage();
                        } while (page != null);
                    } catch (Exception e) {
                        log.debug("listBootVolumes in {} AD {} failed: {}", cid, ad, e.getMessage());
                    }
                }

                // Block Volumes
                Set<String> seenBlock = new HashSet<>();
                for (String ad : ads) {
                    try {
                        String page = null;
                        do {
                            var volResp = client.getBlockstorageClient().listVolumes(
                                    ListVolumesRequest.builder()
                                            .compartmentId(cid)
                                            .availabilityDomain(ad)
                                            .page(page)
                                            .build());
                            for (var v : volResp.getItems()) {
                                if (v.getLifecycleState() == Volume.LifecycleState.Terminated) continue;
                                if (!seenBlock.add(v.getId())) continue;
                                result.add(volumeMap("BLOCK", v.getId(), v.getDisplayName(),
                                        v.getSizeInGBs(), v.getLifecycleState().getValue(),
                                        v.getTimeCreated() != null ? v.getTimeCreated().toString() : null, null));
                            }
                            page = volResp.getOpcNextPage();
                        } while (page != null);
                    } catch (Exception e) {
                        log.debug("listVolumes in {} AD {} failed: {}", cid, ad, e.getMessage());
                    }
                }

                // Boot Volume Backups
                try {
                    var backups = client.getBlockstorageClient().listBootVolumeBackups(
                            ListBootVolumeBackupsRequest.builder().compartmentId(cid).build()
                    ).getItems();
                    for (var b : backups) {
                        if (b.getLifecycleState() == BootVolumeBackup.LifecycleState.Terminated) continue;
                        result.add(volumeMap("BOOT_BACKUP", b.getId(), b.getDisplayName(),
                                b.getSizeInGBs(), b.getLifecycleState().getValue(),
                                b.getTimeCreated() != null ? b.getTimeCreated().toString() : null,
                                b.getBootVolumeId()));
                    }
                } catch (Exception e) {
                    log.debug("listBootVolumeBackups in {} failed: {}", cid, e.getMessage());
                }

                // Volume Backups
                try {
                    var backups = client.getBlockstorageClient().listVolumeBackups(
                            ListVolumeBackupsRequest.builder().compartmentId(cid).build()
                    ).getItems();
                    for (var b : backups) {
                        if (b.getLifecycleState() == VolumeBackup.LifecycleState.Terminated) continue;
                        result.add(volumeMap("BLOCK_BACKUP", b.getId(), b.getDisplayName(),
                                b.getSizeInGBs(), b.getLifecycleState().getValue(),
                                b.getTimeCreated() != null ? b.getTimeCreated().toString() : null,
                                b.getVolumeId()));
                    }
                } catch (Exception e) {
                    log.debug("listVolumeBackups in {} failed: {}", cid, e.getMessage());
                }
            }

            return result;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("查询卷列表失败: " + e.getMessage());
        }
    }

    public void deleteVolume(String userId, String type, String volumeId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = buildBasicDTO(ociUser);
        try (OciClientService client = new OciClientService(dto)) {
            switch (type) {
                case "BOOT" -> client.getBlockstorageClient().deleteBootVolume(
                        DeleteBootVolumeRequest.builder().bootVolumeId(volumeId).build());
                case "BLOCK" -> client.getBlockstorageClient().deleteVolume(
                        DeleteVolumeRequest.builder().volumeId(volumeId).build());
                case "BOOT_BACKUP" -> client.getBlockstorageClient().deleteBootVolumeBackup(
                        DeleteBootVolumeBackupRequest.builder().bootVolumeBackupId(volumeId).build());
                case "BLOCK_BACKUP" -> client.getBlockstorageClient().deleteVolumeBackup(
                        DeleteVolumeBackupRequest.builder().volumeBackupId(volumeId).build());
                default -> throw new OciException("未知卷类型: " + type);
            }
            log.info("Volume deleted: type={}, id={}", type, volumeId);
        } catch (OciException e) {
            throw e;
        } catch (com.oracle.bmc.model.BmcException e) {
            if (e.getStatusCode() == 409) {
                throw new OciException("该卷当前状态不允许删除（可能正在使用或复制中），请稍后再试");
            }
            throw new OciException("删除卷失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        } catch (Exception e) {
            throw new OciException("删除卷失败: " + e.getMessage());
        }
    }

    private Map<String, Object> volumeMap(String type, String id, String displayName,
                                          Long sizeInGBs, String state, String timeCreated, String sourceId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("id", id);
        map.put("displayName", displayName);
        map.put("sizeInGBs", sizeInGBs);
        map.put("lifecycleState", state);
        map.put("timeCreated", timeCreated);
        if (sourceId != null) map.put("sourceId", sourceId);
        return map;
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
