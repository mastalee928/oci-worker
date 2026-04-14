package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("cf_cfg")
public class CfCfg {
    @TableId
    private String id;
    private String domain;
    private String zoneId;
    private String apiToken;
    private LocalDateTime createTime;
}
