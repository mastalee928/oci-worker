package com.ociworker.model.dto;

import lombok.Data;

@Data
public class InstanceDetailDTO {
    private String taskId;
    private String username;
    private String region;
    private String architecture;
    private Integer createNumbers;
    private String instanceId;
    private String instanceName;
    private String shape;
    private Double ocpus;
    private Double memory;
    private Integer disk;
    private String publicIp;
    private String privateIp;
    private String image;
    private String rootPassword;
    private boolean success;
    private boolean die;
    private boolean noShape;
    private boolean noPubVcn;
    private boolean outOfCapacity;
}
