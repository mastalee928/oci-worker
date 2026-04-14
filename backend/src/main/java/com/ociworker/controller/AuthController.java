package com.ociworker.controller;

import com.ociworker.model.params.LoginParams;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.util.CommonUtils;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Value("${web.account}")
    private String account;
    @Value("${web.password}")
    private String password;

    @PostMapping("/login")
    public ResponseData<?> login(@RequestBody @Valid LoginParams params) {
        if (!account.equals(params.getAccount()) || !password.equals(params.getPassword())) {
            return ResponseData.error("账号或密码错误");
        }
        String token = CommonUtils.generateToken(account, password);
        return ResponseData.ok(Map.of("token", token));
    }
}
