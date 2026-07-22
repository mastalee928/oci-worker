package com.ociworker.webssh;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/webssh-api")
@Slf4j
public class WebSshApiController {

    private final WebSshSysInfoService sysInfoService;
    private final WebSshFileService fileService;

    public WebSshApiController(WebSshSysInfoService sysInfoService, WebSshFileService fileService) {
        this.sysInfoService = sysInfoService;
        this.fileService = fileService;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("showFooter", false);
        return body;
    }

    @PostMapping("/check")
    public Map<String, Object> check(@RequestParam("sshInfo") String sshInfo) {
        long start = System.nanoTime();
        try {
            WebSshConnectInfo info = WebSshConnectInfoParser.parse(sshInfo);
            com.jcraft.jsch.Session session = WebSshJschSupport.openSession(info);
            WebSshJschSupport.closeQuietly(session);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("savePass", false);
            return WebSshResponse.body("success", data, duration(start));
        } catch (Exception e) {
            return failure(e, "SSH connection failed", start);
        }
    }

    @PostMapping("/sysinfo")
    public Map<String, Object> sysinfo(
            @RequestParam(value = "sshInfo", required = false) String sshInfo,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        long start = System.nanoTime();
        try {
            Map<String, String> data = hasText(sessionId)
                    ? sysInfoService.collectSession(sessionId)
                    : sysInfoService.collect(requireText(sshInfo, "SSH connection info is required"));
            return WebSshResponse.body("success", data, duration(start));
        } catch (Exception e) {
            return failure(e, "System information unavailable", start);
        }
    }

    @PostMapping("/file/list")
    public Map<String, Object> fileList(
                                        @RequestParam(value = "sshInfo", required = false) String sshInfo,
                                        @RequestParam(value = "sessionId", required = false) String sessionId,
                                        @RequestParam(value = "path", required = false) String path) {
        long start = System.nanoTime();
        try {
            Map<String, Object> data = hasText(sessionId)
                    ? fileService.listFilesSession(sessionId, path)
                    : fileService.listFiles(requireText(sshInfo, "SSH connection info is required"), path);
            return WebSshResponse.body("success", data, duration(start));
        } catch (Exception e) {
            return failure(e, "Directory listing failed", start);
        }
    }

    @PostMapping("/file/download")
    public void fileDownload(@RequestParam(value = "sshInfo", required = false) String sshInfo,
                             @RequestParam(value = "sessionId", required = false) String sessionId,
                             @RequestParam(value = "path", required = false) String path,
                             HttpServletResponse response) throws Exception {
        path = requireText(path, "Download path is required");
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.isBlank()) {
            name = "download";
        }
        String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"download\"; filename*=UTF-8''" + encodedName);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentType("application/octet-stream");
        if (hasText(sessionId)) {
            fileService.streamDownloadSession(sessionId, path, response.getOutputStream());
        } else {
            fileService.streamDownload(requireText(sshInfo, "SSH connection info is required"), path,
                    response.getOutputStream());
        }
    }

    @PostMapping("/file/upload")
    public Map<String, Object> fileUpload(
                                          @RequestParam(value = "sshInfo", required = false) String sshInfo,
                                          @RequestParam(value = "sessionId", required = false) String sessionId,
                                          @RequestParam(value = "path", required = false) String path,
                                          @RequestParam(value = "dir", required = false) String dir,
                                          @RequestParam(value = "id", required = false) String id,
                                          @RequestParam("file") MultipartFile file) {
        long start = System.nanoTime();
        try {
            if (hasText(sessionId)) {
                fileService.uploadSession(sessionId, path, dir, id, file);
            } else {
                fileService.upload(requireText(sshInfo, "SSH connection info is required"), path, dir, id, file);
            }
            return WebSshResponse.body("success", null, duration(start));
        } catch (Exception e) {
            return failure(e, "File upload failed", start);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String requireText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String duration(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        if (ms < 1000) {
            return ms + "ms";
        }
        return String.format("%.3fs", ms / 1000.0);
    }

    private static Map<String, Object> failure(Exception error, String fallback, long startNanos) {
        log.debug("WebSSH API request failed: {}", error == null ? "unknown" : error.getMessage());
        return WebSshResponse.body(userMessage(error, fallback), null, duration(startNanos));
    }

    private static String userMessage(Exception error, String fallback) {
        if (error instanceof IllegalArgumentException) {
            String message = error.getMessage();
            if (message != null && !message.isBlank()) {
                return message.length() <= 200 ? message : message.substring(0, 200);
            }
        }
        String message = error == null || error.getMessage() == null
                ? "" : error.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (message.contains("auth fail") || message.contains("authentication")) {
            return "SSH authentication failed";
        }
        if (message.contains("timed out") || message.contains("timeout")) {
            return "SSH connection timed out";
        }
        if (message.contains("connection refused")) {
            return "SSH connection refused";
        }
        if (message.contains("unknownhost") || message.contains("unknown host")) {
            return "SSH host not found";
        }
        return fallback;
    }
}
