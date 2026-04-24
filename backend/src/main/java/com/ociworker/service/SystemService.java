package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.enums.TaskStatusEnum;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciCreateTask;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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
import java.time.Duration;
import java.util.LinkedHashMap;
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

    private static final String REPO = "mastalee928/oci-worker";
    private static final String JAR_PATH = "/opt/oci-worker/oci-worker.jar";
    private static final String ASSET_NAME = "oci-worker-1.0.0.jar";

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
            // 必须用 /releases/tags/latest：/releases/latest 是「全仓库最近发布的一条 Release」，
            // 若 installer-latest 比应用 JAR 的 latest 更晚发布，会错拿到安装器包（~数 MB），与 oci-worker-1.0.0.jar 无关。
            String tagLatestApi = "https://api.github.com/repos/" + REPO + "/releases/tags/latest";
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tagLatestApi))
                    .header("Accept", "application/vnd.github.v3+json")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (response.statusCode() != 200) {
                result.put("latestSize", -1);
                result.put("latestSizeHuman", "查询失败");
                result.put("hasUpdate", false);
                result.put("error", "GitHub 返回 " + response.statusCode() + "（可能无 tag latest）");
                return result;
            }

            long jarSize = 0;
            Matcher jarAsset = Pattern.compile(
                    "\"name\"\\s*:\\s*\"" + Pattern.quote(ASSET_NAME) + "\"[\\s\\S]*?\"size\"\\s*:\\s*(\\d+)"
            ).matcher(body);
            if (jarAsset.find()) {
                jarSize = Long.parseLong(jarAsset.group(1));
            } else {
                Matcher sizeMatcher = Pattern.compile("\"size\"\\s*:\\s*(\\d+)").matcher(body);
                while (sizeMatcher.find()) {
                    long s = Long.parseLong(sizeMatcher.group(1));
                    if (s > jarSize) jarSize = s;
                }
            }
            if (jarSize > 0) {
                result.put("latestSize", jarSize);
                result.put("latestSizeHuman", humanReadableSize(jarSize));
            } else {
                result.put("latestSize", -1);
                result.put("latestSizeHuman", "未知");
            }

            Matcher dateMatcher = Pattern.compile("\"published_at\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            if (dateMatcher.find()) {
                result.put("publishedAt", dateMatcher.group(1));
            }

            String latestCommit = null;
            Matcher commitMatcher = Pattern.compile("commit\\s+([0-9a-f]{7,40})").matcher(body);
            if (commitMatcher.find()) {
                String fullHash = commitMatcher.group(1);
                latestCommit = fullHash.length() > 7 ? fullHash.substring(0, 7) : fullHash;
                result.put("latestCommit", latestCommit);
            }

            Matcher tagMatcher = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            if (tagMatcher.find()) {
                result.put("latestTag", tagMatcher.group(1));
            }

            if (currentCommit != null && latestCommit != null) {
                result.put("hasUpdate", !currentCommit.equals(latestCommit));
            } else {
                result.put("hasUpdate", false);
                if (currentCommit == null) {
                    result.put("notice", "当前为开发版本，无法对比commit");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check update: {}", e.getMessage());
            result.put("latestSize", -1);
            result.put("latestSizeHuman", "查询失败");
            result.put("hasUpdate", false);
            result.put("error", e.getMessage());
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
                    ASSET="%s"
                    JAR="%s"
                    sleep 2
                    JAR_URL=$(curl -sL "https://api.github.com/repos/${REPO}/releases/tags/latest" \
                      | grep -o "https://github.com/${REPO}/releases/download/[^\\"]*${ASSET}" | head -1)
                    [ -z "$JAR_URL" ] && JAR_URL="https://github.com/${REPO}/releases/download/latest/${ASSET}"
                    curl -fSL --retry 3 --retry-delay 5 -o "${JAR}.tmp" "$JAR_URL"
                    NEW_SIZE=$(stat -c%%s "${JAR}.tmp" 2>/dev/null || echo 0)
                    if [ "$NEW_SIZE" -lt 1000 ]; then
                      rm -f "${JAR}.tmp"
                      echo "Download failed: file too small (${NEW_SIZE} bytes)"
                      exit 1
                    fi
                    mv "${JAR}.tmp" "$JAR"
                    systemctl restart oci-worker
                    """.formatted(REPO, ASSET_NAME, JAR_PATH);

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
}
