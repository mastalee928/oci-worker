package com.ociworker.model.params;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateTaskParams {
    @NotBlank(message = "请选择租户")
    private String userId;
    private String architecture = "ARM";
    private Double ocpus = 1.0;
    private Double memory = 6.0;
    private Integer disk = 50;
    private Integer createNumbers = 1;
    private Integer interval = 60;
    private String rootPassword;
    private String operationSystem = "Ubuntu";
}
