package com.ociworker.service;

import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.oracle.bmc.Region;
import com.oracle.bmc.announcementsservice.AnnouncementClient;
import com.oracle.bmc.announcementsservice.model.AffectedResource;
import com.oracle.bmc.announcementsservice.model.Announcement;
import com.oracle.bmc.announcementsservice.model.AnnouncementSummary;
import com.oracle.bmc.announcementsservice.model.AnnouncementUserStatusDetails;
import com.oracle.bmc.announcementsservice.model.AnnouncementsCollection;
import com.oracle.bmc.announcementsservice.model.BaseAnnouncement;
import com.oracle.bmc.announcementsservice.requests.GetAnnouncementRequest;
import com.oracle.bmc.announcementsservice.responses.ListAnnouncementsResponse;
import com.oracle.bmc.announcementsservice.requests.GetAnnouncementUserStatusRequest;
import com.oracle.bmc.announcementsservice.requests.ListAnnouncementsRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OCI Announcements（控制台铃铛 / 云公告），数据由 Oracle 云端保留约 90 天。
 *
 * @see <a href="https://docs.oracle.com/en-us/iaas/Content/General/Concepts/announcements.htm">Announcements</a>
 */
@Slf4j
@Service
public class AnnouncementService {

    @Resource
    private OciUserMapper userMapper;

