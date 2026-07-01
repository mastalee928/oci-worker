package com.ociworker.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ArchitectureEnum {
    ARM("ARM", "VM.Standard.A1.Flex"),
    AMD("AMD", "VM.Standard.E2.1.Micro"),
    ;

    private final String architecture;
    private final String shape;

    public static String getShape(String architecture) {
        String raw = architecture == null ? "" : architecture.trim();
        if (architecture != null) {
            String upper = raw.toUpperCase();
            if (upper.startsWith("VM.") || upper.startsWith("BM.")) {
                return raw;
            }
        }
        for (ArchitectureEnum e : values()) {
            if (e.getArchitecture().equalsIgnoreCase(raw)) {
                return e.getShape();
            }
        }
        return ARM.getShape();
    }
}
