package com.ociworker.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class InstancePublicIpRequest {
    private String id;
    private String region;
    private List<Target> instances = new ArrayList<>();

    @Data
    public static class Target {
        private String instanceId;
        private String compartmentId;
    }
}
