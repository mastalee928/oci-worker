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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 登录审计：账号、尝试密码、IP、成败、设备、UA 解析、请求扩展详情 JSON；保留 7 天。
 */
@Slf4j
@Service
public class LoginAuditService {

    private static final int PASSWORD_FIELD_MAX = 500;

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
     * 访问入口、网络与链路、客户端与能力（中文键便于前端直接展示）。
     */
    private static String buildLoginDetailJson(HttpServletRequest req) {
        if (req == null) {
            return null;
        }
        try {
            Map<String, Object> root = new LinkedHashMap<>();

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("Host", nz(req.getHeader("Host")));
            entry.put("X-Forwarded-Host", nz(req.getHeader("X-Forwarded-Host")));
            entry.put("X-Forwarded-Proto", nz(req.getHeader("X-Forwarded-Proto")));
            entry.put("Origin", nz(req.getHeader("Origin")));
            entry.put("Referer", trunc(nz(req.getHeader("Referer")), 900));
            root.put("访问入口", entry);

            Map<String, Object> net = new LinkedHashMap<>();
            net.put("X-Forwarded-For", nz(req.getHeader("X-Forwarded-For")));
            net.put("X-Real-IP", nz(req.getHeader("X-Real-IP")));
            net.put("Forwarded", trunc(nz(req.getHeader("Forwarded")), 600));
            net.put("RemoteAddr", nz(req.getRemoteAddr()));
            net.put("RemotePort", String.valueOf(req.getRemotePort()));
            root.put("网络与链路", net);

            Map<String, Object> client = new LinkedHashMap<>();
            client.put("Accept-Language", trunc(nz(req.getHeader("Accept-Language")), 240));
            client.put("Accept-Encoding", trunc(nz(req.getHeader("Accept-Encoding")), 240));
            client.put("Accept", trunc(nz(req.getHeader("Accept")), 400));
            client.put("User-Agent", trunc(nz(req.getHeader("User-Agent")), 2000));
            client.put("Sec-CH-UA", trunc(nz(req.getHeader("Sec-CH-UA")), 500));
            client.put("Sec-CH-UA-Platform", nz(req.getHeader("Sec-CH-UA-Platform")));
            client.put("Sec-CH-UA-Mobile", nz(req.getHeader("Sec-CH-UA-Mobile")));
            String did = HttpRequestUtil.getCookie(req, "ow_did");
            client.put("设备Cookie(ow_did)已携带", StrUtil.isNotBlank(did) ? "是" : "否");
            root.put("客户端与能力", client);

            return JSONUtil.toJsonStr(root);
        } catch (Exception e) {
            log.warn("[LoginAudit] buildLoginDetailJson: {}", e.getMessage());
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String trunc(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
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
