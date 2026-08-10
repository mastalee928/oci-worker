package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 实例守护：检测实例 STOPPED 后自动执行 START。 */
@Data
@TableName("oci_instance_guard")
public class OciInstanceGuard {
    @TableId
    private String id;
    private String tenantConfigId;
    private String tenantName;
    private String region;
    private String instanceId;
    private String instanceName;
    private Boolean enabled;
    private Integer intervalMinutes;
    private Date nextCheckTime;
    private Boolean notifyMuted;
    private String lastState;
    private Date lastCheckTime;
    private Date lastStartTime;
    private Integer startCount;
    private Integer consecutiveFailures;
    private String lastMessage;
    private Date createTime;
    private Date updateTime;
}
