package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ociworker.mapper.OciLoginAuditMapper;
import com.ociworker.model.entity.OciLoginAudit;
import com.ociworker.util.CommonUtils;
import com.ociworker.util.HttpRequestUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 登录审计：完整记录登录画像并保留 7 天。密码、验证码、Cookie、Authorization、
 * 请求 Body 与完整请求详情使用 AES-256-GCM 加密后落库；TG 验证通过后才解密展示。
 */
@Slf4j
@Service
public class LoginAuditService {

    private static final int LOGIN_DETAIL_JSON_MAX = 15_500_000;
    private static final int SINGLE_HEADER_VALUE_MAX = 512 * 1024;
    private static final int CREDENTIAL_FIELD_MAX = 256;

    @Resource
    private OciLoginAuditMapper loginAuditMapper;
    @Resource
    private LoginAuditCryptoService loginAuditCryptoService;

    public record ParsedUa(String os, String browser) {}
    public record LoginRequestSnapshot(String userAgent, String detailJson) {}

    public static ParsedUa parseUserAgent(String ua) {
        if (ua == null || ua.isBlank()) return new ParsedUa("未知", "未知");
        String u = ua.toLowerCase(Locale.ROOT);
        String os = "未知";
        if (u.contains("windows")) os = "Windows";
        else if (u.contains("android")) os = "Android";
        else if (u.contains("iphone") || u.contains("ipad") || u.contains("ios")) os = "iOS";
        else if (u.contains("mac os") || u.contains("macintosh")) os = "macOS";
        else if (u.contains("linux")) os = "Linux";

        String browser = "未知";
        if (u.contains("edg/")) browser = "Edge";
        else if (u.contains("opr/") || u.contains("opera")) browser = "Opera";
        else if (u.contains("firefox/")) browser = "Firefox";
        else if (u.contains("chrome/") || u.contains("crios/")) browser = "Chrome";
        else if (u.contains("safari/") && !u.contains("chrome")) browser = "Safari";
        return new ParsedUa(os, browser);
    }

    public void recordPasswordLogin(String account, String passwordPlain, String ip, String deviceId,
                                    boolean success, HttpServletRequest request, String resultMessage,
                                    String requestBody) {
        recordPasswordLogin(account, passwordPlain, ip, deviceId, success,
                captureRequestSnapshot(request, requestBody), resultMessage);
    }

    public void recordPasswordLogin(String account, String passwordPlain, String ip, String deviceId,
                                    boolean success, LoginRequestSnapshot requestSnapshot,
                                    String resultMessage) {
        insertRow(account, passwordPlain, ip, deviceId, success, requestSnapshot,
                "password", resultMessage);
    }

    public void recordTelegramLogin(String account, String inputCode, String ip, String deviceId,
                                    boolean success, HttpServletRequest request, String resultMessage,
                                    String requestBody) {
        recordTelegramLogin(account, inputCode, ip, deviceId, success,
                captureRequestSnapshot(request, requestBody), resultMessage);
    }

    public void recordTelegramLogin(String account, String inputCode, String ip, String deviceId,
                                    boolean success, LoginRequestSnapshot requestSnapshot,
                                    String resultMessage) {
        insertRow(account, inputCode, ip, deviceId, success, requestSnapshot,
                "telegram", resultMessage);
    }

    public LoginRequestSnapshot captureRequestSnapshot(HttpServletRequest request, String requestBody) {
        return new LoginRequestSnapshot(
                request != null ? request.getHeader("User-Agent") : null,
                buildLoginDetailJson(request, requestBody));
    }

    private void insertRow(String account, String credentialPlain, String ip, String deviceId,
                           boolean success, LoginRequestSnapshot requestSnapshot,
                           String channel, String resultMessage) {
        try {
            String id = CommonUtils.generateId();
            String userAgent = requestSnapshot != null ? requestSnapshot.userAgent() : null;
            ParsedUa parsedUa = parseUserAgent(userAgent);
            OciLoginAudit row = new OciLoginAudit();
            row.setId(id);
            row.setAccount(sanitizeAuditText(account, 64));
            row.setPasswordAttempt(loginAuditCryptoService.encrypt(
                    truncate(credentialPlain, CREDENTIAL_FIELD_MAX), id, "passwordAttempt"));
            row.setIp(ip != null ? ip.trim() : null);
            row.setSuccess(success);
            row.setDeviceId(StrUtil.trimToNull(deviceId));
            row.setOsName(parsedUa.os());
            row.setBrowserName(parsedUa.browser());
            row.setLoginChannel(channel);
            row.setResultMessage(sanitizeAuditText(resultMessage, 128));
            row.setUserAgent(truncate(userAgent, 2000));
            String detail = requestSnapshot != null ? requestSnapshot.detailJson() : null;
            row.setLoginDetail(loginAuditCryptoService.encrypt(detail, id, "loginDetail"));
            row.setCreateTime(LocalDateTime.now());
            loginAuditMapper.insert(row);
        } catch (Exception e) {
            log.warn("[LoginAudit] insert skipped: {}", e.getMessage());
        }
    }

