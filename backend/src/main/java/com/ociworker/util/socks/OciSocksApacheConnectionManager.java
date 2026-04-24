package com.ociworker.util.socks;

import com.ociworker.model.dto.OciProxyConstants;
import com.ociworker.model.dto.OciProxySnapshot;
import org.apache.http.HttpHost;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Socket;
import java.util.Collections;

/**
 * 为 OCI Jersey3 + Apache HttpClient 构建经 SOCKS5/5h 的 {@link PoolingHttpClientConnectionManager}。
 */
public final class OciSocksApacheConnectionManager {

    private OciSocksApacheConnectionManager() {
    }

    public static PoolingHttpClientConnectionManager create(OciProxySnapshot snap) {
        if (!snap.usesSocksForOci()) {
            throw new IllegalArgumentException("not a SOCKS snapshot");
        }
        String proxyHost = snap.host();
        int proxyPort = snap.port();
        String user = snap.proxyUser() == null ? "" : snap.proxyUser();
        String pass = snap.proxyPass() == null ? "" : snap.proxyPass();
        boolean remoteDns = OciProxyConstants.TYPE_SOCKS5H.equals(snap.type());

        SocksParams p = new SocksParams(proxyHost, proxyPort, user, pass, remoteDns);

        Registry<ConnectionSocketFactory> reg = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", new HttpOverSocksFactory(p))
                .register("https", new HttpsOverSocksFactory(p))
                .build();

        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager(reg);
        cm.setMaxTotal(100);
        cm.setDefaultMaxPerRoute(50);
        cm.setDefaultSocketConfig(SocketConfig.custom()
                .setSoTimeout(120_000)
                .setTcpNoDelay(true)
                .build());
        return cm;
    }

    private record SocksParams(String proxyHost, int proxyPort, String user, String pass, boolean remoteDns) {
    }

    private static final class HttpOverSocksFactory implements ConnectionSocketFactory {
        private final SocksParams p;

        HttpOverSocksFactory(SocksParams p) {
            this.p = p;
        }

        @Override
        public Socket createSocket(HttpContext context) {
            return new Socket();
        }

        @Override
        public Socket connectSocket(
                int connectTimeout,
                Socket sock,
                HttpHost remoteHost,
                java.net.InetSocketAddress remoteAddress,
                java.net.InetSocketAddress localAddress,
                HttpContext context) throws IOException {
            return Socks5Tunnel.connect(
                    p.proxyHost,
                    p.proxyPort,
                    p.user,
                    p.pass,
                    remoteHost.getHostName(),
                    remoteHost.getPort(),
                    p.remoteDns,
                    connectTimeout);
        }
    }

    private static final class HttpsOverSocksFactory implements ConnectionSocketFactory {
        private final SocksParams p;

        HttpsOverSocksFactory(SocksParams p) {
            this.p = p;
        }

        @Override
        public Socket createSocket(HttpContext context) {
            return new Socket();
        }

        @Override
        public Socket connectSocket(
                int connectTimeout,
                Socket sock,
                HttpHost remoteHost,
                java.net.InetSocketAddress remoteAddress,
                java.net.InetSocketAddress localAddress,
                HttpContext context) throws IOException {
            Socket tunnel = Socks5Tunnel.connect(
                    p.proxyHost,
                    p.proxyPort,
                    p.user,
                    p.pass,
                    remoteHost.getHostName(),
                    remoteHost.getPort(),
                    p.remoteDns,
                    connectTimeout);
            tunnel.setSoTimeout(120_000);
            SSLContext ctx = SSLContext.getDefault();
            SSLSocketFactory sf = ctx.getSocketFactory();
            SSLSocket ssl = (SSLSocket) sf.createSocket(tunnel, remoteHost.getHostName(), remoteHost.getPort(), true);
            SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            try {
                params.setServerNames(Collections.singletonList(new SNIHostName(remoteHost.getHostName())));
            } catch (IllegalArgumentException ignored) {
            }
            ssl.setSSLParameters(params);
            ssl.setSoTimeout(120_000);
            ssl.startHandshake();
            return ssl;
        }
    }
}
