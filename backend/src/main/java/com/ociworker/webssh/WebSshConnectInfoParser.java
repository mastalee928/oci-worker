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
        if (sshInfoB64.length() > 512 * 1024) {
            throw new IllegalArgumentException("sshInfo is too large");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(sshInfoB64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("SSH connection info is invalid", e);
        }
        if (decoded.length > 384 * 1024) {
            throw new IllegalArgumentException("SSH connection info is too large");
        }
        WebSshConnectInfo info = JSON.readValue(decoded, WebSshConnectInfo.class);
        if (info == null) {
            throw new IllegalArgumentException("SSH connection info is invalid");
        }
        info.normalizeHostname();
        return info;
    }
}
