package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.oracle.bmc.core.requests.ChangeInstanceCompartmentRequest;
import com.oracle.bmc.core.requests.ChangeBootVolumeCompartmentRequest;
import com.oracle.bmc.core.requests.ChangeVolumeCompartmentRequest;
import com.oracle.bmc.core.model.ChangeInstanceCompartmentDetails;
import com.oracle.bmc.core.model.ChangeBootVolumeCompartmentDetails;
import com.oracle.bmc.core.model.ChangeVolumeCompartmentDetails;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.Compartment;
import com.oracle.bmc.identity.model.CreateCompartmentDetails;
import com.oracle.bmc.identity.model.Tenancy;
import com.oracle.bmc.identity.model.MoveCompartmentDetails;
import com.oracle.bmc.identity.model.UpdateCompartmentDetails;
import com.oracle.bmc.identity.requests.*;
import com.oracle.bmc.identity.responses.*;
import com.oracle.bmc.resourcesearch.ResourceSearchClient;
import com.oracle.bmc.resourcesearch.model.ResourceSummary;
import com.oracle.bmc.resourcesearch.model.StructuredSearchDetails;
import com.oracle.bmc.resourcesearch.requests.SearchResourcesRequest;
import com.oracle.bmc.resourcesearch.responses.SearchResourcesResponse;
import com.oracle.bmc.model.BmcException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * OCI 区间（Compartment）管理 — Identity API 20160918 + Resource Search。
 *
 * @see <a href="https://docs.oracle.com/iaas/Content/Identity/Tasks/managingcompartments.htm">Managing Compartments</a>
 */
@Slf4j
@Service
public class CompartmentService {

    private static final Set<String> MOVEABLE_RESOURCE_TYPES = Set.of(
            "Instance", "Volume", "BootVolume"
    );

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

    private static String tenancyId(OciUser user) {
        return user.getOciTenantId();
    }

