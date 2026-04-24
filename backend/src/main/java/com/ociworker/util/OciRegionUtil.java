package com.ociworker.util;

import cn.hutool.core.util.StrUtil;
import com.oracle.bmc.Region;
import com.ociworker.exception.OciException;

/**
 * 与 OciClientService 一致：解析 OCI 区域（短码/全称均可），用于构造 Generative AI 等 hostname。
 */
public final class OciRegionUtil {

    private OciRegionUtil() {
    }

    public static String publicRegionId(String regionId) {
        if (StrUtil.isBlank(regionId)) {
            throw new OciException("Region 不能为空");
        }
        String trimmed = regionId.trim();
        try {
            return Region.fromRegionCodeOrId(trimmed).getRegionId();
        } catch (IllegalArgumentException ignored) {
            for (Region r : Region.values()) {
                if (trimmed.equalsIgnoreCase(r.getRegionId())) {
                    return r.getRegionId();
                }
            }
        }
        throw new OciException("未知 Region: " + regionId);
    }

    public static Region toRegion(String regionId) {
        return Region.fromRegionCodeOrId(publicRegionId(regionId));
    }
}
