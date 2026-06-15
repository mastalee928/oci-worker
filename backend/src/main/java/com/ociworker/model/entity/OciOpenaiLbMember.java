package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oci_openai_lb_member")
public class OciOpenaiLbMember {
    @TableId
    private String id;
    private String portBindingId;
    private Integer weight;
    private Integer enabled;
    private Integer failCount;
    private LocalDateTime cooldownUntil;
    private String lastError;
    private Integer requestLimit5h;
    private Integer requestLimit7d;
    private LocalDateTime lastUsed;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