    /**
     * @param parentId 为空或等于 tenancyId 时：根区间 + 根下<strong>直接</strong>子区间（与控制台一致）；否则仅该父级下直接子区间
     */
    public Map<String, Object> listCompartments(String tenantId, String parentId, String keyword) {
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");
        String tenancy = tenancyId(user);
        boolean atRoot = StrUtil.isBlank(parentId) || tenancy.equals(parentId.trim());

        try (OciClientService client = buildClient(tenantId)) {
            IdentityClient identity = client.getIdentityClient();
            Tenancy tenancyInfo = identity.getTenancy(
                    GetTenancyRequest.builder().tenancyId(tenancy).build()).getTenancy();

            List<Compartment> subtree = listCompartmentsPaginated(identity, tenancy, true);
            subtree = subtree.stream()
                    .filter(c -> c.getLifecycleState() != Compartment.LifecycleState.Deleted)
                    .collect(Collectors.toList());
            Map<String, Integer> childCounts = buildChildCounts(subtree, tenancy);

            List<Map<String, Object>> items = new ArrayList<>();
            String listParentId = atRoot ? tenancy : parentId.trim();
            for (Compartment c : subtree) {
                if (listParentId.equals(c.getCompartmentId())) {
                    items.add(compartmentRow(c, childCounts, false));
                }
            }
            items.sort(Comparator.comparing(m -> String.valueOf(m.get("name")), String.CASE_INSENSITIVE_ORDER));
            if (atRoot) {
                items.add(compartmentRow(
                        tenancy,
                        tenancyInfo.getName() + " (root)",
                        tenancyInfo.getDescription(),
                        Compartment.LifecycleState.Active.getValue(),
                        tenancy,
                        childCounts.getOrDefault(tenancy, 0),
                        null,
                        true));
            }

            if (StrUtil.isNotBlank(keyword)) {
                String kw = keyword.trim().toLowerCase();
                items = items.stream()
                        .filter(m -> String.valueOf(m.get("name")).toLowerCase().contains(kw)
                                || String.valueOf(m.get("id")).toLowerCase().contains(kw))
                        .collect(Collectors.toList());
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tenancyId", tenancy);
            out.put("tenancyName", tenancyInfo.getName());
            out.put("parentId", atRoot ? tenancy : parentId.trim());
            out.put("flatSubtree", false);
            out.put("directChildrenOnly", true);
            out.put("items", items);
            out.put("count", items.size());
            out.put("breadcrumb", buildBreadcrumb(subtree, tenancy, tenancyInfo.getName(), atRoot ? tenancy : parentId.trim()));
            return out;
        } catch (OciException e) {
            throw e;
        } catch (BmcException e) {
            throw new OciException("获取区间列表失败: " + ociMessage(e));
        } catch (Exception e) {
            log.warn("listCompartments failed: {}", e.getMessage());
            throw new OciException("获取区间列表失败: " + e.getMessage());
        }
    }

    /** 全租户区间扁平列表（路径标签），供资源迁移等下拉选择 */
    public Map<String, Object> listCompartmentsPicker(String tenantId) {
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");
        String tenancy = tenancyId(user);
        try (OciClientService client = buildClient(tenantId)) {
            IdentityClient identity = client.getIdentityClient();
            Tenancy tenancyInfo = identity.getTenancy(
                    GetTenancyRequest.builder().tenancyId(tenancy).build()).getTenancy();
            String rootName = tenancyInfo.getName();
            List<Compartment> subtree = listCompartmentsPaginated(identity, tenancy, true);
            Map<String, Compartment> byId = new HashMap<>();
            for (Compartment c : subtree) {
                if (c.getId() != null) byId.put(c.getId(), c);
            }

            List<Map<String, Object>> items = new ArrayList<>();
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("id", tenancy);
            root.put("name", rootName + " (root)");
            root.put("pathLabel", rootName + " (root)");
            root.put("root", true);
            root.put("lifecycleState", "ACTIVE");
            items.add(root);

            for (Compartment c : subtree) {
                String state = stateName(c.getLifecycleState());
                if (!"ACTIVE".equals(state)) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("name", c.getName());
                m.put("pathLabel", buildPathLabel(c.getId(), tenancy, rootName, byId));
                m.put("root", false);
                m.put("lifecycleState", state);
                items.add(m);
            }
            items.sort(Comparator.comparing(o -> String.valueOf(o.get("pathLabel")), String.CASE_INSENSITIVE_ORDER));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tenancyId", tenancy);
            out.put("items", items);
            return out;
        } catch (OciException e) {
            throw e;
        } catch (BmcException e) {
            throw new OciException("获取区间列表失败: " + ociMessage(e));
        } catch (Exception e) {
            log.warn("listCompartmentsPicker failed: {}", e.getMessage());
            throw new OciException("获取区间列表失败: " + e.getMessage());
        }
    }

    public Map<String, Object> getCompartment(String tenantId, String compartmentId) {
        if (StrUtil.isBlank(compartmentId)) throw new OciException("compartmentId 不能为空");
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");
        String tenancy = tenancyId(user);
        String cid = compartmentId.trim();
        boolean isRoot = tenancy.equals(cid);

        try (OciClientService client = buildClient(tenantId)) {
            IdentityClient identity = client.getIdentityClient();
            Map<String, Object> detail = new LinkedHashMap<>();
            if (isRoot) {
                Tenancy t = identity.getTenancy(GetTenancyRequest.builder().tenancyId(tenancy).build()).getTenancy();
                detail.put("id", tenancy);
                detail.put("name", t.getName() + " (root)");
                detail.put("description", t.getDescription());
                detail.put("lifecycleState", "ACTIVE");
                detail.put("parentId", null);
                detail.put("root", true);
                detail.put("timeCreated", null);
            } else {
                Compartment c = identity.getCompartment(
                        GetCompartmentRequest.builder().compartmentId(cid).build()).getCompartment();
                detail.put("id", c.getId());
                detail.put("name", c.getName());
                detail.put("description", c.getDescription());
                detail.put("lifecycleState", stateName(c.getLifecycleState()));
                detail.put("parentId", c.getCompartmentId());
                detail.put("root", false);
                detail.put("timeCreated", c.getTimeCreated());
            }

            List<Compartment> subtree = listCompartmentsPaginated(identity, tenancy, true);
            subtree = subtree.stream()
                    .filter(c -> c.getLifecycleState() != Compartment.LifecycleState.Deleted)
                    .collect(Collectors.toList());
            Map<String, Integer> childCounts = buildChildCounts(subtree, tenancy);
            detail.put("childCount", childCounts.getOrDefault(cid, 0));

            List<Map<String, Object>> children = new ArrayList<>();
            for (Compartment c : subtree) {
                if (cid.equals(c.getCompartmentId())) {
                    children.add(compartmentRow(c, childCounts, false));
                }
            }
            detail.put("children", children);

            String rootName = identity.getTenancy(GetTenancyRequest.builder().tenancyId(tenancy).build())
                    .getTenancy().getName();
            detail.put("breadcrumb", buildBreadcrumb(subtree, tenancy, rootName, cid));
            return detail;
        } catch (OciException e) {
            throw e;
        } catch (BmcException e) {
            throw new OciException("获取区间详情失败: " + ociMessage(e));
        } catch (Exception e) {
            throw new OciException("获取区间详情失败: " + e.getMessage());
        }
    }

    public Map<String, Object> createCompartment(String tenantId, String parentId, String name, String description) {
        validateCompartmentName(name);
        if (StrUtil.isBlank(parentId)) throw new OciException("父区间不能为空");
        OciUser user = userMapper.selectById(tenantId);
        String tenancy = tenancyId(user);
        String parent = parentId.trim();
        if (!tenancy.equals(parent) && !parent.startsWith("ocid1.compartment.")) {
            throw new OciException("父区间 OCID 无效");
        }
        try (OciClientService client = buildClient(tenantId)) {
            CreateCompartmentResponse resp = client.getIdentityClient().createCompartment(
                    CreateCompartmentRequest.builder()
                            .createCompartmentDetails(CreateCompartmentDetails.builder()
                                    .compartmentId(parent)
                                    .name(name.trim())
                                    .description(StrUtil.blankToDefault(description, name.trim()))
                                    .build())
                            .build());
            Compartment c = resp.getCompartment();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", c.getId());
            out.put("name", c.getName());
            out.put("lifecycleState", stateName(c.getLifecycleState()));
            return out;
        } catch (BmcException e) {
            throw new OciException("创建区间失败: " + ociMessage(e));
        }
    }

    public Map<String, Object> updateCompartment(String tenantId, String compartmentId, String name, String description) {
        if (StrUtil.isBlank(compartmentId)) throw new OciException("compartmentId 不能为空");
        OciUser user = userMapper.selectById(tenantId);
        if (tenancyId(user).equals(compartmentId.trim())) {
            throw new OciException("根区间（tenancy）不能在此重命名");
        }
        if (name != null && !name.isBlank()) validateCompartmentName(name);
        UpdateCompartmentDetails.Builder b = UpdateCompartmentDetails.builder();
        if (name != null && !name.isBlank()) b.name(name.trim());
        if (description != null) b.description(description);
        try (OciClientService client = buildClient(tenantId)) {
            Compartment c = client.getIdentityClient().updateCompartment(
                    UpdateCompartmentRequest.builder()
                            .compartmentId(compartmentId.trim())
                            .updateCompartmentDetails(b.build())
                            .build()).getCompartment();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", c.getId());
            out.put("name", c.getName());
            out.put("description", c.getDescription());
            out.put("lifecycleState", stateName(c.getLifecycleState()));
            return out;
        } catch (BmcException e) {
            throw new OciException("更新区间失败: " + ociMessage(e));
        }
    }

    public void deleteCompartment(String tenantId, String compartmentId) {
        OciUser user = userMapper.selectById(tenantId);
        if (tenancyId(user).equals(compartmentId.trim())) {
            throw new OciException("不能删除根区间（tenancy）");
        }
        try (OciClientService client = buildClient(tenantId)) {
            client.getIdentityClient().deleteCompartment(
                    DeleteCompartmentRequest.builder().compartmentId(compartmentId.trim()).build());
        } catch (BmcException e) {
            throw new OciException("删除区间失败: " + ociMessage(e));
        }
    }

    public Map<String, Object> moveCompartment(String tenantId, String compartmentId, String newParentId) {
        if (StrUtil.isBlank(compartmentId) || StrUtil.isBlank(newParentId)) {
            throw new OciException("compartmentId 与 newParentId 不能为空");
        }
        OciUser user = userMapper.selectById(tenantId);
        if (tenancyId(user).equals(compartmentId.trim())) {
            throw new OciException("不能移动根区间");
        }
        try (OciClientService client = buildClient(tenantId)) {
            MoveCompartmentResponse resp = client.getIdentityClient().moveCompartment(
                    MoveCompartmentRequest.builder()
                            .compartmentId(compartmentId.trim())
                            .moveCompartmentDetails(MoveCompartmentDetails.builder()
                                    .targetCompartmentId(newParentId.trim())
                                    .build())
                            .build());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("compartmentId", compartmentId.trim());
            out.put("targetParentId", newParentId.trim());
            out.put("workRequestId", resp.getOpcWorkRequestId());
            return out;
        } catch (BmcException e) {
            throw new OciException("移动区间失败: " + ociMessage(e));
        }
    }

    /**
     * Resource Search — 区间内资源（与控制台 compartment explorer 同类能力）。
     */
    public Map<String, Object> listResources(String tenantId, String compartmentId, String pageToken, Integer limit) {
        if (StrUtil.isBlank(compartmentId)) throw new OciException("compartmentId 不能为空");
        int lim = limit == null || limit < 1 ? 50 : Math.min(limit, 100);
        OciUser user = userMapper.selectById(tenantId);
        if (user == null) throw new OciException("租户配置不存在");

        try (OciClientService client = buildClient(tenantId)) {
            ResourceSearchClient searchClient = ResourceSearchClient.builder().build(client.getProvider());
            try {
            String query = "query all resources where compartmentId = '" + compartmentId.trim().replace("'", "\\'") + "'";
            SearchResourcesRequest.Builder req = SearchResourcesRequest.builder()
                    .searchDetails(StructuredSearchDetails.builder().query(query).build())
                    .limit(lim)
                    .tenantId(tenancyId(user));
            if (StrUtil.isNotBlank(pageToken)) req.page(pageToken);

            SearchResourcesResponse resp = searchClient.searchResources(req.build());
            List<Map<String, Object>> items = new ArrayList<>();
            List<ResourceSummary> summaries = resp.getResourceSummaryCollection() != null
                    ? resp.getResourceSummaryCollection().getItems()
                    : null;
            if (summaries != null) {
                for (ResourceSummary r : summaries) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("identifier", r.getIdentifier());
                    m.put("displayName", r.getDisplayName());
                    m.put("resourceType", r.getResourceType());
                    m.put("lifecycleState", r.getLifecycleState());
                    m.put("compartmentId", r.getCompartmentId());
                    m.put("timeCreated", r.getTimeCreated());
                    m.put("moveable", MOVEABLE_RESOURCE_TYPES.contains(r.getResourceType()));
                    items.add(m);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", items);
            out.put("count", items.size());
            out.put("opcNextPage", resp.getOpcNextPage());
            out.put("moveableTypes", MOVEABLE_RESOURCE_TYPES);
            return out;
            } finally {
                searchClient.close();
            }
        } catch (OciException e) {
            throw e;
        } catch (BmcException e) {
            throw new OciException("查询区间资源失败: " + ociMessage(e));
        } catch (Exception e) {
            throw new OciException("查询区间资源失败: " + e.getMessage());
        }
    }

    public void moveResource(String tenantId, String resourceId, String resourceType, String targetCompartmentId) {
        if (StrUtil.isBlank(resourceId) || StrUtil.isBlank(resourceType) || StrUtil.isBlank(targetCompartmentId)) {
            throw new OciException("resourceId、resourceType、targetCompartmentId 不能为空");
        }
        String type = resourceType.trim();
        if (!MOVEABLE_RESOURCE_TYPES.contains(type)) {
            throw new OciException("暂不支持迁移资源类型: " + type + "，请在 OCI 控制台操作");
        }
        try (OciClientService client = buildClient(tenantId)) {
            switch (type) {
                case "Instance" -> client.getComputeClient().changeInstanceCompartment(
                        ChangeInstanceCompartmentRequest.builder()
                                .instanceId(resourceId.trim())
                                .changeInstanceCompartmentDetails(
                                        ChangeInstanceCompartmentDetails.builder()
                                                .compartmentId(targetCompartmentId.trim())
                                                .build())
                                .build());
                case "Volume" -> client.getBlockstorageClient().changeVolumeCompartment(
                        ChangeVolumeCompartmentRequest.builder()
                                .volumeId(resourceId.trim())
                                .changeVolumeCompartmentDetails(
                                        ChangeVolumeCompartmentDetails.builder()
                                                .compartmentId(targetCompartmentId.trim())
                                                .build())
                                .build());
                case "BootVolume" -> client.getBlockstorageClient().changeBootVolumeCompartment(
                        ChangeBootVolumeCompartmentRequest.builder()
                                .bootVolumeId(resourceId.trim())
                                .changeBootVolumeCompartmentDetails(
                                        ChangeBootVolumeCompartmentDetails.builder()
                                                .compartmentId(targetCompartmentId.trim())
                                                .build())
                                .build());
                default -> throw new OciException("不支持的资源类型: " + type);
            }
        } catch (BmcException e) {
            throw new OciException("迁移资源失败: " + ociMessage(e));
        }
    }

    private List<Compartment> listCompartmentsPaginated(IdentityClient identity, String tenancyId, boolean subtree) {
        List<Compartment> all = new ArrayList<>();
        for (Compartment.LifecycleState state : List.of(
                Compartment.LifecycleState.Active,
                Compartment.LifecycleState.Deleting)) {
            String page = null;
            do {
                ListCompartmentsRequest.Builder b = ListCompartmentsRequest.builder()
                        .compartmentId(tenancyId)
                        .accessLevel(ListCompartmentsRequest.AccessLevel.Accessible)
                        .compartmentIdInSubtree(subtree)
                        .lifecycleState(state);
                if (page != null) b.page(page);
                ListCompartmentsResponse resp = identity.listCompartments(b.build());
                if (resp.getItems() != null) all.addAll(resp.getItems());
                page = resp.getOpcNextPage();
            } while (page != null && !page.isBlank());
        }
        Map<String, Compartment> byId = new LinkedHashMap<>();
        for (Compartment c : all) {
            if (c.getId() != null) byId.putIfAbsent(c.getId(), c);
        }
        return new ArrayList<>(byId.values());
    }

    private static Map<String, Integer> buildChildCounts(List<Compartment> subtree, String tenancyId) {
        Map<String, Integer> counts = new HashMap<>();
        counts.put(tenancyId, 0);
        for (Compartment c : subtree) {
            String pid = c.getCompartmentId();
            if (pid != null) counts.merge(pid, 1, Integer::sum);
        }
        return counts;
    }

    private static Map<String, Object> compartmentRow(Compartment c, Map<String, Integer> childCounts, boolean root) {
        return compartmentRow(
                c.getId(),
                c.getName(),
                c.getDescription(),
                stateName(c.getLifecycleState()),
                c.getCompartmentId(),
                childCounts.getOrDefault(c.getId(), 0),
                c.getTimeCreated(),
                root);
    }

    private static Map<String, Object> compartmentRow(
            String id, String name, String description, String state,
            String parentId, int childCount, Date timeCreated, boolean root) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description);
        m.put("lifecycleState", state);
        m.put("parentId", parentId);
        m.put("childCount", childCount);
        m.put("timeCreated", timeCreated);
        m.put("root", root);
        return m;
    }

