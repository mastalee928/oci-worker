package com.ociworker.service;

import com.oracle.bmc.Region;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.model.InstanceConsoleConnection;
import com.oracle.bmc.core.requests.*;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciUser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ConsoleService {

    @Resource
    private OciUserMapper userMapper;

    private static final String KEY_DIR = "./keys";
    private static final String PRIVATE_KEY_FILE = "console_rsa";
    private static final String PUBLIC_KEY_FILE = "console_rsa.pub";

    private String publicKeyContent;
    private String privateKeyPath;

    private final Map<String, ConsoleSession> activeSessions = new ConcurrentHashMap<>();

    public static class ConsoleSession {
        public String consoleConnectionId;
        public String instanceId;
        public String tenantId;
        public String tempUser;
        public String tempPassword;
        public String sshCommand;
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

            if (Files.exists(privPath) && Files.exists(pubPath)) {
                publicKeyContent = Files.readString(pubPath).trim();
                log.info("【串行控制台】已加载 SSH 密钥: {}", pubPath.toAbsolutePath());
            } else {
                generateSshKeyPair(privPath, pubPath);
                log.info("【串行控制台】已生成 SSH 密钥: {}", pubPath.toAbsolutePath());
            }
        } catch (Exception e) {
            log.error("【串行控制台】SSH 密钥初始化失败: {}", e.getMessage());
        }

        cleanupStaleSessions();
    }

    private void generateSshKeyPair(Path privPath, Path pubPath) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        // Write private key in PEM format
        StringBuilder privPem = new StringBuilder();
        privPem.append("-----BEGIN RSA PRIVATE KEY-----\n");
        String b64 = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        for (int i = 0; i < b64.length(); i += 64) {
            privPem.append(b64, i, Math.min(i + 64, b64.length())).append("\n");
        }
        privPem.append("-----END RSA PRIVATE KEY-----\n");
        Files.writeString(privPath, privPem.toString());

        // Set permission 600
        try {
            Runtime.getRuntime().exec(new String[]{"chmod", "600", privPath.toAbsolutePath().toString()});
        } catch (Exception ignored) {}

        // Write public key in OpenSSH format
        RSAPublicKey rsaPub = (RSAPublicKey) kp.getPublic();
        publicKeyContent = encodePublicKeyOpenSSH(rsaPub);
        Files.writeString(pubPath, publicKeyContent + "\n");
    }

    private String encodePublicKeyOpenSSH(RSAPublicKey key) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            byte[] sshRsa = "ssh-rsa".getBytes();
            writeBytes(buf, sshRsa);
            writeBytes(buf, key.getPublicExponent().toByteArray());
            writeBytes(buf, key.getModulus().toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return "ssh-rsa " + Base64.getEncoder().encodeToString(buf.toByteArray()) + " oci-worker-console";
    }

    private void writeBytes(ByteArrayOutputStream buf, byte[] data) throws IOException {
        buf.write((data.length >> 24) & 0xFF);
        buf.write((data.length >> 16) & 0xFF);
        buf.write((data.length >> 8) & 0xFF);
        buf.write(data.length & 0xFF);
        buf.write(data);
    }

    public Map<String, String> createConsoleConnection(String userId, String instanceId) {
        if (publicKeyContent == null || publicKeyContent.isEmpty()) {
            throw new OciException("SSH 密钥未初始化，无法创建控制台连接");
        }

        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        ComputeClient computeClient = buildComputeClient(ociUser);
        try {
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
                if (conn.getLifecycleState() == InstanceConsoleConnection.LifecycleState.Active) {
                    computeClient.deleteInstanceConsoleConnection(
                            DeleteInstanceConsoleConnectionRequest.builder()
                                    .instanceConsoleConnectionId(conn.getId()).build());
                    log.info("【串行控制台】删除旧连接: {}", conn.getId());
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

            String tempUser = "oci_console_" + System.currentTimeMillis();
            String tempPassword = generateRandomPassword();
            String scriptPath = createConsoleScript(tempUser, sshCommand);
            createTempSystemUser(tempUser, tempPassword, scriptPath);

            ConsoleSession session = new ConsoleSession();
            session.consoleConnectionId = active.getId();
            session.instanceId = instanceId;
            session.tenantId = userId;
            session.tempUser = tempUser;
            session.tempPassword = tempPassword;
            session.sshCommand = sshCommand;
            session.createdAt = System.currentTimeMillis();
            activeSessions.put(active.getId(), session);

            Map<String, String> result = new LinkedHashMap<>();
            result.put("connectionId", active.getId());
            result.put("tempUser", tempUser);
            result.put("tempPassword", tempPassword);
            result.put("sshCommand", sshCommand);
            result.put("state", active.getLifecycleState().getValue());

            log.info("【串行控制台】连接已创建: {} 临时用户: {}", active.getId(), tempUser);
            return result;

        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("创建控制台连接失败: " + e.getMessage());
        } finally {
            computeClient.close();
        }
    }

    public void deleteConsoleConnection(String userId, String connectionId) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        ComputeClient computeClient = buildComputeClient(ociUser);
        try {
            computeClient.deleteInstanceConsoleConnection(
                    DeleteInstanceConsoleConnectionRequest.builder()
                            .instanceConsoleConnectionId(connectionId).build());
        } catch (Exception e) {
            log.warn("【串行控制台】删除OCI连接失败: {}", e.getMessage());
        } finally {
            computeClient.close();
        }

        ConsoleSession session = activeSessions.remove(connectionId);
        if (session != null) {
            cleanupTempUser(session.tempUser);
        }
        log.info("【串行控制台】连接已断开: {}", connectionId);
    }

    private String createConsoleScript(String userName, String sshCommand) {
        try {
            Path scriptDir = Path.of(KEY_DIR);
            Path scriptPath = scriptDir.resolve("console_" + userName + ".sh");
            String sshCmd = sshCommand.replace("ssh ", "ssh -tt -i " + privateKeyPath + " -o StrictHostKeyChecking=no -o ServerAliveInterval=15 -o ServerAliveCountMax=3 ");
            String script = "#!/bin/bash\n" +
                    "trap 'kill 0 2>/dev/null; exit' EXIT INT TERM HUP\n" +
                    "# Kill other sessions of this user\n" +
                    "for p in $(pgrep -u \"$(whoami)\" ssh 2>/dev/null); do\n" +
                    "  [ \"$p\" != \"$$\" ] && kill \"$p\" 2>/dev/null\n" +
                    "done\n" +
                    "echo '正在连接串行控制台...'\n" +
                    "echo '按 Ctrl+] 或 ~. 退出'\n" +
                    "echo ''\n" +
                    sshCmd + "\n" +
                    "echo '串行控制台已断开'\n" +
                    "sleep 1\n" +
                    "exit 0\n";
            Files.writeString(scriptPath, script);
            Runtime.getRuntime().exec(new String[]{"chmod", "+x", scriptPath.toAbsolutePath().toString()}).waitFor();
            return scriptPath.toAbsolutePath().toString();
        } catch (Exception e) {
            throw new OciException("创建控制台脚本失败: " + e.getMessage());
        }
    }

    private void createTempSystemUser(String user, String password, String shell) {
        try {
            Process p1 = Runtime.getRuntime().exec(new String[]{
                    "useradd", "-m", "-s", shell, user
            });
            p1.waitFor();

            Process p2 = Runtime.getRuntime().exec(new String[]{"chpasswd"});
            p2.getOutputStream().write((user + ":" + password).getBytes());
            p2.getOutputStream().close();
            p2.waitFor();

            log.info("【串行控制台】创建临时用户: {}", user);
        } catch (Exception e) {
            throw new OciException("创建临时用户失败: " + e.getMessage());
        }
    }

    private void cleanupTempUser(String user) {
        try {
            // Kill ALL processes of this user first
            Process killAll = Runtime.getRuntime().exec(new String[]{"pkill", "-9", "-u", user});
            killAll.waitFor();
            Thread.sleep(500);

            Runtime.getRuntime().exec(new String[]{"userdel", "-rf", user}).waitFor();
            Path scriptPath = Path.of(KEY_DIR, "console_" + user + ".sh");
            Files.deleteIfExists(scriptPath);
            log.info("【串行控制台】清理临时用户及进程: {}", user);
        } catch (Exception e) {
            log.warn("【串行控制台】清理临时用户失败: {} - {}", user, e.getMessage());
        }
    }

    private void cleanupStaleSessions() {
        try {
            // Kill any leftover oci_console_ processes and users from previous runs
            Process p = Runtime.getRuntime().exec(new String[]{"bash", "-c",
                    "grep -o 'oci_console_[0-9]*' /etc/passwd 2>/dev/null"});
            String output = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (!output.isEmpty()) {
                for (String user : output.split("\n")) {
                    user = user.trim();
                    if (!user.isEmpty()) {
                        log.info("【串行控制台】清理残留用户: {}", user);
                        cleanupTempUser(user);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("【串行控制台】清理残留用户失败: {}", e.getMessage());
        }
    }

    private String generateRandomPassword() {
        String chars = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        Random r = new Random();
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private ComputeClient buildComputeClient(OciUser ociUser) {
        SimpleAuthenticationDetailsProvider provider = SimpleAuthenticationDetailsProvider.builder()
                .tenantId(ociUser.getOciTenantId())
                .userId(ociUser.getOciUserId())
                .fingerprint(ociUser.getOciFingerprint())
                .privateKeySupplier(() -> {
                    try {
                        return new FileInputStream(ociUser.getOciKeyPath());
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to read private key");
                    }
                })
                .region(Region.valueOf(ociUser.getOciRegion()))
                .build();
        return ComputeClient.builder().build(provider);
    }
}
