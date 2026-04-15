package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oci_user")
public class OciUser {
    @TableId
    private String id;
    private String username;
    private String tenantName;
    private LocalDateTime tenantCreateTime;
    private String ociTenantId;
    private String ociUserId;
    private String ociFingerprint;
    private String ociRegion;
    private String ociKeyPath;
    private String planType;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String groupLevel1;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String groupLevel2;
    private LocalDateTime createTime;
}
