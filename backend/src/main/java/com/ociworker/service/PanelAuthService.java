package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.model.entity.OciKv;
import com.ociworker.util.CommonUtils;
import com.ociworker.util.HttpRequestUtil;
import com.ociworker.util.PanelPasswordHasher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
@Service
// 首次安装的空库由 DatabaseGuardService 建表；凭据快照必须在其后加载，否则查询 oci_kv 失败会让 isReady 长期为 false。
@DependsOn("databaseGuardService")
public class PanelAuthService {

    public static final String PANEL_TOKEN_COOKIE = "ow_panel_token";

    @Resource
    private OciKvMapper kvMapper;
    private volatile CredentialSnapshot credentialSnapshot;

    private record CredentialSnapshot(boolean configured, String account, String passwordHash) {
        private static CredentialSnapshot unconfigured() {
            return new CredentialSnapshot(false, null, null);
        }
    }

    public record AuthenticatedSession(String account, String token) {}

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
        CredentialSnapshot snapshot = credentialSnapshot;
        if (snapshot == null || !snapshot.configured()) return false;
        return CommonUtils.validateToken(normalized, snapshot.account(), snapshot.passwordHash());
    }

    public boolean isConfigured() {
        CredentialSnapshot snapshot = credentialSnapshot;
        return snapshot != null && snapshot.configured();
    }

    public boolean isReady() {
        return credentialSnapshot != null;
    }

    /** 快照尚未加载成功时（如启动竞态导致首次加载失败）立即重试一次，让 needSetup 等入口尽快恢复。 */
    public boolean ensureReady() {
        if (credentialSnapshot == null) {
            refreshCredentialSnapshot();
        }
        return credentialSnapshot != null;
    }

    public AuthenticatedSession authenticate(String account, String rawPassword) {
        CredentialSnapshot snapshot = credentialSnapshot;
        if (snapshot == null || !snapshot.configured()) return null;
        // 不可因账号先失败而跳过慢哈希，否则响应时间可用于枚举管理员账号。
        boolean passwordMatches = PanelPasswordHasher.matches(rawPassword, snapshot.passwordHash());
        boolean accountMatches = constantTimeEquals(snapshot.account(), account);
        if (!(accountMatches & passwordMatches)) return null;
        return new AuthenticatedSession(snapshot.account(),
                CommonUtils.generateToken(snapshot.account(), snapshot.passwordHash()));
    }

    public AuthenticatedSession issueCurrentSession() {
        CredentialSnapshot snapshot = credentialSnapshot;
        if (snapshot == null || !snapshot.configured()) return null;
        return new AuthenticatedSession(snapshot.account(),
                CommonUtils.generateToken(snapshot.account(), snapshot.passwordHash()));
    }

    public String currentAccount() {
        CredentialSnapshot snapshot = credentialSnapshot;
        return snapshot != null && snapshot.configured() ? snapshot.account() : null;
    }

    public boolean verifyPassword(String rawPassword) {
        CredentialSnapshot snapshot = credentialSnapshot;
        return snapshot != null && snapshot.configured()
                && PanelPasswordHasher.matches(rawPassword, snapshot.passwordHash());
    }

    public boolean needsPasswordHashUpgrade() {
        CredentialSnapshot snapshot = credentialSnapshot;
        return snapshot != null && snapshot.configured()
                && PanelPasswordHasher.needsUpgrade(snapshot.passwordHash());
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
        OciKv kv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getCode, code).eq(OciKv::getType, "sys_config"));
        return kv != null ? kv.getValue() : null;
    }

    private CredentialSnapshot loadCredentialSnapshot() {
        String storedAccount = getKv("web_account");
        String storedPassword = getKv("web_password");
        boolean hasAccount = StrUtil.isNotBlank(storedAccount);
        boolean hasPassword = StrUtil.isNotBlank(storedPassword);
        if (hasAccount != hasPassword) {
            throw new IllegalStateException("Panel credential configuration is incomplete");
        }
        if (!hasAccount) {
            return CredentialSnapshot.unconfigured();
        }
        String passwordHash = PanelPasswordHasher.isModernHash(storedPassword)
                || PanelPasswordHasher.isLegacySha256(storedPassword)
                ? storedPassword
                : DigestUtil.sha256Hex(storedPassword);
        return new CredentialSnapshot(true, storedAccount, passwordHash);
    }

    @PostConstruct
    void initializeCredentialSnapshot() {
        refreshCredentialSnapshot();
    }

    /** 后台兜底刷新；失败时继续使用最近一次有效凭据，不能退回公开的默认账号密码。 */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public synchronized void refreshCredentialSnapshot() {
        try {
            credentialSnapshot = loadCredentialSnapshot();
        } catch (Exception e) {
            log.warn("Failed to refresh panel credential snapshot; keeping last known credentials: {}", e.getMessage());
        }
    }

    /** 配置写入成功后立即发布新凭据，避免旧账号或旧密码继续生效到 TTL 结束。 */
    public synchronized void updateCredentialSnapshot(String account, String passwordHash) {
        if (StrUtil.isBlank(account) || StrUtil.isBlank(passwordHash)) {
            throw new IllegalArgumentException("Panel account and password hash are required");
        }
        credentialSnapshot = new CredentialSnapshot(true, account, passwordHash);
    }

    private static String normalizeToken(String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim();
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) {
            t = t.substring(7).trim();
        }
        if (t.length() > 2048) return null;
        return t;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
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
