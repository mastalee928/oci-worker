package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oci_openai_lb_key")
public class OciOpenaiLbKey {
    @TableId
    private String id;
    private String keyHash;
    private String keyPrefix;
    private String keyEncrypted;
    private String name;
    private Integer disabled;
    private LocalDateTime createTime;
    private LocalDateTime lastUsed;
}
