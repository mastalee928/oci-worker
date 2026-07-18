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
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 登录审计：账号、结果、IP、设备、UA 与经过脱敏的请求元数据；保留 7 天。
 * 登录密码、TG 验证码、Authorization、Cookie 和请求 Body 永不落库。
 */
@Slf4j
@Service
public class LoginAuditService {

    /** 单条脱敏详情上限；登录审计不应成为大对象或敏感数据存储。 */
    private static final int LOGIN_DETAIL_JSON_MAX = 64 * 1024;
    private static final int SINGLE_HEADER_VALUE_MAX = 2 * 1024;

    @Resource
    private OciLoginAuditMapper loginAuditMapper;

    public record ParsedUa(String os, String browser) {}
    public record LoginRequestSnapshot(String userAgent, String detailJson) {}

    public static ParsedUa parseUserAgent(String ua) {
        if (ua == null || ua.isBlank()) {
            return new ParsedUa("未知", "未知");
        }
        String u = ua.toLowerCase(Locale.ROOT);
        String os = "未知";
        if (u.contains("windows")) {
            os = "Windows";
        } else if (u.contains("android")) {
            os = "Android";
        } else if (u.contains("iphone") || u.contains("ipad") || u.contains("ios")) {
            os = "iOS";
        } else if (u.contains("mac os") || u.contains("macintosh")) {
            os = "macOS";
        } else if (u.contains("linux")) {
            os = "Linux";
        }
        String browser = "未知";
        if (u.contains("edg/")) {
            browser = "Edge";
        } else if (u.contains("opr/") || u.contains("opera")) {
            browser = "Opera";
        } else if (u.contains("firefox/")) {
            browser = "Firefox";
        } else if (u.contains("chrome/") || u.contains("crios/")) {
            browser = "Chrome";
        } else if (u.contains("safari/") && !u.contains("chrome")) {
            browser = "Safari";
        }
        return new ParsedUa(os, browser);
    }

    public void recordPasswordLogin(
            String account,
            String ip,
            String deviceId,
            boolean success,
            HttpServletRequest request) {
        recordPasswordLogin(account, ip, deviceId, success,
                captureRequestSnapshot(request));
    }

    public void recordPasswordLogin(
            String account,
            String ip,
            String deviceId,
            boolean success,
            LoginRequestSnapshot requestSnapshot) {
        insertRow(account, success ? "密码登录成功" : "密码已隐藏",
                ip, deviceId, success, requestSnapshot, "password");
    }

    public void recordTelegramLogin(
            String account,
            String ip,
            String deviceId,
            boolean success,
            HttpServletRequest request,
            String eventSummary) {
        recordTelegramLogin(account, ip, deviceId, success,
                captureRequestSnapshot(request), eventSummary);
    }

    public LoginRequestSnapshot captureRequestSnapshot(HttpServletRequest request) {
        return new LoginRequestSnapshot(
                request != null ? request.getHeader("User-Agent") : null,
                buildLoginDetailJson(request));
    }

    public void recordTelegramLogin(
            String account,
            String ip,
            String deviceId,
            boolean success,
            LoginRequestSnapshot requestSnapshot,
            String eventSummary) {
        String summary = StrUtil.isBlank(eventSummary)
                ? (success ? "TG 验证成功" : "TG 验证失败")
                : eventSummary.trim();
        insertRow(account, summary, ip, deviceId, success, requestSnapshot, "telegram");
    }

