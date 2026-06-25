package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oci_announcement_record")
public class OciAnnouncementRecord {
    @TableId
    private String id;
    private String tenantId;
    private String tenantName;
    private String announcementId;
    private String aggregateKey;
    private String chainId;
    private String summary;
    private String announcementType;
    private String servicesJson;
    private String affectedRegionsJson;
    private LocalDateTime timeCreated;
    private LocalDateTime timeUpdated;
    private String timeOneTitle;
    private String timeOneType;
    private LocalDateTime timeOneValue;
    private String timeTwoTitle;
    private String timeTwoType;
    private LocalDateTime timeTwoValue;
    private Boolean pushed;
    private Boolean ignored;
    private Boolean readFlag;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String pushedBatchId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime pushedAt;
}
