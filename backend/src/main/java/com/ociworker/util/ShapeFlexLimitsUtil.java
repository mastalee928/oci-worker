package com.ociworker.util;

import cn.hutool.core.util.StrUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Flex Shape 默认与最大值 — 与 OCI 控制台及前端 {@code ociBmShapeSpecs.ts} 一致。
 */
public final class ShapeFlexLimitsUtil {

    public static final String ARM_TASK_SHAPE = "VM.Standard.A1.Flex";

    public record FlexLimits(float defaultOcpus, float defaultMemoryGb, float maxOcpus, float maxMemoryGb) {}

    private static final Map<String, FlexLimits> SPECS = buildSpecs();

    private ShapeFlexLimitsUtil() {}

    private static Map<String, FlexLimits> buildSpecs() {
        Map<String, FlexLimits> m = new HashMap<>();
        put(m, "VM.Standard.E6.Flex", 1, 11, 126, 1454);
        put(m, "VM.Standard.E6.Ax.Flex", 1, 7, 94, 712);
        put(m, "VM.Standard.E5.Flex", 1, 12, 126, 2098);
        put(m, "VM.Standard.E4.Flex", 1, 16, 114, 1760);
        put(m, "VM.Standard3.Flex", 1, 16, 56, 896);
        put(m, "VM.Optimized3.Flex", 1, 14, 18, 256);
        put(m, "VM.Standard4.Ax.Flex", 1, 9, 39, 360);
        put(m, "VM.Standard.A1.Flex", 1, 6, 80, 512);
        put(m, "VM.Standard.A2.Flex", 1, 6, 78, 946);
        put(m, "VM.Standard.A4.Flex", 1, 7, 45, 700);
        put(m, "VM.Standard.E3.Flex", 1, 16, 114, 1776);
        return Map.copyOf(m);
    }

    private static void put(Map<String, FlexLimits> m, String shape,
                            float defO, float defM, float maxO, float maxM) {
        m.put(shape.toUpperCase(), new FlexLimits(defO, defM, maxO, maxM));
    }

    public static FlexLimits forShape(String shapeName) {
        if (StrUtil.isBlank(shapeName)) {
            return null;
        }
        return SPECS.get(shapeName.trim().toUpperCase());
    }

    public static FlexLimits forTaskArchitecture(String architecture) {
        if (StrUtil.isBlank(architecture)) {
            return null;
        }
        String arch = architecture.trim();
        if ("ARM".equalsIgnoreCase(arch)) {
            return SPECS.get(ARM_TASK_SHAPE.toUpperCase());
        }
        return forShape(arch);
    }
}
