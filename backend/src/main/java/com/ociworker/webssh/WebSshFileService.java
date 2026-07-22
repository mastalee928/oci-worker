package com.ociworker.webssh;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Vector;

@Service
@Slf4j
public class WebSshFileService {

    private final WebSshUploadRegistry uploadRegistry;
    private final WebSshSessionRegistry sessionRegistry;
    private static final DateTimeFormatter FILE_TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    public WebSshFileService(WebSshUploadRegistry uploadRegistry, WebSshSessionRegistry sessionRegistry) {
        this.uploadRegistry = uploadRegistry;
        this.sessionRegistry = sessionRegistry;
    }

    public Map<String, Object> listFiles(String sshInfoB64, String path) throws Exception {
        WebSshConnectInfo info = WebSshConnectInfoParser.parse(sshInfoB64);
        Session session = WebSshJschSupport.openSession(info);
        try {
            return listFilesOnSession(session, info.getUsername(), path);
        } finally {
            WebSshJschSupport.closeQuietly(session);
        }
    }

    public Map<String, Object> listFilesSession(String sessionId, String path) throws Exception {
        return sessionRegistry.withSession(sessionId,
                (session, username) -> listFilesOnSession(session, username, path));
    }

    public void streamDownload(String sshInfoB64, String path, OutputStream out) throws Exception {
        WebSshConnectInfo info = WebSshConnectInfoParser.parse(sshInfoB64);
        Session session = WebSshJschSupport.openSession(info);
        try {
            streamDownloadOnSession(session, info.getUsername(), path, out);
        } finally {
            WebSshJschSupport.closeQuietly(session);
        }
    }

    public void streamDownloadSession(String sessionId, String path, OutputStream out) throws Exception {
        sessionRegistry.withSession(sessionId,
                (session, username) -> {
                    streamDownloadOnSession(session, username, path, out);
                    return null;
                });
    }

    public String upload(String sshInfoB64, String path, String subDir, String uploadId, MultipartFile file) throws Exception {
        WebSshConnectInfo info = WebSshConnectInfoParser.parse(sshInfoB64);
        Session session = WebSshJschSupport.openSession(info);
        try {
            return uploadOnSession(session, info.getUsername(), path, subDir, uploadId, file);
        } finally {
            WebSshJschSupport.closeQuietly(session);
        }
    }

    public String uploadSession(String sessionId, String path, String subDir, String uploadId,
                                MultipartFile file) throws Exception {
        return sessionRegistry.withSession(sessionId,
                (session, username) -> uploadOnSession(session, username, path, subDir, uploadId, file));
    }

