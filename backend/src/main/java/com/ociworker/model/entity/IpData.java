package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ip_data")
public class IpData {
    @TableId
    private String id;
    private String ip;
    private String country;
    private String area;
    private String city;
    private String org;
    private String asn;
    private String type;
    private Double lat;
    private Double lng;
    private LocalDateTime createTime;
}
