package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.enums.TaskStatusEnum;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciCreateTask;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class SystemService {

    @Resource
    private OciUserMapper userMapper;
    @Resource
    private OciCreateTaskMapper taskMapper;

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
