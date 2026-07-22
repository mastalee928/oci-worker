package com.ociworker.webssh;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.ProxySOCKS5;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;

@Slf4j
final class WebSshJschSupport {

    private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(45);

    private WebSshJschSupport() {
    }

    static Session openSession(WebSshConnectInfo info) throws JSchException {
        JSch jsch = new JSch();
        if (info.getLoginType() != 0) {
            String key = info.getPrivateKey();
            if (key == null || key.isBlank()) {
                throw new JSchException("private key is empty");
            }
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            String pass = info.getPassphrase();
            byte[] passBytes = pass != null && !pass.isBlank() ? pass.getBytes(StandardCharsets.UTF_8) : null;
            try {
                jsch.addIdentity("key", keyBytes, null, passBytes);
            } finally {
                Arrays.fill(keyBytes, (byte) 0);
                if (passBytes != null) {
                    Arrays.fill(passBytes, (byte) 0);
                }
            }
        }

        String host = info.getHostname();
        int port = info.getPort() > 0 ? info.getPort() : 22;
        Session session = jsch.getSession(info.getUsername(), host, port);

        if (info.getLoginType() == 0) {
            session.setPassword(info.getPassword() != null ? info.getPassword() : "");
        }

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);

        String proxyHost = info.getProxyHost();
        if (proxyHost != null && !proxyHost.isBlank()) {
            int proxyPort = info.getProxyPort() > 0 ? info.getProxyPort() : 1080;
            ProxySOCKS5 proxy = new ProxySOCKS5(proxyHost, proxyPort);
            String pu = info.getProxyUser();
            if (pu != null && !pu.isBlank()) {
                proxy.setUserPasswd(pu, info.getProxyPass() != null ? info.getProxyPass() : "");
            }
            session.setProxy(proxy);
        }

        try {
            session.connect(10_000);
            return session;
        } catch (JSchException | RuntimeException e) {
            closeQuietly(session);
            throw e;
        }
    }

    static ChannelShell openShell(Session session, int cols, int rows) throws JSchException {
        ChannelShell shell = (ChannelShell) session.openChannel("shell");
        try {
            shell.setPtyType("xterm", cols, rows, 0, 0);
            shell.setPty(true);
            shell.connect(15_000);
            return shell;
        } catch (JSchException | RuntimeException e) {
            closeQuietly(null, shell);
            throw e;
        }
    }

    static void resizeShell(Channel channel, int cols, int rows) throws JSchException {
        if (channel instanceof ChannelShell shell) {
            shell.setPtySize(cols, rows, 0, 0);
        }
    }

    static ChannelSftp openSftp(Session session) throws JSchException {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        try {
            sftp.connect(15_000);
            return sftp;
        } catch (JSchException | RuntimeException e) {
            closeQuietly(null, sftp);
            throw e;
        }
    }

    static String execCombined(Session session, String command) throws Exception {
        ChannelExec exec = (ChannelExec) session.openChannel("exec");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            exec.setCommand(command);
            exec.setInputStream(null);
            exec.setOutputStream(stdout);
            exec.setErrStream(stderr);
            exec.connect(30_000);
            long deadline = System.nanoTime() + EXEC_TIMEOUT.toNanos();
            while (!exec.isClosed()) {
                if (System.nanoTime() >= deadline) {
                    throw new IOException("SSH command timed out");
                }
                Thread.sleep(20);
            }
            byte[] out = stdout.toByteArray();
            byte[] err = stderr.toByteArray();
            if (out.length == 0 && err.length > 0) {
                return new String(err, StandardCharsets.UTF_8);
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            closeQuietly(null, exec);
        }
    }

    static void closeQuietly(Session session, Channel... channels) {
        if (channels != null) {
            for (Channel ch : channels) {
                if (ch != null && ch.isConnected()) {
                    try {
                        ch.disconnect();
                    } catch (Exception ignored) {
                        log.trace("SSH channel cleanup failed", ignored);
                    }
                }
            }
        }
        if (session != null && session.isConnected()) {
            try {
                session.disconnect();
            } catch (Exception ignored) {
                log.trace("SSH session cleanup failed", ignored);
            }
        }
    }

    static OutputStream shellInput(ChannelShell shell) throws Exception {
        return shell.getOutputStream();
    }

    static InputStream shellOutput(ChannelShell shell) throws Exception {
        return shell.getInputStream();
    }
}
