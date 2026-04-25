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
    /**
     * OCI Generative Multi-Agent / responses 等调用可能要求的 HTTP 头，二选一或按文档同传。
     * 在面板「Oracle 生成式 AI」页为租户配置后，/v1 走网关时会自动带上，便于 New API 等中间层不转发自定义头时仍可用。
     */
    @TableField(value = "generative_openai_project", updateStrategy = FieldStrategy.ALWAYS)
    private String generativeOpenaiProject;
    @TableField(value = "generative_conversation_store_id", updateStrategy = FieldStrategy.ALWAYS)
    private String generativeConversationStoreId;
    private LocalDateTime createTime;
}
