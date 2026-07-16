package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("oci_scheduled_ip_run_log")
public class ScheduledIpRunLog {
    @TableId
    private String id;
    private String taskId;
    private String runId;
    private String triggerType;
    private String status;
    private String oldIp;
    private String newIp;
    private String dnsStatus;
    private String message;
    private String dnsMessage;
    private Date startedAt;
    private Date finishedAt;
}