    private void insertRow(
            String account,
            String credentialSummary,
            String ip,
            String deviceId,
            boolean success,
            LoginRequestSnapshot requestSnapshot,
            String channel) {
        try {
            String userAgent = requestSnapshot != null ? requestSnapshot.userAgent() : null;
            ParsedUa p = parseUserAgent(userAgent);
            OciLoginAudit row = new OciLoginAudit();
            row.setId(CommonUtils.generateId());
            row.setAccount(sanitizeAuditText(account, 64));
            row.setPasswordAttempt(truncPlain(credentialSummary, 120));
            row.setIp(ip != null ? ip.trim() : null);
            row.setSuccess(success);
            row.setDeviceId(StrUtil.trimToNull(deviceId));
            row.setOsName(p.os());
            row.setBrowserName(p.browser());
            row.setLoginChannel(channel);
            row.setUserAgent(userAgent != null && userAgent.length() > 2000 ? userAgent.substring(0, 2000) : userAgent);
            row.setLoginDetail(requestSnapshot != null ? requestSnapshot.detailJson() : null);
            row.setCreateTime(LocalDateTime.now());
            loginAuditMapper.insert(row);
        } catch (Exception e) {
            log.warn("[LoginAudit] insert skipped: {}", e.getMessage());
        }
    }

    /**
     * 登录请求扩展详情 JSON（中文分组键便于前端展示）。仅保留排障所需元数据，
     * 所有认证头、Cookie、设备 Cookie 明文与请求 Body 均脱敏。
     */
    private static String buildLoginDetailJson(HttpServletRequest req) {
        if (req == null) {
            return null;
        }
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
            String did = HttpRequestUtil.getCookie(req, "ow_did");
            client.put("设备Cookie(ow_did)已携带", StrUtil.isNotBlank(did) ? "是" : "否");
            root.put("客户端与能力", client);

            Map<String, Object> allHeaders = new TreeMap<>();
            Enumeration<String> names = req.getHeaderNames();
            if (names != null) {
                for (String hn : Collections.list(names)) {
                    if (hn == null) {
                        continue;
                    }
                    String v = req.getHeader(hn);
                    allHeaders.put(hn, safeHeaderValue(hn, v));
                }
            }
            root.put("请求头（已脱敏）", allHeaders);

            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("Cookie", redactionMarker(req.getHeader("Cookie")));
            raw.put("Authorization", redactionMarker(req.getHeader("Authorization")));
            raw.put("RequestBody", req.getContentLengthLong() == 0 ? "" : "[REDACTED]");
            root.put("敏感字段处理", raw);

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

    private static String truncPlain(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…(超长已截断)";
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String safeHeaderValue(String name, String value) {
        if (name == null) return "";
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.equals("authorization") || n.equals("proxy-authorization")
                || n.equals("cookie") || n.equals("set-cookie")
                || n.equals("x-api-key") || n.contains("token") || n.contains("secret")
                || n.contains("session") || n.contains("credential") || n.contains("jwt")
                || n.contains("csrf") || n.endsWith("-key")) {
            return redactionMarker(value);
        }
        return truncPlain(value, SINGLE_HEADER_VALUE_MAX);
    }

    private static String redactionMarker(String value) {
        return StrUtil.isBlank(value) ? "" : "[REDACTED]";
    }

    private static String sanitizeAuditText(String value, int maxLen) {
        String cleaned = StrUtil.trimToNull(value);
        if (cleaned == null) return null;
        cleaned = cleaned.replaceAll("[\\p{Cntrl}\\r\\n]", "?");
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen);
    }

    public IPage<OciLoginAudit> pageAudits(long current, long size) {
        return loginAuditMapper.selectPage(
                new Page<>(current, size),
                new LambdaQueryWrapper<OciLoginAudit>().orderByDesc(OciLoginAudit::getCreateTime));
    }

    /** 每天凌晨删除超过 7 天的记录 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void purgeOlderThanSevenDays() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
            int n = loginAuditMapper.delete(
                    new LambdaQueryWrapper<OciLoginAudit>().lt(OciLoginAudit::getCreateTime, cutoff));
            if (n > 0) {
                log.info("[LoginAudit] purged {} rows older than 7 days", n);
            }
        } catch (Exception e) {
            log.warn("[LoginAudit] purge failed (表可能尚未创建): {}", e.getMessage());
        }
    }
}
