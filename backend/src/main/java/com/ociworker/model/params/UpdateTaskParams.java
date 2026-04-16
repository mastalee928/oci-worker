package com.ociworker.model.params;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateTaskParams {
    @NotBlank(message = "任务ID不能为空")
    private String taskId;
    private String architecture;
    private Double ocpus;
    private Double memory;
    private Integer disk;
    private Integer createNumbers;
    private Integer interval;
    private String rootPassword;
    private String operationSystem;
    private String customScript;
    private Boolean assignPublicIp;
    private Boolean assignIpv6;
}
