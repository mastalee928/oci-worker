package com.ociworker.model.dto;

import lombok.Data;

@Data
public class ScheduledIpTaskRequest {
    private String id;
    private String tenantConfigId;
    private String tenantName;
    private String region;
    private String instanceId;
    private String instanceName;
    private String shape;
    private String compartmentId;
    private String currentPublicIp;
    private Integer intervalMinutes;
    private Boolean firstRunNow;
    private Boolean dnsEnabled;
    private String dnsProvider;
    private String fqdn;
    private Boolean notifySuccess;
    private Boolean notifyIpFailure;
    private Boolean notifyDnsFailure;
    private Boolean notifyAutoPaused;
}
