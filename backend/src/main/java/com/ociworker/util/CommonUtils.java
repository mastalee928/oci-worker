package com.ociworker.util;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;

import java.util.Base64;

public class CommonUtils {

    public static final String CREATE_TASK_PREFIX = "create_task_";
    public static final String CHANGE_IP_TASK_PREFIX = "change_ip_task_";

    public static final String BEGIN_CREATE_MESSAGE_TEMPLATE =
            "【开机任务】\n\n🚀 开始抢机 🚀\n" +
            "用户：%s\n" +
            "时间：%s\n" +
            "Region：%s\n" +
            "CPU类型：%s\n" +
            "CPU：%s\n" +
            "内存（GB）：%s\n" +
            "磁盘大小（GB）：%s\n" +
            "开机数量：%s\n" +
            "root密码：%s";

    private static final long TOKEN_EXPIRE_HOURS = 24;

    public static String generateId() {
        return IdUtil.fastSimpleUUID();
    }

    public static String generateToken(String account, String password) {
        long expireSlot = System.currentTimeMillis() / (1000 * 60 * 60 * TOKEN_EXPIRE_HOURS);
        String raw = account + ":" + password + ":" + expireSlot;
        return Base64.getEncoder().encodeToString(DigestUtil.sha256(raw));
    }

    public static boolean validateToken(String token, String account, String password) {
        long currentSlot = System.currentTimeMillis() / (1000 * 60 * 60 * TOKEN_EXPIRE_HOURS);
        for (int i = 0; i <= 1; i++) {
            String raw = account + ":" + password + ":" + (currentSlot - i);
            String expected = Base64.getEncoder().encodeToString(DigestUtil.sha256(raw));
            if (expected.equals(token)) return true;
        }
        return false;
    }

    public static String getPwdShell(String password) {
        if (password == null || password.isEmpty()) {
            return "";
        }
        return "#!/bin/bash\n" +
               "echo 'root:" + password + "' | chpasswd\n" +
               "sed -i 's/#PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config\n" +
               "sed -i 's/PasswordAuthentication no/PasswordAuthentication yes/' /etc/ssh/sshd_config\n" +
               "systemctl restart sshd || service sshd restart\n";
    }
}