    private OciClientService buildClient(String tenantId) {
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");
        return new OciClientService(SysUserDTO.builder()
                .username(user.getUsername())
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(user.getOciTenantId())
                        .userId(user.getOciUserId())
                        .fingerprint(user.getOciFingerprint())
                        .region(user.getOciRegion())
                        .privateKeyPath(user.getOciKeyPath())
                        .build())
                .build());
    }

    private AnnouncementClient createAnnouncementClient(OciClientService oci) {
        AnnouncementClient client = AnnouncementClient.builder().build(oci.getProvider());
        String regionId = oci.getUser() != null && oci.getUser().getOciCfg() != null
                ? oci.getUser().getOciCfg().getRegion() : null;
        try {
            client.setRegion(regionId != null ? Region.fromRegionId(regionId) : Region.US_ASHBURN_1);
        } catch (Exception e) {
            client.setRegion(Region.US_ASHBURN_1);
        }
        return client;
    }

    public Map<String, Object> listAnnouncements(String tenantId) {
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");

        String compartmentId = user.getOciTenantId();
        List<Map<String, Object>> items = new ArrayList<>();

        try (OciClientService oci = buildClient(tenantId);
             AnnouncementClient client = createAnnouncementClient(oci)) {
            String page = null;
            do {
                ListAnnouncementsRequest.Builder req = ListAnnouncementsRequest.builder()
                        .compartmentId(compartmentId)
                        .lifecycleState(ListAnnouncementsRequest.LifecycleState.Active)
                        .sortBy(ListAnnouncementsRequest.SortBy.TimeCreated)
                        .sortOrder(ListAnnouncementsRequest.SortOrder.Desc)
                        .limit(100);
                if (page != null) req.page(page);
                ListAnnouncementsResponse resp = client.listAnnouncements(req.build());
                appendListRows(items, resp, true);
                page = resp.getOpcNextPage();
            } while (page != null && !page.isBlank());
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            log.warn("listAnnouncements failed for {}: {}", tenantId, e.getMessage());
            throw new OciException("获取云公告失败: " + e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("compartmentId", compartmentId);
        out.put("items", items);
        out.put("count", items.size());
        out.put("retentionNote", "公告由 Oracle 云端保留约 90 天，面板仅实时查询，不做本地归档。");
        return out;
    }

    public Map<String, Object> getAnnouncementDetail(String tenantId, String announcementId) {
        if (announcementId == null || announcementId.isBlank()) {
            throw new OciException("announcementId 不能为空");
        }
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");

        try (OciClientService oci = buildClient(tenantId);
             AnnouncementClient client = createAnnouncementClient(oci)) {
            Announcement a = client.getAnnouncement(
                    GetAnnouncementRequest.builder().announcementId(announcementId).build()).getAnnouncement();
            if (a == null) throw new OciException("公告不存在");

            Map<String, Object> detail = toDetailMap(a);
            detail.put("userStatus", fetchUserStatusLabel(client, announcementId));

            List<Map<String, Object>> impacted = new ArrayList<>();
            if (a.getAffectedResources() != null) {
                for (AffectedResource r : a.getAffectedResources()) {
                    if (r == null) continue;
                    Map<String, Object> rm = new LinkedHashMap<>();
                    rm.put("resourceId", r.getResourceId());
                    rm.put("resourceName", r.getResourceName());
                    rm.put("region", r.getRegion());
                    rm.put("additionalProperties", r.getAdditionalProperties());
                    impacted.add(rm);
                }
            }

            List<Map<String, Object>> history = new ArrayList<>();
            String chainId = a.getChainId();
            if (chainId != null && !chainId.isBlank()) {
                history = listByChainId(client, user.getOciTenantId(), chainId, announcementId);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("detail", detail);
            out.put("impactedResources", impacted);
            out.put("history", history);
            return out;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            log.warn("getAnnouncementDetail {} failed: {}", announcementId, e.getMessage());
            throw new OciException("获取公告详情失败: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> listByChainId(
            AnnouncementClient client, String compartmentId, String chainId, String excludeId) {
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            String page = null;
            do {
                ListAnnouncementsRequest.Builder req = ListAnnouncementsRequest.builder()
                        .compartmentId(compartmentId)
                        .chainId(chainId)
                        .sortBy(ListAnnouncementsRequest.SortBy.TimeCreated)
                        .sortOrder(ListAnnouncementsRequest.SortOrder.Desc)
                        .limit(100);
                if (page != null) req.page(page);
                ListAnnouncementsResponse resp = client.listAnnouncements(req.build());
                AnnouncementsCollection coll = resp.getAnnouncementsCollection();
                if (coll != null && coll.getItems() != null) {
                    for (AnnouncementSummary a : coll.getItems()) {
                        if (a.getId() != null && a.getId().equals(excludeId)) continue;
                        items.add(toSummaryMap(a));
                    }
                }
                page = resp.getOpcNextPage();
            } while (page != null && !page.isBlank());
        } catch (Exception e) {
            log.warn("listByChainId failed chainId={}: {}", chainId, e.getMessage());
        }
        return items;
    }

    private static void appendListRows(List<Map<String, Object>> target, ListAnnouncementsResponse resp, boolean withUserStatus) {
        AnnouncementsCollection coll = resp.getAnnouncementsCollection();
        if (coll == null || coll.getItems() == null) return;
        Map<String, String> statusById = withUserStatus ? userStatusMap(coll.getUserStatuses()) : Map.of();
        for (AnnouncementSummary a : coll.getItems()) {
            Map<String, Object> row = toSummaryMap(a);
            if (withUserStatus) {
                String id = a.getId();
                row.put("userStatus", id != null && statusById.containsKey(id)
                        ? statusById.get(id)
                        : "Unread");
            }
            target.add(row);
        }
    }

    private static Map<String, String> userStatusMap(List<AnnouncementUserStatusDetails> statuses) {
        Map<String, String> map = new HashMap<>();
        if (statuses == null) return map;
        for (AnnouncementUserStatusDetails s : statuses) {
            if (s == null || s.getUserStatusAnnouncementId() == null) continue;
            map.put(s.getUserStatusAnnouncementId(),
                    s.getTimeAcknowledged() != null ? "Read" : "Unread");
        }
        return map;
    }

    private static String fetchUserStatusLabel(AnnouncementClient client, String announcementId) {
        if (announcementId == null) return "—";
        try {
            var status = client.getAnnouncementUserStatus(
                    GetAnnouncementUserStatusRequest.builder().announcementId(announcementId).build())
                    .getAnnouncementUserStatusDetails();
            if (status != null && status.getTimeAcknowledged() != null) {
                return "Read";
            }
            return "Unread";
        } catch (Exception e) {
            log.debug("getAnnouncementUserStatus {}: {}", announcementId, e.getMessage());
            return "—";
        }
    }

    private static Map<String, Object> toSummaryMap(BaseAnnouncement a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("summary", a.getSummary());
        m.put("referenceTicketNumber", a.getReferenceTicketNumber());
        m.put("announcementType", enumVal(a.getAnnouncementType()));
        m.put("lifecycleState", enumVal(a.getLifecycleState()));
        m.put("platformType", enumVal(a.getPlatformType()));
        m.put("environmentName", a.getEnvironmentName());
        m.put("services", a.getServices());
        m.put("affectedRegions", a.getAffectedRegions());
        m.put("timeCreated", a.getTimeCreated());
        m.put("timeUpdated", a.getTimeUpdated());
        m.put("timeOneTitle", a.getTimeOneTitle());
        m.put("timeOneType", enumVal(a.getTimeOneType()));
        m.put("timeOneValue", a.getTimeOneValue());
        m.put("timeTwoTitle", a.getTimeTwoTitle());
        m.put("timeTwoType", enumVal(a.getTimeTwoType()));
        m.put("timeTwoValue", a.getTimeTwoValue());
        m.put("chainId", a.getChainId());
        m.put("isBanner", a.getIsBanner());
        return m;
    }

    private static Map<String, Object> toDetailMap(Announcement a) {
        Map<String, Object> m = toSummaryMap(a);
        m.put("description", a.getDescription());
        m.put("additionalInformation", a.getAdditionalInformation());
        return m;
    }

    private static String enumVal(Object e) {
        if (e == null) return null;
        try {
            return (String) e.getClass().getMethod("getValue").invoke(e);
        } catch (Exception ignored) {
            return String.valueOf(e);
        }
    }
}