    /** 登录请求完整详情；整个 JSON 在写库前加密，因此可保留原始排障信息。 */
    private static String buildLoginDetailJson(HttpServletRequest req, String requestBody) {
        if (req == null) return null;
        try {
            Map<String, Object> root = new LinkedHashMap<>();

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("Method", nz(req.getMethod()));
            entry.put("RequestURI", nz(req.getRequestURI()));
            entry.put("QueryString", nz(req.getQueryString()));
            entry.put("Content-Type", nz(req.getContentType()));
            entry.put("CharacterEncoding", nz(req.getCharacterEncoding()));
            entry.put("Host", nz(req.getHeader("Host")));
            entry.put("X-Forwarded-Host", nz(req.getHeader("X-Forwarded-Host")));
            entry.put("X-Forwarded-Proto", nz(req.getHeader("X-Forwarded-Proto")));
            entry.put("Origin", nz(req.getHeader("Origin")));
            entry.put("Referer", nz(req.getHeader("Referer")));
            root.put("访问入口", entry);

            Map<String, Object> net = new LinkedHashMap<>();
            net.put("X-Forwarded-For", nz(req.getHeader("X-Forwarded-For")));
            net.put("X-Real-IP", nz(req.getHeader("X-Real-IP")));
            net.put("Forwarded", nz(req.getHeader("Forwarded")));
            net.put("Via", nz(req.getHeader("Via")));
            net.put("Proxy-Connection", nz(req.getHeader("Proxy-Connection")));
            net.put("CF-Ray", nz(req.getHeader("CF-Ray")));
            net.put("CF-Connecting-IP", nz(req.getHeader("CF-Connecting-IP")));
            net.put("CF-Visitor", nz(req.getHeader("CF-Visitor")));
            net.put("True-Client-IP", nz(req.getHeader("True-Client-IP")));
            net.put("X-Request-Id", nz(req.getHeader("X-Request-Id")));
            net.put("X-Correlation-Id", nz(req.getHeader("X-Correlation-Id")));
            net.put("X-Amzn-Trace-Id", nz(req.getHeader("X-Amzn-Trace-Id")));
            net.put("Fastly-Client-IP", nz(req.getHeader("Fastly-Client-IP")));
            net.put("Fly-Client-IP", nz(req.getHeader("Fly-Client-IP")));
            net.put("RemoteAddr", nz(req.getRemoteAddr()));
            net.put("RemotePort", String.valueOf(req.getRemotePort()));
            net.put("LocalAddr", nz(req.getLocalAddr()));
            net.put("LocalPort", String.valueOf(req.getLocalPort()));
            net.put("Protocol", nz(req.getProtocol()));
            root.put("网络与链路", net);

            Map<String, Object> fetch = new LinkedHashMap<>();
            fetch.put("Sec-Fetch-Site", nz(req.getHeader("Sec-Fetch-Site")));
            fetch.put("Sec-Fetch-Mode", nz(req.getHeader("Sec-Fetch-Mode")));
            fetch.put("Sec-Fetch-Dest", nz(req.getHeader("Sec-Fetch-Dest")));
            fetch.put("Sec-Fetch-User", nz(req.getHeader("Sec-Fetch-User")));
            fetch.put("Sec-Fetch-Priority", nz(req.getHeader("Sec-Fetch-Priority")));
            root.put("Fetch 元数据", fetch);

            Map<String, Object> hints = new LinkedHashMap<>();
            hints.put("Sec-CH-UA", nz(req.getHeader("Sec-CH-UA")));
            hints.put("Sec-CH-UA-Full-Version-List", nz(req.getHeader("Sec-CH-UA-Full-Version-List")));
            hints.put("Sec-CH-UA-Platform", nz(req.getHeader("Sec-CH-UA-Platform")));
            hints.put("Sec-CH-UA-Platform-Version", nz(req.getHeader("Sec-CH-UA-Platform-Version")));
            hints.put("Sec-CH-UA-Mobile", nz(req.getHeader("Sec-CH-UA-Mobile")));
            hints.put("Sec-CH-UA-Model", nz(req.getHeader("Sec-CH-UA-Model")));
            hints.put("Sec-CH-UA-Arch", nz(req.getHeader("Sec-CH-UA-Arch")));
            hints.put("Sec-CH-UA-Bitness", nz(req.getHeader("Sec-CH-UA-Bitness")));
            hints.put("Sec-CH-Viewport-Width", nz(req.getHeader("Sec-CH-Viewport-Width")));
            hints.put("Viewport-Width", nz(req.getHeader("Viewport-Width")));
            hints.put("Device-Memory", nz(req.getHeader("Device-Memory")));
            hints.put("DPR", nz(req.getHeader("DPR")));
            hints.put("Downlink", nz(req.getHeader("Downlink")));
            hints.put("RTT", nz(req.getHeader("RTT")));
            hints.put("ECT", nz(req.getHeader("ECT")));
            hints.put("Save-Data", nz(req.getHeader("Save-Data")));
            root.put("Client Hints", hints);

            Map<String, Object> client = new LinkedHashMap<>();
            client.put("Accept-Language", nz(req.getHeader("Accept-Language")));
            client.put("Accept-Encoding", nz(req.getHeader("Accept-Encoding")));
            client.put("Accept", nz(req.getHeader("Accept")));
            client.put("User-Agent", nz(req.getHeader("User-Agent")));
            String deviceCookie = HttpRequestUtil.getCookie(req, "ow_did");
            client.put("设备Cookie(ow_did)已携带", StrUtil.isNotBlank(deviceCookie) ? "是" : "否");
            client.put("ow_did(明文)", nz(deviceCookie));
            root.put("客户端与能力", client);

            Map<String, Object> allHeaders = new TreeMap<>();
            Enumeration<String> names = req.getHeaderNames();
            if (names != null) {
                for (String name : Collections.list(names)) {
                    if (name != null) {
                        allHeaders.put(name, truncate(req.getHeader(name), SINGLE_HEADER_VALUE_MAX));
                    }
                }
            }
            root.put("全部请求头（加密保存）", allHeaders);

            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("Cookie", nz(req.getHeader("Cookie")));
            raw.put("Authorization", nz(req.getHeader("Authorization")));
            raw.put("RequestBody", requestBody == null ? "" : requestBody);
            root.put("请求原文（加密保存）", raw);

            String json = JSONUtil.toJsonStr(root);
            if (json.length() > LOGIN_DETAIL_JSON_MAX) {
                return json.substring(0, LOGIN_DETAIL_JSON_MAX) + "…(login_detail JSON 超长已截断)";
            }
            return json;
        } catch (Exception e) {
            log.warn("[LoginAudit] buildLoginDetailJson: {}", e.getMessage());
            return null;
        }
    }

