package com.ociworker.model.params;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginParams {
    @NotBlank(message = "账号不能为空")
    @Size(max = 64, message = "账号长度不能超过64个字符")
    private String account;
    @NotBlank(message = "密码不能为空")
    @Size(max = 256, message = "密码长度不能超过256个字符")
    private String password;
    private String mfaCode;
}
