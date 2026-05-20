package com.ociworker.util;

import cn.hutool.core.util.StrUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OCI 控制台 Shape series 固定映射（与用户提供截图一致）。
 * 未知 Shape：BM.* → 裸金属机；短码 ARM/Intel；其余返回原字符串便于补全表。
 */
public final class ShapeSeriesUtil {

    public static final String SERIES_AMD = "AMD";
    public static final String SERIES_INTEL = "Intel";
    public static final String SERIES_ARM = "ARM（Ampere）";
    public static final String SERIES_SPECIALTY = "专业和上一代";
    public static final String SERIES_BARE_METAL = "裸金属机";

    private static final Map<String, String> FIXED_VM_SHAPE_SERIES = buildFixedVmShapeSeries();

    private ShapeSeriesUtil() {}

    /**
     * @param shapeOrArchitecture 任务 architecture 或实例 Shape 名
     */
    public static String resolveSeries(String shapeOrArchitecture) {
        if (StrUtil.isBlank(shapeOrArchitecture)) {
            return "-";
        }
        String raw = shapeOrArchitecture.trim();
        String key = raw.toUpperCase();

        if (key.startsWith("BM.")) {
            return SERIES_BARE_METAL;
        }

        String fixed = FIXED_VM_SHAPE_SERIES.get(key);
        if (fixed != null) {
            return fixed;
        }

        if ("ARM".equalsIgnoreCase(raw) || "Ampere".equalsIgnoreCase(raw)) {
            return SERIES_ARM;
        }
        if ("Intel".equalsIgnoreCase(raw) || "INTEL".equalsIgnoreCase(raw)) {
            return SERIES_INTEL;
        }
        if ("AMD".equalsIgnoreCase(raw)) {
            return SERIES_SPECIALTY;
        }

        return raw;
    }

    /** 是否为完整 Shape 名（相对 series 短码） */
    public static boolean isFullShapeName(String shapeOrArchitecture) {
        if (StrUtil.isBlank(shapeOrArchitecture)) {
            return false;
        }
        String u = shapeOrArchitecture.trim().toUpperCase();
        return u.startsWith("VM.") || u.startsWith("BM.");
    }

    private static Map<String, String> buildFixedVmShapeSeries() {
        Map<String, String> m = new HashMap<>();
        register(m, SERIES_ARM, armShapes());
        register(m, SERIES_AMD, amdShapes());
        register(m, SERIES_INTEL, intelShapes());
        register(m, SERIES_SPECIALTY, specialtyShapes());
        return Map.copyOf(m);
    }

    private static void register(Map<String, String> map, String series, List<String> shapes) {
        for (String shape : shapes) {
            map.put(shape.toUpperCase(), series);
        }
    }

    /** 控制台 Ampere / ARM（Ampere） */
    private static List<String> armShapes() {
        return List.of(
                "VM.Standard.A1.Flex",
                "VM.Standard.A2.Flex",
                "VM.Standard.A4.Flex"
        );
    }

    /** 控制台当前代 AMD */
    private static List<String> amdShapes() {
        return List.of(
                "VM.Standard.E4.Flex",
                "VM.Standard.E5.Flex",
                "VM.Standard.E6.Flex",
                "VM.Standard.E6.Ax.Flex"
        );
    }

    /** 控制台当前代 Intel */
    private static List<String> intelShapes() {
        return List.of(
                "VM.Standard3.Flex",
                "VM.Optimized3.Flex",
                "VM.Standard4.Ax.Flex"
        );
    }

    /**
     * 控制台「专业和上一代 / Specialty and previous generation」VM Shape（截图固定列表）.
     */
    private static List<String> specialtyShapes() {
        return List.of(
                "VM.Standard.E2.1.Micro",
                "VM.Standard.E3.Flex",
                "VM.DenseIO.E5.Flex",
                "VM.DenseIO.E4.Flex",
                "VM.DenseIO2.8",
                "VM.DenseIO2.16",
                "VM.DenseIO2.24",
                "VM.GPU.A10.1",
                "VM.GPU.A10.2",
                "VM.GPU2.1",
                "VM.GPU3.1",
                "VM.GPU3.2",
                "VM.GPU3.4",
                "VM.Standard.B1.1",
                "VM.Standard.B1.2",
                "VM.Standard.B1.4",
                "VM.Standard.B1.8",
                "VM.Standard.B1.16",
                "VM.Standard.E2.1",
                "VM.Standard.E2.2",
                "VM.Standard.E2.4",
                "VM.Standard.E2.8",
                "VM.Standard1.1",
                "VM.Standard1.2",
                "VM.Standard1.4",
                "VM.Standard1.8",
                "VM.Standard1.16",
                "VM.Standard2.1",
                "VM.Standard2.2",
                "VM.Standard2.4",
                "VM.Standard2.8",
                "VM.Standard2.16",
                "VM.Standard2.24"
        );
    }
}
