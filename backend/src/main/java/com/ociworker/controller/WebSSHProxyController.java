package com.ociworker.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@RestController
@RequestMapping("/webssh-api")
public class WebSSHProxyController {

    private static final String UPSTREAM = "http://127.0.0.1:8008";
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @GetMapping({"/check", "/sysinfo"})
    public void proxyGet(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String path = req.getRequestURI().replace("/webssh-api", "");
        String query = req.getQueryString();
        String url = UPSTREAM + path + (query != null ? "?" + query : "");

        HttpRequest upstream = HttpRequest.newBuilder()
                .uri(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<byte[]> result = client.send(upstream, HttpResponse.BodyHandlers.ofByteArray());

        resp.setStatus(result.statusCode());
        result.headers().firstValue("Content-Type").ifPresent(ct -> resp.setContentType(ct));
        resp.getOutputStream().write(result.body());
    }

    @GetMapping("/file/list")
    public void proxyFileList(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        proxyGet(req, resp);
    }

    @GetMapping("/file/download")
    public void proxyFileDownload(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String path = req.getRequestURI().replace("/webssh-api", "");
        String query = req.getQueryString();
        String url = UPSTREAM + path + (query != null ? "?" + query : "");

        HttpRequest upstream = HttpRequest.newBuilder()
                .uri(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build();
        HttpResponse<InputStream> result = client.send(upstream, HttpResponse.BodyHandlers.ofInputStream());

        resp.setStatus(result.statusCode());
        result.headers().firstValue("Content-Type").ifPresent(ct -> resp.setContentType(ct));
        result.headers().firstValue("Content-Disposition").ifPresent(cd -> resp.setHeader("Content-Disposition", cd));

        try (InputStream is = result.body(); OutputStream os = resp.getOutputStream()) {
            is.transferTo(os);
        }
    }

    @PostMapping("/file/upload")
    public void proxyFileUpload(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String boundary = "----WebSSHProxy" + System.currentTimeMillis();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        String sshInfo = req.getParameter("sshInfo");
        String filePath = req.getParameter("path");
        String id = req.getParameter("id");

        if (sshInfo != null) writeFormField(baos, boundary, "sshInfo", sshInfo);
        if (filePath != null) writeFormField(baos, boundary, "path", filePath);
        if (id != null) writeFormField(baos, boundary, "id", id);

        if (req instanceof org.springframework.web.multipart.MultipartHttpServletRequest mreq) {
            MultipartFile file = mreq.getFile("file");
            if (file != null) {
                baos.write(("--" + boundary + "\r\n").getBytes());
                baos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getOriginalFilename() + "\"\r\n").getBytes());
                baos.write(("Content-Type: " + file.getContentType() + "\r\n\r\n").getBytes());
                file.getInputStream().transferTo(baos);
                baos.write("\r\n".getBytes());
            }
        }
        baos.write(("--" + boundary + "--\r\n").getBytes());

        HttpRequest upstream = HttpRequest.newBuilder()
                .uri(URI.create(UPSTREAM + "/file/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
                .build();
        HttpResponse<byte[]> result = client.send(upstream, HttpResponse.BodyHandlers.ofByteArray());

        resp.setStatus(result.statusCode());
        result.headers().firstValue("Content-Type").ifPresent(ct -> resp.setContentType(ct));
        resp.getOutputStream().write(result.body());
    }

    private void writeFormField(ByteArrayOutputStream baos, String boundary, String name, String value) throws IOException {
        baos.write(("--" + boundary + "\r\n").getBytes());
        baos.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes());
        baos.write((value + "\r\n").getBytes());
    }
}
