package com.ociworker.util.socks;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;

/**
 * 经 SOCKS5 连到目标主机:端口，供 Apache HttpClient 套接字工厂使用。
 * <p>
 * 实现改为使用与 {@link java.net.http.HttpClient}「测试代理」相同的 JDK 内置 SOCKS 栈
 * （{@link Socket#Socket(Proxy)} + {@link Authenticator}），避免自写 RFC1929 与 JDK 字节级差异导致
 * 代理测试通过而 OCI（Jersey/Apache）仍认证失败。
 */
public final class Socks5Tunnel {

    /** 与 {@link java.net.http.HttpClient} 经 SOCKS 建连串行化，避免并发改 {@link Authenticator#getDefault()}。 */
    private static final Object JDK_SOCKS_CONNECT_LOCK = new Object();

    private Socks5Tunnel() {
    }

    /**
     * 建立到 targetHost:targetPort 的 TCP 连接（经 SOCKS5 代理）。
     *
     * @param remoteDns true 时目标以未解析域名经代理（socks5h）；false 时在本机解析为 IP 再经代理（socks5）
     */
    public static Socket connect(
            String proxyHost,
            int proxyPort,
            String proxyUser,
            String proxyPass,
            String targetHost,
            int targetPort,
            boolean remoteDns,
            int connectTimeoutMs) throws IOException {
        String u = normalizeSocksCredential(proxyUser);
        String p = normalizeSocksCredential(proxyPass);
        boolean hasCreds = !u.isEmpty() || !p.isEmpty();
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxyHost, proxyPort));
        SocketAddress remote = remoteDns
                ? InetSocketAddress.createUnresolved(targetHost, targetPort)
                : new InetSocketAddress(InetAddress.getByName(targetHost), targetPort);

        synchronized (JDK_SOCKS_CONNECT_LOCK) {
            Authenticator old = Authenticator.getDefault();
            try {
                if (hasCreds) {
                    // 在整段 connect 已用全局锁串行化的前提下，对期间任意 Authenticator 询问都返回面板凭据。
                    // 部分 JDK/内部实现里 SOCKS 握手未必以 RequestorType.PROXY 触发，再过滤会退回 null → JDK 走错误回退 → "SOCKS : authentication failed"。
                    Authenticator.setDefault(new Authenticator() {
                        @Override
                        protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(u, p.toCharArray());
                        }
                    });
                } else {
                    Authenticator.setDefault(new Authenticator() {
                    });
                }
                final Socket s = new Socket(proxy);
                try {
                    s.setTcpNoDelay(true);
                    if (connectTimeoutMs > 0) {
                        s.connect(remote, connectTimeoutMs);
                    } else {
                        s.connect(remote);
                    }
                    s.setSoTimeout(connectTimeoutMs > 0 ? Math.max(connectTimeoutMs, 30_000) : 30_000);
                    return s;
                } catch (IOException | RuntimeException e) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                    throw e;
                }
            } finally {
                Authenticator.setDefault(old);
            }
        }
    }

    /**
     * 与 {@link com.ociworker.service.OciProxyConfigService#authenticatorFor} 对 HttpClient 使用的凭据规则一致，避免一边 strip 一边不 strip。
     * 去掉首尾空白、BOM、行尾 CR/LF（常见于从 KV/表单粘贴的密码）。
     */
    public static String normalizeSocksCredential(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        if (t.startsWith("\uFEFF")) {
            t = t.substring(1).strip();
        }
        while (!t.isEmpty() && (t.endsWith("\r") || t.endsWith("\n"))) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
