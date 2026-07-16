package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("oci_scheduled_ip_task")
public class ScheduledIpTask {
    @TableId
    private String id;
    private String name;
    private String tenantConfigId;
    private String tenantName;
    private String region;
    private String instanceId;
    private String instanceName;
    private String shape;
    private String compartmentId;
    private String currentPublicIp;
    private Boolean enabled;
    private Integer intervalMinutes;
    private Date nextRunTime;
    private Date lastRunTime;
    private String lastStatus;
    private String lastMessage;
    private Boolean dnsEnabled;
    private String dnsProvider;
    private String fqdn;
    private String dnsZoneId;
    private String dnsDomainName;
    private String dnsRecordId;
    private String dnsRecordName;
    private Boolean notifySuccess;
    private Boolean notifyIpFailure;
    private Boolean notifyDnsFailure;
    private Boolean notifyAutoPaused;
    private Integer consecutiveFailures;
    private String lastNotifyKey;
    private Date lastNotifyTime;
    private String lockOwner;
    private Date lockUntil;
    private Date createTime;
    private Date updateTime;
}
