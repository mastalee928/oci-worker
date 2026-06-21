package com.ociworker.util;

import com.ociworker.exception.OciException;
import com.oracle.bmc.Region;
import com.oracle.bmc.Realm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 公共区域中文名与下拉行结构：{@link #listUiRows()} 为 SDK 枚举全集；{@link #listUiRowsForIds(Collection)} 为 tenancy 已订阅子集。
 */
public final class OciRegionCatalog {

    private static final Map<String, String> ZH_LABELS = new LinkedHashMap<>();
    private static final Map<String, String> EXTRA_REGION_CODES = new LinkedHashMap<>();
    private static final Pattern PUBLIC_OC1_REGION_ID =
            Pattern.compile("^[a-z]{2}-[a-z0-9]+(?:-[a-z0-9]+)*-\\d+$");

    static {
        ZH_LABELS.put("us-ashburn-1", "美国东部（阿什本）");
        ZH_LABELS.put("us-phoenix-1", "美国西部（凤凰城）");
        ZH_LABELS.put("us-sanjose-1", "美国西部（圣何塞）");
        ZH_LABELS.put("us-chicago-1", "美国中西部（芝加哥）");
        ZH_LABELS.put("ca-toronto-1", "加拿大东南部（多伦多）");
        ZH_LABELS.put("ca-montreal-1", "加拿大东南部（蒙特利尔）");
        ZH_LABELS.put("eu-frankfurt-1", "德国中部（法兰克福）");
        ZH_LABELS.put("eu-zurich-1", "瑞士北部（苏黎世）");
        ZH_LABELS.put("eu-amsterdam-1", "荷兰西北部（阿姆斯特丹）");
        ZH_LABELS.put("eu-marseille-1", "法国南部（马赛）");
        ZH_LABELS.put("eu-stockholm-1", "瑞典北部（斯德哥尔摩）");
        ZH_LABELS.put("eu-milan-1", "意大利西北部（米兰）");
        ZH_LABELS.put("eu-turin-1", "意大利北部（都灵）");
        ZH_LABELS.put("eu-paris-1", "法国中部（巴黎）");
        ZH_LABELS.put("eu-madrid-1", "西班牙中部（马德里）");
        ZH_LABELS.put("eu-madrid-3", "西班牙中部（马德里3）");
        ZH_LABELS.put("uk-london-1", "英国南部（伦敦）");
        ZH_LABELS.put("uk-cardiff-1", "英国西部（加的夫）");
        ZH_LABELS.put("ap-tokyo-1", "日本东部（东京）");
        ZH_LABELS.put("ap-osaka-1", "日本中部（大阪）");
        ZH_LABELS.put("ap-seoul-1", "韩国中部（首尔）");
        ZH_LABELS.put("ap-chuncheon-1", "韩国北部（春川）");
        ZH_LABELS.put("ap-mumbai-1", "印度西部（孟买）");
        ZH_LABELS.put("ap-hyderabad-1", "印度南部（海得拉巴）");
        ZH_LABELS.put("ap-singapore-1", "新加坡（新加坡）");
        ZH_LABELS.put("ap-singapore-2", "新加坡西部（新加坡）");
        ZH_LABELS.put("ap-batam-1", "印度尼西亚北部（巴淡）");
        ZH_LABELS.put("ap-kulai-2", "马来西亚西部（古来）");
        ZH_LABELS.put("ap-sydney-1", "澳大利亚东部（悉尼）");
        ZH_LABELS.put("ap-melbourne-1", "澳大利亚东南部（墨尔本）");
        ZH_LABELS.put("sa-bogota-1", "哥伦比亚中部（波哥大）");
        ZH_LABELS.put("sa-saopaulo-1", "巴西东部（圣保罗）");
        ZH_LABELS.put("sa-vinhedo-1", "巴西东南部（维涅杜）");
        ZH_LABELS.put("sa-santiago-1", "智利中部（圣地亚哥）");
        ZH_LABELS.put("sa-valparaiso-1", "智利西部（瓦尔帕莱索）");
        ZH_LABELS.put("me-jeddah-1", "沙特阿拉伯西部（吉达）");
        ZH_LABELS.put("me-dubai-1", "阿联酋东部（迪拜）");
        ZH_LABELS.put("me-abudhabi-1", "阿联酋中部（阿布扎比）");
        ZH_LABELS.put("me-riyadh-1", "沙特阿拉伯中部（利雅得）");
        ZH_LABELS.put("af-johannesburg-1", "南非中部（约翰内斯堡）");
        ZH_LABELS.put("af-casablanca-1", "摩洛哥西部（卡萨布兰卡）");
        ZH_LABELS.put("il-jerusalem-1", "以色列中部（耶路撒冷）");
        ZH_LABELS.put("mx-queretaro-1", "墨西哥中部（克雷塔罗）");
        ZH_LABELS.put("mx-monterrey-1", "墨西哥东北部（蒙特雷）");
        ZH_LABELS.put("us-saltlake-2", "美国中西部（盐湖城）");
        ZH_LABELS.put("us-langley-1", "美国政府（兰利）");
        ZH_LABELS.put("us-luke-1", "美国政府（卢克）");
        ZH_LABELS.put("us-gov-ashburn-1", "美国政府（阿什本）");
        ZH_LABELS.put("us-gov-chicago-1", "美国政府（芝加哥）");
        ZH_LABELS.put("us-gov-phoenix-1", "美国政府（凤凰城）");

        EXTRA_REGION_CODES.put("eu-turin-1", "nrq");
        ensureAdditionalRegionsRegistered();
    }

    private OciRegionCatalog() {
    }

    /**
     * @return 每项含 regionId、labelZh（无译名时等于 regionId）、label（下拉展示用）
     */
    public static List<Map<String, String>> listUiRows() {
        ensureAdditionalRegionsRegistered();
        TreeSet<String> ids = new TreeSet<>();
        for (Region r : Region.values()) {
            String id = r.getRegionId();
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        ids.addAll(ZH_LABELS.keySet());
        return listUiRowsForIds(ids);
    }

    /** 仅包含 tenancy 已订阅等区域（由 Identity listRegionSubscriptions 得到 regionName 列表）。 */
    public static List<Map<String, String>> listUiRowsForIds(Collection<String> regionIds) {
        ensureAdditionalRegionsRegistered();
        if (regionIds == null || regionIds.isEmpty()) {
            return List.of();
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String raw : regionIds) {
            if (raw == null) {
                continue;
            }
            String id = raw.trim();
            if (!id.isEmpty()) {
                sorted.add(id);
            }
        }
        List<Map<String, String>> out = new ArrayList<>(sorted.size());
        for (String id : sorted) {
            out.add(buildRow(id));
        }
        return out;
    }

    private static Map<String, String> buildRow(String id) {
        String zh = ZH_LABELS.getOrDefault(id, id);
        String label = ZH_LABELS.containsKey(id) ? zh + "（" + id + "）" : id;
        Map<String, String> row = new LinkedHashMap<>();
        row.put("regionId", id);
        row.put("labelZh", zh);
        row.put("label", label);
        return row;
    }

    public static void ensureAdditionalRegionsRegistered() {
        for (Map.Entry<String, String> entry : EXTRA_REGION_CODES.entrySet()) {
            String regionId = entry.getKey();
            try {
                Region.fromRegionCodeOrId(regionId);
            } catch (IllegalArgumentException ignored) {
                Region.register(regionId, Realm.OC1, entry.getValue());
            }
        }
    }

    public static Region resolveRegion(String regionId) {
        if (regionId == null || regionId.trim().isEmpty()) {
            throw new OciException("Region 不能为空");
        }
        ensureAdditionalRegionsRegistered();
        String trimmed = regionId.trim().toLowerCase();
        try {
            return Region.fromRegionCodeOrId(trimmed);
        } catch (IllegalArgumentException ignored) {
            for (Region r : Region.values()) {
                if (trimmed.equalsIgnoreCase(r.getRegionId())) {
                    return r;
                }
            }
            if (PUBLIC_OC1_REGION_ID.matcher(trimmed).matches()) {
                return Region.register(trimmed, Realm.OC1);
            }
            throw new OciException("未知 Region: " + regionId + "（请检查拼写，例如 eu-turin-1）");
        }
    }
}
