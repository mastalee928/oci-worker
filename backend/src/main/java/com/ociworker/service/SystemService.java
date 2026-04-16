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
import java.lang.management.ManagementFactory;
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

    public Map<String, Object> checkUpdate() {
        Map<String, Object> result = new LinkedHashMap<>();

        File jarFile = new File(JAR_PATH);
        if (jarFile.exists()) {
            result.put("currentSize", jarFile.length());
            result.put("currentSizeHuman", humanReadableSize(jarFile.length()));
        } else {
            result.put("currentSize", -1);
            result.put("currentSizeHuman", "未找到");
        }

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"))
                    .header("Accept", "application/vnd.github.v3+json")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();

            Matcher sizeMatcher = Pattern.compile("\"name\"\\s*:\\s*\"" + Pattern.quote(ASSET_NAME) + "\"[^}]*\"size\"\\s*:\\s*(\\d+)").matcher(body);
            if (sizeMatcher.find()) {
                long remoteSize = Long.parseLong(sizeMatcher.group(1));
                result.put("latestSize", remoteSize);
                result.put("latestSizeHuman", humanReadableSize(remoteSize));
                result.put("hasUpdate", jarFile.exists() && remoteSize != jarFile.length());
            } else {
                result.put("latestSize", -1);
                result.put("latestSizeHuman", "未知");
                result.put("hasUpdate", false);
            }

            Matcher dateMatcher = Pattern.compile("\"published_at\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            if (dateMatcher.find()) {
                result.put("publishedAt", dateMatcher.group(1));
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
            String script = String.join(" && ",
                    "JAR_URL=$(curl -sL 'https://api.github.com/repos/" + REPO + "/releases/latest' " +
                            "| grep -o 'https://github.com/" + REPO + "/releases/download/[^\"]*" + ASSET_NAME + "' | head -1)",
                    "[ -z \"$JAR_URL\" ] && JAR_URL='https://github.com/" + REPO + "/releases/download/latest/" + ASSET_NAME + "'",
                    "curl -fSL --retry 3 --retry-delay 5 -o '" + JAR_PATH + ".tmp' \"$JAR_URL\"",
                    "NEW_SIZE=$(stat -c%s '" + JAR_PATH + ".tmp' 2>/dev/null || echo 0)",
                    "[ \"$NEW_SIZE\" -lt 1000 ] && rm -f '" + JAR_PATH + ".tmp' && exit 1",
                    "mv '" + JAR_PATH + ".tmp' '" + JAR_PATH + "'",
                    "systemctl restart oci-worker"
            );
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", "nohup bash -c '" +
                    script.replace("'", "'\\''") + "' > /tmp/oci-worker-update.log 2>&1 &");
            pb.redirectErrorStream(true);
            pb.start();
            log.info("Update process started in background");
        } catch (Exception e) {
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
