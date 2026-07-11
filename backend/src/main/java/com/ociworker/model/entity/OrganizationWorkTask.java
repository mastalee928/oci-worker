package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("oci_organization_work_task")
public class OrganizationWorkTask {
    private String id;
    private String tenantConfigId;
    private String operationType;
    private String targetName;
    private String targetId;
    private String workRequestId;
    private String requestId;
    private String status;
    private Float percentComplete;
    private String errorMessage;
    private Date createTime;
    private Date updateTime;
}
