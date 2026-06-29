package com.ociworker.service;

import com.oracle.bmc.core.model.*;
import com.oracle.bmc.core.requests.*;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.ociworker.util.OciBmcErrorTranslator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * BYOIP（Bring Your Own IP）— 对齐 OCI 官方流程：
 * 导入 → RIR 验证 token/ROA → Finish Import → 分到公网 IP 池（IPv4）→ Advertise → 创建 Reserved 公网 IP。
 * IPv6 通过 addIpv6VcnCidr + Byoipv6CidrDetails 分配到 VCN。
 */
@Slf4j
@Service
public class ByoipService {

    /** 商业云 Oracle BGP ASN（塞尔维亚 Jovanovac 等区域为 14544，见官方文档） */
    public static final int ORACLE_BGP_ASN_COMMERCIAL = 31898;
    private static final Duration BYOIP_READ_CACHE_TTL = Duration.ofSeconds(45);

    @Resource
    private OciUserMapper userMapper;
    @Resource
    private OciReadCacheService ociReadCacheService;

    private String tag(OciUser u) {
        return "[" + u.getUsername() + "] ";
    }

    private OciClientService oci(OciUser ociUser, String region) {
        String r = (region == null || region.isBlank()) ? null : region.trim();
        return new OciClientService(buildBasicDTO(ociUser), r);
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

  private String extractOciErrorMessage(com.oracle.bmc.model.BmcException e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            return "OCI 调用失败（无详细信息）";
        }
        if (msg.contains("LimitExceeded")) {
            return "已超出配额限制。请在 OCI 控制台申请提升 BYOIP / 公网 IP 限额。";
        }
        if (msg.contains("NotAuthorizedOrNotFound")) {
            return "权限不足或资源不存在。请确认 IAM 策略含网络 BYOIP 相关权限。";
        }
        return OciBmcErrorTranslator.translate(e);
    }

    private OciUser requireUser(String userId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");
        return ociUser;
    }

    public static String formatOciValidationToken(String cidrBlock, String ipv6CidrBlock, String validationToken) {
        if (validationToken == null || validationToken.isBlank()) return "";
        String cidr = (ipv6CidrBlock != null && !ipv6CidrBlock.isBlank()) ? ipv6CidrBlock.trim() : cidrBlock;
        if (cidr == null || cidr.isBlank()) return validationToken.trim();
        return "OCITOKEN::" + cidr.trim() + ":" + validationToken.trim();
    }

