package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.enums.TaskStatusEnum;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciCreateTask;
import com.ociworker.util.OciRegionCatalog;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class SystemService {

    @Resource
    private OciUserMapper userMapper;
    @Resource
    private OciCreateTaskMapper taskMapper;
    @Lazy
    @Resource
    private OciProxyConfigService ociProxyConfigService;
    @Resource
    private StorageService storageService;

    private static final String UPDATE_REPO = "mastalee928/oci-worker";
    private static final String PUBLIC_UPDATE_REPO = "OCIworker/OCIworker";
    private static final String UPDATE_TAG = "latest";
    private static final String JAR_PATH = "/opt/oci-worker/oci-worker.jar";
    private static final String UPDATE_ASSET_NAME = "oci-worker-1.0.0.jar";
    private static final ObjectMapper JSON = new ObjectMapper();

    private String currentCommit;

    @jakarta.annotation.PostConstruct
    public void init() {
        try (var is = getClass().getClassLoader().getResourceAsStream("build-commit.txt")) {
            if (is != null) {
                currentCommit = new String(is.readAllBytes()).trim();
                if (currentCommit.length() > 7) currentCommit = currentCommit.substring(0, 7);
                log.info("Current build commit: {}", currentCommit);
            }
        } catch (Exception e) {
            log.warn("Could not read build-commit.txt: {}", e.getMessage());
        }
    }

    public Map<String, Object> checkUpdate() {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("currentCommit", currentCommit != null ? currentCommit : "dev");
        fillUpdateSource(result);

        File jarFile = new File(JAR_PATH);
        if (jarFile.exists()) {
            result.put("currentSize", jarFile.length());
            result.put("currentSizeHuman", humanReadableSize(jarFile.length()));
            long lastModified = jarFile.lastModified();
            var ldt = java.time.Instant.ofEpochMilli(lastModified)
                    .atZone(java.time.ZoneId.of("Asia/Shanghai"));
            result.put("currentBuildTime", ldt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        } else {
            result.put("currentSize", -1);
            result.put("currentSizeHuman", "未找到");
        }

        try {
            // 必须用 /releases/tags/latest，不能用 /releases/latest（会误成 installer-latest）
            String tagLatestApi = releaseApiUrl();
            HttpClient client = ociProxyConfigService.newOutboundHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tagLatestApi))
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "oci-worker-update")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                applyDownloadFallback(result, "GitHub API 返回 " + response.statusCode());
                return result;
            }

            JsonNode root = JSON.readTree(response.body());
            result.put("latestTag", root.path("tag_name").asText(""));

            long jarSize = 0;
            String releasePublishedAt = root.path("published_at").asText("");
            String assetUpdatedAt = "";
            for (JsonNode a : root.withArray("assets")) {
                if (UPDATE_ASSET_NAME.equals(a.path("name").asText())) {
                    jarSize = a.path("size").asLong(0);
                    assetUpdatedAt = a.path("updated_at").asText("");
                    if (assetUpdatedAt.isEmpty()) {
                        assetUpdatedAt = a.path("created_at").asText("");
                    }
                    break;
                }
            }
            String publishedAt = !assetUpdatedAt.isEmpty() ? assetUpdatedAt : releasePublishedAt;
            if (!publishedAt.isEmpty()) {
                result.put("publishedAt", publishedAt);
            }
            if (jarSize > 0) {
                result.put("latestSize", jarSize);
                result.put("latestSizeHuman", humanReadableSize(jarSize));
            } else {
                result.put("latestSize", -1);
                result.put("latestSizeHuman", "未知");
            }

            // 从 Release notes 正文解析构建 commit（避免整段 JSON 里先匹配到别的 "commit" 子串）
            String latestCommit = null;
            String bodyText = root.path("body").asText("");
            Matcher cm = Pattern.compile("(?i)commit[\\s]+([0-9a-f]{7,40})").matcher(bodyText);
            if (cm.find()) {
                String full = cm.group(1);
                latestCommit = full.length() > 7 ? full.substring(0, 7) : full;
                result.put("latestCommit", latestCommit);
            }

            if (currentCommit == null) {
                result.put("hasUpdate", false);
                result.put("notice", "当前为开发版本，无法对比 commit");
            } else if (latestCommit == null) {
                result.put("hasUpdate", false);
                result.put("notice", "无法从 GitHub Release 说明中解析构建 commit，请去仓库 Releases 核对");
            } else if (currentCommit.equalsIgnoreCase(latestCommit)) {
                result.put("hasUpdate", false);
            } else if (PUBLIC_UPDATE_REPO.equalsIgnoreCase(UPDATE_REPO)) {
                result.put("hasUpdate", true);
            } else {
                // 比较：base=线上下载包 commit，head=本机 JAR 内嵌 commit
                // ahead = 本机更新；behind = 本机更旧，应提示更新
                String compareApi = "https://api.github.com/repos/" + UPDATE_REPO + "/compare/"
                        + latestCommit + "..." + currentCommit;
                HttpRequest cr = HttpRequest.newBuilder()
                        .uri(URI.create(compareApi))
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "oci-worker-update")
                        .timeout(Duration.ofSeconds(15))
                        .GET().build();
                HttpResponse<String> cResp = client.send(cr, HttpResponse.BodyHandlers.ofString());
                if (cResp.statusCode() == 200) {
                    String status = JSON.readTree(cResp.body()).path("status").asText("");
                    switch (status) {
                        case "identical" -> {
                            result.put("hasUpdate", false);
                        }
                        case "ahead" -> {
                            // 本机相对线上下载包更「新」：无必要一键降级
                            result.put("hasUpdate", false);
                            result.put("versionNotice", "当前运行版本已新于或等于 GitHub 上 tag latest 发布包，无需在线更新。");
                        }
                        case "behind" -> {
                            result.put("hasUpdate", true);
                        }
                        case "diverged" -> {
                            result.put("hasUpdate", true);
                            result.put("versionNotice", "本地与线上下载包提交已分叉，更新前请确认数据与回滚方式。");
                        }
                        default -> {
                            result.put("hasUpdate", !currentCommit.equalsIgnoreCase(latestCommit));
                        }
                    }
                } else {
                    log.warn("GitHub compare 失败 HTTP {}: {}", cResp.statusCode(), cResp.body());
                    result.put("hasUpdate", !currentCommit.equalsIgnoreCase(latestCommit));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check update: {}", e.getMessage());
            applyDownloadFallback(result, e.getMessage());
        }

        return result;
    }

    public void performUpdate() {
        File jarFile = new File(JAR_PATH);
        if (!jarFile.exists()) {
            throw new OciException("未找到 " + JAR_PATH + "，请先通过 deploy.sh 部署");
        }

        try {
            String script = """
                    #!/bin/bash
                    set -e
                    REPO="%s"
                    TAG="%s"
                    ASSET="%s"
                    JAR="%s"
                    # 直连 latest 资源，避免先调 api.github.com + grep（省一次 RTT，也降低限流概率）
                    JAR_URL="https://github.com/${REPO}/releases/download/${TAG}/${ASSET}"
                    curl -fSL --retry 2 --retry-delay 2 --connect-timeout 15 --max-time 600 -o "${JAR}.tmp" "$JAR_URL"
                    NEW_SIZE=$(stat -c%%s "${JAR}.tmp" 2>/dev/null || echo 0)
                    if [ "$NEW_SIZE" -lt 1000 ]; then
                      rm -f "${JAR}.tmp"
                      echo "Download failed: file too small (${NEW_SIZE} bytes)"
                      exit 1
                    fi
                    mv "${JAR}.tmp" "$JAR"
                    systemctl stop oci-webssh 2>/dev/null || true
                    systemctl disable oci-webssh 2>/dev/null || true
                    rm -f /opt/oci-worker/oci-webssh
                    rm -f /etc/systemd/system/oci-webssh.service
                    systemctl daemon-reload 2>/dev/null || true
                    systemctl restart oci-worker
                    """.formatted(UPDATE_REPO, UPDATE_TAG, UPDATE_ASSET_NAME, JAR_PATH);

            Path scriptFile = Path.of("/tmp/oci-worker-update.sh");
            Files.writeString(scriptFile, script);
            try {
                Files.setPosixFilePermissions(scriptFile, PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (UnsupportedOperationException ignored) {}

            new ProcessBuilder("bash", "-c",
                    "nohup bash /tmp/oci-worker-update.sh > /tmp/oci-worker-update.log 2>&1 &")
                    .redirectErrorStream(true).start();
            log.info("Update process started in background");
        } catch (IOException e) {
            throw new OciException("启动更新失败: " + e.getMessage());
        }
    }

    private void fillUpdateSource(Map<String, Object> result) {
        result.put("updateRepo", UPDATE_REPO);
        result.put("updateTag", UPDATE_TAG);
        result.put("assetName", UPDATE_ASSET_NAME);
        result.put("sourceLabel", "GitHub Releases (" + UPDATE_REPO + ")");
        result.put("releaseUrl", releaseUrl());
        result.put("apiUrl", releaseApiUrl());
        result.put("downloadUrl", releaseDownloadUrl());
        result.put("downloadFallbackAvailable", false);
    }

    private static String releaseUrl() {
        return "https://github.com/" + UPDATE_REPO + "/releases/tag/" + UPDATE_TAG;
    }

    private static String releaseApiUrl() {
        return "https://api.github.com/repos/" + UPDATE_REPO + "/releases/tags/" + UPDATE_TAG;
    }

    private static String releaseDownloadUrl() {
        return "https://github.com/" + UPDATE_REPO + "/releases/download/" + UPDATE_TAG + "/" + UPDATE_ASSET_NAME;
    }

    private void applyDownloadFallback(Map<String, Object> result, String apiFailure) {
        String reason = StrUtil.blankToDefault(apiFailure, "GitHub API 查询失败");
        result.put("latestTag", UPDATE_TAG);
        result.put("apiError", reason);
        result.put("hasUpdate", false);

        try {
            HttpClient client = ociProxyConfigService.newOutboundHttpClientBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(releaseDownloadUrl()))
                    .header("User-Agent", "oci-worker-update")
                    .timeout(Duration.ofSeconds(20))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            result.put("downloadHttpStatus", status);
            if (status >= 200 && status < 400) {
                long size = parseContentLength(response);
                result.put("downloadFallbackAvailable", true);
                result.put("latestSize", size);
                result.put("latestSizeHuman", size > 0 ? humanReadableSize(size) : "可下载");
                result.put("notice", "GitHub API 查询失败，但安装包可下载，可直接更新或稍后重试检查");
                return;
            }

            result.put("latestSize", -1);
            result.put("latestSizeHuman", "查询失败");
            result.put("error", reason + "；安装包直链返回 " + status);
        } catch (Exception fallbackError) {
            result.put("latestSize", -1);
            result.put("latestSizeHuman", "查询失败");
            result.put("error", reason + "；安装包直链检查失败: " + fallbackError.getMessage());
        }
    }

    private static long parseContentLength(HttpResponse<?> response) {
        return response.headers().firstValue("content-length")
                .map(v -> {
                    try {
                        return Long.parseLong(v);
                    } catch (NumberFormatException ignored) {
                        return -1L;
                    }
                })
                .orElse(-1L);
    }

    private String humanReadableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB"};
        double size = bytes;
        for (String unit : units) {
            size /= 1024.0;
            if (size < 1024) return String.format("%.1f %s", size, unit);
        }
        return String.format("%.1f TB", size / 1024);
    }

    public Map<String, Object> getGlance() {
        Map<String, Object> result = new LinkedHashMap<>();

        long tenantCount = userMapper.selectCount(null);
        long runningTaskCount = taskMapper.selectCount(
                new LambdaQueryWrapper<OciCreateTask>()
                        .eq(OciCreateTask::getStatus, TaskStatusEnum.RUNNING.getStatus()));

        result.put("tenantCount", tenantCount);
        result.put("runningTaskCount", runningTaskCount);

        try {
            SystemInfo si = new SystemInfo();
            CentralProcessor processor = si.getHardware().getProcessor();
            GlobalMemory memory = si.getHardware().getMemory();

            double cpuLoad = processor.getSystemCpuLoad(500) * 100;
            long totalMem = memory.getTotal();
            long availMem = memory.getAvailable();
            double memUsage = (double) (totalMem - availMem) / totalMem * 100;

            result.put("cpuUsage", String.format("%.1f", cpuLoad));
            result.put("memoryUsage", String.format("%.1f", memUsage));
            result.put("totalMemoryGB", String.format("%.1f", totalMem / 1024.0 / 1024 / 1024));
        } catch (Exception e) {
            log.warn("Failed to get system info: {}", e.getMessage());
        }

        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        Duration uptime = Duration.ofMillis(uptimeMs);
        result.put("uptime", String.format("%dd %dh %dm",
                uptime.toDays(), uptime.toHoursPart(), uptime.toMinutesPart()));

        return result;
    }

    /**
     * 区域下拉：传 {@code userId} 时仅返回该租户在 OCI tenancy 已订阅的区域；不传则返回 SDK 枚举全集（如新增配置尚未有 id）。
     */
    public List<Map<String, String>> listOciRegionCatalog(String userId) {
        if (StrUtil.isNotBlank(userId)) {
            return OciRegionCatalog.listUiRowsForIds(storageService.listSubscribedRegionIds(userId));
        }
        return OciRegionCatalog.listUiRows();
    }
}
