package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oci_openai_lb_member_model_state")
public class OciOpenaiLbMemberModelState {
    @TableId
    private String id;
    private String memberId;
    private String model;
    private String status;
    private Integer failCount;
    private Integer successCount;
    private LocalDateTime unavailableUntil;
    private Integer lastStatus;
    private String lastError;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
