package com.ociworker.service;

import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.model.InstanceConsoleConnection;
import com.oracle.bmc.core.requests.*;
import com.oracle.bmc.model.BmcException;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import com.ociworker.util.OciBmcErrorTranslator;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

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
    private static final String EXEC_SCRIPT_PREFIX = "console_exec_";
    private static final String EXEC_SCRIPT_SUFFIX = ".sh";
    private static final int MAX_CONNECTION_ID_LENGTH = 512;
    private static final long SESSION_IDLE_MILLIS = 7_200_000L;
    private static final Pattern GENERATED_EXEC_SCRIPT = Pattern.compile(
            "^console_exec_[a-zA-Z0-9._-]+\\.sh$");

    private static final String SSH_HOST_OPTS =
            "-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null "
                    + "-o ServerAliveInterval=15 -o ServerAliveCountMax=3 ";
    private static final String RSA_OPTS =
            "-o HostkeyAlgorithms=+ssh-rsa -o PubkeyAcceptedAlgorithms=+ssh-rsa ";

    private String publicKeyContent;
    private String privateKeyPath;
    private Path keyDirectory = Path.of(KEY_DIR);

    private final Map<String, ConsoleSession> activeSessions = new ConcurrentHashMap<>();
    private final ExecutorService cleanupExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("console-cleanup-", 0).factory());
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public static class ConsoleSession {
        public String consoleConnectionId;
        public String instanceId;
        public String tenantId;
        public String ownerAccount;
        public String region;
        public String sshCommand;
        public String execScriptPath;
        public String leaseId;
        public boolean cleanupInProgress;
        public long createdAt;
        public long lastTouchedAt;
    }

    public record ConsoleLease(String connectionId, String leaseId) {}

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(keyDirectory);
            cleanupOrphanExecScripts();
            Path privPath = keyDirectory.resolve(PRIVATE_KEY_FILE);
            Path pubPath = keyDirectory.resolve(PUBLIC_KEY_FILE);
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
        try {
            p.waitFor();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }
        if (p.exitValue() != 0) {
            throw new RuntimeException("ssh-keygen failed: " + output);
        }

        publicKeyContent = Files.readString(pubPath).trim();
    }

    public Map<String, String> createConsoleConnection(String userId, String instanceId,
                                                       String region, String ownerAccount) {
        userId = requireIdentifier(userId, "租户配置");
        instanceId = requireIdentifier(instanceId, "实例");
        requireOwner(ownerAccount);
        if (publicKeyContent == null || publicKeyContent.isEmpty()) {
            throw new OciException("SSH 密钥未初始化，无法创建控制台连接");
        }

        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService env = oci(ociUser, region)) {
            ComputeClient computeClient = env.getComputeClient();
            removeLocalSessionsForInstance(userId, instanceId);
            InstanceConsoleConnection active = provisionConnection(
                    computeClient, instanceId, publicKeyContent);

            String sshCommand = active.getConnectionString();

            ConsoleSession session = new ConsoleSession();
            session.consoleConnectionId = active.getId();
            session.instanceId = instanceId;
            session.tenantId = userId;
            session.ownerAccount = ownerAccount;
            session.region = normalizeRegion(region);
            session.sshCommand = sshCommand;
            session.createdAt = System.currentTimeMillis();
            session.lastTouchedAt = session.createdAt;
            ConsoleSession replaced = activeSessions.put(active.getId(), session);
            if (replaced != null) {
                deleteExecScript(replaced);
            }

            Map<String, String> result = new LinkedHashMap<>();
            result.put("connectionId", active.getId());
            result.put("sshCommand", sshCommand);
            result.put("state", active.getLifecycleState().getValue());

            log.info("【串行控制台】连接已创建: {}", active.getId());
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OciException("创建控制台连接已取消");
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("创建控制台连接失败: " + e.getMessage());
        }
    }

    /** 清理实例上的旧控制台连接，用给定公钥新建一个并等待 ACTIVE。 */
    private InstanceConsoleConnection provisionConnection(ComputeClient computeClient,
                                                          String instanceId, String publicKey)
            throws InterruptedException {
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
                                                .publicKey(publicKey)
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
            try {
                computeClient.deleteInstanceConsoleConnection(
                        DeleteInstanceConsoleConnectionRequest.builder()
                                .instanceConsoleConnectionId(connection.getId())
                                .build());
            } catch (Exception cleanupError) {
                log.warn("【串行控制台】回收创建超时的 OCI 连接失败: {}", cleanupError.getMessage());
            }
            throw new OciException("控制台连接创建超时，请稍后重试");
        }
        return active;
    }

    /**
     * 本机直连：创建控制台连接并返回串口/VNC 命令，命令在用户自己的电脑上运行。
     * publicKey 为空且 generateKey=true 时由面板生成一次性密钥对，私钥随响应返回
     * 供用户下载，服务端不落盘、不保存。
     */
    public Map<String, String> createLocalConsoleConnection(String userId, String instanceId,
                                                            String region, String publicKey,
                                                            boolean generateKey, String ownerAccount) {
        userId = requireIdentifier(userId, "租户配置");
        instanceId = requireIdentifier(instanceId, "实例");
        requireOwner(ownerAccount);

        String generatedPrivateKey = null;
        String effectivePublicKey;
        if (generateKey && (publicKey == null || publicKey.isBlank())) {
            String[] pair = generateDisposableKeyPair();
            generatedPrivateKey = pair[0];
            effectivePublicKey = pair[1];
        } else {
            effectivePublicKey = normalizeLocalPublicKey(publicKey);
        }

        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        try (OciClientService env = oci(ociUser, region)) {
            ComputeClient computeClient = env.getComputeClient();
            removeLocalSessionsForInstance(userId, instanceId);
            InstanceConsoleConnection active = provisionConnection(
                    computeClient, instanceId, effectivePublicKey);

            Map<String, String> result = new LinkedHashMap<>();
            result.put("connectionId", active.getId());
            result.put("serialCommand", active.getConnectionString() == null
                    ? "" : active.getConnectionString());
            result.put("vncCommand", active.getVncConnectionString() == null
                    ? "" : active.getVncConnectionString());
            result.put("state", active.getLifecycleState().getValue());
            if (generatedPrivateKey != null) {
                result.put("privateKey", generatedPrivateKey);
                result.put("keyFileName", "oci-console-"
                        + instanceId.substring(Math.max(0, instanceId.length() - 8)) + ".key");
            }
            log.info("【串行控制台】本机直连已创建: {}", active.getId());
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OciException("创建本机直连已取消");
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("创建本机直连失败: " + e.getMessage());
        }
    }

    /** 生成一次性 RSA 密钥对，返回 [私钥PEM, OpenSSH公钥]，仅存在于本次响应。 */
    private static String[] generateDisposableKeyPair() {
        com.jcraft.jsch.KeyPair pair = null;
        try {
            pair = com.jcraft.jsch.KeyPair.genKeyPair(
                    new com.jcraft.jsch.JSch(), com.jcraft.jsch.KeyPair.RSA, 2048);
            java.io.ByteArrayOutputStream privateOut = new java.io.ByteArrayOutputStream();
            pair.writePrivateKey(privateOut);
            java.io.ByteArrayOutputStream publicOut = new java.io.ByteArrayOutputStream();
            pair.writePublicKey(publicOut, "ociworker-console");
            return new String[]{
                    privateOut.toString(java.nio.charset.StandardCharsets.UTF_8),
                    publicOut.toString(java.nio.charset.StandardCharsets.UTF_8).trim()};
        } catch (Exception e) {
            throw new OciException("生成密钥对失败: " + e.getMessage());
        } finally {
            if (pair != null) pair.dispose();
        }
    }

    private static String normalizeLocalPublicKey(String publicKey) {
        String normalized = publicKey == null ? "" : publicKey.trim().replaceAll("[\\r\\n]+", " ");
        if (normalized.isEmpty()) {
            throw new OciException("请粘贴 OpenSSH 公钥（对应私钥保存在你本地）");
        }
        if (!normalized.matches(
                "(ssh-(rsa|ed25519|dss)|ecdsa-sha2-nistp(256|384|521))\\s+[A-Za-z0-9+/=]+(\\s+\\S{0,256})?")) {
            throw new OciException("SSH 公钥格式不正确，请提供 OpenSSH 格式公钥（如 ssh-ed25519 / ssh-rsa 开头）");
        }
        return normalized;
    }

    public void deleteConsoleConnection(String userId, String connectionId,
                                        String region, String ownerAccount) {
        userId = requireIdentifier(userId, "租户配置");
        String normalizedConnectionId = requireConnectionId(connectionId);
        requireOwner(ownerAccount);

        ConsoleSession session = activeSessions.get(normalizedConnectionId);
        String effectiveRegion = normalizeRegion(region);
        if (session != null) {
            synchronized (session) {
                if (activeSessions.get(normalizedConnectionId) != session) {
                    throw new OciException("控制台会话状态已变化，请刷新后重试");
                }
                if (!Objects.equals(session.tenantId, userId)) {
                    throw new OciException("控制台会话不属于当前租户");
                }
                if (!Objects.equals(session.ownerAccount, ownerAccount)) {
                    throw new OciException("控制台会话不属于当前账号");
                }
                if (session.cleanupInProgress) {
                    throw new OciException("控制台会话正在清理，请稍后重试");
                }
                session.cleanupInProgress = true;
                effectiveRegion = session.region;
            }
        }

        try {
            deleteRemoteConsoleConnection(userId, normalizedConnectionId, effectiveRegion);
            if (session != null) {
                removeLocalSession(normalizedConnectionId, session);
            }
            log.info("【串行控制台】连接已断开: {}", normalizedConnectionId);
        } catch (RuntimeException e) {
            resetCleanupState(normalizedConnectionId, session);
            throw e;
        }
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
        if (key == null || key.isBlank()) {
            throw new OciException("SSH 密钥未初始化，无法启动控制台连接");
        }

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

    public ConsoleLease claimConsoleSession(String connectionId, String ownerAccount) {
        if (shuttingDown.get()) {
            throw new OciException("服务正在停止，请稍后重试");
        }
        String normalizedConnectionId = requireConnectionId(connectionId);
        requireOwner(ownerAccount);
        ConsoleSession session = activeSessions.get(normalizedConnectionId);
        if (session == null) {
            throw new OciException("控制台会话不存在或已过期，请重新创建连接");
        }
        synchronized (session) {
            if (activeSessions.get(normalizedConnectionId) != session) {
                throw new OciException("控制台会话不存在或已过期，请重新创建连接");
            }
            if (!Objects.equals(session.ownerAccount, ownerAccount)) {
                throw new OciException("控制台会话不属于当前账号");
            }
            if (session.cleanupInProgress) {
                throw new OciException("控制台会话已过期，请重新创建连接");
            }
            if (session.leaseId != null) {
                throw new OciException("控制台会话已在其他窗口连接");
            }
            session.leaseId = UUID.randomUUID().toString();
            session.lastTouchedAt = System.currentTimeMillis();
            return new ConsoleLease(normalizedConnectionId, session.leaseId);
        }
    }

    public Path getOrCreateExecScript(ConsoleLease lease) throws IOException {
        Objects.requireNonNull(lease, "lease");
        ConsoleSession session = activeSessions.get(lease.connectionId());
        if (session == null) {
            throw new OciException("控制台会话不存在或已过期，请重新创建连接");
        }

        synchronized (session) {
            requireActiveLease(session, lease);
            if (session.execScriptPath != null) {
                Path existing = Path.of(session.execScriptPath);
                if (Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS)) {
                    return existing;
                }
                session.execScriptPath = null;
            }

            String prepared = buildPreparedSshCommand(session.sshCommand);
            Path script = createExecScriptPath(lease.connectionId());
            boolean ready = false;
            try {
                String content = "#!/bin/bash\nexport TERM=vt100\nexec " + prepared + "\n";
                Files.writeString(script, content, StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                makeOwnerExecutable(script);
                requireActiveLease(session, lease);
                session.execScriptPath = script.toAbsolutePath().toString();
                ready = true;
                log.info("【串行控制台】已准备执行脚本: {}", lease.connectionId());
                return script;
            } finally {
                if (!ready) {
                    deleteScriptPath(script);
                }
            }
        }
    }

    public void releaseConsoleSession(ConsoleLease lease) {
        if (lease == null) {
            return;
        }
        ConsoleSession session = activeSessions.get(lease.connectionId());
        if (session == null) {
            return;
        }
        String scriptPath = null;
        synchronized (session) {
            if (!Objects.equals(session.leaseId, lease.leaseId())) {
                return;
            }
            session.leaseId = null;
            session.lastTouchedAt = System.currentTimeMillis();
            scriptPath = session.execScriptPath;
            session.execScriptPath = null;
        }
        deleteScriptPath(scriptPath);
    }

    private void deleteExecScript(ConsoleSession session) {
        String scriptPath;
        synchronized (session) {
            scriptPath = session.execScriptPath;
            session.execScriptPath = null;
        }
        deleteScriptPath(scriptPath);
    }

    private void requireActiveLease(ConsoleSession session, ConsoleLease lease) {
        if (activeSessions.get(lease.connectionId()) != session
                || !Objects.equals(session.leaseId, lease.leaseId())
                || session.cleanupInProgress) {
            throw new OciException("控制台会话已释放或过期，请重新连接");
        }
    }

    private static String safeId(String connectionId) {
        String safe = connectionId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.length() <= 96 ? safe : safe.substring(0, 96);
    }

    private Path createExecScriptPath(String connectionId) throws IOException {
        Files.createDirectories(keyDirectory);
        String prefix = EXEC_SCRIPT_PREFIX + safeId(connectionId) + "_";
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            FileAttribute<Set<PosixFilePermission>> permissions = PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------"));
            return Files.createTempFile(keyDirectory, prefix, EXEC_SCRIPT_SUFFIX, permissions);
        }
        return Files.createTempFile(keyDirectory, prefix, EXEC_SCRIPT_SUFFIX);
    }

    private static void makeOwnerExecutable(Path script) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
            return;
        }
        if (!script.toFile().setExecutable(true, true)) {
            throw new IOException("无法设置串行控制台脚本执行权限");
        }
    }

    private void deleteScriptPath(String scriptPath) {
        if (scriptPath == null) {
            return;
        }
        try {
            deleteScriptPath(Path.of(scriptPath));
        } catch (Exception e) {
            log.warn("【串行控制台】删除脚本失败: {}", e.getMessage());
        }
    }

    private void deleteScriptPath(Path script) {
        try {
            Files.deleteIfExists(script);
        } catch (Exception e) {
            log.warn("【串行控制台】删除脚本失败: {}", e.getMessage());
        }
    }

    void cleanupOrphanExecScripts() {
        if (!Files.isDirectory(keyDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        int removed = 0;
        try (DirectoryStream<Path> scripts = Files.newDirectoryStream(
                keyDirectory, EXEC_SCRIPT_PREFIX + "*" + EXEC_SCRIPT_SUFFIX)) {
            for (Path script : scripts) {
                Path fileName = script.getFileName();
                if (fileName == null
                        || !GENERATED_EXEC_SCRIPT.matcher(fileName.toString()).matches()
                        || !Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS)
                        || !hasGeneratedScriptSignature(script)) {
                    continue;
                }
                if (Files.deleteIfExists(script)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("【串行控制台】清理遗留执行脚本失败: {}", e.getMessage());
        }
        if (removed > 0) {
            log.info("【串行控制台】已清理遗留执行脚本: {} 个", removed);
        }
    }

    private static boolean hasGeneratedScriptSignature(Path script) {
        try (BufferedReader reader = Files.newBufferedReader(script, StandardCharsets.UTF_8)) {
            return "#!/bin/bash".equals(reader.readLine())
                    && "export TERM=vt100".equals(reader.readLine())
                    && Optional.ofNullable(reader.readLine()).orElse("").startsWith("exec ssh ");
        } catch (IOException e) {
            return false;
        }
    }

    private void deleteRemoteConsoleConnection(String userId, String connectionId, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) {
            throw new OciException("租户配置不存在");
        }
        try (OciClientService env = oci(ociUser, region)) {
            env.getComputeClient().deleteInstanceConsoleConnection(
                    DeleteInstanceConsoleConnectionRequest.builder()
                            .instanceConsoleConnectionId(connectionId)
                            .build());
        } catch (BmcException e) {
            if (e.getStatusCode() != 404) {
                throw new OciException("删除控制台连接失败: " + OciBmcErrorTranslator.translate(e));
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            String message = e.getMessage();
            throw new OciException("删除控制台连接失败: "
                    + (message == null || message.isBlank() ? "OCI 请求异常" : message));
        }
    }

    private void removeLocalSessionsForInstance(String tenantId, String instanceId) {
        activeSessions.forEach((connectionId, session) -> {
            if (!Objects.equals(session.tenantId, tenantId)
                    || !Objects.equals(session.instanceId, instanceId)
                    || !activeSessions.remove(connectionId, session)) {
                return;
            }
            synchronized (session) {
                session.cleanupInProgress = true;
                session.leaseId = null;
            }
            deleteExecScript(session);
            log.info("【串行控制台】清理已替换的本地会话: {}", connectionId);
        });
    }

    private void removeLocalSession(String connectionId, ConsoleSession session) {
        activeSessions.remove(connectionId, session);
        synchronized (session) {
            session.cleanupInProgress = true;
            session.leaseId = null;
        }
        deleteExecScript(session);
    }

    private void resetCleanupState(String connectionId, ConsoleSession session) {
        if (session == null) {
            return;
        }
        synchronized (session) {
            if (activeSessions.get(connectionId) == session) {
                session.cleanupInProgress = false;
            }
        }
    }

    private static String requireConnectionId(String connectionId) {
        String value = requireIdentifier(connectionId, "控制台连接");
        if (value.length() > MAX_CONNECTION_ID_LENGTH) {
            throw new OciException("控制台连接 ID 无效");
        }
        return value;
    }

    private static String requireIdentifier(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new OciException(label + "不能为空");
        }
        return value.trim();
    }

    private static void requireOwner(String ownerAccount) {
        if (ownerAccount == null || ownerAccount.isBlank()) {
            throw new OciException("登录账号无效，请重新登录");
        }
    }

    private static String normalizeRegion(String region) {
        return region == null || region.isBlank() ? null : region.trim();
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("【串行控制台】清理旧版临时用户被中断");
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
            Path scriptPath = keyDirectory.resolve("console_" + user + ".sh");
            Files.deleteIfExists(scriptPath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("【串行控制台】清理旧版用户被中断: {}", user);
        } catch (Exception e) {
            log.warn("【串行控制台】清理旧版用户失败: {} - {}", user, e.getMessage());
        }
    }

    @Scheduled(fixedRate = 300_000)
    public void periodicCleanup() {
        if (shuttingDown.get()) {
            return;
        }
        long cutoff = System.currentTimeMillis() - SESSION_IDLE_MILLIS;
        for (ConsoleSession session : markExpiredSessions(cutoff)) {
            deleteExecScript(session);
            try {
                cleanupExecutor.submit(() -> cleanupExpiredSession(session));
            } catch (RejectedExecutionException e) {
                resetCleanupState(session.consoleConnectionId, session);
                log.debug("串行控制台清理任务已拒绝: {}", e.getMessage());
            }
        }
    }

    List<ConsoleSession> markExpiredSessions(long cutoff) {
        List<ConsoleSession> expired = new ArrayList<>();
        activeSessions.forEach((connectionId, session) -> {
            synchronized (session) {
                long lastTouched = session.lastTouchedAt > 0 ? session.lastTouchedAt : session.createdAt;
                if (activeSessions.get(connectionId) == session
                        && session.leaseId == null
                        && !session.cleanupInProgress
                        && lastTouched < cutoff) {
                    session.cleanupInProgress = true;
                    expired.add(session);
                }
            }
        });
        return expired;
    }

    private void cleanupExpiredSession(ConsoleSession session) {
        try {
            deleteRemoteConsoleConnection(
                    session.tenantId, session.consoleConnectionId, session.region);
            removeLocalSession(session.consoleConnectionId, session);
            log.info("【串行控制台】已清理过期会话: {}", session.consoleConnectionId);
        } catch (RuntimeException e) {
            resetCleanupState(session.consoleConnectionId, session);
            log.warn("【串行控制台】清理过期 OCI 连接失败，稍后重试: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        shuttingDown.set(true);
        cleanupExecutor.shutdownNow();
        activeSessions.forEach((connectionId, session) -> {
            if (activeSessions.remove(connectionId, session)) {
                deleteExecScript(session);
            }
        });
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
