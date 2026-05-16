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

import org.springframework.web.util.ContentCachingRequestWrapper;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 登录审计：账号、尝试密码、IP、成败、设备、UA 解析、请求扩展详情 JSON；保留 7 天。
 * <p>login_detail 可按部署需求记录完整请求明文（含 Cookie、Authorization、Body），须严格限制库与备份访问权限。</p>
 */
@Slf4j
@Service
public class LoginAuditService {

    private static final int PASSWORD_FIELD_MAX = 500;
    /** 单条 login_detail JSON 最大长度（MEDIUMTEXT）；超出则整体截断 */
    private static final int LOGIN_DETAIL_JSON_MAX = 15_500_000;
    /** 遍历请求头时，单个 header 值过长则截断（防 OOM） */
    private static final int SINGLE_HEADER_VALUE_MAX = 512 * 1024;

    @Resource
    private OciLoginAuditMapper loginAuditMapper;

    public record ParsedUa(String os, String browser) {}

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
            String passwordPlain,
            String ip,
            String deviceId,
            boolean success,
            HttpServletRequest request) {
        String ua = request != null ? request.getHeader("User-Agent") : null;
        insertRow(account, passwordPlain, ip, deviceId, success, ua, "password", request);
    }

    public void recordTelegramLogin(
            String account,
            String ip,
            String deviceId,
            boolean success,
            HttpServletRequest request,
            String passwordPlaceholder) {
        String ua = request != null ? request.getHeader("User-Agent") : null;
        insertRow(account, passwordPlaceholder, ip, deviceId, success, ua, "telegram", request);
    }

    private void insertRow(
            String account,
            String passwordPlain,
            String ip,
            String deviceId,
            boolean success,
            String userAgent,
            String channel,
            HttpServletRequest request) {
        try {
            ParsedUa p = parseUserAgent(userAgent);
            OciLoginAudit row = new OciLoginAudit();
            row.setId(CommonUtils.generateId());
            row.setAccount(StrUtil.trimToNull(account));
            row.setPasswordAttempt(truncatePwd(passwordPlain));
            row.setIp(ip != null ? ip.trim() : null);
            row.setSuccess(success);
            row.setDeviceId(StrUtil.trimToNull(deviceId));
            row.setOsName(p.os());
            row.setBrowserName(p.browser());
            row.setLoginChannel(channel);
            row.setUserAgent(userAgent != null && userAgent.length() > 2000 ? userAgent.substring(0, 2000) : userAgent);
            row.setLoginDetail(buildLoginDetailJson(request));
            row.setCreateTime(LocalDateTime.now());
            loginAuditMapper.insert(row);
        } catch (Exception e) {
            log.warn("[LoginAudit] insert skipped: {}", e.getMessage());
        }
    }

    /**
     * 登录请求扩展详情 JSON（中文分组键便于前端展示）。按运维要求记录尽量完整的明文请求信息；
     * 单 header / 整 JSON 仍有上限以防恶意超大请求导致 OOM。
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
            client.put("ow_did(明文)", nz(did));
            root.put("客户端与能力", client);

            Map<String, Object> allHeaders = new TreeMap<>();
            Enumeration<String> names = req.getHeaderNames();
            if (names != null) {
                for (String hn : Collections.list(names)) {
                    if (hn == null) {
                        continue;
                    }
                    String v = req.getHeader(hn);
                    allHeaders.put(hn, truncPlain(v, SINGLE_HEADER_VALUE_MAX));
                }
            }
            root.put("全部请求头（明文）", allHeaders);

            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("Cookie", nz(req.getHeader("Cookie")));
            raw.put("Authorization", nz(req.getHeader("Authorization")));
            raw.put("RequestBody", readCachedRequestBody(req));
            root.put("请求原文（高敏感）", raw);

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

    private static String readCachedRequestBody(HttpServletRequest req) {
        if (!(req instanceof ContentCachingRequestWrapper w)) {
            return "";
        }
        Charset cs = StandardCharsets.UTF_8;
        String enc = req.getCharacterEncoding();
        if (enc != null && !enc.isBlank()) {
            try {
                cs = Charset.forName(enc.trim());
            } catch (Exception ignored) {
                // keep UTF-8
            }
        }
        try {
            return w.getContentAsString(cs);
        } catch (Exception e) {
            byte[] buf = w.getContentAsByteArray();
            if (buf == null || buf.length == 0) {
                return "";
            }
            return new String(buf, StandardCharsets.UTF_8);
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

    private static String truncatePwd(String p) {
        if (p == null) {
            return null;
        }
        if (p.length() <= PASSWORD_FIELD_MAX) {
            return p;
        }
        return p.substring(0, PASSWORD_FIELD_MAX);
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
