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
