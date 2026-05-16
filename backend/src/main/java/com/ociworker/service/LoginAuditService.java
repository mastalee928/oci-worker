package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ociworker.mapper.OciLoginAuditMapper;
import com.ociworker.model.entity.OciLoginAudit;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 登录审计：账号、尝试密码、IP、成败、设备、UA 解析；保留 7 天。
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
            String userAgent) {
        insertRow(account, passwordPlain, ip, deviceId, success, userAgent, "password");
    }

    public void recordTelegramLogin(
            String account,
            String ip,
            String deviceId,
            boolean success,
            String userAgent,
            String passwordPlaceholder) {
        insertRow(account, passwordPlaceholder, ip, deviceId, success, userAgent, "telegram");
    }

    private void insertRow(
            String account,
            String passwordPlain,
            String ip,
            String deviceId,
            boolean success,
            String userAgent,
            String channel) {
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
            row.setCreateTime(LocalDateTime.now());
            loginAuditMapper.insert(row);
        } catch (Exception e) {
            log.warn("[LoginAudit] insert skipped: {}", e.getMessage());
        }
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
