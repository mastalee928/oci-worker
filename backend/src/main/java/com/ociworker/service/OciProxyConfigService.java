package com.ociworker.service;

import com.ociworker.enums.SysCfgEnum;
import com.ociworker.exception.OciException;
import com.ociworker.model.dto.OciProxyConstants;
import com.ociworker.model.dto.OciProxySnapshot;
import com.oracle.bmc.http.client.ProxyConfiguration;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 系统级 Outbound 代理：OCI SDK、HttpClient 出站、Telegram/Cloudflare/GitHub 等。
 * 不含 WebSSH 本地上游代理（Java HttpClient 显式直连）。
 */
@Slf4j
@Service
public class OciProxyConfigService {

    public static final String TYPE_HTTP = OciProxyConstants.TYPE_HTTP;
    public static final String TYPE_SOCKS5 = OciProxyConstants.TYPE_SOCKS5;
    public static final String TYPE_SOCKS5H = OciProxyConstants.TYPE_SOCKS5H;

    private static OciProxyConfigService INSTANCE;

    @Resource
    private NotificationService notificationService;

    private volatile OciProxySnapshot cache = OciProxySnapshot.disabled();

    @PostConstruct
    public void postConstruct() {
        INSTANCE = this;
        reload();
    }

    public static OciProxyConfigService instance() {
        return INSTANCE;
    }

    public void reload() {
        try {
            cache = OciProxySnapshot.fromKv((SysCfgEnum e) -> notificationService.getKvValue(e));
        } catch (Exception e) {
            log.warn("OciProxy reload: {}", e.getMessage());
        }
    }

    public OciProxySnapshot snapshot() {
        return cache;
    }

    public Optional<ProxyConfiguration> getOciProxyConfiguration() {
        return cache.toOciProxyConfiguration();
    }

    public HttpClient newOutboundHttpClient() {
        return newOutboundHttpClientBuilder().build();
    }

    public HttpClient.Builder newOutboundHttpClientBuilder() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10));
        if (!cache.enabled() || !cache.canConnect()) {
            return b;
        }
        b.proxy(singleProxy(cache.toJavaNetProxy()));
        b.authenticator(authenticatorFor(cache));
        return b;
    }

    /**
     * 使用临时参数测试连通性（不必先落库），失败抛 {@link OciException}。
     */
    public String testWithParams(OciProxySnapshot test) {
        if (!test.canConnect()) {
            throw new OciException("请填写有效的主机、端口，或「完整 URL」");
        }
        try {
            HttpClient client = newHttpClientForSnapshot(test);
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/zen"))
                    .header("User-Agent", "oci-worker/1.0")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            long t0 = System.currentTimeMillis();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 400) {
                throw new OciException("HTTP 状态: " + resp.statusCode());
            }
            return "ok，" + (System.currentTimeMillis() - t0) + " ms";
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("测试失败: " + e.getMessage());
        }
    }

    public static HttpClient newHttpClientForSnapshot(OciProxySnapshot t) {
        return newHttpClientBuilderForSnapshot(t).build();
    }

    public static HttpClient.Builder newHttpClientBuilderForSnapshot(OciProxySnapshot t) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10));
        if (!t.canConnect()) {
            return b;
        }
        b.proxy(singleProxy(t.toJavaNetProxy()));
        b.authenticator(authenticatorFor(t));
        return b;
    }

    private static Authenticator authenticatorFor(OciProxySnapshot s) {
        final String user = s.proxyUser() == null ? "" : s.proxyUser();
        final char[] pass = s.proxyPass() == null ? new char[0] : s.proxyPass().toCharArray();
        // 用户名空但密码非空时也必须提供 Authenticator：否则 JDK SOCKS 会退回系统用户名 + 空密码，
        // 与 Socks5Tunnel（空用户名 + 面板密码）不一致，表现为「检查更新能过、OCI RFC1929 失败」。
        if (user.isEmpty() && pass.length == 0) {
            return new Authenticator() {
            };
        }
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() == Authenticator.RequestorType.PROXY) {
                    return new PasswordAuthentication(user, pass);
                }
                return null;
            }
        };
    }

    public void persistAndReload(OciProxySnapshot s) {
        Map<String, String> m = s.toRawKvForPersistence();
        notificationService.saveKvValue(SysCfgEnum.OCI_PROXY_ENABLED, m.get("enabled"));
        notificationService.saveKvValue(SysCfgEnum.OCI_PROXY_TYPE, m.get("type"));
        notificationService.saveKvValue(SysCfgEnum.OCI_PROXY_HOST, m.get("host"));
        notificationService.saveKvValue(SysCfgEnum.OCI_PROXY_PORT, m.get("port"));
        notificationService.saveKvValue(SysCfgEnum.OCI_PROXY_USER, m.get("user"));
        notificationService.saveKvValue(SysCfgEnum.OCI_PROXY_PASS, m.get("pass"));
        notificationService.saveKvValue(SysCfgEnum.OCI_PROXY_FULL_URL, m.get("fullUrl"));
        reload();
    }

    /** Java 11+ 兼容（{@link ProxySelector#of(Proxy)} 需 Java 16+） */
    private static ProxySelector singleProxy(Proxy proxy) {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(proxy);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        };
    }
}
