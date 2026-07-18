package com.ociworker.util;

import com.google.common.net.InetAddresses;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;

public final class HttpRequestUtil {

    private HttpRequestUtil() {}

    public static String getClientIp(HttpServletRequest request) {
        if (request == null) return "";
        String remote = normalizeIpLiteral(request.getRemoteAddr());
        // 只信任来自本机、容器网段或内网反代的转发头。公网客户端直连时若无条件
        // 信任 X-Forwarded-For，攻击者可伪造 IP 绕过登录限流和禁止名单。
        if (!isTrustedForwardingProxy(remote)) return remote;

        String forwarded = firstValidForwardedIp(request.getHeader("X-Forwarded-For"));
        if (!forwarded.isEmpty()) return forwarded;
        String realIp = normalizeIpLiteral(request.getHeader("X-Real-IP"));
        return !realIp.isEmpty() ? realIp : remote;
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

    /**
     * 从原始 Cookie 请求头解析指定名称（用于 WebSocket 握手等无 {@link HttpServletRequest} 场景）。
     */
    public static String getCookieValueFromCookieHeader(String cookieHeader, String name) {
        if (cookieHeader == null || name == null) {
            return null;
        }
        for (String part : cookieHeader.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && name.equals(kv[0].trim())) {
                String v = kv[1].trim();
                return v.isBlank() ? null : v;
            }
        }
        return null;
    }

    private static String firstValidForwardedIp(String header) {
        if (header == null || header.isBlank()) return "";
        for (String part : header.split(",")) {
            String ip = normalizeIpLiteral(part);
            if (!ip.isEmpty()) return ip;
        }
        return "";
    }

    private static String normalizeIpLiteral(String value) {
        if (value == null) return "";
        String ip = value.trim();
        if (ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) return "";
        if (ip.startsWith("[") && ip.endsWith("]")) ip = ip.substring(1, ip.length() - 1);
        int zone = ip.indexOf('%');
        if (zone > 0) ip = ip.substring(0, zone);
        try {
            return InetAddresses.toAddrString(InetAddresses.forString(ip));
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static boolean isTrustedForwardingProxy(String remote) {
        if (remote.isEmpty()) return false;
        try {
            InetAddress address = InetAddresses.forString(remote);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()) return true;
            byte[] bytes = address.getAddress();
            // IPv6 Unique Local Address fc00::/7（Java isSiteLocalAddress 不覆盖该范围）。
            return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
