package com.ociworker.model.params;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class IdParams {
    @NotBlank(message = "ID不能为空")
    private String id;
}
