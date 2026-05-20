package com.ociworker.util;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Flex Shape 默认与最大值 — 与 OCI 控制台及前端 {@code ociBmShapeSpecs.ts} 一致。
 */
@Slf4j
public final class ShapeFlexLimitsUtil {

    public static final String ARM_TASK_SHAPE = "VM.Standard.A1.Flex";
    public static final String AMD_TASK_SHAPE = "VM.Standard.E2.1.Micro";

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
        if ("AMD".equalsIgnoreCase(arch)) {
            return new FlexLimits(1, 1, 1, 1);
        }
        return forShape(arch);
    }

    /**
     * 将任务 OCPU/内存钳制到该架构允许范围；无固定表条目时原样返回。
     *
     * @return [ocpus, memoryGb]
     */
    public static double[] normalizeOcpusAndMemory(String architecture, Double ocpus, Double memory) {
        FlexLimits lim = forTaskArchitecture(architecture);
        double o = ocpus != null ? ocpus : (lim != null ? lim.defaultOcpus() : 1d);
        double m = memory != null ? memory : (lim != null ? lim.defaultMemoryGb() : 6d);
        if (lim == null) {
            return new double[]{o, m};
        }
        double co = Math.min(Math.max(o, 1d), lim.maxOcpus());
        double cm = Math.min(Math.max(m, 1d), lim.maxMemoryGb());
        return new double[]{co, cm};
    }

    /**
     * 开机任务创建/更新/执行前：超限则静默改为上限并写日志。
     */
    public static double[] normalizeAndLogIfAdjusted(String architecture, Double ocpus, Double memory, String context) {
        double beforeO = ocpus != null ? ocpus : -1;
        double beforeM = memory != null ? memory : -1;
        double[] out = normalizeOcpusAndMemory(architecture, ocpus, memory);
        if ((ocpus != null && ocpus != out[0]) || (memory != null && memory != out[1])) {
            log.warn("{} 资源配置已按 Shape 上限调整: arch={} ocpus {} -> {}, memory {} -> {}",
                    context, architecture, beforeO, out[0], beforeM, out[1]);
        }
        return out;
    }
}
