package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oci_announcement_push_batch")
public class OciAnnouncementPushBatch {
    @TableId
    private String id;
    private String batchId;
    private LocalDateTime pushedAt;
    private Integer announcementCount;
    private Integer tenantCount;
    private String messagePreview;
    private String status;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;
    private LocalDateTime createTime;
}
