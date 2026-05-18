package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 面板为 OpenAI 兼容网关注册的 sk 密钥：哈希用于校验，加密字段供面板「查看」完整密钥。
 */
@Data
@TableName("oci_openai_key")
public class OciOpenaiKey {
    @TableId
    private String id;
    private String ociUserId;
    private String keyHash;
    /** 展示用：sk- 后若干位（兼容旧数据） */
    private String keyPrefix;
    /** AES 加密后的完整 sk（Base64） */
    private String keyEncrypted;
    private String name;
    private Integer disabled;
    private LocalDateTime createTime;
    private LocalDateTime lastUsed;
}