    public IPage<OciLoginAudit> pageAudits(long current, long size) {
        IPage<OciLoginAudit> page = loginAuditMapper.selectPage(
                new Page<>(current, size),
                new LambdaQueryWrapper<OciLoginAudit>().orderByDesc(OciLoginAudit::getCreateTime));
        List<OciLoginAudit> records = page.getRecords();
        if (records == null) return page;
        for (OciLoginAudit row : records) {
            try {
                row.setPasswordAttempt(loginAuditCryptoService.decryptIfEncrypted(
                        row.getPasswordAttempt(), row.getId(), "passwordAttempt"));
                row.setLoginDetail(loginAuditCryptoService.decryptIfEncrypted(
                        row.getLoginDetail(), row.getId(), "loginDetail"));
            } catch (Exception e) {
                log.warn("[LoginAudit] decrypt failed for {}: {}", row.getId(), e.getMessage());
                row.setPasswordAttempt("(凭据解密失败)");
                row.setLoginDetail(null);
            }
        }
        return page;
    }

    /** 每天凌晨删除超过 7 天的记录。 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void purgeOlderThanSevenDays() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
            int deleted = loginAuditMapper.delete(
                    new LambdaQueryWrapper<OciLoginAudit>().lt(OciLoginAudit::getCreateTime, cutoff));
            if (deleted > 0) log.info("[LoginAudit] purged {} rows older than 7 days", deleted);
        } catch (Exception e) {
            log.warn("[LoginAudit] purge failed (表可能尚未创建): {}", e.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max) + "…(超长已截断)";
    }

    private static String nz(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sanitizeAuditText(String value, int maxLen) {
        String cleaned = StrUtil.trimToNull(value);
        if (cleaned == null) return null;
        cleaned = cleaned.replaceAll("[\\p{Cntrl}\\r\\n]", "?");
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen);
    }
}
