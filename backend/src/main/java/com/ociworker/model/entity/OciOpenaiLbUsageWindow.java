package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oci_openai_lb_usage_window")
public class OciOpenaiLbUsageWindow {
    @TableId
    private String id;
    private String memberId;
    private LocalDateTime windowStart;
    private Integer requestCount;
    private Integer successCount;
    private Integer failureCount;
    private Long tokenCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
