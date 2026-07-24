package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oci_webssh_script_bookmark")
public class WebSshScriptBookmark {
    @TableId
    private String id;
    private String name;
    private String commandEncrypted;
    private Long sortOrder;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
