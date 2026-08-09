package com.ociworker.bastion;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/** JSch support dedicated to the OCI Bastion two-hop channel. */
@Slf4j
final class BastionJschSupport {

    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int MAX_TOFU_KEYS = 2_048;
    /** OCI's Bastion troubleshooting guide still requires ssh-rsa fallback for some keys. */
    private static final String SERVER_HOST_KEY_ALGORITHMS =
            "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,"
                    + "rsa-sha2-512,rsa-sha2-256,ssh-rsa";
    private static final String PUBKEY_ACCEPTED_ALGORITHMS = SERVER_HOST_KEY_ALGORITHMS;
    private static final Map<String, byte[]> TARGET_HOST_KEYS = new ConcurrentHashMap<>();

    private BastionJschSupport() {
    }

    static BastionSshConnection open(BastionConnectionSpec spec) throws Exception {
        if (spec == null) {
            throw new JSchException("Bastion connection specification is missing");
        }
        JSch bastionJsch = new JSch();
        Session bastion = null;
        Session target = null;
        int forwardedPort = -1;
        try {
            addIdentity(bastionJsch, "ociworker-bastion", spec.bastionPrivateKey(), null);
            bastion = bastionJsch.getSession(spec.bastionUser(), spec.bastionHost(), spec.bastionPort());
            configureBastion(bastion, spec);
            bastion.connect(CONNECT_TIMEOUT_MILLIS);

            forwardedPort = bastion.setPortForwardingL(0, spec.targetHost(), spec.targetPort());

            JSch targetJsch = new JSch();
            if (spec.targetLoginType() == 1) {
                addIdentity(targetJsch, "ociworker-target", spec.targetPrivateKey(), spec.targetPassphrase());
            } else if (spec.targetLoginType() != 0) {
                throw new JSchException("Unsupported Bastion target login type");
            }
            target = targetJsch.getSession(spec.targetUsername(), "127.0.0.1", forwardedPort);
            if (spec.targetLoginType() == 0) {
                target.setPassword(spec.targetPassword() == null ? "" : spec.targetPassword());
            }
            configureTarget(target, targetHostKeyCacheKey(spec));
            target.connect(CONNECT_TIMEOUT_MILLIS);
            return new BastionSshConnection(bastion, target, forwardedPort);
        } catch (Exception e) {
            closeQuietly(target);
            closeQuietly(bastion, forwardedPort);
            throw e;
        }
    }

    private static void addIdentity(JSch jsch, String name, String privateKey, String passphrase)
            throws JSchException {
        if (privateKey == null || privateKey.isBlank()) {
            throw new JSchException("SSH private key is empty");
        }
        byte[] keyBytes = privateKey.getBytes(StandardCharsets.UTF_8);
        byte[] passBytes = passphrase == null || passphrase.isEmpty()
                ? null : passphrase.getBytes(StandardCharsets.UTF_8);
        try {
            jsch.addIdentity(name, keyBytes, null, passBytes);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
            if (passBytes != null) {
                Arrays.fill(passBytes, (byte) 0);
            }
        }
    }

