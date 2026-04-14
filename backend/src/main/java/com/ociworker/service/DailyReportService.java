package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class DailyReportService {

    @Resource
    private OciUserMapper userMapper;
    @Resource
    private OciCreateTaskMapper taskMapper;
    @Resource
    private NotificationService notificationService;

    @Scheduled(cron = "0 0 9 * * ?")
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
