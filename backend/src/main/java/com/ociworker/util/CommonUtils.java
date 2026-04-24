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
            // chpasswd 行用 Base64 传入，避免密码中的 ' " $ ` \ 等把 shell 或 user_data 弄断（此前登录失败主因之一）
            String chpasswdLine = "root:" + password + "\n";
            String chpasswdB64 = Base64.getEncoder().encodeToString(
                    chpasswdLine.getBytes(StandardCharsets.UTF_8));
            sb.append("set -e\n");
            sb.append("printf '%s' '").append(chpasswdB64).append("' | base64 -d | chpasswd\n");
            sb.append("set +e\n");
            // 与 cloud-init 的 50-*.conf 等：用更晚的序号覆盖
            sb.append("if [ -d /etc/ssh/sshd_config.d ]; then\n");
            sb.append("  cat > /etc/ssh/sshd_config.d/99-ociworker.conf <<'SSHEOF'\n");
            sb.append("PermitRootLogin yes\n");
            sb.append("PasswordAuthentication yes\n");
            sb.append("KbdInteractiveAuthentication yes\n");
            sb.append("PubkeyAuthentication yes\n");
            sb.append("SSHEOF\n");
            sb.append("  chmod 644 /etc/ssh/sshd_config.d/99-ociworker.conf\n");
            sb.append("fi\n");
            sb.append("sed -i -E 's/^[#[:space:]]*PermitRootLogin[[:space:]].*/PermitRootLogin yes/' /etc/ssh/sshd_config\n");
            sb.append("sed -i -E 's/^[#[:space:]]*PasswordAuthentication[[:space:]].*/PasswordAuthentication yes/' /etc/ssh/sshd_config\n");
            // 配置不合法时勿盲目 restart，避免把 sshd 打挂
            sb.append("if sshd -t 2>>/var/log/ociworker-bootstrap.log; then\n");
            sb.append("  systemctl restart sshd 2>/dev/null || systemctl restart ssh 2>/dev/null || ");
            sb.append("service sshd restart 2>/dev/null || service ssh restart\n");
            sb.append("else\n");
            sb.append("  echo 'ociworker: sshd -t failed, not restarting ssh' >>/var/log/ociworker-bootstrap.log\n");
            sb.append("fi\n");
        }
        if (customScript != null && !customScript.trim().isEmpty()) {
            sb.append("\n# --- Custom Script ---\n");
            sb.append(customScript.trim()).append("\n");
        }
        return sb.length() > "#!/bin/bash\n".length() ? sb.toString() : "";
    }
}
