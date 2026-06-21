package com.ociworker.util;

import cn.hutool.core.util.StrUtil;
import com.ociworker.exception.OciException;
import com.oracle.bmc.Region;

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
        return OciRegionCatalog.resolveRegion(regionId).getRegionId();
    }

    public static Region toRegion(String regionId) {
        return OciRegionCatalog.resolveRegion(regionId);
    }
}
