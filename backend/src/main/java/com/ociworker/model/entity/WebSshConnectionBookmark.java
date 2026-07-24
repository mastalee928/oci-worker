package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oci_webssh_connection_bookmark")
public class WebSshConnectionBookmark {
    @TableId
    private String id;
    private String dedupeKey;
    private String hostname;
    private Integer port;
    private String username;
    private String authType;
    private Long sortOrder;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
