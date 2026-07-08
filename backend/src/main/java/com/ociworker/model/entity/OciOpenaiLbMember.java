package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
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
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer requestLimit5h;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer requestLimit7d;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer maxConcurrency;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer rpmLimit;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long tpmLimit;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer contextLimit;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer streamFirstChunkTimeoutSeconds;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer streamIdleTimeoutSeconds;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer streamMaxSeconds;
    private String healthStatus;
    private String healthMessage;
    private LocalDateTime healthCheckedAt;
    private Integer lastLatencyMs;
    private Integer lastStatus;
    private String lastErrorType;
    private Double ewmaSuccessRate;
    private Long ewmaLatencyMs;
    private LocalDateTime recoveryUntil;
    private LocalDateTime lastUsed;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
