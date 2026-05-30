package com.ociworker.service;

import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.model.InstanceConsoleConnection;
import com.oracle.bmc.core.requests.*;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ConsoleService {

    @Resource
    private OciUserMapper userMapper;
    @Lazy
    @Resource
    private OciProxyConfigService ociProxyConfigService;

    private static final String KEY_DIR = "./keys";
    private static final String PRIVATE_KEY_FILE = "console_rsa";
    private static final String PUBLIC_KEY_FILE = "console_rsa.pub";

    private static final String SSH_HOST_OPTS =
            "-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null "
                    + "-o ServerAliveInterval=15 -o ServerAliveCountMax=3 ";
    private static final String RSA_OPTS =
            "-o HostkeyAlgorithms=+ssh-rsa -o PubkeyAcceptedAlgorithms=+ssh-rsa ";

    private String publicKeyContent;
    private String privateKeyPath;

    private final Map<String, ConsoleSession> activeSessions = new ConcurrentHashMap<>();

    public static class ConsoleSession {
        public String consoleConnectionId;
        public String instanceId;
        public String tenantId;
        public String sshCommand;
        public String execScriptPath;
        public long createdAt;
    }

    @PostConstruct
    public void init() {
        try {
            Path keyDir = Path.of(KEY_DIR);
            Files.createDirectories(keyDir);
            Path privPath = keyDir.resolve(PRIVATE_KEY_FILE);
            Path pubPath = keyDir.resolve(PUBLIC_KEY_FILE);
            privateKeyPath = privPath.toAbsolutePath().toString();

            boolean needRegenerate = !Files.exists(privPath) || !Files.exists(pubPath);

            if (!needRegenerate) {
                String privContent = Files.readString(privPath);
                if (!privContent.contains("-----BEGIN OPENSSH PRIVATE KEY-----")) {
                    log.warn("【串行控制台】密钥非 ssh-keygen 格式，强制重新生成...");
                    needRegenerate = true;
                }
            }

            if (needRegenerate) {
                generateSshKeyPair(privPath, pubPath);
                log.info("【串行控制台】已生成 SSH 密钥: {}", pubPath.toAbsolutePath());
            } else {
                publicKeyContent = Files.readString(pubPath).trim();
                log.info("【串行控制台】已加载 SSH 密钥: {}", pubPath.toAbsolutePath());
            }
        } catch (Exception e) {
            log.error("【串行控制台】SSH 密钥初始化失败: {}", e.getMessage());
        }

        cleanupLegacyTempUsers();
    }

    private void generateSshKeyPair(Path privPath, Path pubPath) throws Exception {
        Files.deleteIfExists(privPath);
        Files.deleteIfExists(pubPath);

        ProcessBuilder pb = new ProcessBuilder(
                "ssh-keygen", "-t", "rsa", "-b", "2048", "-f", privPath.toAbsolutePath().toString(),
                "-N", "", "-C", "oci-worker-console").redirectErrorStream(true);
        Process p = pb.start();
        String output;
        try (InputStream in = p.getInputStream()) {
            output = new String(in.readAllBytes());
        }
        p.waitFor();
        if (p.exitValue() != 0) {
            throw new RuntimeException("ssh-keygen failed: " + output);
        }

        publicKeyContent = Files.readString(pubPath).trim();
    }

    public Map<String, String> createConsoleConnection(String userId, String instanceId, String region) {
        if (publicKeyContent == null || publicKeyContent.isEmpty()) {
            throw new OciException("SSH 密钥未初始化，无法创建控制台连接");
        }

        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService env = oci(ociUser, region)) {
            ComputeClient computeClient = env.getComputeClient();
            var instance = computeClient.getInstance(
                    GetInstanceRequest.builder().instanceId(instanceId).build()
            ).getInstance();
            String compartmentId = instance.getCompartmentId();

            var existing = computeClient.listInstanceConsoleConnections(
                    ListInstanceConsoleConnectionsRequest.builder()
                            .compartmentId(compartmentId)
                            .instanceId(instanceId)
                            .build()
            ).getItems();

            for (var conn : existing) {
                var state = conn.getLifecycleState();
                if (state == InstanceConsoleConnection.LifecycleState.Active
                        || state == InstanceConsoleConnection.LifecycleState.Creating) {
                    computeClient.deleteInstanceConsoleConnection(
                            DeleteInstanceConsoleConnectionRequest.builder()
                                    .instanceConsoleConnectionId(conn.getId()).build());
                    log.info("【串行控制台】删除旧连接: {} (状态: {})", conn.getId(), state);
                }
            }

            if (!existing.isEmpty()) {
                boolean cleared = false;
                for (int i = 0; i < 15; i++) {
                    Thread.sleep(2000);
                    var check = computeClient.listInstanceConsoleConnections(
                            ListInstanceConsoleConnectionsRequest.builder()
                                    .compartmentId(compartmentId)
                                    .instanceId(instanceId)
                                    .build()
                    ).getItems();
                    boolean allGone = check.stream().allMatch(c ->
                            c.getLifecycleState() == InstanceConsoleConnection.LifecycleState.Deleted);
                    if (allGone || check.isEmpty()) {
                        cleared = true;
                        break;
                    }
                }
                if (!cleared) {
                    throw new OciException("旧连接尚未完全删除，请稍后再试");
                }
            }

            InstanceConsoleConnection connection = computeClient
                    .createInstanceConsoleConnection(
                            CreateInstanceConsoleConnectionRequest.builder()
                                    .createInstanceConsoleConnectionDetails(
                                            com.oracle.bmc.core.model.CreateInstanceConsoleConnectionDetails.builder()
                                                    .instanceId(instanceId)
                                                    .publicKey(publicKeyContent)
                                                    .build())
                                    .build()
                    ).getInstanceConsoleConnection();

            int maxWait = 15;
            InstanceConsoleConnection active = connection;
            while (maxWait-- > 0 && active.getLifecycleState() != InstanceConsoleConnection.LifecycleState.Active) {
                Thread.sleep(2000);
                active = computeClient.getInstanceConsoleConnection(
                        GetInstanceConsoleConnectionRequest.builder()
                                .instanceConsoleConnectionId(connection.getId()).build()
                ).getInstanceConsoleConnection();
            }

            if (active.getLifecycleState() != InstanceConsoleConnection.LifecycleState.Active) {
                throw new OciException("控制台连接创建超时，请稍后重试");
            }

            String sshCommand = active.getConnectionString();
            log.info("【串行控制台】OCI connectionString: {}", sshCommand);

            ConsoleSession session = new ConsoleSession();
            session.consoleConnectionId = active.getId();
            session.instanceId = instanceId;
            session.tenantId = userId;
            session.sshCommand = sshCommand;
            session.createdAt = System.currentTimeMillis();
            activeSessions.put(active.getId(), session);

            Map<String, String> result = new LinkedHashMap<>();
            result.put("connectionId", active.getId());
            result.put("sshCommand", sshCommand);
            result.put("state", active.getLifecycleState().getValue());

            log.info("【串行控制台】连接已创建: {}", active.getId());
            return result;

        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("创建控制台连接失败: " + e.getMessage());
        }
    }

    public void deleteConsoleConnection(String userId, String connectionId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService env = oci(ociUser, region)) {
            ComputeClient computeClient = env.getComputeClient();
            try {
                computeClient.deleteInstanceConsoleConnection(
                        DeleteInstanceConsoleConnectionRequest.builder()
                                .instanceConsoleConnectionId(connectionId).build());
            } catch (Exception e) {
                log.warn("【串行控制台】删除OCI连接失败: {}", e.getMessage());
            }
        }

        ConsoleSession session = activeSessions.remove(connectionId);
        if (session != null) {
            deleteExecScript(session);
        }
        log.info("【串行控制台】连接已断开: {}", connectionId);
    }

    /**
     * 在 OCI 返回的 connection-string 上仅注入私钥路径与文档要求的 RSA 算法选项，不改动 -t/-tt 等原有参数。
     */
    public String buildPreparedSshCommand(String connectionString) {
        if (connectionString == null || connectionString.isBlank()) {
            throw new OciException("无效的 connectionString");
        }
        String cmd = connectionString.trim();
        String key = privateKeyPath;

        if (!cmd.contains("HostkeyAlgorithms")) {
            cmd = cmd.replaceFirst("^ssh\\s+", "ssh " + RSA_OPTS);
        }

        if (cmd.contains("ProxyCommand='ssh ")) {
            cmd = cmd.replace("ProxyCommand='ssh ",
                    "ProxyCommand='ssh -i " + key + " " + SSH_HOST_OPTS);
        } else if (cmd.contains("ProxyCommand=\"ssh ")) {
            cmd = cmd.replace("ProxyCommand=\"ssh ",
                    "ProxyCommand=\"ssh -i " + key + " " + SSH_HOST_OPTS);
        }

        if (cmd.startsWith("ssh ")) {
            cmd = "ssh -i " + key + " " + SSH_HOST_OPTS + cmd.substring(4);
        }

        return cmd;
    }

    public Path getOrCreateExecScript(String connectionId) throws IOException {
        ConsoleSession session = activeSessions.get(connectionId);
        if (session == null) {
            throw new OciException("控制台会话不存在或已过期，请重新创建连接");
        }

        if (session.execScriptPath != null) {
            Path existing = Path.of(session.execScriptPath);
            if (!Files.exists(existing)) {
                session.execScriptPath = null;
            }
        }

        String prepared = buildPreparedSshCommand(session.sshCommand);
        Path script = session.execScriptPath != null
                ? Path.of(session.execScriptPath)
                : Path.of(KEY_DIR).resolve("console_exec_" + safeId(connectionId) + ".sh");
        String content = "#!/bin/bash\nexport TERM=vt100\nexec " + prepared + "\n";
        Files.writeString(script, content);
        try {
            new ProcessBuilder("chmod", "+x", script.toAbsolutePath().toString())
                    .redirectErrorStream(true).start().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("chmod failed", e);
        }
        session.execScriptPath = script.toAbsolutePath().toString();
        log.info("【串行控制台】执行脚本: {} -> {}", connectionId, prepared);
        return script;
    }

    private void deleteExecScript(ConsoleSession session) {
        if (session.execScriptPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(session.execScriptPath));
        } catch (Exception e) {
            log.warn("【串行控制台】删除脚本失败: {}", e.getMessage());
        }
    }

    private static String safeId(String connectionId) {
        return connectionId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** 清理旧版临时用户（升级前遗留） */
    private void cleanupLegacyTempUsers() {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                    "grep -o 'oci_console_[0-9]*' /etc/passwd 2>/dev/null").redirectErrorStream(true);
            Process p = pb.start();
            String output;
            try (InputStream in = p.getInputStream()) {
                output = new String(in.readAllBytes()).trim();
            }
            p.waitFor();
            if (!output.isEmpty()) {
                for (String user : output.split("\n")) {
                    user = user.trim();
                    if (!user.isEmpty()) {
                        log.info("【串行控制台】清理旧版临时用户: {}", user);
                        cleanupLegacyTempUser(user);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("【串行控制台】清理旧版临时用户失败: {}", e.getMessage());
        }
    }

    private void cleanupLegacyTempUser(String user) {
        try {
            Process killAll = Runtime.getRuntime().exec(new String[]{"pkill", "-9", "-u", user});
            killAll.waitFor();
            Thread.sleep(500);
            Runtime.getRuntime().exec(new String[]{"userdel", "-rf", user}).waitFor();
            Path scriptPath = Path.of(KEY_DIR, "console_" + user + ".sh");
            Files.deleteIfExists(scriptPath);
        } catch (Exception e) {
            log.warn("【串行控制台】清理旧版用户失败: {} - {}", user, e.getMessage());
        }
    }

    @Scheduled(fixedRate = 300_000)
    public void periodicCleanup() {
        long cutoff = System.currentTimeMillis() - 7200_000;
        List<String> expired = new ArrayList<>();
        activeSessions.forEach((id, session) -> {
            if (session.createdAt < cutoff) expired.add(id);
        });
        for (String id : expired) {
            ConsoleSession session = activeSessions.remove(id);
            if (session != null) {
                deleteExecScript(session);
                log.info("【串行控制台】清理过期会话: {}", id);
            }
        }
    }

    private SysUserDTO buildDto(OciUser ociUser) {
        return SysUserDTO.builder()
                .username(ociUser.getUsername())
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(ociUser.getOciTenantId())
                        .userId(ociUser.getOciUserId())
                        .fingerprint(ociUser.getOciFingerprint())
                        .region(ociUser.getOciRegion())
                        .privateKeyPath(ociUser.getOciKeyPath())
                        .build())
                .build();
    }

    private OciClientService oci(OciUser ociUser, String region) {
        String r = (region == null || region.isBlank()) ? null : region.trim();
        return new OciClientService(buildDto(ociUser), r);
    }
}
