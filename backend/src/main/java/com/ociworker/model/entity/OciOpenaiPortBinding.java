package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oci_openai_port_binding")
public class OciOpenaiPortBinding {
    @TableId
    private String id;
    private String name;
    private Integer port;
    private String ociUserId;
    private String openaiKeyId;
    private Integer defaultMaxTokens;
    private String allowedModelsJson;
    private Integer enabled;
    private String status;
    private String statusMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime lastUsed;
}
