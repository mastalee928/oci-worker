package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oci_openai_lb_request_log")
public class OciOpenaiLbRequestLog {
    @TableId
    private String id;
    private String requestId;
    private String lbKeyId;
    private String memberId;
    private String portBindingId;
    private Integer port;
    private String model;
    private Integer stream;
    private Long estimatedPromptTokens;
    private Integer statusCode;
    private String status;
    private String errorType;
    private String errorMessage;
    private Long latencyMs;
    private Long firstChunkMs;
    private Integer chunkCount;
    private Long tokenCount;
    private Integer clientAborted;
    private Integer retryCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
