package com.ociworker.util.socks;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * SOCKS5 客户端隧道（RFC 1928 + RFC 1929 用户名密码），供 Apache HttpClient 套接字工厂使用。
 */
public final class Socks5Tunnel {

    private Socks5Tunnel() {
    }

    /**
     * 建立到 targetHost:targetPort 的 TCP 连接（经 SOCKS5 代理）。
     *
     * @param remoteDns true 时目标以域名形式发给代理（socks5h）；false 时在本机解析为 IP 再发给代理（socks5）
     */
    public static Socket connect(
            String proxyHost,
            int proxyPort,
            String proxyUser,
            String proxyPass,
            String targetHost,
            int targetPort,
            boolean remoteDns,
            int connectTimeoutMs) throws IOException {
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(proxyHost, proxyPort), connectTimeoutMs);
        socket.setSoTimeout(Math.max(connectTimeoutMs, 30_000));
        try {
            negotiateAndConnect(socket, proxyUser, proxyPass, targetHost, targetPort, remoteDns);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
        return socket;
    }

    private static void negotiateAndConnect(
            Socket socket,
            String proxyUser,
            String proxyPass,
            String targetHost,
            int targetPort,
            boolean remoteDns) throws IOException {
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        DataInputStream in = new DataInputStream(socket.getInputStream());

        boolean wantUserPass = (proxyUser != null && !proxyUser.isBlank())
                || (proxyPass != null && !proxyPass.isBlank());
        if (wantUserPass) {
            out.writeByte(0x05);
            out.writeByte(0x02);
            out.writeByte(0x00);
            out.writeByte(0x02);
        } else {
            out.writeByte(0x05);
            out.writeByte(0x01);
            out.writeByte(0x00);
        }
        out.flush();

        int ver = in.readUnsignedByte();
        int method = in.readUnsignedByte();
        if (ver != 0x05) {
            throw new IOException("SOCKS: 无效版本: " + ver);
        }
        if (method == 0xff) {
            throw new IOException("SOCKS: 无可用认证方式");
        }
        if (method == 0x02) {
            if (!wantUserPass) {
                throw new IOException("SOCKS: 服务器要求用户名密码但未配置");
            }
            doUsernamePasswordAuth(out, in, proxyUser, proxyPass);
        } else if (method != 0x00) {
            throw new IOException("SOCKS: 不支持的认证方法: " + method);
        }

        byte[] addrPayload;
        byte atyp;
        if (remoteDns) {
            byte[] hostBytes = targetHost.getBytes(StandardCharsets.UTF_8);
            if (hostBytes.length > 255) {
                throw new IOException("SOCKS: 目标主机名过长");
            }
            atyp = 0x03;
            addrPayload = new byte[1 + hostBytes.length];
            addrPayload[0] = (byte) hostBytes.length;
            System.arraycopy(hostBytes, 0, addrPayload, 1, hostBytes.length);
        } else {
            InetAddress resolved = InetAddress.getByName(targetHost);
            byte[] raw = resolved.getAddress();
            if (raw.length == 4) {
                atyp = 0x01;
                addrPayload = raw;
            } else if (raw.length == 16) {
                atyp = 0x04;
                addrPayload = raw;
            } else {
                throw new IOException("SOCKS: 无法解析目标地址");
            }
        }

        int reqLen = 4 + 1 + addrPayload.length + 2;
        byte[] req = new byte[reqLen];
        int i = 0;
        req[i++] = 0x05;
        req[i++] = 0x01;
        req[i++] = 0x00;
        req[i++] = atyp;
        System.arraycopy(addrPayload, 0, req, i, addrPayload.length);
        i += addrPayload.length;
        req[i++] = (byte) ((targetPort >> 8) & 0xff);
        req[i++] = (byte) (targetPort & 0xff);
        out.write(req);
        out.flush();

        int rver = in.readUnsignedByte();
        int rep = in.readUnsignedByte();
        in.readUnsignedByte();
        int rAtyp = in.readUnsignedByte();
        if (rver != 0x05) {
            throw new IOException("SOCKS: CONNECT 响应版本异常");
        }
        if (rep != 0x00) {
            throw new IOException("SOCKS: CONNECT 失败, REP=" + rep);
        }
        skipBindAddress(in, rAtyp);
    }

    private static void skipBindAddress(DataInputStream in, int atyp) throws IOException {
        int skip;
        switch (atyp) {
            case 0x01 -> skip = 4 + 2;
            case 0x03 -> skip = in.readUnsignedByte() + 2;
            case 0x04 -> skip = 16 + 2;
            default -> throw new IOException("SOCKS: 未知 ATYP: " + atyp);
        }
        in.skipBytes(skip);
    }

    private static void doUsernamePasswordAuth(DataOutputStream out, DataInputStream in, String user, String pass)
            throws IOException {
        String u = user == null ? "" : user;
        String p = pass == null ? "" : pass;
        byte[] ub = u.getBytes(StandardCharsets.UTF_8);
        byte[] pb = p.getBytes(StandardCharsets.UTF_8);
        if (ub.length > 255 || pb.length > 255) {
            throw new IOException("SOCKS: 用户名或密码过长");
        }
        out.writeByte(0x01);
        out.writeByte(ub.length);
        out.write(ub);
        out.writeByte(pb.length);
        out.write(pb);
        out.flush();
        int av = in.readUnsignedByte();
        int st = in.readUnsignedByte();
        if (av != 0x01 || st != 0x00) {
            throw new IOException("SOCKS: 用户名密码认证失败 (RFC1929 status=" + st + ", 请核对账号或完整 URL 中密码是否需百分号编码)");
        }
    }
}
