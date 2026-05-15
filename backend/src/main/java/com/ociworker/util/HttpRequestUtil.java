package com.ociworker.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

public final class HttpRequestUtil {

    private HttpRequestUtil() {}

    public static String getClientIp(HttpServletRequest request) {
        if (request == null) return "";
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip != null ? ip : "";
    }

    public static String getCookie(HttpServletRequest request, String name) {
        if (request == null || name == null) return null;
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                String v = c.getValue();
                return v != null && !v.isBlank() ? v.trim() : null;
            }
        }
        return null;
    }
}
