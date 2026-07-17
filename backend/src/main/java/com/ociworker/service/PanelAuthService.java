package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.model.entity.OciKv;
import com.ociworker.util.CommonUtils;
import com.ociworker.util.HttpRequestUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Service
public class PanelAuthService {

    public static final String PANEL_TOKEN_COOKIE = "ow_panel_token";
    private static final long CREDENTIAL_SNAPSHOT_TTL_MS = 30_000L;

    @Value("${web.account}")
    private String defaultAccount;
    @Value("${web.password}")
    private String defaultPassword;

    @Resource
    private OciKvMapper kvMapper;
    private volatile CredentialSnapshot credentialSnapshot;
    private volatile long credentialSnapshotAt;

    private record CredentialSnapshot(String account, String passwordHash) {}

    public boolean validateRequestToken(HttpServletRequest request) {
        return validateRequestToken(request, true, false);
    }

    public boolean validateRequestToken(HttpServletRequest request, boolean allowQueryToken, boolean allowCookieToken) {
        return validateToken(readToken(request, allowQueryToken, allowCookieToken));
    }

    public boolean validateToken(String token) {
        String normalized = normalizeToken(token);
        if (StrUtil.isBlank(normalized)) {
            return false;
        }
        CredentialSnapshot snapshot = getCredentialSnapshot();
        return CommonUtils.validateToken(normalized, snapshot.account(), snapshot.passwordHash());
    }

    public String readToken(HttpServletRequest request) {
        return readToken(request, true, false);
    }

    public String readToken(HttpServletRequest request, boolean allowQueryToken, boolean allowCookieToken) {
        if (request == null) {
            return null;
        }
        String token = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StrUtil.isBlank(token) && allowQueryToken) {
            token = request.getParameter("token");
        }
        if (StrUtil.isBlank(token) && allowCookieToken) {
            token = decodeCookieValue(HttpRequestUtil.getCookie(request, PANEL_TOKEN_COOKIE));
        }
        return normalizeToken(token);
    }

    public String readToken(HttpHeaders headers, String rawQuery) {
        String token = headers != null ? headers.getFirst(HttpHeaders.AUTHORIZATION) : null;
        if (StrUtil.isBlank(token)) {
            token = readCookieHeader(headers, PANEL_TOKEN_COOKIE);
        }
        return normalizeToken(token);
    }

    private String getKv(String code) {
        try {
            OciKv kv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                    .eq(OciKv::getCode, code).eq(OciKv::getType, "sys_config"));
            return kv != null ? kv.getValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String getEffectiveAccount() {
        String stored = getKv("web_account");
        return stored != null ? stored : defaultAccount;
    }

    private CredentialSnapshot getCredentialSnapshot() {
        long now = System.currentTimeMillis();
        CredentialSnapshot current = credentialSnapshot;
        if (current != null && now - credentialSnapshotAt < CREDENTIAL_SNAPSHOT_TTL_MS) return current;
        synchronized (this) {
            now = System.currentTimeMillis();
            current = credentialSnapshot;
            if (current != null && now - credentialSnapshotAt < CREDENTIAL_SNAPSHOT_TTL_MS) return current;
            CredentialSnapshot loaded = new CredentialSnapshot(getEffectiveAccount(), getEffectivePasswordHash());
            credentialSnapshot = loaded;
            credentialSnapshotAt = now;
            return loaded;
        }
    }

    private String getEffectivePasswordHash() {
        String stored = getKv("web_password");
        if (stored != null) {
            if (isHashedPassword(stored)) {
                return stored;
            }
            return DigestUtil.sha256Hex(stored);
        }
        return DigestUtil.sha256Hex(defaultPassword);
    }

    private static boolean isHashedPassword(String pwd) {
        return pwd != null && pwd.length() == 64 && pwd.matches("[0-9a-f]+");
    }

    private static String normalizeToken(String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim();
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) {
            t = t.substring(7).trim();
        }
        return t;
    }

    private static String readCookieHeader(HttpHeaders headers, String name) {
        if (headers == null || StrUtil.isBlank(name)) {
            return null;
        }
        for (String header : headers.getOrEmpty(HttpHeaders.COOKIE)) {
            if (StrUtil.isBlank(header)) {
                continue;
            }
            for (String part : header.split(";")) {
                int i = part.indexOf('=');
                if (i <= 0) {
                    continue;
                }
                String k = part.substring(0, i).trim();
                if (name.equals(k)) {
                    return decodeCookieValue(part.substring(i + 1).trim());
                }
            }
        }
        return null;
    }

    private static String decodeCookieValue(String value) {
        if (value == null) {
            return null;
        }
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}