    private static String buildPathLabel(String compartmentId, String tenancyId, String rootName, Map<String, Compartment> byId) {
        if (tenancyId.equals(compartmentId)) return rootName + " (root)";
        Deque<String> names = new ArrayDeque<>();
        String cur = compartmentId;
        int guard = 0;
        while (cur != null && !tenancyId.equals(cur) && guard++ < 32) {
            Compartment c = byId.get(cur);
            if (c == null) break;
            names.addFirst(c.getName());
            cur = c.getCompartmentId();
        }
        List<String> parts = new ArrayList<>();
        parts.add(rootName + " (root)");
        parts.addAll(names);
        return String.join(" / ", parts);
    }

    private static List<Map<String, String>> buildBreadcrumb(
            List<Compartment> subtree, String tenancyId, String rootName, String currentId) {
        Map<String, Compartment> byId = new HashMap<>();
        for (Compartment c : subtree) byId.put(c.getId(), c);
        List<Map<String, String>> chain = new ArrayList<>();
        chain.add(Map.of("id", tenancyId, "name", rootName + " (root)"));
        if (tenancyId.equals(currentId)) return chain;
        Deque<String> ids = new ArrayDeque<>();
        String cur = currentId;
        int guard = 0;
        while (cur != null && !tenancyId.equals(cur) && guard++ < 20) {
            ids.addFirst(cur);
            Compartment c = byId.get(cur);
            if (c == null) break;
            cur = c.getCompartmentId();
        }
        for (String id : ids) {
            Compartment c = byId.get(id);
            chain.add(Map.of("id", id, "name", c != null ? c.getName() : id));
        }
        return chain;
    }

    private static void validateCompartmentName(String name) {
        if (name == null || name.isBlank()) throw new OciException("区间名称不能为空");
        String n = name.trim();
        if (n.length() > 100) throw new OciException("区间名称不能超过 100 个字符");
    }

    private static String stateName(Compartment.LifecycleState s) {
        return s == null ? "—" : s.getValue();
    }

    private static String ociMessage(BmcException e) {
        return e.getMessage() != null ? e.getMessage() : ("HTTP " + e.getStatusCode());
    }
}
