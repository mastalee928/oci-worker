package com.ociworker.model.dto;

import cn.hutool.core.util.StrUtil;
import com.ociworker.enums.SysCfgEnum;
import com.ociworker.util.socks.Socks5Tunnel;
import com.oracle.bmc.http.client.ProxyConfiguration;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 系统 OCI/出站 API 代理配置（内存/传输快照，持久化在 oci_kv）。
 */
public record OciProxySnapshot(
        boolean enabled,
        String type,
        String host,
        int port,
        String proxyUser,
        String proxyPass,
        String fullUrl
) {

    public static OciProxySnapshot disabled() {
        return new OciProxySnapshot(
                false,
                OciProxyConstants.TYPE_HTTP,
                "", 0, "", "", ""
        );
    }

    public static OciProxySnapshot fromKv(Function<SysCfgEnum, String> getKv) {
        String en = s(getKv, SysCfgEnum.OCI_PROXY_ENABLED);
        boolean on = "true".equalsIgnoreCase(en) || "1".equals(en) || "yes".equalsIgnoreCase(en);
        String type = s(getKv, SysCfgEnum.OCI_PROXY_TYPE);
        if (StrUtil.isBlank(type)) {
            type = OciProxyConstants.TYPE_HTTP;
        }
        type = type.trim().toLowerCase();
        if (!OciProxyConstants.TYPE_SOCKS5.equals(type)
                && !OciProxyConstants.TYPE_SOCKS5H.equals(type)
                && !OciProxyConstants.TYPE_HTTP.equals(type)) {
            type = OciProxyConstants.TYPE_HTTP;
        }
        String host = s(getKv, SysCfgEnum.OCI_PROXY_HOST);
        int port = 0;
        String ps = s(getKv, SysCfgEnum.OCI_PROXY_PORT);
        if (StrUtil.isNotBlank(ps)) {
            try {
                port = Integer.parseInt(ps.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        String user = s(getKv, SysCfgEnum.OCI_PROXY_USER);
        String pass = s(getKv, SysCfgEnum.OCI_PROXY_PASS);
        String full = s(getKv, SysCfgEnum.OCI_PROXY_FULL_URL);

        OciProxySnapshot base = new OciProxySnapshot(on, type, host, port, nvl(user), nvl(pass), nvl(full));
        return base.mergedWithFullUrl();
    }

    private static String s(Function<SysCfgEnum, String> getKv, SysCfgEnum e) {
        if (getKv == null) return "";
        return nvl(getKv.apply(e));
    }

    private static String nvl(String v) {
        return v == null ? "" : v;
    }

    /**
     * {@link java.net.URI#getUserInfo()} 为原始百分号编码；SOCKS/HTTP 认证需解码。
     * 将 {@code +} 先转义，避免 {@link URLDecoder} 把密码中的 {@code +} 误当成空格。
     */
    private static String decodeUriUserInfoPart(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    public static OciProxySnapshot fromForm(boolean enabled, String type, String host, int port,
                                            String user, String pass, String fullUrl) {
        String t = StrUtil.isBlank(type) ? OciProxyConstants.TYPE_HTTP : type.trim().toLowerCase();
        if (!OciProxyConstants.TYPE_SOCKS5.equals(t)
                && !OciProxyConstants.TYPE_SOCKS5H.equals(t)
                && !OciProxyConstants.TYPE_HTTP.equals(t)) {
            t = OciProxyConstants.TYPE_HTTP;
        }
        OciProxySnapshot snap = new OciProxySnapshot(
                enabled, t, nvl(host), port, nvl(user), nvl(pass), nvl(fullUrl)
        );
        return snap.mergedWithFullUrl();
    }

    private OciProxySnapshot mergedWithFullUrl() {
        if (StrUtil.isBlank(fullUrl)) {
            return this;
        }
        String u = fullUrl.trim();
        try {
            java.net.URI uri = java.net.URI.create(u);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return this;
            }
            String t = mapSchemeToType(scheme);
            String h = uri.getHost();
            if (h == null) {
                return this;
            }
            int p = uri.getPort();
            if (p < 0) {
                p = defaultPortForType(t, scheme);
            }
            String userInfo = uri.getUserInfo();
            String nu = nvl(this.proxyUser);
            String np = nvl(this.proxyPass);
            if (StrUtil.isNotBlank(userInfo)) {
                if (userInfo.contains(":")) {
                    int idx = userInfo.indexOf(':');
                    nu = decodeUriUserInfoPart(userInfo.substring(0, idx));
                    np = decodeUriUserInfoPart(userInfo.substring(idx + 1));
                } else {
                    nu = decodeUriUserInfoPart(userInfo);
                }
            }
            return new OciProxySnapshot(this.enabled, t, h, p, nu, np, u);
        } catch (Exception e) {
            return this;
        }
    }

    private static int defaultPortForType(String t, String scheme) {
        if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
            return 8080;
        }
        if (OciProxyConstants.TYPE_HTTP.equals(t)) {
            return 8080;
        }
        return 1080;
    }

    private static String mapSchemeToType(String scheme) {
        String s = scheme.toLowerCase();
        if ("socks5h".equals(s)) {
            return OciProxyConstants.TYPE_SOCKS5H;
        }
        if ("socks5".equals(s) || "socks".equals(s)) {
            return OciProxyConstants.TYPE_SOCKS5;
        }
        return OciProxyConstants.TYPE_HTTP;
    }

    public boolean canConnect() {
        if (StrUtil.isNotBlank(fullUrl) && (host == null || host.isBlank() || port <= 0)) {
            try {
                java.net.URI u = java.net.URI.create(fullUrl.trim());
                if (u.getHost() == null) {
                    return false;
                }
                int p = u.getPort();
                if (p < 0) {
                    p = defaultPortForType(
                            mapSchemeToType(nvl(u.getScheme())),
                            nvl(u.getScheme())
                    );
                }
                return p > 0 && p <= 65535;
            } catch (Exception e) {
                return false;
            }
        }
        return StrUtil.isNotBlank(host) && port > 0 && port <= 65535;
    }

    public InetSocketAddress toInetSocketAddress() {
        return new InetSocketAddress(host, port);
    }

    public Proxy toJavaNetProxy() {
        InetSocketAddress addr = toInetSocketAddress();
        Proxy.Type pt = OciProxyConstants.TYPE_HTTP.equals(type)
                ? Proxy.Type.HTTP
                : Proxy.Type.SOCKS;
        return new Proxy(pt, addr);
    }

    /** OCI SDK 经 Apache SOCKS 时勿再设 {@link ProxyConfiguration}（会触发 UnsupportedSchemeException）。 */
    public boolean usesSocksForOci() {
        return enabled && canConnect()
                && (OciProxyConstants.TYPE_SOCKS5.equals(type) || OciProxyConstants.TYPE_SOCKS5H.equals(type));
    }

    public Optional<ProxyConfiguration> toOciProxyConfiguration() {
        if (!enabled || !canConnect()) {
            return Optional.empty();
        }
        if (usesSocksForOci()) {
            return Optional.empty();
        }
        var b = ProxyConfiguration.builder()
                .proxy(toJavaNetProxy());
        // 与 OciProxyConfigService / OCI SOCKS 路径一致做规范化；并支持「仅密码」的 HTTP 代理（此前仅 isNotBlank(user) 会漏掉密码）。
        String u = Socks5Tunnel.normalizeSocksCredential(proxyUser);
        String p = Socks5Tunnel.normalizeSocksCredential(proxyPass);
        if (!u.isEmpty() || !p.isEmpty()) {
            b.username(u).password(p.toCharArray());
        }
        return Optional.of(b.build());
    }

    public Map<String, String> toRawKvForPersistence() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("enabled", enabled ? "true" : "false");
        m.put("type", type);
        m.put("host", nvl(host));
        m.put("port", port > 0 ? String.valueOf(port) : "");
        m.put("user", nvl(proxyUser));
        m.put("pass", nvl(proxyPass));
        m.put("fullUrl", nvl(fullUrl));
        return m;
    }
}
