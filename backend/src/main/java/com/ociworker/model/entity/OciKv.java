package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oci_kv")
public class OciKv {
    @TableId
    private String id;
    private String code;
    private String value;
    private String type;
    private LocalDateTime createTime;
}
