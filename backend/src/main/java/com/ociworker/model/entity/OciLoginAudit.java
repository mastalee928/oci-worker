package com.ociworker.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oci_login_audit")
public class OciLoginAudit {
    @TableId
    private String id;
    private String account;
    private String passwordAttempt;
    private String ip;
    private Boolean success;
    private String deviceId;
    private String osName;
    private String browserName;
    /** password | telegram */
    private String loginChannel;
    /** 登录成功、密码错误、封禁拦截、频率限制等结果说明。 */
    private String resultMessage;
    private String userAgent;
    /** AES-256-GCM 加密的 JSON：访问入口、网络、客户端、请求头与请求原文。 */
    private String loginDetail;
    private LocalDateTime createTime;
}
