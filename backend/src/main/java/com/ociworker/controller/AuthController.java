package com.ociworker.controller;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.model.entity.OciKv;
import com.ociworker.model.params.LoginParams;
import com.ociworker.model.vo.ResponseData;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Value("${web.account}")
    private String defaultAccount;
    @Value("${web.password}")
    private String defaultPassword;

    @Resource
    private OciKvMapper kvMapper;

    private String getStoredPassword() {
        OciKv kv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getCode, "web_password")
                .eq(OciKv::getType, "sys_config"));
        return kv != null ? kv.getValue() : null;
    }

    private String getEffectivePassword() {
        String stored = getStoredPassword();
        return stored != null ? stored : defaultPassword;
    }

    @PostMapping("/login")
    public ResponseData<?> login(@RequestBody @Valid LoginParams params) {
        String effectivePwd = getEffectivePassword();
        if (!defaultAccount.equals(params.getAccount()) || !effectivePwd.equals(params.getPassword())) {
            return ResponseData.error("账号或密码错误");
        }
        String token = CommonUtils.generateToken(defaultAccount, effectivePwd);
        return ResponseData.ok(Map.of("token", token, "expireHours", 24));
    }

    @PostMapping("/changePassword")
    public ResponseData<?> changePassword(@RequestBody Map<String, String> params) {
        String oldPwd = params.get("oldPassword");
        String newPwd = params.get("newPassword");
        if (oldPwd == null || newPwd == null || newPwd.length() < 6) {
            return ResponseData.error("新密码不能少于6位");
        }

        String effectivePwd = getEffectivePassword();
        if (!effectivePwd.equals(oldPwd)) {
            return ResponseData.error("原密码错误");
        }

        OciKv existing = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getCode, "web_password")
                .eq(OciKv::getType, "sys_config"));
        if (existing != null) {
            existing.setValue(newPwd);
            kvMapper.updateById(existing);
        } else {
            OciKv kv = new OciKv();
            kv.setId(CommonUtils.generateId());
            kv.setCode("web_password");
            kv.setValue(newPwd);
            kv.setType("sys_config");
            kvMapper.insert(kv);
        }

        String newToken = CommonUtils.generateToken(defaultAccount, newPwd);
        return ResponseData.ok(Map.of("token", newToken, "message", "密码修改成功"));
    }
}
