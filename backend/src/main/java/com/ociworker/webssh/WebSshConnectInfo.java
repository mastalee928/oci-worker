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
        if (hostname != null && hostname.contains(":") && !hostname.startsWith("[")) {
            hostname = "[" + hostname + "]";
        }
        if (port <= 0) {
            port = 22;
        }
    }
}
