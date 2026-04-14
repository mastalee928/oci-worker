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
        for (ArchitectureEnum e : values()) {
            if (e.getArchitecture().equalsIgnoreCase(architecture)) {
                return e.getShape();
            }
        }
        return ARM.getShape();
    }
}
