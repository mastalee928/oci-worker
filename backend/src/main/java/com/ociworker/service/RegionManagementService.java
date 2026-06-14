package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.CreateRegionSubscriptionDetails;
import com.oracle.bmc.identity.model.Region;
import com.oracle.bmc.identity.model.RegionSubscription;
import com.oracle.bmc.identity.requests.CreateRegionSubscriptionRequest;
import com.oracle.bmc.identity.requests.GetTenancyRequest;
import com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest;
import com.oracle.bmc.identity.requests.ListRegionsRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * OCI tenancy 区域订阅管理。
 *
 * @see <a href="https://docs.oracle.com/en-us/iaas/Content/Identity/Tasks/managingregions.htm">Managing Regions</a>
 */
@Slf4j
@Service
public class RegionManagementService {

    @Resource
    private OciUserMapper userMapper;

    public Map<String, Object> listRegions(String userId) {
        OciUser user = requireUser(userId);
        try (OciClientService client = new OciClientService(buildDto(user))) {
            IdentityClient identity = client.getIdentityClient();
            List<RegionSubscription> subscriptions = listSubscriptions(identity, user.getOciTenantId());
            String homeRegionName = resolveHomeRegionName(identity, user.getOciTenantId(), user.getOciRegion(), subscriptions);
            String homeRegionKey = resolveHomeRegionKey(identity, user.getOciTenantId(), subscriptions);
            List<Region> available = identity.listRegions(ListRegionsRequest.builder().build()).getItems();
            return buildRegionList(user, available, subscriptions, homeRegionKey, homeRegionName);
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(user) + "获取区域列表失败: " + safeMessage(e));
        }
    }

    public Map<String, Object> subscribeRegion(String userId, String regionKey) {
        OciUser user = requireUser(userId);
        String key = normalizeRegionKey(regionKey);
        if (StrUtil.isBlank(key)) {
            throw new OciException("regionKey 不能为空");
        }

        try (OciClientService client = new OciClientService(buildDto(user))) {
            IdentityClient identity = client.getIdentityClient();
            List<RegionSubscription> before = listSubscriptions(identity, user.getOciTenantId());
            RegionSubscription existing = findSubscription(before, key);
            if (existing != null) {
                throw new OciException("该区域已订阅或正在订阅");
            }

            String homeRegionName = resolveHomeRegionName(identity, user.getOciTenantId(), user.getOciRegion(), before);
            if (StrUtil.isNotBlank(homeRegionName)) {
                identity.setRegion(homeRegionName);
            }

            RegionSubscription created = identity.createRegionSubscription(CreateRegionSubscriptionRequest.builder()
                    .tenancyId(user.getOciTenantId())
                    .opcRetryToken(UUID.randomUUID().toString())
                    .createRegionSubscriptionDetails(CreateRegionSubscriptionDetails.builder()
                            .regionKey(key)
                            .build())
                    .build()).getRegionSubscription();

            return regionRow(null, created, resolveHomeRegionKey(identity, user.getOciTenantId(), before));
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException(tag(user) + "订阅区域失败: " + safeMessage(e));
        }
    }

    private Map<String, Object> buildRegionList(
            OciUser user,
            List<Region> available,
            List<RegionSubscription> subscriptions,
            String homeRegionKey,
            String homeRegionName) {
        Map<String, RegionSubscription> subscriptionByKey = new HashMap<>();
        for (RegionSubscription sub : subscriptions) {
            String key = normalizeRegionKey(sub.getRegionKey());
            if (StrUtil.isNotBlank(key)) {
                subscriptionByKey.put(key, sub);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        if (available != null) {
            for (Region region : available) {
                String key = normalizeRegionKey(region.getKey());
                RegionSubscription sub = subscriptionByKey.remove(key);
                rows.add(regionRow(region, sub, homeRegionKey));
            }
        }
        for (RegionSubscription sub : subscriptionByKey.values()) {
            rows.add(regionRow(null, sub, homeRegionKey));
        }

        rows.sort(Comparator
                .comparing((Map<String, Object> r) -> !Boolean.TRUE.equals(r.get("isHomeRegion")))
                .thenComparing(r -> String.valueOf(r.getOrDefault("regionName", "")), String.CASE_INSENSITIVE_ORDER));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", user.getOciTenantId());
        out.put("tenantName", user.getTenantName());
        out.put("configuredRegion", user.getOciRegion());
        out.put("homeRegionKey", homeRegionKey);
        out.put("homeRegionName", homeRegionName);
        out.put("items", rows);
        String consoleRegion = StrUtil.isNotBlank(homeRegionName) ? homeRegionName : user.getOciRegion();
        Map<String, Object> links = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(consoleRegion)) {
            links.put("regions", "https://cloud.oracle.com/regions?region=" + consoleRegion);
        }
        out.put("links", links);
        return out;
    }

    private Map<String, Object> regionRow(Region region, RegionSubscription subscription, String homeRegionKey) {
        String regionKey = normalizeRegionKey(region != null ? region.getKey() : null);
        if (StrUtil.isBlank(regionKey) && subscription != null) {
            regionKey = normalizeRegionKey(subscription.getRegionKey());
        }
        String regionName = region != null ? region.getName() : null;
        if (StrUtil.isBlank(regionName) && subscription != null) {
            regionName = subscription.getRegionName();
        }
        boolean subscribed = subscription != null;
        String status = subscribed ? enumValue(subscription.getStatus()) : "NOT_SUBSCRIBED";
        boolean home = subscribed && Boolean.TRUE.equals(subscription.getIsHomeRegion());
        if (!home && StrUtil.isNotBlank(homeRegionKey) && homeRegionKey.equalsIgnoreCase(regionKey)) {
            home = true;
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("regionKey", regionKey);
        row.put("regionName", regionName);
        row.put("status", status);
        row.put("subscribed", subscribed);
        row.put("isHomeRegion", home);
        row.put("canSubscribe", !subscribed && StrUtil.isNotBlank(regionKey));
        return row;
    }

    private List<RegionSubscription> listSubscriptions(IdentityClient identity, String tenancyId) {
        var items = identity.listRegionSubscriptions(ListRegionSubscriptionsRequest.builder()
                .tenancyId(tenancyId)
                .build()).getItems();
        return items == null ? List.of() : items;
    }

    private RegionSubscription findSubscription(List<RegionSubscription> subscriptions, String regionKey) {
        if (subscriptions == null || StrUtil.isBlank(regionKey)) {
            return null;
        }
        for (RegionSubscription sub : subscriptions) {
            if (regionKey.equalsIgnoreCase(normalizeRegionKey(sub.getRegionKey()))) {
                return sub;
            }
        }
        return null;
    }

    private String resolveHomeRegionName(
            IdentityClient identity,
            String tenancyId,
            String fallbackRegionName,
            List<RegionSubscription> subscriptions) {
        if (subscriptions != null) {
            for (RegionSubscription sub : subscriptions) {
                if (Boolean.TRUE.equals(sub.getIsHomeRegion()) && StrUtil.isNotBlank(sub.getRegionName())) {
                    return sub.getRegionName();
                }
            }
        }
        String homeKey = resolveHomeRegionKey(identity, tenancyId, subscriptions);
        if (StrUtil.isNotBlank(homeKey) && subscriptions != null) {
            for (RegionSubscription sub : subscriptions) {
                if (homeKey.equalsIgnoreCase(normalizeRegionKey(sub.getRegionKey()))
                        && StrUtil.isNotBlank(sub.getRegionName())) {
                    return sub.getRegionName();
                }
            }
        }
        return fallbackRegionName;
    }

    private String resolveHomeRegionKey(
            IdentityClient identity,
            String tenancyId,
            List<RegionSubscription> subscriptions) {
        if (subscriptions != null) {
            for (RegionSubscription sub : subscriptions) {
                if (Boolean.TRUE.equals(sub.getIsHomeRegion()) && StrUtil.isNotBlank(sub.getRegionKey())) {
                    return normalizeRegionKey(sub.getRegionKey());
                }
            }
        }
        try {
            var tenancy = identity.getTenancy(GetTenancyRequest.builder()
                    .tenancyId(tenancyId)
                    .build()).getTenancy();
            return tenancy == null ? null : normalizeRegionKey(tenancy.getHomeRegionKey());
        } catch (Exception e) {
            log.warn("Failed to resolve home region key for tenancy {}: {}", tenancyId, e.getMessage());
            return null;
        }
    }

    private OciUser requireUser(String userId) {
        if (StrUtil.isBlank(userId)) {
            throw new OciException("缺少租户 id");
        }
        OciUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new OciException("租户配置不存在");
        }
        return user;
    }

    private SysUserDTO buildDto(OciUser user) {
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

    private static String normalizeRegionKey(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v.toUpperCase(Locale.ROOT);
    }

    private static String enumValue(Object e) {
        if (e == null) return null;
        try {
            Object v = e.getClass().getMethod("getValue").invoke(e);
            return v == null ? null : String.valueOf(v);
        } catch (Exception ignored) {
            return String.valueOf(e);
        }
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String tag(OciUser user) {
        return "[" + (Objects.toString(user.getUsername(), "")) + "] ";
    }
}
