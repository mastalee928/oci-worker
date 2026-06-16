package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ociworker.enums.SysCfgEnum;
import com.ociworker.enums.TaskStatusEnum;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciCreateTask;
import com.ociworker.model.entity.OciUser;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.requests.GetTenancyRequest;
import com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest;
import com.oracle.bmc.model.BmcException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
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
            List<String> checkFailedNames = new ArrayList<>();

            for (OciUser user : allUsers) {
                TenantCheckResult check = checkTenant(user);
                if (check.invalid()) {
                    invalidNames.add(user.getUsername());
                } else if (!check.ok()) {
                    checkFailedNames.add(user.getUsername() + "（" + check.reason() + "）");
                }
            }

            long runningTasks = taskMapper.selectCount(
                    new LambdaQueryWrapper<OciCreateTask>()
                            .eq(OciCreateTask::getStatus, TaskStatusEnum.RUNNING.getStatus()));

            StringBuilder sb = new StringBuilder();
            sb.append("【每日播报】📊 系统日报\n");
            sb.append(String.format("租户总数: %d\n", total));
            sb.append(String.format("失效租户: %d\n", invalidNames.size()));
            if (!checkFailedNames.isEmpty()) {
                sb.append(String.format("检测失败: %d\n", checkFailedNames.size()));
            }
            sb.append(String.format("运行中任务: %d\n", runningTasks));
            if (!invalidNames.isEmpty()) {
                sb.append("失效租户列表: ").append(String.join(", ", invalidNames)).append("\n");
            }
            if (!checkFailedNames.isEmpty()) {
                sb.append("检测失败列表: ").append(String.join(", ", checkFailedNames));
            } else if (invalidNames.isEmpty()) {
                sb.append("所有租户配置正常 ✅");
            } else {
                sb.append("其余租户配置正常 ✅");
            }

            notificationService.sendMessage(NotificationService.TYPE_DAILY_REPORT, sb.toString());
            log.info("Daily report sent");
        } catch (Exception e) {
            log.error("Failed to send daily report: {}", e.getMessage());
        }
    }

    private TenantCheckResult checkTenant(OciUser user) {
        try {
            requireField(user.getOciTenantId(), "Tenant ID 为空");
            requireField(user.getOciUserId(), "User ID 为空");
            requireField(user.getOciFingerprint(), "Fingerprint 为空");
            requireField(user.getOciRegion(), "Region 为空");
            requireField(user.getOciKeyPath(), "密钥路径为空");

            SimpleAuthenticationDetailsProvider provider = SimpleAuthenticationDetailsProvider.builder()
                    .tenantId(user.getOciTenantId().trim())
                    .userId(user.getOciUserId().trim())
                    .fingerprint(user.getOciFingerprint().trim())
                    .privateKeySupplier(() -> {
                        try (FileInputStream fis = new FileInputStream(user.getOciKeyPath().trim());
                             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                            fis.transferTo(baos);
                            return new ByteArrayInputStream(baos.toByteArray());
                        } catch (Exception e) {
                            throw new RuntimeException("密钥文件无效或不存在", e);
                        }
                    })
                    .region(Region.fromRegionCodeOrId(user.getOciRegion().trim()))
                    .build();

            try (IdentityClient identityClient = buildIdentityClient(provider)) {
                identityClient.getTenancy(GetTenancyRequest.builder()
                        .tenancyId(user.getOciTenantId().trim())
                        .build());
                identityClient.listRegionSubscriptions(ListRegionSubscriptionsRequest.builder()
                        .tenancyId(user.getOciTenantId().trim())
                        .build());
            }
            return TenantCheckResult.success();
        } catch (Exception e) {
            if (isDefinitelyInvalid(e)) {
                return TenantCheckResult.invalid(shortReason(e));
            }
            return TenantCheckResult.failed(shortReason(e));
        }
    }

    private static IdentityClient buildIdentityClient(SimpleAuthenticationDetailsProvider provider) {
        var builder = IdentityClient.builder();
        OciProxyConfigService proxyConfigService = OciProxyConfigService.instance();
        if (proxyConfigService == null || !proxyConfigService.ociUsesExplicitClientProxy()) {
            builder = builder.additionalClientConfigurator(OciProxyConfigService.ociSdkJerseyDirectConfigurator());
        }
        return builder.build(provider);
    }

    private static void requireField(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean isDefinitelyInvalid(Exception e) {
        if (e instanceof IllegalArgumentException) {
            return true;
        }
        Throwable cur = e;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("notauthenticated")
                        || lower.contains("not authorized")
                        || lower.contains("notauthorized")
                        || lower.contains("tenant id 不存在")
                        || lower.contains("not found")
                        || lower.contains("密钥")
                        || lower.contains("private key")
                        || lower.contains("unknown region")) {
                    return true;
                }
            }
            if (cur instanceof BmcException bmc) {
                int status = bmc.getStatusCode();
                return status == 401 || status == 403 || status == 404;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String shortReason(Exception e) {
        String msg = e.getMessage();
        if ((msg == null || msg.isBlank()) && e.getCause() != null) {
            msg = e.getCause().getMessage();
        }
        if (msg == null || msg.isBlank()) {
            msg = e.getClass().getSimpleName();
        }
        msg = msg.replace('\n', ' ').replace('\r', ' ').trim();
        return msg.length() > 80 ? msg.substring(0, 80) : msg;
    }

    private record TenantCheckResult(boolean ok, boolean invalid, String reason) {
        static TenantCheckResult success() {
            return new TenantCheckResult(true, false, "");
        }

        static TenantCheckResult invalid(String reason) {
            return new TenantCheckResult(false, true, reason);
        }

        static TenantCheckResult failed(String reason) {
            return new TenantCheckResult(false, false, reason);
        }
    }
}
