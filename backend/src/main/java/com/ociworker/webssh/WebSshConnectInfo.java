package com.ociworker.webssh;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebSshConnectInfo {

    private String username;
    private String password;
    private String hostname;
    private int port = 22;

    @JsonProperty("logintype")
    private int loginType;

    private String privateKey;
    private String passphrase;

    private String proxyHost;
    private int proxyPort;
    private String proxyUser;
    private String proxyPass;

    void normalizeHostname() {
        username = username == null ? null : username.trim();
        hostname = hostname == null ? null : hostname.trim();
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("SSH username is required");
        }
        if (hostname == null || hostname.isBlank() || containsControl(hostname)) {
            throw new IllegalArgumentException("SSH hostname is invalid");
        }
        if (hostname != null && hostname.contains(":") && !hostname.startsWith("[")) {
            hostname = "[" + hostname + "]";
        }
        if (port <= 0) {
            port = 22;
        }
        if (port > 65535) {
            throw new IllegalArgumentException("SSH port is invalid");
        }
        if (proxyPort <= 0) {
            proxyPort = 1080;
        }
        if (proxyPort > 65535) {
            throw new IllegalArgumentException("Proxy port is invalid");
        }
        if (loginType != 0 && loginType != 1) {
            throw new IllegalArgumentException("SSH login type is invalid");
        }
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f);
    }
}
