package com.ociworker.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class InstanceDetailDTO {
    private String taskId;
    private String username;
    private String region;
    private String architecture;
    private Integer createNumbers;
    private String instanceId;
    private String instanceName;
    private String shape;
    private Double ocpus;
    private Double memory;
    private Integer disk;
    private String publicIp;
    private String privateIp;
    /** 勾选 IPv6 且分配成功时的地址 */
    private String ipv6Address;
    private String image;
    private String rootPassword;
    private boolean success;
    private boolean die;
    private boolean noShape;
    private boolean noPubVcn;
    private boolean outOfCapacity;
    /** 引导卷（启动盘）块存储配额已达 OCI 上限 */
    private boolean bootVolumeQuotaExceeded;
    /** 供任务播报的简短失败说明 */
    private String failureHint;
    /** 本次尝试解析出的目标 Shape（完整名） */
    private String resolvedTargetShape;
    /** 本次尝试中判定为「当前可用域无此 Shape」、本任务后续将跳过的 AD */
    private List<String> adsExcludedNoShape = new ArrayList<>();
    /** 区域内可用域均已在本任务中标记为无此 Shape */
    private boolean allAdsExcludedNoShape;
}
