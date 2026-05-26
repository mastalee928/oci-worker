package com.ociworker.webssh;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
public class WebSshApiController {

    private final WebSshSysInfoService sysInfoService;
    private final WebSshFileService fileService;

    public WebSshApiController(WebSshSysInfoService sysInfoService, WebSshFileService fileService) {
        this.sysInfoService = sysInfoService;
        this.fileService = fileService;
    }

    @GetMapping("/check")
    public Map<String, Object> check(@RequestParam("sshInfo") String sshInfo) {
        long start = System.nanoTime();
        try {
            WebSshConnectInfo info = WebSshConnectInfoParser.parse(sshInfo);
            com.jcraft.jsch.Session session = WebSshJschSupport.openSession(info);
            WebSshJschSupport.closeQuietly(session);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("savePass", true);
            return WebSshResponse.body("success", data, duration(start));
        } catch (Exception e) {
            return WebSshResponse.body(e.getMessage(), null, duration(start));
        }
    }

    @GetMapping("/sysinfo")
    public Map<String, Object> sysinfo(@RequestParam("sshInfo") String sshInfo) {
        long start = System.nanoTime();
        try {
            return WebSshResponse.body("success", sysInfoService.collect(sshInfo), duration(start));
        } catch (Exception e) {
            return WebSshResponse.body(e.getMessage(), null, duration(start));
        }
    }

    @GetMapping("/file/list")
    public Map<String, Object> fileList(@RequestParam("sshInfo") String sshInfo,
                                        @RequestParam(value = "path", required = false) String path) {
        long start = System.nanoTime();
        try {
            return WebSshResponse.body("success", fileService.listFiles(sshInfo, path), duration(start));
        } catch (Exception e) {
            return WebSshResponse.body(e.getMessage(), null, duration(start));
        }
    }

    @GetMapping("/file/download")
    public void fileDownload(@RequestParam("sshInfo") String sshInfo,
                             @RequestParam(value = "path", required = false) String path,
                             HttpServletResponse response) throws Exception {
        String name = path != null && path.contains("/")
                ? path.substring(path.lastIndexOf('/') + 1)
                : "download";
        response.setHeader("Content-Disposition",
                "attachment; filename=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        response.setContentType("application/octet-stream");
        fileService.streamDownload(sshInfo, path, response.getOutputStream());
    }

    @PostMapping("/file/upload")
    public Map<String, Object> fileUpload(@RequestParam("sshInfo") String sshInfo,
                                          @RequestParam(value = "path", required = false) String path,
                                          @RequestParam(value = "dir", required = false) String dir,
                                          @RequestParam(value = "id", required = false) String id,
                                          @RequestParam("file") MultipartFile file) {
        long start = System.nanoTime();
        try {
            fileService.upload(sshInfo, path, dir, id, file);
            return WebSshResponse.body("success", null, duration(start));
        } catch (Exception e) {
            return WebSshResponse.body(e.getMessage(), null, duration(start));
        }
    }

    private static String duration(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        if (ms < 1000) {
            return ms + "ms";
        }
        return String.format("%.3fs", ms / 1000.0);
    }
}
