package com.ociworker.webssh;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class WebSshConnectInfoParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WebSshConnectInfoParser() {
    }

    static WebSshConnectInfo parse(String sshInfoB64) throws Exception {
        if (sshInfoB64 == null || sshInfoB64.isBlank()) {
            throw new IllegalArgumentException("sshInfo is empty");
        }
        byte[] decoded = Base64.getDecoder().decode(sshInfoB64.trim());
        WebSshConnectInfo info = JSON.readValue(decoded, WebSshConnectInfo.class);
        info.normalizeHostname();
        return info;
    }
}
