package com.ociworker.model.entity;

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
    private String architecture;
    private Integer intervalSeconds;
    private Integer createNumbers;
    private String rootPassword;
    private String operationSystem;
    private String customScript;
    private String status;
    private Integer attemptCount;
    private LocalDateTime createTime;
}
