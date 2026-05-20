package com.ociworker.model.params;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class UserParams {
    private String tenantId;
    private String userId;
    private String userName;
    private String email;
    private String firstName;
    private String lastName;
    private String password;
    private Boolean addToAdminGroup;
    private String domainId;

    private Integer bypassCodeCount;
    private Integer bypassCodeExpiryDays;

    /** IAM 用户能力开关，键见 {@link com.ociworker.service.UserManagementService#CAPABILITY_KEYS} */
    private Map<String, Boolean> capabilities;

    /** 同步用户组成员关系时的目标组 OCID 列表 */
    private List<String> groupIds;
}
