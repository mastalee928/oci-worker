package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("oci_tenant_traffic_protection")
public class TenantTrafficProtection {
    @TableId
    private String tenantConfigId;
    private Boolean enabled;
    private Long monthlyLimitBytes;
    private Integer warningPercent;
    private String exceedAction;
    private String monthKey;
    private Long monthlyBytes;
    private Date lastCollectTime;
    private Date nextCollectTime;
    private Integer lastWarningLevel;
    private Boolean stopExecuted;
    private Date stopExecutedTime;
    private String collectionLockOwner;
    private Date collectionLockUntil;
    private String lastError;
    private Date createTime;
    private Date updateTime;
}
