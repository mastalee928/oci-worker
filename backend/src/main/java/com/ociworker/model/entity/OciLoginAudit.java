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
    private String userAgent;
    /** JSON：访问入口、网络、客户端与已脱敏请求头；不包含密码、验证码、Token 或 Cookie 明文。 */
    private String loginDetail;
    private LocalDateTime createTime;
}
