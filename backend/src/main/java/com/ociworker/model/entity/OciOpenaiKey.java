package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 面板为 OpenAI 兼容网关注册的 sk 密钥，仅存储哈希；绑定 oci_user 租户以使用其 OCI 配置。
 */
@Data
@TableName("oci_openai_key")
public class OciOpenaiKey {
    @TableId
    private String id;
    private String ociUserId;
    private String keyHash;
    /** 展示用：sk- 后若干位 */
    private String keyPrefix;
    private String name;
    private Integer disabled;
    private LocalDateTime createTime;
    private LocalDateTime lastUsed;
}