    private Map<String, Object> mapByoipRange(ByoipRange r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.getId());
        map.put("compartmentId", r.getCompartmentId());
        map.put("displayName", r.getDisplayName());
        map.put("cidrBlock", r.getCidrBlock());
        map.put("ipv6CidrBlock", r.getIpv6CidrBlock());
        map.put("ipVersion", r.getIpv6CidrBlock() != null && !r.getIpv6CidrBlock().isBlank() ? "IPV6" : "IPV4");
        map.put("lifecycleState", r.getLifecycleState() != null ? r.getLifecycleState().getValue() : null);
        map.put("lifecycleDetails", r.getLifecycleDetails() != null ? r.getLifecycleDetails().getValue() : null);
        map.put("validationToken", r.getValidationToken());
        map.put("ociValidationToken", formatOciValidationToken(r.getCidrBlock(), r.getIpv6CidrBlock(), r.getValidationToken()));
        map.put("timeCreated", r.getTimeCreated() != null ? r.getTimeCreated().toString() : null);
        map.put("timeValidated", r.getTimeValidated() != null ? r.getTimeValidated().toString() : null);
        map.put("timeAdvertised", r.getTimeAdvertised() != null ? r.getTimeAdvertised().toString() : null);
        map.put("timeWithdrawn", r.getTimeWithdrawn() != null ? r.getTimeWithdrawn().toString() : null);
        if (r.getByoipRangeVcnIpv6Allocations() != null) {
            map.put("vcnIpv6Allocations", r.getByoipRangeVcnIpv6Allocations().stream().map(a -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("vcnId", a.getVcnId());
                m.put("ipv6CidrBlock", a.getIpv6CidrBlock());
                return m;
            }).collect(Collectors.toList()));
        } else {
            map.put("vcnIpv6Allocations", List.of());
        }
        return map;
    }

    private Map<String, Object> mapByoipRangeSummary(ByoipRangeSummary r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.getId());
        map.put("compartmentId", r.getCompartmentId());
        map.put("displayName", r.getDisplayName());
        map.put("cidrBlock", r.getCidrBlock());
        map.put("ipv6CidrBlock", r.getIpv6CidrBlock());
        map.put("ipVersion", r.getIpv6CidrBlock() != null && !r.getIpv6CidrBlock().isBlank() ? "IPV6" : "IPV4");
        map.put("lifecycleState", r.getLifecycleState() != null ? r.getLifecycleState().getValue() : null);
        map.put("lifecycleDetails", r.getLifecycleDetails() != null ? r.getLifecycleDetails().getValue() : null);
        map.put("timeCreated", r.getTimeCreated() != null ? r.getTimeCreated().toString() : null);
        if (r.getByoipRangeVcnIpv6Allocations() != null) {
            map.put("vcnIpv6Allocations", r.getByoipRangeVcnIpv6Allocations().stream().map(a -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("vcnId", a.getVcnId());
                m.put("ipv6CidrBlock", a.getIpv6CidrBlock());
                return m;
            }).collect(Collectors.toList()));
        } else {
            map.put("vcnIpv6Allocations", List.of());
        }
        return map;
    }

    public List<Map<String, Object>> listByoipRanges(String userId, String region) {
        return listByoipRanges(userId, region, false);
    }

    public List<Map<String, Object>> listByoipRanges(String userId, String region, boolean force) {
        OciUser ociUser = requireUser(userId);
        return ociReadCacheService.get(byoipCacheKey("ranges", ociUser, region), BYOIP_READ_CACHE_TTL, force,
                () -> fetchByoipRanges(ociUser, region));
    }

    private List<Map<String, Object>> fetchByoipRanges(OciUser ociUser, String region) {
        try (OciClientService client = oci(ociUser, region)) {
            var coll = client.getVirtualNetworkClient().listByoipRanges(
                    ListByoipRangesRequest.builder()
                            .compartmentId(client.getCompartmentId())
                            .build()
            ).getByoipRangeCollection();
            if (coll == null || coll.getItems() == null) {
                return List.of();
            }
            return coll.getItems().stream().map(this::mapByoipRangeSummary).collect(Collectors.toList());
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "获取 BYOIP 网段失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "获取 BYOIP 网段失败: " + e.getMessage());
        }
    }

    public Map<String, Object> getByoipRange(String userId, String byoipRangeId, String region) {
        OciUser ociUser = requireUser(userId);
        try (OciClientService client = oci(ociUser, region)) {
            ByoipRange r = client.getVirtualNetworkClient().getByoipRange(
                    GetByoipRangeRequest.builder().byoipRangeId(byoipRangeId).build()
            ).getByoipRange();
            return mapByoipRange(r);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "获取 BYOIP 详情失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "获取 BYOIP 详情失败: " + e.getMessage());
        }
    }

    public Map<String, Object> createByoipRange(String userId, String displayName, String cidrBlock,
                                                 String ipv6CidrBlock, String region) {
        OciUser ociUser = requireUser(userId);
        boolean hasV4 = cidrBlock != null && !cidrBlock.isBlank();
        boolean hasV6 = ipv6CidrBlock != null && !ipv6CidrBlock.isBlank();
        if (!hasV4 && !hasV6) {
            throw new OciException("请填写 IPv4 CIDR（如 203.0.113.0/24）或 IPv6 前缀（/48 或更大）");
        }
        if (hasV4 && hasV6) {
            throw new OciException("一次只能导入 IPv4 CIDR 或 IPv6 前缀其一");
        }

        try (OciClientService client = oci(ociUser, region)) {
            CreateByoipRangeDetails.Builder b = CreateByoipRangeDetails.builder()
                    .compartmentId(client.getCompartmentId());
            if (displayName != null && !displayName.isBlank()) {
                b.displayName(displayName.trim());
            }
            if (hasV4) b.cidrBlock(cidrBlock.trim());
            if (hasV6) b.ipv6CidrBlock(ipv6CidrBlock.trim());

            ByoipRange created = client.getVirtualNetworkClient().createByoipRange(
                    CreateByoipRangeRequest.builder().createByoipRangeDetails(b.build()).build()
            ).getByoipRange();
            log.info("BYOIP range created: {}", created.getId());
            evictByoipReadCaches(ociUser, region);
            return mapByoipRange(created);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "创建 BYOIP 导入请求失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "创建 BYOIP 导入请求失败: " + e.getMessage());
        }
    }

    public Map<String, Object> updateByoipRange(String userId, String byoipRangeId, String displayName, String region) {
        OciUser ociUser = requireUser(userId);
        try (OciClientService client = oci(ociUser, region)) {
            UpdateByoipRangeDetails.Builder b = UpdateByoipRangeDetails.builder();
            if (displayName != null && !displayName.isBlank()) {
                b.displayName(displayName.trim());
            }
            ByoipRange updated = client.getVirtualNetworkClient().updateByoipRange(
                    UpdateByoipRangeRequest.builder()
                            .byoipRangeId(byoipRangeId)
                            .updateByoipRangeDetails(b.build())
                            .build()
            ).getByoipRange();
            evictByoipReadCaches(ociUser, region);
            return mapByoipRange(updated);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "更新 BYOIP 失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "更新 BYOIP 失败: " + e.getMessage());
        }
    }

    public void deleteByoipRange(String userId, String byoipRangeId, String region) {
        OciUser ociUser = requireUser(userId);
        try (OciClientService client = oci(ociUser, region)) {
            client.getVirtualNetworkClient().deleteByoipRange(
                    DeleteByoipRangeRequest.builder().byoipRangeId(byoipRangeId).build());
            log.info("BYOIP range deleted: {}", byoipRangeId);
            evictByoipReadCaches(ociUser, region);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "删除 BYOIP 网段失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "删除 BYOIP 网段失败: " + e.getMessage());
        }
    }

    /** 对应控制台 Finish Import；Oracle 与 RIR 校验，最长约 10 个工作日 */
    public void validateByoipRange(String userId, String byoipRangeId, String region) {
        OciUser ociUser = requireUser(userId);
        try (OciClientService client = oci(ociUser, region)) {
            client.getVirtualNetworkClient().validateByoipRange(
                    ValidateByoipRangeRequest.builder().byoipRangeId(byoipRangeId).build());
            log.info("BYOIP validate requested: {}", byoipRangeId);
            evictByoipReadCaches(ociUser, region);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "提交 BYOIP 校验失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "提交 BYOIP 校验失败: " + e.getMessage());
        }
    }

    /** 须 lifecycle 为 PROVISIONED；开始 BGP 宣告后公网方可路由到自有网段 */
    public void advertiseByoipRange(String userId, String byoipRangeId, String region) {
        OciUser ociUser = requireUser(userId);
        try (OciClientService client = oci(ociUser, region)) {
            client.getVirtualNetworkClient().advertiseByoipRange(
                    AdvertiseByoipRangeRequest.builder().byoipRangeId(byoipRangeId).build());
            log.info("BYOIP advertise requested: {}", byoipRangeId);
            evictByoipReadCaches(ociUser, region);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "宣告 BYOIP 失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "宣告 BYOIP 失败: " + e.getMessage());
        }
    }

    public void withdrawByoipRange(String userId, String byoipRangeId, String region) {
        OciUser ociUser = requireUser(userId);
        try (OciClientService client = oci(ociUser, region)) {
            client.getVirtualNetworkClient().withdrawByoipRange(
                    WithdrawByoipRangeRequest.builder().byoipRangeId(byoipRangeId).build());
            log.info("BYOIP withdraw requested: {}", byoipRangeId);
            evictByoipReadCaches(ociUser, region);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "撤回 BYOIP 宣告失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "撤回 BYOIP 宣告失败: " + e.getMessage());
        }
    }

    public void changeByoipRangeCompartment(String userId, String byoipRangeId, String compartmentId, String region) {
        OciUser ociUser = requireUser(userId);
        if (compartmentId == null || compartmentId.isBlank()) {
            throw new OciException("目标区间不能为空");
        }
        try (OciClientService client = oci(ociUser, region)) {
            client.getVirtualNetworkClient().changeByoipRangeCompartment(
                    ChangeByoipRangeCompartmentRequest.builder()
                            .byoipRangeId(byoipRangeId)
                            .changeByoipRangeCompartmentDetails(
                                    ChangeByoipRangeCompartmentDetails.builder()
                                            .compartmentId(compartmentId.trim())
                                            .build())
                            .build());
            evictByoipReadCaches(ociUser, region);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "移动 BYOIP 区间失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "移动 BYOIP 区间失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listByoipAllocatedRanges(String userId, String byoipRangeId, String region) {
        return listByoipAllocatedRanges(userId, byoipRangeId, region, false);
    }

    public List<Map<String, Object>> listByoipAllocatedRanges(String userId, String byoipRangeId, String region, boolean force) {
        OciUser ociUser = requireUser(userId);
        return ociReadCacheService.get(byoipCacheKey("allocatedRanges", ociUser, region, normalizeBlank(byoipRangeId)),
                BYOIP_READ_CACHE_TTL, force, () -> fetchByoipAllocatedRanges(ociUser, byoipRangeId, region));
    }

    private List<Map<String, Object>> fetchByoipAllocatedRanges(OciUser ociUser, String byoipRangeId, String region) {
        try (OciClientService client = oci(ociUser, region)) {
            var allocColl = client.getVirtualNetworkClient().listByoipAllocatedRanges(
                    ListByoipAllocatedRangesRequest.builder().byoipRangeId(byoipRangeId).build()
            ).getByoipAllocatedRangeCollection();
            if (allocColl == null || allocColl.getItems() == null) {
                return List.of();
            }
            return allocColl.getItems().stream().map(a -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("cidrBlock", a.getCidrBlock());
                m.put("publicIpPoolId", a.getPublicIpPoolId());
                m.put("byoipRangeId", byoipRangeId);
                return m;
            }).collect(Collectors.toList());
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "获取已分配子网段失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "获取已分配子网段失败: " + e.getMessage());
        }
    }

    // ---------- Public IP Pool ----------

    public List<Map<String, Object>> listPublicIpPools(String userId, String byoipRangeId, String region) {
        return listPublicIpPools(userId, byoipRangeId, region, false);
    }

    public List<Map<String, Object>> listPublicIpPools(String userId, String byoipRangeId, String region, boolean force) {
        OciUser ociUser = requireUser(userId);
        return ociReadCacheService.get(byoipCacheKey("publicIpPools", ociUser, region, normalizeBlank(byoipRangeId)),
                BYOIP_READ_CACHE_TTL, force, () -> fetchPublicIpPools(ociUser, byoipRangeId, region));
    }

    private List<Map<String, Object>> fetchPublicIpPools(OciUser ociUser, String byoipRangeId, String region) {
        try (OciClientService client = oci(ociUser, region)) {
            ListPublicIpPoolsRequest.Builder req = ListPublicIpPoolsRequest.builder()
                    .compartmentId(client.getCompartmentId());
            if (byoipRangeId != null && !byoipRangeId.isBlank()) {
                req.byoipRangeId(byoipRangeId.trim());
            }
            var poolColl = client.getVirtualNetworkClient().listPublicIpPools(req.build())
                    .getPublicIpPoolCollection();
            if (poolColl == null || poolColl.getItems() == null) {
                return List.of();
            }
            return poolColl.getItems().stream().map(this::mapPublicIpPoolSummary).collect(Collectors.toList());
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "获取公网 IP 池失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "获取公网 IP 池失败: " + e.getMessage());
        }
    }

    private Map<String, Object> mapPublicIpPool(PublicIpPool p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", p.getId());
        map.put("displayName", p.getDisplayName());
        map.put("cidrBlocks", p.getCidrBlocks() != null ? p.getCidrBlocks() : List.of());
        map.put("lifecycleState", p.getLifecycleState() != null ? p.getLifecycleState().getValue() : null);
        map.put("timeCreated", p.getTimeCreated() != null ? p.getTimeCreated().toString() : null);
        return map;
    }

    private Map<String, Object> mapPublicIpPoolSummary(PublicIpPoolSummary p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", p.getId());
        map.put("displayName", p.getDisplayName());
        map.put("cidrBlocks", List.of());
        map.put("lifecycleState", p.getLifecycleState() != null ? p.getLifecycleState().getValue() : null);
        map.put("timeCreated", p.getTimeCreated() != null ? p.getTimeCreated().toString() : null);
        return map;
    }

    public Map<String, Object> createPublicIpPool(String userId, String displayName, String region) {
        OciUser ociUser = requireUser(userId);
        try (OciClientService client = oci(ociUser, region)) {
            CreatePublicIpPoolDetails.Builder b = CreatePublicIpPoolDetails.builder()
                    .compartmentId(client.getCompartmentId());
            if (displayName != null && !displayName.isBlank()) {
                b.displayName(displayName.trim());
            }
            PublicIpPool pool = client.getVirtualNetworkClient().createPublicIpPool(
                    CreatePublicIpPoolRequest.builder().createPublicIpPoolDetails(b.build()).build()
            ).getPublicIpPool();
            evictByoipReadCaches(ociUser, region);
            return mapPublicIpPool(pool);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "创建公网 IP 池失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "创建公网 IP 池失败: " + e.getMessage());
        }
    }

    public Map<String, Object> updatePublicIpPool(String userId, String publicIpPoolId, String displayName, String region) {
        OciUser ociUser = requireUser(userId);
        try (OciClientService client = oci(ociUser, region)) {
            UpdatePublicIpPoolDetails.Builder b = UpdatePublicIpPoolDetails.builder();
            if (displayName != null && !displayName.isBlank()) {
                b.displayName(displayName.trim());
            }
            PublicIpPool pool = client.getVirtualNetworkClient().updatePublicIpPool(
                    UpdatePublicIpPoolRequest.builder()
                            .publicIpPoolId(publicIpPoolId)
                            .updatePublicIpPoolDetails(b.build())
                            .build()
            ).getPublicIpPool();
            evictByoipReadCaches(ociUser, region);
            return mapPublicIpPool(pool);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "更新公网 IP 池失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "更新公网 IP 池失败: " + e.getMessage());
        }
    }

    public void deletePublicIpPool(String userId, String publicIpPoolId, String region) {
        OciUser ociUser = requireUser(userId);
        try (OciClientService client = oci(ociUser, region)) {
            client.getVirtualNetworkClient().deletePublicIpPool(
                    DeletePublicIpPoolRequest.builder().publicIpPoolId(publicIpPoolId).build());
            evictByoipReadCaches(ociUser, region);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "删除公网 IP 池失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "删除公网 IP 池失败: " + e.getMessage());
        }
    }

    /** 将 BYOIP 子网段加入公网 IP 池 */
    public void addPublicIpPoolCapacity(String userId, String publicIpPoolId, String byoipRangeId,
                                        String cidrBlock, String region) {
        OciUser ociUser = requireUser(userId);
        if (publicIpPoolId == null || publicIpPoolId.isBlank() || byoipRangeId == null || byoipRangeId.isBlank()
                || cidrBlock == null || cidrBlock.isBlank()) {
            throw new OciException("公网 IP 池、BYOIP 网段与子网 CIDR 均不能为空");
        }
        try (OciClientService client = oci(ociUser, region)) {
            client.getVirtualNetworkClient().addPublicIpPoolCapacity(
                    AddPublicIpPoolCapacityRequest.builder()
                            .publicIpPoolId(publicIpPoolId.trim())
                            .addPublicIpPoolCapacityDetails(AddPublicIpPoolCapacityDetails.builder()
                                    .byoipRangeId(byoipRangeId.trim())
                                    .cidrBlock(cidrBlock.trim())
                                    .build())
                            .build());
            log.info("Added {} from BYOIP {} to pool {}", cidrBlock, byoipRangeId, publicIpPoolId);
            evictByoipReadCaches(ociUser, region);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "向 IP 池添加 BYOIP 容量失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "向 IP 池添加 BYOIP 容量失败: " + e.getMessage());
        }
    }

    public void removePublicIpPoolCapacity(String userId, String publicIpPoolId, String cidrBlock, String region) {
        OciUser ociUser = requireUser(userId);
        try (OciClientService client = oci(ociUser, region)) {
            client.getVirtualNetworkClient().removePublicIpPoolCapacity(
                    RemovePublicIpPoolCapacityRequest.builder()
                            .publicIpPoolId(publicIpPoolId)
                            .removePublicIpPoolCapacityDetails(RemovePublicIpPoolCapacityDetails.builder()
                                    .cidrBlock(cidrBlock.trim())
                                    .build())
                            .build());
            evictByoipReadCaches(ociUser, region);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "从 IP 池移除 BYOIP 容量失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "从 IP 池移除 BYOIP 容量失败: " + e.getMessage());
        }
    }

    // ---------- Public IP from BYOIP pool ----------

    public Map<String, String> createByoipReservedIp(String userId, String displayName, String publicIpPoolId, String region) {
        OciUser ociUser = requireUser(userId);
        if (publicIpPoolId == null || publicIpPoolId.isBlank()) {
            throw new OciException("请选择公网 IP 池");
        }
        try (OciClientService client = oci(ociUser, region)) {
            CreatePublicIpDetails.Builder builder = CreatePublicIpDetails.builder()
                    .compartmentId(client.getCompartmentId())
                    .lifetime(CreatePublicIpDetails.Lifetime.Reserved)
                    .publicIpPoolId(publicIpPoolId.trim());
            if (displayName != null && !displayName.isBlank()) {
                builder.displayName(displayName.trim());
            }
            PublicIp ip = client.getVirtualNetworkClient().createPublicIp(
                    CreatePublicIpRequest.builder().createPublicIpDetails(builder.build()).build()
            ).getPublicIp();
            evictByoipReadCaches(ociUser, region);
            return Map.of(
                    "publicIpId", ip.getId(),
                    "ipAddress", ip.getIpAddress() != null ? ip.getIpAddress() : ""
            );
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "从 BYOIP 池创建公网 IP 失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "从 BYOIP 池创建公网 IP 失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listByoipPublicIps(String userId, String region) {
        return listByoipPublicIps(userId, region, false);
    }

    public List<Map<String, Object>> listByoipPublicIps(String userId, String region, boolean force) {
        OciUser ociUser = requireUser(userId);
        return ociReadCacheService.get(byoipCacheKey("publicIps", ociUser, region),
                BYOIP_READ_CACHE_TTL, force, () -> fetchByoipPublicIps(ociUser, region));
    }

    private List<Map<String, Object>> fetchByoipPublicIps(OciUser ociUser, String region) {
        try (OciClientService client = oci(ociUser, region)) {
            List<PublicIp> publicIps = client.getVirtualNetworkClient().listPublicIps(
                    ListPublicIpsRequest.builder()
                            .compartmentId(client.getCompartmentId())
                            .scope(ListPublicIpsRequest.Scope.Region)
                            .lifetime(ListPublicIpsRequest.Lifetime.Reserved)
                            .build()
            ).getItems();
            return publicIps.stream()
                    .filter(ip -> ip.getPublicIpPoolId() != null && !ip.getPublicIpPoolId().isBlank())
                    .map(ip -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("id", ip.getId());
                        map.put("ipAddress", ip.getIpAddress());
                        map.put("displayName", ip.getDisplayName());
                        map.put("publicIpPoolId", ip.getPublicIpPoolId());
                        map.put("lifecycleState", ip.getLifecycleState() != null ? ip.getLifecycleState().getValue() : null);
                        map.put("isAssigned", ip.getAssignedEntityId() != null);
                        map.put("assignedEntityId", ip.getAssignedEntityId());
                        map.put("timeCreated", ip.getTimeCreated() != null ? ip.getTimeCreated().toString() : null);
                        return map;
                    }).collect(Collectors.toList());
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "获取 BYOIP 公网 IP 失败: " + e.getMessage());
        }
    }

    /** IPv6 BYOIP：将前缀段分配到 VCN（官方 addIpv6VcnCidr + Byoipv6CidrDetails） */
    public void assignByoipv6ToVcn(String userId, String vcnId, String byoipRangeId, String ipv6CidrBlock, String region) {
        OciUser ociUser = requireUser(userId);
        if (vcnId == null || vcnId.isBlank() || byoipRangeId == null || byoipRangeId.isBlank()
                || ipv6CidrBlock == null || ipv6CidrBlock.isBlank()) {
            throw new OciException("VCN、BYOIP 网段与 IPv6 子网前缀均不能为空");
        }
        try (OciClientService client = oci(ociUser, region)) {
            client.getVirtualNetworkClient().addIpv6VcnCidr(
                    AddIpv6VcnCidrRequest.builder()
                            .vcnId(vcnId.trim())
                            .addVcnIpv6CidrDetails(AddVcnIpv6CidrDetails.builder()
                                    .byoipv6CidrDetail(Byoipv6CidrDetails.builder()
                                            .byoipv6RangeId(byoipRangeId.trim())
                                            .ipv6CidrBlock(ipv6CidrBlock.trim())
                                            .build())
                                    .build())
                            .build());
            log.info("BYOIPv6 {} assigned to VCN {}", ipv6CidrBlock, vcnId);
            evictByoipReadCaches(ociUser, region);
            evictVcnReadCaches(ociUser, region);
        } catch (com.oracle.bmc.model.BmcException e) {
            throw new OciException(tag(ociUser) + "分配 BYOIPv6 到 VCN 失败: " + extractOciErrorMessage(e));
        } catch (Exception e) {
            throw new OciException(tag(ociUser) + "分配 BYOIPv6 到 VCN 失败: " + e.getMessage());
        }
    }

    private void evictByoipReadCaches(OciUser user, String region) {
        if (user == null) {
            return;
        }
        ociReadCacheService.evictByPrefix(byoipCachePrefix(user, region) + "|");
    }

    private void evictVcnReadCaches(OciUser user, String region) {
        if (user == null) {
            return;
        }
        ociReadCacheService.evictByPrefix(VcnService.vcnCachePrefix(user, region) + "|");
    }

    private static String byoipCacheKey(String type, OciUser user, String region, Object... parts) {
        Object[] all = new Object[(parts == null ? 0 : parts.length) + 1];
        all[0] = type;
        if (parts != null && parts.length > 0) {
            System.arraycopy(parts, 0, all, 1, parts.length);
        }
        return OciReadCacheService.key(byoipCachePrefix(user, region), all);
    }

    private static String byoipCachePrefix(OciUser user, String region) {
        return OciReadCacheService.key(
                "oci:byoip",
                user.getId(),
                user.getOciTenantId(),
                configuredRegion(user),
                effectiveRegion(user, region));
    }

    private static String configuredRegion(OciUser user) {
        return user.getOciRegion() == null ? "" : user.getOciRegion().trim();
    }

    private static String effectiveRegion(OciUser user, String region) {
        String r = normalizeBlank(region);
        return r.isEmpty() ? configuredRegion(user) : r;
    }

    private static String normalizeBlank(String value) {
        return value == null ? "" : value.trim();
    }

    public Map<String, Object> getByoipHelp() {
        Map<String, Object> help = new LinkedHashMap<>();
        help.put("oracleBgpAsn", ORACLE_BGP_ASN_COMMERCIAL);
        help.put("oracleBgpAsnNote", "塞尔维亚 Jovanovac 等区域 ASN 为 14544；美国政府云见 OCI 文档");
        help.put("ipv4CidrLimits", "/24 至 /8");
        help.put("ipv6PrefixLimits", "/48 或更大");
        help.put("maxRangesPerTenancy", 20);
        help.put("freeTierSupported", false);
        help.put("validationDays", "最长约 10 个工作日");
        help.put("steps", List.of(
                "1. 在本面板「导入网段」创建 ByoipRange，复制 OCITOKEN 验证串",
                "2. 在 RIR（ARIN/RIPE/APNIC）添加验证 token，并创建 ROA 授权 Oracle ASN",
                "3. 点击「完成导入校验」(validateByoipRange)",
                "4. IPv4：创建/选择公网 IP 池，将 BYOIP 子网段加入池",
                "5. 状态 PROVISIONED 后点击「BGP 宣告」(advertise)",
                "6. 从 IP 池创建 Reserved 公网 IP，绑定实例/LB/NAT",
                "7. IPv6：校验通过后使用「分配到 VCN」，再在子网/实例上配置 IPv6"
        ));
        help.put("docUrl", "https://docs.oracle.com/en-us/iaas/Content/Network/Concepts/BYOIP.htm");
        return help;
    }
}
