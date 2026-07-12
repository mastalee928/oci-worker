package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("oci_tenant_traffic_action_log")
public class TenantTrafficActionLog {
    @TableId
    private String id;
    private String tenantConfigId;
    private String monthKey;
    private String action;
    private Long estimatedBytes;
    private String affectedInstanceIds;
    private Integer successCount;
    private Integer failureCount;
    private String errorSummary;
    private Date createTime;
}