    private static void configureBastion(Session session, BastionConnectionSpec spec) throws JSchException {
        String hostKeyInfo = spec.bastionHostKeyInfo();
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "yes");
        config.put("HashKnownHosts", "no");
        config.put("server_host_key", SERVER_HOST_KEY_ALGORITHMS);
        config.put("PubkeyAcceptedAlgorithms", PUBKEY_ACCEPTED_ALGORITHMS);
        session.setConfig(config);
        if (hostKeyInfo == null || hostKeyInfo.isBlank()) {
            // OCI often omits bastionPublicHostKeyInfo. Pin the regional bastion
            // endpoint on first use and fail on any later key change.
            session.setHostKeyRepository(new TofuHostKeyRepository(bastionHostKeyCacheKey(spec)));
        } else {
            session.setHostKeyRepository(new PinnedHostKeyRepository(hostKeyInfo));
        }
        session.setServerAliveInterval(15_000);
        session.setServerAliveCountMax(3);
    }

    static String bastionHostKeyCacheKey(BastionConnectionSpec spec) {
        return "bastion|" + spec.bastionHost() + ':' + spec.bastionPort();
    }

    /**
     * OCI exposes the Bastion host key, not the target VM host key. The target
     * hop is over a loopback forwarding socket, so use bounded process-local
     * TOFU for the target and fail on a key change instead of disabling host-key
     * checking globally.
     */
    private static void configureTarget(Session session, String cacheKey)
            throws JSchException {
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "yes");
        config.put("HashKnownHosts", "no");
        config.put("server_host_key", SERVER_HOST_KEY_ALGORITHMS);
        config.put("PubkeyAcceptedAlgorithms", PUBKEY_ACCEPTED_ALGORITHMS);
        session.setConfig(config);
        session.setHostKeyRepository(new TofuHostKeyRepository(cacheKey));
        session.setServerAliveInterval(15_000);
        session.setServerAliveCountMax(3);
    }

    static String targetHostKeyCacheKey(BastionConnectionSpec spec) {
        return spec.tenantId() + '|' + spec.region() + '|' + spec.targetDisplayName()
                + '|' + spec.targetHost() + ':' + spec.targetPort();
    }

    record ShellChannel(ChannelShell shell, InputStream stdout) {
    }

    static ShellChannel openShell(Session session, int cols, int rows) throws Exception {
        if (session == null || !session.isConnected()) {
            throw new JSchException("Bastion target SSH session is not connected");
        }
        ChannelShell shell = (ChannelShell) session.openChannel("shell");
        try {
            shell.setPtyType("xterm", cols, rows, 0, 0);
            shell.setPty(true);
            InputStream stdout = shell.getInputStream();
            shell.connect(CONNECT_TIMEOUT_MILLIS);
            return new ShellChannel(shell, stdout);
        } catch (Exception e) {
            closeQuietly(null, shell);
            throw e;
        }
    }

    static OutputStream shellInput(ChannelShell shell) throws IOException {
        return shell.getOutputStream();
    }

    static void resizeShell(Channel channel, int cols, int rows) throws JSchException {
        if (channel instanceof ChannelShell shell && shell.isConnected()) {
            shell.setPtySize(cols, rows, 0, 0);
        }
    }

    static void closeQuietly(BastionSshConnection connection) {
        if (connection == null) {
            return;
        }
        closeQuietly(null, connection.targetShell());
        closeQuietly(connection.targetSession());
        closeQuietly(connection.bastionSession(), connection.forwardedPort());
    }

    static void closeQuietly(Session session, Channel... channels) {
        closeQuietly(session, -1, channels);
    }

    static void closeQuietly(Session session, int forwardedPort) {
        closeQuietly(session, forwardedPort, (Channel[]) null);
    }

    private static void closeQuietly(Session session, int forwardedPort, Channel... channels) {
        if (channels != null) {
            for (Channel channel : channels) {
                if (channel != null && channel.isConnected()) {
                    try {
                        channel.disconnect();
                    } catch (Exception ignored) {
                        log.trace("Bastion SSH channel cleanup failed", ignored);
                    }
                }
            }
        }
        if (session != null) {
            if (forwardedPort > 0 && session.isConnected()) {
                try {
                    session.delPortForwardingL(forwardedPort);
                } catch (Exception ignored) {
                    log.trace("Bastion SSH forwarding cleanup failed", ignored);
                }
            }
            if (session.isConnected()) {
                try {
                    session.disconnect();
                } catch (Exception ignored) {
                    log.trace("Bastion SSH session cleanup failed", ignored);
                }
            }
        }
    }

    static void closeQuietly(Session session) {
        closeQuietly(session, -1);
    }

    private static final class PinnedHostKeyRepository implements HostKeyRepository {
        private final byte[] expectedKey;
        private final String expectedSha256;
        private final String expectedMd5;

        private PinnedHostKeyRepository(String rawInfo) throws JSchException {
            HostKeyExpectation expectation = HostKeyExpectation.parse(rawInfo);
            this.expectedKey = expectation.key();
            this.expectedSha256 = expectation.sha256();
            this.expectedMd5 = expectation.md5();
        }

        @Override
        public int check(String host, byte[] key) {
            if (expectedKey != null && MessageDigest.isEqual(expectedKey, key)) {
                return HostKeyRepository.OK;
            }
            if (expectedSha256 != null && expectedSha256.equals(fingerprint("SHA256", key))) {
                return HostKeyRepository.OK;
            }
            if (expectedMd5 != null && expectedMd5.equals(fingerprint("MD5", key))) {
                return HostKeyRepository.OK;
            }
            return HostKeyRepository.CHANGED;
        }

        @Override
        public void add(HostKey hostkey, com.jcraft.jsch.UserInfo ui) {
            // Pinned OCI metadata is immutable for this connection.
        }

        @Override
        public void remove(String host, String type) {
        }

        @Override
        public void remove(String host, String type, byte[] key) {
        }

        @Override
        public String getKnownHostsRepositoryID() {
            return "oci-bastion-pinned";
        }

        @Override
        public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            return new HostKey[0];
        }
    }

    private static final class TofuHostKeyRepository implements HostKeyRepository {
        private final String cacheKey;

        private TofuHostKeyRepository(String cacheKey) {
            this.cacheKey = cacheKey;
        }

        @Override
        public int check(String host, byte[] key) {
            byte[] known = TARGET_HOST_KEYS.get(cacheKey);
            if (known == null) {
                if (TARGET_HOST_KEYS.size() >= MAX_TOFU_KEYS) {
                    return HostKeyRepository.CHANGED;
                }
                byte[] copy = Arrays.copyOf(key, key.length);
                byte[] previous = TARGET_HOST_KEYS.putIfAbsent(cacheKey, copy);
                known = previous == null ? copy : previous;
            }
            return MessageDigest.isEqual(known, key)
                    ? HostKeyRepository.OK : HostKeyRepository.CHANGED;
        }

        @Override
        public void add(HostKey hostkey, com.jcraft.jsch.UserInfo ui) {
        }

        @Override
        public void remove(String host, String type) {
        }

        @Override
        public void remove(String host, String type, byte[] key) {
        }

        @Override
        public String getKnownHostsRepositoryID() {
            return "oci-bastion-target-tofu";
        }

        @Override
        public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            return new HostKey[0];
        }
    }

    private record HostKeyExpectation(byte[] key, String sha256, String md5) {
        private static HostKeyExpectation parse(String rawInfo) throws JSchException {
            String value = rawInfo == null ? "" : rawInfo.trim();
            if (value.isEmpty()) {
                throw new JSchException("OCI Bastion host key is empty");
            }
            String[] tokens = value.replace('\r', '\n').split("\\s+");
            byte[] key = null;
            String sha256 = null;
            String md5 = null;
            for (String token : tokens) {
                if (token.isBlank()) continue;
                String normalized = token.trim();
                if (normalized.regionMatches(true, 0, "SHA256:", 0, 7)) {
                    sha256 = normalizeSha256(normalized.substring(7));
                    continue;
                }
                if (normalized.regionMatches(true, 0, "MD5:", 0, 4)) {
                    md5 = normalizeMd5(normalized.substring(4));
                    continue;
                }
                if (normalized.matches("[A-Za-z0-9+/]+={0,2}")) {
                    try {
                        byte[] candidate = Base64.getDecoder().decode(normalized);
                        if (candidate.length >= 16) key = candidate;
                    } catch (IllegalArgumentException ignored) {
                        // The value may be a fingerprint with no explicit prefix.
                    }
                }
            }
            if (key == null && sha256 == null && md5 == null) {
                // OCI has returned bare SHA-256 values in older regions.
                String bare = value.replace(" ", "");
                if (bare.matches("[A-Za-z0-9+/]+={0,2}")) {
                    sha256 = normalizeSha256(bare);
                }
            }
            if (key == null && sha256 == null && md5 == null) {
                throw new JSchException("OCI Bastion host key format is unsupported");
            }
            return new HostKeyExpectation(key, sha256, md5);
        }
    }

    private static String fingerprint(String algorithm, byte[] key) {
        try {
            MessageDigest digest = MessageDigest.getInstance(
                    "SHA256".equals(algorithm) ? "SHA-256" : "MD5");
            byte[] value = digest.digest(key);
            if ("SHA256".equals(algorithm)) {
                return normalizeSha256(Base64.getEncoder().withoutPadding().encodeToString(value));
            }
            StringBuilder result = new StringBuilder(value.length * 3 - 1);
            for (int i = 0; i < value.length; i++) {
                if (i > 0) result.append(':');
                result.append(String.format(Locale.ROOT, "%02x", value[i] & 0xff));
            }
            return normalizeMd5(result.toString());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM fingerprint algorithm is unavailable", e);
        }
    }

    private static String normalizeSha256(String value) {
        return value == null ? "" : value.trim().replace("=", "");
    }

    private static String normalizeMd5(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    static final class BastionSshConnection {
        private final Session bastionSession;
        private final Session targetSession;
        private final int forwardedPort;
        private volatile ChannelShell targetShell;

        BastionSshConnection(Session bastionSession, Session targetSession, int forwardedPort) {
            this.bastionSession = bastionSession;
            this.targetSession = targetSession;
            this.forwardedPort = forwardedPort;
        }

        Session bastionSession() {
            return bastionSession;
        }

        Session targetSession() {
            return targetSession;
        }

        int forwardedPort() {
            return forwardedPort;
        }

        ChannelShell targetShell() {
            return targetShell;
        }

        void targetShell(ChannelShell shell) {
            this.targetShell = shell;
        }
    }
}
