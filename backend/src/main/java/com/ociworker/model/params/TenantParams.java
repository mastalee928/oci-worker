package com.ociworker.model.params;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TenantParams {
    private String id;
    @NotBlank(message = "名称不能为空")
    private String username;
    @NotBlank(message = "Tenant OCID 不能为空")
    private String ociTenantId;
    @NotBlank(message = "User OCID 不能为空")
    private String ociUserId;
    @NotBlank(message = "Fingerprint 不能为空")
    private String ociFingerprint;
    @NotBlank(message = "Region 不能为空")
    private String ociRegion;
    private String ociKeyPath;
    private String groupLevel1;
    private String groupLevel2;
}
