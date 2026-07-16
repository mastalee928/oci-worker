package com.ociworker.service;

import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.mapper.ScheduledIpExecutionLockMapper;
import com.ociworker.mapper.ScheduledIpRunLogMapper;
import com.ociworker.mapper.ScheduledIpTaskMapper;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.entity.ScheduledIpRunLog;
import com.ociworker.model.entity.ScheduledIpTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledIpRunnerTest {
    @Mock private ScheduledIpTaskMapper taskMapper;
    @Mock private ScheduledIpRunLogMapper runLogMapper;
    @Mock private ScheduledIpExecutionLockMapper executionLockMapper;
    @Mock private OciUserMapper userMapper;
    @Mock private NetworkService networkService;
    @Mock private ScheduledIpDnsService dnsService;
    @Mock private NotificationService notificationService;

    private ScheduledIpRunner runner;

    @BeforeEach
    void setUp() {
        runner = new ScheduledIpRunner();
        ReflectionTestUtils.setField(runner, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(runner, "runLogMapper", runLogMapper);
        ReflectionTestUtils.setField(runner, "executionLockMapper", executionLockMapper);
        ReflectionTestUtils.setField(runner, "userMapper", userMapper);
        ReflectionTestUtils.setField(runner, "networkService", networkService);
        ReflectionTestUtils.setField(runner, "dnsService", dnsService);
        ReflectionTestUtils.setField(runner, "notificationService", notificationService);

        when(taskMapper.claimExecution(anyString(), anyString(), any(Date.class), any(Date.class))).thenReturn(1);
        when(taskMapper.updateById(any(ScheduledIpTask.class))).thenReturn(1);
        when(executionLockMapper.claimExecution(anyString(), anyString(), any(Date.class), any(Date.class))).thenReturn(1);
        when(executionLockMapper.claimEnabledTask(anyString(), anyString(), any(Date.class))).thenReturn(1);
        when(runLogMapper.insert(any(ScheduledIpRunLog.class))).thenReturn(1);
        when(runLogMapper.updateById(any(ScheduledIpRunLog.class))).thenReturn(1);
        when(userMapper.selectById("tenant-1")).thenReturn(new OciUser());
    }

    @Test
    void dnsFailureDoesNotRepeatIpChangeAndLongErrorUsesCooldown() {
        ScheduledIpTask task = task();
        task.setDnsEnabled(true);
        task.setDnsProvider("CF");
        task.setFqdn("api.example.com");
        when(taskMapper.selectById("task-1")).thenReturn(task);
        when(networkService.changePublicIpForScheduledTask(anyString(), anyString(), anyString(), any()))
                .thenReturn(new NetworkService.ChangePublicIpResult(
                        "198.51.100.1", "203.0.113.1", "vnic", "private", "compartment"));
        when(dnsService.sync(task, "203.0.113.1"))
                .thenThrow(new OciException("DNS失败" + "x".repeat(400)));

        runner.execute("task-1", "MANUAL");

        assertEquals("DNS_FAILED", task.getLastStatus());
        assertEquals("203.0.113.1", task.getCurrentPublicIp());
        verify(networkService, times(1)).changePublicIpForScheduledTask(anyString(), anyString(), anyString(), any());
        verify(notificationService, times(1)).sendMessage(anyString());

        runner.execute("task-1", "MANUAL");
        verify(notificationService, times(1)).sendMessage(anyString());
    }

    @Test
    void thirdIpFailureAutomaticallyPausesAndReleasesEnabledReservation() {
        ScheduledIpTask task = task();
        task.setConsecutiveFailures(2);
        task.setDnsEnabled(false);
        when(taskMapper.selectById("task-1")).thenReturn(task);
        when(networkService.changePublicIpForScheduledTask(anyString(), anyString(), anyString(), any()))
                .thenThrow(new OciException("测试换 IP 失败"));

        runner.execute("task-1", "SCHEDULED");

        assertFalse(task.getEnabled());
        assertEquals("AUTO_PAUSED", task.getLastStatus());
        verify(executionLockMapper).releaseEnabledTask(anyString(), anyString(), any(Date.class));
    }

    private static ScheduledIpTask task() {
        ScheduledIpTask task = new ScheduledIpTask();
        task.setId("task-1");
        task.setName("test-instance 换IP");
        task.setTenantConfigId("tenant-1");
        task.setTenantName("测试租户");
        task.setRegion("us-ashburn-1");
        task.setInstanceId("ocid1.instance.oc1.iad.test");
        task.setInstanceName("test-instance");
        task.setCompartmentId("compartment");
        task.setEnabled(true);
        task.setIntervalMinutes(10);
        task.setNextRunTime(new Date());
        task.setConsecutiveFailures(0);
        task.setNotifyIpFailure(true);
        task.setNotifyDnsFailure(true);
        task.setNotifyAutoPaused(true);
        task.setNotifySuccess(false);
        task.setCreateTime(new Date());
        task.setUpdateTime(new Date());
        return task;
    }
}
