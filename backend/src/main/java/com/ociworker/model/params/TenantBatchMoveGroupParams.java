package com.ociworker.model.params;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class TenantBatchMoveGroupParams {
    @NotEmpty(message = "请选择要移动的租户")
    private List<String> idList;
    @NotBlank(message = "请选择一级分组")
    private String groupLevel1;
    private String groupLevel2;
}