    private static Map<String, Object> listFilesOnSession(Session session, String username, String path)
            throws Exception {
        ChannelSftp sftp = WebSshJschSupport.openSftp(session);
        try {
            String home = detectHomeDir(sftp, username);
            String resolved = resolveListPath(path, home, username);
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(resolved);
            List<Map<String, Object>> list = new ArrayList<>();
            for (ChannelSftp.LsEntry e : entries) {
                String name = e.getFilename();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                SftpATTRS attrs = e.getAttrs();
                boolean dir = attrs.isDir();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("Name", name);
                row.put("IsDir", dir);
                row.put("Size", dir ? String.valueOf(attrs.getSize()) : byteFmt(attrs.getSize()));
                row.put("ModifyTime", formatTime(attrs.getMTime()));
                list.add(row);
            }
            list.sort(Comparator.comparing((Map<String, Object> m) -> !(Boolean) m.get("IsDir"))
                    .thenComparing(m -> String.valueOf(m.get("Name"))));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("list", list);
            data.put("home", home);
            return data;
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                throw new IllegalArgumentException("Directory " + path + ": no such file or directory");
            }
            throw e;
        } finally {
            WebSshJschSupport.closeQuietly(null, sftp);
        }
    }

    private static void streamDownloadOnSession(Session session, String username, String path, OutputStream out)
            throws Exception {
        ChannelSftp sftp = WebSshJschSupport.openSftp(session);
        try {
            String resolved = path;
            if (resolved == null || resolved.isBlank()) {
                throw new IllegalArgumentException("Download path is required");
            }
            try (InputStream in = sftp.get(resolved)) {
                in.transferTo(out);
            }
        } finally {
            WebSshJschSupport.closeQuietly(null, sftp);
        }
    }

    private String uploadOnSession(Session session, String username, String path, String subDir,
                                   String uploadId, MultipartFile file) throws Exception {
        ChannelSftp sftp = WebSshJschSupport.openSftp(session);
        try {
            String base = path;
            if (base == null || base.isBlank()) {
                base = detectHomeDir(sftp, username);
            }
            base = base.replaceAll("/+$", "");
            if (subDir != null && !subDir.isBlank()) {
                String dir = base + "/" + normalizeSubDirectory(subDir);
                mkdirsIfMissing(sftp, dir);
                base = dir;
            }
            String dst = base + "/" + sanitizeFileName(file.getOriginalFilename());
            uploadRegistry.track(uploadId);
            try (InputStream in = file.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                try (OutputStream dstOut = sftp.put(dst)) {
                    while ((n = in.read(buf)) >= 0) {
                        if (n == 0) {
                            continue;
                        }
                        dstOut.write(buf, 0, n);
                        uploadRegistry.add(uploadId, n);
                    }
                }
            } finally {
                uploadRegistry.remove(uploadId);
            }
            return dst;
        } finally {
            WebSshJschSupport.closeQuietly(null, sftp);
        }
    }

    private static String resolveListPath(String path, String home, String username) {
        if ("/".equals(path) && !"/".equals(home) && !"root".equals(username)) {
            return home;
        }
        if (path == null || path.isBlank()) {
            return "root".equals(username) ? "/" : home;
        }
        return path;
    }

    private static String detectHomeDir(ChannelSftp sftp, String username) throws SftpException {
        try {
            return sftp.pwd();
        } catch (SftpException e) {
            log.trace("Unable to read SFTP working directory", e);
        }
        if ("root".equals(username)) {
            return "/root";
        }
        String u1 = "/usr/home/" + username;
        try {
            sftp.stat(u1);
            return u1;
        } catch (SftpException e) {
            log.trace("Unable to inspect candidate home directory {}", u1, e);
        }
        String u2 = "/home/" + username;
        try {
            sftp.stat(u2);
            return u2;
        } catch (SftpException e) {
            log.trace("Unable to inspect candidate home directory {}", u2, e);
        }
        return "/home";
    }

    private static void mkdirsIfMissing(ChannelSftp sftp, String path) throws SftpException {
        try {
            SftpATTRS attrs = sftp.stat(path);
            if (!attrs.isDir()) {
                throw new IllegalArgumentException("Remote path is not a directory: " + path);
            }
            return;
        } catch (SftpException e) {
            if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                throw e;
            }
        }

        int slash = path.lastIndexOf('/');
        if (slash > 0) {
            mkdirsIfMissing(sftp, path.substring(0, slash));
        }
        try {
            sftp.mkdir(path);
        } catch (SftpException e) {
            try {
                if (sftp.stat(path).isDir()) {
                    return;
                }
            } catch (SftpException statError) {
                e.addSuppressed(statError);
            }
            throw e;
        }
    }

    private static String normalizeSubDirectory(String subDir) {
        String normalized = subDir.replace('\\', '/').replaceAll("^/+|/+$", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Upload directory is empty");
        }
        for (String part : normalized.split("/")) {
            if (part.isBlank() || ".".equals(part) || "..".equals(part) || containsControlCharacter(part)) {
                throw new IllegalArgumentException("Invalid upload directory");
            }
        }
        return normalized;
    }

    private static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "upload.bin";
        }
        String normalized = fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        normalized = normalized.trim();
        normalized = normalized.replaceAll("[\\x00-\\x1F\\x7F]", "_");
        return normalized.isBlank() || ".".equals(normalized) || "..".equals(normalized)
                ? "upload.bin"
                : normalized;
    }

    private static String formatTime(int mtime) {
        return FILE_TIME_FORMAT.format(Instant.ofEpochSecond(mtime));
    }

    static String byteFmt(long bytes) {
        if (bytes <= 0) {
            return "0B";
        }
        final String[] units = {"B", "K", "M", "G", "T", "P", "E"};
        int unit = 0;
        double value = bytes;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        String s = String.format(Locale.ROOT, "%.2f", value).replaceAll("\\.00$", "");
        return s + units[unit];
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f);
    }
}
