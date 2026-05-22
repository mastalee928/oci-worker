package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oci_create_task")
public class OciCreateTask {
    @TableId
    private String id;
    private String userId;
    private String ociRegion;
    private Double ocpus;
    private Double memory;
    private Integer disk;
    /** 引导卷 VPUs/GB，默认 10（须显式列名，避免 MP 将 PerGB 转成 vpus_per_g_b） */
    @TableField("vpus_per_gb")
    private Integer vpusPerGB;
    private String architecture;
    private Integer intervalSeconds;
    private Integer createNumbers;
    private String rootPassword;
    private String operationSystem;
    private String customScript;
    private Boolean assignPublicIp;
    private Boolean assignIpv6;
    private String status;
    private Integer attemptCount;
    private Integer successCount;
    private String createdInstances;
    private LocalDateTime createTime;
}
