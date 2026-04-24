package com.ociworker.util;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
        if (token == null || account == null || password == null) return false;
        long currentSlot = System.currentTimeMillis() / (1000 * 60 * 60 * TOKEN_EXPIRE_HOURS);
        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i <= 1; i++) {
            String raw = account + ":" + password + ":" + (currentSlot - i);
            String expected = Base64.getEncoder().encodeToString(DigestUtil.sha256(raw));
            byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(tokenBytes, expectedBytes)) return true;
        }
        return false;
    }

    public static String getPwdShell(String password) {
        return getPwdShell(password, null);
    }

    public static String getPwdShell(String password, String customScript) {
        StringBuilder sb = new StringBuilder("#!/bin/bash\n");
        if (password != null && !password.isEmpty()) {
            // 设 root 密码
            sb.append("echo 'root:").append(password).append("' | chpasswd\n");
            // 许多镜像用 prohibit-password 或把规则写在 sshd_config.d（如 50-cloud-init.conf），
            // 仅 sed 主文件里 # 开头的 PermitRootLogin 会无效，导致「仅密钥、无密码」类握手错误。
            sb.append("if [ -d /etc/ssh/sshd_config.d ]; then\n");
            sb.append("  printf '%s\\n' 'PermitRootLogin yes' 'PasswordAuthentication yes' ");
            sb.append("> /etc/ssh/sshd_config.d/99-ociworker.conf\n");
            sb.append("  chmod 644 /etc/ssh/sshd_config.d/99-ociworker.conf\n");
            sb.append("fi\n");
            sb.append("sed -i -E 's/^[#[:space:]]*PermitRootLogin[[:space:]].*/PermitRootLogin yes/' ");
            sb.append("/etc/ssh/sshd_config\n");
            sb.append("sed -i -E 's/^[#[:space:]]*PasswordAuthentication[[:space:]].*/PasswordAuthentication yes/' ");
            sb.append("/etc/ssh/sshd_config\n");
            sb.append("systemctl restart sshd 2>/dev/null || systemctl restart ssh 2>/dev/null || ");
            sb.append("service sshd restart 2>/dev/null || service ssh restart\n");
        }
        if (customScript != null && !customScript.trim().isEmpty()) {
            sb.append("\n# --- Custom Script ---\n");
            sb.append(customScript.trim()).append("\n");
        }
        return sb.length() > "#!/bin/bash\n".length() ? sb.toString() : "";
    }
}
