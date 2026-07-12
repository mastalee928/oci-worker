package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("oci_tenant_traffic_instance_usage")
public class TenantTrafficInstanceUsage {
    @TableId
    private String id;
    private String tenantConfigId;
    private String monthKey;
    private String instanceId;
    private String instanceName;
    private String region;
    private String lifecycleState;
    private Long bytesToNetwork;
    private Date lastSeenTime;
}
