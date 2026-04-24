package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.enums.SysCfgEnum;
import com.ociworker.enums.TaskStatusEnum;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciCreateTask;
import com.ociworker.model.entity.OciUser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class DailyReportService {

    private static final ZoneId DAILY_REPORT_ZONE = ZoneId.of("Asia/Shanghai");

    private final AtomicReference<LocalDate> lastDailyReportDate = new AtomicReference<>();

    @Resource
    private OciUserMapper userMapper;
    @Resource
    private OciCreateTaskMapper taskMapper;
    @Resource
    private NotificationService notificationService;

    /**
     * 每分钟检查是否到达用户配置的东八区时刻；同自然日只发送一次。
     */
    @Scheduled(cron = "0 * * * * ?")
    public void tickDailyReport() {
        if (!notificationService.isNotifyTypeEnabled(NotificationService.TYPE_DAILY_REPORT)) {
            return;
        }
        int[] hm = parseDailyTime(notificationService.getKvValue(SysCfgEnum.TG_DAILY_REPORT_TIME));
        ZonedDateTime now = ZonedDateTime.now(DAILY_REPORT_ZONE);
        if (now.getHour() != hm[0] || now.getMinute() != hm[1]) {
            return;
        }
        LocalDate today = now.toLocalDate();
        if (lastDailyReportDate.get() != null && lastDailyReportDate.get().equals(today)) {
            return;
        }
        try {
            sendDailyReport();
            lastDailyReportDate.set(today);
        } catch (Exception e) {
            log.error("Failed to send daily report: {}", e.getMessage());
        }
    }

    /** 解析 HH:mm，非法或空则默认 09:00 */
    private static int[] parseDailyTime(String s) {
        if (s == null) return new int[] {9, 0};
        s = s.trim();
        if (!s.matches("([01]\\d|2[0-3]):[0-5]\\d")) {
            return new int[] {9, 0};
        }
        String[] p = s.split(":");
        return new int[] {Integer.parseInt(p[0], 10), Integer.parseInt(p[1], 10)};
    }

    public void sendDailyReport() {
        try {
            List<OciUser> allUsers = userMapper.selectList(null);
            int total = allUsers.size();
            List<String> invalidNames = new ArrayList<>();

            for (OciUser user : allUsers) {
                try {
                    SysUserDTO dto = SysUserDTO.builder()
                            .username(user.getUsername())
                            .ociCfg(SysUserDTO.OciCfg.builder()
                                    .tenantId(user.getOciTenantId())
                                    .userId(user.getOciUserId())
                                    .fingerprint(user.getOciFingerprint())
                                    .region(user.getOciRegion())
                                    .privateKeyPath(user.getOciKeyPath())
                                    .build())
                            .build();
                    try (OciClientService client = new OciClientService(dto)) {
                        client.getIdentityClient().listRegionSubscriptions(
                                com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest.builder()
                                        .tenancyId(client.getCompartmentId())
                                        .build());
                    }
                } catch (Exception e) {
                    invalidNames.add(user.getUsername());
                }
            }

            long runningTasks = taskMapper.selectCount(
                    new LambdaQueryWrapper<OciCreateTask>()
                            .eq(OciCreateTask::getStatus, TaskStatusEnum.RUNNING.getStatus()));

            StringBuilder sb = new StringBuilder();
            sb.append("【每日播报】📊 系统日报\n");
            sb.append(String.format("租户总数: %d\n", total));
            sb.append(String.format("失效租户: %d\n", invalidNames.size()));
            sb.append(String.format("运行中任务: %d\n", runningTasks));
            if (!invalidNames.isEmpty()) {
                sb.append("失效租户列表: ").append(String.join(", ", invalidNames));
            } else {
                sb.append("所有租户配置正常 ✅");
            }

            notificationService.sendMessage(NotificationService.TYPE_DAILY_REPORT, sb.toString());
            log.info("Daily report sent");
        } catch (Exception e) {
            log.error("Failed to send daily report: {}", e.getMessage());
        }
    }
}
