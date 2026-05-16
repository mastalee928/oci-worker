package com.ociworker.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysUserDTO {

    private String taskId;
    private String username;
    private String architecture;
    private Double ocpus;
    private Double memory;
    private Integer disk;
    private Integer createNumbers;
    private String rootPassword;
    private String operationSystem;
    private String customScript;
    private Boolean assignPublicIp;
    private Boolean assignIpv6;
    /**
     * 本次创建在任务目标中的第几台（从 1 起）。仅开机任务在调用 {@code createInstanceData} 前设置，用于 OCI displayName。
     */
    private Integer instanceDisplayOrdinal;
    private OciCfg ociCfg;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OciCfg {
        private String tenantId;
        private String userId;
        private String fingerprint;
        private String region;
        private String privateKeyPath;
        private String compartmentId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CloudInstance {
        private String instanceId;
        private String name;
        private String region;
        private String shape;
        private float ocpus;
        private float memoryInGBs;
        private String state;
        private String publicIp;
        private String privateIp;
        private String imageId;
        private String availabilityDomain;
        private String timeCreated;
    }
}
