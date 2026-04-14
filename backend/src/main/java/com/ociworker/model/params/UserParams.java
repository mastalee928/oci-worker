package com.ociworker.model.params;

import lombok.Data;

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
}
